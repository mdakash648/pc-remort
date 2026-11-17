"""Tests for Direct P2P UDP hole punching and authenticated low-latency input packets."""

from __future__ import annotations

import asyncio
import json
import socket
import struct
import time

import pytest

from core.p2p import (
    P2P_PACKET_SIZE,
    PKT_KEEPALIVE,
    PKT_KEEPALIVE_ACK,
    PKT_MOVE,
    PKT_PUNCH,
    PKT_PUNCH_ACK,
    PKT_SCROLL,
    P2PUdpEndpoint,
    _SeqDeduplicator,
    derive_auth_tag,
    pack_control_packet,
    pack_delta_packet,
    parse_relay_host_and_udp_port,
    unpack_packet,
)


def test_pack_unpack_roundtrip_and_auth_tag() -> None:
    tag = derive_auth_tag("rpad-secret-token", "session1234")
    assert len(tag) == 8
    assert tag == derive_auth_tag("rpad-secret-token", "session1234")
    assert tag != derive_auth_tag("wrong-token", "session1234")

    move_pkt = pack_delta_packet(PKT_MOVE, tag, 42, 12.5, -7.25)
    assert len(move_pkt) == P2P_PACKET_SIZE
    parsed = unpack_packet(move_pkt)
    assert parsed is not None
    pkt_type, out_tag, seq, payload = parsed
    assert pkt_type == PKT_MOVE
    assert out_tag == tag
    assert seq == 42
    dx, dy = struct.unpack(">ff", payload)
    assert dx == pytest.approx(12.5)
    assert dy == pytest.approx(-7.25)

    ctrl_pkt = pack_control_packet(PKT_PUNCH, tag, 7, 1700000123456)
    assert len(ctrl_pkt) == P2P_PACKET_SIZE
    parsed_ctrl = unpack_packet(ctrl_pkt)
    assert parsed_ctrl is not None
    assert parsed_ctrl[0] == PKT_PUNCH
    assert parsed_ctrl[2] == 7
    assert struct.unpack(">Q", parsed_ctrl[3])[0] == 1700000123456

    assert unpack_packet(b"BAD!" + move_pkt[4:]) is None
    assert unpack_packet(move_pkt[:-1]) is None


def test_parse_relay_host_and_udp_port() -> None:
    assert parse_relay_host_and_udp_port("ws://182.255.88.108:8770") == ("182.255.88.108", 8771)
    assert parse_relay_host_and_udp_port("ws://182.255.88.108:8770", explicit_udp_port=9000) == (
        "182.255.88.108",
        9000,
    )
    assert parse_relay_host_and_udp_port("wss://relay.example.com:8443") == ("relay.example.com", 8444)
    assert parse_relay_host_and_udp_port("not-a-url") is None


def test_seq_deduplicator_preserves_out_of_order_and_drops_duplicates() -> None:
    dedup = _SeqDeduplicator()
    assert dedup.accept(1) is True
    assert dedup.accept(1) is False  # duplicate
    assert dedup.accept(3) is True
    assert dedup.accept(2) is True  # out-of-order relative delta still applied
    assert dedup.accept(2) is False  # duplicate out-of-order dropped
    assert dedup.accept(100) is True
    assert dedup.accept(10) is False  # too old (> 64 behind max_seq)


def test_p2p_end_to_end_punch_move_scroll_and_reject_spoofed() -> None:
    asyncio.run(_run_p2p_e2e())


async def _run_p2p_e2e() -> None:
    moves: list[tuple[float, float]] = []
    scrolls: list[tuple[float, float]] = []

    token = "rpad-test-token-999"
    sid = "abcdef1234567890"
    tag = derive_auth_tag(token, sid)

    # Local mock STUN reflector on 127.0.0.1:0
    loop = asyncio.get_running_loop()

    class MockStun(asyncio.DatagramProtocol):
        def connection_made(self, transport):
            self.transport = transport

        def datagram_received(self, data, addr):
            msg = json.loads(data.decode("utf-8"))
            if msg.get("t") == "p2p_stun":
                reply = json.dumps(
                    {"t": "p2p_stun_res", "ip": addr[0], "port": addr[1], "nonce": msg["nonce"]}
                ).encode("utf-8")
                self.transport.sendto(reply, addr)

    stun_transport, _ = await loop.create_datagram_endpoint(MockStun, local_addr=("127.0.0.1", 0))
    stun_port = stun_transport.get_extra_info("socket").getsockname()[1]

    # Mock phone UDP socket on 127.0.0.1:0
    phone_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    phone_sock.bind(("127.0.0.1", 0))
    phone_sock.setblocking(False)
    phone_port = phone_sock.getsockname()[1]

    endpoint = P2PUdpEndpoint(
        token=token,
        on_move=lambda dx, dy: moves.append((dx, dy)),
        on_scroll=lambda dx, dy: scrolls.append((dx, dy)),
        bind_host="127.0.0.1",
    )

    ws_sent: list[dict[str, object]] = []

    async def send_ws(obj: dict[str, object]) -> None:
        ws_sent.append(obj)

    try:
        await endpoint.handle_offer(
            msg={
                "t": "p2p_offer",
                "sid": sid,
                "udp_port": stun_port,
                "endpoints": [{"ip": "127.0.0.1", "port": phone_port}],
            },
            relay_host="127.0.0.1",
            default_udp_port=stun_port,
            send_ws_json=send_ws,
        )

        # PC should reply with p2p_answer containing its STUN-discovered 127.0.0.1:local_port
        assert len(ws_sent) == 1
        answer = ws_sent[0]
        assert answer["t"] == "p2p_answer"
        assert answer["sid"] == sid
        eps = answer["endpoints"]
        assert isinstance(eps, list) and len(eps) >= 1
        assert {"ip": "127.0.0.1", "port": endpoint.local_port} in eps

        pc_addr = ("127.0.0.1", endpoint.local_port)

        # Phone sends PUNCH -> PC must reply PUNCH_ACK echoing timestamp
        now_ms = int(time.time() * 1000)
        phone_sock.sendto(pack_control_packet(PKT_PUNCH, tag, 1, now_ms), pc_addr)

        ack_received = False
        deadline = time.monotonic() + 1.0
        while time.monotonic() < deadline:
            try:
                data, _ = phone_sock.recvfrom(256)
                parsed = unpack_packet(data)
                if parsed and parsed[0] == PKT_PUNCH_ACK:
                    assert struct.unpack(">Q", parsed[3])[0] == now_ms
                    ack_received = True
                    break
            except BlockingIOError:
                await asyncio.sleep(0.02)
        assert ack_received is True
        assert endpoint.is_active is True

        # Phone sends authenticated MOVE, duplicate MOVE, SCROLL, and a spoofed-token MOVE
        phone_sock.sendto(pack_delta_packet(PKT_MOVE, tag, 10, 6.5, -3.25), pc_addr)
        phone_sock.sendto(pack_delta_packet(PKT_MOVE, tag, 10, 6.5, -3.25), pc_addr)  # duplicate
        phone_sock.sendto(pack_delta_packet(PKT_SCROLL, tag, 11, 0.0, -2.0), pc_addr)
        bad_tag = derive_auth_tag("wrong-token", sid)
        phone_sock.sendto(pack_delta_packet(PKT_MOVE, bad_tag, 12, 99.0, 99.0), pc_addr)

        # Keepalive check
        phone_sock.sendto(pack_control_packet(PKT_KEEPALIVE, tag, 13, now_ms + 55), pc_addr)

        ka_ack = False
        deadline = time.monotonic() + 1.0
        while time.monotonic() < deadline:
            try:
                data, _ = phone_sock.recvfrom(256)
                parsed = unpack_packet(data)
                if parsed and parsed[0] == PKT_KEEPALIVE_ACK:
                    assert struct.unpack(">Q", parsed[3])[0] == now_ms + 55
                    ka_ack = True
                    break
            except BlockingIOError:
                await asyncio.sleep(0.02)
        assert ka_ack is True

        assert len(moves) == 1
        assert moves[0][0] == pytest.approx(6.5)
        assert moves[0][1] == pytest.approx(-3.25)
        assert len(scrolls) == 1
        assert scrolls[0][1] == pytest.approx(-2.0)
    finally:
        await endpoint.stop()
        stun_transport.close()
        phone_sock.close()
