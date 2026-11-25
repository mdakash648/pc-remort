"""Real-time Windows screen capture & adaptive streaming for RemotePad (AnyDesk-style).

Supports:
- Ultra-fast Win32 GDI BitBlt + GetDIBits capture with zero extra third-party dependencies.
- Multi-monitor enumeration and live monitor switching.
- Real Windows mouse cursor rendering onto captured frames + normalized cursor coordinates.
- Lock Screen / UAC desktop capture via ensure_thread_on_input_desktop().
- Compact 20-byte binary RSCR header + JPEG payload (works seamlessly over Local Wi-Fi WS,
  Direct P2P UDP chunking, and Internet VPS WebSocket relay without exceeding 512 KB).
- Dirty-frame skipping and flow-controlled frame pacing (in-flight window = 2) for zero lag buildup.
"""

from __future__ import annotations

import asyncio
import ctypes
import inspect
import io
import logging
import struct
import sys
import threading
import time
import zlib
from collections.abc import Awaitable, Callable
from concurrent.futures import ThreadPoolExecutor
from ctypes import wintypes
from dataclasses import dataclass
from typing import Any

from PIL import Image

logger = logging.getLogger(__name__)

SCREEN_MAGIC = b"RSCR"
SCREEN_HEADER_STRUCT = struct.Struct(">4sBBBBIHHHH")
SCREEN_HEADER_SIZE = SCREEN_HEADER_STRUCT.size  # 20 bytes

FLAG_CODEC_JPEG = 0x00
FLAG_KEYFRAME = 0x02
FLAG_HAS_CURSOR = 0x04
FLAG_ROI = 0x08

SCREEN_ROI_HEADER_STRUCT = struct.Struct(">4sBBBBIHHHHHHHH")
SCREEN_ROI_HEADER_SIZE = SCREEN_ROI_HEADER_STRUCT.size  # 28 bytes

MAX_FRAME_PAYLOAD_BYTES = 360 * 1024  # Safely below VPS relay 512 KB maxPayload

# Win32 GDI / Cursor constants
_SRCCOPY = 0x00CC0020
_CAPTUREBLT = 0x40000000
_DIB_RGB_COLORS = 0
_BI_RGB = 0
_CURSOR_SHOWING = 0x00000001
_DI_NORMAL = 0x0003
_MONITORINFOF_PRIMARY = 0x00000001
_COLORONCOLOR = 3
_SM_CXCURSOR = 13
_SM_CYCURSOR = 14


@dataclass(frozen=True, slots=True)
class MonitorInfo:
    index: int
    left: int
    top: int
    right: int
    bottom: int
    is_primary: bool = False

    @property
    def width(self) -> int:
        return max(self.right - self.left, 1)

    @property
    def height(self) -> int:
        return max(self.bottom - self.top, 1)

    @property
    def rect(self) -> tuple[int, int, int, int]:
        return (self.left, self.top, self.right, self.bottom)


@dataclass(frozen=True, slots=True)
class CapturedFrame:
    image: Image.Image
    monitor_index: int
    monitor_count: int
    cursor_x: int  # 0..65535 normalized within monitor
    cursor_y: int  # 0..65535 normalized within monitor
    has_cursor: bool
    raw_crc32: int | None = None
    roi: tuple[float, float, float, float] | None = None


@dataclass(frozen=True, slots=True)
class UnpackedScreenFrame:
    flags: int
    monitor_index: int
    monitor_count: int
    seq: int
    width: int
    height: int
    cursor_x: int
    cursor_y: int
    payload: bytes
    roi: tuple[float, float, float, float] | None = None

    @property
    def has_cursor(self) -> bool:
        return bool(self.flags & FLAG_HAS_CURSOR)

    @property
    def has_roi(self) -> bool:
        return bool(self.flags & FLAG_ROI)


def pack_screen_frame(
    *,
    seq: int,
    monitor_index: int,
    monitor_count: int,
    width: int,
    height: int,
    cursor_x: int,
    cursor_y: int,
    has_cursor: bool,
    payload: bytes,
    keyframe: bool = True,
    roi: tuple[float, float, float, float] | None = None,
) -> bytes:
    """Pack an RSCR header (20 bytes standard or 28 bytes if ROI is active) + JPEG payload."""
    flags = FLAG_CODEC_JPEG
    if keyframe:
        flags |= FLAG_KEYFRAME
    if has_cursor:
        flags |= FLAG_HAS_CURSOR
    if roi is not None:
        flags |= FLAG_ROI
        rx, ry, rw, rh = roi
        header = SCREEN_ROI_HEADER_STRUCT.pack(
            SCREEN_MAGIC,
            flags & 0xFF,
            monitor_index & 0xFF,
            max(monitor_count, 1) & 0xFF,
            0,
            seq & 0xFFFFFFFF,
            min(max(width, 1), 65535),
            min(max(height, 1), 65535),
            min(max(cursor_x, 0), 65535),
            min(max(cursor_y, 0), 65535),
            min(max(int(round(rx * 65535.0)), 0), 65535),
            min(max(int(round(ry * 65535.0)), 0), 65535),
            min(max(int(round(rw * 65535.0)), 0), 65535),
            min(max(int(round(rh * 65535.0)), 0), 65535),
        )
    else:
        header = SCREEN_HEADER_STRUCT.pack(
            SCREEN_MAGIC,
            flags & 0xFF,
            monitor_index & 0xFF,
            max(monitor_count, 1) & 0xFF,
            0,
            seq & 0xFFFFFFFF,
            min(max(width, 1), 65535),
            min(max(height, 1), 65535),
            min(max(cursor_x, 0), 65535),
            min(max(cursor_y, 0), 65535),
        )
    return header + payload


def unpack_screen_frame(data: bytes) -> UnpackedScreenFrame | None:
    """Unpack an RSCR binary screen frame (standard 20-byte or ROI 28-byte)."""
    if len(data) < SCREEN_HEADER_SIZE or data[:4] != SCREEN_MAGIC:
        return None
    flags = data[4]
    if flags & FLAG_ROI:
        if len(data) < SCREEN_ROI_HEADER_SIZE:
            return None
        _magic, flags, mon_idx, mon_cnt, _res, seq, w, h, cx, cy, rx, ry, rw, rh = (
            SCREEN_ROI_HEADER_STRUCT.unpack_from(data, 0)
        )
        return UnpackedScreenFrame(
            flags=flags,
            monitor_index=mon_idx,
            monitor_count=mon_cnt,
            seq=seq,
            width=w,
            height=h,
            cursor_x=cx,
            cursor_y=cy,
            payload=data[SCREEN_ROI_HEADER_SIZE:],
            roi=(rx / 65535.0, ry / 65535.0, rw / 65535.0, rh / 65535.0),
        )
    else:
        _magic, flags, mon_idx, mon_cnt, _res, seq, w, h, cx, cy = SCREEN_HEADER_STRUCT.unpack_from(data, 0)
        return UnpackedScreenFrame(
            flags=flags,
            monitor_index=mon_idx,
            monitor_count=mon_cnt,
            seq=seq,
            width=w,
            height=h,
            cursor_x=cx,
            cursor_y=cy,
            payload=data[SCREEN_HEADER_SIZE:],
            roi=None,
        )


def resolve_quality_profile(
    quality: str,
    transport_kind: str,
    requested_fps: int,
    requested_max_width: int,
) -> tuple[int, int, int]:
    """Return (max_width, jpeg_quality, target_fps) prioritizing text readability on mobile/P2P/VPS.
    
    On slow cellular or remote relay links, frame rate is reduced down to 2-6 fps
    instead of crushing spatial resolution and destroying 12-14pt fonts.
    """
    q = quality.strip().lower()
    if q in ("hd", "text"):
        if transport_kind == "lan":
            base_w, base_q, base_fps = 1920, 86, 30
        elif transport_kind == "p2p":
            base_w, base_q, base_fps = 1920, 80, 16
        else:  # "vps"
            base_w, base_q, base_fps = 1920, 78, 12
    elif q == "balanced":
        if transport_kind == "lan":
            base_w, base_q, base_fps = 1600, 76, 24
        elif transport_kind == "p2p":
            base_w, base_q, base_fps = 1440, 70, 16
        else:  # "vps"
            base_w, base_q, base_fps = 1280, 66, 12
    elif q == "fast":
        if transport_kind == "lan":
            base_w, base_q, base_fps = 960, 56, 30
        elif transport_kind == "p2p":
            base_w, base_q, base_fps = 960, 46, 30
        else:  # "vps"
            base_w, base_q, base_fps = 960, 44, 30
    else:  # "auto"
        if transport_kind == "lan":
            base_w, base_q, base_fps = 1920, 82, 30
        elif transport_kind == "p2p":
            base_w, base_q, base_fps = 1920, 76, 24
        else:  # "vps"
            base_w, base_q, base_fps = 1600, 74, 20

    final_w = min(max(min(base_w, requested_max_width), 640), 1920)
    final_fps = min(max(min(base_fps, requested_fps), 1), 30)
    return final_w, base_q, final_fps


def resolve_jpeg_subsampling(quality: str, transport_kind: str) -> int:
    """Choose chroma subsampling: 0=4:4:4 (sharp text), 1=4:2:2 (crisp edges), 2=4:2:0 (smallest).
    
    Windows ClearType fonts require 4:4:4 (0) or 4:2:2 (1) to prevent severe subpixel color bleeding.
    4:2:0 (2) is restricted only to 'fast' mode.
    """
    q = quality.strip().lower()
    if q in ("hd", "text"):
        return 0  # 4:4:4: Perfect RGB edge fidelity for 12-14pt fonts everywhere
    if q == "fast":
        return 2  # 4:2:0: Smallest payload for high-speed motion
    if transport_kind == "lan":
        return 0  # 4:4:4 on local Wi-Fi
    # On P2P/VPS: 4:2:2 preserves ClearType horizontal subpixels while saving 20% byte payload
    return 1


def resolve_max_frame_bytes(quality: str, transport_kind: str, is_roi: bool = False) -> int:
    """Per-frame byte budget tuned for crisp text while respecting transport upper bounds."""
    if is_roi:
        # Cropped viewport is smaller in dimensions -> requires far fewer bytes for crystal clarity
        return 65 * 1024

    q = quality.strip().lower()
    if transport_kind == "lan":
        return 340 * 1024 if q in ("hd", "text") else 240 * 1024
    if transport_kind == "p2p":
        if q in ("hd", "text"):
            return 140 * 1024  # Well within 320 UDP chunks (358 KB max limit)
        if q == "balanced":
            return 52 * 1024
        if q == "fast":
            return 28 * 1024
        return 60 * 1024
    # "vps"
    if q in ("hd", "text"):
        return 120 * 1024  # Well below VPS relay 512 KB limit
    if q == "balanced":
        return 44 * 1024
    if q == "fast":
        return 28 * 1024
    return 48 * 1024


def encode_jpeg_bounded(
    image: Image.Image,
    max_width: int,
    jpeg_quality: int,
    max_bytes: int = MAX_FRAME_PAYLOAD_BYTES,
    subsampling: int = 0,
) -> tuple[bytes, int, int]:
    """Resize if needed and encode image to JPEG strictly within max_bytes with absolute safety cap.
    
    Subsampling transition order: 4:4:4 -> 4:2:2 first. 4:2:0 is strictly a last resort.
    Guarantees output never exceeds min(max_bytes, 350 KB) to prevent P2P/VPS socket drop.
    """
    img = image
    if img.mode != "RGB":
        img = img.convert("RGB")

    w, h = img.size
    if w > max_width and w > 0:
        scale = max_width / float(w)
        new_w = max(int(round(w * scale)) & ~1, 16)
        new_h = max(int(round(h * scale)) & ~1, 16)
        img = img.resize((new_w, new_h), Image.Resampling.BILINEAR)

    # Upper transport safety limit (P2P chunk ceiling = 358 KB)
    hard_limit = min(max_bytes, 350 * 1024)
    quality = min(max(int(jpeg_quality), 35), 92)
    cur_subsampling = subsampling

    for _ in range(4):
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=quality, subsampling=cur_subsampling, optimize=False)
        data = buf.getvalue()
        if len(data) <= hard_limit:
            return data, img.size[0], img.size[1]

        # 1. First degrade quality moderately while preserving chroma
        if quality > 55:
            quality = max(quality - 10, 50)
        # 2. Then step down chroma: 4:4:4 -> 4:2:2
        elif cur_subsampling == 0:
            cur_subsampling = 1
        # 3. Then drop quality further
        elif quality > 42:
            quality = max(quality - 8, 38)
        # 4. Only as last resort drop to 4:2:0
        elif cur_subsampling == 1:
            cur_subsampling = 2
        else:
            # Gentle spatial downscale if still oversized
            cw, ch = img.size
            if cw > 960:
                nw = max(int(cw * 0.85) & ~1, 640)
                nh = max(int(ch * 0.85) & ~1, 360)
                img = img.resize((nw, nh), Image.Resampling.BILINEAR)
            else:
                quality = 35
                break

    # Final hard clamp guarantee
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=max(quality, 32), subsampling=cur_subsampling, optimize=False)
    data = buf.getvalue()
    if len(data) > hard_limit:
        # Emergency extreme fallback to guarantee frame is never dropped by network transport
        cw, ch = img.size
        img_small = img.resize((max(int(cw * 0.70) & ~1, 480), max(int(ch * 0.70) & ~1, 270)), Image.Resampling.BILINEAR)
        buf = io.BytesIO()
        img_small.save(buf, format="JPEG", quality=30, subsampling=2, optimize=False)
        return buf.getvalue(), img_small.size[0], img_small.size[1]

    return data, img.size[0], img.size[1]


# ---------------------------------------------------------------------------
# Win32 GDI Screen Capturer (fast, zero-dependency, Lock Screen & UAC aware)
# ---------------------------------------------------------------------------

if sys.platform == "win32":
    _user32 = ctypes.WinDLL("user32", use_last_error=True)
    _gdi32 = ctypes.WinDLL("gdi32", use_last_error=True)

    class _RECT(ctypes.Structure):
        _fields_ = [
            ("left", wintypes.LONG),
            ("top", wintypes.LONG),
            ("right", wintypes.LONG),
            ("bottom", wintypes.LONG),
        ]

    class _MONITORINFO(ctypes.Structure):
        _fields_ = [
            ("cbSize", wintypes.DWORD),
            ("rcMonitor", _RECT),
            ("rcWork", _RECT),
            ("dwFlags", wintypes.DWORD),
        ]

    class _BITMAPINFOHEADER(ctypes.Structure):
        _fields_ = [
            ("biSize", wintypes.DWORD),
            ("biWidth", wintypes.LONG),
            ("biHeight", wintypes.LONG),
            ("biPlanes", wintypes.WORD),
            ("biBitCount", wintypes.WORD),
            ("biCompression", wintypes.DWORD),
            ("biSizeImage", wintypes.DWORD),
            ("biXPelsPerMeter", wintypes.LONG),
            ("biYPelsPerMeter", wintypes.LONG),
            ("biClrUsed", wintypes.DWORD),
            ("biClrImportant", wintypes.DWORD),
        ]

    class _BITMAPINFO(ctypes.Structure):
        _fields_ = [
            ("bmiHeader", _BITMAPINFOHEADER),
            ("bmiColors", wintypes.DWORD * 3),
        ]

    class _POINT(ctypes.Structure):
        _fields_ = [
            ("x", wintypes.LONG),
            ("y", wintypes.LONG),
        ]

    class _CURSORINFO(ctypes.Structure):
        _fields_ = [
            ("cbSize", wintypes.DWORD),
            ("flags", wintypes.DWORD),
            ("hCursor", wintypes.HANDLE),
            ("ptScreenPos", _POINT),
        ]

    class _ICONINFO(ctypes.Structure):
        _fields_ = [
            ("fIcon", wintypes.BOOL),
            ("xHotspot", wintypes.DWORD),
            ("yHotspot", wintypes.DWORD),
            ("hbmMask", wintypes.HBITMAP),
            ("hbmColor", wintypes.HBITMAP),
        ]

    _MONITORENUMPROC = ctypes.WINFUNCTYPE(
        wintypes.BOOL,
        wintypes.HANDLE,
        wintypes.HDC,
        ctypes.POINTER(_RECT),
        wintypes.LPARAM,
    )

    _user32.EnumDisplayMonitors.argtypes = (
        wintypes.HDC,
        ctypes.POINTER(_RECT),
        _MONITORENUMPROC,
        wintypes.LPARAM,
    )
    _user32.EnumDisplayMonitors.restype = wintypes.BOOL
    _user32.GetMonitorInfoW.argtypes = (wintypes.HANDLE, ctypes.POINTER(_MONITORINFO))
    _user32.GetMonitorInfoW.restype = wintypes.BOOL
    _user32.GetDC.argtypes = (wintypes.HWND,)
    _user32.GetDC.restype = wintypes.HDC
    _user32.ReleaseDC.argtypes = (wintypes.HWND, wintypes.HDC)
    _user32.ReleaseDC.restype = ctypes.c_int
    _user32.GetCursorInfo.argtypes = (ctypes.POINTER(_CURSORINFO),)
    _user32.GetCursorInfo.restype = wintypes.BOOL
    _user32.GetIconInfo.argtypes = (wintypes.HANDLE, ctypes.POINTER(_ICONINFO))
    _user32.GetIconInfo.restype = wintypes.BOOL
    _user32.DrawIconEx.argtypes = (
        wintypes.HDC,
        ctypes.c_int,
        ctypes.c_int,
        wintypes.HANDLE,
        ctypes.c_int,
        ctypes.c_int,
        wintypes.UINT,
        wintypes.HBRUSH,
        wintypes.UINT,
    )
    _user32.DrawIconEx.restype = wintypes.BOOL
    _user32.GetSystemMetrics.argtypes = (ctypes.c_int,)
    _user32.GetSystemMetrics.restype = ctypes.c_int

    _gdi32.CreateCompatibleDC.argtypes = (wintypes.HDC,)
    _gdi32.CreateCompatibleDC.restype = wintypes.HDC
    _gdi32.DeleteDC.argtypes = (wintypes.HDC,)
    _gdi32.DeleteDC.restype = wintypes.BOOL
    _gdi32.CreateCompatibleBitmap.argtypes = (wintypes.HDC, ctypes.c_int, ctypes.c_int)
    _gdi32.CreateCompatibleBitmap.restype = wintypes.HBITMAP
    _gdi32.SelectObject.argtypes = (wintypes.HDC, wintypes.HGDIOBJ)
    _gdi32.SelectObject.restype = wintypes.HGDIOBJ
    _gdi32.DeleteObject.argtypes = (wintypes.HGDIOBJ,)
    _gdi32.DeleteObject.restype = wintypes.BOOL
    _gdi32.BitBlt.argtypes = (
        wintypes.HDC,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_int,
        wintypes.HDC,
        ctypes.c_int,
        ctypes.c_int,
        wintypes.DWORD,
    )
    _gdi32.BitBlt.restype = wintypes.BOOL
    _gdi32.StretchBlt.argtypes = (
        wintypes.HDC,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_int,
        wintypes.HDC,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_int,
        wintypes.DWORD,
    )
    _gdi32.StretchBlt.restype = wintypes.BOOL
    _gdi32.SetStretchBltMode.argtypes = (wintypes.HDC, ctypes.c_int)
    _gdi32.SetStretchBltMode.restype = ctypes.c_int
    _gdi32.CreateDIBSection.argtypes = (
        wintypes.HDC,
        ctypes.POINTER(_BITMAPINFO),
        wintypes.UINT,
        ctypes.POINTER(ctypes.c_void_p),
        wintypes.HANDLE,
        wintypes.DWORD,
    )
    _gdi32.CreateDIBSection.restype = wintypes.HBITMAP
    _gdi32.GetDIBits.argtypes = (
        wintypes.HDC,
        wintypes.HBITMAP,
        wintypes.UINT,
        wintypes.UINT,
        ctypes.c_void_p,
        ctypes.POINTER(_BITMAPINFO),
        wintypes.UINT,
    )
    _gdi32.GetDIBits.restype = ctypes.c_int
else:
    _user32 = None
    _gdi32 = None


def enumerate_monitors() -> list[MonitorInfo]:
    """Enumerate connected Windows displays (primary monitor ordered first)."""
    if sys.platform != "win32" or _user32 is None:
        return [MonitorInfo(index=0, left=0, top=0, right=1920, bottom=1080, is_primary=True)]

    raw_monitors: list[tuple[bool, int, int, int, int]] = []

    def _cb(hmon: int, _hdc: int, _rect_ptr: Any, _lparam: int) -> bool:
        info = _MONITORINFO()
        info.cbSize = ctypes.sizeof(_MONITORINFO)
        if _user32.GetMonitorInfoW(hmon, ctypes.byref(info)):
            r = info.rcMonitor
            is_prim = bool(info.dwFlags & _MONITORINFOF_PRIMARY)
            raw_monitors.append((is_prim, int(r.left), int(r.top), int(r.right), int(r.bottom)))
        return True

    cb = _MONITORENUMPROC(_cb)
    try:
        _user32.EnumDisplayMonitors(None, None, cb, 0)
    except Exception:
        logger.debug("EnumDisplayMonitors failed", exc_info=True)

    if not raw_monitors:
        w = max(int(_user32.GetSystemMetrics(0)), 640)
        h = max(int(_user32.GetSystemMetrics(1)), 480)
        return [MonitorInfo(index=0, left=0, top=0, right=w, bottom=h, is_primary=True)]

    # Primary monitor first, then left-to-right
    raw_monitors.sort(key=lambda item: (not item[0], item[1], item[2]))
    return [
        MonitorInfo(index=i, left=l, top=t, right=r, bottom=b, is_primary=prim)
        for i, (prim, l, t, r, b) in enumerate(raw_monitors)
    ]


class WindowsScreenCapturer:
    """Thread-safe Win32 GDI screen capturer with reusable DIBSection, StretchBlt downscaling, and real cursor drawing."""

    def __init__(self) -> None:
        self._monitors_cache: list[MonitorInfo] = []
        self._monitors_ts: float = 0.0
        self._cache_lock = threading.Lock()
        self._dib_thread_id: int = 0
        self._dib_hdc: Any = None
        self._dib_hbitmap: Any = None
        self._dib_old_obj: Any = None
        self._dib_arr: Any = None
        self._dib_view: memoryview | None = None
        self._dib_w: int = 0
        self._dib_h: int = 0

    def release_cache(self) -> None:
        """Release cached GDI DIBSection resources safely."""
        with self._cache_lock:
            self._release_dib_locked()

    def _release_dib_locked(self) -> None:
        self._dib_view = None
        self._dib_arr = None
        if _gdi32 is not None:
            if self._dib_hdc and self._dib_old_obj:
                try:
                    _gdi32.SelectObject(self._dib_hdc, self._dib_old_obj)
                except Exception:
                    pass
            if self._dib_hbitmap:
                try:
                    _gdi32.DeleteObject(self._dib_hbitmap)
                except Exception:
                    pass
            if self._dib_hdc:
                try:
                    _gdi32.DeleteDC(self._dib_hdc)
                except Exception:
                    pass
        self._dib_hdc = None
        self._dib_hbitmap = None
        self._dib_old_obj = None
        self._dib_w = 0
        self._dib_h = 0
        self._dib_thread_id = 0

    def _ensure_dib_locked(self, hdc_screen: Any, target_w: int, target_h: int) -> tuple[Any, memoryview] | None:
        assert _gdi32 is not None
        tid = threading.get_ident()
        if (
            self._dib_hdc
            and self._dib_hbitmap
            and self._dib_view is not None
            and self._dib_w == target_w
            and self._dib_h == target_h
            and self._dib_thread_id == tid
        ):
            return self._dib_hdc, self._dib_view

        self._release_dib_locked()

        hdc_mem = _gdi32.CreateCompatibleDC(hdc_screen)
        if not hdc_mem:
            return None

        bmi = _BITMAPINFO()
        bmi.bmiHeader.biSize = ctypes.sizeof(_BITMAPINFOHEADER)
        bmi.bmiHeader.biWidth = target_w
        bmi.bmiHeader.biHeight = -target_h  # Top-down DIB
        bmi.bmiHeader.biPlanes = 1
        bmi.bmiHeader.biBitCount = 32
        bmi.bmiHeader.biCompression = _BI_RGB

        bits = ctypes.c_void_p()
        hbitmap = _gdi32.CreateDIBSection(
            hdc_screen,
            ctypes.byref(bmi),
            _DIB_RGB_COLORS,
            ctypes.byref(bits),
            None,
            0,
        )
        if not hbitmap or not bits.value:
            _gdi32.DeleteDC(hdc_mem)
            if hbitmap:
                _gdi32.DeleteObject(hbitmap)
            return None

        old_obj = _gdi32.SelectObject(hdc_mem, hbitmap)
        _gdi32.SetStretchBltMode(hdc_mem, _COLORONCOLOR)

        needed = target_w * target_h * 4
        dib_arr = (ctypes.c_char * needed).from_address(bits.value)
        dib_view = memoryview(dib_arr)

        self._dib_thread_id = tid
        self._dib_hdc = hdc_mem
        self._dib_hbitmap = hbitmap
        self._dib_old_obj = old_obj
        self._dib_arr = dib_arr
        self._dib_view = dib_view
        self._dib_w = target_w
        self._dib_h = target_h
        return hdc_mem, dib_view

    def get_monitors(self, force: bool = False) -> list[MonitorInfo]:
        now = time.monotonic()
        if force or not self._monitors_cache or (now - self._monitors_ts) > 2.5:
            self._monitors_cache = enumerate_monitors()
            self._monitors_ts = now
        return self._monitors_cache

    def get_monitor_rect(self, monitor_index: int) -> tuple[int, int, int, int] | None:
        monitors = self.get_monitors()
        if not monitors:
            return None
        idx = monitor_index % len(monitors)
        return monitors[idx].rect

    def capture(
        self,
        monitor_index: int = 0,
        max_width: int | None = None,
        draw_cursor: bool = True,
        roi: tuple[float, float, float, float] | None = None,
    ) -> CapturedFrame | None:
        frame, _unchanged = self.capture_if_changed(
            monitor_index=monitor_index,
            max_width=max_width,
            last_signature=None,
            draw_cursor=draw_cursor,
            roi=roi,
        )
        return frame

    def capture_if_changed(
        self,
        monitor_index: int = 0,
        max_width: int | None = None,
        last_signature: tuple[Any, ...] | None = None,
        draw_cursor: bool = True,
        roi: tuple[float, float, float, float] | None = None,
    ) -> tuple[CapturedFrame | None, bool]:
        """Capture monitor (optionally cropped to ROI or hardware-downscaled via GDI StretchBlt).

        Returns (frame, unchanged). When unchanged is True, skips expensive RGB conversion.
        When draw_cursor is False (AnyDesk-style client-side local cursor mode), cursor coordinates
        are still reported in metadata, but the cursor is not baked into the video pixels and pure
        cursor movement over a static desktop won't trigger redundant JPEG frames.
        """
        if sys.platform != "win32" or _user32 is None or _gdi32 is None:
            return None, False

        from core.windows_integration import ensure_thread_on_input_desktop

        ensure_thread_on_input_desktop(force=False)
        frame, unchanged = self._capture_once(
            monitor_index,
            max_width=max_width,
            last_signature=last_signature,
            draw_cursor=draw_cursor,
            roi=roi,
        )
        if frame is None and not unchanged:
            # Desktop may have just transitioned (Default <-> Winlogon / UAC); rebuild cache & retry once.
            self.release_cache()
            ensure_thread_on_input_desktop(force=True)
            self._monitors_ts = 0.0
            frame, unchanged = self._capture_once(
                monitor_index,
                max_width=max_width,
                last_signature=last_signature,
                draw_cursor=draw_cursor,
                roi=roi,
            )
        return frame, unchanged

    def _capture_once(
        self,
        monitor_index: int,
        max_width: int | None = None,
        last_signature: tuple[Any, ...] | None = None,
        draw_cursor: bool = True,
        roi: tuple[float, float, float, float] | None = None,
    ) -> tuple[CapturedFrame | None, bool]:
        assert _user32 is not None and _gdi32 is not None
        monitors = self.get_monitors()
        if not monitors:
            return None, False
        mon_idx = monitor_index % len(monitors)
        mon = monitors[mon_idx]
        w, h = mon.width, mon.height

        src_x = mon.left
        src_y = mon.top
        src_w = w
        src_h = h
        if roi is not None:
            rx, ry, rw, rh = roi
            src_x = mon.left + int(round(rx * w))
            src_y = mon.top + int(round(ry * h))
            src_w = max(int(round(rw * w)) & ~1, 16)
            src_h = max(int(round(rh * h)) & ~1, 16)
            if src_x + src_w > mon.right:
                src_w = max((mon.right - src_x) & ~1, 16)
            if src_y + src_h > mon.bottom:
                src_h = max((mon.bottom - src_y) & ~1, 16)

        target_w, target_h = src_w, src_h
        if max_width is not None and 16 <= max_width < src_w:
            scale = max_width / float(src_w)
            target_w = max(int(round(src_w * scale)) & ~1, 16)
            target_h = max(int(round(src_h * scale)) & ~1, 16)

        hdc_screen = _user32.GetDC(None)
        if not hdc_screen:
            return None, False

        try:
            with self._cache_lock:
                dib = self._ensure_dib_locked(hdc_screen, target_w, target_h)
                if dib is None:
                    return None, False
                hdc_mem, raw_view = dib

                if target_w == src_w and target_h == src_h:
                    ok = _gdi32.BitBlt(
                        hdc_mem,
                        0,
                        0,
                        src_w,
                        src_h,
                        hdc_screen,
                        src_x,
                        src_y,
                        _SRCCOPY,
                    )
                else:
                    ok = _gdi32.StretchBlt(
                        hdc_mem,
                        0,
                        0,
                        target_w,
                        target_h,
                        hdc_screen,
                        src_x,
                        src_y,
                        src_w,
                        src_h,
                        _SRCCOPY,
                    )
                if not ok:
                    self._release_dib_locked()
                    return None, False

                cursor_x = 0
                cursor_y = 0
                has_cursor = False

                ci = _CURSORINFO()
                ci.cbSize = ctypes.sizeof(_CURSORINFO)
                if _user32.GetCursorInfo(ctypes.byref(ci)) and (ci.flags & _CURSOR_SHOWING):
                    px = int(ci.ptScreenPos.x)
                    py = int(ci.ptScreenPos.y)
                    if mon.left <= px < mon.right and mon.top <= py < mon.bottom:
                        has_cursor = True
                        cursor_x = int(round((px - mon.left) * 65535.0 / max(w - 1, 1)))
                        cursor_y = int(round((py - mon.top) * 65535.0 / max(h - 1, 1)))
                        cursor_x = min(max(cursor_x, 0), 65535)
                        cursor_y = min(max(cursor_y, 0), 65535)

                        if draw_cursor and ci.hCursor and (src_x <= px < src_x + src_w and src_y <= py < src_y + src_h):
                            draw_x = px - src_x
                            draw_y = py - src_y
                            ii = _ICONINFO()
                            if _user32.GetIconInfo(ci.hCursor, ctypes.byref(ii)):
                                try:
                                    draw_x -= int(ii.xHotspot)
                                    draw_y -= int(ii.yHotspot)
                                finally:
                                    if ii.hbmMask:
                                        _gdi32.DeleteObject(ii.hbmMask)
                                    if ii.hbmColor:
                                        _gdi32.DeleteObject(ii.hbmColor)
                            if target_w == src_w and target_h == src_h:
                                _user32.DrawIconEx(hdc_mem, draw_x, draw_y, ci.hCursor, 0, 0, 0, None, _DI_NORMAL)
                            else:
                                sx = target_w / float(src_w)
                                sy = target_h / float(src_h)
                                cur_w = max(int(_user32.GetSystemMetrics(_SM_CXCURSOR)), 16)
                                cur_h = max(int(_user32.GetSystemMetrics(_SM_CYCURSOR)), 16)
                                _user32.DrawIconEx(
                                    hdc_mem,
                                    int(round(draw_x * sx)),
                                    int(round(draw_y * sy)),
                                    ci.hCursor,
                                    max(int(round(cur_w * sx)), 10),
                                    max(int(round(cur_h * sy)), 10),
                                    0,
                                    None,
                                    _DI_NORMAL,
                                )

                raw_crc = zlib.crc32(raw_view)
                sig = (
                    (raw_crc, mon_idx, cursor_x, cursor_y, has_cursor, roi)
                    if draw_cursor
                    else (raw_crc, mon_idx, 0, 0, False, roi)
                )
                if last_signature is not None and last_signature == sig:
                    return None, True

                # BGRX -> RGB unpacks into a fresh RGB Image buffer while lock is held.
                img = Image.frombuffer("RGB", (target_w, target_h), raw_view, "raw", "BGRX", 0, 1)
                return (
                    CapturedFrame(
                        image=img,
                        monitor_index=mon_idx,
                        monitor_count=len(monitors),
                        cursor_x=cursor_x,
                        cursor_y=cursor_y,
                        has_cursor=has_cursor,
                        raw_crc32=raw_crc,
                        roi=roi,
                    ),
                    False,
                )
        finally:
            _user32.ReleaseDC(None, hdc_screen)


class ScreenStreamer:
    """Async per-client screen streamer with adaptive flow control, dirty check, and LAN/P2P/VPS routing."""

    _LAN_IN_FLIGHT_WINDOW = 3
    _P2P_IN_FLIGHT_WINDOW = 2
    _VPS_IN_FLIGHT_WINDOW = 2
    _LAN_ACK_TIMEOUT_S = 1.0
    _VPS_ACK_TIMEOUT_S = 1.4
    _P2P_MIN_ACK_TIMEOUT_S = 0.22
    _P2P_MAX_ACK_TIMEOUT_S = 0.65
    _P2P_RETRY_UNACKED_S = 0.35
    _KEEPALIVE_INTERVAL_S = 1.25
    _P2P_FIRST_FRAME_MAX_BYTES = 34 * 1024

    def __init__(
        self,
        send_ws_binary: Callable[[bytes], Awaitable[None]],
        capturer: WindowsScreenCapturer | None = None,
        send_p2p_frame: Callable[[bytes], bool | Awaitable[bool]] | None = None,
        is_p2p_active: Callable[[], bool] | None = None,
        is_via_relay: bool = False,
        initial_seq: int = 0,
    ) -> None:
        self._send_ws_binary = send_ws_binary
        self._capturer = capturer or WindowsScreenCapturer()
        self._send_p2p_frame = send_p2p_frame
        self._is_p2p_active = is_p2p_active
        self._is_via_relay = is_via_relay

        self._quality = "auto"
        self._fps = 30
        self._max_width = 1920
        self._monitor = 0
        self._prefer_p2p = True
        self._hide_cursor = False

        self._running = False
        self._task: asyncio.Task[None] | None = None
        self._executor: ThreadPoolExecutor | None = None

        self._sent_seq = initial_seq & 0xFFFFFFFF
        self._acked_seq = self._sent_seq
        self._ack_event = asyncio.Event()
        self._wake_event = asyncio.Event()
        self._force_keyframe = True
        self._last_signature: tuple[int, int, int, int, bool] | None = None
        self._last_sent_ts = 0.0
        self._last_transport_kind = ""

        # Real-time adaptive congestion & packet-loss state (prevents P2P drops & VPS bufferbloat)
        self._sent_times: dict[int, tuple[float, str]] = {}
        self._ewma_rtt: float = 0.08
        self._adapt_q_drop: int = 0
        self._adapt_scale: float = 1.0
        self._adapt_fps_scale: float = 1.0
        self._adapt_budget_ratio: float = 1.0
        self._consecutive_timeouts: int = 0
        self._p2p_acked_count: int = 0
        self._active_roi: tuple[float, float, float, float] | None = None
        self._input_activity_ts: float = 0.0

    @property
    def is_running(self) -> bool:
        return self._running and self._task is not None and not self._task.done()

    @property
    def monitor_index(self) -> int:
        return self._monitor

    @property
    def last_seq(self) -> int:
        return self._sent_seq

    def get_monitor_rect(self, monitor_index: int | None = None) -> tuple[int, int, int, int] | None:
        idx = self._monitor if monitor_index is None else monitor_index
        return self._capturer.get_monitor_rect(idx)

    def notify_input_activity(self) -> None:
        """Wake the capture loop immediately on mouse/keyboard activity for instant feedback."""
        self._input_activity_ts = time.monotonic()
        if self._running:
            self._wake_event.set()

    def _in_flight_count(self) -> int:
        return (self._sent_seq - self._acked_seq) & 0xFFFFFFFF

    def _reset_adaptation(self, *, clear_p2p_probe: bool = False) -> None:
        self._adapt_q_drop = 0
        self._adapt_scale = 1.0
        self._adapt_fps_scale = 1.0
        self._adapt_budget_ratio = 1.0
        self._consecutive_timeouts = 0
        if clear_p2p_probe:
            self._p2p_acked_count = 0

    async def start(
        self,
        *,
        quality: str = "auto",
        fps: int = 30,
        max_width: int = 1920,
        monitor: int = 0,
        prefer_p2p: bool = True,
        hide_cursor: bool = False,
        roi: tuple[float, float, float, float] | None = None,
    ) -> None:
        self._quality = quality
        self._fps = fps
        self._max_width = max_width
        self._monitor = monitor
        self._prefer_p2p = prefer_p2p
        self._hide_cursor = hide_cursor
        self._active_roi = roi
        self._force_keyframe = True
        self._last_signature = None
        self._acked_seq = self._sent_seq
        self._sent_times.clear()
        self._reset_adaptation(clear_p2p_probe=True)

        if self._task is not None and not self._task.done():
            self._ack_event.set()
            self._wake_event.set()
            return

        self._running = True
        self._ack_event.set()
        self._wake_event.set()
        if self._executor is None:
            self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="remotepad-screen")
        self._task = asyncio.create_task(self._stream_loop(), name="remotepad-screen-stream")
        logger.info(
            "Screen stream started (quality=%s, fps=%d, max_w=%d, mon=%d, relay=%s, hide_cursor=%s, roi=%s)",
            quality,
            fps,
            max_width,
            monitor,
            self._is_via_relay,
            hide_cursor,
            roi,
        )

    def update_config(
        self,
        *,
        quality: str | None = None,
        fps: int | None = None,
        max_width: int | None = None,
        monitor: int | None = None,
        prefer_p2p: bool | None = None,
        hide_cursor: bool | None = None,
        roi: tuple[float, float, float, float] | None = None,
        clear_roi: bool = False,
    ) -> None:
        reset_flow = False
        if quality is not None:
            if quality != self._quality:
                reset_flow = True
                self._reset_adaptation(clear_p2p_probe=False)
            self._quality = quality
        if fps is not None:
            self._fps = fps
        if max_width is not None:
            self._max_width = max_width
        if monitor is not None:
            self._monitor = monitor
        if prefer_p2p is not None:
            if prefer_p2p != self._prefer_p2p:
                reset_flow = True
                self._reset_adaptation(clear_p2p_probe=prefer_p2p)
            self._prefer_p2p = prefer_p2p
        if hide_cursor is not None:
            self._hide_cursor = hide_cursor
        if roi is not None or clear_roi:
            new_roi = None if clear_roi else roi
            if new_roi != self._active_roi:
                self._active_roi = new_roi
                self._force_keyframe = True
                self._last_signature = None
        if reset_flow:
            # Clear stale in-flight counter so quality/transport switches take effect immediately
            self._acked_seq = self._sent_seq
            self._sent_times.clear()
        self._force_keyframe = True
        self._last_signature = None
        self._ack_event.set()
        self._wake_event.set()

    def on_ack(self, seq: int) -> None:
        seq &= 0xFFFFFFFF
        now = time.monotonic()
        diff = (seq - self._acked_seq) & 0xFFFFFFFF
        if 0 < diff < 0x80000000:
            self._acked_seq = seq
        self._consecutive_timeouts = 0

        sent_meta = self._sent_times.pop(seq, None)
        if sent_meta is not None:
            sent_ts, sent_transport = sent_meta
            rtt = max(now - sent_ts, 0.001)
            self._ewma_rtt = self._ewma_rtt * 0.72 + rtt * 0.28
            if sent_transport == "p2p":
                self._p2p_acked_count += 1

            if sent_transport == "lan":
                self._reset_adaptation(clear_p2p_probe=False)
            else:
                slow_thr = 0.30 if sent_transport == "p2p" else 0.40
                fast_thr = 0.14 if sent_transport == "p2p" else 0.18
                if rtt > slow_thr:
                    self._adapt_fps_scale = max(self._adapt_fps_scale * 0.78, 0.20)
                    self._adapt_q_drop = min(self._adapt_q_drop + 2, 10)
                    self._adapt_budget_ratio = max(self._adapt_budget_ratio * 0.85, 0.45)
                    if rtt > slow_thr * 1.6:
                        self._adapt_scale = max(self._adapt_scale * 0.95, 0.88)
                elif rtt < fast_thr:
                    self._adapt_fps_scale = min(self._adapt_fps_scale + 0.08, 1.0)
                    self._adapt_q_drop = max(self._adapt_q_drop - 1, 0)
                    self._adapt_budget_ratio = min(self._adapt_budget_ratio + 0.06, 1.0)
                    self._adapt_scale = min(self._adapt_scale + 0.04, 1.0)

        # Prune stale send timestamps
        if len(self._sent_times) > 32:
            cutoff = now - 5.0
            self._sent_times = {k: v for k, v in self._sent_times.items() if v[0] >= cutoff}

        self._ack_event.set()

    async def stop(self) -> None:
        self._running = False
        self._ack_event.set()
        self._wake_event.set()
        task = self._task
        self._task = None
        if task is not None and not task.done():
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
        executor = self._executor
        self._executor = None
        if executor is not None:
            executor.shutdown(wait=False, cancel_futures=True)
        release_fn = getattr(self._capturer, "release_cache", None)
        if callable(release_fn):
            try:
                release_fn()
            except Exception:
                pass
        logger.info("Screen stream stopped")

    def _active_transport_kind(self) -> str:
        if not self._is_via_relay:
            return "lan"
        if self._prefer_p2p and self._is_p2p_active is not None and self._is_p2p_active():
            return "p2p"
        return "vps"

    def _apply_timeout_backoff(self, transport_kind: str) -> None:
        self._consecutive_timeouts += 1
        if transport_kind == "p2p":
            self._adapt_fps_scale = max(self._adapt_fps_scale * 0.65, 0.15)
            self._adapt_q_drop = min(self._adapt_q_drop + 4, 10)
            self._adapt_budget_ratio = max(self._adapt_budget_ratio * 0.75, 0.45)
            self._adapt_scale = max(self._adapt_scale * 0.95, 0.88)
            self._force_keyframe = True
        elif transport_kind == "vps":
            self._adapt_fps_scale = max(self._adapt_fps_scale * 0.68, 0.20)
            self._adapt_q_drop = min(self._adapt_q_drop + 4, 10)
            self._adapt_budget_ratio = max(self._adapt_budget_ratio * 0.75, 0.48)
            self._adapt_scale = max(self._adapt_scale * 0.95, 0.90)

    def _capture_and_encode_sync(
        self,
        monitor_index: int,
        max_width: int,
        jpeg_quality: int,
        subsampling: int,
        force_send: bool,
        max_bytes: int = MAX_FRAME_PAYLOAD_BYTES,
        draw_cursor: bool = True,
        roi: tuple[float, float, float, float] | None = None,
    ) -> tuple[bytes, int, int, int, int, int, int, bool, tuple[Any, ...], tuple[float, float, float, float] | None] | None:
        last_sig = None if force_send else self._last_signature
        capture_fast = getattr(self._capturer, "capture_if_changed", None)
        if callable(capture_fast):
            try:
                captured, unchanged = capture_fast(
                    monitor_index=monitor_index,
                    max_width=max_width,
                    last_signature=last_sig,
                    draw_cursor=draw_cursor,
                    roi=roi,
                )
            except TypeError:
                captured, unchanged = capture_fast(
                    monitor_index=monitor_index,
                    max_width=max_width,
                    last_signature=last_sig,
                    draw_cursor=draw_cursor,
                )
            if unchanged or captured is None:
                return None
        else:
            try:
                captured = self._capturer.capture(monitor_index, max_width=max_width, draw_cursor=draw_cursor, roi=roi)
            except TypeError:
                try:
                    captured = self._capturer.capture(monitor_index, max_width=max_width, draw_cursor=draw_cursor)
                except TypeError:
                    captured = self._capturer.capture(monitor_index)
            if captured is None:
                return None

        # Full-pixel C CRC32 + exact cursor coords + roi
        pixel_crc = captured.raw_crc32 if captured.raw_crc32 is not None else zlib.crc32(captured.image.tobytes())
        sig = (
            (
                pixel_crc,
                captured.monitor_index,
                captured.cursor_x,
                captured.cursor_y,
                captured.has_cursor,
                roi,
            )
            if draw_cursor
            else (pixel_crc, captured.monitor_index, 0, 0, False, roi)
        )
        if not force_send and self._last_signature == sig:
            return None

        jpeg_bytes, out_w, out_h = encode_jpeg_bounded(
            captured.image,
            max_width=max_width,
            jpeg_quality=jpeg_quality,
            max_bytes=max_bytes,
            subsampling=subsampling,
        )
        return (
            jpeg_bytes,
            captured.monitor_index,
            captured.monitor_count,
            out_w,
            out_h,
            captured.cursor_x,
            captured.cursor_y,
            captured.has_cursor,
            sig,
            captured.roi,
        )

    async def _stream_loop(self) -> None:
        loop = asyncio.get_running_loop()
        refinement_armed_ts: float | None = None
        refinement_sent = False

        try:
            while self._running:
                loop_start = time.monotonic()
                transport_kind = self._active_transport_kind()

                if transport_kind != self._last_transport_kind:
                    self._last_transport_kind = transport_kind
                    self._acked_seq = self._sent_seq
                    self._sent_times.clear()
                    self._force_keyframe = True
                    self._reset_adaptation(clear_p2p_probe=(transport_kind == "p2p"))
                    refinement_armed_ts = None
                    refinement_sent = False

                if transport_kind == "lan":
                    in_flight_limit = self._LAN_IN_FLIGHT_WINDOW
                    ack_timeout = self._LAN_ACK_TIMEOUT_S
                elif transport_kind == "p2p":
                    in_flight_limit = self._P2P_IN_FLIGHT_WINDOW
                    ack_timeout = min(
                        max(self._ewma_rtt * 2.5, 0.35),
                        0.95,
                    )
                else:  # "vps"
                    in_flight_limit = 1 if self._ewma_rtt > 0.25 else self._VPS_IN_FLIGHT_WINDOW
                    ack_timeout = min(max(self._ewma_rtt * 2.5, 0.45), 1.5)

                # Backpressure flow control tuned separately for reliable TCP (LAN/VPS) vs UDP (P2P)
                if self._in_flight_count() >= in_flight_limit:
                    self._ack_event.clear()
                    try:
                        await asyncio.wait_for(self._ack_event.wait(), timeout=ack_timeout)
                    except asyncio.TimeoutError:
                        self._apply_timeout_backoff(transport_kind)
                        self._acked_seq = (self._sent_seq - max(in_flight_limit - 1, 0)) & 0xFFFFFFFF
                    if not self._running:
                        break

                base_w, base_q, target_fps = resolve_quality_profile(
                    self._quality,
                    transport_kind,
                    self._fps,
                    self._max_width,
                )
                is_roi_active = self._active_roi is not None

                if transport_kind == "lan":
                    eff_w = base_w
                    eff_q = base_q
                    eff_fps = target_fps
                    eff_max_bytes = resolve_max_frame_bytes(self._quality, transport_kind, is_roi_active)
                    subsampling = resolve_jpeg_subsampling(self._quality, transport_kind)
                else:
                    eff_w = max(int(round(base_w * self._adapt_scale)) & ~1, 960)
                    eff_q = max(base_q - self._adapt_q_drop, 45)
                    eff_fps = max(int(round(target_fps * self._adapt_fps_scale)), 2)
                    base_bytes = resolve_max_frame_bytes(self._quality, transport_kind, is_roi_active)
                    eff_max_bytes = max(int(base_bytes * self._adapt_budget_ratio), 18 * 1024)
                    subsampling = resolve_jpeg_subsampling(self._quality, transport_kind)

                frame_interval = 1.0 / float(max(eff_fps, 1))
                p2p_unacked_retry = (
                    transport_kind == "p2p"
                    and self._in_flight_count() > 0
                    and (loop_start - self._last_sent_ts) >= 0.65
                )
                if p2p_unacked_retry:
                    self._apply_timeout_backoff("p2p")

                # If user interacted recently, cancel pending refinement and treat as active motion
                recent_user_input = (loop_start - self._input_activity_ts) < 0.20
                if recent_user_input:
                    refinement_armed_ts = None
                    refinement_sent = False

                is_still = (
                    (self._last_signature is not None)
                    and not self._force_keyframe
                    and not p2p_unacked_retry
                    and not recent_user_input
                )

                # --- STILL-SCREEN REFINEMENT LOGIC (PRIORITY 1) ---
                is_refinement_frame = False
                if is_still:
                    if not refinement_sent:
                        if refinement_armed_ts is None:
                            refinement_armed_ts = loop_start + 0.250  # 250ms debounce
                        elif loop_start >= refinement_armed_ts:
                            is_refinement_frame = True
                    else:
                        keepalive_due = (loop_start - self._last_sent_ts) >= self._KEEPALIVE_INTERVAL_S
                        if not keepalive_due:
                            sleep_s = 0.1
                            self._wake_event.clear()
                            try:
                                await asyncio.wait_for(self._wake_event.wait(), timeout=sleep_s)
                            except asyncio.TimeoutError:
                                pass
                            continue

                if is_refinement_frame:
                    capture_w = base_w
                    capture_q = 90
                    capture_subsampling = 0
                    capture_max_bytes = 140 * 1024 if not is_roi_active else 70 * 1024
                    force_send = True
                else:
                    capture_w = eff_w
                    capture_q = eff_q
                    capture_subsampling = subsampling
                    capture_max_bytes = eff_max_bytes
                    force_send = (
                        self._force_keyframe
                        or p2p_unacked_retry
                        or (loop_start - self._last_sent_ts) >= self._KEEPALIVE_INTERVAL_S
                    )

                executor = self._executor
                if executor is None:
                    break
                encoded = await loop.run_in_executor(
                    executor,
                    self._capture_and_encode_sync,
                    self._monitor,
                    capture_w,
                    capture_q,
                    capture_subsampling,
                    force_send,
                    capture_max_bytes,
                    not self._hide_cursor,
                    self._active_roi,
                )

                if not self._running:
                    break

                if encoded is not None:
                    jpeg_bytes, mon_idx, mon_cnt, out_w, out_h, cx, cy, has_cursor, sig, roi_out = encoded
                    self._last_signature = sig
                    self._force_keyframe = False
                    self._sent_seq = (self._sent_seq + 1) & 0xFFFFFFFF

                    if is_refinement_frame:
                        refinement_sent = True
                        refinement_armed_ts = None
                    else:
                        refinement_sent = False
                        refinement_armed_ts = None

                    wire_frame = pack_screen_frame(
                        seq=self._sent_seq,
                        monitor_index=mon_idx,
                        monitor_count=mon_cnt,
                        width=out_w,
                        height=out_h,
                        cursor_x=cx,
                        cursor_y=cy,
                        has_cursor=has_cursor,
                        payload=jpeg_bytes,
                        keyframe=True,
                        roi=roi_out,
                    )

                    sent_via_p2p = False
                    if transport_kind == "p2p" and self._send_p2p_frame is not None:
                        res = self._send_p2p_frame(wire_frame)
                        if inspect.isawaitable(res):
                            sent_via_p2p = bool(await res)
                        else:
                            sent_via_p2p = bool(res)

                    actual_transport = "p2p" if sent_via_p2p else ("lan" if transport_kind == "lan" else "vps")
                    if not sent_via_p2p:
                        await self._send_ws_binary(wire_frame)

                    now = time.monotonic()
                    self._sent_times[self._sent_seq] = (now, actual_transport)
                    self._last_sent_ts = now

                elapsed = time.monotonic() - loop_start
                sleep_s = frame_interval - elapsed
                if sleep_s > 0.002:
                    self._wake_event.clear()
                    try:
                        await asyncio.wait_for(self._wake_event.wait(), timeout=sleep_s)
                    except asyncio.TimeoutError:
                        pass
                else:
                    await asyncio.sleep(0)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            logger.debug("Screen stream loop ended: %s", exc)
        finally:
            self._running = False
