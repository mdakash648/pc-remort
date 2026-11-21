"""Loopback control IPC between the pre-login SYSTEM agent worker and the user's Tray GUI."""

from __future__ import annotations

import asyncio
import hmac
import json
import logging
import os
import secrets
import socket
import threading
from pathlib import Path
from typing import Any, Callable

from config import get_shared_config_dir
from core.runtime import AgentRuntime, AgentStatus
from core.windows_integration import is_system

logger = logging.getLogger(__name__)

_MAX_IPC_LINE_BYTES = 64 * 1024


def get_ipc_state_path() -> Path:
    """Return the shared state file path advertising the running service agent's loopback IPC."""
    return get_shared_config_dir() / "service_ipc.json"


def _status_to_dict(status: AgentStatus, *, running_as_system: bool) -> dict[str, Any]:
    return {
        "running": status.running,
        "pc_name": status.pc_name,
        "ws_port": status.ws_port,
        "local_ips": list(status.local_ips),
        "relay_enabled": status.relay_enabled,
        "relay_url": status.relay_url,
        "relay_device_id": status.relay_device_id,
        "relay_token": status.relay_token,
        "relay_state": status.relay_state,
        "relay_peer_online": status.relay_peer_online,
        "relay_last_error": status.relay_last_error,
        "lan_clients_count": status.lan_clients_count,
        "mic_status": status.mic_status,
        "received_files_dir": status.received_files_dir,
        "running_as_system": running_as_system,
        "connected_devices": list(status.connected_devices),
        "connected_devices_count": status.connected_devices_count,
        "blocked_devices": list(status.blocked_devices),
    }


def _status_from_dict(data: dict[str, Any]) -> tuple[AgentStatus, bool]:
    status = AgentStatus(
        running=bool(data.get("running", False)),
        pc_name=str(data.get("pc_name", socket.gethostname())),
        ws_port=int(data.get("ws_port", 8765)),
        local_ips=tuple(str(ip) for ip in data.get("local_ips", ())),
        relay_enabled=bool(data.get("relay_enabled", False)),
        relay_url=str(data.get("relay_url", "")),
        relay_device_id=str(data.get("relay_device_id", "")),
        relay_token=str(data.get("relay_token", "")),
        relay_state=str(data.get("relay_state", "connecting")),
        relay_peer_online=bool(data.get("relay_peer_online", False)),
        relay_last_error=data.get("relay_last_error"),
        lan_clients_count=int(data.get("lan_clients_count", 0)),
        mic_status=str(data.get("mic_status", "")),
        received_files_dir=str(data.get("received_files_dir", "")),
        connected_devices=tuple(dict(d) for d in data.get("connected_devices", ())),
        connected_devices_count=int(data.get("connected_devices_count", 0)),
        blocked_devices=tuple(dict(b) for b in data.get("blocked_devices", ())),
    )
    return status, bool(data.get("running_as_system", False))


class ServiceIpcServer:
    """Lightweight authenticated 127.0.0.1 JSON-line control server hosted by the agent worker."""

    def __init__(
        self,
        runtime: AgentRuntime,
        *,
        state_path: Path | None = None,
        on_shutdown_requested: Callable[[], None] | None = None,
        running_as_system: bool | None = None,
    ) -> None:
        self._runtime = runtime
        self._state_path = state_path or get_ipc_state_path()
        self._on_shutdown_requested = on_shutdown_requested
        self._running_as_system = is_system() if running_as_system is None else running_as_system
        self._token = secrets.token_hex(32)
        self._port = 0
        self._pid = os.getpid()

        self._loop: asyncio.AbstractEventLoop | None = None
        self._server: asyncio.AbstractServer | None = None
        self._thread: threading.Thread | None = None
        self._ready = threading.Event()
        self._startup_error: BaseException | None = None

    @property
    def port(self) -> int:
        return self._port

    @property
    def state_path(self) -> Path:
        return self._state_path

    def start(self, timeout: float = 5.0) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._ready.clear()
        self._startup_error = None
        self._thread = threading.Thread(
            target=self._thread_main,
            name="remotepad-service-ipc",
            daemon=True,
        )
        self._thread.start()
        if not self._ready.wait(timeout=timeout):
            raise TimeoutError("Timed out starting RemotePad service IPC server.")
        if self._startup_error is not None:
            raise self._startup_error

    def stop(self, timeout: float = 3.0) -> None:
        self._remove_state_file()
        loop = self._loop
        thread = self._thread
        if loop is not None and loop.is_running():
            fut = asyncio.run_coroutine_threadsafe(self._close_server(), loop)
            try:
                fut.result(timeout=timeout)
            except Exception:
                pass
            loop.call_soon_threadsafe(loop.stop)
        if thread is not None and thread.is_alive():
            thread.join(timeout=timeout)
        self._thread = None
        self._loop = None

    def _thread_main(self) -> None:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        self._loop = loop
        try:
            loop.run_until_complete(self._start_server())
        except BaseException as exc:
            self._startup_error = exc
            self._ready.set()
            loop.close()
            return

        self._ready.set()
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

    async def _start_server(self) -> None:
        self._server = await asyncio.start_server(
            self._handle_client,
            host="127.0.0.1",
            port=0,
        )
        assert self._server.sockets
        self._port = int(self._server.sockets[0].getsockname()[1])
        self._write_state_file()
        logger.info("Service IPC server listening on 127.0.0.1:%d", self._port)

    async def _close_server(self) -> None:
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()
            self._server = None

    def _write_state_file(self) -> None:
        self._state_path.parent.mkdir(parents=True, exist_ok=True)
        payload = json.dumps(
            {
                "host": "127.0.0.1",
                "port": self._port,
                "token": self._token,
                "pid": self._pid,
                "running_as_system": self._running_as_system,
            },
            indent=2,
        )
        tmp = self._state_path.with_suffix(".tmp")
        tmp.write_text(payload, encoding="utf-8")
        tmp.replace(self._state_path)

    def _remove_state_file(self) -> None:
        try:
            if not self._state_path.exists():
                return
            raw = json.loads(self._state_path.read_text(encoding="utf-8"))
            if isinstance(raw, dict) and int(raw.get("pid", -1)) == self._pid:
                self._state_path.unlink(missing_ok=True)
        except Exception:
            pass

    async def _handle_client(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        try:
            raw_line = await asyncio.wait_for(reader.readline(), timeout=5.0)
            if not raw_line or len(raw_line) > _MAX_IPC_LINE_BYTES:
                return
            try:
                req = json.loads(raw_line.decode("utf-8"))
            except (UnicodeDecodeError, ValueError):
                await self._send(writer, {"ok": False, "error": "Invalid JSON"})
                return

            if not isinstance(req, dict):
                await self._send(writer, {"ok": False, "error": "Invalid request"})
                return

            req_token = str(req.get("token", ""))
            if not hmac.compare_digest(req_token, self._token):
                await self._send(writer, {"ok": False, "error": "Unauthorized"})
                return

            cmd = str(req.get("cmd", "")).strip()
            resp = await asyncio.to_thread(self._dispatch, cmd, req)
            await self._send(writer, resp)
        except (asyncio.TimeoutError, ConnectionError):
            pass
        except Exception as exc:
            logger.debug("Service IPC handler error: %s", exc)
        finally:
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass

    @staticmethod
    async def _send(writer: asyncio.StreamWriter, payload: dict[str, Any]) -> None:
        encoded = (json.dumps(payload, separators=(",", ":")) + "\n").encode("utf-8")
        writer.write(encoded)
        await writer.drain()

    def _dispatch(self, cmd: str, req: dict[str, Any]) -> dict[str, Any]:
        if cmd == "get_status":
            status = self._runtime.get_status()
            return {
                "ok": True,
                "status": _status_to_dict(status, running_as_system=self._running_as_system),
            }

        if cmd == "update_relay_settings":
            try:
                self._runtime.update_relay_settings(
                    relay_enabled=bool(req.get("relay_enabled", False)),
                    relay_url=str(req.get("relay_url", "")),
                    relay_device_id=str(req.get("relay_device_id", "")),
                    relay_token=str(req.get("relay_token", "")),
                    persist=bool(req.get("persist", True)),
                )
                return {"ok": True}
            except ValueError as exc:
                return {"ok": False, "error_type": "ValueError", "error": str(exc)}
            except Exception as exc:
                return {"ok": False, "error": str(exc)}

        if cmd == "reconnect_relay":
            try:
                self._runtime.reconnect_relay()
                return {"ok": True}
            except Exception as exc:
                return {"ok": False, "error": str(exc)}

        if cmd == "disconnect_device":
            device_id = str(req.get("device_id", "")).strip()
            res = self._runtime.disconnect_device(device_id)
            return {"ok": res}

        if cmd == "block_device":
            device_id = str(req.get("device_id", "")).strip()
            res = self._runtime.block_device(device_id)
            return {"ok": res}

        if cmd == "unblock_device":
            identifier = str(req.get("identifier", "")).strip()
            res = self._runtime.unblock_device(identifier)
            return {"ok": res}

        if cmd == "shutdown":
            logger.info("Service IPC received user shutdown request")
            if self._on_shutdown_requested is not None:
                threading.Thread(
                    target=self._on_shutdown_requested,
                    name="remotepad-ipc-shutdown",
                    daemon=True,
                ).start()
            return {"ok": True}

        return {"ok": False, "error": f"Unknown command: {cmd}"}


class RemoteAgentProxy:
    """Tray GUI runtime adapter backed by the already-running SYSTEM background service agent."""

    is_service_backed = True

    def __init__(
        self,
        *,
        host: str,
        port: int,
        token: str,
        running_as_system: bool = True,
        state_path: Path | None = None,
        initial_status: AgentStatus | None = None,
    ) -> None:
        self._lock = threading.RLock()
        self._host = host
        self._port = port
        self._token = token
        self.running_as_system = running_as_system
        self._state_path = state_path or get_ipc_state_path()
        self._last_status = initial_status
        self._connected = True

    @classmethod
    def connect(
        cls,
        state_path: Path | None = None,
        timeout: float = 0.8,
    ) -> "RemoteAgentProxy | None":
        """Attempt to connect to an already-running RemotePad service worker via service_ipc.json."""
        path = state_path or get_ipc_state_path()
        endpoint = cls._read_state_file(path)
        if endpoint is None:
            return None
        host, port, token, running_as_system = endpoint
        proxy = cls(
            host=host,
            port=port,
            token=token,
            running_as_system=running_as_system,
            state_path=path,
        )
        try:
            status = proxy._fetch_status(timeout=timeout)
            proxy._last_status = status
            return proxy
        except Exception:
            return None

    @staticmethod
    def _read_state_file(path: Path) -> tuple[str, int, str, bool] | None:
        try:
            if not path.exists():
                return None
            raw = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(raw, dict):
                return None
            host = str(raw.get("host", "127.0.0.1"))
            port = int(raw.get("port", 0))
            token = str(raw.get("token", ""))
            running_as_system = bool(raw.get("running_as_system", False))
            if port <= 0 or not token:
                return None
            return host, port, token, running_as_system
        except Exception:
            return None

    def _refresh_endpoint_locked(self) -> bool:
        endpoint = self._read_state_file(self._state_path)
        if endpoint is None:
            return False
        self._host, self._port, self._token, self.running_as_system = endpoint
        return True

    def _request(self, payload: dict[str, Any], timeout: float = 3.0) -> dict[str, Any]:
        last_exc: Exception | None = None
        for attempt in range(2):
            with self._lock:
                if attempt == 1:
                    if not self._refresh_endpoint_locked():
                        break
                host, port, token = self._host, self._port, self._token

            req = dict(payload)
            req["token"] = token
            raw_req = (json.dumps(req, separators=(",", ":")) + "\n").encode("utf-8")
            try:
                with socket.create_connection((host, port), timeout=timeout) as sock:
                    sock.settimeout(timeout)
                    sock.sendall(raw_req)
                    chunks: list[bytes] = []
                    while True:
                        chunk = sock.recv(4096)
                        if not chunk:
                            break
                        chunks.append(chunk)
                        if b"\n" in chunk:
                            break
                raw_resp = b"".join(chunks).split(b"\n", 1)[0]
                if not raw_resp:
                    raise ConnectionError("Empty response from RemotePad service")
                parsed = json.loads(raw_resp.decode("utf-8"))
                if not isinstance(parsed, dict):
                    raise ConnectionError("Malformed IPC response from RemotePad service")
                return parsed
            except Exception as exc:
                last_exc = exc
        raise ConnectionError(f"Could not reach RemotePad service: {last_exc}") from last_exc

    @property
    def is_running(self) -> bool:
        return self._connected

    def _fetch_status(self, timeout: float = 2.0) -> AgentStatus:
        resp = self._request({"cmd": "get_status"}, timeout=timeout)
        if not resp.get("ok"):
            raise RuntimeError(str(resp.get("error", "Failed to query service status")))
        status, running_as_system = _status_from_dict(resp["status"])
        self.running_as_system = running_as_system
        self._connected = True
        return status

    def get_status(self) -> AgentStatus:
        try:
            status = self._fetch_status(timeout=1.5)
            self._last_status = status
            return status
        except Exception as exc:
            self._connected = False
            if self._last_status is not None:
                prev = self._last_status
                return AgentStatus(
                    running=False,
                    pc_name=prev.pc_name,
                    ws_port=prev.ws_port,
                    local_ips=prev.local_ips,
                    relay_enabled=prev.relay_enabled,
                    relay_url=prev.relay_url,
                    relay_device_id=prev.relay_device_id,
                    relay_token=prev.relay_token,
                    relay_state="connecting",
                    relay_peer_online=False,
                    relay_last_error=f"Reconnecting to service ({exc})",
                    lan_clients_count=0,
                    mic_status=prev.mic_status,
                    received_files_dir=prev.received_files_dir,
                    connected_devices=(),
                    connected_devices_count=0,
                    blocked_devices=prev.blocked_devices,
                )
            raise

    def disconnect_device(self, device_id: str) -> bool:
        resp = self._request({"cmd": "disconnect_device", "device_id": device_id}, timeout=3.0)
        return bool(resp.get("ok"))

    def block_device(self, device_id: str) -> bool:
        resp = self._request({"cmd": "block_device", "device_id": device_id}, timeout=3.0)
        return bool(resp.get("ok"))

    def unblock_device(self, identifier: str) -> bool:
        resp = self._request({"cmd": "unblock_device", "identifier": identifier}, timeout=3.0)
        return bool(resp.get("ok"))

    def update_relay_settings(
        self,
        *,
        relay_enabled: bool,
        relay_url: str,
        relay_device_id: str,
        relay_token: str,
        persist: bool = True,
    ) -> None:
        resp = self._request(
            {
                "cmd": "update_relay_settings",
                "relay_enabled": bool(relay_enabled),
                "relay_url": relay_url,
                "relay_device_id": relay_device_id,
                "relay_token": relay_token,
                "persist": bool(persist),
            },
            timeout=6.0,
        )
        if not resp.get("ok"):
            err = str(resp.get("error", "Failed to update relay settings"))
            if resp.get("error_type") == "ValueError":
                raise ValueError(err)
            raise RuntimeError(err)

    def reconnect_relay(self) -> None:
        resp = self._request({"cmd": "reconnect_relay"}, timeout=6.0)
        if not resp.get("ok"):
            raise RuntimeError(str(resp.get("error", "Failed to reconnect relay")))

    def shutdown_service(self, timeout: float = 3.0) -> bool:
        """Ask the running background service worker + supervisor to stop cleanly."""
        try:
            resp = self._request({"cmd": "shutdown"}, timeout=timeout)
            return bool(resp.get("ok"))
        except Exception as exc:
            logger.debug("Service shutdown IPC request failed: %s", exc)
            return False

    def stop(self, timeout: float = 5.0) -> None:
        """Detach the Tray UI without stopping the background pre-login SYSTEM service."""
        self._connected = False
