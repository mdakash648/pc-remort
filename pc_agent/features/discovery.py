"""LAN auto-discovery service for RemotePad PC Agent.

Supports two complementary mechanisms over UDP (default port 8766):
1. Immediate unicast reply whenever a phone broadcasts a `{"t":"discover","v":1}` probe.
2. Periodic LAN broadcast beacons (`{"t":"announce","v":1,"name":"...","port":8765}`)
   sent on every local IPv4 interface so phones still discover this PC even if
   Windows Firewall blocks inbound UDP probes.
"""

from __future__ import annotations

import asyncio
import ipaddress
import json
import logging
import socket
from typing import Callable

from config import PROTOCOL_VERSION

logger = logging.getLogger(__name__)

DEFAULT_DISCOVERY_PORT = 8766
BEACON_INTERVAL_SECONDS = 2.0


def encode_announce(pc_name: str, ws_port: int) -> bytes:
    """Serialize a LAN discovery announce packet."""
    payload = {
        "t": "announce",
        "v": PROTOCOL_VERSION,
        "name": pc_name,
        "port": ws_port,
    }
    return json.dumps(payload, separators=(",", ":")).encode("utf-8")


def is_discover_probe(data: bytes) -> bool:
    """Return True if raw UDP bytes represent a valid RemotePad discover probe."""
    try:
        parsed = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, ValueError, TypeError):
        return False
    return isinstance(parsed, dict) and parsed.get("t") == "discover"


def _compute_broadcast_targets(local_ips: list[str]) -> list[tuple[str, str]]:
    """Return (bind_ip, broadcast_ip) pairs to reach devices across LAN interfaces."""
    targets: list[tuple[str, str]] = []
    seen: set[tuple[str, str]] = set()

    for ip in local_ips:
        if not ip or ip.startswith("127."):
            continue
        # Subnet /24 directed broadcast + limited broadcast from this specific interface
        try:
            net = ipaddress.IPv4Network(f"{ip}/24", strict=False)
            subnet_bcast = str(net.broadcast_address)
            pair = (ip, subnet_bcast)
            if pair not in seen:
                seen.add(pair)
                targets.append(pair)
        except ValueError:
            pass

        pair_global = (ip, "255.255.255.255")
        if pair_global not in seen:
            seen.add(pair_global)
            targets.append(pair_global)

    if not targets:
        targets.append(("", "255.255.255.255"))
    return targets


class _DiscoveryProtocol(asyncio.DatagramProtocol):
    def __init__(self, announce_bytes: bytes) -> None:
        self._announce_bytes = announce_bytes
        self._transport: asyncio.DatagramTransport | None = None

    def connection_made(self, transport: asyncio.BaseTransport) -> None:
        assert isinstance(transport, asyncio.DatagramTransport)
        self._transport = transport

    def datagram_received(self, data: bytes, addr: tuple[str, int]) -> None:
        if self._transport is None:
            return
        if is_discover_probe(data):
            logger.debug("Discovery probe from %s:%d -> replying", addr[0], addr[1])
            try:
                self._transport.sendto(self._announce_bytes, addr)
            except OSError as exc:
                logger.debug("Failed to reply to discovery probe from %s: %s", addr, exc)

    def error_received(self, exc: Exception) -> None:
        logger.debug("Discovery UDP socket error: %s", exc)


class LanDiscoveryService:
    """Listens for UDP discovery probes and broadcasts periodic LAN beacons."""

    def __init__(
        self,
        pc_name: str,
        ws_port: int,
        discovery_port: int = DEFAULT_DISCOVERY_PORT,
        beacon_interval: float = BEACON_INTERVAL_SECONDS,
        ip_provider: Callable[[], list[str]] | None = None,
        enable_beacons: bool = True,
    ) -> None:
        self.pc_name = pc_name
        self.ws_port = ws_port
        self.discovery_port = discovery_port
        self.beacon_interval = beacon_interval
        self._ip_provider = ip_provider
        self._enable_beacons = enable_beacons

        self._announce_bytes = encode_announce(pc_name, ws_port)
        self._transport: asyncio.DatagramTransport | None = None
        self._beacon_task: asyncio.Task[None] | None = None

    @property
    def bound_port(self) -> int:
        if self._transport is not None:
            sockname = self._transport.get_extra_info("sockname")
            if isinstance(sockname, tuple) and len(sockname) >= 2:
                return int(sockname[1])
        return self.discovery_port

    async def start(self) -> None:
        loop = asyncio.get_running_loop()
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.bind(("0.0.0.0", self.discovery_port))
            sock.setblocking(False)
            transport, _ = await loop.create_datagram_endpoint(
                lambda: _DiscoveryProtocol(self._announce_bytes),
                sock=sock,
            )
            self._transport = transport
            logger.info("LAN discovery listening on UDP port %d", self.bound_port)
        except OSError as exc:
            sock.close()
            logger.warning(
                "Could not bind UDP discovery port %d (%s); continuing with outbound beacons only.",
                self.discovery_port,
                exc,
            )

        if self._enable_beacons and self._beacon_task is None:
            self._beacon_task = asyncio.create_task(self._beacon_loop())

    async def stop(self) -> None:
        if self._beacon_task is not None:
            self._beacon_task.cancel()
            try:
                await self._beacon_task
            except asyncio.CancelledError:
                pass
            self._beacon_task = None

        if self._transport is not None:
            self._transport.close()
            self._transport = None
            logger.info("LAN discovery stopped")

    async def _beacon_loop(self) -> None:
        while True:
            self.send_beacon_once()
            await asyncio.sleep(self.beacon_interval)

    def send_beacon_once(self) -> None:
        """Broadcast one announce packet per local IPv4 interface."""
        ips = self._ip_provider() if self._ip_provider is not None else []
        targets = _compute_broadcast_targets(ips)
        for bind_ip, bcast_ip in targets:
            try:
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                    if bind_ip:
                        sock.bind((bind_ip, 0))
                    sock.sendto(self._announce_bytes, (bcast_ip, self.discovery_port))
            except OSError:
                continue
