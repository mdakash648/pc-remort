"""Windows power & session management controller."""

from __future__ import annotations

import ctypes
import logging
import subprocess
import sys
import threading
import time
from ctypes import wintypes
from typing import Callable

logger = logging.getLogger(__name__)

_HWND_BROADCAST = 0xFFFF
_WM_SYSCOMMAND = 0x0112
_SC_MONITORPOWER = 0xF170
_MONITOR_OFF = 2
_CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)

VALID_POWER_ACTIONS = frozenset(
    {
        "sleep",
        "hibernate",
        "shutdown",
        "restart",
        "lock",
        "display_off",
        "sign_out",
    }
)


def _post_display_off_windows() -> None:
    try:
        # Brief delay prevents trailing touch/key events from immediately waking the monitor
        time.sleep(0.25)
        from core.windows_integration import ensure_thread_on_input_desktop

        ensure_thread_on_input_desktop(force=True)
        user32 = ctypes.WinDLL("user32", use_last_error=True)
        user32.PostMessageW.argtypes = (
            wintypes.HWND,
            wintypes.UINT,
            wintypes.WPARAM,
            wintypes.LPARAM,
        )
        user32.PostMessageW.restype = wintypes.BOOL
        user32.PostMessageW(
            wintypes.HWND(_HWND_BROADCAST),
            _WM_SYSCOMMAND,
            _SC_MONITORPOWER,
            _MONITOR_OFF,
        )
    except Exception as exc:
        logger.warning("Failed to turn off display: %s", exc)


def _suspend_windows(hibernate: bool) -> None:
    try:
        powrprof = ctypes.WinDLL("powrprof", use_last_error=True)
        powrprof.SetSuspendState.argtypes = (
            wintypes.BOOLEAN,
            wintypes.BOOLEAN,
            wintypes.BOOLEAN,
        )
        powrprof.SetSuspendState.restype = wintypes.BOOLEAN
        ok = bool(powrprof.SetSuspendState(1 if hibernate else 0, 0, 0))
        if not ok and hibernate:
            subprocess.Popen(["shutdown", "/h"], creationflags=_CREATE_NO_WINDOW)
    except Exception as exc:
        logger.warning("Failed to suspend Windows (hibernate=%s): %s", hibernate, exc)


def _execute_power_windows(action: str) -> bool:
    if sys.platform != "win32":
        logger.warning("Power action '%s' is only supported on Windows", action)
        return False

    try:
        if action == "lock":
            from core.windows_integration import lock_active_console_session

            return lock_active_console_session()

        if action == "display_off":
            threading.Thread(
                target=_post_display_off_windows,
                name="remotepad-display-off",
                daemon=True,
            ).start()
            return True

        if action == "sleep":
            threading.Thread(
                target=_suspend_windows,
                args=(False,),
                name="remotepad-sleep",
                daemon=True,
            ).start()
            return True

        if action == "hibernate":
            threading.Thread(
                target=_suspend_windows,
                args=(True,),
                name="remotepad-hibernate",
                daemon=True,
            ).start()
            return True

        if action == "shutdown":
            subprocess.Popen(["shutdown", "/s", "/t", "0"], creationflags=_CREATE_NO_WINDOW)
            return True

        if action == "restart":
            subprocess.Popen(["shutdown", "/r", "/t", "0"], creationflags=_CREATE_NO_WINDOW)
            return True

        if action == "sign_out":
            from core.windows_integration import logoff_active_console_session

            return logoff_active_console_session()
    except Exception as exc:
        logger.warning("Failed to execute power action '%s': %s", action, exc)
        return False

    return False


class PowerController:
    """Executes Windows power commands when enabled in config."""

    def __init__(
        self,
        enabled: bool = True,
        executor: Callable[[str], bool] | None = None,
    ) -> None:
        self.enabled = enabled
        self._executor = executor or _execute_power_windows

    def handle(self, action: str) -> bool:
        normalized = action.strip().lower()
        if not self.enabled:
            logger.info("Power command '%s' ignored because power control is disabled", normalized)
            return False
        if normalized not in VALID_POWER_ACTIONS:
            logger.debug("Ignoring unknown power action '%s'", action)
            return False
        logger.info("Executing power command: %s", normalized)
        return self._executor(normalized)
