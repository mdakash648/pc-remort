"""Persistent outbound WebSocket relay client + Direct P2P UDP coordinator for RemotePad PC Agent.

Connects to the user's VPS relay server so the Android app can control this PC
over the Internet (mobile data / external Wi-Fi) without port forwarding, reusing
the exact same LocalWebSocketServer input/media/power/volume dispatcher.

When the phone connects via the relay, both peers also negotiate an authenticated
Direct P2P UDP path via the VPS UDP reflector (port 8771) so mouse movement and
scrolling travel directly PC <-> Phone at near-local latency (~20-40 ms), while
automatically falling back to the VPS WebSocket relay if strict NAT blocks UDP.
"""

from __future__ import annotations

import asyncio
import json
import logging
import struct
import time
from typing import Any

import websockets

from core.device_manager import ConnectedDevice, DeviceManager
from core.p2p import P2PUdpEndpoint, parse_relay_host_and_udp_port
from core.p2p_file import P2PFileHandler, unpack_file_header
from core.protocol import HelloMessage, parse_message
from core.server import LocalWebSocketServer, _ClientSession, _configure_low_latency_socket
from features.file_transfer import FileTransferServer
from features.mic_receiver import MicReceiverService

logger = logging.getLogger(__name__)


class _RelayControllerWsAdapter:
    """Wraps the host relay WebSocket to route messages and binary frames to a specific controller."""

    def __init__(self, ws: Any, cid: str) -> None:
        self.ws = ws
        self.cid = cid
        self.remote_address = ("relay", cid)

    async def send(self, data: str | bytes) -> None:
        if isinstance(data, str):
            try:
                obj = json.loads(data)
                if isinstance(obj, dict):
                    obj["_to"] = self.cid
                    data = json.dumps(obj, separators=(",", ":"))
            except ValueError:
                pass
            await self.ws.send(data)
        elif isinstance(data, bytes):
            # Prepend RCID header: [4 bytes "RCID"][1 byte cid_len][cid_bytes][data]
            cid_bytes = self.cid.encode("utf-8")
            header = b"RCID" + struct.pack("B", len(cid_bytes)) + cid_bytes
            await self.ws.send(header + data)

    async def close(self, code: int = 1000, reason: str = "") -> None:
        payload = json.dumps(
            {"t": "disconnect_client", "clientId": self.cid},
            separators=(",", ":"),
        )
        try:
            await self.ws.send(payload)
        except Exception:
            pass


class RelayClient:
    """Maintains an outbound WebSocket connection to the VPS relay with auto-reconnect and P2P UDP."""

    def __init__(
        self,
        server: LocalWebSocketServer,
        relay_url: str,
        device_id: str,
        token: str,
        p2p_enabled: bool = True,
        relay_udp_port: int | None = None,
        file_server: FileTransferServer | None = None,
        device_manager: DeviceManager | None = None,
        mic_server: MicReceiverService | None = None,
    ) -> None:
        self.server = server
        self.relay_url = relay_url.strip()
        self.device_id = device_id.strip()
        self.token = token.strip()
        self.p2p_enabled = p2p_enabled
        self.relay_udp_port = relay_udp_port
        self.device_manager = device_manager
        self.mic_server = mic_server

        self._task: asyncio.Task[None] | None = None
        self._p2p_offer_task: asyncio.Task[None] | None = None
        self._stop_event = asyncio.Event()
        self._connected = False
        self._peer_online = False
        self._last_error: str | None = None
        self._sessions: dict[str, _ClientSession] = {}
        self._ws: Any | None = None

        self._file_handler: P2PFileHandler | None = (
            P2PFileHandler(file_server) if file_server is not None else None
        )
        self._p2p: P2PUdpEndpoint | None = (
            P2PUdpEndpoint(
                token=self.token,
                on_move=self._on_p2p_move,
                on_scroll=self._on_p2p_scroll,
                file_handler=self._file_handler,
                on_screen_ack=self._on_p2p_screen_ack,
            )
            if p2p_enabled
            else None
        )

    @property
    def is_connected(self) -> bool:
        return self._connected

    @property
    def is_peer_online(self) -> bool:
        return self._peer_online

    @property
    def last_error(self) -> str | None:
        return self._last_error

    @property
    def is_p2p_active(self) -> bool:
        return bool(self._p2p and self._p2p.is_active)

    def _on_p2p_move(self, dx: float, dy: float) -> None:
        self.server._smoother.enqueue(dx, dy)
        for session in list(self._sessions.values()):
            if session.screen_streamer is not None:
                session.screen_streamer.notify_input_activity()

    def _on_p2p_scroll(self, dx: float, dy: float) -> None:
        self.server._smoother.flush()
        self.server.mouse.scroll(dx, dy)
        for session in list(self._sessions.values()):
            if session.screen_streamer is not None:
                session.screen_streamer.notify_input_activity()

    async def _send_p2p_screen(self, wire_frame: bytes) -> bool:
        if self._p2p is None:
            return False
        return await self._p2p.send_screen_frame_paced(wire_frame)

    def _on_p2p_screen_ack(self, seq: int) -> None:
        for session in list(self._sessions.values()):
            if session.screen_streamer is not None:
                session.screen_streamer.on_ack(seq)

    async def start(self) -> None:
        if not self.relay_url or not self.device_id or len(self.token) < 8:
            logger.info("Relay client skipped (missing relay_url, device_id, or 8+ char token)")
            return
        if self._task is not None and not self._task.done():
            return
        self._stop_event.clear()
        self._task = asyncio.create_task(self._run_loop(), name="remotepad-relay-client")

    async def stop(self) -> None:
        self._stop_event.set()
        self._cancel_p2p_offer_task()
        if self._p2p is not None:
            await self._p2p.stop()
        task = self._task
        self._task = None
        if task is not None and not task.done():
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
        self._connected = False
        self._peer_online = False
        self._ws = None

    def _cancel_p2p_offer_task(self) -> None:
        task = self._p2p_offer_task
        self._p2p_offer_task = None
        if task is not None and not task.done():
            task.cancel()

    async def _send_disconnect_client(self, cid: str) -> None:
        ws = self._ws
        if ws is not None and self._connected:
            try:
                await ws.send(json.dumps({"t": "disconnect_client", "clientId": cid}, separators=(",", ":")))
            except Exception as exc:
                logger.debug("Failed sending disconnect_client to relay: %s", exc)

    async def _send_block_client(self, cid: str, name: str = "", ip: str = "") -> None:
        ws = self._ws
        if ws is not None and self._connected:
            try:
                await ws.send(json.dumps({
                    "t": "block_client",
                    "clientId": cid,
                    "name": name,
                    "ip": ip,
                }, separators=(",", ":")))
            except Exception as exc:
                logger.debug("Failed sending block_client to relay: %s", exc)

    async def _send_unblock_device(self, identifier: str) -> None:
        ws = self._ws
        if ws is not None and self._connected:
            try:
                await ws.send(json.dumps({
                    "t": "unblock_device",
                    "identifier": identifier,
                }, separators=(",", ":")))
            except Exception as exc:
                logger.debug("Failed sending unblock_device to relay: %s", exc)

    async def _send_binary_to_cid(self, ws: Any, cid: str, data: bytes) -> None:
        try:
            cid_bytes = cid.encode("utf-8")
            header = b"RCID" + struct.pack("B", len(cid_bytes)) + cid_bytes
            await ws.send(header + data)
        except Exception as exc:
            logger.debug("Failed sending binary to cid %s: %s", cid, exc)

    async def _run_loop(self) -> None:
        backoff = 2.0
        while not self._stop_event.is_set():
            try:
                await self._connect_once()
                backoff = 2.0
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                self._connected = False
                self._peer_online = False
                self._last_error = str(exc)
                logger.warning("Relay connection error (%s); retrying in %.0fs", exc, backoff)

            if self._stop_event.is_set():
                break
            try:
                await asyncio.wait_for(self._stop_event.wait(), timeout=backoff)
                break
            except asyncio.TimeoutError:
                backoff = min(backoff * 2.0, 30.0)

    async def _connect_once(self) -> None:
        parsed_stun = parse_relay_host_and_udp_port(self.relay_url, self.relay_udp_port)

        async with websockets.connect(
            self.relay_url,
            max_size=512 * 1024,
            compression=None,
            ping_interval=20,
            ping_timeout=20,
            open_timeout=10,
        ) as ws:
            _configure_low_latency_socket(ws)  # type: ignore[arg-type]
            self._ws = ws
            register_payload = json.dumps(
                {
                    "t": "relay_register",
                    "role": "host",
                    "deviceId": self.device_id,
                    "token": self.token,
                    "name": self.server.pc_name,
                },
                separators=(",", ":"),
            )
            await ws.send(register_payload)

            raw_ack = await asyncio.wait_for(ws.recv(), timeout=10.0)
            ack = json.loads(raw_ack if isinstance(raw_ack, str) else raw_ack.decode("utf-8"))
            if ack.get("t") != "relay_ready" or not ack.get("ok"):
                raise RuntimeError(f"Relay rejected registration: {ack}")

            self._connected = True
            self._last_error = None
            logger.info("Connected to VPS relay %s as '%s'", self.relay_url, self.device_id)

            async def send_ws_json(obj: dict[str, object]) -> None:
                try:
                    await ws.send(json.dumps(obj, separators=(",", ":")))
                except Exception as exc:
                    logger.debug("Relay WS signaling send failed: %s", exc)

            try:
                async for raw_message in ws:
                    if isinstance(raw_message, bytes):
                        # Unpack RCID prefixed binary frames
                        if raw_message.startswith(b"RCID") and len(raw_message) >= 5:
                            cid_len = raw_message[4]
                            if len(raw_message) >= 5 + cid_len:
                                cid = raw_message[5 : 5 + cid_len].decode("utf-8", errors="ignore")
                                payload = raw_message[5 + cid_len :]
                                if payload.startswith(b"RP2P"):
                                    if self._file_handler is not None:
                                        header = unpack_file_header(payload)
                                        if header is not None:
                                            pkt_type, flags, auth_tag, xfer_id, body = header
                                            self._file_handler.handle_packet(
                                                pkt_type,
                                                flags,
                                                auth_tag,
                                                xfer_id,
                                                body,
                                                lambda pkt, target_cid=cid: asyncio.create_task(
                                                    self._send_binary_to_cid(ws, target_cid, pkt)
                                                ),
                                            )
                                    continue
                                elif payload.startswith(b"RMIC"):
                                    if self.mic_server is not None:
                                        self.mic_server.feed_packet(payload)
                                    continue
                        try:
                            raw_message = raw_message.decode("utf-8")
                        except UnicodeDecodeError:
                            continue

                    # Handle relay signaling and input protocol dispatch
                    try:
                        ctrl = json.loads(raw_message)
                    except ValueError:
                        ctrl = None

                    if isinstance(ctrl, dict):
                        msg_type = ctrl.get("t")
                        if msg_type == "relay_device_connected":
                            cid = str(ctrl.get("cid") or ctrl.get("clientId") or "")
                            name = str(ctrl.get("name") or "Remote Phone")
                            ip = str(ctrl.get("ip") or "")
                            dev_id = f"relay:{cid}"
                            if self.device_manager is not None:
                                if self.device_manager.is_blocked(name=name, ip=ip, dev_id=dev_id):
                                    asyncio.create_task(self._send_block_client(cid, name, ip))
                                    continue

                                def disconnect_fn(target_cid=cid) -> None:
                                    asyncio.create_task(self._send_disconnect_client(target_cid))

                                dev = ConnectedDevice(
                                    id=dev_id,
                                    name=name,
                                    transport="VPS Relay",
                                    ip=ip,
                                    connected_at=time.time(),
                                    disconnect_fn=disconnect_fn,
                                    is_relay=True,
                                    relay_cid=cid,
                                )
                                if not self.device_manager.register_device(dev):
                                    asyncio.create_task(self._send_block_client(cid, name, ip))
                            continue

                        if msg_type == "relay_device_disconnected":
                            cid = str(ctrl.get("cid") or ctrl.get("clientId") or "")
                            dev_id = f"relay:{cid}"
                            if self.device_manager is not None:
                                self.device_manager.unregister_device(dev_id)
                            if cid in self._sessions:
                                old_sess = self._sessions.pop(cid)
                                await self._cleanup_session(old_sess)
                            continue

                        if msg_type == "relay_peer":
                            self._peer_online = bool(ctrl.get("online"))
                            if not self._peer_online:
                                for s in list(self._sessions.values()):
                                    await self._cleanup_session(s)
                                self._sessions.clear()
                            continue

                        if msg_type == "relay_error":
                            self._last_error = str(ctrl.get("message") or "Relay error")
                            logger.warning("Relay error: %s", ctrl.get("message"))
                            continue

                        if msg_type == "p2p_offer":
                            if self._p2p is not None and parsed_stun is not None:
                                self._cancel_p2p_offer_task()
                                relay_host, default_udp_port = parsed_stun
                                self._p2p_offer_task = asyncio.create_task(
                                    self._safe_handle_p2p_offer(
                                        ctrl,
                                        relay_host,
                                        default_udp_port,
                                        send_ws_json,
                                    ),
                                    name="remotepad-p2p-offer",
                                )
                            continue

                    try:
                        # Regular controller message dispatch
                        cid = (
                            str(ctrl.get("cid") or ctrl.get("clientId") or "default")
                            if isinstance(ctrl, dict)
                            else "default"
                        )
                        if cid not in self._sessions:
                            session = _ClientSession(
                                is_via_relay=True,
                                p2p_send_screen=self._send_p2p_screen,
                                is_p2p_active=lambda: self.is_p2p_active,
                            )
                            self._sessions[cid] = session
                        session = self._sessions[cid]

                        msg = parse_message(raw_message)
                        if msg is None:
                            continue

                        if isinstance(msg, HelloMessage):
                            dev_name = msg.device_name or "Remote Phone"
                            if self.device_manager is not None and self.device_manager.is_blocked(
                                name=dev_name, dev_id=f"relay:{cid}"
                            ):
                                asyncio.create_task(self._send_block_client(cid, dev_name))
                                continue
                            if self.device_manager is not None:
                                existing = self.device_manager.get_device(f"relay:{cid}")
                                if existing:
                                    if msg.device_name:
                                        existing.name = msg.device_name
                                        self.device_manager._notify()
                                else:
                                    def disconnect_fn(target_cid=cid) -> None:
                                        asyncio.create_task(self._send_disconnect_client(target_cid))
                                    dev = ConnectedDevice(
                                        id=f"relay:{cid}",
                                        name=dev_name,
                                        transport="VPS Relay",
                                        ip="",
                                        connected_at=time.time(),
                                        disconnect_fn=disconnect_fn,
                                        is_relay=True,
                                        relay_cid=cid,
                                    )
                                    self.device_manager.register_device(dev)

                        adapter = _RelayControllerWsAdapter(ws, cid)
                        await self.server._dispatch(adapter, msg, session, f"relay:{cid}")  # type: ignore[arg-type]
                    except Exception as exc:
                        logger.exception("Error dispatching relay controller message: %s", exc)
                        continue
            finally:
                self._connected = False
                self._peer_online = False
                self._ws = None
                for s in list(self._sessions.values()):
                    await self._cleanup_session(s)
                self._sessions.clear()

    async def _safe_handle_p2p_offer(
        self,
        offer: dict[str, object],
        relay_host: str,
        default_udp_port: int,
        send_ws_json: Any,
    ) -> None:
        if self._p2p is None:
            return
        try:
            await self._p2p.handle_offer(offer, relay_host, default_udp_port, send_ws_json)
        except asyncio.CancelledError:
            pass
        except Exception as exc:
            logger.debug("P2P offer handling failed (falling back to VPS relay): %s", exc)

    async def _cleanup_session(self, session: _ClientSession) -> None:
        self._cancel_p2p_offer_task()
        if self._p2p is not None:
            self._p2p.reset_peer()
        await self.server._stop_screen_stream(session)
        await self.server._stop_volume_sync(session)
        self.server._smoother.reset()
        for btn in list(session.held_buttons):
            self.server.mouse.button(btn, "up")
        session.held_buttons.clear()
        for key in list(session.held_keys):
            self.server.keyboard.key_event(key, "up")
        session.held_keys.clear()
        self.server.mouse.reset_accumulators()
