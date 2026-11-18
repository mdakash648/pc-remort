"""Lightweight streaming HTTP/1.1 file transfer server for RemotePad PC Agent.

Runs on its own TCP port (default 8767) so large file uploads/downloads never block
low-latency touchpad WebSocket frames, and streams all data in fixed 128 KB chunks
so memory usage remains constant regardless of file size.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import shutil
import socket
import string
import sys
import uuid
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, unquote, urlsplit

logger = logging.getLogger(__name__)

DEFAULT_FILE_PORT = 8767
CHUNK_SIZE = 128 * 1024  # 128 KB streaming chunks
MAX_HEADER_BYTES = 32 * 1024

_INVALID_FILENAME_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


def _get_user_home() -> Path:
    try:
        from core.windows_integration import get_effective_user_home

        return get_effective_user_home()
    except Exception:
        return Path.home()


def get_default_received_dir() -> Path:
    """Return default folder for files received from phones (~/Downloads/RemotePad)."""
    home = _get_user_home()
    downloads = home / "Downloads"
    if downloads.exists():
        return downloads / "RemotePad"
    return home / "RemotePad_Received"


def sanitize_filename(raw_name: str) -> str:
    """Strip any directory components or unsafe characters from an uploaded filename."""
    # Take only the final path segment across both / and \
    base = raw_name.replace("\\", "/").split("/")[-1].strip()
    cleaned = _INVALID_FILENAME_CHARS.sub("_", base).strip(". ")
    if not cleaned or cleaned in {".", ".."}:
        return "received_file"
    if len(cleaned) > 200:
        stem, dot, ext = cleaned.rpartition(".")
        if dot and len(ext) <= 16:
            cleaned = f"{stem[:180]}.{ext}"
        else:
            cleaned = cleaned[:200]
    return cleaned


def resolve_unique_path(directory: Path, filename: str) -> Path:
    """Return a non-colliding file path inside `directory`."""
    candidate = directory / filename
    if not candidate.exists():
        return candidate
    stem = candidate.stem
    suffix = candidate.suffix
    counter = 1
    while True:
        alt = directory / f"{stem} ({counter}){suffix}"
        if not alt.exists():
            return alt
        counter += 1


def _first_existing_dir(*paths: Path) -> Path | None:
    for path in paths:
        if path.exists() and path.is_dir():
            return path
    return None


def list_quick_dirs(received_dir: Path) -> list[dict[str, str]]:
    """Return common PC folders for one-tap browsing from the phone."""
    home = _get_user_home()
    onedrive = home / "OneDrive"
    candidates: list[tuple[str, Path | None]] = [
        ("Received", received_dir),
        ("Desktop", _first_existing_dir(home / "Desktop", onedrive / "Desktop")),
        ("Downloads", _first_existing_dir(home / "Downloads")),
        ("Documents", _first_existing_dir(home / "Documents", onedrive / "Documents")),
        ("Pictures", _first_existing_dir(home / "Pictures", onedrive / "Pictures")),
        ("Videos", _first_existing_dir(home / "Videos")),
        ("Home", home),
    ]
    result: list[dict[str, str]] = []
    seen: set[str] = set()
    for label, path in candidates:
        if path is None:
            continue
        try:
            resolved = str(path.resolve())
        except OSError:
            resolved = str(path)
        if path.exists() and path.is_dir() and resolved.lower() not in seen:
            seen.add(resolved.lower())
            result.append({"label": label, "path": resolved})
    return result


def list_drives() -> list[dict[str, Any]]:
    """Return available drive roots with optional free/total capacity."""
    if sys.platform != "win32":
        total = free = 0
        try:
            usage = shutil.disk_usage("/")
            total, free = int(usage.total), int(usage.free)
        except OSError:
            pass
        return [{"label": "/", "path": "/", "total_bytes": total, "free_bytes": free}]

    drives: list[dict[str, Any]] = []
    for letter in string.ascii_uppercase:
        root = f"{letter}:\\"
        if os.path.exists(root):
            total = free = 0
            try:
                usage = shutil.disk_usage(root)
                total, free = int(usage.total), int(usage.free)
            except OSError:
                pass
            drives.append(
                {
                    "label": f"{letter}:",
                    "path": root,
                    "total_bytes": total,
                    "free_bytes": free,
                }
            )
    return drives


class FileTransferServer:
    """Async HTTP/1.1 streaming server for bidirectional Phone <-> PC file transfer."""

    def __init__(
        self,
        host: str = "0.0.0.0",
        port: int = DEFAULT_FILE_PORT,
        received_dir: Path | None = None,
        pc_name: str | None = None,
    ) -> None:
        self.host = host
        self._configured_port = port
        self._explicit_received_dir = received_dir
        self.pc_name = pc_name or socket.gethostname()
        self._server: asyncio.AbstractServer | None = None

    @property
    def received_dir(self) -> Path:
        if self._explicit_received_dir is not None:
            return self._explicit_received_dir
        return get_default_received_dir()

    @property
    def port(self) -> int:
        if self._server and self._server.sockets:
            return int(self._server.sockets[0].getsockname()[1])
        return self._configured_port

    async def start(self) -> None:
        self.received_dir.mkdir(parents=True, exist_ok=True)
        self._server = await asyncio.start_server(
            self._handle_connection,
            host=self.host,
            port=self._configured_port,
        )
        logger.info(
            "File transfer HTTP server listening on %s:%d (received_dir=%s)",
            self.host,
            self.port,
            self.received_dir,
        )

    async def stop(self) -> None:
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()
            self._server = None
            logger.info("File transfer HTTP server stopped")

    async def _handle_connection(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        try:
            header_block = await asyncio.wait_for(
                reader.readuntil(b"\r\n\r\n"),
                timeout=15.0,
            )
            if len(header_block) > MAX_HEADER_BYTES:
                await _send_json(writer, 431, {"ok": False, "error": "Headers too large"})
                return
        except (asyncio.IncompleteReadError, asyncio.LimitOverrunError, asyncio.TimeoutError, ConnectionError):
            await _close_writer(writer)
            return

        try:
            lines = header_block.decode("iso-8859-1").split("\r\n")
            request_line = lines[0].split(" ")
            if len(request_line) < 2:
                await _send_json(writer, 400, {"ok": False, "error": "Bad request"})
                return
            method = request_line[0].upper()
            raw_target = request_line[1]

            headers: dict[str, str] = {}
            for line in lines[1:]:
                if ":" in line:
                    k, v = line.split(":", 1)
                    headers[k.strip().lower()] = v.strip()

            parsed = urlsplit(raw_target)
            path = parsed.path
            query = parse_qs(parsed.query, keep_blank_values=True)

            if method == "GET" and path == "/api/info":
                await self._handle_info(writer)
            elif method == "GET" and path == "/api/list":
                dir_param = query.get("path", [""])[0]
                await self._handle_list(writer, dir_param)
            elif method == "GET" and path == "/api/download":
                file_param = query.get("path", [""])[0]
                await self._handle_download(writer, file_param)
            elif method == "POST" and path == "/api/upload":
                name_param = query.get("name", [""])[0]
                dir_param = query.get("dir", [""])[0]
                await self._handle_upload(reader, writer, headers, name_param, dir_param)
            else:
                await _send_json(writer, 404, {"ok": False, "error": "Not found"})
        except ConnectionError:
            pass
        except Exception as exc:
            logger.exception("File transfer request error: %s", exc)
            try:
                await _send_json(writer, 500, {"ok": False, "error": "Internal server error"})
            except Exception:
                pass
        finally:
            await _close_writer(writer)

    def build_info_payload(self) -> dict[str, Any]:
        self.received_dir.mkdir(parents=True, exist_ok=True)
        return {
            "ok": True,
            "pc_name": self.pc_name,
            "received_dir": str(self.received_dir.resolve()),
            "quick_dirs": list_quick_dirs(self.received_dir),
            "drives": list_drives(),
        }

    def build_list_payload(self, raw_dir: str) -> tuple[int, dict[str, Any]]:
        target = Path(unquote(raw_dir)) if raw_dir.strip() else self.received_dir
        if target == self.received_dir:
            self.received_dir.mkdir(parents=True, exist_ok=True)

        if not target.exists() or not target.is_dir():
            return 404, {"ok": False, "error": "Directory not found"}

        resolved = target.resolve()
        parent_str = str(resolved.parent) if resolved.parent != resolved else ""

        entries: list[dict[str, Any]] = []
        try:
            with os.scandir(resolved) as it:
                for entry in it:
                    try:
                        stat = entry.stat(follow_symlinks=False)
                        is_dir = entry.is_dir(follow_symlinks=False)
                        entries.append(
                            {
                                "name": entry.name,
                                "path": entry.path,
                                "is_dir": is_dir,
                                "size": 0 if is_dir else int(stat.st_size),
                                "modified_ms": int(stat.st_mtime * 1000),
                            }
                        )
                    except OSError:
                        continue
        except PermissionError:
            return 403, {"ok": False, "error": "Permission denied"}
        except OSError as exc:
            return 500, {"ok": False, "error": str(exc)}

        entries.sort(key=lambda e: (not e["is_dir"], e["name"].lower()))
        return 200, {
            "ok": True,
            "path": str(resolved),
            "parent": parent_str,
            "entries": entries,
        }

    async def _handle_info(self, writer: asyncio.StreamWriter) -> None:
        await _send_json(writer, 200, self.build_info_payload())

    async def _handle_list(self, writer: asyncio.StreamWriter, raw_dir: str) -> None:
        status, payload = self.build_list_payload(raw_dir)
        await _send_json(writer, status, payload)

    async def _handle_download(self, writer: asyncio.StreamWriter, raw_path: str) -> None:
        if not raw_path.strip():
            await _send_json(writer, 400, {"ok": False, "error": "Missing path"})
            return

        target = Path(unquote(raw_path))
        if not target.exists() or not target.is_file():
            await _send_json(writer, 404, {"ok": False, "error": "File not found"})
            return

        try:
            file_size = target.stat().st_size
            file_obj = open(target, "rb")
        except OSError as exc:
            await _send_json(writer, 403, {"ok": False, "error": str(exc)})
            return

        safe_header_name = target.name.replace('"', "'").encode("ascii", errors="replace").decode("ascii")
        header = (
            "HTTP/1.1 200 OK\r\n"
            "Content-Type: application/octet-stream\r\n"
            f"Content-Length: {file_size}\r\n"
            f'Content-Disposition: attachment; filename="{safe_header_name}"\r\n'
            "Connection: close\r\n"
            "\r\n"
        ).encode("iso-8859-1")

        try:
            writer.write(header)
            await writer.drain()

            loop = asyncio.get_running_loop()
            while True:
                chunk = await loop.run_in_executor(None, file_obj.read, CHUNK_SIZE)
                if not chunk:
                    break
                writer.write(chunk)
                await writer.drain()
        finally:
            file_obj.close()

    async def _handle_upload(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
        headers: dict[str, str],
        raw_name: str,
        raw_dir: str,
    ) -> None:
        filename = sanitize_filename(unquote(raw_name)) if raw_name.strip() else "received_file"
        target_dir = Path(unquote(raw_dir)) if raw_dir.strip() else self.received_dir

        try:
            target_dir.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            await _send_json(writer, 400, {"ok": False, "error": f"Invalid target directory: {exc}"})
            return

        if not target_dir.is_dir():
            await _send_json(writer, 400, {"ok": False, "error": "Target is not a directory"})
            return

        is_chunked = "chunked" in headers.get("transfer-encoding", "").lower()
        content_length: int | None = None
        if not is_chunked:
            raw_len = headers.get("content-length")
            if raw_len is None:
                await _send_json(writer, 411, {"ok": False, "error": "Content-Length required"})
                return
            try:
                content_length = int(raw_len)
                if content_length < 0:
                    raise ValueError
            except ValueError:
                await _send_json(writer, 400, {"ok": False, "error": "Invalid Content-Length"})
                return

        if headers.get("expect", "").lower() == "100-continue":
            writer.write(b"HTTP/1.1 100 Continue\r\n\r\n")
            await writer.drain()

        part_path = target_dir / f".remotepad_{uuid.uuid4().hex}.part"
        total_written = 0
        loop = asyncio.get_running_loop()

        try:
            with open(part_path, "wb") as out:
                if is_chunked:
                    total_written = await _stream_chunked_to_file(reader, out, loop)
                else:
                    assert content_length is not None
                    total_written = await _stream_fixed_to_file(reader, out, content_length, loop)

            final_path = resolve_unique_path(target_dir, filename)
            os.replace(part_path, final_path)
            logger.info("Received file: %s (%d bytes)", final_path, total_written)
            await _send_json(
                writer,
                200,
                {
                    "ok": True,
                    "name": final_path.name,
                    "path": str(final_path.resolve()),
                    "size": total_written,
                },
            )
        except Exception:
            try:
                if part_path.exists():
                    part_path.unlink()
            except OSError:
                pass
            raise


async def _stream_fixed_to_file(
    reader: asyncio.StreamReader,
    out_file: Any,
    content_length: int,
    loop: asyncio.AbstractEventLoop,
) -> int:
    remaining = content_length
    written = 0
    while remaining > 0:
        data = await reader.read(min(CHUNK_SIZE, remaining))
        if not data:
            raise ConnectionError("Client disconnected before upload completed")
        await loop.run_in_executor(None, out_file.write, data)
        written += len(data)
        remaining -= len(data)
    return written


async def _stream_chunked_to_file(
    reader: asyncio.StreamReader,
    out_file: Any,
    loop: asyncio.AbstractEventLoop,
) -> int:
    written = 0
    while True:
        size_line = await reader.readuntil(b"\r\n")
        size_token = size_line.split(b";", 1)[0].strip()
        chunk_len = int(size_token, 16)
        if chunk_len == 0:
            # Consume trailing CRLF (and optional trailers)
            while True:
                trailer = await reader.readuntil(b"\r\n")
                if trailer == b"\r\n":
                    break
            break

        remaining = chunk_len
        while remaining > 0:
            data = await reader.read(min(CHUNK_SIZE, remaining))
            if not data:
                raise ConnectionError("Unexpected EOF during chunked upload")
            await loop.run_in_executor(None, out_file.write, data)
            written += len(data)
            remaining -= len(data)

        crlf = await reader.readexactly(2)
        if crlf != b"\r\n":
            raise ValueError("Malformed chunk terminator")
    return written


_STATUS_TEXT = {
    200: "OK",
    400: "Bad Request",
    403: "Forbidden",
    404: "Not Found",
    411: "Length Required",
    431: "Request Header Fields Too Large",
    500: "Internal Server Error",
}


async def _send_json(writer: asyncio.StreamWriter, status: int, data: dict[str, Any]) -> None:
    body = json.dumps(data, separators=(",", ":")).encode("utf-8")
    reason = _STATUS_TEXT.get(status, "Error")
    header = (
        f"HTTP/1.1 {status} {reason}\r\n"
        "Content-Type: application/json; charset=utf-8\r\n"
        f"Content-Length: {len(body)}\r\n"
        "Connection: close\r\n"
        "\r\n"
    ).encode("iso-8859-1")
    writer.write(header + body)
    await writer.drain()


async def _close_writer(writer: asyncio.StreamWriter) -> None:
    try:
        writer.close()
        await writer.wait_closed()
    except Exception:
        pass
