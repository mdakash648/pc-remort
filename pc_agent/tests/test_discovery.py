"""Tests for LAN auto-discovery UDP responder and beacon encoding."""

from __future__ import annotations

import asyncio
import json
import socket

from features.discovery import LanDiscoveryService, encode_announce, is_discover_probe


def test_encode_announce_and_probe_validation() -> None:
    raw = encode_announce("MY-WINDOWS-PC", 8765)
    parsed = json.loads(raw.decode("utf-8"))
    assert parsed == {"t": "announce", "v": 1, "name": "MY-WINDOWS-PC", "port": 8765}

    assert is_discover_probe(b'{"t":"discover","v":1}') is True
    assert is_discover_probe(b'{"t":"other"}') is False
    assert is_discover_probe(b"not-json") is False
    assert is_discover_probe(b"\xff\xfe") is False


def test_lan_discovery_replies_to_udp_probe() -> None:
    asyncio.run(_run_probe_test())


async def _run_probe_test() -> None:
    service = LanDiscoveryService(
        pc_name="TEST-PC",
        ws_port=8765,
        discovery_port=0,
        enable_beacons=False,
    )
    await service.start()
    try:
        assert service.bound_port > 0

        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
            client.settimeout(2.0)
            # Send invalid garbage first to ensure it's ignored without breaking responder
            client.sendto(b"garbage", ("127.0.0.1", service.bound_port))
            client.sendto(b'{"t":"discover","v":1}', ("127.0.0.1", service.bound_port))

            loop = asyncio.get_running_loop()
            data, _ = await loop.run_in_executor(None, client.recvfrom, 1024)

        reply = json.loads(data.decode("utf-8"))
        assert reply["t"] == "announce"
        assert reply["name"] == "TEST-PC"
        assert reply["port"] == 8765
    finally:
        await service.stop()
