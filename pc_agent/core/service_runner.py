"""Pre-login Windows Boot Supervisor (Session 0) and Console-Session SYSTEM Worker."""

from __future__ import annotations

import logging
import signal
import sys
import threading
import time
from pathlib import Path

from config import AgentConfig
from core.runtime import AgentRuntime
from core.service_ipc import ServiceIpcServer
from core.windows_integration import (
    EXIT_CODE_USER_QUIT,
    SERVICE_STOP_EVENT_NAME,
    SERVICE_SUPERVISOR_MUTEX_NAME,
    SERVICE_WORKER_MUTEX_NAME,
    close_win32_handle,
    create_global_mutex,
    create_service_stop_event,
    ensure_thread_on_input_desktop,
    get_active_console_session_id,
    is_system,
    is_win32_event_signaled,
    reset_win32_event,
    spawn_process_in_session,
    terminate_process_handle,
    wait_for_process_handle,
)

logger = logging.getLogger(__name__)


def _get_worker_executable_and_args() -> tuple[str, list[str]]:
    if getattr(sys, "frozen", False):
        return sys.executable, ["--agent-worker"]
    exe_path = Path(sys.executable)
    pythonw = exe_path.with_name("pythonw.exe")
    launcher = str(pythonw) if pythonw.exists() else str(exe_path)
    main_script = str(Path(__file__).resolve().parent.parent / "main.py")
    return launcher, [main_script, "--agent-worker"]


def run_service_supervisor() -> int:
    """Session-0 SYSTEM watchdog that keeps an active-console-session agent worker running from boot.

    Starts at Windows BootTrigger BEFORE any user unlocks Windows, detects the active
    console session (e.g. Session 1 showing the Lock Screen), and launches
    `RemotePad.exe --agent-worker` inside that session as NT AUTHORITY\\SYSTEM.
    """
    if sys.platform != "win32":
        logger.error("--service-supervisor is only supported on Windows.")
        return 1

    mutex = create_global_mutex(SERVICE_SUPERVISOR_MUTEX_NAME)
    if not mutex:
        logger.info("Another RemotePad service supervisor is already running; exiting.")
        return 0

    stop_event = create_service_stop_event(SERVICE_STOP_EVENT_NAME)
    reset_win32_event(stop_event)

    executable, worker_args = _get_worker_executable_and_args()
    logger.info(
        "RemotePad boot supervisor started (system=%s, worker=%s %s)",
        is_system(),
        executable,
        " ".join(worker_args),
    )

    worker_handle: int | None = None
    worker_pid: int | None = None
    worker_session_id: int | None = None
    waiting_logged = False

    try:
        while not is_win32_event_signaled(stop_event):
            active_sid = get_active_console_session_id()
            if active_sid is None or active_sid == 0:
                if not waiting_logged:
                    logger.info("Waiting for interactive console session (Winlogon)...")
                    waiting_logged = True
                time.sleep(0.5)
                continue
            waiting_logged = False

            # Spawn worker in active console session (try Default desktop first, then Winlogon)
            spawned = spawn_process_in_session(
                active_sid,
                executable,
                worker_args,
                desktop="winsta0\\default",
            )
            if spawned is None:
                spawned = spawn_process_in_session(
                    active_sid,
                    executable,
                    worker_args,
                    desktop="winsta0\\winlogon",
                )
            if spawned is None:
                logger.warning(
                    "Could not spawn agent worker in console session %d yet; retrying in 2s...",
                    active_sid,
                )
                time.sleep(2.0)
                continue

            worker_handle, worker_pid = spawned
            worker_session_id = active_sid
            logger.info(
                "Spawned RemotePad agent worker pid=%d in console session %d",
                worker_pid,
                worker_session_id,
            )

            # Monitor worker lifetime and active console session transitions
            user_requested_stop = False
            while True:
                if is_win32_event_signaled(stop_event):
                    logger.info("Service stop event signaled; stopping worker pid=%d", worker_pid)
                    # Give worker a moment to exit cleanly upon seeing the same stop event
                    exited = wait_for_process_handle(worker_handle, 4000)
                    if exited is None:
                        terminate_process_handle(worker_handle, 0)
                    close_win32_handle(worker_handle)
                    worker_handle = None
                    return 0

                exit_code = wait_for_process_handle(worker_handle, 500)
                if exit_code is not None:
                    close_win32_handle(worker_handle)
                    worker_handle = None
                    if exit_code == EXIT_CODE_USER_QUIT:
                        logger.info("Agent worker exited by explicit user quit; stopping supervisor.")
                        user_requested_stop = True
                        break
                    logger.warning(
                        "Agent worker pid=%d exited unexpectedly (code=%d); restarting in 1.5s...",
                        worker_pid,
                        exit_code,
                    )
                    time.sleep(1.5)
                    break

                current_sid = get_active_console_session_id()
                if (
                    current_sid is not None
                    and current_sid != 0
                    and current_sid != worker_session_id
                ):
                    logger.info(
                        "Active console session switched (%d -> %d); moving worker...",
                        worker_session_id,
                        current_sid,
                    )
                    terminate_process_handle(worker_handle, 0)
                    wait_for_process_handle(worker_handle, 2000)
                    close_win32_handle(worker_handle)
                    worker_handle = None
                    time.sleep(0.5)
                    break

            if user_requested_stop:
                return 0
    finally:
        if worker_handle is not None:
            terminate_process_handle(worker_handle, 0)
            close_win32_handle(worker_handle)
        close_win32_handle(stop_event)
        close_win32_handle(mutex)
        logger.info("RemotePad boot supervisor exited.")

    return 0


def run_agent_worker(config: AgentConfig) -> int:
    """Headless console-session SYSTEM agent worker (works on Lock Screen, UAC, and Desktop)."""
    mutex = create_global_mutex(SERVICE_WORKER_MUTEX_NAME)
    if not mutex:
        logger.info("Another RemotePad agent worker is already running; exiting.")
        return 0

    stop_event = create_service_stop_event(SERVICE_STOP_EVENT_NAME)
    user_quit_event = threading.Event()
    local_stop_event = threading.Event()

    def _on_signal(_signum: int, _frame: object) -> None:
        local_stop_event.set()

    for sig_name in ("SIGINT", "SIGTERM"):
        sig = getattr(signal, sig_name, None)
        if sig is not None:
            try:
                signal.signal(sig, _on_signal)
            except Exception:
                pass

    # Attach initial desktop awareness (Winlogon when locked, Default when unlocked)
    desktop_name = ensure_thread_on_input_desktop(force=True)
    logger.info(
        "RemotePad agent worker starting (system=%s, active_desktop=%s)",
        is_system(),
        desktop_name or "unknown",
    )

    runtime = AgentRuntime(config)
    started = False
    last_exc: Exception | None = None
    for attempt in range(1, 6):
        try:
            runtime.start(timeout=10.0)
            started = True
            break
        except Exception as exc:
            last_exc = exc
            logger.warning(
                "Agent worker failed to start services (attempt %d/5): %s",
                attempt,
                exc,
            )
            if is_win32_event_signaled(stop_event) or local_stop_event.is_set():
                break
            time.sleep(1.5)

    if not started:
        logger.error("Agent worker could not start services: %s", last_exc)
        close_win32_handle(stop_event)
        close_win32_handle(mutex)
        return 1

    ipc_server = ServiceIpcServer(
        runtime=runtime,
        on_shutdown_requested=user_quit_event.set,
    )
    try:
        ipc_server.start(timeout=5.0)
    except Exception as exc:
        logger.exception("Failed to start Service IPC server: %s", exc)
        runtime.stop(timeout=5.0)
        close_win32_handle(stop_event)
        close_win32_handle(mutex)
        return 1

    exit_code = 0
    try:
        while True:
            if user_quit_event.is_set():
                logger.info("Agent worker stopping due to explicit user quit.")
                exit_code = EXIT_CODE_USER_QUIT
                break
            if local_stop_event.is_set() or is_win32_event_signaled(stop_event):
                logger.info("Agent worker stopping due to service stop signal.")
                exit_code = 0
                break
            time.sleep(0.25)
    except KeyboardInterrupt:
        exit_code = 0
    finally:
        ipc_server.stop(timeout=3.0)
        runtime.stop(timeout=5.0)
        close_win32_handle(stop_event)
        close_win32_handle(mutex)
        logger.info("RemotePad agent worker stopped (exit_code=%d).", exit_code)

    return exit_code
