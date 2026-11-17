"""Reliable Direct P2P UDP File Transfer handler for RemotePad PC Agent.

Runs directly over the already-punched, session-authenticated P2PUdpEndpoint socket
so Phone <-> PC file browsing, downloads, and uploads work over the Internet WITHOUT
sending a single byte of file data through the VPS relay!

Protocol (all packets share the 20-byte RP2P header: magic + type + flags + auth_tag + xfer_id):
- PKT_FILE_REQ   (0x10): Phone -> PC JSON request  (info / list / download / upload / cancel)
- PKT_FILE_RESP  (0x11): PC -> Phone JSON response (ready / error / upload done)
- PKT_FILE_DATA  (0x12): Sender -> Receiver chunk  (uint32 chunk_idx + up to 1150 bytes; flags&1 = EOF)
- PKT_FILE_ACK   (0x13): Receiver -> Sender SACK   (uint32 base_idx + uint64 64-bit sack_mask)
- PKT_FILE_ABORT (0x14): Either -> Either abort/error
"""

from __future__ import annotations

import asyncio
import json
import logging
import math
import os
import struct
import time
import uuid
from collections.abc import Callable
from pathlib import Path
from typing import Any
from urllib.parse import unquote

from features.file_transfer import (
    FileTransferServer,
    resolve_unique_path,
    sanitize_filename,
)

logger = logging.getLogger(__name__)

P2P_FILE_MAGIC = b"RP2P"
_HDR_STRUCT = struct.Struct(">4sBB2s8sI")  # 20 bytes: magic, type, flags, pad2, auth_tag8, xfer_id
_DATA_SUB_STRUCT = struct.Struct(">I")     # 4 bytes: chunk_idx
_ACK_SUB_STRUCT = struct.Struct(">IQ")     # 12 bytes: base_idx, sack_mask

PKT_FILE_REQ = 0x10
PKT_FILE_RESP = 0x11
PKT_FILE_DATA = 0x12
PKT_FILE_ACK = 0x13
PKT_FILE_ABORT = 0x14

FLAG_EOF = 0x01

FILE_CHUNK_SIZE = 1150  # 24 + 1150 = 1174 bytes <= safe mobile 4G/LTE MTU
FILE_WINDOW_SIZE = 48   # 48 * 1150 ~= 55 KB in flight
_INITIAL_RTO_S = 0.18
_MAX_RTO_S = 0.60
_STALL_TIMEOUT_S = 15.0
_COMPLETED_CACHE_TTL_S = 30.0


def pack_file_json(pkt_type: int, auth_tag: bytes, xfer_id: int, obj: dict[str, Any]) -> bytes:
    body = json.dumps(obj, separators=(",", ":")).encode("utf-8")
    return _HDR_STRUCT.pack(P2P_FILE_MAGIC, pkt_type & 0xFF, 0, b"\x00\x00", auth_tag[:8], xfer_id & 0xFFFFFFFF) + body


def pack_file_data(
    auth_tag: bytes,
    xfer_id: int,
    chunk_idx: int,
    payload: bytes,
    is_eof: bool = False,
) -> bytes:
    flags = FLAG_EOF if is_eof else 0
    return (
        _HDR_STRUCT.pack(P2P_FILE_MAGIC, PKT_FILE_DATA, flags, b"\x00\x00", auth_tag[:8], xfer_id & 0xFFFFFFFF)
        + _DATA_SUB_STRUCT.pack(chunk_idx & 0xFFFFFFFF)
        + payload
    )


def pack_file_ack(auth_tag: bytes, xfer_id: int, base_idx: int, sack_mask: int) -> bytes:
    return (
        _HDR_STRUCT.pack(P2P_FILE_MAGIC, PKT_FILE_ACK, 0, b"\x00\x00", auth_tag[:8], xfer_id & 0xFFFFFFFF)
        + _ACK_SUB_STRUCT.pack(base_idx & 0xFFFFFFFF, sack_mask & 0xFFFFFFFFFFFFFFFF)
    )


def pack_file_abort(auth_tag: bytes, xfer_id: int, reason: str = "") -> bytes:
    return (
        _HDR_STRUCT.pack(P2P_FILE_MAGIC, PKT_FILE_ABORT, 0, b"\x00\x00", auth_tag[:8], xfer_id & 0xFFFFFFFF)
        + reason.encode("utf-8")[:256]
    )


def unpack_file_header(data: bytes) -> tuple[int, int, bytes, int, bytes] | None:
    """Unpack variable-length RP2P file packet -> (pkt_type, flags, auth_tag, xfer_id, body)."""
    if len(data) < 20 or data[:4] != P2P_FILE_MAGIC:
        return None
    _magic, pkt_type, flags, _pad, auth_tag, xfer_id = _HDR_STRUCT.unpack_from(data, 0)
    return pkt_type, flags, auth_tag, xfer_id, data[20:]


class _OutboundTransfer:
    """Windowed SACK sender streaming memory bytes or a local file from PC -> Phone."""

    def __init__(
        self,
        xfer_id: int,
        auth_tag: bytes,
        resp_packet: bytes,
        send_raw: Callable[[bytes], None],
        memory_bytes: bytes | None = None,
        file_path: Path | None = None,
        total_size: int = 0,
        chunk_size: int = FILE_CHUNK_SIZE,
    ) -> None:
        self.xfer_id = xfer_id
        self.auth_tag = auth_tag
        self.resp_packet = resp_packet
        self._send_raw = send_raw
        self._memory_bytes = memory_bytes
        self._file_path = file_path
        self.total_size = total_size
        self.chunk_size = chunk_size
        self.total_chunks = max(1, math.ceil(total_size / chunk_size)) if total_size > 0 else 1

        self.base_idx = 0
        self.next_idx = 0
        self._acked: set[int] = set()
        self._in_flight: dict[int, tuple[bytes, float, float]] = {}  # idx -> (pkt, sent_at, rto)
        self._last_ack_at = time.monotonic()
        self._wake = asyncio.Event()
        self._cancelled = False
        self.task: asyncio.Task[None] | None = None

    def on_ack(self, ack_base: int, sack_mask: int) -> None:
        self._last_ack_at = time.monotonic()
        if ack_base > self.base_idx:
            for idx in range(self.base_idx, min(ack_base, self.total_chunks)):
                self._in_flight.pop(idx, None)
                self._acked.discard(idx)
            self.base_idx = min(ack_base, self.total_chunks)

        if sack_mask:
            for bit in range(64):
                if (sack_mask >> bit) & 1:
                    s_idx = ack_base + 1 + bit
                    if self.base_idx <= s_idx < self.total_chunks:
                        self._acked.add(s_idx)
                        self._in_flight.pop(s_idx, None)

        self._wake.set()

    def cancel(self) -> None:
        self._cancelled = True
        self._wake.set()
        if self.task is not None and not self.task.done():
            self.task.cancel()

    async def run(self, on_done: Callable[[int], None]) -> None:
        file_obj = None
        try:
            if self._file_path is not None:
                file_obj = open(self._file_path, "rb")

            # Send initial response metadata twice spaced slightly? Send once right away,
            # then start streaming chunks immediately (zero extra RTT!).
            self._send_raw(self.resp_packet)

            while not self._cancelled and self.base_idx < self.total_chunks:
                now = time.monotonic()
                if now - self._last_ack_at > _STALL_TIMEOUT_S:
                    logger.warning("P2P outbound transfer %d stalled (timeout)", self.xfer_id)
                    self._send_raw(pack_file_abort(self.auth_tag, self.xfer_id, "Transfer timeout"))
                    return

                # 1. Fill available window slots
                sent_batch = 0
                while (
                    self.next_idx < self.total_chunks
                    and self.next_idx < self.base_idx + FILE_WINDOW_SIZE
                    and sent_batch < 16
                ):
                    idx = self.next_idx
                    self.next_idx += 1
                    if idx in self._acked:
                        continue
                    chunk = self._read_chunk(file_obj, idx)
                    pkt = pack_file_data(
                        self.auth_tag,
                        self.xfer_id,
                        idx,
                        chunk,
                        is_eof=(idx == self.total_chunks - 1),
                    )
                    self._in_flight[idx] = (pkt, now, _INITIAL_RTO_S)
                    self._send_raw(pkt)
                    sent_batch += 1

                # 2. Retransmit any timed-out packets in flight
                now = time.monotonic()
                retx_count = 0
                for idx, (pkt, sent_at, rto) in list(self._in_flight.items()):
                    if idx < self.base_idx or idx in self._acked:
                        self._in_flight.pop(idx, None)
                        continue
                    if now - sent_at >= rto:
                        new_rto = min(rto * 1.5, _MAX_RTO_S)
                        self._in_flight[idx] = (pkt, now, new_rto)
                        self._send_raw(pkt)
                        retx_count += 1
                        if retx_count >= 12:
                            break

                if self.base_idx >= self.total_chunks:
                    break

                # Yield cleanly so mouse/scroll datagrams are never delayed
                if sent_batch > 0 and self.next_idx < self.base_idx + FILE_WINDOW_SIZE:
                    await asyncio.sleep(0.001)
                else:
                    self._wake.clear()
                    try:
                        await asyncio.wait_for(self._wake.wait(), timeout=0.03)
                    except asyncio.TimeoutError:
                        pass
        except asyncio.CancelledError:
            pass
        except Exception as exc:
            logger.exception("P2P outbound transfer %d failed: %s", self.xfer_id, exc)
            try:
                self._send_raw(pack_file_abort(self.auth_tag, self.xfer_id, str(exc)))
            except Exception:
                pass
        finally:
            if file_obj is not None:
                try:
                    file_obj.close()
                except OSError:
                    pass
            on_done(self.xfer_id)

    def _read_chunk(self, file_obj: Any, idx: int) -> bytes:
        if self.total_size == 0:
            return b""
        offset = idx * self.chunk_size
        if self._memory_bytes is not None:
            return self._memory_bytes[offset : offset + self.chunk_size]
        assert file_obj is not None
        file_obj.seek(offset)
        return file_obj.read(self.chunk_size)


class _InboundUpload:
    """Windowed SACK receiver writing an incoming Phone -> PC file upload."""

    def __init__(
        self,
        xfer_id: int,
        auth_tag: bytes,
        target_dir: Path,
        filename: str,
        expected_size: int,
        ready_packet: bytes,
        send_raw: Callable[[bytes], None],
    ) -> None:
        self.xfer_id = xfer_id
        self.auth_tag = auth_tag
        self.target_dir = target_dir
        self.filename = filename
        self.expected_size = expected_size
        self.ready_packet = ready_packet
        self._send_raw = send_raw

        self.part_path = target_dir / f".remotepad_p2p_{uuid.uuid4().hex}.part"
        self._file_obj = open(self.part_path, "wb")
        self.base_idx = 0
        self._ooo: dict[int, bytes] = {}
        self._eof_idx: int | None = None
        self.total_written = 0
        self._in_order_since_ack = 0
        self._last_ack_sent_at = 0.0
        self.last_rx_at = time.monotonic()
        self.completed = False

    def on_data(self, chunk_idx: int, flags: int, payload: bytes) -> bytes | None:
        """Process incoming chunk. Returns final done-response packet once upload completes."""
        self.last_rx_at = time.monotonic()
        if self.completed:
            return None

        if flags & FLAG_EOF:
            self._eof_idx = chunk_idx

        send_ack_now = False
        if chunk_idx == self.base_idx:
            if payload:
                self._file_obj.write(payload)
                self.total_written += len(payload)
            self.base_idx += 1
            self._in_order_since_ack += 1
            while self.base_idx in self._ooo:
                buffered = self._ooo.pop(self.base_idx)
                if buffered:
                    self._file_obj.write(buffered)
                    self.total_written += len(buffered)
                self.base_idx += 1
                self._in_order_since_ack += 1
        elif self.base_idx < chunk_idx <= self.base_idx + 64:
            if chunk_idx not in self._ooo:
                self._ooo[chunk_idx] = payload
            send_ack_now = True
        else:
            # Duplicate of already-received chunk -> immediately re-ACK so sender advances
            send_ack_now = True

        # Check completion
        if self._eof_idx is not None and self.base_idx > self._eof_idx:
            self.completed = True
            self._send_ack()
            self._file_obj.flush()
            self._file_obj.close()
            final_path = resolve_unique_path(self.target_dir, self.filename)
            os.replace(self.part_path, final_path)
            logger.info("Received P2P file: %s (%d bytes)", final_path, self.total_written)
            done_pkt = pack_file_json(
                PKT_FILE_RESP,
                self.auth_tag,
                self.xfer_id,
                {
                    "ok": True,
                    "done": True,
                    "name": final_path.name,
                    "path": str(final_path.resolve()),
                    "size": self.total_written,
                },
            )
            self._send_raw(done_pkt)
            return done_pkt

        now = time.monotonic()
        if send_ack_now or self._in_order_since_ack >= 8 or (now - self._last_ack_sent_at) >= 0.025:
            self._send_ack()
        return None

    def _send_ack(self) -> None:
        mask = 0
        for idx in self._ooo:
            bit = idx - self.base_idx - 1
            if 0 <= bit < 64:
                mask |= 1 << bit
        self._in_order_since_ack = 0
        self._last_ack_sent_at = time.monotonic()
        self._send_raw(pack_file_ack(self.auth_tag, self.xfer_id, self.base_idx, mask))

    def abort_and_cleanup(self) -> None:
        try:
            self._file_obj.close()
        except OSError:
            pass
        if not self.completed:
            try:
                if self.part_path.exists():
                    self.part_path.unlink()
            except OSError:
                pass


class P2PFileHandler:
    """Dispatches Direct P2P UDP file browse, download, and upload transfers on PC."""

    def __init__(self, file_server: FileTransferServer) -> None:
        self._file_server = file_server
        self._outbound: dict[int, _OutboundTransfer] = {}
        self._inbound: dict[int, _InboundUpload] = {}
        self._completed_uploads: dict[int, tuple[bytes, float]] = {}  # xfer_id -> (done_pkt, expires_at)

    def reset(self) -> None:
        for out in list(self._outbound.values()):
            out.cancel()
        self._outbound.clear()
        for inc in list(self._inbound.values()):
            inc.abort_and_cleanup()
        self._inbound.clear()
        self._completed_uploads.clear()

    def handle_packet(
        self,
        pkt_type: int,
        flags: int,
        auth_tag: bytes,
        xfer_id: int,
        body: bytes,
        send_raw: Callable[[bytes], None],
    ) -> None:
        self._prune_completed()

        if pkt_type == PKT_FILE_REQ:
            self._handle_request(auth_tag, xfer_id, body, send_raw)
        elif pkt_type == PKT_FILE_ACK:
            if len(body) >= 12:
                base_idx, sack_mask = _ACK_SUB_STRUCT.unpack_from(body, 0)
                out = self._outbound.get(xfer_id)
                if out is not None:
                    out.on_ack(base_idx, sack_mask)
        elif pkt_type == PKT_FILE_DATA:
            if len(body) < 4:
                return
            (chunk_idx,) = _DATA_SUB_STRUCT.unpack_from(body, 0)
            payload = body[4:]
            inc = self._inbound.get(xfer_id)
            if inc is not None:
                done_pkt = inc.on_data(chunk_idx, flags, payload)
                if done_pkt is not None:
                    self._inbound.pop(xfer_id, None)
                    self._completed_uploads[xfer_id] = (done_pkt, time.monotonic() + _COMPLETED_CACHE_TTL_S)
            else:
                # Duplicate chunk after upload completed -> re-send cached done packet
                cached = self._completed_uploads.get(xfer_id)
                if cached is not None:
                    send_raw(cached[0])
        elif pkt_type == PKT_FILE_ABORT:
            out = self._outbound.pop(xfer_id, None)
            if out is not None:
                out.cancel()
            inc = self._inbound.pop(xfer_id, None)
            if inc is not None:
                inc.abort_and_cleanup()

    def _handle_request(
        self,
        auth_tag: bytes,
        xfer_id: int,
        body: bytes,
        send_raw: Callable[[bytes], None],
    ) -> None:
        try:
            req = json.loads(body.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return
        if not isinstance(req, dict):
            return

        op = req.get("op")
        if op == "cancel":
            out = self._outbound.pop(xfer_id, None)
            if out is not None:
                out.cancel()
            inc = self._inbound.pop(xfer_id, None)
            if inc is not None:
                inc.abort_and_cleanup()
            return

        # Idempotent retry handling if initial RESP packet was dropped
        if xfer_id in self._outbound:
            send_raw(self._outbound[xfer_id].resp_packet)
            return
        if xfer_id in self._inbound:
            send_raw(self._inbound[xfer_id].ready_packet)
            return
        cached = self._completed_uploads.get(xfer_id)
        if cached is not None:
            send_raw(cached[0])
            return

        if op == "info":
            payload_bytes = json.dumps(self._file_server.build_info_payload(), separators=(",", ":")).encode("utf-8")
            self._start_outbound(auth_tag, xfer_id, send_raw, memory_bytes=payload_bytes, total_size=len(payload_bytes))
        elif op == "list":
            raw_path = str(req.get("path") or "")
            status, payload_obj = self._file_server.build_list_payload(raw_path)
            if status != 200:
                send_raw(pack_file_json(PKT_FILE_RESP, auth_tag, xfer_id, payload_obj))
                return
            payload_bytes = json.dumps(payload_obj, separators=(",", ":")).encode("utf-8")
            self._start_outbound(auth_tag, xfer_id, send_raw, memory_bytes=payload_bytes, total_size=len(payload_bytes))
        elif op == "download":
            raw_path = str(req.get("path") or "").strip()
            if not raw_path:
                send_raw(pack_file_json(PKT_FILE_RESP, auth_tag, xfer_id, {"ok": False, "error": "Missing path"}))
                return
            target = Path(unquote(raw_path))
            if not target.exists() or not target.is_file():
                send_raw(pack_file_json(PKT_FILE_RESP, auth_tag, xfer_id, {"ok": False, "error": "File not found"}))
                return
            try:
                size = int(target.stat().st_size)
                # Verify readable before announcing ready
                with open(target, "rb"):
                    pass
            except OSError as exc:
                send_raw(pack_file_json(PKT_FILE_RESP, auth_tag, xfer_id, {"ok": False, "error": str(exc)}))
                return
            self._start_outbound(auth_tag, xfer_id, send_raw, file_path=target, total_size=size)
        elif op == "upload":
            raw_name = str(req.get("name") or "")
            raw_dir = str(req.get("dir") or "")
            expected_size = int(req.get("size") or 0)
            filename = sanitize_filename(unquote(raw_name)) if raw_name.strip() else "received_file"
            target_dir = Path(unquote(raw_dir)) if raw_dir.strip() else self._file_server.received_dir
            try:
                target_dir.mkdir(parents=True, exist_ok=True)
                if not target_dir.is_dir():
                    raise OSError("Target is not a directory")
                ready_pkt = pack_file_json(
                    PKT_FILE_RESP,
                    auth_tag,
                    xfer_id,
                    {"ok": True, "chunk_size": FILE_CHUNK_SIZE},
                )
                inc = _InboundUpload(
                    xfer_id=xfer_id,
                    auth_tag=auth_tag,
                    target_dir=target_dir,
                    filename=filename,
                    expected_size=expected_size,
                    ready_packet=ready_pkt,
                    send_raw=send_raw,
                )
            except OSError as exc:
                send_raw(pack_file_json(PKT_FILE_RESP, auth_tag, xfer_id, {"ok": False, "error": str(exc)}))
                return
            self._inbound[xfer_id] = inc
            send_raw(ready_pkt)
        else:
            send_raw(pack_file_json(PKT_FILE_RESP, auth_tag, xfer_id, {"ok": False, "error": f"Unknown op: {op}"}))

    def _start_outbound(
        self,
        auth_tag: bytes,
        xfer_id: int,
        send_raw: Callable[[bytes], None],
        memory_bytes: bytes | None = None,
        file_path: Path | None = None,
        total_size: int = 0,
    ) -> None:
        resp_pkt = pack_file_json(
            PKT_FILE_RESP,
            auth_tag,
            xfer_id,
            {"ok": True, "size": total_size, "chunk_size": FILE_CHUNK_SIZE},
        )
        out = _OutboundTransfer(
            xfer_id=xfer_id,
            auth_tag=auth_tag,
            resp_packet=resp_pkt,
            send_raw=send_raw,
            memory_bytes=memory_bytes,
            file_path=file_path,
            total_size=total_size,
        )
        self._outbound[xfer_id] = out
        out.task = asyncio.create_task(
            out.run(on_done=lambda xid: self._outbound.pop(xid, None)),
            name=f"remotepad-p2p-file-{xfer_id}",
        )

    def _prune_completed(self) -> None:
        now = time.monotonic()
        if self._completed_uploads:
            expired = [k for k, (_, exp) in self._completed_uploads.items() if now >= exp]
            for k in expired:
                self._completed_uploads.pop(k, None)
        if self._inbound:
            stalled = [k for k, inc in self._inbound.items() if now - inc.last_rx_at > 45.0]
            for k in stalled:
                inc = self._inbound.pop(k, None)
                if inc is not None:
                    inc.abort_and_cleanup()
