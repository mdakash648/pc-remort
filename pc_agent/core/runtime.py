"""Background asyncio runtime controller for RemotePad PC Agent with live relay hot-reload."""

from __future__ import annotations

import asyncio
import logging
import re
import secrets
import socket
import threading
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

from config import AgentConfig
from core.relay_client import RelayClient
from core.server import LocalWebSocketServer, get_local_ipv4_addresses
from features.discovery import LanDiscoveryService
from features.file_transfer import FileTransferServer
from features.gestures import GestureHandler
from features.mic_receiver import MicReceiverService
from input.keyboard import KeyboardController
from input.mouse import MouseController

logger = logging.getLogger(__name__)

_DEVICE_ID_PATTERN = re.compile(r"^[a-zA-Z0-9._-]{3,64}$")


def generate_relay_token() -> str:
    """Generate a random secure relay token matching RemotePad's format."""
    return f"rpad-{secrets.token_hex(8)}"


def validate_relay_settings(
    relay_enabled: bool,
    relay_url: str,
    device_id: str,
    token: str,
) -> str | None:
    """Validate VPS relay fields; returns None if valid, or a user-facing error message."""
    if not relay_enabled:
        return None

    url = relay_url.strip()
    dev = device_id.strip()
    tok = token.strip()

    if not url:
        return "VPS Relay URL cannot be empty."
    parsed = urlparse(url)
    if parsed.scheme not in ("ws", "wss") or not parsed.hostname:
        return "VPS Relay URL must start with ws:// or wss:// and include a host."

    if not _DEVICE_ID_PATTERN.match(dev):
        return "PC ID must be 3-64 characters (letters, numbers, '.', '_', or '-')."

    if len(tok) < 8 or len(tok) > 128:
        return "Token must be between 8 and 128 characters."

    return None


@dataclass(frozen=True)
class AgentStatus:
    """Thread-safe snapshot of the PC agent's live state for the tray icon and settings UI."""

    running: bool
    pc_name: str
    ws_port: int
    local_ips: tuple[str, ...]
    relay_enabled: bool
    relay_url: str
    relay_device_id: str
    relay_token: str
    relay_state: str  # "disabled" | "connecting" | "connected" | "p2p_active" | "error"
    relay_peer_online: bool
    relay_last_error: str | None
    lan_clients_count: int
    mic_status: str
    received_files_dir: str


class AgentRuntime:
    """Runs RemotePad's asyncio servers in a background thread with safe hot-reload hooks."""

    def __init__(self, config: AgentConfig, config_path: Path | None = None) -> None:
        self.config = config
        self._config_path = config_path

        self._lock = threading.RLock()
        self._loop: asyncio.AbstractEventLoop | None = None
        self._thread: threading.Thread | None = None
        self._ready_event = threading.Event()
        self._startup_error: BaseException | None = None
        self._running = False

        self._server: LocalWebSocketServer | None = None
        self._file_server: FileTransferServer | None = None
        self._mic_server: MicReceiverService | None = None
        self._discovery: LanDiscoveryService | None = None
        self._relay_client: RelayClient | None = None

    @property
    def is_running(self) -> bool:
        with self._lock:
            return self._running

    def start(self, timeout: float = 10.0) -> None:
        """Start all agent services in a dedicated background asyncio thread."""
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return
            self._ready_event.clear()
            self._startup_error = None
            self._thread = threading.Thread(
                target=self._thread_main,
                name="remotepad-agent-loop",
                daemon=True,
            )
            self._thread.start()

        if not self._ready_event.wait(timeout=timeout):
            raise TimeoutError("Timed out starting RemotePad PC Agent services.")
        if self._startup_error is not None:
            raise self._startup_error

    def stop(self, timeout: float = 5.0) -> None:
        """Gracefully stop all agent services and the background asyncio thread."""
        with self._lock:
            loop = self._loop
            thread = self._thread
            if loop is None or thread is None:
                self._running = False
                return

        if loop.is_running():
            fut = asyncio.run_coroutine_threadsafe(self._stop_services(), loop)
            try:
                fut.result(timeout=timeout)
            except Exception as exc:
                logger.debug("Error waiting for service shutdown: %s", exc)
            loop.call_soon_threadsafe(loop.stop)

        thread.join(timeout=timeout)
        with self._lock:
            self._thread = None
            self._loop = None
            self._running = False

    def update_relay_settings(
        self,
        *,
        relay_enabled: bool,
        relay_url: str,
        relay_device_id: str,
        relay_token: str,
        persist: bool = True,
    ) -> None:
        """Validate, persist, and hot-reload the VPS relay configuration immediately."""
        err = validate_relay_settings(relay_enabled, relay_url, relay_device_id, relay_token)
        if err is not None:
            raise ValueError(err)

        with self._lock:
            self.config.relay_enabled = bool(relay_enabled)
            self.config.relay_url = relay_url.strip()
            self.config.relay_device_id = relay_device_id.strip()
            self.config.relay_token = relay_token.strip()
            if persist:
                self.config.save(self._config_path)
            loop = self._loop

        if loop is not None and loop.is_running():
            fut = asyncio.run_coroutine_threadsafe(self._reload_relay_client(), loop)
            fut.result(timeout=5.0)

    def reconnect_relay(self) -> None:
        """Force the VPS relay client to disconnect and reconnect."""
        with self._lock:
            loop = self._loop
        if loop is not None and loop.is_running():
            fut = asyncio.run_coroutine_threadsafe(self._reload_relay_client(), loop)
            fut.result(timeout=5.0)

    def get_status(self) -> AgentStatus:
        """Return a thread-safe snapshot of current agent and relay status."""
        with self._lock:
            cfg = self.config
            server = self._server
            relay = self._relay_client
            mic = self._mic_server
            file_srv = self._file_server
            running = self._running

        pc_name = server.pc_name if server is not None else socket.gethostname()
        ws_port = server.port if server is not None else cfg.port
        lan_clients = len(server._clients) if server is not None else 0
        local_ips = tuple(get_local_ipv4_addresses())

        if not cfg.relay_enabled or not cfg.relay_url.strip():
            relay_state = "disabled"
            relay_peer_online = False
            relay_last_error = None
        elif relay is None:
            relay_state = "connecting"
            relay_peer_online = False
            relay_last_error = None
        elif relay.is_p2p_active:
            relay_state = "p2p_active"
            relay_peer_online = True
            relay_last_error = None
        elif relay.is_connected:
            relay_state = "connected"
            relay_peer_online = relay.is_peer_online
            relay_last_error = relay.last_error
        elif relay.last_error:
            relay_state = "error"
            relay_peer_online = False
            relay_last_error = relay.last_error
        else:
            relay_state = "connecting"
            relay_peer_online = False
            relay_last_error = None

        if mic is None:
            mic_status = "Disabled"
        else:
            mic_status = mic.device_name or "VB-Cable not detected"

        received_dir = str(file_srv.received_dir) if file_srv is not None else ""

        return AgentStatus(
            running=running,
            pc_name=pc_name,
            ws_port=ws_port,
            local_ips=local_ips,
            relay_enabled=cfg.relay_enabled,
            relay_url=cfg.relay_url,
            relay_device_id=cfg.relay_device_id,
            relay_token=cfg.relay_token,
            relay_state=relay_state,
            relay_peer_online=relay_peer_online,
            relay_last_error=relay_last_error,
            lan_clients_count=lan_clients,
            mic_status=mic_status,
            received_files_dir=received_dir,
        )

    def _thread_main(self) -> None:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        with self._lock:
            self._loop = loop

        try:
            loop.run_until_complete(self._start_services())
            with self._lock:
                self._running = True
        except BaseException as exc:
            self._startup_error = exc
            self._ready_event.set()
            try:
                loop.run_until_complete(self._stop_services())
            except Exception:
                pass
            loop.close()
            return

        self._ready_event.set()
        try:
            loop.run_forever()
        finally:
            try:
                pending = [t for t in asyncio.all_tasks(loop) if not t.done()]
                for task in pending:
                    task.cancel()
                if pending:
                    loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
            except Exception:
                pass
            loop.close()
            with self._lock:
                self._running = False

    async def _start_services(self) -> None:
        cfg = self.config
        mouse = MouseController(
            sensitivity=cfg.mouse_sensitivity,
            acceleration=cfg.mouse_acceleration,
            scroll_sensitivity=cfg.scroll_sensitivity,
            natural_scroll=cfg.natural_scroll,
        )
        keyboard = KeyboardController()
        gestures = GestureHandler(keyboard=keyboard, gesture_map=cfg.gesture_map)

        file_server: FileTransferServer | None = None
        if cfg.file_transfer_enabled:
            received_dir = Path(cfg.received_files_dir) if cfg.received_files_dir.strip() else None
            file_server = FileTransferServer(
                host=cfg.host,
                port=cfg.file_port,
                received_dir=received_dir,
            )
            await file_server.start()

        mic_server: MicReceiverService | None = None
        if cfg.mic_enabled:
            mic_server = MicReceiverService(
                host=cfg.host,
                port=cfg.mic_port,
                preferred_device=cfg.mic_output_device,
            )
            await mic_server.start()

        server = LocalWebSocketServer(
            config=cfg,
            mouse=mouse,
            keyboard=keyboard,
            gestures=gestures,
            file_port=file_server.port if file_server is not None else None,
            mic_port=mic_server.port if mic_server is not None else None,
            mic_device_provider=mic_server.refresh_device if mic_server is not None else None,
        )
        await server.start()

        discovery: LanDiscoveryService | None = None
        if cfg.discovery_enabled:
            discovery = LanDiscoveryService(
                pc_name=server.pc_name,
                ws_port=server.port,
                discovery_port=cfg.discovery_port,
                ip_provider=get_local_ipv4_addresses,
            )
            await discovery.start()

        with self._lock:
            self._file_server = file_server
            self._mic_server = mic_server
            self._server = server
            self._discovery = discovery

        await self._reload_relay_client()

    async def _reload_relay_client(self) -> None:
        with self._lock:
            old_relay = self._relay_client
            self._relay_client = None
            server = self._server
            file_server = self._file_server
            cfg = self.config

        if old_relay is not None:
            await old_relay.stop()

        if server is None:
            return

        if cfg.relay_enabled and cfg.relay_url.strip():
            new_relay = RelayClient(
                server=server,
                relay_url=cfg.relay_url,
                device_id=cfg.relay_device_id,
                token=cfg.relay_token,
                p2p_enabled=cfg.relay_p2p_enabled,
                relay_udp_port=cfg.relay_udp_port,
                file_server=file_server,
            )
            await new_relay.start()
            with self._lock:
                self._relay_client = new_relay
            logger.info(
                "Relay client started: url=%s, device_id=%s",
                cfg.relay_url,
                cfg.relay_device_id,
            )
        else:
            logger.info("Relay client disabled")

    async def _stop_services(self) -> None:
        with self._lock:
            relay = self._relay_client
            discovery = self._discovery
            server = self._server
            mic = self._mic_server
            file_srv = self._file_server
            self._relay_client = None
            self._discovery = None
            self._server = None
            self._mic_server = None
            self._file_server = None

        if relay is not None:
            await relay.stop()
        if discovery is not None:
            await discovery.stop()
        if server is not None:
            await server.stop()
        if mic is not None:
            await mic.stop()
        if file_srv is not None:
            await file_srv.stop()
