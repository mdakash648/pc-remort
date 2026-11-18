"""Windows SendInput keyboard controller (Virtual Keys + Unicode typing)."""

from __future__ import annotations

import ctypes
import logging
import sys
import time
from ctypes import wintypes
from typing import Callable, Sequence

from input.mouse import INPUT, INPUT_KEYBOARD, KEYBDINPUT

logger = logging.getLogger(__name__)

# Windows keybd_event / SendInput flags
KEYEVENTF_EXTENDEDKEY = 0x0001
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004
KEYEVENTF_SCANCODE = 0x0008

_user32 = ctypes.WinDLL("user32", use_last_error=True) if hasattr(ctypes, "WinDLL") else None
_kernel32 = ctypes.WinDLL("kernel32", use_last_error=True) if hasattr(ctypes, "WinDLL") else None

CF_UNICODETEXT = 13
GMEM_MOVEABLE = 0x0002

if _user32 is not None:
    _user32.SendInput.argtypes = (
        wintypes.UINT,
        ctypes.POINTER(INPUT),
        ctypes.c_int,
    )
    _user32.SendInput.restype = wintypes.UINT
    _user32.MapVirtualKeyW.argtypes = (wintypes.UINT, wintypes.UINT)
    _user32.MapVirtualKeyW.restype = wintypes.UINT
    _user32.VkKeyScanW.argtypes = (wintypes.WCHAR,)
    _user32.VkKeyScanW.restype = ctypes.c_short
    _user32.OpenClipboard.argtypes = (wintypes.HWND,)
    _user32.OpenClipboard.restype = wintypes.BOOL
    _user32.EmptyClipboard.argtypes = ()
    _user32.EmptyClipboard.restype = wintypes.BOOL
    _user32.SetClipboardData.argtypes = (wintypes.UINT, wintypes.HANDLE)
    _user32.SetClipboardData.restype = wintypes.HANDLE
    _user32.CloseClipboard.argtypes = ()
    _user32.CloseClipboard.restype = wintypes.BOOL

if _kernel32 is not None:
    _kernel32.GlobalAlloc.argtypes = (wintypes.UINT, ctypes.c_size_t)
    _kernel32.GlobalAlloc.restype = wintypes.HGLOBAL
    _kernel32.GlobalLock.argtypes = (wintypes.HGLOBAL,)
    _kernel32.GlobalLock.restype = ctypes.c_void_p
    _kernel32.GlobalUnlock.argtypes = (wintypes.HGLOBAL,)
    _kernel32.GlobalUnlock.restype = wintypes.BOOL
    _kernel32.GlobalFree.argtypes = (wintypes.HGLOBAL,)
    _kernel32.GlobalFree.restype = wintypes.HGLOBAL

_last_kb_error_log = 0.0

# Virtual-Key codes (Windows WinUser.h)
VK_MAP: dict[str, int] = {
    # Modifiers
    "ctrl": 0xA2,       # VK_LCONTROL
    "lctrl": 0xA2,
    "rctrl": 0xA3,      # VK_RCONTROL
    "shift": 0xA0,      # VK_LSHIFT
    "lshift": 0xA0,
    "rshift": 0xA1,     # VK_RSHIFT
    "alt": 0xA4,        # VK_LMENU
    "lalt": 0xA4,
    "ralt": 0xA5,       # VK_RMENU
    "win": 0x5B,        # VK_LWIN
    "lwin": 0x5B,
    "rwin": 0x5C,       # VK_RWIN
    # Common control & editing keys
    "backspace": 0x08,
    "bksp": 0x08,
    "tab": 0x09,
    "enter": 0x0D,
    "return": 0x0D,
    "pause": 0x13,
    "capslock": 0x14,
    "caps": 0x14,
    "esc": 0x1B,
    "escape": 0x1B,
    "space": 0x20,
    "pgup": 0x21,
    "page_up": 0x21,
    "pageup": 0x21,
    "pgdn": 0x22,
    "page_down": 0x22,
    "pagedown": 0x22,
    "end": 0x23,
    "home": 0x24,
    "left": 0x25,
    "up": 0x26,
    "right": 0x27,
    "down": 0x28,
    "prtsc": 0x2C,
    "printscreen": 0x2C,
    "ins": 0x2D,
    "insert": 0x2D,
    "del": 0x2E,
    "delete": 0x2E,
    "menu": 0x5D,
    "apps": 0x5D,
    "numlock": 0x90,
    "scrolllock": 0x91,
    # Punctuation / OEM keys (US layout VK codes for shortcut combos)
    ";": 0xBA,
    "=": 0xBB,
    ",": 0xBC,
    "-": 0xBD,
    ".": 0xBE,
    "/": 0xBF,
    "`": 0xC0,
    "[": 0xDB,
    "\\": 0xDC,
    "]": 0xDD,
    "'": 0xDE,
    "plus": 0xBB,
    "minus": 0xBD,
    # Browser keys
    "browser_back": 0xA6,
    "browser_forward": 0xA7,
    "browser_refresh": 0xA8,
    "browser_stop": 0xA9,
    "browser_search": 0xAA,
    "browser_favorites": 0xAB,
    "browser_home": 0xAC,
    # Media & Volume keys
    "vol_mute": 0xAD,
    "vol_down": 0xAE,
    "vol_up": 0xAF,
    "media_next": 0xB0,
    "media_prev": 0xB1,
    "media_stop": 0xB2,
    "media_play_pause": 0xB3,
}

# Add F1-F12 (0x70..0x7B)
for _i in range(1, 13):
    VK_MAP[f"f{_i}"] = 0x6F + _i

# Add 0-9 (0x30..0x39)
for _d in range(10):
    VK_MAP[str(_d)] = 0x30 + _d

# Add a-z (0x41..0x5A)
for _c in range(26):
    VK_MAP[chr(ord("a") + _c)] = 0x41 + _c

EXTENDED_VKS: frozenset[int] = frozenset(
    {
        0xA3,  # VK_RCONTROL
        0xA5,  # VK_RMENU
        0x5B,  # VK_LWIN
        0x5C,  # VK_RWIN
        0x5D,  # VK_APPS
        0x21,  # VK_PRIOR (PgUp)
        0x22,  # VK_NEXT (PgDn)
        0x23,  # VK_END
        0x24,  # VK_HOME
        0x25,  # VK_LEFT
        0x26,  # VK_UP
        0x27,  # VK_RIGHT
        0x28,  # VK_DOWN
        0x2C,  # VK_SNAPSHOT (PrtSc)
        0x2D,  # VK_INSERT
        0x2E,  # VK_DELETE
        0x90,  # VK_NUMLOCK
        0xA6,  # VK_BROWSER_BACK
        0xA7,  # VK_BROWSER_FORWARD
        0xA8,  # VK_BROWSER_REFRESH
        0xA9,  # VK_BROWSER_STOP
        0xAA,  # VK_BROWSER_SEARCH
        0xAB,  # VK_BROWSER_FAVORITES
        0xAC,  # VK_BROWSER_HOME
        0xAD,  # VK_VOLUME_MUTE
        0xAE,  # VK_VOLUME_DOWN
        0xAF,  # VK_VOLUME_UP
        0xB0,  # VK_MEDIA_NEXT_TRACK
        0xB1,  # VK_MEDIA_PREV_TRACK
        0xB2,  # VK_MEDIA_STOP
        0xB3,  # VK_MEDIA_PLAY_PAUSE
    }
)


def resolve_vk(key_name: str) -> int | None:
    """Resolve a protocol key name (case-insensitive) to a Windows Virtual-Key code."""
    return VK_MAP.get(key_name.strip().lower())


def _make_vk_input(vk: int, key_up: bool = False) -> INPUT:
    flags = 0
    if vk in EXTENDED_VKS:
        flags |= KEYEVENTF_EXTENDEDKEY
    if key_up:
        flags |= KEYEVENTF_KEYUP

    scan = 0
    if _user32 is not None:
        try:
            scan = int(_user32.MapVirtualKeyW(vk, 0)) & 0xFFFF
        except Exception:
            scan = 0

    return INPUT(
        type=INPUT_KEYBOARD,
        ki=KEYBDINPUT(
            wVk=vk,
            wScan=scan,
            dwFlags=flags,
            time=0,
            dwExtraInfo=0,
        ),
    )


def _make_unicode_input(code_unit: int, key_up: bool = False) -> INPUT:
    flags = KEYEVENTF_UNICODE | (KEYEVENTF_KEYUP if key_up else 0)
    return INPUT(
        type=INPUT_KEYBOARD,
        ki=KEYBDINPUT(
            wVk=0,
            wScan=code_unit & 0xFFFF,
            dwFlags=flags,
            time=0,
            dwExtraInfo=0,
        ),
    )


def _encode_ascii_vk_inputs(ch: str) -> list[INPUT] | None:
    """Translate a printable ASCII character to hardware-like VK events via VkKeyScanW.

    Used on the Windows Lock Screen (Winlogon desktop) where LogonUI.exe accepts
    virtual-key scan events reliably to dismiss the lock curtain and enter PINs/passwords.
    """
    if _user32 is None or len(ch) != 1 or not (0x20 <= ord(ch) <= 0x7E):
        return None
    try:
        packed = int(_user32.VkKeyScanW(ch))
    except Exception:
        return None
    if packed == -1:
        return None
    vk = packed & 0xFF
    shift_state = (packed >> 8) & 0xFF
    if vk == 0:
        return None

    modifiers: list[int] = []
    if shift_state & 1:
        modifiers.append(VK_MAP["lshift"])
    if shift_state & 2:
        modifiers.append(VK_MAP["lctrl"])
    if shift_state & 4:
        modifiers.append(VK_MAP["lalt"])

    events: list[INPUT] = [_make_vk_input(m, key_up=False) for m in modifiers]
    events.append(_make_vk_input(vk, key_up=False))
    events.append(_make_vk_input(vk, key_up=True))
    events.extend(_make_vk_input(m, key_up=True) for m in reversed(modifiers))
    return events


def _is_on_winlogon_desktop() -> bool:
    if sys.platform != "win32":
        return False
    try:
        from core.windows_integration import ensure_thread_on_input_desktop

        name = ensure_thread_on_input_desktop(force=False)
        return bool(name and name.lower() == "winlogon")
    except Exception:
        return False


def _send_inputs_windows(inputs: Sequence[INPUT]) -> int:
    global _last_kb_error_log
    if not inputs or _user32 is None:
        return 0
    if sys.platform == "win32":
        from core.windows_integration import ensure_thread_on_input_desktop

        ensure_thread_on_input_desktop(force=False)
    array_type = INPUT * len(inputs)
    arr = array_type(*inputs)
    ctypes.set_last_error(0)
    sent = _user32.SendInput(
        len(inputs),
        arr,
        ctypes.sizeof(INPUT),
    )
    if sent != len(inputs) and sys.platform == "win32":
        from core.windows_integration import ensure_thread_on_input_desktop

        ensure_thread_on_input_desktop(force=True)
        ctypes.set_last_error(0)
        sent = _user32.SendInput(
            len(inputs),
            arr,
            ctypes.sizeof(INPUT),
        )
    if sent != len(inputs):
        err = ctypes.get_last_error()
        now = time.monotonic()
        if now - _last_kb_error_log >= 5.0:
            _last_kb_error_log = now
            if err == 5:
                logger.warning(
                    "Keyboard SendInput blocked (ERROR_ACCESS_DENIED). "
                    "Run pc_agent from your normal Windows desktop session "
                    "(or via the RemotePad Boot Service for Lock Screen / elevated windows)."
                )
            else:
                logger.warning(
                    "Keyboard SendInput sent %d/%d events (win32 error=%d)",
                    sent,
                    len(inputs),
                    err,
                )
    return int(sent)


def _set_clipboard_text_windows(text: str) -> bool:
    """Copy Unicode text (with Windows CRLF paragraph breaks) to the Windows clipboard."""
    if _user32 is None or _kernel32 is None:
        return False
    if sys.platform == "win32":
        from core.windows_integration import ensure_thread_on_input_desktop

        ensure_thread_on_input_desktop(force=True)

    normalized = text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n")
    raw = (normalized + "\x00").encode("utf-16-le")

    opened = False
    for _ in range(6):
        if _user32.OpenClipboard(None):
            opened = True
            break
        time.sleep(0.015)

    if not opened:
        logger.warning("Could not open Windows clipboard for paragraph paste")
        return False

    hmem = None
    try:
        if not _user32.EmptyClipboard():
            return False
        hmem = _kernel32.GlobalAlloc(GMEM_MOVEABLE, len(raw))
        if not hmem:
            return False
        ptr = _kernel32.GlobalLock(hmem)
        if not ptr:
            _kernel32.GlobalFree(hmem)
            hmem = None
            return False
        try:
            ctypes.memmove(ptr, raw, len(raw))
        finally:
            _kernel32.GlobalUnlock(hmem)

        if not _user32.SetClipboardData(CF_UNICODETEXT, hmem):
            _kernel32.GlobalFree(hmem)
            hmem = None
            return False
        # Ownership transferred to Windows once SetClipboardData succeeds
        hmem = None
        return True
    finally:
        if hmem:
            _kernel32.GlobalFree(hmem)
        _user32.CloseClipboard()


class KeyboardController:
    """Translates protocol keyboard commands into Windows SendInput calls."""

    def __init__(
        self,
        sender: Callable[[Sequence[INPUT]], int] | None = None,
        clipboard_setter: Callable[[str], bool] | None = None,
    ) -> None:
        self._sender = sender or _send_inputs_windows
        self._clipboard_setter = clipboard_setter or _set_clipboard_text_windows

    def key_event(self, key: str, state: str) -> bool:
        """Send a single key down or key up event."""
        vk = resolve_vk(key)
        if vk is None or state not in ("down", "up"):
            return False
        self._sender([_make_vk_input(vk, key_up=(state == "up"))])
        return True

    def key_combo(self, keys: Sequence[str]) -> bool:
        """Press all keys in sequence, then release them in reverse order."""
        vks: list[int] = []
        for k in keys:
            vk = resolve_vk(k)
            if vk is None:
                logger.debug("Unknown key in combo: %r", k)
                return False
            vks.append(vk)
        if not vks:
            return False

        down_events: list[INPUT] = [_make_vk_input(vk, key_up=False) for vk in vks]
        up_events: list[INPUT] = [_make_vk_input(vk, key_up=True) for vk in reversed(vks)]
        if len(vks) > 1 and self._sender is _send_inputs_windows:
            # Small hold delay helps Office/PowerPoint & browsers reliably register modifier combos
            self._sender(down_events)
            time.sleep(0.015)
            self._sender(up_events)
        else:
            self._sender(down_events + up_events)
        return True

    def type_text(self, text: str) -> int:
        """Type an arbitrary Unicode string (including Bangla) via KEYEVENTF_UNICODE.

        Returns the number of SendInput events dispatched.
        """
        if not text:
            return 0

        use_winlogon_vk = self._sender is _send_inputs_windows and _is_on_winlogon_desktop()
        events: list[INPUT] = []
        for ch in text:
            if ch == "\r":
                continue
            if ch == "\n":
                events.append(_make_vk_input(VK_MAP["enter"], key_up=False))
                events.append(_make_vk_input(VK_MAP["enter"], key_up=True))
                continue
            if ch == "\t":
                events.append(_make_vk_input(VK_MAP["tab"], key_up=False))
                events.append(_make_vk_input(VK_MAP["tab"], key_up=True))
                continue

            if use_winlogon_vk:
                vk_events = _encode_ascii_vk_inputs(ch)
                if vk_events is not None:
                    events.extend(vk_events)
                    continue

            # Encode to UTF-16LE so both BMP (English, Bangla) and surrogate pairs work
            encoded = ch.encode("utf-16-le")
            units = [
                int.from_bytes(encoded[i : i + 2], "little")
                for i in range(0, len(encoded), 2)
            ]
            for unit in units:
                events.append(_make_unicode_input(unit, key_up=False))
            for unit in units:
                events.append(_make_unicode_input(unit, key_up=True))

        if not events:
            return 0
        return self._sender(events)

    def paste_text(self, text: str) -> bool:
        """Paste multi-paragraph Unicode text via clipboard + Ctrl+V without triggering Enter.

        Ideal for sending multi-paragraph messages into chat apps (WhatsApp, Messenger,
        ChatGPT, Discord) where raw Enter keystrokes would prematurely submit each paragraph.
        """
        if not text:
            return False
        if not self._clipboard_setter(text):
            return False
        if self._sender is _send_inputs_windows:
            time.sleep(0.02)
        return self.key_combo(["ctrl", "v"])
