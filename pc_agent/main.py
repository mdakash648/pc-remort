"""Entry point for RemotePad PC Agent (System Tray GUI + CLI Console Mode)."""

from __future__ import annotations

import argparse
import asyncio
import ctypes
import logging
import sys
from ctypes import wintypes
from logging.handlers import RotatingFileHandler
from pathlib import Path

from config import AgentConfig, get_config_dir
from core.relay_client import RelayClient
from core.runtime import AgentRuntime
from core.server import LocalWebSocketServer, get_local_ipv4_addresses
from core.windows_integration import (
    SingleInstanceGuard,
    get_resource_path,
    is_admin,
    relaunch_as_admin,
    set_app_user_model_id,
    set_autostart_enabled,
)
from features.discovery import LanDiscoveryService
from features.file_transfer import FileTransferServer
from features.gestures import GestureHandler
from features.mic_receiver import MicReceiverService
from input.keyboard import KeyboardController
from input.mouse import MouseController


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="RemotePad PC Agent")
    parser.add_argument(
        "--minimized",
        action="store_true",
        help="Start silently in the system tray without opening the settings window",
    )
    parser.add_argument(
        "--console",
        action="store_true",
        help="Run in console/headless mode instead of the system tray GUI",
    )
    parser.add_argument(
        "--no-elevate",
        action="store_true",
        help="Do not automatically request Administrator elevation on startup",
    )
    parser.add_argument(
        "--install-autostart",
        action="store_true",
        help="Register Windows auto-startup task (elevated when run as admin) and exit",
    )
    parser.add_argument(
        "--remove-autostart",
        action="store_true",
        help="Remove Windows auto-startup task/registry entry and exit",
    )
    parser.add_argument("--host", type=str, default=None, help="Bind host (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=None, help="Bind port (default: 8765)")
    parser.add_argument("--file-port", type=int, default=None, help="File transfer HTTP port (default: 8767)")
    parser.add_argument("--mic-port", type=int, default=None, help="Microphone UDP port (default: 8768)")
    parser.add_argument(
        "--mic-device",
        type=str,
        default=None,
        help="Playback device name substring for mic routing (default: auto-detect VB-Cable)",
    )
    parser.add_argument(
        "--no-discovery",
        action="store_true",
        help="Disable LAN auto-discovery UDP responder/beacon",
    )
    parser.add_argument(
        "--no-files",
        action="store_true",
        help="Disable HTTP file transfer server",
    )
    parser.add_argument(
        "--no-mic",
        action="store_true",
        help="Disable phone-to-PC microphone UDP receiver",
    )
    parser.add_argument(
        "--relay-url",
        type=str,
        default=None,
        help="VPS relay WebSocket URL (e.g. ws://182.255.88.108:8770)",
    )
    parser.add_argument(
        "--relay-id",
        type=str,
        default=None,
        help="PC ID for VPS relay registration",
    )
    parser.add_argument(
        "--relay-token",
        type=str,
        default=None,
        help="Shared secret token for VPS relay registration",
    )
    parser.add_argument(
        "--no-relay",
        action="store_true",
        help="Disable VPS internet relay client",
    )
    parser.add_argument(
        "--sensitivity",
        type=float,
        default=None,
        help="Mouse sensitivity multiplier (default: 1.0)",
    )
    parser.add_argument(
        "--natural-scroll",
        action="store_true",
        default=None,
        help="Invert scroll direction",
    )
    parser.add_argument(
        "--debug",
        action="store_true",
        help="Enable verbose debug logging",
    )
    return parser


def _configure_logging(debug: bool) -> None:
    level = logging.DEBUG if debug else logging.INFO
    formatter = logging.Formatter("%(asctime)s [%(levelname)s] %(name)s: %(message)s")
    handlers: list[logging.Handler] = []

    if sys.stderr is not None:
        stream_handler = logging.StreamHandler(sys.stderr)
        stream_handler.setFormatter(formatter)
        handlers.append(stream_handler)

    try:
        log_dir = get_config_dir()
        log_dir.mkdir(parents=True, exist_ok=True)
        file_handler = RotatingFileHandler(
            log_dir / "remotepad.log",
            maxBytes=1_048_576,
            backupCount=2,
            encoding="utf-8",
        )
        file_handler.setFormatter(formatter)
        handlers.append(file_handler)
    except Exception:
        pass

    logging.basicConfig(level=level, handlers=handlers, force=True)


async def _run(config: AgentConfig) -> None:
    mouse = MouseController(
        sensitivity=config.mouse_sensitivity,
        acceleration=config.mouse_acceleration,
        scroll_sensitivity=config.scroll_sensitivity,
        natural_scroll=config.natural_scroll,
    )
    keyboard = KeyboardController()
    gestures = GestureHandler(keyboard=keyboard, gesture_map=config.gesture_map)

    file_server: FileTransferServer | None = None
    if config.file_transfer_enabled:
        received_dir = Path(config.received_files_dir) if config.received_files_dir.strip() else None
        file_server = FileTransferServer(
            host=config.host,
            port=config.file_port,
            received_dir=received_dir,
        )
        await file_server.start()

    mic_server: MicReceiverService | None = None
    if config.mic_enabled:
        mic_server = MicReceiverService(
            host=config.host,
            port=config.mic_port,
            preferred_device=config.mic_output_device,
        )
        await mic_server.start()

    server = LocalWebSocketServer(
        config=config,
        mouse=mouse,
        keyboard=keyboard,
        gestures=gestures,
        file_port=file_server.port if file_server is not None else None,
        mic_port=mic_server.port if mic_server is not None else None,
        mic_device_provider=mic_server.refresh_device if mic_server is not None else None,
    )
    await server.start()

    discovery: LanDiscoveryService | None = None
    if config.discovery_enabled:
        discovery = LanDiscoveryService(
            pc_name=server.pc_name,
            ws_port=server.port,
            discovery_port=config.discovery_port,
            ip_provider=get_local_ipv4_addresses,
        )
        await discovery.start()

    relay_client: RelayClient | None = None
    if config.relay_enabled and config.relay_url.strip():
        relay_client = RelayClient(
            server=server,
            relay_url=config.relay_url,
            device_id=config.relay_device_id,
            token=config.relay_token,
            p2p_enabled=config.relay_p2p_enabled,
            relay_udp_port=config.relay_udp_port,
            file_server=file_server,
        )
        await relay_client.start()

    ips = get_local_ipv4_addresses()
    print("=" * 56)
    print(f"  RemotePad PC Agent ({server.pc_name})")
    print("=" * 56)
    print(f"  Listening port : {server.port}")
    if file_server is not None:
        print(f"  File transfer  : port {file_server.port} ({file_server.received_dir})")
    if mic_server is not None:
        mic_status = mic_server.device_name or "Install VB-Cable (CABLE Input) for PC Mic"
        print(f"  Phone Mic      : UDP port {mic_server.port} -> {mic_status}")
    if discovery is not None:
        print(f"  Auto-discovery : UDP port {discovery.bound_port} (LAN)")
    if relay_client is not None:
        p2p_mode = f" + Direct P2P UDP:{config.relay_udp_port}" if config.relay_p2p_enabled else ""
        print(f"  Internet Relay : {config.relay_url} (id={config.relay_device_id}{p2p_mode})")
    for ip in ips:
        print(f"  Connect URL    : ws://{ip}:{server.port}")
    print("  Press Ctrl+C to stop.")
    print("=" * 56, flush=True)

    try:
        await asyncio.Future()  # Run until cancelled
    finally:
        if relay_client is not None:
            await relay_client.stop()
        if discovery is not None:
            await discovery.stop()
        await server.stop()
        if mic_server is not None:
            await mic_server.stop()
        if file_server is not None:
            await file_server.stop()


def _apply_windows_console_icon() -> None:
    """Set custom window title and taskbar/titlebar icon from assets/app_icon.ico on Windows."""
    if sys.platform != "win32":
        return
    try:
        set_app_user_model_id()
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        user32 = ctypes.WinDLL("user32", use_last_error=True)

        kernel32.SetConsoleTitleW("RemotePad PC Agent")

        icon_path = get_resource_path("assets/app_icon.ico")
        if not icon_path.exists():
            return

        kernel32.GetConsoleWindow.restype = wintypes.HWND
        hwnd = kernel32.GetConsoleWindow()
        if not hwnd:
            return

        user32.LoadImageW.argtypes = (
            wintypes.HINSTANCE,
            wintypes.LPCWSTR,
            wintypes.UINT,
            ctypes.c_int,
            ctypes.c_int,
            wintypes.UINT,
        )
        user32.LoadImageW.restype = wintypes.HANDLE
        user32.SendMessageW.argtypes = (
            wintypes.HWND,
            wintypes.UINT,
            wintypes.WPARAM,
            wintypes.LPARAM,
        )
        user32.SendMessageW.restype = ctypes.c_ssize_t

        image_icon = 1
        lr_loadfromfile = 0x0010
        wm_seticon = 0x0080

        hicon_small = user32.LoadImageW(None, str(icon_path), image_icon, 16, 16, lr_loadfromfile)
        hicon_big = user32.LoadImageW(None, str(icon_path), image_icon, 48, 48, lr_loadfromfile)
        if hicon_small:
            user32.SendMessageW(hwnd, wm_seticon, 0, hicon_small)
        if hicon_big:
            user32.SendMessageW(hwnd, wm_seticon, 1, hicon_big)
    except Exception:
        pass


def _show_fatal_error_dialog(title: str, message: str) -> None:
    try:
        import tkinter as tk
        from tkinter import messagebox

        root = tk.Tk()
        root.withdraw()
        messagebox.showerror(title, message)
        root.destroy()
    except Exception:
        if sys.stderr is not None:
            print(f"{title}: {message}", file=sys.stderr)


def _run_tray_gui(config: AgentConfig, start_minimized: bool, no_elevate: bool = False) -> int:
    from ui.tray_app import RemotePadTrayApp

    guard = SingleInstanceGuard()
    if not guard.acquire():
        # Another RemotePad instance is already running in the tray; wake its window with zero UAC!
        if not start_minimized:
            guard.signal_existing_instance()
        return 0

    # When launching the packaged .exe without Administrator rights (e.g. from Windows Search
    # after quitting), elevate so mouse & keyboard work over Task Manager and other Admin apps.
    if (
        sys.platform == "win32"
        and getattr(sys, "frozen", False)
        and not no_elevate
        and not is_admin()
    ):
        guard.close()
        if relaunch_as_admin(extra_args=["--no-elevate"]):
            return 0
        # User declined UAC — continue gracefully in Standard mode
        if not guard.acquire():
            return 0

    runtime = AgentRuntime(config)
    try:
        runtime.start(timeout=10.0)
    except Exception as exc:
        logging.getLogger(__name__).exception("Failed to start RemotePad PC Agent")
        guard.close()
        _show_fatal_error_dialog(
            "RemotePad PC Agent — Startup Error",
            f"Could not start RemotePad services:\n\n{exc}\n\n"
            "Make sure another program is not using port 8765.",
        )
        return 1

    app = RemotePadTrayApp(runtime=runtime, start_minimized=start_minimized)
    guard.start_activation_listener(app.request_show_window)
    try:
        app.run()
    finally:
        guard.close()
        runtime.stop(timeout=5.0)

    if app.restart_as_admin_requested:
        relaunch_as_admin(extra_args=["--no-elevate"])
    return 0


def main() -> int:
    args = _build_arg_parser().parse_args()
    _configure_logging(debug=args.debug)

    if args.install_autostart:
        return 0 if set_autostart_enabled(True) else 1
    if args.remove_autostart:
        return 0 if set_autostart_enabled(False) else 1

    config = AgentConfig.load()
    if args.host is not None:
        config.host = args.host
    if args.port is not None:
        config.port = args.port
    if args.file_port is not None:
        config.file_port = args.file_port
    if args.mic_port is not None:
        config.mic_port = args.mic_port
    if args.mic_device is not None:
        config.mic_output_device = args.mic_device
    if args.no_discovery:
        config.discovery_enabled = False
    if args.no_files:
        config.file_transfer_enabled = False
    if args.no_mic:
        config.mic_enabled = False
    if args.relay_url is not None:
        config.relay_url = args.relay_url
    if args.relay_id is not None:
        config.relay_device_id = args.relay_id
    if args.relay_token is not None:
        config.relay_token = args.relay_token
    if args.no_relay:
        config.relay_enabled = False
    if args.sensitivity is not None:
        config.mouse_sensitivity = args.sensitivity
    if args.natural_scroll is not None:
        config.natural_scroll = args.natural_scroll

    if args.console:
        _apply_windows_console_icon()
        try:
            asyncio.run(_run(config))
        except KeyboardInterrupt:
            print("\nStopped.")
        return 0

    return _run_tray_gui(
        config=config,
        start_minimized=args.minimized,
        no_elevate=args.no_elevate,
    )


if __name__ == "__main__":
    sys.exit(main())
