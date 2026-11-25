"""System tray icon and modern settings window for RemotePad PC Agent."""

from __future__ import annotations

import ctypes
import logging
import sys
import threading
import tkinter as tk
from pathlib import Path
from typing import Any

import pystray
from PIL import Image, ImageDraw, ImageTk

from config import (
    AgentConfig,
    get_config_dir,
    get_shared_config_dir,
    get_user_config_dir,
    sync_config_to_shared,
)
from core.runtime import AgentRuntime, AgentStatus, generate_relay_token, validate_relay_settings
from core.windows_integration import (
    enable_high_dpi_awareness,
    get_resource_path,
    is_admin,
    is_autostart_enabled,
    is_system,
    open_folder_in_explorer,
    relaunch_as_admin,
    run_elevated_flag,
    set_app_user_model_id,
    set_autostart_enabled,
)

logger = logging.getLogger(__name__)

# Color palette (modern slate dark theme)
_BG_ROOT = "#0F172A"
_BG_CARD = "#1E293B"
_BG_INPUT = "#0B1220"
_BG_INPUT_DISABLED = "#151F32"
_BORDER = "#334155"
_BORDER_FOCUS = "#38BDF8"
_TEXT_PRIMARY = "#F8FAFC"
_TEXT_SECONDARY = "#94A3B8"
_TEXT_MUTED = "#64748B"
_ACCENT = "#2563EB"
_ACCENT_HOVER = "#1D4ED8"
_BTN_SECONDARY = "#334155"
_BTN_SECONDARY_HOVER = "#475569"
_DANGER = "#B91C1C"
_DANGER_HOVER = "#991B1B"
_SUCCESS = "#22C55E"
_WARN = "#F59E0B"
_ERROR = "#EF4444"
_INFO = "#38BDF8"


def _apply_windows_dark_titlebar(window: tk.Tk) -> None:
    """Enable native Windows 10/11 dark titlebar on the Tk window if supported."""
    if sys.platform != "win32":
        return
    try:
        window.update_idletasks()
        hwnd = ctypes.windll.user32.GetParent(window.winfo_id())
        if not hwnd:
            hwnd = window.winfo_id()
        value = ctypes.c_int(1)
        dwmapi = ctypes.windll.dwmapi
        # 20 = DWMWA_USE_IMMERSIVE_DARK_MODE (Win11 / Win10 20H1+), 19 = older Win10
        res = dwmapi.DwmSetWindowAttribute(hwnd, 20, ctypes.byref(value), ctypes.sizeof(value))
        if res != 0:
            dwmapi.DwmSetWindowAttribute(hwnd, 19, ctypes.byref(value), ctypes.sizeof(value))
    except Exception:
        pass


def _load_tray_image() -> Image.Image:
    """Load the app icon for the system tray (falling back to a crisp drawn icon)."""
    for rel in ("assets/app_icon.png", "assets/app_icon.ico"):
        path = get_resource_path(rel)
        if path.exists():
            try:
                img = Image.open(path).convert("RGBA")
                return img.resize((64, 64), Image.Resampling.LANCZOS)
            except Exception as exc:
                logger.debug("Failed loading tray icon %s: %s", path, exc)

    # Fallback generated icon
    img = Image.new("RGBA", (64, 64), (15, 23, 42, 255))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle((4, 4, 60, 60), radius=14, fill=(37, 99, 235, 255))
    draw.rounded_rectangle((16, 16, 48, 40), radius=4, outline=(248, 250, 252, 255), width=3)
    draw.line((24, 48, 40, 48), fill=(56, 189, 248, 255), width=4)
    return img


class RemotePadTrayApp:
    """System tray controller + interactive Settings/Status window for RemotePad PC Agent."""

    _POLL_INTERVAL_MS = 1500

    def __init__(self, runtime: Any, start_minimized: bool = False) -> None:
        self.runtime = runtime
        self._start_minimized = start_minimized
        self._is_admin = is_admin()
        self._is_service_backed = bool(getattr(runtime, "is_service_backed", False))
        self._running_as_system = bool(getattr(runtime, "running_as_system", False))
        self._has_elevated_control = self._is_admin or self._running_as_system
        self.restart_as_admin_requested = False
        self.explicit_quit_requested = False

        self._root: tk.Tk | None = None
        self._tray_icon: pystray.Icon | None = None
        self._shutting_down = False
        self._dirty = False
        self._syncing_form = False
        self._token_hidden = False
        self._feedback_clear_job: str | None = None
        self._header_photo: ImageTk.PhotoImage | None = None
        self._window_photo: ImageTk.PhotoImage | None = None

        # Tk variables (initialized in _build_window)
        self._var_relay_enabled: tk.BooleanVar
        self._var_relay_url: tk.StringVar
        self._var_device_id: tk.StringVar
        self._var_token: tk.StringVar
        self._var_autostart: tk.BooleanVar

        # Widget refs for live updates
        self._status_dot: tk.Label
        self._status_label: tk.Label
        self._subtitle_label: tk.Label
        self._lan_value_label: tk.Label
        self._mic_value_label: tk.Label
        self._feedback_label: tk.Label
        self._url_entry: tk.Entry
        self._id_entry: tk.Entry
        self._token_entry: tk.Entry
        self._toggle_token_btn: tk.Button
        self._save_btn: tk.Button
        self._devices_badge_lbl: tk.Label
        self._devices_container: tk.Frame
        self._blocked_container: tk.Frame
        self._last_devices_snapshot: tuple[dict[str, Any], ...] | None = None
        self._last_blocked_snapshot: tuple[dict[str, str], ...] | None = None
        self._last_connected_count: int = -1
        self._last_lan_url: str = ""
        self._last_tooltip: str = ""
        self._last_tray_status_line: str = ""

    def run(self) -> None:
        """Start the Tk UI loop on the main thread and run the tray icon alongside it."""
        enable_high_dpi_awareness()
        set_app_user_model_id()

        self._build_window()
        self._start_tray_icon()

        assert self._root is not None
        if self._start_minimized:
            self._root.withdraw()
        else:
            self._show_window_on_main_thread()

        self._poll_status()
        try:
            self._root.mainloop()
        finally:
            self._cleanup()

    def request_show_window(self) -> None:
        """Thread-safe request to show and focus the settings window."""
        root = self._root
        if root is not None and not self._shutting_down:
            try:
                root.after(0, self._show_window_on_main_thread)
            except Exception:
                pass

    def request_quit(self) -> None:
        """Thread-safe request to shut down the app and agent cleanly."""
        root = self._root
        if root is not None and not self._shutting_down:
            try:
                root.after(0, self._quit_on_main_thread)
            except Exception:
                pass

    # ------------------------------------------------------------------
    # Window construction
    # ------------------------------------------------------------------

    def _build_window(self) -> None:
        root = tk.Tk()
        self._root = root
        root.title("RemotePad PC Agent")
        root.configure(bg=_BG_ROOT)
        root.resizable(False, False)
        root.protocol("WM_DELETE_WINDOW", self._hide_window)

        # Window icon (.ico + photo fallback)
        ico_path = get_resource_path("assets/app_icon.ico")
        if ico_path.exists():
            try:
                root.iconbitmap(default=str(ico_path))
            except Exception as exc:
                logger.debug("iconbitmap failed: %s", exc)

        tray_img = _load_tray_image()
        try:
            self._window_photo = ImageTk.PhotoImage(tray_img.resize((32, 32), Image.Resampling.LANCZOS))
            root.iconphoto(True, self._window_photo)
            self._header_photo = ImageTk.PhotoImage(tray_img.resize((36, 36), Image.Resampling.LANCZOS))
        except Exception as exc:
            logger.debug("iconphoto setup failed: %s", exc)

        # Variables
        status = self.runtime.get_status()
        self._var_relay_enabled = tk.BooleanVar(value=status.relay_enabled)
        self._var_relay_url = tk.StringVar(value=status.relay_url)
        self._var_device_id = tk.StringVar(value=status.relay_device_id)
        self._var_token = tk.StringVar(value=status.relay_token)
        self._var_autostart = tk.BooleanVar(value=is_autostart_enabled())

        for var in (self._var_relay_url, self._var_device_id, self._var_token):
            var.trace_add("write", self._on_form_edited)

        # Main container
        outer = tk.Frame(root, bg=_BG_ROOT, padx=20, pady=18)
        outer.pack(fill="both", expand=True)

        # --- Header ---
        header = tk.Frame(outer, bg=_BG_ROOT)
        header.pack(fill="x", pady=(0, 14))

        left_hdr = tk.Frame(header, bg=_BG_ROOT)
        left_hdr.pack(side="left", fill="x", expand=True)

        if self._header_photo is not None:
            icon_lbl = tk.Label(left_hdr, image=self._header_photo, bg=_BG_ROOT)
            icon_lbl.pack(side="left", padx=(0, 10))

        title_col = tk.Frame(left_hdr, bg=_BG_ROOT)
        title_col.pack(side="left", anchor="w")

        tk.Label(
            title_col,
            text="RemotePad",
            bg=_BG_ROOT,
            fg=_TEXT_PRIMARY,
            font=("Segoe UI", 15, "bold"),
        ).pack(anchor="w")

        priv_tag = self._privilege_tag()
        self._subtitle_label = tk.Label(
            title_col,
            text=f"PC Agent  •  {status.pc_name}  •  {priv_tag}",
            bg=_BG_ROOT,
            fg=_TEXT_SECONDARY,
            font=("Segoe UI", 9),
        )
        self._subtitle_label.pack(anchor="w")

        # Status badge on right
        badge = tk.Frame(header, bg=_BG_CARD, highlightbackground=_BORDER, highlightthickness=1, padx=10, pady=5)
        badge.pack(side="right", anchor="center")

        self._status_dot = tk.Label(badge, text="●", bg=_BG_CARD, fg=_WARN, font=("Segoe UI", 9))
        self._status_dot.pack(side="left", padx=(0, 5))

        self._status_label = tk.Label(
            badge,
            text="Connecting...",
            bg=_BG_CARD,
            fg=_TEXT_PRIMARY,
            font=("Segoe UI", 9, "bold"),
        )
        self._status_label.pack(side="left")

        # --- Card 1: VPS Relay Settings ---
        relay_card = tk.Frame(
            outer,
            bg=_BG_CARD,
            highlightbackground=_BORDER,
            highlightthickness=1,
            padx=16,
            pady=14,
        )
        relay_card.pack(fill="x", pady=(0, 12))

        relay_hdr = tk.Frame(relay_card, bg=_BG_CARD)
        relay_hdr.pack(fill="x", pady=(0, 10))

        tk.Label(
            relay_hdr,
            text="VPS Relay Settings",
            bg=_BG_CARD,
            fg=_TEXT_PRIMARY,
            font=("Segoe UI", 11, "bold"),
        ).pack(side="left")

        tk.Checkbutton(
            relay_hdr,
            text="Enable Relay",
            variable=self._var_relay_enabled,
            command=self._on_relay_toggle,
            bg=_BG_CARD,
            fg=_TEXT_PRIMARY,
            activebackground=_BG_CARD,
            activeforeground=_TEXT_PRIMARY,
            selectcolor=_BG_INPUT,
            font=("Segoe UI", 9),
            cursor="hand2",
            highlightthickness=0,
            bd=0,
        ).pack(side="right")

        # VPS Relay URL
        self._url_entry = self._build_input_row(
            parent=relay_card,
            label="VPS Relay URL",
            variable=self._var_relay_url,
            copy_label="VPS Relay URL",
            hint="Example: ws://your-vps-ip:8770  or  wss://relay.yourdomain.com",
        )

        # PC ID
        self._id_entry = self._build_input_row(
            parent=relay_card,
            label="PC ID",
            variable=self._var_device_id,
            copy_label="PC ID",
            hint="3-64 characters: letters, numbers, '.', '_', or '-'",
        )

        # Token (visible by default with Hide/Show, Copy, New)
        tk.Label(
            relay_card,
            text="Token",
            bg=_BG_CARD,
            fg=_TEXT_SECONDARY,
            font=("Segoe UI", 9, "bold"),
        ).pack(anchor="w", pady=(8, 3))

        tok_row = tk.Frame(relay_card, bg=_BG_CARD)
        tok_row.pack(fill="x")

        self._token_entry = self._make_styled_entry(tok_row, self._var_token)
        self._token_entry.pack(side="left", fill="x", expand=True, ipady=6)

        self._toggle_token_btn = self._make_small_button(tok_row, "Hide", self._toggle_token_visibility)
        self._toggle_token_btn.pack(side="left", padx=(6, 0))

        self._make_small_button(
            tok_row,
            "Copy",
            lambda: self._copy_to_clipboard(self._var_token.get(), "Token"),
        ).pack(side="left", padx=(6, 0))

        self._make_small_button(tok_row, "New", self._generate_new_token).pack(side="left", padx=(6, 0))

        tk.Label(
            relay_card,
            text="8-128 characters. Must match the Token entered in the Android app.",
            bg=_BG_CARD,
            fg=_TEXT_MUTED,
            font=("Segoe UI", 8),
        ).pack(anchor="w", pady=(3, 0))

        # Inline feedback / error line
        self._feedback_label = tk.Label(
            relay_card,
            text="",
            bg=_BG_CARD,
            fg=_INFO,
            font=("Segoe UI", 9),
            wraplength=430,
            justify="left",
        )
        self._feedback_label.pack(anchor="w", fill="x", pady=(10, 0))

        # Action buttons row
        btn_row = tk.Frame(relay_card, bg=_BG_CARD)
        btn_row.pack(fill="x", pady=(10, 0))

        self._save_btn = tk.Button(
            btn_row,
            text="Save & Reconnect",
            command=self._on_save_clicked,
            bg=_ACCENT,
            fg="#FFFFFF",
            activebackground=_ACCENT_HOVER,
            activeforeground="#FFFFFF",
            relief="flat",
            bd=0,
            padx=16,
            pady=7,
            font=("Segoe UI", 9, "bold"),
            cursor="hand2",
        )
        self._save_btn.pack(side="left")
        self._bind_hover(self._save_btn, _ACCENT, _ACCENT_HOVER)

        reconnect_btn = tk.Button(
            btn_row,
            text="Reconnect Now",
            command=self._on_reconnect_clicked,
            bg=_BTN_SECONDARY,
            fg=_TEXT_PRIMARY,
            activebackground=_BTN_SECONDARY_HOVER,
            activeforeground=_TEXT_PRIMARY,
            relief="flat",
            bd=0,
            padx=12,
            pady=7,
            font=("Segoe UI", 9),
            cursor="hand2",
        )
        reconnect_btn.pack(side="left", padx=(8, 0))
        self._bind_hover(reconnect_btn, _BTN_SECONDARY, _BTN_SECONDARY_HOVER)

        # --- Card 2: Remote Connected Devices ---
        devices_card = tk.Frame(
            outer,
            bg=_BG_CARD,
            highlightbackground=_BORDER,
            highlightthickness=1,
            padx=16,
            pady=12,
        )
        devices_card.pack(fill="x", pady=(0, 12))

        dev_hdr = tk.Frame(devices_card, bg=_BG_CARD)
        dev_hdr.pack(fill="x", pady=(0, 8))

        tk.Label(
            dev_hdr,
            text="Remote Connected Devices",
            bg=_BG_CARD,
            fg=_TEXT_PRIMARY,
            font=("Segoe UI", 11, "bold"),
        ).pack(side="left")

        self._devices_badge_lbl = tk.Label(
            dev_hdr,
            text="0 Connected",
            bg=_BG_CARD,
            fg=_TEXT_MUTED,
            font=("Segoe UI", 9, "bold"),
        )
        self._devices_badge_lbl.pack(side="right")

        self._devices_container = tk.Frame(devices_card, bg=_BG_CARD)
        self._devices_container.pack(fill="x", pady=(0, 4))

        self._blocked_container = tk.Frame(devices_card, bg=_BG_CARD)
        self._blocked_container.pack(fill="x")

        # --- Card 3: Local Network & Startup ---
        info_card = tk.Frame(
            outer,
            bg=_BG_CARD,
            highlightbackground=_BORDER,
            highlightthickness=1,
            padx=16,
            pady=12,
        )
        info_card.pack(fill="x", pady=(0, 12))

        lan_row = tk.Frame(info_card, bg=_BG_CARD)
        lan_row.pack(fill="x", pady=(0, 6))

        tk.Label(
            lan_row,
            text="Local LAN:",
            bg=_BG_CARD,
            fg=_TEXT_SECONDARY,
            font=("Segoe UI", 9, "bold"),
            width=11,
            anchor="w",
        ).pack(side="left")

        self._lan_value_label = tk.Label(
            lan_row,
            text="...",
            bg=_BG_CARD,
            fg=_TEXT_PRIMARY,
            font=("Consolas", 9),
            anchor="w",
        )
        self._lan_value_label.pack(side="left", fill="x", expand=True)

        self._make_small_button(
            lan_row,
            "Copy",
            lambda: self._copy_to_clipboard(self._last_lan_url, "LAN URL"),
        ).pack(side="right")

        mic_row = tk.Frame(info_card, bg=_BG_CARD)
        mic_row.pack(fill="x", pady=(0, 8))

        tk.Label(
            mic_row,
            text="Phone Mic:",
            bg=_BG_CARD,
            fg=_TEXT_SECONDARY,
            font=("Segoe UI", 9, "bold"),
            width=11,
            anchor="w",
        ).pack(side="left")

        self._mic_value_label = tk.Label(
            mic_row,
            text="...",
            bg=_BG_CARD,
            fg=_TEXT_SECONDARY,
            font=("Segoe UI", 9),
            anchor="w",
        )
        self._mic_value_label.pack(side="left", fill="x", expand=True)

        tk.Frame(info_card, bg=_BORDER, height=1).pack(fill="x", pady=(2, 8))

        tk.Checkbutton(
            info_card,
            text="Start automatically at Windows boot (works on Lock Screen)",
            variable=self._var_autostart,
            command=self._on_autostart_toggle,
            bg=_BG_CARD,
            fg=_TEXT_PRIMARY,
            activebackground=_BG_CARD,
            activeforeground=_TEXT_PRIMARY,
            selectcolor=_BG_INPUT,
            font=("Segoe UI", 9),
            cursor="hand2",
            highlightthickness=0,
            bd=0,
        ).pack(anchor="w")

        if not self._has_elevated_control:
            admin_row = tk.Frame(info_card, bg=_BG_CARD)
            admin_row.pack(fill="x", pady=(8, 0))

            tk.Label(
                admin_row,
                text="Running without Admin — Task Manager & Admin apps won't respond.",
                bg=_BG_CARD,
                fg=_WARN,
                font=("Segoe UI", 8),
                anchor="w",
            ).pack(side="left", fill="x", expand=True)

            self._make_small_button(
                admin_row,
                "Restart as Admin",
                self._on_restart_as_admin_clicked,
            ).pack(side="right", padx=(6, 0))

        # --- Footer ---
        footer = tk.Frame(outer, bg=_BG_ROOT)
        footer.pack(fill="x")

        logs_btn = tk.Button(
            footer,
            text="Open Logs Folder",
            command=lambda: open_folder_in_explorer(self._active_logs_dir()),
            bg=_BG_ROOT,
            fg=_TEXT_SECONDARY,
            activebackground=_BG_ROOT,
            activeforeground=_TEXT_PRIMARY,
            relief="flat",
            bd=0,
            font=("Segoe UI", 9, "underline"),
            cursor="hand2",
        )
        logs_btn.pack(side="left")

        quit_btn = tk.Button(
            footer,
            text="Quit Agent",
            command=self._on_user_quit_clicked,
            bg=_DANGER,
            fg="#FFFFFF",
            activebackground=_DANGER_HOVER,
            activeforeground="#FFFFFF",
            relief="flat",
            bd=0,
            padx=12,
            pady=5,
            font=("Segoe UI", 9),
            cursor="hand2",
        )
        quit_btn.pack(side="right")
        self._bind_hover(quit_btn, _DANGER, _DANGER_HOVER)

        hide_btn = tk.Button(
            footer,
            text="Hide to Tray",
            command=self._hide_window,
            bg=_BTN_SECONDARY,
            fg=_TEXT_PRIMARY,
            activebackground=_BTN_SECONDARY_HOVER,
            activeforeground=_TEXT_PRIMARY,
            relief="flat",
            bd=0,
            padx=12,
            pady=5,
            font=("Segoe UI", 9),
            cursor="hand2",
        )
        hide_btn.pack(side="right", padx=(0, 8))
        self._bind_hover(hide_btn, _BTN_SECONDARY, _BTN_SECONDARY_HOVER)

        # Pressing Enter in any input saves & reconnects
        for entry in (self._url_entry, self._id_entry, self._token_entry):
            entry.bind("<Return>", lambda _e: self._on_save_clicked())

        self._update_entry_enabled_states()
        self._apply_status_to_widgets(status)

        # Center window on screen with exact computed dimensions
        root.update_idletasks()
        width = max(root.winfo_reqwidth(), 500)
        height = root.winfo_reqheight()
        screen_w = root.winfo_screenwidth()
        screen_h = root.winfo_screenheight()
        x = max((screen_w - width) // 2, 0)
        y = max((screen_h - height) // 2 - 20, 0)
        root.geometry(f"{width}x{height}+{x}+{y}")
        _apply_windows_dark_titlebar(root)

    def _build_input_row(
        self,
        *,
        parent: tk.Widget,
        label: str,
        variable: tk.StringVar,
        copy_label: str,
        hint: str,
    ) -> tk.Entry:
        tk.Label(
            parent,
            text=label,
            bg=_BG_CARD,
            fg=_TEXT_SECONDARY,
            font=("Segoe UI", 9, "bold"),
        ).pack(anchor="w", pady=(6, 3))

        row = tk.Frame(parent, bg=_BG_CARD)
        row.pack(fill="x")

        entry = self._make_styled_entry(row, variable)
        entry.pack(side="left", fill="x", expand=True, ipady=6)

        self._make_small_button(
            row,
            "Copy",
            lambda: self._copy_to_clipboard(variable.get(), copy_label),
        ).pack(side="left", padx=(6, 0))

        tk.Label(
            parent,
            text=hint,
            bg=_BG_CARD,
            fg=_TEXT_MUTED,
            font=("Segoe UI", 8),
        ).pack(anchor="w", pady=(3, 0))
        return entry

    def _make_styled_entry(self, parent: tk.Widget, variable: tk.StringVar) -> tk.Entry:
        entry = tk.Entry(
            parent,
            textvariable=variable,
            bg=_BG_INPUT,
            fg=_TEXT_PRIMARY,
            disabledbackground=_BG_INPUT_DISABLED,
            disabledforeground=_TEXT_MUTED,
            insertbackground=_TEXT_PRIMARY,
            relief="flat",
            highlightbackground=_BORDER,
            highlightcolor=_BORDER_FOCUS,
            highlightthickness=1,
            font=("Consolas", 10),
        )
        return entry

    def _make_small_button(self, parent: tk.Widget, text: str, command: Any) -> tk.Button:
        btn = tk.Button(
            parent,
            text=text,
            command=command,
            bg=_BTN_SECONDARY,
            fg=_TEXT_PRIMARY,
            activebackground=_BTN_SECONDARY_HOVER,
            activeforeground=_TEXT_PRIMARY,
            relief="flat",
            bd=0,
            padx=9,
            pady=5,
            font=("Segoe UI", 8, "bold"),
            cursor="hand2",
        )
        self._bind_hover(btn, _BTN_SECONDARY, _BTN_SECONDARY_HOVER)
        return btn

    def _make_danger_button(self, parent: tk.Widget, text: str, command: Any) -> tk.Button:
        btn = tk.Button(
            parent,
            text=text,
            command=command,
            bg=_DANGER,
            fg="#FFFFFF",
            activebackground=_DANGER_HOVER,
            activeforeground="#FFFFFF",
            relief="flat",
            bd=0,
            padx=8,
            pady=4,
            font=("Segoe UI", 8, "bold"),
            cursor="hand2",
        )
        self._bind_hover(btn, _DANGER, _DANGER_HOVER)
        return btn

    def _on_disconnect_device(self, device_id: str) -> None:
        def _worker() -> None:
            self.runtime.disconnect_device(device_id)
            self._post_to_ui(self._poll_status)
        threading.Thread(target=_worker, daemon=True).start()

    def _on_block_device(self, device_id: str) -> None:
        def _worker() -> None:
            self.runtime.block_device(device_id)
            self._post_to_ui(self._poll_status)
        threading.Thread(target=_worker, daemon=True).start()

    def _on_unblock_device(self, identifier: str) -> None:
        def _worker() -> None:
            self.runtime.unblock_device(identifier)
            self._post_to_ui(self._poll_status)
        threading.Thread(target=_worker, daemon=True).start()

    def _rebuild_devices_ui(
        self,
        devs: tuple[dict[str, Any], ...],
        blocked: tuple[dict[str, str], ...],
    ) -> None:
        for w in self._devices_container.winfo_children():
            w.destroy()
        for w in self._blocked_container.winfo_children():
            w.destroy()

        if not devs:
            tk.Label(
                self._devices_container,
                text="No remote devices currently connected.",
                bg=_BG_CARD,
                fg=_TEXT_MUTED,
                font=("Segoe UI", 9),
            ).pack(anchor="w", pady=(2, 4))
        else:
            for d in devs:
                dev_id = d["id"]
                row = tk.Frame(
                    self._devices_container,
                    bg=_BG_INPUT,
                    highlightbackground=_BORDER,
                    highlightthickness=1,
                    padx=10,
                    pady=6,
                )
                row.pack(fill="x", pady=(0, 6))

                info_col = tk.Frame(row, bg=_BG_INPUT)
                info_col.pack(side="left", fill="x", expand=True)

                name_row = tk.Frame(info_col, bg=_BG_INPUT)
                name_row.pack(anchor="w")

                tk.Label(
                    name_row,
                    text="●",
                    bg=_BG_INPUT,
                    fg=_SUCCESS,
                    font=("Segoe UI", 8),
                ).pack(side="left", padx=(0, 6))

                tk.Label(
                    name_row,
                    text=d.get("name", "Unknown Phone"),
                    bg=_BG_INPUT,
                    fg=_TEXT_PRIMARY,
                    font=("Segoe UI", 9, "bold"),
                ).pack(side="left")

                sub_text = f"{d.get('transport', 'Remote')}  •  IP: {d.get('ip') or 'N/A'}"
                tk.Label(
                    info_col,
                    text=sub_text,
                    bg=_BG_INPUT,
                    fg=_TEXT_SECONDARY,
                    font=("Segoe UI", 8),
                ).pack(anchor="w", padx=(14, 0))

                btn_col = tk.Frame(row, bg=_BG_INPUT)
                btn_col.pack(side="right")

                self._make_danger_button(
                    btn_col,
                    "Disconnect",
                    lambda did=dev_id: self._on_disconnect_device(did),
                ).pack(side="left", padx=(0, 6))

                self._make_danger_button(
                    btn_col,
                    "Block",
                    lambda did=dev_id: self._on_block_device(did),
                ).pack(side="left")

        if blocked:
            tk.Frame(self._blocked_container, bg=_BORDER, height=1).pack(fill="x", pady=(6, 6))
            tk.Label(
                self._blocked_container,
                text=f"Blocked Devices ({len(blocked)}):",
                bg=_BG_CARD,
                fg=_WARN,
                font=("Segoe UI", 8, "bold"),
            ).pack(anchor="w", pady=(0, 4))

            for b in blocked:
                b_ident = b.get("id") or b.get("name") or b.get("ip") or "Device"
                b_row = tk.Frame(self._blocked_container, bg=_BG_CARD)
                b_row.pack(fill="x", pady=(0, 3))
                tk.Label(
                    b_row,
                    text=f"🚫 {b.get('name') or b_ident} ({b.get('ip') or 'blocked'})",
                    bg=_BG_CARD,
                    fg=_TEXT_MUTED,
                    font=("Segoe UI", 8),
                ).pack(side="left")

                self._make_small_button(
                    b_row,
                    "Unblock",
                    lambda ident=b_ident: self._on_unblock_device(ident),
                ).pack(side="right")

    @staticmethod
    def _bind_hover(widget: tk.Button, normal_bg: str, hover_bg: str) -> None:
        widget.bind("<Enter>", lambda _e: widget.configure(bg=hover_bg))
        widget.bind("<Leave>", lambda _e: widget.configure(bg=normal_bg))

    # ------------------------------------------------------------------
    # System tray integration (pystray)
    # ------------------------------------------------------------------

    def _devices_menu(self) -> list[Any]:
        status = self.runtime.get_status()
        devs = status.connected_devices
        if not devs:
            return [pystray.MenuItem("No devices connected", lambda _icon, _item: None, enabled=False)]
        items: list[Any] = []
        for d in devs:
            dev_id = str(d["id"])
            dev_name = str(d["name"])
            transport = str(d["transport"])

            def _make_disconnect(target_id: str) -> Callable[[Any, Any], None]:
                return lambda _icon, _item: self._on_disconnect_device(target_id)

            def _make_block(target_id: str) -> Callable[[Any, Any], None]:
                return lambda _icon, _item: self._on_block_device(target_id)

            dev_sub = pystray.Menu(
                pystray.MenuItem(f"Type: {transport}", lambda _icon, _item: None, enabled=False),
                pystray.MenuItem(f"IP: {d.get('ip') or 'N/A'}", lambda _icon, _item: None, enabled=False),
                pystray.Menu.SEPARATOR,
                pystray.MenuItem("Disconnect", _make_disconnect(dev_id)),
                pystray.MenuItem("Block Device", _make_block(dev_id)),
            )
            items.append(pystray.MenuItem(f"{dev_name} ({transport})", dev_sub))
        return items

    def _start_tray_icon(self) -> None:
        items: list[Any] = [
            pystray.MenuItem(
                "Open RemotePad",
                self._tray_on_open,
                default=True,
            ),
            pystray.MenuItem(
                lambda _item: self._tray_status_text(),
                lambda _icon, _item: None,
                enabled=False,
            ),
            pystray.MenuItem(
                lambda _item: f"Connected Devices ({self.runtime.get_status().connected_devices_count})",
                pystray.Menu(lambda: self._devices_menu()),
            ),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Reconnect Relay", self._tray_on_reconnect),
            pystray.MenuItem(
                "Start with Windows",
                self._tray_on_toggle_autostart,
                checked=lambda _item: is_autostart_enabled(),
            ),
        ]
        if not self._has_elevated_control:
            items.append(pystray.MenuItem("Restart as Admin", self._tray_on_restart_as_admin))
        items.extend(
            [
                pystray.MenuItem(
                    "Open Logs Folder",
                    lambda _icon, _item: open_folder_in_explorer(self._active_logs_dir()),
                ),
                pystray.Menu.SEPARATOR,
                pystray.MenuItem("Quit RemotePad", self._tray_on_quit),
            ]
        )
        menu = pystray.Menu(*items)

        self._tray_icon = pystray.Icon(
            name="RemotePad",
            icon=_load_tray_image(),
            title="RemotePad PC Agent",
            menu=menu,
        )
        self._tray_icon.run_detached()

    def _tray_status_text(self) -> str:
        status = self.runtime.get_status()
        if status.relay_state == "unconfigured":
            return "Relay: PC ID & Token not set"
        if status.relay_state == "p2p_active":
            return f"Relay: Direct P2P ({status.relay_device_id})"
        if status.relay_state == "connected":
            suffix = " • Phone Online" if status.relay_peer_online else ""
            dev_label = f" ({status.relay_device_id})" if status.relay_device_id else ""
            return f"Relay: Connected{dev_label}{suffix}"
        if status.relay_state == "disabled":
            return "Relay: Disabled (LAN only)"
        if status.relay_state == "error":
            return "Relay: Reconnecting..."
        return "Relay: Connecting..."

    def _tray_on_open(self, _icon: pystray.Icon, _item: pystray.MenuItem) -> None:
        self.request_show_window()

    def _tray_on_reconnect(self, _icon: pystray.Icon, _item: pystray.MenuItem) -> None:
        threading.Thread(target=self._reconnect_background, daemon=True).start()

    def _privilege_tag(self) -> str:
        if self._running_as_system:
            return "System Service"
        if self._is_admin:
            return "Admin"
        return "Standard"

    def _active_logs_dir(self) -> Path:
        return get_shared_config_dir() if self._is_service_backed else get_config_dir()

    def _apply_autostart_change(self, desired: bool) -> bool:
        if is_admin() or sys.platform != "win32":
            return set_autostart_enabled(desired)
        return run_elevated_flag("--install-autostart" if desired else "--remove-autostart")

    def _tray_on_toggle_autostart(self, _icon: pystray.Icon, _item: pystray.MenuItem) -> None:
        new_val = not is_autostart_enabled()
        self._apply_autostart_change(new_val)
        root = self._root
        if root is not None and not self._shutting_down:
            root.after(0, lambda: self._var_autostart.set(is_autostart_enabled()))

    def _tray_on_quit(self, _icon: pystray.Icon, _item: pystray.MenuItem) -> None:
        self._post_to_ui(self._on_user_quit_clicked)

    def _on_user_quit_clicked(self) -> None:
        self.explicit_quit_requested = True
        self._quit_on_main_thread()

    def _tray_on_restart_as_admin(self, _icon: pystray.Icon, _item: pystray.MenuItem) -> None:
        self._post_to_ui(self._on_restart_as_admin_clicked)

    def _on_restart_as_admin_clicked(self) -> None:
        self.restart_as_admin_requested = True
        self._quit_on_main_thread()

    # ------------------------------------------------------------------
    # Window actions & callbacks
    # ------------------------------------------------------------------

    def _show_window_on_main_thread(self) -> None:
        root = self._root
        if root is None or self._shutting_down:
            return
        if not self._dirty:
            self._sync_form_from_status(self.runtime.get_status())
        self._var_autostart.set(is_autostart_enabled())
        root.deiconify()
        root.lift()
        root.attributes("-topmost", True)
        root.after(120, lambda: root.attributes("-topmost", False) if not self._shutting_down else None)
        root.focus_force()

    def _hide_window(self) -> None:
        if self._root is not None:
            self._root.withdraw()

    def _on_form_edited(self, *_args: Any) -> None:
        if not self._syncing_form:
            self._dirty = True

    def _on_relay_toggle(self) -> None:
        self._dirty = True
        self._update_entry_enabled_states()

    def _update_entry_enabled_states(self) -> None:
        state = "normal" if self._var_relay_enabled.get() else "disabled"
        self._url_entry.configure(state=state)
        self._id_entry.configure(state=state)
        self._token_entry.configure(state=state)

    def _toggle_token_visibility(self) -> None:
        self._token_hidden = not self._token_hidden
        self._token_entry.configure(show="•" if self._token_hidden else "")
        self._toggle_token_btn.configure(text="Show" if self._token_hidden else "Hide")

    def _generate_new_token(self) -> None:
        self._var_token.set(generate_relay_token())
        if self._token_hidden:
            self._toggle_token_visibility()
        self._set_feedback("Generated a new token. Click 'Save & Reconnect' to apply it.", _INFO)

    def _copy_to_clipboard(self, value: str, label: str) -> None:
        text = value.strip()
        if not text or self._root is None:
            return
        self._root.clipboard_clear()
        self._root.clipboard_append(text)
        self._root.update_idletasks()
        self._set_feedback(f"Copied {label} to clipboard.", _SUCCESS, auto_clear_ms=2500)

    def _on_autostart_toggle(self) -> None:
        desired = bool(self._var_autostart.get())
        ok = self._apply_autostart_change(desired)
        actual = is_autostart_enabled()
        self._var_autostart.set(actual)
        if ok:
            msg = (
                "Boot auto-startup enabled (works on Lock Screen)."
                if actual
                else "Auto-startup disabled."
            )
            self._set_feedback(msg, _SUCCESS, auto_clear_ms=3000)
        else:
            self._set_feedback("Could not update Windows Startup setting.", _ERROR)
        if self._tray_icon is not None:
            try:
                self._tray_icon.update_menu()
            except Exception:
                pass

    def _on_save_clicked(self) -> None:
        relay_enabled = bool(self._var_relay_enabled.get())
        relay_url = self._var_relay_url.get().strip()
        device_id = self._var_device_id.get().strip()
        token = self._var_token.get().strip()

        err = validate_relay_settings(relay_enabled, relay_url, device_id, token)
        if err is not None:
            self._set_feedback(err, _ERROR)
            return

        self._save_btn.configure(state="disabled", text="Saving...")
        self._set_feedback("Saving settings and reconnecting...", _INFO)

        def _worker() -> None:
            try:
                self.runtime.update_relay_settings(
                    relay_enabled=relay_enabled,
                    relay_url=relay_url,
                    relay_device_id=device_id,
                    relay_token=token,
                    persist=True,
                )
                if not is_system():
                    try:
                        user_cfg_path = get_user_config_dir() / "config.json"
                        user_cfg = AgentConfig.load(user_cfg_path)
                        user_cfg.relay_enabled = relay_enabled
                        user_cfg.relay_url = relay_url
                        user_cfg.relay_device_id = device_id
                        user_cfg.relay_token = token
                        user_cfg.save()
                        sync_config_to_shared()
                    except Exception as sync_exc:
                        logger.debug("Failed saving user-level config mirror: %s", sync_exc)
            except Exception as exc:
                self._post_to_ui(lambda: self._on_save_done(str(exc)))
                return
            self._post_to_ui(lambda: self._on_save_done(None))

        threading.Thread(target=_worker, name="remotepad-save-relay", daemon=True).start()

    def _on_save_done(self, error: str | None) -> None:
        self._save_btn.configure(state="normal", text="Save & Reconnect")
        if error is not None:
            self._set_feedback(f"Failed to save: {error}", _ERROR)
            return
        self._dirty = False
        status = self.runtime.get_status()
        self._sync_form_from_status(status)
        if status.relay_enabled:
            self._set_feedback("Saved! Connecting to VPS relay with updated settings...", _SUCCESS, auto_clear_ms=4000)
        else:
            self._set_feedback("Saved! VPS relay disabled (LAN mode only).", _SUCCESS, auto_clear_ms=4000)
        self._apply_status_to_widgets(status)

    def _on_reconnect_clicked(self) -> None:
        self._set_feedback("Reconnecting to VPS relay...", _INFO, auto_clear_ms=2500)
        threading.Thread(target=self._reconnect_background, daemon=True).start()

    def _reconnect_background(self) -> None:
        try:
            self.runtime.reconnect_relay()
        except Exception as exc:
            logger.warning("Manual reconnect failed: %s", exc)
            self._post_to_ui(lambda: self._set_feedback(f"Reconnect failed: {exc}", _ERROR))

    def _post_to_ui(self, fn: Any) -> None:
        root = self._root
        if root is not None and not self._shutting_down:
            try:
                root.after(0, fn)
            except Exception:
                pass

    def _set_feedback(self, message: str, color: str, auto_clear_ms: int | None = None) -> None:
        if self._root is None:
            return
        if self._feedback_clear_job is not None:
            try:
                self._root.after_cancel(self._feedback_clear_job)
            except Exception:
                pass
            self._feedback_clear_job = None

        self._feedback_label.configure(text=message, fg=color)
        if auto_clear_ms is not None:
            self._feedback_clear_job = self._root.after(
                auto_clear_ms,
                lambda: self._feedback_label.configure(text="") if not self._shutting_down else None,
            )

    def _sync_form_from_status(self, status: AgentStatus) -> None:
        self._syncing_form = True
        try:
            self._var_relay_enabled.set(status.relay_enabled)
            self._var_relay_url.set(status.relay_url)
            self._var_device_id.set(status.relay_device_id)
            self._var_token.set(status.relay_token)
            self._update_entry_enabled_states()
        finally:
            self._syncing_form = False

    # ------------------------------------------------------------------
    # Live status polling
    # ------------------------------------------------------------------

    def _poll_status(self) -> None:
        if self._shutting_down or self._root is None:
            return
        try:
            status = self.runtime.get_status()
            self._apply_status_to_widgets(status)
            self._update_tray_metadata(status)
        except Exception as exc:
            logger.debug("Status poll error: %s", exc)
        finally:
            if not self._shutting_down and self._root is not None:
                self._root.after(self._POLL_INTERVAL_MS, self._poll_status)

    def _apply_status_to_widgets(self, status: AgentStatus) -> None:
        priv_tag = self._privilege_tag()
        self._subtitle_label.configure(text=f"PC Agent  •  {status.pc_name}  •  {priv_tag}")

        if status.relay_state == "p2p_active":
            dot_color, text = _SUCCESS, "Direct P2P Active"
        elif status.relay_state == "connected":
            dot_color = _SUCCESS
            text = "Connected • Phone Online" if status.relay_peer_online else "Relay Connected"
        elif status.relay_state == "disabled":
            dot_color, text = _TEXT_MUTED, "Relay Disabled"
        elif status.relay_state == "error":
            dot_color, text = _ERROR, "Relay Error"
        else:
            dot_color, text = _WARN, "Connecting..."

        self._status_dot.configure(fg=dot_color)
        self._status_label.configure(text=text)

        # Update devices card
        curr_devs = status.connected_devices
        curr_blocked = status.blocked_devices
        count = status.connected_devices_count
        self._devices_badge_lbl.configure(
            text=f"{count} Connected" if count > 0 else "0 Connected",
            fg=_SUCCESS if count > 0 else _TEXT_MUTED,
        )

        if curr_devs != self._last_devices_snapshot or curr_blocked != self._last_blocked_snapshot:
            self._last_devices_snapshot = curr_devs
            self._last_blocked_snapshot = curr_blocked
            self._rebuild_devices_ui(curr_devs, curr_blocked)

        primary_ip = status.local_ips[0] if status.local_ips else "127.0.0.1"
        self._last_lan_url = f"ws://{primary_ip}:{status.ws_port}"
        lan_extra = f"  ({status.lan_clients_count} phone)" if status.lan_clients_count else ""
        self._lan_value_label.configure(text=f"{self._last_lan_url}{lan_extra}")
        self._mic_value_label.configure(text=status.mic_status)

        # Surface relay connection error gently if no user feedback is currently active
        if status.relay_state == "error" and status.relay_last_error and not self._feedback_clear_job:
            short_err = status.relay_last_error.splitlines()[0][:110]
            self._feedback_label.configure(text=f"Relay issue: {short_err}", fg=_WARN)
        elif status.relay_state in ("connected", "p2p_active") and not self._feedback_clear_job:
            if self._feedback_label.cget("text").startswith("Relay issue:"):
                self._feedback_label.configure(text="")

    def _update_tray_metadata(self, status: AgentStatus) -> None:
        icon = self._tray_icon
        if icon is None:
            return
        if status.relay_state == "unconfigured":
            tooltip = f"RemotePad — PC ID & Token not set ({status.pc_name})"
        elif status.relay_state == "p2p_active":
            tooltip = f"RemotePad — Direct P2P ({status.relay_device_id})"
        elif status.relay_state == "connected":
            dev_label = f" ({status.relay_device_id})" if status.relay_device_id else ""
            tooltip = f"RemotePad — Connected{dev_label}"
        elif status.relay_state == "disabled":
            tooltip = f"RemotePad — LAN Only ({status.pc_name})"
        elif status.relay_state == "error":
            dev_label = f" ({status.relay_device_id})" if status.relay_device_id else ""
            tooltip = f"RemotePad — Reconnecting{dev_label}"
        else:
            dev_label = f" ({status.relay_device_id})" if status.relay_device_id else ""
            tooltip = f"RemotePad — Connecting{dev_label}"

        if status.connected_devices_count > 0:
            tooltip += f" • {status.connected_devices_count} Device(s)"

        # Windows tray tooltips max 127 chars
        tooltip = tooltip[:120]
        if tooltip != self._last_tooltip:
            self._last_tooltip = tooltip
            try:
                icon.title = tooltip
            except Exception:
                pass

        tray_status_line = self._tray_status_text()
        if (
            tray_status_line != self._last_tray_status_line
            or status.connected_devices_count != self._last_connected_count
        ):
            self._last_tray_status_line = tray_status_line
            self._last_connected_count = status.connected_devices_count
            try:
                icon.update_menu()
            except Exception:
                pass
            self._last_tray_status_line = tray_status_line
            try:
                icon.update_menu()
            except Exception:
                pass

    # ------------------------------------------------------------------
    # Shutdown
    # ------------------------------------------------------------------

    def _quit_on_main_thread(self) -> None:
        if self._shutting_down:
            return
        self._shutting_down = True
        if self._root is not None:
            try:
                self._root.withdraw()
                self._root.quit()
            except Exception:
                pass

    def _cleanup(self) -> None:
        self._shutting_down = True
        if self._tray_icon is not None:
            try:
                self._tray_icon.stop()
            except Exception:
                pass
            self._tray_icon = None

        try:
            self.runtime.stop(timeout=5.0)
        except Exception as exc:
            logger.debug("Runtime stop error: %s", exc)

        if self._root is not None:
            try:
                self._root.destroy()
            except Exception:
                pass
            self._root = None
