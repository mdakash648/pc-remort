"""Windows Core Audio (IAudioEndpointVolume) master volume controller via ctypes.

Zero external dependencies. Queries the current default audio render endpoint fresh
on each operation so switching between speakers, headphones, or Bluetooth works
automatically without stale COM pointers.
"""

from __future__ import annotations

import ctypes
import logging
import sys
import uuid
from ctypes import wintypes
from typing import Any, Protocol

logger = logging.getLogger(__name__)

_COINIT_MULTITHREADED = 0x0
_CLSCTX_ALL = 23
_E_RENDER = 0
_E_MULTIMEDIA = 1
_RPC_E_CHANGED_MODE = -2147417850  # 0x80010106 as signed HRESULT


class _GUID(ctypes.Structure):
    _fields_ = [
        ("Data1", wintypes.DWORD),
        ("Data2", wintypes.WORD),
        ("Data3", wintypes.WORD),
        ("Data4", ctypes.c_ubyte * 8),
    ]

    @classmethod
    def from_str(cls, text: str) -> "_GUID":
        u = uuid.UUID(text)
        d4 = (ctypes.c_ubyte * 8)(*u.bytes[8:])
        return cls(u.time_low, u.time_mid, u.time_hi_version, d4)


_CLSID_MMDEVICE_ENUMERATOR = _GUID.from_str("{BCDE0395-E52F-467C-8E3D-C4579291692E}")
_IID_IMMDEVICE_ENUMERATOR = _GUID.from_str("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
_IID_IAUDIO_ENDPOINT_VOLUME = _GUID.from_str("{5CDF2C82-841E-4546-9722-0CF74078229A}")


def _vtable_fn(obj: ctypes.c_void_p, idx: int, restype: Any, *argtypes: Any) -> Any:
    vtbl_ptr = ctypes.cast(obj, ctypes.POINTER(ctypes.c_void_p))[0]
    vtbl = ctypes.cast(vtbl_ptr, ctypes.POINTER(ctypes.c_void_p))
    return ctypes.WINFUNCTYPE(restype, ctypes.c_void_p, *argtypes)(vtbl[idx])


def _release_com(ptr: ctypes.c_void_p) -> None:
    if ptr and ptr.value:
        try:
            release = _vtable_fn(ptr, 2, ctypes.c_ulong)
            release(ptr)
        except Exception:
            pass


class VolumeBackend(Protocol):
    def get_state(self) -> tuple[float, bool] | None: ...
    def set_volume(self, level: float, unmute_if_positive: bool = True) -> tuple[float, bool] | None: ...
    def set_mute(self, muted: bool) -> tuple[float, bool] | None: ...


class _WindowsCoreAudioBackend:
    """Direct ctypes COM wrapper around Windows Core Audio IAudioEndpointVolume."""

    def __init__(self) -> None:
        self._ole32 = ctypes.WinDLL("ole32") if sys.platform == "win32" else None
        if self._ole32 is not None:
            self._ole32.CoInitializeEx.argtypes = (ctypes.c_void_p, wintypes.DWORD)
            self._ole32.CoInitializeEx.restype = ctypes.c_long
            self._ole32.CoCreateInstance.argtypes = (
                ctypes.POINTER(_GUID),
                ctypes.c_void_p,
                wintypes.DWORD,
                ctypes.POINTER(_GUID),
                ctypes.POINTER(ctypes.c_void_p),
            )
            self._ole32.CoCreateInstance.restype = ctypes.c_long

    def _with_endpoint(self, fn: Any) -> tuple[float, bool] | None:
        if self._ole32 is None:
            return None

        hr_init = self._ole32.CoInitializeEx(None, _COINIT_MULTITHREADED)
        if hr_init < 0 and hr_init != _RPC_E_CHANGED_MODE:
            return None

        enum_ptr = ctypes.c_void_p()
        dev_ptr = ctypes.c_void_p()
        vol_ptr = ctypes.c_void_p()

        try:
            hr = self._ole32.CoCreateInstance(
                ctypes.byref(_CLSID_MMDEVICE_ENUMERATOR),
                None,
                _CLSCTX_ALL,
                ctypes.byref(_IID_IMMDEVICE_ENUMERATOR),
                ctypes.byref(enum_ptr),
            )
            if hr < 0 or not enum_ptr.value:
                return None

            get_default = _vtable_fn(
                enum_ptr,
                4,
                ctypes.c_long,
                ctypes.c_uint,
                ctypes.c_uint,
                ctypes.POINTER(ctypes.c_void_p),
            )
            hr = get_default(enum_ptr, _E_RENDER, _E_MULTIMEDIA, ctypes.byref(dev_ptr))
            if hr < 0 or not dev_ptr.value:
                return None

            activate = _vtable_fn(
                dev_ptr,
                3,
                ctypes.c_long,
                ctypes.POINTER(_GUID),
                wintypes.DWORD,
                ctypes.c_void_p,
                ctypes.POINTER(ctypes.c_void_p),
            )
            hr = activate(
                dev_ptr,
                ctypes.byref(_IID_IAUDIO_ENDPOINT_VOLUME),
                _CLSCTX_ALL,
                None,
                ctypes.byref(vol_ptr),
            )
            if hr < 0 or not vol_ptr.value:
                return None

            return fn(vol_ptr)
        except Exception as exc:
            logger.debug("Core Audio volume call failed: %s", exc)
            return None
        finally:
            _release_com(vol_ptr)
            _release_com(dev_ptr)
            _release_com(enum_ptr)

    @staticmethod
    def _read_vol_and_mute(vol_ptr: ctypes.c_void_p) -> tuple[float, bool] | None:
        get_scalar = _vtable_fn(vol_ptr, 9, ctypes.c_long, ctypes.POINTER(ctypes.c_float))
        get_mute = _vtable_fn(vol_ptr, 15, ctypes.c_long, ctypes.POINTER(wintypes.BOOL))
        lvl = ctypes.c_float()
        muted = wintypes.BOOL()
        if get_scalar(vol_ptr, ctypes.byref(lvl)) < 0:
            return None
        if get_mute(vol_ptr, ctypes.byref(muted)) < 0:
            return None
        return (min(max(float(lvl.value), 0.0), 1.0), bool(muted.value))

    def get_state(self) -> tuple[float, bool] | None:
        return self._with_endpoint(self._read_vol_and_mute)

    def set_volume(self, level: float, unmute_if_positive: bool = True) -> tuple[float, bool] | None:
        clamped = min(max(float(level), 0.0), 1.0)

        def _op(vol_ptr: ctypes.c_void_p) -> tuple[float, bool] | None:
            set_scalar = _vtable_fn(vol_ptr, 7, ctypes.c_long, ctypes.c_float, ctypes.c_void_p)
            if set_scalar(vol_ptr, ctypes.c_float(clamped), None) < 0:
                return None
            if unmute_if_positive and clamped > 0.0:
                set_mute = _vtable_fn(vol_ptr, 14, ctypes.c_long, wintypes.BOOL, ctypes.c_void_p)
                set_mute(vol_ptr, wintypes.BOOL(0), None)
            return self._read_vol_and_mute(vol_ptr)

        return self._with_endpoint(_op)

    def set_mute(self, muted: bool) -> tuple[float, bool] | None:
        def _op(vol_ptr: ctypes.c_void_p) -> tuple[float, bool] | None:
            set_mute = _vtable_fn(vol_ptr, 14, ctypes.c_long, wintypes.BOOL, ctypes.c_void_p)
            if set_mute(vol_ptr, wintypes.BOOL(1 if muted else 0), None) < 0:
                return None
            return self._read_vol_and_mute(vol_ptr)

        return self._with_endpoint(_op)


class VolumeController:
    """High-level Windows system volume & mute controller with safe clamping."""

    def __init__(self, backend: VolumeBackend | None = None) -> None:
        self._backend: VolumeBackend = backend or _WindowsCoreAudioBackend()

    def get_state(self) -> tuple[float, bool] | None:
        return self._backend.get_state()

    def set_volume(self, level: float, unmute_if_positive: bool = True) -> tuple[float, bool] | None:
        clamped = min(max(float(level), 0.0), 1.0)
        return self._backend.set_volume(clamped, unmute_if_positive=unmute_if_positive)

    def step_volume(self, delta: float) -> tuple[float, bool] | None:
        current = self.get_state()
        if current is None:
            return None
        cur_level, _ = current
        target = min(max(cur_level + delta, 0.0), 1.0)
        return self.set_volume(target, unmute_if_positive=(delta > 0.0))

    def toggle_mute(self) -> tuple[float, bool] | None:
        current = self.get_state()
        if current is None:
            return None
        _, cur_muted = current
        return self._backend.set_mute(not cur_muted)
