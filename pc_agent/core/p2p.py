"""Authenticated Direct P2P UDP Hole Punching endpoint for RemotePad PC Agent.

When the phone connects over the Internet via the VPS WebSocket relay, both peers
query the VPS UDP endpoint reflector (STUN-like, default port 8771) to discover their
public IP:port, exchange endpoints over the already-authenticated WebSocket, and punch
a direct UDP path.

Once punched, high-frequency mouse `move` and `scroll` packets flow directly between
Phone <-> PC (~20-40 ms local ISP RTT) instead of detouring through the remote VPS.
If strict/symmetric CGNAT blocks direct UDP, the existing VPS WebSocket relay continues
working seamlessly with zero interruption.
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import logging
import math
import secrets
import socket
import struct
import time
from collections.abc import Awaitable, Callable
from typing import Any
from urllib.parse import urlparse

from core.p2p_file import (
    PKT_FILE_ABORT,
    PKT_FILE_ACK,
    PKT_FILE_DATA,
    PKT_FILE_REQ,
    PKT_FILE_RESP,
    unpack_file_header,
)
from core.server import get_local_ipv4_addresses

logger = logging.getLogger(__name__)

P2P_MAGIC = b"RP2P"
P2P_PACKET_SIZE = 28
_HEADER_STRUCT = struct.Struct(">4sB3s8sI")
_DELTA_STRUCT = struct.Struct(">ff")
_TS_STRUCT = struct.Struct(">Q")

PKT_PUNCH = 0x01
PKT_PUNCH_ACK = 0x02
PKT_MOVE = 0x03
PKT_SCROLL = 0x04
PKT_KEEPALIVE = 0x05
PKT_KEEPALIVE_ACK = 0x06

_SEQ_WINDOW = 64


def derive_auth_tag(token: str, sid: str) -> bytes:
    """Derive an 8-byte session authentication tag from the shared relay token and session id."""
    material = f"{token}:{sid}".encode("utf-8")
    return hashlib.sha256(material).digest()[:8]


def pack_control_packet(pkt_type: int, auth_tag: bytes, seq: int, timestamp_ms: int) -> bytes:
    """Pack a 28-byte P2P control datagram (PUNCH / PUNCH_ACK / KEEPALIVE / KEEPALIVE_ACK)."""
    return _HEADER_STRUCT.pack(P2P_MAGIC, pkt_type & 0xFF, b"\x00\x00\x00", auth_tag[:8], seq & 0xFFFFFFFF) + _TS_STRUCT.pack(
        timestamp_ms & 0xFFFFFFFFFFFFFFFF
    )


def pack_delta_packet(pkt_type: int, auth_tag: bytes, seq: int, dx: float, dy: float) -> bytes:
    """Pack a 28-byte P2P move/scroll datagram."""
    return _HEADER_STRUCT.pack(P2P_MAGIC, pkt_type & 0xFF, b"\x00\x00\x00", auth_tag[:8], seq & 0xFFFFFFFF) + _DELTA_STRUCT.pack(
        float(dx), float(dy)
    )


def unpack_packet(data: bytes) -> tuple[int, bytes, int, bytes] | None:
    """Unpack a 28-byte P2P datagram into (pkt_type, auth_tag, seq, payload8)."""
    if len(data) != P2P_PACKET_SIZE or data[:4] != P2P_MAGIC:
        return None
    _magic, pkt_type, _reserved, auth_tag, seq = _HEADER_STRUCT.unpack_from(data, 0)
    return pkt_type, auth_tag, seq, data[20:28]


def parse_relay_host_and_udp_port(relay_url: str, explicit_udp_port: int | None = None) -> tuple[str, int] | None:
    """Extract (host, udp_stun_port) from a relay WebSocket URL."""
    try:
        parsed = urlparse(relay_url.strip())
    except ValueError:
        return None
    host = parsed.hostname
    if not host:
        return None
    if explicit_udp_port is not None and 1 <= explicit_udp_port <= 65535:
        return host, explicit_udp_port
    ws_port = parsed.port or (443 if parsed.scheme == "wss" else 80)
    udp_port = ws_port + 1 if ws_port < 65535 else 8771
    return host, udp_port


def _clamp_finite(val: float, limit: float) -> float | None:
    if not math.isfinite(val):
        return None
    if val > limit:
        return limit
    if val < -limit:
        return -limit
    return val


class _SeqDeduplicator:
    """Tiny sliding-window deduplicator preserving out-of-order relative deltas."""

    __slots__ = ("_initialized", "_max_seq", "_seen")

    def __init__(self) -> None:
        self._initialized = False
        self._max_seq = 0
        self._seen: set[int] = set()

    def reset(self) -> None:
        self._initialized = False
        self._max_seq = 0
        self._seen.clear()

    def accept(self, seq: int) -> bool:
        seq &= 0xFFFFFFFF
        if not self._initialized:
            self._initialized = True
            self._max_seq = seq
            self._seen.add(seq)
            return True

        diff = (seq - self._max_seq) & 0xFFFFFFFF
        if diff == 0:
            return False  # exact duplicate of max_seq

        if diff < 0x80000000:
            # Newer packet
            self._max_seq = seq
            self._seen.add(seq)
            if len(self._seen) > _SEQ_WINDOW * 2:
                self._prune()
            return True

        # Older packet (seq < max_seq in modular arithmetic)
        age = (self._max_seq - seq) & 0xFFFFFFFF
        if age >= _SEQ_WINDOW or seq in self._seen:
            return False
        self._seen.add(seq)
        return True

    def _prune(self) -> None:
        max_s = self._max_seq
        self._seen = {s for s in self._seen if ((max_s - s) & 0xFFFFFFFF) < _SEQ_WINDOW}


class _P2PDatagramProtocol(asyncio.DatagramProtocol):
    def __init__(self, endpoint: "P2PUdpEndpoint") -> None:
        self._endpoint = endpoint

    def datagram_received(self, data: bytes, addr: tuple[str, int]) -> None:
        self._endpoint._on_datagram(data, addr)

    def error_received(self, exc: Exception) -> None:
        logger.debug("P2P UDP error: %s", exc)


class P2PUdpEndpoint:
    """Manages a local UDP socket for STUN endpoint discovery, hole punching, and low-latency input."""

    def __init__(
        self,
        token: str,
        on_move: Callable[[float, float], None],
        on_scroll: Callable[[float, float], None],
        file_handler: Any | None = None,
        bind_host: str = "0.0.0.0",
        bind_port: int = 0,
    ) -> None:
        self._token = token
        self._on_move = on_move
        self._on_scroll = on_scroll
        self._file_handler = file_handler
        self._bind_host = bind_host
        self._bind_port = bind_port

        self._transport: asyncio.DatagramTransport | None = None
        self._local_port: int = 0

        self._sid: str = ""
        self._auth_tag: bytes = b""
        self._peer_addr: tuple[str, int] | None = None
        self._p2p_active: bool = False
        self._last_rx_monotonic: float = 0.0
        self._tx_seq: int = 0
        self._seq_dedup = _SeqDeduplicator()

        self._stun_nonce: str = ""
        self._stun_future: asyncio.Future[tuple[str, int]] | None = None
        self._punch_task: asyncio.Task[None] | None = None

    @property
    def local_port(self) -> int:
        return self._local_port

    @property
    def is_active(self) -> bool:
        return self._p2p_active

    @property
    def peer_addr(self) -> tuple[str, int] | None:
        return self._peer_addr

    async def start(self) -> None:
        if self._transport is not None and not self._transport.is_closing():
            return
        loop = asyncio.get_running_loop()
        transport, _ = await loop.create_datagram_endpoint(
            lambda: _P2PDatagramProtocol(self),
            local_addr=(self._bind_host, self._bind_port),
            family=socket.AF_INET,
        )
        self._transport = transport
        sock = transport.get_extra_info("socket")
        if sock is not None:
            try:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 262144)
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 262144)
            except OSError:
                pass
            self._local_port = int(sock.getsockname()[1])
        logger.debug("P2P UDP endpoint bound on %s:%d", self._bind_host, self._local_port)

    async def stop(self) -> None:
        self.reset_peer()
        if self._stun_future is not None and not self._stun_future.done():
            self._stun_future.cancel()
        self._stun_future = None
        if self._transport is not None:
            self._transport.close()
            self._transport = None
        self._local_port = 0

    def reset_peer(self) -> None:
        if self._punch_task is not None and not self._punch_task.done():
            self._punch_task.cancel()
        self._punch_task = None
        if self._file_handler is not None:
            self._file_handler.reset()
        self._sid = ""
        self._auth_tag = b""
        self._peer_addr = None
        self._p2p_active = False
        self._last_rx_monotonic = 0.0
        self._tx_seq = 0
        self._seq_dedup.reset()

    async def discover_public_endpoint(
        self,
        stun_host: str,
        stun_port: int,
        timeout: float = 1.2,
    ) -> tuple[str, int] | None:
        """Query the VPS UDP reflector to learn our NAT-mapped public (ip, port)."""
        await self.start()
        if self._transport is None:
            return None

        loop = asyncio.get_running_loop()
        try:
            infos = await loop.getaddrinfo(stun_host, stun_port, family=socket.AF_INET, type=socket.SOCK_DGRAM)
            target = (str(infos[0][4][0]), int(infos[0][4][1]))
        except OSError as exc:
            logger.debug("P2P STUN resolve failed for %s:%d: %s", stun_host, stun_port, exc)
            return None

        nonce = secrets.token_hex(8)
        self._stun_nonce = nonce
        fut: asyncio.Future[tuple[str, int]] = loop.create_future()
        self._stun_future = fut

        probe = json.dumps({"t": "p2p_stun", "nonce": nonce}, separators=(",", ":")).encode("utf-8")
        deadline = time.monotonic() + timeout
        try:
            for attempt in range(4):
                if self._transport is None or self._transport.is_closing():
                    return None
                self._transport.sendto(probe, target)
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                wait_s = min(0.28, remaining)
                try:
                    return await asyncio.wait_for(asyncio.shield(fut), timeout=wait_s)
                except asyncio.TimeoutError:
                    if attempt == 3:
                        break
            return None
        except asyncio.CancelledError:
            raise
        finally:
            if self._stun_future is fut:
                self._stun_future = None

    def collect_local_endpoints(self) -> list[dict[str, object]]:
        if self._local_port <= 0:
            return []
        result: list[dict[str, object]] = []
        for ip in get_local_ipv4_addresses():
            if ip and not ip.startswith("127."):
                result.append({"ip": ip, "port": self._local_port})
        return result

    async def handle_offer(
        self,
        msg: dict[str, object],
        relay_host: str,
        default_udp_port: int,
        send_ws_json: Callable[[dict[str, object]], Awaitable[None]],
    ) -> None:
        """Process a phone's p2p_offer, reply with p2p_answer, and begin simultaneous UDP punching."""
        sid = msg.get("sid")
        if not isinstance(sid, str) or len(sid) < 6:
            return

        raw_udp_port = msg.get("udp_port")
        udp_port = (
            raw_udp_port
            if isinstance(raw_udp_port, int) and not isinstance(raw_udp_port, bool) and 1 <= raw_udp_port <= 65535
            else default_udp_port
        )

        peer_endpoints = _parse_endpoints(msg.get("endpoints"))
        if not peer_endpoints:
            return

        await self.start()
        self.reset_peer()
        self._sid = sid
        self._auth_tag = derive_auth_tag(self._token, sid)

        pc_endpoints: list[dict[str, object]] = []
        seen: set[tuple[str, int]] = set()

        pub = await self.discover_public_endpoint(relay_host, udp_port, timeout=1.2)
        if pub is not None:
            seen.add(pub)
            pc_endpoints.append({"ip": pub[0], "port": pub[1]})

        for ep in self.collect_local_endpoints():
            key = (str(ep["ip"]), int(ep["port"]))
            if key not in seen:
                seen.add(key)
                pc_endpoints.append(ep)

        await send_ws_json(
            {
                "t": "p2p_answer",
                "sid": sid,
                "endpoints": pc_endpoints,
            }
        )

        self._punch_task = asyncio.create_task(
            self._punch_loop(sid, self._auth_tag, peer_endpoints),
            name=f"remotepad-p2p-punch-{sid[:6]}",
        )
        logger.info(
            "P2P offer received (sid=%s): public=%s, punching %d peer endpoint(s)",
            sid[:8],
            f"{pub[0]}:{pub[1]}" if pub else "none",
            len(peer_endpoints),
        )

    async def _punch_loop(
        self,
        sid: str,
        auth_tag: bytes,
        peer_endpoints: list[tuple[str, int]],
    ) -> None:
        try:
            for _ in range(30):  # 30 * 100ms = 3.0s
                if self._sid != sid or self._transport is None or self._transport.is_closing():
                    return
                if self._p2p_active:
                    return
                ts_ms = int(time.time() * 1000)
                self._tx_seq = (self._tx_seq + 1) & 0xFFFFFFFF
                pkt = pack_control_packet(PKT_PUNCH, auth_tag, self._tx_seq, ts_ms)
                for target in peer_endpoints:
                    try:
                        self._transport.sendto(pkt, target)
                    except OSError:
                        pass
                await asyncio.sleep(0.10)
        except asyncio.CancelledError:
            pass

    def _on_datagram(self, data: bytes, addr: tuple[str, int]) -> None:
        if not data:
            return

        # 1. STUN reflection response from VPS
        if data[0:1] == b"{" and self._stun_future is not None and not self._stun_future.done():
            try:
                msg = json.loads(data.decode("utf-8"))
                if (
                    isinstance(msg, dict)
                    and msg.get("t") == "p2p_stun_res"
                    and msg.get("nonce") == self._stun_nonce
                ):
                    ip = msg.get("ip")
                    port = msg.get("port")
                    if isinstance(ip, str) and isinstance(port, int) and 1 <= port <= 65535:
                        self._stun_future.set_result((ip, port))
            except (ValueError, UnicodeDecodeError):
                pass
            return

        # 2. Authenticated P2P datagram (either variable-length file packet or fixed 28-byte control/input)
        if not self._auth_tag:
            return
        if len(data) >= 20 and data[:4] == P2P_MAGIC and data[4] in (
            PKT_FILE_REQ,
            PKT_FILE_RESP,
            PKT_FILE_DATA,
            PKT_FILE_ACK,
            PKT_FILE_ABORT,
        ):
            unpacked_file = unpack_file_header(data)
            if unpacked_file is None:
                return
            f_type, f_flags, f_tag, xfer_id, f_body = unpacked_file
            if not hmac.compare_digest(f_tag, self._auth_tag):
                return
            self._peer_addr = addr
            self._last_rx_monotonic = time.monotonic()
            if not self._p2p_active:
                self._p2p_active = True
            if self._file_handler is not None:
                self._file_handler.handle_packet(
                    f_type,
                    f_flags,
                    self._auth_tag,
                    xfer_id,
                    f_body,
                    self._send_raw_to_peer,
                )
            return

        parsed = unpack_packet(data)
        if parsed is None:
            return
        pkt_type, tag, seq, payload = parsed
        if not hmac.compare_digest(tag, self._auth_tag):
            return

        self._peer_addr = addr
        self._last_rx_monotonic = time.monotonic()

        if pkt_type == PKT_PUNCH:
            (ts_ms,) = _TS_STRUCT.unpack(payload)
            self._send_control_to(PKT_PUNCH_ACK, ts_ms, addr)
            if not self._p2p_active:
                self._p2p_active = True
                logger.info("Direct P2P UDP established with %s:%d", addr[0], addr[1])
        elif pkt_type == PKT_PUNCH_ACK:
            if not self._p2p_active:
                self._p2p_active = True
                logger.info("Direct P2P UDP confirmed (ACK) with %s:%d", addr[0], addr[1])
        elif pkt_type == PKT_KEEPALIVE:
            (ts_ms,) = _TS_STRUCT.unpack(payload)
            self._send_control_to(PKT_KEEPALIVE_ACK, ts_ms, addr)
        elif pkt_type == PKT_MOVE:
            if not self._seq_dedup.accept(seq):
                return
            dx, dy = _DELTA_STRUCT.unpack(payload)
            cdx = _clamp_finite(dx, 500.0)
            cdy = _clamp_finite(dy, 500.0)
            if cdx is not None and cdy is not None:
                if not self._p2p_active:
                    self._p2p_active = True
                self._on_move(cdx, cdy)
        elif pkt_type == PKT_SCROLL:
            if not self._seq_dedup.accept(seq):
                return
            dx, dy = _DELTA_STRUCT.unpack(payload)
            cdx = _clamp_finite(dx, 100.0)
            cdy = _clamp_finite(dy, 100.0)
            if cdx is not None and cdy is not None:
                if not self._p2p_active:
                    self._p2p_active = True
                self._on_scroll(cdx, cdy)

    def _send_raw_to_peer(self, raw: bytes) -> None:
        if self._transport is None or self._transport.is_closing() or self._peer_addr is None:
            return
        try:
            self._transport.sendto(raw, self._peer_addr)
        except OSError:
            pass

    def _send_control_to(self, pkt_type: int, ts_ms: int, addr: tuple[str, int]) -> None:
        if self._transport is None or self._transport.is_closing() or not self._auth_tag:
            return
        self._tx_seq = (self._tx_seq + 1) & 0xFFFFFFFF
        pkt = pack_control_packet(pkt_type, self._auth_tag, self._tx_seq, ts_ms)
        try:
            self._transport.sendto(pkt, addr)
        except OSError:
            pass


def _parse_endpoints(raw: object) -> list[tuple[str, int]]:
    if not isinstance(raw, list):
        return []
    result: list[tuple[str, int]] = []
    seen: set[tuple[str, int]] = set()
    for item in raw[:12]:
        if not isinstance(item, dict):
            continue
        ip = item.get("ip")
        port = item.get("port")
        if (
            isinstance(ip, str)
            and ip
            and isinstance(port, int)
            and not isinstance(port, bool)
            and 1 <= port <= 65535
        ):
            key = (ip.strip(), port)
            if key not in seen:
                seen.add(key)
                result.append(key)
    return result
