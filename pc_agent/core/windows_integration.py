"""Windows OS integration helpers: elevation, Task Scheduler auto-startup, and single-instance guard."""

from __future__ import annotations

import ctypes
import logging
import os
import subprocess
import sys
import tempfile
import threading
from ctypes import wintypes
from pathlib import Path
from typing import Callable
from xml.sax.saxutils import escape as xml_escape

logger = logging.getLogger(__name__)

APP_USER_MODEL_ID = "RemotePad.PCAgent"
AUTOSTART_VALUE_NAME = "RemotePad"
AUTOSTART_TASK_NAME = "RemotePad"
RUN_REG_PATH = r"Software\Microsoft\Windows\CurrentVersion\Run"

SINGLE_INSTANCE_MUTEX_NAME = "Local\\RemotePad_PC_Agent_SingleInstance_v1"
SHOW_WINDOW_EVENT_NAME = "Local\\RemotePad_PC_Agent_ShowWindow_v1"

_ERROR_ACCESS_DENIED = 5
_ERROR_ALREADY_EXISTS = 183
_EVENT_MODIFY_STATE = 0x0002
_WAIT_OBJECT_0 = 0x00000000
_CREATE_NO_WINDOW = 0x08000000

# Allows Administrators full access and Authenticated Users (including Medium-IL launches in the
# same Local\ session) to signal the ShowWindow event without needing a UAC prompt.
_SHARED_IPC_SDDL = "D:(A;;GA;;;BA)(A;;0x00100002;;;AU)S:(ML;;NW;;;ME)"


class _SECURITY_ATTRIBUTES(ctypes.Structure):
    _fields_ = [
        ("nLength", wintypes.DWORD),
        ("lpSecurityDescriptor", wintypes.LPVOID),
        ("bInheritHandle", wintypes.BOOL),
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
    <Description>Starts RemotePad PC Agent automatically at logon with Administrator privileges so mouse and keyboard work over Task Manager and elevated apps.</Description>
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


def _set_scheduled_task_enabled(
    enabled: bool,
    task_name: str = AUTOSTART_TASK_NAME,
    executable: str | None = None,
    arguments: str | None = None,
) -> bool:
    if sys.platform != "win32":
        return False
    if not enabled:
        try:
            res = _run_schtasks(["/Delete", "/TN", task_name, "/F"])
            # 0 = deleted, 1 = task does not exist (also fine)
            return res.returncode in (0, 1)
        except Exception as exc:
            logger.warning("Failed deleting scheduled task '%s': %s", task_name, exc)
            return False

    default_exe, default_args = get_launch_parts(minimized=True)
    exe = executable if executable is not None else default_exe
    args = arguments if arguments is not None else default_args
    xml_content = build_autostart_task_xml(exe, args, _get_current_windows_user_id())

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
            logger.info("Registered elevated Task Scheduler auto-startup task '%s'", task_name)
            return True
        logger.warning(
            "schtasks /Create failed (rc=%s): %s %s",
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
    """Return True if RemotePad is configured for auto-startup (Task Scheduler or HKCU Run)."""
    if sys.platform != "win32":
        return False
    if _is_registry_autostart_enabled(value_name=value_name):
        return True
    if value_name == AUTOSTART_VALUE_NAME:
        return _is_scheduled_task_enabled(task_name=AUTOSTART_TASK_NAME)
    return False


def set_autostart_enabled(
    enabled: bool,
    command: str | None = None,
    value_name: str = AUTOSTART_VALUE_NAME,
) -> bool:
    """Enable or disable Windows auto-startup.

    When running as Administrator with the default name, registers an elevated Windows
    Task Scheduler logon task (so RemotePad starts with full Admin/UIPI support and zero
    UAC prompts on boot) and cleans up any legacy HKCU Run entry.
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
            if _set_scheduled_task_enabled(True, task_name=AUTOSTART_TASK_NAME):
                # Avoid duplicate startup if an older HKCU Run key existed
                _set_registry_autostart_enabled(False, value_name=value_name)
                return True
        return _set_registry_autostart_enabled(True, command=command, value_name=value_name)

    # Disabling: remove both HKCU Run entry and (if default name) Task Scheduler entry
    reg_ok = _set_registry_autostart_enabled(False, value_name=value_name)
    task_ok = True
    if value_name == AUTOSTART_VALUE_NAME and _is_scheduled_task_enabled(AUTOSTART_TASK_NAME):
        task_ok = _set_scheduled_task_enabled(False, task_name=AUTOSTART_TASK_NAME)
    return reg_ok and task_ok


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
