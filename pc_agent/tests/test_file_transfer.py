"""Tests for streaming HTTP FileTransferServer."""

from __future__ import annotations

import asyncio
import json
import socket
import urllib.parse
import urllib.request
from pathlib import Path

from features.file_transfer import (
    FileTransferServer,
    resolve_unique_path,
    sanitize_filename,
)


def test_sanitize_filename_and_unique_path(tmp_path: Path) -> None:
    assert sanitize_filename("../../evil/path\\photo?.jpg") == "photo_.jpg"
    assert sanitize_filename("   ") == "received_file"
    assert sanitize_filename("আমার_ফাইল.pdf") == "আমার_ফাইল.pdf"

    first = resolve_unique_path(tmp_path, "demo.txt")
    first.write_text("a", encoding="utf-8")
    second = resolve_unique_path(tmp_path, "demo.txt")
    assert second.name == "demo (1).txt"


def test_file_transfer_info_list_upload_and_download(tmp_path: Path) -> None:
    asyncio.run(_run_file_transfer_flow(tmp_path))


async def _run_file_transfer_flow(tmp_path: Path) -> None:
    received = tmp_path / "received"
    server = FileTransferServer(host="127.0.0.1", port=0, received_dir=received, pc_name="TEST-PC")
    await server.start()
    loop = asyncio.get_running_loop()

    try:
        base = f"http://127.0.0.1:{server.port}"

        # 1. GET /api/info
        def get_json(url: str) -> dict:
            with urllib.request.urlopen(url, timeout=5) as resp:
                assert resp.status == 200
                return json.loads(resp.read().decode("utf-8"))

        info = await loop.run_in_executor(None, get_json, f"{base}/api/info")
        assert info["ok"] is True
        assert info["pc_name"] == "TEST-PC"
        assert Path(info["received_dir"]) == received.resolve()

        # 2. POST /api/upload (Content-Length streaming, ~300 KB payload > 2 chunks)
        payload = bytes(range(256)) * 1200  # 307,200 bytes
        upload_url = f"{base}/api/upload?name={urllib.parse.quote('sample.bin')}"

        def post_fixed() -> dict:
            req = urllib.request.Request(upload_url, data=payload, method="POST")
            with urllib.request.urlopen(req, timeout=5) as resp:
                assert resp.status == 200
                return json.loads(resp.read().decode("utf-8"))

        up_res = await loop.run_in_executor(None, post_fixed)
        assert up_res["ok"] is True
        assert up_res["name"] == "sample.bin"
        assert up_res["size"] == len(payload)
        saved_path = Path(up_res["path"])
        assert saved_path.read_bytes() == payload

        # 3. POST /api/upload with Transfer-Encoding: chunked (duplicate name auto-renames)
        chunked_data = b"hello chunked world!"

        def post_chunked() -> dict:
            with socket.create_connection(("127.0.0.1", server.port), timeout=5) as sock:
                body = (
                    f"{len(chunked_data):x}\r\n".encode("ascii")
                    + chunked_data
                    + b"\r\n0\r\n\r\n"
                )
                req = (
                    f"POST /api/upload?name=sample.bin HTTP/1.1\r\n"
                    f"Host: 127.0.0.1:{server.port}\r\n"
                    f"Transfer-Encoding: chunked\r\n"
                    f"Connection: close\r\n\r\n"
                ).encode("ascii") + body
                sock.sendall(req)
                raw = b""
                while True:
                    part = sock.recv(4096)
                    if not part:
                        break
                    raw += part
            _, _, resp_body = raw.partition(b"\r\n\r\n")
            return json.loads(resp_body.decode("utf-8"))

        chunk_res = await loop.run_in_executor(None, post_chunked)
        assert chunk_res["ok"] is True
        assert chunk_res["name"] == "sample (1).bin"
        assert Path(chunk_res["path"]).read_bytes() == chunked_data

        # 4. GET /api/list
        listing = await loop.run_in_executor(
            None,
            get_json,
            f"{base}/api/list?path={urllib.parse.quote(str(received))}",
        )
        names = [e["name"] for e in listing["entries"]]
        assert "sample.bin" in names
        assert "sample (1).bin" in names

        # 5. GET /api/download
        dl_url = f"{base}/api/download?path={urllib.parse.quote(str(saved_path))}"

        def download_bytes() -> bytes:
            with urllib.request.urlopen(dl_url, timeout=5) as resp:
                assert resp.status == 200
                return resp.read()

        downloaded = await loop.run_in_executor(None, download_bytes)
        assert downloaded == payload

        # 6. GET /api/download with Range (for streaming video & direct image partial loading)
        def range_download() -> tuple[int, str | None, str | None, bytes]:
            req = urllib.request.Request(dl_url, headers={"Range": "bytes=10-49"})
            with urllib.request.urlopen(req, timeout=5) as resp:
                status = resp.status
                accept_ranges = resp.headers.get("Accept-Ranges")
                content_range = resp.headers.get("Content-Range")
                body = resp.read()
                return status, accept_ranges, content_range, body

        r_status, r_accept, r_content_range, r_body = await loop.run_in_executor(None, range_download)
        assert r_status == 206
        assert r_body == payload[10:50]
        assert r_accept == "bytes"
        assert r_content_range == f"bytes 10-49/{len(payload)}"

        # 7. HEAD /api/download
        def head_download() -> tuple[int, int, bytes]:
            req = urllib.request.Request(dl_url, method="HEAD")
            with urllib.request.urlopen(req, timeout=5) as resp:
                cl = int(resp.headers.get("Content-Length", 0))
                return resp.status, cl, resp.read()

        h_status, h_cl, h_body = await loop.run_in_executor(None, head_download)
        assert h_status == 200
        assert h_cl == len(payload)
        assert len(h_body) == 0
    finally:
        await server.stop()
