"""Tests for Reliable Direct P2P UDP File Transfer (info, list, download, upload, SACK recovery)."""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
import struct
import time
from pathlib import Path

from core.p2p import derive_auth_tag
from core.p2p_file import (
    FILE_CHUNK_SIZE,
    FLAG_EOF,
    PKT_FILE_ACK,
    PKT_FILE_DATA,
    PKT_FILE_REQ,
    PKT_FILE_RESP,
    P2PFileHandler,
    pack_file_ack,
    pack_file_data,
    pack_file_json,
    unpack_file_header,
)
from features.file_transfer import FileTransferServer


def test_p2p_file_info_list_download_upload_with_packet_loss(tmp_path: Path) -> None:
    asyncio.run(_run_p2p_file_tests(tmp_path))


async def _run_p2p_file_tests(tmp_path: Path) -> None:
    recv_dir = tmp_path / "received"
    recv_dir.mkdir()
    sample_data = os.urandom(150 * 1024 + 37)  # ~150 KB, > 130 UDP chunks!
    sample_file = recv_dir / "demo_video.bin"
    sample_file.write_bytes(sample_data)

    empty_file = recv_dir / "empty.txt"
    empty_file.write_bytes(b"")

    file_server = FileTransferServer(received_dir=recv_dir, pc_name="TEST-PC")
    handler = P2PFileHandler(file_server)
    tag = derive_auth_tag("rpad-secret-1234", "sid99999")

    phone_rx: asyncio.Queue[bytes] = asyncio.Queue()

    def send_to_phone(raw: bytes) -> None:
        phone_rx.put_nowait(raw)

    async def recv_stream_on_phone(xfer_id: int, drop_once_indices: set[int] | None = None) -> tuple[dict, bytes]:
        resp_meta = None
        base_idx = 0
        ooo: dict[int, bytes] = {}
        eof_idx: int | None = None
        out = bytearray()
        dropped_already: set[int] = set()
        drop_targets = drop_once_indices or set()

        deadline = time.monotonic() + 5.0
        while time.monotonic() < deadline:
            raw = await asyncio.wait_for(phone_rx.get(), timeout=2.0)
            hdr = unpack_file_header(raw)
            assert hdr is not None
            pkt_type, flags, r_tag, r_xid, body = hdr
            assert r_tag == tag
            if r_xid != xfer_id:
                continue

            if pkt_type == PKT_FILE_RESP:
                resp_meta = json.loads(body.decode("utf-8"))
                if not resp_meta.get("ok"):
                    return resp_meta, b""
            elif pkt_type == PKT_FILE_DATA:
                (c_idx,) = struct.unpack_from(">I", body, 0)
                payload = body[4:]

                # Simulate selective packet loss once for specified chunk indices
                if c_idx in drop_targets and c_idx not in dropped_already:
                    dropped_already.add(c_idx)
                    continue

                if flags & FLAG_EOF:
                    eof_idx = c_idx
                if c_idx == base_idx:
                    out.extend(payload)
                    base_idx += 1
                    while base_idx in ooo:
                        out.extend(ooo.pop(base_idx))
                        base_idx += 1
                elif base_idx < c_idx <= base_idx + 64:
                    ooo.setdefault(c_idx, payload)

                mask = 0
                for k in ooo:
                    bit = k - base_idx - 1
                    if 0 <= bit < 64:
                        mask |= 1 << bit
                ack = pack_file_ack(tag, xfer_id, base_idx, mask)
                a_hdr = unpack_file_header(ack)
                assert a_hdr is not None
                handler.handle_packet(a_hdr[0], a_hdr[1], a_hdr[2], a_hdr[3], a_hdr[4], send_to_phone)

                if eof_idx is not None and base_idx > eof_idx:
                    assert resp_meta is not None
                    return resp_meta, bytes(out)

        raise AssertionError("Timed out receiving P2P stream")

    def send_req(xfer_id: int, obj: dict) -> None:
        pkt = pack_file_json(PKT_FILE_REQ, tag, xfer_id, obj)
        hdr = unpack_file_header(pkt)
        assert hdr is not None
        handler.handle_packet(hdr[0], hdr[1], hdr[2], hdr[3], hdr[4], send_to_phone)

    try:
        # 1. INFO
        send_req(1, {"op": "info"})
        meta, raw_info = await recv_stream_on_phone(1)
        assert meta["ok"] is True
        info = json.loads(raw_info.decode("utf-8"))
        assert info["pc_name"] == "TEST-PC"
        assert len(info["drives"]) >= 1

        # 2. LIST
        send_req(2, {"op": "list", "path": str(recv_dir)})
        meta, raw_list = await recv_stream_on_phone(2)
        assert meta["ok"] is True
        listing = json.loads(raw_list.decode("utf-8"))
        names = [e["name"] for e in listing["entries"]]
        assert "demo_video.bin" in names
        assert "empty.txt" in names

        # 3. DOWNLOAD 150 KB WITH SIMULATED PACKET DROPS (chunks 5, 19, 77 dropped once!)
        send_req(3, {"op": "download", "path": str(sample_file)})
        meta, dl_bytes = await recv_stream_on_phone(3, drop_once_indices={5, 19, 77})
        assert meta["ok"] is True
        assert meta["size"] == len(sample_data)
        assert hashlib.sha256(dl_bytes).hexdigest() == hashlib.sha256(sample_data).hexdigest()

        # 4. DOWNLOAD 0-BYTE FILE
        send_req(4, {"op": "download", "path": str(empty_file)})
        meta, dl_empty = await recv_stream_on_phone(4)
        assert meta["ok"] is True and meta["size"] == 0 and dl_empty == b""

        # 5. UPLOAD 150 KB (Phone -> PC) WITH OUT-OF-ORDER + DUPLICATE CHUNKS
        upload_data = os.urandom(150 * 1024 + 19)
        send_req(5, {"op": "upload", "name": "from_phone.bin", "dir": str(recv_dir), "size": len(upload_data)})
        ready_raw = await asyncio.wait_for(phone_rx.get(), timeout=2.0)
        ready_hdr = unpack_file_header(ready_raw)
        assert ready_hdr is not None and ready_hdr[0] == PKT_FILE_RESP
        ready_meta = json.loads(ready_hdr[4].decode("utf-8"))
        assert ready_meta["ok"] is True

        chunks = [upload_data[i : i + FILE_CHUNK_SIZE] for i in range(0, len(upload_data), FILE_CHUNK_SIZE)]
        # Intentionally send chunk 2 before chunk 1, and duplicate chunk 0
        order = list(range(len(chunks)))
        order[1], order[2] = order[2], order[1]
        order.insert(1, 0)  # duplicate chunk 0

        for idx in order:
            pkt = pack_file_data(tag, 5, idx, chunks[idx], is_eof=(idx == len(chunks) - 1))
            hdr = unpack_file_header(pkt)
            assert hdr is not None
            handler.handle_packet(hdr[0], hdr[1], hdr[2], hdr[3], hdr[4], send_to_phone)

        done_meta = None
        while not phone_rx.empty():
            raw = phone_rx.get_nowait()
            hdr = unpack_file_header(raw)
            if hdr and hdr[0] == PKT_FILE_RESP and hdr[3] == 5:
                done_meta = json.loads(hdr[4].decode("utf-8"))
                break
        assert done_meta is not None and done_meta.get("done") is True
        saved_path = Path(done_meta["path"])
        assert saved_path.exists()
        assert hashlib.sha256(saved_path.read_bytes()).hexdigest() == hashlib.sha256(upload_data).hexdigest()
    finally:
        handler.reset()
