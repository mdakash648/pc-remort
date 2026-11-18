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
// deviceId -> { hostWs, controllerWs, tokenHash: Buffer, hostName: string }
const rooms = new Map();

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

    const peer = ws.role === "host" ? room.controllerWs : room.hostWs;
    if (peer && peer.readyState === WebSocket.OPEN) {
      if (isBinary && ws.role === "host" && isScreenFrameBuffer(raw)) {
        forwardScreenFrame(peer, raw);
      } else {
        safeSend(peer, isBinary ? raw : raw.toString("utf8"));
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
      // If token changed, kick any old controller so mismatched tokens cannot linger
      if (existing.controllerWs && !tokensEqual(existing.tokenHash, tokenHash)) {
        try {
          existing.controllerWs.close(4003, "Token updated");
        } catch {
          // ignore
        }
        existing.controllerWs = null;
      }
      existing.hostWs = ws;
      existing.tokenHash = tokenHash;
      existing.hostName = typeof name === "string" && name.trim() ? name.trim().slice(0, 64) : deviceId;
    } else {
      rooms.set(deviceId, {
        hostWs: ws,
        controllerWs: null,
        tokenHash,
        hostName: typeof name === "string" && name.trim() ? name.trim().slice(0, 64) : deviceId,
      });
    }

    ws.registered = true;
    ws.role = "host";
    ws.deviceId = deviceId;
    sendJson(ws, { t: "relay_ready", ok: true, role: "host", deviceId });
    console.log(`[relay] host registered: ${deviceId} (${rooms.get(deviceId).hostName}) from ${ip}`);
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

  if (room.controllerWs && room.controllerWs !== ws) {
    try {
      room.controllerWs.close(4009, "Replaced by new phone connection");
    } catch {
      // ignore
    }
  }

  room.controllerWs = ws;
  ws.registered = true;
  ws.role = "controller";
  ws.deviceId = deviceId;

  sendJson(ws, {
    t: "relay_ready",
    ok: true,
    role: "controller",
    deviceId,
    name: room.hostName,
    udpPort: UDP_PORT,
  });
  sendJson(room.hostWs, { t: "relay_peer", online: true });
  console.log(`[relay] controller joined: ${deviceId} from ${ip}`);
}

function cleanupConnection(ws) {
  ws.pendingScreenFrame = null;
  ws.screenSendInFlight = false;
  if (!ws.registered || !ws.deviceId) return;
  const room = rooms.get(ws.deviceId);
  if (!room) return;

  if (ws.role === "host" && room.hostWs === ws) {
    room.hostWs = null;
    if (room.controllerWs && room.controllerWs.readyState === WebSocket.OPEN) {
      sendJson(room.controllerWs, { t: "relay_peer", online: false });
      try {
        room.controllerWs.close(1001, "PC disconnected");
      } catch {
        // ignore
      }
    }
    rooms.delete(ws.deviceId);
    console.log(`[relay] host disconnected: ${ws.deviceId}`);
  } else if (ws.role === "controller" && room.controllerWs === ws) {
    room.controllerWs = null;
    if (room.hostWs && room.hostWs.readyState === WebSocket.OPEN) {
      sendJson(room.hostWs, { t: "relay_peer", online: false });
    }
    console.log(`[relay] controller disconnected: ${ws.deviceId}`);
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
