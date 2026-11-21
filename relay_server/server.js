/**
 * RemotePad Lightweight WebSocket Relay Server
 *
 * Bridges a paired Windows PC Agent (role="host") and Android Phone (role="controller")
 * over the Internet when not on the same LAN.
 *
 * Design goals:
 * - Zero disk I/O, tiny RAM footprint (~25-35 MB), safe alongside existing VPS websites.
 * - Constant-time SHA-256 token verification + per-IP auth failure rate limiting.
 * - TCP_NODELAY enabled + perMessageDeflate disabled for ultra-low mouse latency.
 */

import crypto from "node:crypto";
import dgram from "node:dgram";
import http from "node:http";
import { WebSocket, WebSocketServer } from "ws";

const PORT = Number(process.env.PORT || 8770);
const UDP_PORT = Number(process.env.UDP_PORT || 8771);
const HOST = process.env.HOST || "0.0.0.0";
const MAX_PAYLOAD_BYTES = 512 * 1024; // 512 KB (supports multi-paragraph paste)
const MAX_SCREEN_BUFFERED_BYTES = 64 * 1024; // Drop/coalesce stale RSCR screen frames when phone link is congested
const HEARTBEAT_INTERVAL_MS = 25_000;
const REGISTER_TIMEOUT_MS = 10_000;

// Rate limit auth failures per IP
const AUTH_WINDOW_MS = 5 * 60 * 1000;
const MAX_AUTH_FAILS = 15;
const authFailures = new Map(); // ip -> { count, resetAt }

// Active rooms keyed by deviceId:
// deviceId -> { hostWs, controllers: Map<string, { ws, name, ip, connectedAt }>, tokenHash: Buffer, hostName: string, blockedDevices: Set<string> }
const rooms = new Map();

function broadcastDeviceList(room) {
  if (!room) return;
  const list = [];
  for (const [cid, c] of room.controllers.entries()) {
    list.push({ id: cid, name: c.name, ip: c.ip, connectedAt: c.connectedAt });
  }
  const updateMsg = { t: "devices_update", count: list.length, devices: list };
  if (room.hostWs && room.hostWs.readyState === WebSocket.OPEN) {
    sendJson(room.hostWs, updateMsg);
  }
  for (const [cid, c] of room.controllers.entries()) {
    if (c.ws && c.ws.readyState === WebSocket.OPEN) {
      const userSpecificDevices = list.map((d) => ({ ...d, is_you: d.id === cid }));
      sendJson(c.ws, { t: "devices_update", count: list.length, devices: userSpecificDevices });
    }
  }
}

function sha256Buffer(text) {
  return crypto.createHash("sha256").update(text, "utf8").digest();
}

function tokensEqual(hashA, hashB) {
  if (!hashA || !hashB || hashA.length !== hashB.length) return false;
  return crypto.timingSafeEqual(hashA, hashB);
}

function getClientIp(req) {
  const fwd = req.headers["x-forwarded-for"];
  if (typeof fwd === "string" && fwd.trim()) {
    return fwd.split(",")[0].trim();
  }
  return req.socket?.remoteAddress || "unknown";
}

function isRateLimited(ip) {
  const now = Date.now();
  const entry = authFailures.get(ip);
  if (!entry) return false;
  if (now >= entry.resetAt) {
    authFailures.delete(ip);
    return false;
  }
  return entry.count >= MAX_AUTH_FAILS;
}

function recordAuthFailure(ip) {
  const now = Date.now();
  const entry = authFailures.get(ip);
  if (!entry || now >= entry.resetAt) {
    authFailures.set(ip, { count: 1, resetAt: now + AUTH_WINDOW_MS });
  } else {
    entry.count += 1;
  }
}

function safeSend(ws, data) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    try {
      ws.send(data);
    } catch {
      // Ignore send errors on closing sockets
    }
  }
}

function sendJson(ws, obj) {
  safeSend(ws, JSON.stringify(obj));
}

function isScreenFrameBuffer(raw) {
  return (
    Buffer.isBuffer(raw) &&
    raw.length >= 20 &&
    raw[0] === 0x52 && // 'R'
    raw[1] === 0x53 && // 'S'
    raw[2] === 0x43 && // 'C'
    raw[3] === 0x52 // 'R'
  );
}

function forwardScreenFrame(peer, raw) {
  if (!peer || peer.readyState !== WebSocket.OPEN) return;
  // Latest-frame-wins backpressure: if previous frame is still flushing over a slow mobile link,
  // hold ONLY the newest frame instead of piling up multi-second bufferbloat on the VPS.
  if (peer.screenSendInFlight || peer.bufferedAmount > MAX_SCREEN_BUFFERED_BYTES) {
    peer.pendingScreenFrame = raw;
    return;
  }
  peer.screenSendInFlight = true;
  try {
    peer.send(raw, { binary: true }, () => {
      peer.screenSendInFlight = false;
      const next = peer.pendingScreenFrame;
      if (next && peer.readyState === WebSocket.OPEN) {
        peer.pendingScreenFrame = null;
        forwardScreenFrame(peer, next);
      }
    });
  } catch {
    peer.screenSendInFlight = false;
    peer.pendingScreenFrame = null;
  }
}

function isValidDeviceId(id) {
  return typeof id === "string" && /^[a-zA-Z0-9._-]{3,64}$/.test(id);
}

function isValidToken(token) {
  return typeof token === "string" && token.length >= 8 && token.length <= 128;
}

const server = http.createServer((req, res) => {
  if (req.method === "GET" && req.url === "/health") {
    const body = JSON.stringify({
      ok: true,
      service: "remotepad-relay",
      rooms: rooms.size,
      udp_stun_port: UDP_PORT,
      uptime_s: Math.floor(process.uptime()),
    });
    res.writeHead(200, {
      "Content-Type": "application/json; charset=utf-8",
      "Content-Length": Buffer.byteLength(body),
    });
    res.end(body);
    return;
  }
  res.writeHead(404, { "Content-Type": "text/plain" });
  res.end("Not found");
});

const wss = new WebSocketServer({
  server,
  maxPayload: MAX_PAYLOAD_BYTES,
  perMessageDeflate: false,
});

wss.on("connection", (ws, req) => {
  // Disable Nagle's algorithm for crisp real-time mouse cursor movement
  req.socket?.setNoDelay(true);

  const ip = getClientIp(req);
  if (isRateLimited(ip)) {
    sendJson(ws, { t: "relay_error", code: "rate_limited", message: "Too many failed attempts" });
    ws.close(4029, "Rate limited");
    return;
  }

  ws.isAlive = true;
  ws.registered = false;
  ws.role = null;
  ws.deviceId = null;

  ws.on("pong", () => {
    ws.isAlive = true;
  });

  const regTimer = setTimeout(() => {
    if (!ws.registered && ws.readyState === WebSocket.OPEN) {
      sendJson(ws, { t: "relay_error", code: "timeout", message: "Registration timeout" });
      ws.close(4008, "Registration timeout");
    }
  }, REGISTER_TIMEOUT_MS);

  ws.on("message", (raw, isBinary) => {
    if (!ws.registered) {
      clearTimeout(regTimer);
      handleRegister(ws, raw, ip);
      return;
    }

    const room = rooms.get(ws.deviceId);
    if (!room) return;

    if (ws.role === "host") {
      // Host sending to controller(s)
      if (isBinary) {
        if (isScreenFrameBuffer(raw)) {
          // Broadcast screen frame to all controllers
          for (const c of room.controllers.values()) {
            forwardScreenFrame(c.ws, raw);
          }
          return;
        }
        // Check if prefixed with RCID header: [4 bytes "RCID"][1 byte cid_len][cid_bytes][payload]
        if (
          raw.length >= 5 &&
          raw[0] === 0x52 && // 'R'
          raw[1] === 0x43 && // 'C'
          raw[2] === 0x49 && // 'I'
          raw[3] === 0x44    // 'D'
        ) {
          const cidLen = raw[4];
          if (raw.length >= 5 + cidLen) {
            const targetCid = raw.toString("utf8", 5, 5 + cidLen);
            const payload = raw.subarray(5 + cidLen);
            const target = room.controllers.get(targetCid);
            if (target && target.ws.readyState === WebSocket.OPEN) {
              if (isScreenFrameBuffer(payload)) {
                forwardScreenFrame(target.ws, payload);
              } else {
                safeSend(target.ws, payload);
              }
            }
            return;
          }
        }
        // General binary broadcast
        for (const c of room.controllers.values()) {
          safeSend(c.ws, raw);
        }
      } else {
        const text = raw.toString("utf8");
        let parsed = null;
        try {
          parsed = JSON.parse(text);
        } catch {}

        if (parsed && typeof parsed === "object") {
          // Host control commands
          if (parsed.t === "disconnect_client") {
            const target = room.controllers.get(parsed.clientId);
            if (target) {
              sendJson(target.ws, { t: "relay_error", code: "disconnected_by_host", message: "Disconnected by PC" });
              try { target.ws.close(4000, "Disconnected by host"); } catch {}
              room.controllers.delete(parsed.clientId);
              broadcastDeviceList(room);
            }
            return;
          }
          if (parsed.t === "block_client") {
            const target = room.controllers.get(parsed.clientId);
            if (parsed.name) room.blockedDevices.add(parsed.name.toLowerCase());
            if (parsed.ip) room.blockedDevices.add(parsed.ip);
            if (parsed.clientId) room.blockedDevices.add(parsed.clientId);
            if (target) {
              if (target.name) room.blockedDevices.add(target.name.toLowerCase());
              if (target.ip) room.blockedDevices.add(target.ip);
              sendJson(target.ws, { t: "relay_error", code: "blocked", message: "Device has been blocked by host PC" });
              try { target.ws.close(4003, "Blocked by host"); } catch {}
              room.controllers.delete(parsed.clientId);
            }
            broadcastDeviceList(room);
            return;
          }
          if (parsed.t === "unblock_device") {
            if (parsed.identifier) {
              room.blockedDevices.delete(parsed.identifier);
              room.blockedDevices.delete(parsed.identifier.toLowerCase());
            }
            return;
          }
          if (parsed._to && parsed._to !== "all") {
            // Targeted to single controller
            const target = room.controllers.get(parsed._to);
            if (target && target.ws.readyState === WebSocket.OPEN) {
              safeSend(target.ws, text);
            }
            return;
          }
        }
        // Broadcast to all controllers
        for (const c of room.controllers.values()) {
          safeSend(c.ws, text);
        }
      }
    } else {
      // Controller sending to host
      const hostWs = room.hostWs;
      if (!hostWs || hostWs.readyState !== WebSocket.OPEN) return;

      if (isBinary) {
        // Prefix with RCID: [4 bytes "RCID"][1 byte cid_len][cid_bytes][payload]
        const cidBuf = Buffer.from(ws.clientId || "", "utf8");
        const header = Buffer.alloc(5 + cidBuf.length);
        header.write("RCID", 0, 4, "ascii");
        header.writeUInt8(cidBuf.length, 4);
        cidBuf.copy(header, 5);
        const wrapped = Buffer.concat([header, raw]);
        safeSend(hostWs, wrapped);
      } else {
        const text = raw.toString("utf8");
        let parsed = null;
        try {
          parsed = JSON.parse(text);
        } catch {}
        if (parsed && typeof parsed === "object") {
          parsed.cid = ws.clientId;
          safeSend(hostWs, JSON.stringify(parsed));
        } else {
          safeSend(hostWs, text);
        }
      }
    }
  });

  ws.on("close", () => {
    clearTimeout(regTimer);
    cleanupConnection(ws);
  });

  ws.on("error", () => {
    clearTimeout(regTimer);
    cleanupConnection(ws);
  });
});

function handleRegister(ws, raw, ip) {
  let msg;
  try {
    msg = JSON.parse(raw.toString("utf8"));
  } catch {
    sendJson(ws, { t: "relay_error", code: "bad_json", message: "Invalid JSON" });
    ws.close(4000, "Invalid JSON");
    return;
  }

  if (!msg || msg.t !== "relay_register") {
    sendJson(ws, { t: "relay_error", code: "expected_register", message: "Expected relay_register" });
    ws.close(4001, "Expected relay_register");
    return;
  }

  const { role, deviceId, token, name } = msg;
  if (role !== "host" && role !== "controller") {
    sendJson(ws, { t: "relay_error", code: "invalid_role", message: "Invalid role" });
    ws.close(4002, "Invalid role");
    return;
  }

  if (!isValidDeviceId(deviceId) || !isValidToken(token)) {
    recordAuthFailure(ip);
    sendJson(ws, {
      t: "relay_error",
      code: "invalid_credentials",
      message: "Device ID (3-64 chars) and Token (8+ chars) required",
    });
    ws.close(4003, "Invalid credentials");
    return;
  }

  const tokenHash = sha256Buffer(token);

  if (role === "host") {
    const existing = rooms.get(deviceId);
    if (existing) {
      if (existing.hostWs && existing.hostWs !== ws) {
        try {
          existing.hostWs.close(4009, "Replaced by new PC connection");
        } catch {
          // ignore
        }
      }
      // If token changed, kick controllers with old token
      if (!tokensEqual(existing.tokenHash, tokenHash)) {
        for (const c of existing.controllers.values()) {
          try {
            c.ws.close(4003, "Token updated");
          } catch {}
        }
        existing.controllers.clear();
      }
      existing.hostWs = ws;
      existing.tokenHash = tokenHash;
      existing.hostName = typeof name === "string" && name.trim() ? name.trim().slice(0, 64) : deviceId;
    } else {
      rooms.set(deviceId, {
        hostWs: ws,
        controllers: new Map(),
        blockedDevices: new Set(),
        tokenHash,
        hostName: typeof name === "string" && name.trim() ? name.trim().slice(0, 64) : deviceId,
      });
    }

    ws.registered = true;
    ws.role = "host";
    ws.deviceId = deviceId;
    sendJson(ws, { t: "relay_ready", ok: true, role: "host", deviceId });
    console.log(`[relay] host registered: ${deviceId} (${rooms.get(deviceId).hostName}) from ${ip}`);
    broadcastDeviceList(rooms.get(deviceId));
    return;
  }

  // role === "controller"
  const room = rooms.get(deviceId);
  if (!room || !room.hostWs || room.hostWs.readyState !== WebSocket.OPEN) {
    sendJson(ws, { t: "relay_error", code: "host_offline", message: "PC is offline" });
    ws.close(4004, "PC is offline");
    return;
  }

  if (!tokensEqual(room.tokenHash, tokenHash)) {
    recordAuthFailure(ip);
    sendJson(ws, { t: "relay_error", code: "invalid_token", message: "Wrong token" });
    ws.close(4003, "Invalid token");
    return;
  }

  const clientName = typeof name === "string" && name.trim() ? name.trim().slice(0, 64) : "Phone";
  const reqClientId = typeof msg.clientId === "string" && msg.clientId.trim() ? msg.clientId.trim().slice(0, 64) : null;
  const clientId = reqClientId || `c_${crypto.randomBytes(6).toString("hex")}`;

  // Check if blocked
  if (
    room.blockedDevices.has(clientName.toLowerCase()) ||
    room.blockedDevices.has(ip) ||
    room.blockedDevices.has(clientId)
  ) {
    sendJson(ws, { t: "relay_error", code: "blocked", message: "Device has been blocked by host PC" });
    ws.close(4003, "Blocked");
    return;
  }

  // If same clientId is already in room, close previous connection cleanly
  if (room.controllers.has(clientId)) {
    const prev = room.controllers.get(clientId);
    if (prev && prev.ws !== ws) {
      try { prev.ws.close(4009, "Replaced by reconnecting device"); } catch {}
    }
  }

  ws.registered = true;
  ws.role = "controller";
  ws.deviceId = deviceId;
  ws.clientId = clientId;
  ws.clientName = clientName;
  ws.clientIp = ip;
  ws.connectedAt = Date.now();

  room.controllers.set(clientId, {
    ws,
    name: clientName,
    ip,
    connectedAt: ws.connectedAt,
  });

  sendJson(ws, {
    t: "relay_ready",
    ok: true,
    role: "controller",
    deviceId,
    clientId,
    name: room.hostName,
    udpPort: UDP_PORT,
  });

  sendJson(room.hostWs, {
    t: "relay_device_connected",
    clientId,
    cid: clientId,
    name: clientName,
    ip,
    connectedAt: ws.connectedAt,
  });
  sendJson(room.hostWs, { t: "relay_peer", online: true, count: room.controllers.size });
  broadcastDeviceList(room);
  console.log(`[relay] controller joined: ${deviceId} (client=${clientId}, name=${clientName}) from ${ip}`);
}

function cleanupConnection(ws) {
  ws.pendingScreenFrame = null;
  ws.screenSendInFlight = false;
  if (!ws.registered || !ws.deviceId) return;
  const room = rooms.get(ws.deviceId);
  if (!room) return;

  if (ws.role === "host" && room.hostWs === ws) {
    room.hostWs = null;
    for (const c of room.controllers.values()) {
      if (c.ws && c.ws.readyState === WebSocket.OPEN) {
        sendJson(c.ws, { t: "relay_peer", online: false });
        try {
          c.ws.close(1001, "PC disconnected");
        } catch {
          // ignore
        }
      }
    }
    room.controllers.clear();
    rooms.delete(ws.deviceId);
    console.log(`[relay] host disconnected: ${ws.deviceId}`);
  } else if (ws.role === "controller" && ws.clientId && room.controllers.has(ws.clientId)) {
    room.controllers.delete(ws.clientId);
    if (room.hostWs && room.hostWs.readyState === WebSocket.OPEN) {
      sendJson(room.hostWs, { t: "relay_device_disconnected", clientId: ws.clientId, cid: ws.clientId });
      sendJson(room.hostWs, { t: "relay_peer", online: room.controllers.size > 0, count: room.controllers.size });
    }
    broadcastDeviceList(room);
    console.log(`[relay] controller disconnected: ${ws.deviceId} (client=${ws.clientId})`);
  }
}

// Keepalive ping to detect and clean up dead TCP sockets
const heartbeat = setInterval(() => {
  const now = Date.now();
  for (const [ip, entry] of authFailures.entries()) {
    if (now >= entry.resetAt) authFailures.delete(ip);
  }
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    try {
      ws.ping();
    } catch {
      ws.terminate();
    }
  }
}, HEARTBEAT_INTERVAL_MS);

wss.on("close", () => clearInterval(heartbeat));

server.listen(PORT, HOST, () => {
  console.log(`[remotepad-relay] listening on ws://${HOST}:${PORT} (health: /health)`);
});

// Lightweight UDP endpoint reflector (STUN-like) for Direct P2P UDP Hole Punching.
// Completely stateless: echoes back the caller's observed public IP:port so PC and Phone
// can punch a direct low-latency UDP path without relaying mouse traffic through the VPS.
const udpReflector = dgram.createSocket("udp4");

udpReflector.on("error", (err) => {
  console.error(`[remotepad-p2p-stun] UDP error: ${err.message}`);
});

udpReflector.on("message", (buf, rinfo) => {
  if (buf.length === 0 || buf.length > 512) return;
  let msg;
  try {
    msg = JSON.parse(buf.toString("utf8"));
  } catch {
    return;
  }
  if (!msg || msg.t !== "p2p_stun") return;
  const nonce = typeof msg.nonce === "string" ? msg.nonce.slice(0, 64) : "";
  const reply = Buffer.from(
    JSON.stringify({
      t: "p2p_stun_res",
      ip: rinfo.address,
      port: rinfo.port,
      nonce,
    }),
    "utf8",
  );
  udpReflector.send(reply, rinfo.port, rinfo.address, () => {
    // Ignore transient send errors
  });
});

udpReflector.bind(UDP_PORT, HOST, () => {
  console.log(`[remotepad-p2p-stun] UDP reflector listening on ${HOST}:${UDP_PORT}`);
});
