"""Tests for UDP microphone receiver packet parsing and sequence ordering."""

from __future__ import annotations

import asyncio
import socket
import struct

from features.mic_receiver import (
    MIC_HEADER_FMT,
    MIC_MAGIC,
    MicReceiverService,
    WinMMWaveOutSink,
    is_virtual_mic_device_name,
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


def test_is_virtual_mic_device_name_rejects_physical_speakers() -> None:
    assert is_virtual_mic_device_name("CABLE Input (VB-Audio Virtual C") is True
    assert is_virtual_mic_device_name("VoiceMeeter Input (VB-Audio)") is True
    assert is_virtual_mic_device_name("LED MONITOR (HD Audio Driver fo") is False
    assert is_virtual_mic_device_name("Speakers (High Definition Audio") is False
    assert is_virtual_mic_device_name("Headphones (WZ-BT5.0)") is False


def test_waveout_sink_re_resolves_shifted_device_index_before_open(monkeypatch) -> None:
    import features.mic_receiver as mr

    # Simulate CABLE Input initially at waveOut[0], then shifting to waveOut[2]
    # after Windows changes the default playback device to physical speakers.
    state = {"devices": [(0, "CABLE Input (VB-Audio Virtual C"), (1, "Speakers (Realtek)")]}
    opened_ids: list[int] = []

    class _FakeWinMM:
        def waveOutOpen(self, phandle, dev_id, _wfx, _cb, _inst, _flags):
            opened_ids.append(int(dev_id))
            phandle._obj.value = 100 + int(dev_id)
            return 0

        def waveOutGetID(self, handle, pid):
            pid._obj.value = int(handle.value) - 100
            return 0

        def waveOutClose(self, _handle):
            return 0

        def waveOutReset(self, _handle):
            return 0

        def waveOutMessage(self, *_args):
            return 1  # unavailable in unit mock

    monkeypatch.setattr(mr, "_winmm", _FakeWinMM())
    monkeypatch.setattr(mr, "list_playback_devices", lambda: list(state["devices"]))
    monkeypatch.setattr(
        mr,
        "_get_waveout_device_name",
        lambda dev_id: dict(state["devices"]).get(dev_id, ""),
    )

    sink = WinMMWaveOutSink()
    assert sink._device_id == 0

    # Windows changes default playback device -> physical speakers become index 0, CABLE Input becomes index 2!
    state["devices"] = [
        (0, "LED MONITOR (HD Audio Driver fo"),
        (1, "Headphones (WZ-BT5.0)"),
        (2, "CABLE Input (VB-Audio Virtual C"),
    ]

    assert sink._ensure_open_locked() is True
    assert opened_ids == [2]  # MUST open index 2 (CABLE Input), NEVER stale index 0 (LED MONITOR)!
    sink.close()

