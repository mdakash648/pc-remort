"""Local WebSocket server for RemotePad PC Agent."""

from __future__ import annotations

import asyncio
import ctypes
import json
import logging
import socket
import sys
import time
from typing import Any

import websockets
from websockets.asyncio.server import Server, ServerConnection

from config import AgentConfig
from core.device_manager import ConnectedDevice, DeviceManager
from core.p2p_file import P2PFileHandler, unpack_file_header
from core.protocol import (
    ButtonMessage,
    ClickMessage,
    GestureMessage,
    HelloMessage,
    KeyComboMessage,
    KeyEventMessage,
    MediaMessage,
    MoveAbsMessage,
    MoveMessage,
    PasteMessage,
    PingMessage,
    PowerMessage,
    ScreenAckMessage,
    ScreenConfigMessage,
    ScreenStartMessage,
    ScreenStopMessage,
    ScrollMessage,
    TextMessage,
    UnknownMessage,
    VolumeSetMessage,
    VolumeSyncMessage,
    encode_hello_ack,
    encode_pong,
    encode_vol_state,
    parse_message,
)
from features.gestures import GestureHandler
from features.mic_receiver import MicReceiverService
from features.screen_capture import ScreenStreamer, WindowsScreenCapturer
from input.keyboard import KeyboardController
from input.media import MediaController
from input.mouse import CursorSmoother, MouseController
from input.power import PowerController
from input.volume import VolumeController

logger = logging.getLogger(__name__)

_SIO_TCP_SET_ACK_FREQUENCY = 0x98000017
_ws2_32 = ctypes.WinDLL("ws2_32") if hasattr(ctypes, "WinDLL") and sys.platform == "win32" else None


def _configure_low_latency_socket(ws: ServerConnection) -> None:
    """Disable Nagle's algorithm and Windows Delayed ACK on the client TCP socket."""
    transport = getattr(ws, "transport", None)
    if transport is None:
        return
    sock = transport.get_extra_info("socket")
    if sock is None:
        return
    try:
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    except (OSError, AttributeError):
        pass
    if _ws2_32 is not None:
        try:
            fd = sock.fileno()
            if fd >= 0:
                freq = ctypes.c_uint32(1)
                returned = ctypes.c_uint32(0)
                _ws2_32.WSAIoctl(
                    ctypes.c_size_t(fd),
                    ctypes.c_uint32(_SIO_TCP_SET_ACK_FREQUENCY),
                    ctypes.byref(freq),
                    ctypes.sizeof(freq),
                    None,
                    0,
                    ctypes.byref(returned),
                    None,
                    None,
                )
        except (OSError, AttributeError, ValueError):
            pass


def get_local_ipv4_addresses() -> list[str]:
    """Return likely LAN IPv4 addresses of this PC for easy connection."""
    addresses: list[str] = []
    # Primary outbound interface IP (does not actually send packets)
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            primary_ip = sock.getsockname()[0]
            if primary_ip and not primary_ip.startswith("127."):
                addresses.append(primary_ip)
    except OSError:
        pass

    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            ip = str(info[4][0])
            if ip and not ip.startswith("127.") and ip not in addresses:
                addresses.append(ip)
    except OSError:
        pass

    return addresses or ["127.0.0.1"]


class _ClientSession:
    __slots__ = (
        "held_buttons",
        "held_keys",
        "vol_sync_enabled",
        "vol_sync_task",
        "last_vol_state",
        "screen_streamer",
        "screen_seq",
        "is_via_relay",
        "p2p_send_screen",
        "is_p2p_active",
    )

    def __init__(
        self,
        *,
        is_via_relay: bool = False,
        p2p_send_screen: Any | None = None,
        is_p2p_active: Any | None = None,
    ) -> None:
        self.held_buttons: set[str] = set()
        self.held_keys: set[str] = set()
        self.vol_sync_enabled: bool = False
        self.vol_sync_task: asyncio.Task[None] | None = None
        self.last_vol_state: tuple[int, bool] | None = None
        self.screen_streamer: ScreenStreamer | None = None
        self.screen_seq: int = 0
        self.is_via_relay: bool = is_via_relay
        self.p2p_send_screen = p2p_send_screen
        self.is_p2p_active = is_p2p_active


class LocalWebSocketServer:
    """Handles incoming local WebSocket connections and dispatches input commands."""

    _VOL_SYNC_INTERVAL_S = 0.12

    def __init__(
        self,
        config: AgentConfig,
        mouse: MouseController,
        keyboard: KeyboardController | None = None,
        gestures: GestureHandler | None = None,
        media: MediaController | None = None,
        power: PowerController | None = None,
        volume: VolumeController | None = None,
        file_port: int | None = None,
        mic_port: int | None = None,
        mic_device_provider: Any | None = None,
        device_manager: DeviceManager | None = None,
        file_handler: P2PFileHandler | None = None,
        mic_server: MicReceiverService | None = None,
    ) -> None:
        self.config = config
        self.mouse = mouse
        self.keyboard = keyboard or KeyboardController()
        self.gestures = gestures or GestureHandler(self.keyboard, config.gesture_map)
        self.media = media or MediaController(self.keyboard)
        self.power = power or PowerController(enabled=True)
        self.volume = volume or VolumeController()
        self.file_port = file_port
        self.mic_port = mic_port
        self._mic_device_provider = mic_device_provider
        self._device_manager = device_manager
        self._file_handler = file_handler
        self._mic_server = mic_server
        self._screen_capturer = WindowsScreenCapturer()
        self._smoother = CursorSmoother(
            mouse=self.mouse,
            enabled=config.cursor_smoothing,
            tau_ms=config.cursor_smoothing_ms,
        )
        self.pc_name = socket.gethostname()
        self._server: Server | None = None
        self._clients: set[ServerConnection] = set()

    @property
    def port(self) -> int:
        if self._server and self._server.sockets:
            return int(self._server.sockets[0].getsockname()[1])
        return self.config.port

    async def broadcast_json_async(self, payload: dict[str, Any]) -> None:
        """Broadcast a JSON payload to all connected local WebSocket clients."""
        text = json.dumps(payload, separators=(",", ":"))
        coros = [ws.send(text) for ws in list(self._clients)]
        if coros:
            await asyncio.gather(*coros, return_exceptions=True)

    def broadcast_json(self, payload: dict[str, Any]) -> None:
        """Schedule an async broadcast of JSON payload to all local clients."""
        try:
            loop = asyncio.get_running_loop()
            loop.create_task(self.broadcast_json_async(payload))
        except RuntimeError:
            pass

    async def start(self) -> None:
        self._smoother.start()
        self._server = await websockets.serve(
            self._handle_client,
            self.config.host,
            self.config.port,
            max_size=512 * 1024,
            compression=None,
            ping_interval=20,
            ping_timeout=20,
        )
        logger.info("Local WebSocket server listening on %s:%d", self.config.host, self.port)

    async def stop(self) -> None:
        self._smoother.stop()
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()
            self._server = None
            logger.info("Local WebSocket server stopped")

    async def serve_forever(self) -> None:
        await self.start()
        assert self._server is not None
        await self._server.serve_forever()

    async def _handle_client(self, ws: ServerConnection) -> None:
        _configure_low_latency_socket(ws)
        peer = _format_peer(ws.remote_address)
        peer_ip = ws.remote_address[0] if isinstance(ws.remote_address, tuple) and ws.remote_address else ""
        dev_id = f"lan:{peer}"

        if self._device_manager and self._device_manager.is_blocked(ip=peer_ip, dev_id=dev_id):
            logger.warning("Rejecting connection from blocked IP %s (%s)", peer_ip, dev_id)
            await ws.close(4003, "Blocked by host")
            return

        self._clients.add(ws)
        session = _ClientSession()
        logger.info("Client connected: %s (active=%d)", peer, len(self._clients))
        device_registered = False

        try:
            async for raw_message in ws:
                if isinstance(raw_message, bytes):
                    if raw_message.startswith(b"RP2P"):
                        if self._file_handler is not None:
                            header = unpack_file_header(raw_message)
                            if header is not None:
                                pkt_type, flags, auth_tag, xfer_id, body = header
                                self._file_handler.handle_packet(
                                    pkt_type,
                                    flags,
                                    auth_tag,
                                    xfer_id,
                                    body,
                                    lambda pkt: asyncio.create_task(ws.send(pkt)),
                                )
                        continue
                    elif raw_message.startswith(b"RMIC"):
                        if self._mic_server is not None:
                            self._mic_server.feed_packet(raw_message)
                        continue
                    else:
                        try:
                            raw_message = raw_message.decode("utf-8")
                        except UnicodeDecodeError:
                            logger.warning("Ignoring invalid UTF-8 frame from %s", peer)
                            continue

                msg = parse_message(raw_message)
                if msg is None:
                    logger.warning("Ignoring malformed message from %s", peer)
                    continue

                if isinstance(msg, HelloMessage):
                    dev_name = msg.device_name or f"Device ({peer})"
                    if self._device_manager and self._device_manager.is_blocked(name=dev_name, ip=peer_ip, dev_id=dev_id):
                        logger.warning("Rejecting blocked device %s (%s)", dev_name, peer)
                        await ws.close(4003, "Blocked by host")
                        return

                    def close_client() -> None:
                        asyncio.create_task(ws.close(4008, "Disconnected by host"))

                    dev = ConnectedDevice(
                        id=dev_id,
                        name=dev_name,
                        transport="Local Wi-Fi",
                        ip=peer_ip,
                        connected_at=time.time(),
                        disconnect_fn=close_client,
                        is_relay=False,
                    )
                    if self._device_manager:
                        if not self._device_manager.register_device(dev):
                            await ws.close(4003, "Blocked by host")
                            return
                        device_registered = True

                await self._dispatch(ws, msg, session, peer)
        except websockets.ConnectionClosed as exc:
            logger.info("Client disconnected (%s): code=%s", peer, exc.code)
        except Exception:
            logger.exception("Unexpected error in client handler for %s", peer)
        finally:
            if device_registered and self._device_manager:
                self._device_manager.unregister_device(dev_id)
            await self._stop_screen_stream(session)
            await self._stop_volume_sync(session)
            self._smoother.reset()
            # Never leave mouse buttons or modifier keys stuck down on disconnect
            for btn in list(session.held_buttons):
                self.mouse.button(btn, "up")
            session.held_buttons.clear()
            for key in list(session.held_keys):
                self.keyboard.key_event(key, "up")
            session.held_keys.clear()
            self._clients.discard(ws)
            self.mouse.reset_accumulators()
            logger.info("Client cleaned up: %s (active=%d)", peer, len(self._clients))

    async def _start_volume_sync(self, ws: ServerConnection, session: _ClientSession) -> None:
        session.vol_sync_enabled = True
        session.last_vol_state = None
        await self._push_volume_state(ws, session, force=True)
        if session.vol_sync_task is None or session.vol_sync_task.done():
            session.vol_sync_task = asyncio.create_task(self._volume_sync_loop(ws, session))

    async def _stop_volume_sync(self, session: _ClientSession) -> None:
        session.vol_sync_enabled = False
        session.last_vol_state = None
        task = session.vol_sync_task
        session.vol_sync_task = None
        if task is not None and not task.done():
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass

    async def _volume_sync_loop(self, ws: ServerConnection, session: _ClientSession) -> None:
        try:
            while session.vol_sync_enabled:
                await asyncio.sleep(self._VOL_SYNC_INTERVAL_S)
                if not session.vol_sync_enabled:
                    break
                await self._push_volume_state(ws, session, force=False)
        except (asyncio.CancelledError, websockets.ConnectionClosed):
            pass

    async def _push_volume_state(
        self,
        ws: ServerConnection,
        session: _ClientSession,
        state: tuple[float, bool] | None = None,
        force: bool = False,
    ) -> None:
        resolved = state if state is not None else self.volume.get_state()
        if resolved is None:
            return
        level, muted = resolved
        quantized = (int(round(level * 100)), muted)
        if not force and session.last_vol_state == quantized:
            return
        session.last_vol_state = quantized
        await ws.send(encode_vol_state(level, muted))

    async def _start_screen_stream(
        self,
        ws: ServerConnection,
        session: _ClientSession,
        msg: ScreenStartMessage,
    ) -> None:
        if session.screen_streamer is None:
            async def send_binary(frame_bytes: bytes) -> None:
                await ws.send(frame_bytes)

            session.screen_streamer = ScreenStreamer(
                send_ws_binary=send_binary,
                capturer=self._screen_capturer,
                send_p2p_frame=session.p2p_send_screen,
                is_p2p_active=session.is_p2p_active,
                is_via_relay=session.is_via_relay,
                initial_seq=session.screen_seq,
            )
        await session.screen_streamer.start(
            quality=msg.quality,
            fps=msg.fps,
            max_width=msg.max_width,
            monitor=msg.monitor,
            prefer_p2p=msg.prefer_p2p,
            hide_cursor=msg.hide_cursor,
            roi=msg.roi,
        )

    async def _stop_screen_stream(self, session: _ClientSession) -> None:
        streamer = session.screen_streamer
        session.screen_streamer = None
        if streamer is not None:
            session.screen_seq = streamer.last_seq
            await streamer.stop()

    async def _dispatch(
        self,
        ws: ServerConnection,
        msg: Any,
        session: _ClientSession,
        peer: str,
    ) -> None:
        if isinstance(msg, MoveMessage):
            self._smoother.enqueue(msg.dx, msg.dy)
            if session.screen_streamer is not None:
                session.screen_streamer.notify_input_activity()
        elif isinstance(msg, MoveAbsMessage):
            self._smoother.flush()
            mon_rect = (
                session.screen_streamer.get_monitor_rect(msg.monitor)
                if session.screen_streamer is not None
                else self._screen_capturer.get_monitor_rect(msg.monitor)
            )
            self.mouse.move_absolute(msg.x, msg.y, monitor_rect=mon_rect)
            if session.screen_streamer is not None:
                session.screen_streamer.notify_input_activity()
        elif isinstance(msg, ButtonMessage):
            self._smoother.flush()
            self.mouse.button(msg.button, msg.state)
            if msg.state == "down":
                session.held_buttons.add(msg.button)
            else:
                session.held_buttons.discard(msg.button)
            if session.screen_streamer is not None:
                session.screen_streamer.notify_input_activity()
        elif isinstance(msg, ClickMessage):
            self._smoother.flush()
            self.mouse.click(msg.button, msg.count)
            if session.screen_streamer is not None:
                session.screen_streamer.notify_input_activity()
        elif isinstance(msg, ScrollMessage):
            self._smoother.flush()
            self.mouse.scroll(msg.dx, msg.dy)
            if session.screen_streamer is not None:
                session.screen_streamer.notify_input_activity()
        elif isinstance(msg, KeyComboMessage):
            self.keyboard.key_combo(msg.keys)
            if session.screen_streamer is not None:
                session.screen_streamer.notify_input_activity()
        elif isinstance(msg, KeyEventMessage):
            if self.keyboard.key_event(msg.key, msg.state):
                if msg.state == "down":
                    session.held_keys.add(msg.key)
                else:
                    session.held_keys.discard(msg.key)
            if session.screen_streamer is not None:
                session.screen_streamer.notify_input_activity()
        elif isinstance(msg, TextMessage):
            self.keyboard.type_text(msg.value)
            if session.screen_streamer is not None:
                session.screen_streamer.notify_input_activity()
        elif isinstance(msg, PasteMessage):
            self.keyboard.paste_text(msg.value)
            if session.screen_streamer is not None:
                session.screen_streamer.notify_input_activity()
        elif isinstance(msg, GestureMessage):
            self.gestures.handle(msg.gesture)
        elif isinstance(msg, VolumeSyncMessage):
            if msg.enable:
                await self._start_volume_sync(ws, session)
            else:
                await self._stop_volume_sync(session)
        elif isinstance(msg, VolumeSetMessage):
            updated = self.volume.set_volume(msg.level, unmute_if_positive=True)
            if session.vol_sync_enabled and updated is not None:
                await self._push_volume_state(ws, session, state=updated, force=True)
        elif isinstance(msg, MediaMessage):
            if msg.action == "vol_up":
                updated = self.volume.step_volume(0.04)
                if updated is None:
                    self.media.handle("vol_up")
                elif session.vol_sync_enabled:
                    await self._push_volume_state(ws, session, state=updated, force=True)
            elif msg.action == "vol_down":
                updated = self.volume.step_volume(-0.04)
                if updated is None:
                    self.media.handle("vol_down")
                elif session.vol_sync_enabled:
                    await self._push_volume_state(ws, session, state=updated, force=True)
            elif msg.action == "mute":
                updated = self.volume.toggle_mute()
                if updated is None:
                    self.media.handle("mute")
                elif session.vol_sync_enabled:
                    await self._push_volume_state(ws, session, state=updated, force=True)
            else:
                self.media.handle(msg.action)
        elif isinstance(msg, PowerMessage):
            self.power.handle(msg.action)
        elif isinstance(msg, ScreenStartMessage):
            await self._start_screen_stream(ws, session, msg)
        elif isinstance(msg, ScreenStopMessage):
            await self._stop_screen_stream(session)
        elif isinstance(msg, ScreenConfigMessage):
            if session.screen_streamer is not None:
                session.screen_streamer.update_config(
                    quality=msg.quality,
                    fps=msg.fps,
                    max_width=msg.max_width,
                    monitor=msg.monitor,
                    prefer_p2p=msg.prefer_p2p,
                    hide_cursor=msg.hide_cursor,
                    roi=msg.roi,
                    clear_roi=(msg.roi is None),
                )
        elif isinstance(msg, ScreenAckMessage):
            if session.screen_streamer is not None:
                session.screen_streamer.on_ack(msg.seq)
        elif isinstance(msg, PingMessage):
            self._smoother.flush()
            await ws.send(encode_pong())
        elif isinstance(msg, HelloMessage):
            logger.info(
                "Handshake from %s (protocol v%d, device=%s)",
                peer,
                msg.version,
                msg.device_name or "unknown",
            )
            mic_device = (
                self._mic_device_provider()
                if callable(self._mic_device_provider)
                else None
            )
            connected_count = (
                self._device_manager.get_count()
                if self._device_manager is not None
                else len(self._clients)
            )
            await ws.send(
                encode_hello_ack(
                    self.pc_name,
                    ok=True,
                    file_port=self.file_port,
                    mic_port=self.mic_port,
                    mic_device=mic_device,
                    connected_count=connected_count,
                )
            )
        elif isinstance(msg, UnknownMessage):
            logger.debug("Ignoring unknown message type '%s' from %s", msg.msg_type, peer)


def _format_peer(remote_address: Any) -> str:
    if isinstance(remote_address, tuple) and len(remote_address) >= 2:
        return f"{remote_address[0]}:{remote_address[1]}"
    return str(remote_address)
