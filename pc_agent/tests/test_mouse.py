"""Unit tests for pc_agent.input.mouse."""

from __future__ import annotations

import ctypes
import sys
import time

from input.mouse import (
    INPUT,
    INPUT_MOUSE,
    MOUSEEVENTF_HWHEEL,
    MOUSEEVENTF_LEFTDOWN,
    MOUSEEVENTF_LEFTUP,
    MOUSEEVENTF_MIDDLEUP,
    MOUSEEVENTF_MOVE,
    MOUSEEVENTF_RIGHTDOWN,
    MOUSEEVENTF_WHEEL,
    WHEEL_DELTA,
    CursorSmoother,
    MouseController,
)


def test_input_struct_size_on_windows() -> None:
    if sys.platform == "win32":
        expected = 40 if ctypes.sizeof(ctypes.c_void_p) == 8 else 28
        assert ctypes.sizeof(INPUT) == expected


def test_move_fractional_accumulation() -> None:
    captured: list[INPUT] = []
    mouse = MouseController(
        sensitivity=1.0,
        acceleration=0.0,
        sender=lambda events: (captured.extend(events), len(events))[1],
    )

    assert mouse.move(0.4, 0.0) == (0, 0)
    assert len(captured) == 0

    assert mouse.move(0.4, 0.0) == (0, 0)
    assert len(captured) == 0

    assert mouse.move(0.4, 0.0) == (1, 0)
    assert len(captured) == 1
    assert captured[0].type == INPUT_MOUSE
    assert captured[0].mi.dwFlags == MOUSEEVENTF_MOVE
    assert captured[0].mi.dx == 1
    assert captured[0].mi.dy == 0


def test_move_acceleration_increases_for_faster_motion() -> None:
    captured: list[INPUT] = []
    mouse = MouseController(
        sensitivity=1.0,
        acceleration=0.5,
        sender=lambda events: (captured.extend(events), len(events))[1],
    )
    slow = mouse.move(2.0, 0.0)
    mouse.reset_accumulators()
    fast = mouse.move(40.0, 0.0)
    # Fast movement ratio per unit delta must exceed slow movement ratio
    assert (fast[0] / 40.0) > (slow[0] / 2.0)


def test_button_and_click() -> None:
    captured: list[INPUT] = []
    mouse = MouseController(sender=lambda events: (captured.extend(events), len(events))[1])

    assert mouse.button("right", "down") is True
    assert mouse.button("middle", "up") is True
    assert mouse.button("invalid", "down") is False
    assert [e.mi.dwFlags for e in captured] == [MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_MIDDLEUP]

    captured.clear()
    assert mouse.click("left", count=2) is True
    assert [e.mi.dwFlags for e in captured] == [
        MOUSEEVENTF_LEFTDOWN,
        MOUSEEVENTF_LEFTUP,
        MOUSEEVENTF_LEFTDOWN,
        MOUSEEVENTF_LEFTUP,
    ]


def test_scroll_vertical_horizontal_and_natural() -> None:
    captured: list[INPUT] = []
    mouse = MouseController(
        scroll_sensitivity=1.0,
        natural_scroll=False,
        sender=lambda events: (captured.extend(events), len(events))[1],
    )

    ix, iy = mouse.scroll(1.0, -2.0)
    assert (ix, iy) == (WHEEL_DELTA, -2 * WHEEL_DELTA)
    assert len(captured) == 2
    assert captured[0].mi.dwFlags == MOUSEEVENTF_WHEEL
    assert captured[0].mi.mouseData == (-2 * WHEEL_DELTA) & 0xFFFFFFFF
    assert captured[1].mi.dwFlags == MOUSEEVENTF_HWHEEL
    assert captured[1].mi.mouseData == WHEEL_DELTA

    captured.clear()
    mouse.natural_scroll = True
    mouse.reset_accumulators()
    ix2, iy2 = mouse.scroll(0.0, -1.0)
    assert (ix2, iy2) == (0, WHEEL_DELTA)
    assert captured[0].mi.mouseData == WHEEL_DELTA


def test_cursor_smoother_splits_large_jump_and_preserves_total_distance() -> None:
    captured: list[INPUT] = []
    mouse = MouseController(
        sensitivity=1.0,
        acceleration=0.0,
        sender=lambda events: (captured.extend(events), len(events))[1],
    )
    smoother = CursorSmoother(mouse=mouse, enabled=True, tau_ms=9.0, tick_hz=250.0)
    smoother.start()
    try:
        smoother.enqueue(30.0, -15.0)
        time.sleep(0.08)
        smoother.flush()
    finally:
        smoother.stop()

    move_events = [e for e in captured if e.mi.dwFlags == MOUSEEVENTF_MOVE]
    assert len(move_events) >= 2
    assert sum(e.mi.dx for e in move_events) == 30
    assert sum(e.mi.dy for e in move_events) == -15


def test_cursor_smoother_flush_completes_immediately() -> None:
    captured: list[INPUT] = []
    mouse = MouseController(
        sensitivity=1.0,
        acceleration=0.0,
        sender=lambda events: (captured.extend(events), len(events))[1],
    )
    smoother = CursorSmoother(mouse=mouse, enabled=True, tau_ms=15.0, tick_hz=250.0)
    smoother.start()
    try:
        smoother.enqueue(24.0, 12.0)
        smoother.flush()
    finally:
        smoother.stop()

    move_events = [e for e in captured if e.mi.dwFlags == MOUSEEVENTF_MOVE]
    assert sum(e.mi.dx for e in move_events) == 24
    assert sum(e.mi.dy for e in move_events) == 12
