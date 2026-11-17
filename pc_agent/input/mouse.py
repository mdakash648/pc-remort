"""Windows SendInput mouse controller using ctypes."""

from __future__ import annotations

import ctypes
import logging
import math
import sys
import threading
import time
from ctypes import wintypes
from typing import Callable, Sequence

logger = logging.getLogger(__name__)

_user32 = ctypes.WinDLL("user32", use_last_error=True) if hasattr(ctypes, "WinDLL") else None
_winmm = ctypes.WinDLL("winmm") if hasattr(ctypes, "WinDLL") and sys.platform == "win32" else None
_last_send_error_log = 0.0

# Windows SendInput constants
INPUT_MOUSE = 0
INPUT_KEYBOARD = 1
INPUT_HARDWARE = 2

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800
MOUSEEVENTF_HWHEEL = 0x1000

WHEEL_DELTA = 120

ULONG_PTR = ctypes.c_size_t


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx", wintypes.LONG),
        ("dy", wintypes.LONG),
        ("mouseData", wintypes.DWORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    ]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", wintypes.WORD),
        ("wScan", wintypes.WORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    ]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [
        ("uMsg", wintypes.DWORD),
        ("wParamL", wintypes.WORD),
        ("wParamH", wintypes.WORD),
    ]


class _INPUT_UNION(ctypes.Union):
    _fields_ = [
        ("mi", MOUSEINPUT),
        ("ki", KEYBDINPUT),
        ("hi", HARDWAREINPUT),
    ]


class INPUT(ctypes.Structure):
    _anonymous_ = ("_input",)
    _fields_ = [
        ("type", wintypes.DWORD),
        ("_input", _INPUT_UNION),
    ]


_BUTTON_FLAGS: dict[tuple[str, str], int] = {
    ("left", "down"): MOUSEEVENTF_LEFTDOWN,
    ("left", "up"): MOUSEEVENTF_LEFTUP,
    ("right", "down"): MOUSEEVENTF_RIGHTDOWN,
    ("right", "up"): MOUSEEVENTF_RIGHTUP,
    ("middle", "down"): MOUSEEVENTF_MIDDLEDOWN,
    ("middle", "up"): MOUSEEVENTF_MIDDLEUP,
}


def _make_mouse_input(
    flags: int,
    dx: int = 0,
    dy: int = 0,
    mouse_data: int = 0,
) -> INPUT:
    return INPUT(
        type=INPUT_MOUSE,
        mi=MOUSEINPUT(
            dx=dx,
            dy=dy,
            mouseData=mouse_data & 0xFFFFFFFF,
            dwFlags=flags,
            time=0,
            dwExtraInfo=0,
        ),
    )


if _user32 is not None:
    _user32.SendInput.argtypes = (
        wintypes.UINT,
        ctypes.POINTER(INPUT),
        ctypes.c_int,
    )
    _user32.SendInput.restype = wintypes.UINT


def _send_inputs_windows(inputs: Sequence[INPUT]) -> int:
    global _last_send_error_log
    if not inputs or _user32 is None:
        return 0
    array_type = INPUT * len(inputs)
    arr = array_type(*inputs)
    ctypes.set_last_error(0)
    sent = _user32.SendInput(
        len(inputs),
        arr,
        ctypes.sizeof(INPUT),
    )
    if sent != len(inputs):
        err = ctypes.get_last_error()
        now = time.monotonic()
        if now - _last_send_error_log >= 5.0:
            _last_send_error_log = now
            if err == 5:
                logger.warning(
                    "SendInput blocked (ERROR_ACCESS_DENIED). "
                    "Make sure pc_agent is started from your normal Windows desktop session "
                    "(or as Administrator when controlling elevated windows)."
                )
            else:
                logger.warning(
                    "SendInput sent %d/%d events (win32 error=%d)",
                    sent,
                    len(inputs),
                    err,
                )
    return int(sent)


def _begin_high_res_timer() -> bool:
    """Request 1 ms Windows multimedia timer resolution for smooth cursor pacing."""
    if _winmm is None:
        return False
    try:
        return int(_winmm.timeBeginPeriod(1)) == 0
    except OSError:
        return False


def _end_high_res_timer() -> None:
    if _winmm is None:
        return
    try:
        _winmm.timeEndPeriod(1)
    except OSError:
        pass


_REFERENCE_FRAME_DT = 1.0 / 60.0
_IDLE_RESET_DT = 0.15


class MouseController:
    """Translates protocol mouse actions into Windows SendInput calls."""

    def __init__(
        self,
        sensitivity: float = 1.0,
        acceleration: float = 0.35,
        scroll_sensitivity: float = 1.0,
        natural_scroll: bool = False,
        sender: Callable[[Sequence[INPUT]], int] | None = None,
    ) -> None:
        self.sensitivity = sensitivity
        self.acceleration = acceleration
        self.scroll_sensitivity = scroll_sensitivity
        self.natural_scroll = natural_scroll
        self._sender = sender or _send_inputs_windows

        self._lock = threading.Lock()
        # Fractional remainders prevent slow finger movements from truncating to 0
        self._rem_x: float = 0.0
        self._rem_y: float = 0.0
        self._scroll_rem_x: float = 0.0
        self._scroll_rem_y: float = 0.0
        self._last_move_time: float | None = None
        self._ema_dt: float = _REFERENCE_FRAME_DT

    def scale_delta(self, dx: float, dy: float, now: float | None = None) -> tuple[float, float]:
        """Apply sensitivity and velocity-normalized acceleration to a raw move delta.

        Normalizing acceleration by smoothed inter-packet interval prevents Wi-Fi
        packet coalescing/jitter from artificially spiking cursor jumps.
        """
        if dx == 0.0 and dy == 0.0:
            return (0.0, 0.0)

        ts = time.monotonic() if now is None else now
        with self._lock:
            if self._last_move_time is None or (ts - self._last_move_time) > _IDLE_RESET_DT:
                self._ema_dt = _REFERENCE_FRAME_DT
            else:
                elapsed = ts - self._last_move_time
                clamped_dt = min(max(elapsed, 0.006), 0.040)
                self._ema_dt = 0.75 * self._ema_dt + 0.25 * clamped_dt
            self._last_move_time = ts
            effective_dt = self._ema_dt

        mag = math.hypot(dx, dy)
        speed_per_frame = mag * (_REFERENCE_FRAME_DT / effective_dt)
        accel_factor = 1.0 + self.acceleration * math.log1p(speed_per_frame * 0.25)
        scale = self.sensitivity * accel_factor
        return (dx * scale, dy * scale)

    def move_pixels(self, px: float, py: float) -> tuple[int, int]:
        """Emit an already-scaled floating-point pixel delta with sub-pixel carry."""
        if px == 0.0 and py == 0.0:
            return (0, 0)

        with self._lock:
            total_x = px + self._rem_x
            total_y = py + self._rem_y

            ix = int(math.trunc(total_x + math.copysign(1e-9, total_x))) if total_x != 0.0 else 0
            iy = int(math.trunc(total_y + math.copysign(1e-9, total_y))) if total_y != 0.0 else 0

            self._rem_x = total_x - ix
            self._rem_y = total_y - iy

        if ix != 0 or iy != 0:
            self._sender([_make_mouse_input(MOUSEEVENTF_MOVE, dx=ix, dy=iy)])
        return (ix, iy)

    def move(self, dx: float, dy: float) -> tuple[int, int]:
        """Move cursor by relative delta with sensitivity, acceleration, and sub-pixel carry."""
        if dx == 0.0 and dy == 0.0:
            return (0, 0)
        sx, sy = self.scale_delta(dx, dy)
        return self.move_pixels(sx, sy)

    def button(self, button: str, state: str) -> bool:
        """Press or release left/middle/right mouse button."""
        flag = _BUTTON_FLAGS.get((button, state))
        if flag is None:
            return False
        self._sender([_make_mouse_input(flag)])
        return True

    def click(self, button: str, count: int = 1) -> bool:
        """Click left/middle/right mouse button `count` times."""
        down_flag = _BUTTON_FLAGS.get((button, "down"))
        up_flag = _BUTTON_FLAGS.get((button, "up"))
        if down_flag is None or up_flag is None or count < 1:
            return False

        events: list[INPUT] = []
        for _ in range(count):
            events.append(_make_mouse_input(down_flag))
            events.append(_make_mouse_input(up_flag))
        self._sender(events)
        return True

    def scroll(self, dx: float, dy: float) -> tuple[int, int]:
        """Scroll vertically (dy) and horizontally (dx) in wheel-notch units.

        Convention: positive `dy` scrolls up (away from user), negative `dy` scrolls down,
        unless `natural_scroll` is enabled (which inverts both axes).
        """
        direction = -1.0 if self.natural_scroll else 1.0
        scale = self.scroll_sensitivity * WHEEL_DELTA * direction

        with self._lock:
            total_x = dx * scale + self._scroll_rem_x
            total_y = dy * scale + self._scroll_rem_y

            ix = int(math.trunc(total_x))
            iy = int(math.trunc(total_y))

            self._scroll_rem_x = total_x - ix
            self._scroll_rem_y = total_y - iy

        events: list[INPUT] = []
        if iy != 0:
            events.append(_make_mouse_input(MOUSEEVENTF_WHEEL, mouse_data=iy))
        if ix != 0:
            events.append(_make_mouse_input(MOUSEEVENTF_HWHEEL, mouse_data=ix))

        if events:
            self._sender(events)
        return (ix, iy)

    def reset_accumulators(self) -> None:
        with self._lock:
            self._rem_x = 0.0
            self._rem_y = 0.0
            self._scroll_rem_x = 0.0
            self._scroll_rem_y = 0.0
            self._last_move_time = None
            self._ema_dt = _REFERENCE_FRAME_DT


class CursorSmoother:
    """High-rate (250 Hz) low-latency cursor pacer that eliminates Wi-Fi burst stutter.

    Incoming network move deltas are scaled immediately and drained smoothly over a short
    time constant (default 9 ms) so Wi-Fi micro-bursts and 60 Hz->120/144 Hz refresh
    mismatches never cause visible cursor freezes or teleport jumps.
    """

    _SNAP_THRESHOLD_PX = 1.25

    def __init__(
        self,
        mouse: MouseController,
        enabled: bool = True,
        tau_ms: float = 9.0,
        tick_hz: float = 250.0,
    ) -> None:
        self._mouse = mouse
        self.enabled = enabled and tau_ms > 0.0
        self._tau = max(tau_ms, 0.0) / 1000.0
        self._tick_interval = 1.0 / max(tick_hz, 60.0)

        self._lock = threading.Lock()
        self._pending_x: float = 0.0
        self._pending_y: float = 0.0
        self._wake = threading.Event()
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None
        self._high_res_timer_active = False

    def start(self) -> None:
        if not self.enabled:
            return
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return
            self._stop_event.clear()
            self._wake.clear()
            self._high_res_timer_active = _begin_high_res_timer()
            self._thread = threading.Thread(
                target=self._run_loop,
                name="remotepad-cursor-smoother",
                daemon=True,
            )
            self._thread.start()

    def stop(self) -> None:
        self.flush()
        self._stop_event.set()
        self._wake.set()
        thread = self._thread
        if thread is not None and thread.is_alive():
            thread.join(timeout=0.5)
        self._thread = None
        if self._high_res_timer_active:
            _end_high_res_timer()
            self._high_res_timer_active = False

    def enqueue(self, dx: float, dy: float) -> None:
        if dx == 0.0 and dy == 0.0:
            return
        sx, sy = self._mouse.scale_delta(dx, dy)
        if not self.enabled or self._thread is None or not self._thread.is_alive():
            self._mouse.move_pixels(sx, sy)
            return

        with self._lock:
            self._pending_x += sx
            self._pending_y += sy
            self._wake.set()

    def flush(self) -> None:
        """Immediately emit all remaining smoothed cursor motion (e.g., before click)."""
        with self._lock:
            px = self._pending_x
            py = self._pending_y
            self._pending_x = 0.0
            self._pending_y = 0.0
        if px != 0.0 or py != 0.0:
            self._mouse.move_pixels(px, py)

    def reset(self) -> None:
        """Discard any pending motion (used on client disconnect)."""
        with self._lock:
            self._pending_x = 0.0
            self._pending_y = 0.0

    def _run_loop(self) -> None:
        last_tick = time.perf_counter()
        was_idle = True

        while not self._stop_event.is_set():
            with self._lock:
                has_pending = self._pending_x != 0.0 or self._pending_y != 0.0
                if not has_pending:
                    self._wake.clear()

            if not has_pending:
                was_idle = True
                self._wake.wait()
                continue

            now = time.perf_counter()
            if was_idle:
                dt = self._tick_interval
                was_idle = False
            else:
                dt = min(max(now - last_tick, 0.001), 0.050)
            last_tick = now

            with self._lock:
                px = self._pending_x
                py = self._pending_y
                dist = math.hypot(px, py)
                if dist == 0.0:
                    continue
                if dist <= self._SNAP_THRESHOLD_PX:
                    step_x = px
                    step_y = py
                    self._pending_x = 0.0
                    self._pending_y = 0.0
                else:
                    alpha = 1.0 - math.exp(-dt / self._tau)
                    step_x = px * alpha
                    step_y = py * alpha
                    self._pending_x = px - step_x
                    self._pending_y = py - step_y

            self._mouse.move_pixels(step_x, step_y)
            time.sleep(self._tick_interval)
