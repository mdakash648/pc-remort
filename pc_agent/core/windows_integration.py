"""Windows OS integration helpers: elevation, Boot/Logon Task Scheduler, Winlogon desktop switching, and IPC."""

from __future__ import annotations

import ctypes
import logging
import os
import subprocess
import sys
import tempfile
import threading
import time
from ctypes import wintypes
from pathlib import Path
from typing import Callable, Sequence
from xml.sax.saxutils import escape as xml_escape

logger = logging.getLogger(__name__)

APP_USER_MODEL_ID = "RemotePad.PCAgent"
AUTOSTART_VALUE_NAME = "RemotePad"
AUTOSTART_TASK_NAME = "RemotePad"
BOOT_SERVICE_TASK_NAME = "RemotePadService"
RUN_REG_PATH = r"Software\Microsoft\Windows\CurrentVersion\Run"

SINGLE_INSTANCE_MUTEX_NAME = "Local\\RemotePad_PC_Agent_SingleInstance_v1"
SHOW_WINDOW_EVENT_NAME = "Local\\RemotePad_PC_Agent_ShowWindow_v1"
SERVICE_SUPERVISOR_MUTEX_NAME = "Global\\RemotePad_Service_Supervisor_v1"
SERVICE_WORKER_MUTEX_NAME = "Global\\RemotePad_Agent_Worker_v1"
SERVICE_STOP_EVENT_NAME = "Global\\RemotePad_Service_Stop_v1"

EXIT_CODE_USER_QUIT = 42

_ERROR_ACCESS_DENIED = 5
_ERROR_ALREADY_EXISTS = 183
_EVENT_MODIFY_STATE = 0x0002
_WAIT_OBJECT_0 = 0x00000000
_WAIT_TIMEOUT = 0x00000102
_CREATE_NO_WINDOW = 0x08000000
_CREATE_UNICODE_ENVIRONMENT = 0x00000400

_TOKEN_QUERY = 0x0008
_TOKEN_ADJUST_PRIVILEGES = 0x0020
_TOKEN_ALL_ACCESS = 0x000F01FF
_TOKEN_USER_CLASS = 1
_TOKEN_SESSION_ID_CLASS = 12
_SECURITY_IDENTIFICATION = 2
_TOKEN_PRIMARY = 1
_MAXIMUM_ALLOWED = 0x02000000
_SE_PRIVILEGE_ENABLED = 0x00000002

_UOI_NAME = 2
_DESKTOP_ACCESS_ALL = 0x01FF
_DESKTOP_ACCESS_MIN = 0x0181  # READOBJECTS | WRITEOBJECTS | SWITCHDESKTOP
_DESKTOP_CHECK_INTERVAL_S = 0.15

_SEE_MASK_NOCLOSEPROCESS = 0x00000040

# Allows Administrators full access and Authenticated Users (including Medium-IL launches in the
# same Local\ session) to signal the ShowWindow event without needing a UAC prompt.
_SHARED_IPC_SDDL = "D:(A;;GA;;;BA)(A;;0x00100002;;;AU)S:(ML;;NW;;;ME)"

# Allows SYSTEM and Administrators full control of global service stop/mutex objects.
_SERVICE_CONTROL_SDDL = "D:(A;;GA;;;SY)(A;;GA;;;BA)"

_desktop_tls = threading.local()
_is_system_cached: bool | None = None


class _SECURITY_ATTRIBUTES(ctypes.Structure):
    _fields_ = [
        ("nLength", wintypes.DWORD),
        ("lpSecurityDescriptor", wintypes.LPVOID),
        ("bInheritHandle", wintypes.BOOL),
    ]


class _LUID(ctypes.Structure):
    _fields_ = [
        ("LowPart", wintypes.DWORD),
        ("HighPart", wintypes.LONG),
    ]


class _LUID_AND_ATTRIBUTES(ctypes.Structure):
    _fields_ = [
        ("Luid", _LUID),
        ("Attributes", wintypes.DWORD),
    ]


class _TOKEN_PRIVILEGES(ctypes.Structure):
    _fields_ = [
        ("PrivilegeCount", wintypes.DWORD),
        ("Privileges", _LUID_AND_ATTRIBUTES * 1),
    ]


class _SID_AND_ATTRIBUTES(ctypes.Structure):
    _fields_ = [
        ("Sid", wintypes.LPVOID),
        ("Attributes", wintypes.DWORD),
    ]


class _TOKEN_USER(ctypes.Structure):
    _fields_ = [
        ("User", _SID_AND_ATTRIBUTES),
    ]


class _STARTUPINFOW(ctypes.Structure):
    _fields_ = [
        ("cb", wintypes.DWORD),
        ("lpReserved", wintypes.LPWSTR),
        ("lpDesktop", wintypes.LPWSTR),
        ("lpTitle", wintypes.LPWSTR),
        ("dwX", wintypes.DWORD),
        ("dwY", wintypes.DWORD),
        ("dwXSize", wintypes.DWORD),
        ("dwYSize", wintypes.DWORD),
        ("dwXCountChars", wintypes.DWORD),
        ("dwYCountChars", wintypes.DWORD),
        ("dwFillAttribute", wintypes.DWORD),
        ("dwFlags", wintypes.DWORD),
        ("wShowWindow", wintypes.WORD),
        ("cbReserved2", wintypes.WORD),
        ("lpReserved2", ctypes.POINTER(ctypes.c_byte)),
        ("hStdInput", wintypes.HANDLE),
        ("hStdOutput", wintypes.HANDLE),
        ("hStdError", wintypes.HANDLE),
    ]


class _PROCESS_INFORMATION(ctypes.Structure):
    _fields_ = [
        ("hProcess", wintypes.HANDLE),
        ("hThread", wintypes.HANDLE),
        ("dwProcessId", wintypes.DWORD),
        ("dwThreadId", wintypes.DWORD),
    ]


class _SHELLEXECUTEINFOW(ctypes.Structure):
    _fields_ = [
        ("cbSize", wintypes.DWORD),
        ("fMask", wintypes.ULONG),
        ("hwnd", wintypes.HWND),
        ("lpVerb", wintypes.LPCWSTR),
        ("lpFile", wintypes.LPCWSTR),
        ("lpParameters", wintypes.LPCWSTR),
        ("lpDirectory", wintypes.LPCWSTR),
        ("nShow", ctypes.c_int),
        ("hInstApp", wintypes.HINSTANCE),
        ("lpIDList", wintypes.LPVOID),
        ("lpClass", wintypes.LPCWSTR),
        ("hkeyClass", wintypes.HKEY),
        ("dwHotKey", wintypes.DWORD),
        ("hIconOrMonitor", wintypes.HANDLE),
        ("hProcess", wintypes.HANDLE),
    ]


def get_resource_path(relative_path: str) -> Path:
    """Resolve bundled data file paths for both PyInstaller frozen builds and source runs."""
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        candidate = Path(meipass) / relative_path
        if candidate.exists():
            return candidate
    base_dir = Path(__file__).resolve().parent.parent
    return base_dir / relative_path


def is_admin() -> bool:
    """Return True if the current process is running with Windows Administrator privileges."""
    if sys.platform != "win32":
        return False
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def is_system() -> bool:
    """Return True if the current process is running as NT AUTHORITY\\SYSTEM (S-1-5-18)."""
    global _is_system_cached
    if _is_system_cached is not None:
        return _is_system_cached
    if sys.platform != "win32":
        _is_system_cached = False
        return False

    try:
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        advapi32 = ctypes.WinDLL("advapi32", use_last_error=True)

        kernel32.GetCurrentProcess.restype = wintypes.HANDLE
        kernel32.CloseHandle.argtypes = (wintypes.HANDLE,)
        kernel32.CloseHandle.restype = wintypes.BOOL
        kernel32.LocalFree.argtypes = (wintypes.HLOCAL,)
        kernel32.LocalFree.restype = wintypes.HLOCAL

        advapi32.OpenProcessToken.argtypes = (
            wintypes.HANDLE,
            wintypes.DWORD,
            ctypes.POINTER(wintypes.HANDLE),
        )
        advapi32.OpenProcessToken.restype = wintypes.BOOL
        advapi32.GetTokenInformation.argtypes = (
            wintypes.HANDLE,
            ctypes.c_int,
            wintypes.LPVOID,
            wintypes.DWORD,
            ctypes.POINTER(wintypes.DWORD),
        )
        advapi32.GetTokenInformation.restype = wintypes.BOOL
        advapi32.ConvertSidToStringSidW.argtypes = (
            wintypes.LPVOID,
            ctypes.POINTER(wintypes.LPWSTR),
        )
        advapi32.ConvertSidToStringSidW.restype = wintypes.BOOL

        token = wintypes.HANDLE()
        if not advapi32.OpenProcessToken(kernel32.GetCurrentProcess(), _TOKEN_QUERY, ctypes.byref(token)):
            _is_system_cached = False
            return False
        try:
            needed = wintypes.DWORD(0)
            advapi32.GetTokenInformation(token, _TOKEN_USER_CLASS, None, 0, ctypes.byref(needed))
            if not needed.value:
                _is_system_cached = False
                return False
            buf = ctypes.create_string_buffer(needed.value)
            if not advapi32.GetTokenInformation(token, _TOKEN_USER_CLASS, buf, needed.value, ctypes.byref(needed)):
                _is_system_cached = False
                return False
            token_user = ctypes.cast(buf, ctypes.POINTER(_TOKEN_USER)).contents
            sid_ptr = wintypes.LPWSTR()
            if not advapi32.ConvertSidToStringSidW(token_user.User.Sid, ctypes.byref(sid_ptr)):
                _is_system_cached = False
                return False
            try:
                sid_str = sid_ptr.value or ""
                _is_system_cached = sid_str.upper() == "S-1-5-18"
                return _is_system_cached
            finally:
                if sid_ptr.value:
                    kernel32.LocalFree(sid_ptr)
        finally:
            kernel32.CloseHandle(token)
    except Exception:
        _is_system_cached = os.environ.get("USERNAME", "").strip().upper() == "SYSTEM"
        return _is_system_cached


def get_active_console_session_id() -> int | None:
    """Return the active physical console Windows session ID, or None if no session is active."""
    if sys.platform != "win32":
        return None
    try:
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.WTSGetActiveConsoleSessionId.argtypes = ()
        kernel32.WTSGetActiveConsoleSessionId.restype = wintypes.DWORD
        sid = int(kernel32.WTSGetActiveConsoleSessionId())
        if sid == 0xFFFFFFFF:
            return None
        return sid
    except Exception:
        return None


def get_active_console_user_home() -> Path | None:
    """Return the profile directory (e.g. C:\\Users\\akash) of the active console user.

    Intended for processes running as NT AUTHORITY\\SYSTEM (requires SeTcbPrivilege).
    Returns None when no user is currently logged into the console (e.g., pre-login lock screen).
    """
    if sys.platform != "win32":
        return None
    sid = get_active_console_session_id()
    if sid is None or sid == 0:
        return None
    try:
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        wtsapi32 = ctypes.WinDLL("wtsapi32", use_last_error=True)
        userenv = ctypes.WinDLL("userenv", use_last_error=True)

        kernel32.CloseHandle.argtypes = (wintypes.HANDLE,)
        kernel32.CloseHandle.restype = wintypes.BOOL
        wtsapi32.WTSQueryUserToken.argtypes = (wintypes.ULONG, ctypes.POINTER(wintypes.HANDLE))
        wtsapi32.WTSQueryUserToken.restype = wintypes.BOOL
        userenv.GetUserProfileDirectoryW.argtypes = (
            wintypes.HANDLE,
            wintypes.LPWSTR,
            ctypes.POINTER(wintypes.DWORD),
        )
        userenv.GetUserProfileDirectoryW.restype = wintypes.BOOL

        user_token = wintypes.HANDLE()
        if not wtsapi32.WTSQueryUserToken(sid, ctypes.byref(user_token)):
            return None
        try:
            size = wintypes.DWORD(0)
            userenv.GetUserProfileDirectoryW(user_token, None, ctypes.byref(size))
            if not size.value:
                return None
            buf = ctypes.create_unicode_buffer(size.value)
            if not userenv.GetUserProfileDirectoryW(user_token, buf, ctypes.byref(size)):
                return None
            profile = Path(buf.value)
            return profile if profile.exists() else None
        finally:
            kernel32.CloseHandle(user_token)
    except Exception:
        return None


def get_effective_user_home() -> Path:
    """Return the interactive user's home directory, even when running as SYSTEM."""
    if sys.platform == "win32" and is_system():
        active_home = get_active_console_user_home()
        if active_home is not None:
            return active_home
        public_dir = Path(os.environ.get("PUBLIC", r"C:\Users\Public"))
        if public_dir.exists():
            return public_dir
    return Path.home()


def _get_desktop_name(user32: ctypes.WinDLL, hdesk: wintypes.HANDLE) -> str | None:
    if not hdesk:
        return None
    needed = wintypes.DWORD(0)
    buf = ctypes.create_unicode_buffer(128)
    ok = user32.GetUserObjectInformationW(
        hdesk,
        _UOI_NAME,
        buf,
        ctypes.sizeof(buf),
        ctypes.byref(needed),
    )
    if ok:
        return buf.value or None
    return None


def ensure_thread_on_input_desktop(force: bool = False) -> str | None:
    """Attach the calling thread to the currently active Windows input desktop.

    When running as SYSTEM inside the active console session, this seamlessly switches
    input-injecting threads between WinSta0\\Winlogon (Lock Screen / Sign-in / UAC)
    and WinSta0\\Default (normal user desktop) so SendInput works everywhere.
    """
    if sys.platform != "win32":
        return None

    now = time.monotonic()
    last_check = getattr(_desktop_tls, "last_check", 0.0)
    cached_name = getattr(_desktop_tls, "desktop_name", None)
    if not force and cached_name is not None and (now - last_check) < _DESKTOP_CHECK_INTERVAL_S:
        return cached_name

    _desktop_tls.last_check = now
    try:
        user32 = ctypes.WinDLL("user32", use_last_error=True)
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

        user32.OpenInputDesktop.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
        user32.OpenInputDesktop.restype = wintypes.HANDLE
        user32.GetThreadDesktop.argtypes = (wintypes.DWORD,)
        user32.GetThreadDesktop.restype = wintypes.HANDLE
        user32.SetThreadDesktop.argtypes = (wintypes.HANDLE,)
        user32.SetThreadDesktop.restype = wintypes.BOOL
        user32.CloseDesktop.argtypes = (wintypes.HANDLE,)
        user32.CloseDesktop.restype = wintypes.BOOL
        user32.GetUserObjectInformationW.argtypes = (
            wintypes.HANDLE,
            ctypes.c_int,
            wintypes.LPVOID,
            wintypes.DWORD,
            ctypes.POINTER(wintypes.DWORD),
        )
        user32.GetUserObjectInformationW.restype = wintypes.BOOL
        kernel32.GetCurrentThreadId.argtypes = ()
        kernel32.GetCurrentThreadId.restype = wintypes.DWORD

        h_input = user32.OpenInputDesktop(0, False, _DESKTOP_ACCESS_ALL)
        if not h_input:
            h_input = user32.OpenInputDesktop(0, False, _DESKTOP_ACCESS_MIN)
        if not h_input:
            _desktop_tls.desktop_name = None
            return None

        try:
            input_name = _get_desktop_name(user32, h_input)
            h_cur = user32.GetThreadDesktop(kernel32.GetCurrentThreadId())
            cur_name = _get_desktop_name(user32, h_cur) if h_cur else None

            if input_name and (cur_name is None or input_name.lower() != cur_name.lower()):
                if user32.SetThreadDesktop(h_input):
                    logger.info(
                        "Switched input thread desktop: %s -> %s",
                        cur_name or "unknown",
                        input_name,
                    )
                    _desktop_tls.desktop_name = input_name
                else:
                    err = ctypes.get_last_error()
                    logger.debug(
                        "SetThreadDesktop(%s -> %s) failed (win32 error=%d)",
                        cur_name,
                        input_name,
                        err,
                    )
                    _desktop_tls.desktop_name = cur_name
            else:
                _desktop_tls.desktop_name = input_name or cur_name
            return _desktop_tls.desktop_name
        finally:
            user32.CloseDesktop(h_input)
    except Exception:
        return cached_name


def get_current_input_desktop_name() -> str | None:
    """Return the active input desktop name (e.g. 'Default' or 'Winlogon') on Windows."""
    return ensure_thread_on_input_desktop(force=False)


def enable_process_privileges(privileges: Sequence[str]) -> None:
    """Best-effort enable named Win32 privileges on the current process token."""
    if sys.platform != "win32":
        return
    try:
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        advapi32 = ctypes.WinDLL("advapi32", use_last_error=True)

        kernel32.GetCurrentProcess.restype = wintypes.HANDLE
        kernel32.CloseHandle.argtypes = (wintypes.HANDLE,)
        kernel32.CloseHandle.restype = wintypes.BOOL
        advapi32.OpenProcessToken.argtypes = (
            wintypes.HANDLE,
            wintypes.DWORD,
            ctypes.POINTER(wintypes.HANDLE),
        )
        advapi32.OpenProcessToken.restype = wintypes.BOOL
        advapi32.LookupPrivilegeValueW.argtypes = (
            wintypes.LPCWSTR,
            wintypes.LPCWSTR,
            ctypes.POINTER(_LUID),
        )
        advapi32.LookupPrivilegeValueW.restype = wintypes.BOOL
        advapi32.AdjustTokenPrivileges.argtypes = (
            wintypes.HANDLE,
            wintypes.BOOL,
            ctypes.POINTER(_TOKEN_PRIVILEGES),
            wintypes.DWORD,
            wintypes.LPVOID,
            ctypes.POINTER(wintypes.DWORD),
        )
        advapi32.AdjustTokenPrivileges.restype = wintypes.BOOL

        token = wintypes.HANDLE()
        if not advapi32.OpenProcessToken(
            kernel32.GetCurrentProcess(),
            _TOKEN_ADJUST_PRIVILEGES | _TOKEN_QUERY,
            ctypes.byref(token),
        ):
            return
        try:
            for name in privileges:
                luid = _LUID()
                if not advapi32.LookupPrivilegeValueW(None, name, ctypes.byref(luid)):
                    continue
                tp = _TOKEN_PRIVILEGES()
                tp.PrivilegeCount = 1
                tp.Privileges[0].Luid = luid
                tp.Privileges[0].Attributes = _SE_PRIVILEGE_ENABLED
                advapi32.AdjustTokenPrivileges(token, False, ctypes.byref(tp), 0, None, None)
        finally:
            kernel32.CloseHandle(token)
    except Exception as exc:
        logger.debug("enable_process_privileges failed: %s", exc)


def spawn_process_in_session(
    session_id: int,
    executable: str,
    args: Sequence[str],
    desktop: str = "winsta0\\default",
    working_dir: str | None = None,
) -> tuple[int, int] | None:
    """Spawn a SYSTEM-privileged child process inside a specific interactive Windows session.

    Used by the Session-0 boot supervisor to launch RemotePad's headless agent worker
    inside the active console session (e.g., Session 1) before any user logs in.
    Returns (process_handle, pid) on success, or None on failure.
    """
    if sys.platform != "win32":
        return None

    enable_process_privileges(
        (
            "SeTcbPrivilege",
            "SeAssignPrimaryTokenPrivilege",
            "SeIncreaseQuotaPrivilege",
            "SeDebugPrivilege",
        )
    )

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    advapi32 = ctypes.WinDLL("advapi32", use_last_error=True)

    kernel32.GetCurrentProcess.restype = wintypes.HANDLE
    kernel32.CloseHandle.argtypes = (wintypes.HANDLE,)
    kernel32.CloseHandle.restype = wintypes.BOOL
    advapi32.OpenProcessToken.argtypes = (
        wintypes.HANDLE,
        wintypes.DWORD,
        ctypes.POINTER(wintypes.HANDLE),
    )
    advapi32.OpenProcessToken.restype = wintypes.BOOL
    advapi32.DuplicateTokenEx.argtypes = (
        wintypes.HANDLE,
        wintypes.DWORD,
        wintypes.LPVOID,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.POINTER(wintypes.HANDLE),
    )
    advapi32.DuplicateTokenEx.restype = wintypes.BOOL
    advapi32.SetTokenInformation.argtypes = (
        wintypes.HANDLE,
        ctypes.c_int,
        wintypes.LPVOID,
        wintypes.DWORD,
    )
    advapi32.SetTokenInformation.restype = wintypes.BOOL
    advapi32.CreateProcessAsUserW.argtypes = (
        wintypes.HANDLE,
        wintypes.LPCWSTR,
        wintypes.LPWSTR,
        wintypes.LPVOID,
        wintypes.LPVOID,
        wintypes.BOOL,
        wintypes.DWORD,
        wintypes.LPVOID,
        wintypes.LPCWSTR,
        ctypes.POINTER(_STARTUPINFOW),
        ctypes.POINTER(_PROCESS_INFORMATION),
    )
    advapi32.CreateProcessAsUserW.restype = wintypes.BOOL

    self_token = wintypes.HANDLE()
    dup_token = wintypes.HANDLE()

    if not advapi32.OpenProcessToken(
        kernel32.GetCurrentProcess(),
        _TOKEN_ALL_ACCESS,
        ctypes.byref(self_token),
    ):
        logger.warning("OpenProcessToken failed (err=%d)", ctypes.get_last_error())
        return None

    try:
        if not advapi32.DuplicateTokenEx(
            self_token,
            _MAXIMUM_ALLOWED,
            None,
            _SECURITY_IDENTIFICATION,
            _TOKEN_PRIMARY,
            ctypes.byref(dup_token),
        ):
            logger.warning("DuplicateTokenEx failed (err=%d)", ctypes.get_last_error())
            return None

        sid_dword = wintypes.DWORD(session_id)
        if not advapi32.SetTokenInformation(
            dup_token,
            _TOKEN_SESSION_ID_CLASS,
            ctypes.byref(sid_dword),
            ctypes.sizeof(sid_dword),
        ):
            logger.warning(
                "SetTokenInformation(TokenSessionId=%d) failed (err=%d)",
                session_id,
                ctypes.get_last_error(),
            )
            return None

        cmd_line = subprocess.list2cmdline([executable, *args])
        cmd_buf = ctypes.create_unicode_buffer(cmd_line)

        si = _STARTUPINFOW()
        si.cb = ctypes.sizeof(_STARTUPINFOW)
        si.lpDesktop = desktop
        pi = _PROCESS_INFORMATION()

        cwd = working_dir or str(Path(executable).parent)
        ok = advapi32.CreateProcessAsUserW(
            dup_token,
            executable,
            cmd_buf,
            None,
            None,
            False,
            _CREATE_NO_WINDOW | _CREATE_UNICODE_ENVIRONMENT,
            None,
            cwd,
            ctypes.byref(si),
            ctypes.byref(pi),
        )
        if not ok:
            logger.warning(
                "CreateProcessAsUserW(session=%d, desktop=%s) failed (err=%d)",
                session_id,
                desktop,
                ctypes.get_last_error(),
            )
            return None

        if pi.hThread:
            kernel32.CloseHandle(pi.hThread)
        return int(pi.hProcess), int(pi.dwProcessId)
    finally:
        if dup_token.value:
            kernel32.CloseHandle(dup_token)
        if self_token.value:
            kernel32.CloseHandle(self_token)


def wait_for_process_handle(handle: int, timeout_ms: int) -> int | None:
    """Wait up to timeout_ms for a Win32 process handle. Returns exit code if exited, else None."""
    if sys.platform != "win32" or not handle:
        return None
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.WaitForSingleObject.argtypes = (wintypes.HANDLE, wintypes.DWORD)
    kernel32.WaitForSingleObject.restype = wintypes.DWORD
    kernel32.GetExitCodeProcess.argtypes = (wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD))
    kernel32.GetExitCodeProcess.restype = wintypes.BOOL

    res = kernel32.WaitForSingleObject(wintypes.HANDLE(handle), wintypes.DWORD(timeout_ms))
    if res == _WAIT_OBJECT_0:
        code = wintypes.DWORD(0)
        if kernel32.GetExitCodeProcess(wintypes.HANDLE(handle), ctypes.byref(code)):
            return int(code.value)
        return 0
    return None


def terminate_process_handle(handle: int, exit_code: int = 0) -> None:
    """Forcefully terminate a Win32 process handle."""
    if sys.platform != "win32" or not handle:
        return
    try:
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.TerminateProcess.argtypes = (wintypes.HANDLE, wintypes.UINT)
        kernel32.TerminateProcess.restype = wintypes.BOOL
        kernel32.TerminateProcess(wintypes.HANDLE(handle), wintypes.UINT(exit_code))
    except Exception:
        pass


def close_win32_handle(handle: int | None) -> None:
    """Safely close a Win32 HANDLE."""
    if sys.platform != "win32" or not handle:
        return
    try:
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.CloseHandle.argtypes = (wintypes.HANDLE,)
        kernel32.CloseHandle.restype = wintypes.BOOL
        kernel32.CloseHandle(wintypes.HANDLE(handle))
    except Exception:
        pass


def lock_active_console_session() -> bool:
    """Lock the active Windows workstation, supporting both user sessions and SYSTEM workers."""
    if sys.platform != "win32":
        return False
    if not is_system():
        user32 = ctypes.WinDLL("user32", use_last_error=True)
        user32.LockWorkStation.restype = wintypes.BOOL
        return bool(user32.LockWorkStation())

    sid = get_active_console_session_id()
    if sid is None or sid == 0:
        return False

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    wtsapi32 = ctypes.WinDLL("wtsapi32", use_last_error=True)
    advapi32 = ctypes.WinDLL("advapi32", use_last_error=True)

    kernel32.CloseHandle.argtypes = (wintypes.HANDLE,)
    kernel32.CloseHandle.restype = wintypes.BOOL
    wtsapi32.WTSQueryUserToken.argtypes = (wintypes.ULONG, ctypes.POINTER(wintypes.HANDLE))
    wtsapi32.WTSQueryUserToken.restype = wintypes.BOOL
    advapi32.CreateProcessAsUserW.argtypes = (
        wintypes.HANDLE,
        wintypes.LPCWSTR,
        wintypes.LPWSTR,
        wintypes.LPVOID,
        wintypes.LPVOID,
        wintypes.BOOL,
        wintypes.DWORD,
        wintypes.LPVOID,
        wintypes.LPCWSTR,
        ctypes.POINTER(_STARTUPINFOW),
        ctypes.POINTER(_PROCESS_INFORMATION),
    )
    advapi32.CreateProcessAsUserW.restype = wintypes.BOOL

    user_token = wintypes.HANDLE()
    if not wtsapi32.WTSQueryUserToken(sid, ctypes.byref(user_token)):
        logger.debug("WTSQueryUserToken failed during lock (err=%d)", ctypes.get_last_error())
        return False
    try:
        system_root = os.environ.get("SystemRoot", r"C:\Windows")
        rundll32 = str(Path(system_root) / "System32" / "rundll32.exe")
        cmd_buf = ctypes.create_unicode_buffer(f'"{rundll32}" user32.dll,LockWorkStation')
        si = _STARTUPINFOW()
        si.cb = ctypes.sizeof(_STARTUPINFOW)
        si.lpDesktop = "winsta0\\default"
        pi = _PROCESS_INFORMATION()
        ok = bool(
            advapi32.CreateProcessAsUserW(
                user_token,
                rundll32,
                cmd_buf,
                None,
                None,
                False,
                _CREATE_NO_WINDOW,
                None,
                None,
                ctypes.byref(si),
                ctypes.byref(pi),
            )
        )
        if ok:
            if pi.hThread:
                kernel32.CloseHandle(pi.hThread)
            if pi.hProcess:
                kernel32.CloseHandle(pi.hProcess)
        return ok
    finally:
        kernel32.CloseHandle(user_token)


def logoff_active_console_session() -> bool:
    """Sign out the active Windows console user (works from both user session and SYSTEM)."""
    if sys.platform != "win32":
        return False
    if not is_system():
        subprocess.Popen(["shutdown", "/l"], creationflags=_CREATE_NO_WINDOW)
        return True
    sid = get_active_console_session_id()
    if sid is None or sid == 0:
        return False
    try:
        wtsapi32 = ctypes.WinDLL("wtsapi32", use_last_error=True)
        wtsapi32.WTSLogoffSession.argtypes = (wintypes.HANDLE, wintypes.DWORD, wintypes.BOOL)
        wtsapi32.WTSLogoffSession.restype = wintypes.BOOL
        return bool(wtsapi32.WTSLogoffSession(None, wintypes.DWORD(sid), False))
    except Exception as exc:
        logger.warning("WTSLogoffSession failed: %s", exc)
        return False


def run_elevated_flag(flag: str, wait: bool = True, timeout_ms: int = 15000) -> bool:
    """Run RemotePad with a single maintenance CLI flag (e.g. --install-autostart) elevated via UAC."""
    if sys.platform != "win32":
        return False

    if getattr(sys, "frozen", False):
        executable = sys.executable
        arg_list = [flag]
    else:
        executable = sys.executable
        main_script = str(Path(__file__).resolve().parent.parent / "main.py")
        arg_list = [main_script, flag]

    params = subprocess.list2cmdline(arg_list)
    try:
        shell32 = ctypes.WinDLL("shell32", use_last_error=True)
        shell32.ShellExecuteExW.argtypes = (ctypes.POINTER(_SHELLEXECUTEINFOW),)
        shell32.ShellExecuteExW.restype = wintypes.BOOL

        sei = _SHELLEXECUTEINFOW()
        sei.cbSize = ctypes.sizeof(_SHELLEXECUTEINFOW)
        sei.fMask = _SEE_MASK_NOCLOSEPROCESS
        sei.lpVerb = "runas"
        sei.lpFile = executable
        sei.lpParameters = params
        sei.nShow = 0  # SW_HIDE

        if not shell32.ShellExecuteExW(ctypes.byref(sei)):
            return False

        proc = int(sei.hProcess) if sei.hProcess else 0
        if proc:
            try:
                if wait:
                    code = wait_for_process_handle(proc, timeout_ms)
                    return code == 0
                return True
            finally:
                close_win32_handle(proc)
        return True
    except Exception as exc:
        logger.warning("Failed to run elevated flag %s: %s", flag, exc)
        return False


def relaunch_as_admin(extra_args: list[str] | None = None) -> bool:
    """Re-launch the current executable/script with UAC elevation ('runas').

    Returns True if the elevated child process was started, False if cancelled or failed.
    """
    if sys.platform != "win32":
        return False

    raw_args = list(sys.argv[1:])
    if extra_args:
        for flag in extra_args:
            if flag not in raw_args:
                raw_args.append(flag)

    if getattr(sys, "frozen", False):
        executable = sys.executable
        arg_list = raw_args
    else:
        executable = sys.executable
        main_script = str(Path(__file__).resolve().parent.parent / "main.py")
        arg_list = [main_script, *raw_args]

    params = subprocess.list2cmdline(arg_list)
    try:
        shell32 = ctypes.WinDLL("shell32", use_last_error=True)
        shell32.ShellExecuteW.argtypes = (
            wintypes.HWND,
            wintypes.LPCWSTR,
            wintypes.LPCWSTR,
            wintypes.LPCWSTR,
            wintypes.LPCWSTR,
            ctypes.c_int,
        )
        shell32.ShellExecuteW.restype = ctypes.c_void_p
        # SW_SHOWNORMAL = 1; return value > 32 indicates success
        res = shell32.ShellExecuteW(None, "runas", executable, params, None, 1)
        return bool(res and int(res) > 32)
    except Exception as exc:
        logger.warning("Failed to relaunch as Administrator: %s", exc)
        return False


def get_launch_parts(minimized: bool = True) -> tuple[str, str]:
    """Return (executable_path, arguments_string) to launch RemotePad at Windows login."""
    if getattr(sys, "frozen", False):
        return sys.executable, ("--minimized" if minimized else "")

    exe_path = Path(sys.executable)
    pythonw = exe_path.with_name("pythonw.exe")
    launcher = str(pythonw) if pythonw.exists() else str(exe_path)
    main_script = Path(__file__).resolve().parent.parent / "main.py"
    args = f'"{main_script}" --minimized' if minimized else f'"{main_script}"'
    return launcher, args


def get_service_launch_parts(flag: str = "--service-supervisor") -> tuple[str, str]:
    """Return (executable_path, arguments_string) to launch RemotePad's boot service or worker."""
    if getattr(sys, "frozen", False):
        return sys.executable, flag

    exe_path = Path(sys.executable)
    pythonw = exe_path.with_name("pythonw.exe")
    launcher = str(pythonw) if pythonw.exists() else str(exe_path)
    main_script = Path(__file__).resolve().parent.parent / "main.py"
    return launcher, f'"{main_script}" {flag}'


def get_launch_command(minimized: bool = True) -> str:
    """Return the combined command line string for HKCU\\...\\Run fallback."""
    exe, args = get_launch_parts(minimized=minimized)
    return f'"{exe}" {args}'.strip()


def _get_current_windows_user_id() -> str:
    """Return DOMAIN\\Username for Task Scheduler XML Principal/LogonTrigger."""
    domain = os.environ.get("USERDOMAIN", "").strip()
    user = os.environ.get("USERNAME", "").strip()
    if domain and user:
        return f"{domain}\\{user}"
    return user


def build_autostart_task_xml(executable: str, arguments: str, user_id: str) -> str:
    """Generate a Windows Task Scheduler XML that launches RemotePad elevated at user logon.

    Explicitly disables battery restrictions and execution time limits so laptops and
    long-running desktops keep RemotePad alive continuously with full Admin (UIPI) access.
    """
    exe_escaped = xml_escape(executable)
    args_xml = f"\n      <Arguments>{xml_escape(arguments)}</Arguments>" if arguments.strip() else ""
    user_xml = f"\n      <UserId>{xml_escape(user_id)}</UserId>" if user_id.strip() else ""
    trigger_user_xml = f"\n      <UserId>{xml_escape(user_id)}</UserId>" if user_id.strip() else ""

    return f"""<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>Starts RemotePad PC Agent tray icon automatically at logon and connects to the pre-login boot service.</Description>
  </RegistrationInfo>
  <Triggers>
    <LogonTrigger>
      <Enabled>true</Enabled>{trigger_user_xml}
    </LogonTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author">{user_xml}
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>HighestAvailable</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <AllowHardTerminate>true</AllowHardTerminate>
    <StartWhenAvailable>true</StartWhenAvailable>
    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
    <IdleSettings>
      <StopOnIdleEnd>false</StopOnIdleEnd>
      <RestartOnIdle>false</RestartOnIdle>
    </IdleSettings>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <Enabled>true</Enabled>
    <Hidden>false</Hidden>
    <RunOnlyIfIdle>false</RunOnlyIfIdle>
    <WakeToRun>false</WakeToRun>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>7</Priority>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>{exe_escaped}</Command>{args_xml}
    </Exec>
  </Actions>
</Task>
"""


def build_boot_service_task_xml(
    executable: str,
    arguments: str = "--service-supervisor",
) -> str:
    """Generate a Windows Task Scheduler XML that starts RemotePad at OS boot before login.

    Runs as NT AUTHORITY\\SYSTEM (S-1-5-18) on BootTrigger so the Session-0 supervisor
    immediately spawns the headless console-session agent before Windows is unlocked,
    enabling LAN/Relay connection and mouse/keyboard input on the Windows Lock Screen.
    """
    exe_escaped = xml_escape(executable)
    args_xml = f"\n      <Arguments>{xml_escape(arguments)}</Arguments>" if arguments.strip() else ""

    return f"""<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>Starts RemotePad PC Agent automatically at Windows boot (before login/unlock) as SYSTEM so remote control works on the Lock Screen, UAC prompts, and Desktop.</Description>
  </RegistrationInfo>
  <Triggers>
    <BootTrigger>
      <Enabled>true</Enabled>
    </BootTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author">
      <UserId>S-1-5-18</UserId>
      <RunLevel>HighestAvailable</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <AllowHardTerminate>true</AllowHardTerminate>
    <StartWhenAvailable>true</StartWhenAvailable>
    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
    <IdleSettings>
      <StopOnIdleEnd>false</StopOnIdleEnd>
      <RestartOnIdle>false</RestartOnIdle>
    </IdleSettings>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <Enabled>true</Enabled>
    <Hidden>false</Hidden>
    <RunOnlyIfIdle>false</RunOnlyIfIdle>
    <WakeToRun>false</WakeToRun>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>6</Priority>
    <RestartOnFailure>
      <Interval>PT1M</Interval>
      <Count>3</Count>
    </RestartOnFailure>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>{exe_escaped}</Command>{args_xml}
    </Exec>
  </Actions>
</Task>
"""


def _run_schtasks(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["schtasks.exe", *args],
        capture_output=True,
        text=True,
        creationflags=_CREATE_NO_WINDOW,
        timeout=10,
    )


def _is_scheduled_task_enabled(task_name: str = AUTOSTART_TASK_NAME) -> bool:
    if sys.platform != "win32":
        return False
    try:
        res = _run_schtasks(["/Query", "/TN", task_name])
        return res.returncode == 0
    except Exception:
        return False


def _register_task_from_xml(task_name: str, xml_content: str) -> bool:
    tmp_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-16",
            suffix=".xml",
            prefix="remotepad_task_",
            delete=False,
        ) as fh:
            fh.write(xml_content)
            tmp_path = Path(fh.name)

        res = _run_schtasks(["/Create", "/TN", task_name, "/XML", str(tmp_path), "/F"])
        if res.returncode == 0:
            logger.info("Registered Task Scheduler task '%s'", task_name)
            return True
        logger.warning(
            "schtasks /Create '%s' failed (rc=%s): %s %s",
            task_name,
            res.returncode,
            res.stdout.strip(),
            res.stderr.strip(),
        )
        return False
    except Exception as exc:
        logger.warning("Failed creating scheduled task '%s': %s", task_name, exc)
        return False
    finally:
        if tmp_path is not None:
            try:
                tmp_path.unlink(missing_ok=True)
            except OSError:
                pass


def _delete_scheduled_task(task_name: str) -> bool:
    try:
        res = _run_schtasks(["/Delete", "/TN", task_name, "/F"])
        # 0 = deleted, 1 = task does not exist (also fine)
        return res.returncode in (0, 1)
    except Exception as exc:
        logger.warning("Failed deleting scheduled task '%s': %s", task_name, exc)
        return False


def _set_scheduled_task_enabled(
    enabled: bool,
    task_name: str = AUTOSTART_TASK_NAME,
    executable: str | None = None,
    arguments: str | None = None,
) -> bool:
    if sys.platform != "win32":
        return False
    if not enabled:
        return _delete_scheduled_task(task_name)

    default_exe, default_args = get_launch_parts(minimized=True)
    exe = executable if executable is not None else default_exe
    args = arguments if arguments is not None else default_args
    xml_content = build_autostart_task_xml(exe, args, _get_current_windows_user_id())
    return _register_task_from_xml(task_name, xml_content)


def is_boot_service_task_enabled(task_name: str = BOOT_SERVICE_TASK_NAME) -> bool:
    """Return True if the pre-login SYSTEM boot service scheduled task is registered."""
    return _is_scheduled_task_enabled(task_name=task_name)


def _set_boot_service_task_enabled(
    enabled: bool,
    task_name: str = BOOT_SERVICE_TASK_NAME,
    executable: str | None = None,
    arguments: str | None = None,
) -> bool:
    if sys.platform != "win32":
        return False
    if not enabled:
        stop_boot_service_task(task_name=task_name)
        return _delete_scheduled_task(task_name)

    default_exe, default_args = get_service_launch_parts("--service-supervisor")
    exe = executable if executable is not None else default_exe
    args = arguments if arguments is not None else default_args
    xml_content = build_boot_service_task_xml(exe, args)
    return _register_task_from_xml(task_name, xml_content)


def create_global_mutex(mutex_name: str) -> int | None:
    """Acquire a machine-global Win32 mutex with SYSTEM+Admin ACL. Returns handle or None if held."""
    if sys.platform != "win32":
        return 1
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    advapi32 = ctypes.WinDLL("advapi32", use_last_error=True)
    kernel32.CreateMutexW.argtypes = (wintypes.LPVOID, wintypes.BOOL, wintypes.LPCWSTR)
    kernel32.CreateMutexW.restype = wintypes.HANDLE
    kernel32.CloseHandle.argtypes = (wintypes.HANDLE,)
    kernel32.CloseHandle.restype = wintypes.BOOL
    kernel32.LocalFree.argtypes = (wintypes.HLOCAL,)
    kernel32.LocalFree.restype = wintypes.HLOCAL
    advapi32.ConvertStringSecurityDescriptorToSecurityDescriptorW.argtypes = (
        wintypes.LPCWSTR,
        wintypes.DWORD,
        ctypes.POINTER(wintypes.LPVOID),
        ctypes.POINTER(wintypes.ULONG),
    )
    advapi32.ConvertStringSecurityDescriptorToSecurityDescriptorW.restype = wintypes.BOOL

    sd = wintypes.LPVOID()
    sd_size = wintypes.ULONG()
    sa_ptr = None
    sa = None
    if advapi32.ConvertStringSecurityDescriptorToSecurityDescriptorW(
        _SERVICE_CONTROL_SDDL,
        1,
        ctypes.byref(sd),
        ctypes.byref(sd_size),
    ) and sd.value:
        sa = _SECURITY_ATTRIBUTES(
            nLength=ctypes.sizeof(_SECURITY_ATTRIBUTES),
            lpSecurityDescriptor=sd.value,
            bInheritHandle=False,
        )
        sa_ptr = ctypes.byref(sa)

    try:
        ctypes.set_last_error(0)
        handle = kernel32.CreateMutexW(sa_ptr, False, mutex_name)
        last_err = ctypes.get_last_error()
        if not handle:
            if last_err == _ERROR_ACCESS_DENIED:
                return None
            return None
        if last_err == _ERROR_ALREADY_EXISTS:
            kernel32.CloseHandle(handle)
            return None
        return int(handle)
    finally:
        if sd.value:
            kernel32.LocalFree(sd.value)


def create_service_stop_event(event_name: str = SERVICE_STOP_EVENT_NAME) -> int | None:
    """Create or open the manual-reset global stop event used to stop the boot service cleanly."""
    if sys.platform != "win32":
        return None
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    advapi32 = ctypes.WinDLL("advapi32", use_last_error=True)
    kernel32.CreateEventW.argtypes = (wintypes.LPVOID, wintypes.BOOL, wintypes.BOOL, wintypes.LPCWSTR)
    kernel32.CreateEventW.restype = wintypes.HANDLE
    kernel32.ResetEvent.argtypes = (wintypes.HANDLE,)
    kernel32.ResetEvent.restype = wintypes.BOOL
    kernel32.LocalFree.argtypes = (wintypes.HLOCAL,)
    kernel32.LocalFree.restype = wintypes.HLOCAL
    advapi32.ConvertStringSecurityDescriptorToSecurityDescriptorW.argtypes = (
        wintypes.LPCWSTR,
        wintypes.DWORD,
        ctypes.POINTER(wintypes.LPVOID),
        ctypes.POINTER(wintypes.ULONG),
    )
    advapi32.ConvertStringSecurityDescriptorToSecurityDescriptorW.restype = wintypes.BOOL

    sd = wintypes.LPVOID()
    sd_size = wintypes.ULONG()
    sa_ptr = None
    sa = None
    if advapi32.ConvertStringSecurityDescriptorToSecurityDescriptorW(
        _SERVICE_CONTROL_SDDL,
        1,
        ctypes.byref(sd),
        ctypes.byref(sd_size),
    ) and sd.value:
        sa = _SECURITY_ATTRIBUTES(
            nLength=ctypes.sizeof(_SECURITY_ATTRIBUTES),
            lpSecurityDescriptor=sd.value,
            bInheritHandle=False,
        )
        sa_ptr = ctypes.byref(sa)

    try:
        handle = kernel32.CreateEventW(sa_ptr, True, False, event_name)
        if not handle:
            return None
        return int(handle)
    finally:
        if sd.value:
            kernel32.LocalFree(sd.value)


def reset_win32_event(handle: int | None) -> None:
    if sys.platform != "win32" or not handle:
        return
    try:
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.ResetEvent.argtypes = (wintypes.HANDLE,)
        kernel32.ResetEvent.restype = wintypes.BOOL
        kernel32.ResetEvent(wintypes.HANDLE(handle))
    except Exception:
        pass


def is_win32_event_signaled(handle: int | None, timeout_ms: int = 0) -> bool:
    if sys.platform != "win32" or not handle:
        return False
    try:
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.WaitForSingleObject.argtypes = (wintypes.HANDLE, wintypes.DWORD)
        kernel32.WaitForSingleObject.restype = wintypes.DWORD
        return kernel32.WaitForSingleObject(wintypes.HANDLE(handle), wintypes.DWORD(timeout_ms)) == _WAIT_OBJECT_0
    except Exception:
        return False


def signal_service_stop_event(event_name: str = SERVICE_STOP_EVENT_NAME) -> bool:
    """Signal the running boot supervisor & worker to shut down cleanly."""
    if sys.platform != "win32":
        return False
    try:
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.OpenEventW.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.LPCWSTR)
        kernel32.OpenEventW.restype = wintypes.HANDLE
        kernel32.SetEvent.argtypes = (wintypes.HANDLE,)
        kernel32.SetEvent.restype = wintypes.BOOL
        kernel32.CloseHandle.argtypes = (wintypes.HANDLE,)
        kernel32.CloseHandle.restype = wintypes.BOOL
        handle = kernel32.OpenEventW(_EVENT_MODIFY_STATE, False, event_name)
        if not handle:
            return False
        try:
            return bool(kernel32.SetEvent(handle))
        finally:
            kernel32.CloseHandle(handle)
    except Exception:
        return False


def start_boot_service_task(task_name: str = BOOT_SERVICE_TASK_NAME) -> bool:
    """Trigger the registered SYSTEM boot service task immediately."""
    if sys.platform != "win32":
        return False
    try:
        res = _run_schtasks(["/Run", "/TN", task_name])
        if res.returncode == 0:
            logger.info("Started boot service task '%s'", task_name)
            return True
        logger.debug("schtasks /Run '%s' returned %d: %s", task_name, res.returncode, res.stderr.strip())
        return False
    except Exception as exc:
        logger.debug("Failed starting boot service task '%s': %s", task_name, exc)
        return False


def stop_boot_service_task(task_name: str = BOOT_SERVICE_TASK_NAME) -> bool:
    """Stop the running SYSTEM boot service supervisor and worker."""
    if sys.platform != "win32":
        return False
    signaled = signal_service_stop_event()
    if signaled:
        time.sleep(0.4)
    try:
        res = _run_schtasks(["/End", "/TN", task_name])
        return res.returncode in (0, 1) or signaled
    except Exception:
        return signaled


def _is_registry_autostart_enabled(value_name: str = AUTOSTART_VALUE_NAME) -> bool:
    if sys.platform != "win32":
        return False
    try:
        import winreg

        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_REG_PATH, 0, winreg.KEY_READ) as key:
            val, _ = winreg.QueryValueEx(key, value_name)
            return bool(isinstance(val, str) and val.strip())
    except FileNotFoundError:
        return False
    except OSError as exc:
        logger.debug("Failed to read autostart registry value: %s", exc)
        return False


def _set_registry_autostart_enabled(
    enabled: bool,
    command: str | None = None,
    value_name: str = AUTOSTART_VALUE_NAME,
) -> bool:
    if sys.platform != "win32":
        return False
    try:
        import winreg

        with winreg.OpenKey(
            winreg.HKEY_CURRENT_USER,
            RUN_REG_PATH,
            0,
            winreg.KEY_SET_VALUE | winreg.KEY_READ,
        ) as key:
            if enabled:
                cmd = command if command is not None else get_launch_command(minimized=True)
                winreg.SetValueEx(key, value_name, 0, winreg.REG_SZ, cmd)
                logger.info("Enabled Windows Registry auto-startup: %s", cmd)
            else:
                try:
                    winreg.DeleteValue(key, value_name)
                    logger.info("Removed Windows Registry auto-startup value '%s'", value_name)
                except FileNotFoundError:
                    pass
        return True
    except OSError as exc:
        logger.warning("Failed to update Windows Registry auto-startup (%s): %s", enabled, exc)
        return False


def is_autostart_enabled(value_name: str = AUTOSTART_VALUE_NAME) -> bool:
    """Return True if RemotePad is configured for auto-startup (Boot Service, Logon Task, or HKCU Run)."""
    if sys.platform != "win32":
        return False
    if _is_registry_autostart_enabled(value_name=value_name):
        return True
    if value_name == AUTOSTART_VALUE_NAME:
        return (
            is_boot_service_task_enabled(task_name=BOOT_SERVICE_TASK_NAME)
            or _is_scheduled_task_enabled(task_name=AUTOSTART_TASK_NAME)
        )
    return False


def set_autostart_enabled(
    enabled: bool,
    command: str | None = None,
    value_name: str = AUTOSTART_VALUE_NAME,
) -> bool:
    """Enable or disable Windows auto-startup.

    When running as Administrator with the default name:
    - Syncs user config.json to %PROGRAMDATA%\\RemotePad so the pre-login boot service
      has the user's exact relay/sensitivity settings.
    - Registers 'RemotePadService' (BootTrigger as NT AUTHORITY\\SYSTEM) so RemotePad
      starts immediately when Windows turns on — before unlocking the Lock Screen.
    - Registers 'RemotePad' (LogonTrigger as the current user) so the System Tray UI
      attaches seamlessly after unlocking Windows.
    - Cleans up any legacy HKCU\\...\\Run entry.
    """
    if sys.platform != "win32":
        return False

    use_task_scheduler = (
        value_name == AUTOSTART_VALUE_NAME
        and command is None
        and is_admin()
    )

    if enabled:
        if use_task_scheduler:
            try:
                from config import sync_config_to_shared

                sync_config_to_shared()
            except Exception as exc:
                logger.debug("Config sync before autostart install failed: %s", exc)

            boot_ok = _set_boot_service_task_enabled(True, task_name=BOOT_SERVICE_TASK_NAME)
            tray_ok = _set_scheduled_task_enabled(True, task_name=AUTOSTART_TASK_NAME)
            if boot_ok or tray_ok:
                # Avoid duplicate startup if an older HKCU Run key existed
                _set_registry_autostart_enabled(False, value_name=value_name)
                return True
        return _set_registry_autostart_enabled(True, command=command, value_name=value_name)

    # Disabling: remove HKCU Run entry, Boot Service task, and Logon Tray task
    reg_ok = _set_registry_autostart_enabled(False, value_name=value_name)
    task_ok = True
    boot_ok = True
    if value_name == AUTOSTART_VALUE_NAME:
        if is_boot_service_task_enabled(BOOT_SERVICE_TASK_NAME):
            boot_ok = _set_boot_service_task_enabled(False, task_name=BOOT_SERVICE_TASK_NAME)
        if _is_scheduled_task_enabled(AUTOSTART_TASK_NAME):
            task_ok = _set_scheduled_task_enabled(False, task_name=AUTOSTART_TASK_NAME)
    return reg_ok and task_ok and boot_ok


def set_app_user_model_id(app_id: str = APP_USER_MODEL_ID) -> None:
    """Assign an explicit AppUserModelID so Windows groups taskbar/tray icons cleanly."""
    if sys.platform != "win32":
        return
    try:
        shell32 = ctypes.WinDLL("shell32", use_last_error=True)
        shell32.SetCurrentProcessExplicitAppUserModelID(app_id)
    except Exception:
        pass


def enable_high_dpi_awareness() -> None:
    """Enable system DPI awareness so the Tk window renders sharply on Windows."""
    if sys.platform != "win32":
        return
    try:
        shcore = ctypes.WinDLL("shcore", use_last_error=True)
        shcore.SetProcessDpiAwareness(1)
    except Exception:
        try:
            user32 = ctypes.WinDLL("user32", use_last_error=True)
            user32.SetProcessDPIAware()
        except Exception:
            pass


def open_folder_in_explorer(path: Path) -> None:
    """Open a directory in Windows Explorer."""
    try:
        path.mkdir(parents=True, exist_ok=True)
        if sys.platform == "win32":
            os.startfile(str(path))  # type: ignore[attr-defined]
    except Exception as exc:
        logger.warning("Failed to open folder %s: %s", path, exc)


class SingleInstanceGuard:
    """Ensures only one RemotePad GUI instance runs per user session and wakes it on re-launch."""

    def __init__(
        self,
        mutex_name: str = SINGLE_INSTANCE_MUTEX_NAME,
        event_name: str = SHOW_WINDOW_EVENT_NAME,
    ) -> None:
        self._mutex_name = mutex_name
        self._event_name = event_name
        self._mutex_handle: int | None = None
        self._event_handle: int | None = None
        self._stop_listener = threading.Event()
        self._listener_thread: threading.Thread | None = None
        self._kernel32 = (
            ctypes.WinDLL("kernel32", use_last_error=True)
            if sys.platform == "win32"
            else None
        )
        self._advapi32 = (
            ctypes.WinDLL("advapi32", use_last_error=True)
            if sys.platform == "win32"
            else None
        )
        if self._kernel32 is not None:
            self._configure_win32()

    def _configure_win32(self) -> None:
        k32 = self._kernel32
        assert k32 is not None
        k32.CreateMutexW.argtypes = (wintypes.LPVOID, wintypes.BOOL, wintypes.LPCWSTR)
        k32.CreateMutexW.restype = wintypes.HANDLE
        k32.CreateEventW.argtypes = (wintypes.LPVOID, wintypes.BOOL, wintypes.BOOL, wintypes.LPCWSTR)
        k32.CreateEventW.restype = wintypes.HANDLE
        k32.OpenEventW.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.LPCWSTR)
        k32.OpenEventW.restype = wintypes.HANDLE
        k32.SetEvent.argtypes = (wintypes.HANDLE,)
        k32.SetEvent.restype = wintypes.BOOL
        k32.WaitForSingleObject.argtypes = (wintypes.HANDLE, wintypes.DWORD)
        k32.WaitForSingleObject.restype = wintypes.DWORD
        k32.CloseHandle.argtypes = (wintypes.HANDLE,)
        k32.CloseHandle.restype = wintypes.BOOL
        k32.LocalFree.argtypes = (wintypes.HLOCAL,)
        k32.LocalFree.restype = wintypes.HLOCAL

        if self._advapi32 is not None:
            self._advapi32.ConvertStringSecurityDescriptorToSecurityDescriptorW.argtypes = (
                wintypes.LPCWSTR,
                wintypes.DWORD,
                ctypes.POINTER(wintypes.LPVOID),
                ctypes.POINTER(wintypes.ULONG),
            )
            self._advapi32.ConvertStringSecurityDescriptorToSecurityDescriptorW.restype = wintypes.BOOL

    def _build_ipc_security_attributes(self) -> tuple[_SECURITY_ATTRIBUTES | None, wintypes.LPVOID | None]:
        """Build SECURITY_ATTRIBUTES allowing Medium-IL launches to signal an Elevated primary."""
        if self._advapi32 is None:
            return None, None
        sd = wintypes.LPVOID()
        sd_size = wintypes.ULONG()
        ok = self._advapi32.ConvertStringSecurityDescriptorToSecurityDescriptorW(
            _SHARED_IPC_SDDL,
            1,  # SDDL_REVISION_1
            ctypes.byref(sd),
            ctypes.byref(sd_size),
        )
        if not ok or not sd.value:
            return None, None
        sa = _SECURITY_ATTRIBUTES(
            nLength=ctypes.sizeof(_SECURITY_ATTRIBUTES),
            lpSecurityDescriptor=sd.value,
            bInheritHandle=False,
        )
        return sa, sd

    def acquire(self) -> bool:
        """Acquire the single-instance mutex. Returns False if another instance is already active."""
        if self._kernel32 is None:
            return True

        sa, sd = self._build_ipc_security_attributes()
        sa_ptr = ctypes.byref(sa) if sa is not None else None
        try:
            ctypes.set_last_error(0)
            handle = self._kernel32.CreateMutexW(sa_ptr, False, self._mutex_name)
            last_err = ctypes.get_last_error()
            if not handle:
                if last_err == _ERROR_ACCESS_DENIED:
                    # Mutex already exists and is owned by an elevated instance
                    return False
                logger.warning("CreateMutexW failed (err=%s); continuing without lock", last_err)
                return True

            if last_err == _ERROR_ALREADY_EXISTS:
                self._kernel32.CloseHandle(handle)
                return False

            self._mutex_handle = int(handle)
            event_handle = self._kernel32.CreateEventW(sa_ptr, False, False, self._event_name)
            if event_handle:
                self._event_handle = int(event_handle)
            return True
        finally:
            if sd is not None and sd.value:
                self._kernel32.LocalFree(sd.value)

    def signal_existing_instance(self) -> bool:
        """Signal the already-running instance to show and focus its window."""
        if self._kernel32 is None:
            return False
        event_handle = self._kernel32.OpenEventW(_EVENT_MODIFY_STATE, False, self._event_name)
        if not event_handle:
            return False
        try:
            return bool(self._kernel32.SetEvent(event_handle))
        finally:
            self._kernel32.CloseHandle(event_handle)

    def start_activation_listener(self, on_activate: Callable[[], None]) -> None:
        """Listen in a daemon thread for activation signals from secondary launches."""
        if self._kernel32 is None or not self._event_handle:
            return
        if self._listener_thread is not None and self._listener_thread.is_alive():
            return

        self._stop_listener.clear()

        def _watch() -> None:
            assert self._kernel32 is not None
            while not self._stop_listener.is_set():
                handle = self._event_handle
                if not handle:
                    break
                res = self._kernel32.WaitForSingleObject(handle, 250)
                if res == _WAIT_OBJECT_0 and not self._stop_listener.is_set():
                    try:
                        on_activate()
                    except Exception as exc:
                        logger.debug("Activation callback error: %s", exc)

        self._listener_thread = threading.Thread(
            target=_watch,
            name="remotepad-activation-listener",
            daemon=True,
        )
        self._listener_thread.start()

    def close(self) -> None:
        """Stop the activation listener and release OS handles."""
        self._stop_listener.set()
        if self._event_handle and self._kernel32 is not None:
            try:
                self._kernel32.SetEvent(self._event_handle)
            except Exception:
                pass
        if self._listener_thread is not None and self._listener_thread.is_alive():
            self._listener_thread.join(timeout=1.0)
        self._listener_thread = None

        if self._kernel32 is not None:
            if self._event_handle:
                self._kernel32.CloseHandle(self._event_handle)
                self._event_handle = None
            if self._mutex_handle:
                self._kernel32.CloseHandle(self._mutex_handle)
                self._mutex_handle = None
