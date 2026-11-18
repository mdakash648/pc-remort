"""Tests for RemotePad PC screen capture, RSCR wire framing, P2P chunking, and absolute cursor control."""

from __future__ import annotations

import asyncio
from typing import Any

from PIL import Image

from core.p2p import (
    PKT_SCREEN_ACK,
    PKT_SCREEN_CHUNK,
    P2PUdpEndpoint,
    derive_auth_tag,
    pack_control_packet,
    pack_screen_chunk,
    unpack_screen_chunk,
)
from core.protocol import (
    MoveAbsMessage,
    ScreenAckMessage,
    ScreenConfigMessage,
    ScreenStartMessage,
    ScreenStopMessage,
    parse_message,
)
from features.screen_capture import (
    CapturedFrame,
    ScreenStreamer,
    encode_jpeg_bounded,
    pack_screen_frame,
    resolve_quality_profile,
    unpack_screen_frame,
)
from input.mouse import MOUSEEVENTF_ABSOLUTE, MOUSEEVENTF_MOVE, MOUSEEVENTF_VIRTUALDESK, MouseController


class _FakeCapturer:
    def __init__(self, width: int = 320, height: int = 180) -> None:
        self.width = width
        self.height = height
        self.counter = 0

    def get_monitor_rect(self, monitor_index: int) -> tuple[int, int, int, int]:
        return (0, 0, self.width, self.height)

    def capture(self, monitor_index: int = 0) -> CapturedFrame:
        self.counter += 1
        color = ((self.counter * 37) % 255, (self.counter * 73) % 255, 120)
        img = Image.new("RGB", (self.width, self.height), color)
        return CapturedFrame(
            image=img,
            monitor_index=monitor_index,
            monitor_count=2,
            cursor_x=32768,
            cursor_y=16384,
            has_cursor=True,
        )


def test_parse_screen_and_move_abs_messages() -> None:
    m1 = parse_message('{"t":"move_abs","x":0.25,"y":0.75,"monitor":1}')
    assert isinstance(m1, MoveAbsMessage)
    assert m1.x == 0.25 and m1.y == 0.75 and m1.monitor == 1

    # Clamping out-of-range coordinates
    m1_clamped = parse_message('{"t":"move_abs","x":1.5,"y":-0.2}')
    assert isinstance(m1_clamped, MoveAbsMessage)
    assert m1_clamped.x == 1.0 and m1_clamped.y == 0.0 and m1_clamped.monitor == 0

    m2 = parse_message('{"t":"screen_start","quality":"hd","fps":24,"max_width":1280,"monitor":1,"prefer_p2p":false}')
    assert isinstance(m2, ScreenStartMessage)
    assert m2.quality == "hd"
    assert m2.fps == 24
    assert m2.max_width == 1280
    assert m2.monitor == 1
    assert m2.prefer_p2p is False

    m3 = parse_message('{"t":"screen_cfg","quality":"fast","monitor":0}')
    assert isinstance(m3, ScreenConfigMessage)
    assert m3.quality == "fast" and m3.monitor == 0 and m3.fps is None

    m4 = parse_message('{"t":"screen_ack","seq":42}')
    assert isinstance(m4, ScreenAckMessage) and m4.seq == 42

    m5 = parse_message('{"t":"screen_stop"}')
    assert isinstance(m5, ScreenStopMessage)

    assert parse_message('{"t":"screen_ack","seq":-1}') is None
    assert parse_message('{"t":"move_abs","x":"bad","y":0.5}') is None


def test_rscr_pack_unpack_roundtrip() -> None:
    payload = b"\xff\xd8\xff\xe0dummy-jpeg-bytes\xff\xd9"
    wire = pack_screen_frame(
        seq=12345,
        monitor_index=1,
        monitor_count=2,
        width=1280,
        height=720,
        cursor_x=40000,
        cursor_y=20000,
        has_cursor=True,
        payload=payload,
    )
    unpacked = unpack_screen_frame(wire)
    assert unpacked is not None
    assert unpacked.seq == 12345
    assert unpacked.monitor_index == 1
    assert unpacked.monitor_count == 2
    assert unpacked.width == 1280
    assert unpacked.height == 720
    assert unpacked.cursor_x == 40000
    assert unpacked.cursor_y == 20000
    assert unpacked.has_cursor is True
    assert unpacked.payload == payload
    assert unpack_screen_frame(b"BAD!") is None


def test_encode_jpeg_bounded_respects_size_and_width() -> None:
    img = Image.new("RGB", (1920, 1080), (40, 110, 210))
    data, w, h = encode_jpeg_bounded(img, max_width=960, jpeg_quality=65, max_bytes=100_000)
    assert w == 960
    assert h == 540
    assert len(data) <= 100_000
    assert data[:2] == b"\xff\xd8"


def test_quality_profiles_adapt_to_transport() -> None:
    lan_w, lan_q, lan_fps = resolve_quality_profile("auto", "lan", 30, 1920)
    p2p_w, p2p_q, p2p_fps = resolve_quality_profile("auto", "p2p", 30, 1920)
    vps_w, vps_q, vps_fps = resolve_quality_profile("auto", "vps", 30, 1920)
    assert lan_w >= p2p_w >= vps_w
    assert lan_q >= p2p_q >= vps_q
    assert lan_fps >= p2p_fps >= vps_fps


def test_p2p_screen_chunk_pack_unpack_and_endpoint_tx_rx() -> None:
    tag = derive_auth_tag("rpad-secret-token", "sid-123456")
    chunk = pack_screen_chunk(tag, frame_seq=7, chunk_idx=2, total_chunks=5, chunk_payload=b"hello-chunk")
    parsed = unpack_screen_chunk(chunk)
    assert parsed is not None
    p_tag, p_seq, p_idx, p_tot, p_body = parsed
    assert p_tag == tag
    assert p_seq == 7
    assert p_idx == 2
    assert p_tot == 5
    assert p_body == b"hello-chunk"

    acked: list[int] = []
    sent_datagrams: list[tuple[bytes, tuple[str, int]]] = []

    class _FakeTransport:
        def is_closing(self) -> bool:
            return False

        def sendto(self, data: bytes, addr: tuple[str, int]) -> None:
            sent_datagrams.append((data, addr))

    ep = P2PUdpEndpoint(
        token="rpad-secret-token",
        on_move=lambda _x, _y: None,
        on_scroll=lambda _x, _y: None,
        on_screen_ack=lambda seq: acked.append(seq),
    )
    ep._transport = _FakeTransport()  # type: ignore[assignment]
    ep._auth_tag = tag
    ep._peer_addr = ("203.0.113.5", 55555)
    ep._p2p_active = True

    wire = pack_screen_frame(
        seq=99,
        monitor_index=0,
        monitor_count=1,
        width=640,
        height=360,
        cursor_x=0,
        cursor_y=0,
        has_cursor=False,
        payload=b"X" * 3000,
    )
    assert ep.send_screen_frame(wire) is True
    assert len(sent_datagrams) == 3

    # Reassemble chunks and verify byte-for-byte identity
    pieces: dict[int, bytes] = {}
    for raw, addr in sent_datagrams:
        assert addr == ("203.0.113.5", 55555)
        u = unpack_screen_chunk(raw)
        assert u is not None
        assert u[0] == tag and u[1] == 99 and u[3] == 3
        pieces[u[2]] = u[4]
    reassembled = b"".join(pieces[i] for i in range(3))
    assert reassembled == wire

    # Verify PKT_SCREEN_ACK triggers callback
    ack_pkt = pack_control_packet(PKT_SCREEN_ACK, tag, 99, 123456789)
    ep._on_datagram(ack_pkt, ("203.0.113.5", 55555))
    assert acked == [99]


def test_screen_streamer_ws_and_p2p_routing() -> None:
    async def _run() -> None:
        ws_frames: list[bytes] = []
        p2p_frames: list[bytes] = []
        p2p_active = False

        async def send_ws(data: bytes) -> None:
            ws_frames.append(data)
            frame = unpack_screen_frame(data)
            assert frame is not None
            streamer.on_ack(frame.seq)

        def send_p2p(data: bytes) -> bool:
            p2p_frames.append(data)
            frame = unpack_screen_frame(data)
            assert frame is not None
            streamer.on_ack(frame.seq)
            return True

        streamer = ScreenStreamer(
            send_ws_binary=send_ws,
            capturer=_FakeCapturer(),  # type: ignore[arg-type]
            send_p2p_frame=send_p2p,
            is_p2p_active=lambda: p2p_active,
            is_via_relay=True,
        )

        # Starts on VPS WS while P2P is inactive
        await streamer.start(quality="fast", fps=30, max_width=640, monitor=0, prefer_p2p=True)
        await asyncio.sleep(0.15)
        assert len(ws_frames) >= 1
        assert len(p2p_frames) == 0

        # Once P2P becomes active, frames switch automatically to P2P UDP
        p2p_active = True
        await asyncio.sleep(0.15)
        assert len(p2p_frames) >= 1

        # Disabling prefer_p2p switches cleanly back to WS
        ws_before = len(ws_frames)
        streamer.update_config(prefer_p2p=False)
        await asyncio.sleep(0.15)
        assert len(ws_frames) > ws_before

        await streamer.stop()
        assert streamer.is_running is False

    asyncio.run(_run())


def test_mouse_move_absolute_single_and_multimonitor() -> None:
    sent: list[Any] = []
    mouse = MouseController(sender=lambda events: sent.extend(events) or len(events))

    # Single monitor normalized center
    ax, ay = mouse.move_absolute(0.5, 0.5)
    assert ax == 32768 and ay == 32768
    assert sent[-1].mi.dwFlags == (MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE)

    # Dual monitor: right monitor [1920, 0, 3840, 1080] inside virtual [0, 0, 3840, 1080]
    vx, vy = mouse.move_absolute(
        0.5,
        0.5,
        monitor_rect=(1920, 0, 3840, 1080),
        virtual_rect=(0, 0, 3840, 1080),
    )
    assert 48500 <= vx <= 49600  # ~75% of total virtual desktop width
    assert 32500 <= vy <= 33000  # ~50% of virtual desktop height
    assert sent[-1].mi.dwFlags == (MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK)


def test_screen_streamer_detects_single_pixel_change_immediately() -> None:
    class _SinglePixelCapturer:
        def __init__(self) -> None:
            self.flip = False

        def get_monitor_rect(self, monitor_index: int) -> tuple[int, int, int, int]:
            return (0, 0, 128, 72)

        def capture(self, monitor_index: int = 0) -> CapturedFrame:
            img = Image.new("RGB", (128, 72), (10, 20, 30))
            if self.flip:
                # Change literally 1 single pixel out of 9,216 pixels
                img.putpixel((77, 43), (255, 255, 255))
            return CapturedFrame(
                image=img,
                monitor_index=monitor_index,
                monitor_count=1,
                cursor_x=100,
                cursor_y=100,
                has_cursor=True,
            )

    cap = _SinglePixelCapturer()
    streamer = ScreenStreamer(send_ws_binary=lambda _b: asyncio.sleep(0), capturer=cap)  # type: ignore[arg-type]

    first = streamer._capture_and_encode_sync(0, 1920, 80, 1, force_send=False)
    assert first is not None
    streamer._last_signature = first[8]

    # Static identical frame -> skipped
    same = streamer._capture_and_encode_sync(0, 1920, 80, 1, force_send=False)
    assert same is None

    # Single pixel flips -> MUST be detected immediately (never missed!)
    cap.flip = True
    changed = streamer._capture_and_encode_sync(0, 1920, 80, 1, force_send=False)
    assert changed is not None


def test_fast_quality_profile_and_compact_remote_budgets() -> None:
    from features.screen_capture import resolve_jpeg_subsampling, resolve_max_frame_bytes

    for transport in ("lan", "p2p", "vps"):
        w, q, fps = resolve_quality_profile("fast", transport, 30, 960)
        assert w == 960
        assert fps == 30
        assert resolve_jpeg_subsampling("fast", transport) == 2

    assert resolve_max_frame_bytes("fast", "p2p") <= 32 * 1024
    assert resolve_max_frame_bytes("fast", "vps") <= 32 * 1024
    assert resolve_max_frame_bytes("auto", "p2p") <= 64 * 1024


def test_p2p_paced_screen_frame_sender_and_monotonic_seq() -> None:
    async def _run() -> None:
        tag = derive_auth_tag("rpad-secret-token", "sid-paced-01")
        sent_datagrams: list[tuple[bytes, tuple[str, int]]] = []

        class _FakeTransport:
            def is_closing(self) -> bool:
                return False

            def sendto(self, data: bytes, addr: tuple[str, int]) -> None:
                sent_datagrams.append((data, addr))

        ep = P2PUdpEndpoint(
            token="rpad-secret-token",
            on_move=lambda _x, _y: None,
            on_scroll=lambda _x, _y: None,
        )
        ep._transport = _FakeTransport()  # type: ignore[assignment]
        ep._auth_tag = tag
        ep._peer_addr = ("203.0.113.9", 54321)
        ep._p2p_active = True

        wire = pack_screen_frame(
            seq=42,
            monitor_index=0,
            monitor_count=1,
            width=960,
            height=540,
            cursor_x=0,
            cursor_y=0,
            has_cursor=False,
            payload=b"Z" * 12000,
        )
        ok = await ep.send_screen_frame_paced(wire, burst_packets=4, burst_delay_s=0.0, max_total_pace_s=0.0)
        assert ok is True
        assert len(sent_datagrams) == 11  # ceil(12020 / 1120)

        pieces: dict[int, bytes] = {}
        for raw, _addr in sent_datagrams:
            u = unpack_screen_chunk(raw)
            assert u is not None
            pieces[u[2]] = u[4]
        assert b"".join(pieces[i] for i in range(len(sent_datagrams))) == wire

        # Verify ScreenStreamer continues monotonically from initial_seq
        received_seqs: list[int] = []

        async def send_ws(data: bytes) -> None:
            f = unpack_screen_frame(data)
            assert f is not None
            received_seqs.append(f.seq)
            streamer.on_ack(f.seq)

        streamer = ScreenStreamer(
            send_ws_binary=send_ws,
            capturer=_FakeCapturer(),  # type: ignore[arg-type]
            is_via_relay=False,
            initial_seq=100,
        )
        await streamer.start(quality="fast", fps=30, max_width=960)
        await asyncio.sleep(0.10)
        await streamer.stop()
        assert received_seqs and received_seqs[0] == 101
        assert streamer.last_seq >= 101

    asyncio.run(_run())


def test_screen_streamer_adaptive_backoff_and_recovery() -> None:
    streamer = ScreenStreamer(
        send_ws_binary=lambda _b: asyncio.sleep(0),  # type: ignore[arg-type]
        capturer=_FakeCapturer(),  # type: ignore[arg-type]
        is_via_relay=True,
    )
    assert streamer._adapt_q_drop == 0
    assert streamer._adapt_budget_ratio == 1.0

    # P2P timeout (packet loss) shrinks quality & byte budget immediately
    streamer._apply_timeout_backoff("p2p")
    assert streamer._adapt_q_drop > 0
    assert streamer._adapt_budget_ratio < 1.0
    assert streamer._force_keyframe is True

    # Fast ACK gradually recovers quality & budget
    prev_drop = streamer._adapt_q_drop
    prev_budget = streamer._adapt_budget_ratio
    streamer._sent_seq = 5
    streamer._sent_times[5] = (0.0, "p2p")
    # Simulate fast RTT by placing timestamp right near monotonic()
    import time as _time

    streamer._sent_times[5] = (_time.monotonic() - 0.02, "p2p")
    streamer.on_ack(5)
    assert streamer._adapt_q_drop < prev_drop
    assert streamer._adapt_budget_ratio > prev_budget
    assert streamer._p2p_acked_count == 1


def test_hide_cursor_protocol_and_dirty_check_skips_cursor_only_moves() -> None:
    m_start = parse_message('{"t":"screen_start","quality":"fast","fps":30,"max_width":960,"hide_cursor":true}')
    assert isinstance(m_start, ScreenStartMessage)
    assert m_start.hide_cursor is True

    m_cfg = parse_message('{"t":"screen_cfg","hide_cursor":false}')
    assert isinstance(m_cfg, ScreenConfigMessage)
    assert m_cfg.hide_cursor is False

    class _CursorOnlyMoveCapturer:
        def __init__(self) -> None:
            self.cx = 1000
            self.cy = 2000

        def get_monitor_rect(self, monitor_index: int) -> tuple[int, int, int, int]:
            return (0, 0, 128, 72)

        def capture(self, monitor_index: int = 0) -> CapturedFrame:
            return CapturedFrame(
                image=Image.new("RGB", (128, 72), (15, 25, 35)),
                monitor_index=monitor_index,
                monitor_count=1,
                cursor_x=self.cx,
                cursor_y=self.cy,
                has_cursor=True,
            )

    cap = _CursorOnlyMoveCapturer()
    streamer = ScreenStreamer(send_ws_binary=lambda _b: asyncio.sleep(0), capturer=cap)  # type: ignore[arg-type]

    first = streamer._capture_and_encode_sync(0, 960, 50, 2, force_send=False, draw_cursor=False)
    assert first is not None
    streamer._last_signature = first[8]

    # Moving cursor on a static desktop with draw_cursor=False (local phone cursor mode) -> no redundant frame!
    cap.cx = 40000
    cap.cy = 50000
    skipped = streamer._capture_and_encode_sync(0, 960, 50, 2, force_send=False, draw_cursor=False)
    assert skipped is None

    # With draw_cursor=True (LAN mode), cursor move still produces a frame
    streamer._last_signature = None
    first_lan = streamer._capture_and_encode_sync(0, 960, 50, 2, force_send=False, draw_cursor=True)
    assert first_lan is not None
    streamer._last_signature = first_lan[8]
    cap.cx = 55000
    moved_lan = streamer._capture_and_encode_sync(0, 960, 50, 2, force_send=False, draw_cursor=True)
    assert moved_lan is not None

