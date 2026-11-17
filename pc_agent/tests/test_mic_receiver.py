"""Tests for UDP microphone receiver packet parsing and sequence ordering."""

from __future__ import annotations

import asyncio
import socket
import struct

from features.mic_receiver import (
    MIC_HEADER_FMT,
    MIC_MAGIC,
    MicReceiverService,
    parse_mic_packet,
)


class FakeAudioSink:
    def __init__(self) -> None:
        self.chunks: list[bytes] = []
        self.closed = False

    @property
    def device_name(self) -> str:
        return "CABLE Input (VB-Audio Virtual Cable)"

    @property
    def is_ready(self) -> bool:
        return True

    def refresh_device(self) -> str:
        return self.device_name

    def write_pcm(self, pcm: bytes) -> None:
        self.chunks.append(pcm)

    def check_idle_close(self, now: float | None = None) -> None:
        pass

    def close(self) -> None:
        self.closed = True


def test_parse_mic_packet_valid_and_invalid() -> None:
    pkt = struct.pack(MIC_HEADER_FMT, MIC_MAGIC, 42) + b"\x01\x02\x03\x04"
    assert parse_mic_packet(pkt) == (42, b"\x01\x02\x03\x04")

    assert parse_mic_packet(b"BAD!1234\x00\x00") is None
    assert parse_mic_packet(struct.pack(MIC_HEADER_FMT, MIC_MAGIC, 1)) is None  # empty pcm
    assert parse_mic_packet(struct.pack(MIC_HEADER_FMT, MIC_MAGIC, 1) + b"\x01") is None  # odd byte


def test_mic_receiver_udp_end_to_end_and_out_of_order_drop() -> None:
    asyncio.run(_run_mic_udp_test())


async def _run_mic_udp_test() -> None:
    sink = FakeAudioSink()
    service = MicReceiverService(host="127.0.0.1", port=0, sink=sink)
    await service.start()
    try:
        assert service.port > 0
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            def send_seq(seq: int, payload: bytes) -> None:
                sock.sendto(
                    struct.pack(MIC_HEADER_FMT, MIC_MAGIC, seq) + payload,
                    ("127.0.0.1", service.port),
                )

            send_seq(10, b"\x10\x00\x20\x00")
            send_seq(11, b"\x30\x00\x40\x00")
            send_seq(9, b"\xff\xff\xff\xff")   # stale out-of-order -> must be dropped
            send_seq(12, b"\x50\x00\x60\x00")

        await asyncio.sleep(0.08)
        assert sink.chunks == [
            b"\x10\x00\x20\x00",
            b"\x30\x00\x40\x00",
            b"\x50\x00\x60\x00",
        ]
    finally:
        await service.stop()
        assert sink.closed is True
