"""Configuration management for RemotePad PC Agent."""

from __future__ import annotations

import json
import logging
import os
import shutil
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path

logger = logging.getLogger(__name__)

APP_NAME = "RemotePad"
PROTOCOL_VERSION = 1


def _is_windows_system() -> bool:
    if sys.platform != "win32":
        return False
    try:
        from core.windows_integration import is_system

        return is_system()
    except Exception:
        return os.environ.get("USERNAME", "").strip().upper() == "SYSTEM"


def get_shared_config_dir() -> Path:
    """Return the machine-wide shared directory (%PROGRAMDATA%\\RemotePad on Windows).

    Used by the pre-login SYSTEM boot service so settings and logs are accessible
    before any user logs in and shared cleanly with the user's tray UI.
    """
    if sys.platform == "win32":
        program_data = os.environ.get("PROGRAMDATA") or r"C:\ProgramData"
        return Path(program_data) / APP_NAME
    return get_user_config_dir()


def get_user_config_dir() -> Path:
    """Return the per-user configuration directory (%APPDATA%\\RemotePad on Windows)."""
    appdata = os.environ.get("APPDATA")
    if appdata:
        return Path(appdata) / APP_NAME
    return Path.home() / f".{APP_NAME.lower()}"


def get_config_dir() -> Path:
    """Return the active config/log directory (ProgramData for SYSTEM, AppData for users)."""
    if _is_windows_system():
        return get_shared_config_dir()
    return get_user_config_dir()


def get_shared_config_path() -> Path:
    return get_shared_config_dir() / "config.json"


def get_config_path() -> Path:
    return get_config_dir() / "config.json"


def sync_config_to_shared(
    user_path: Path | None = None,
    shared_path: Path | None = None,
) -> Path:
    """Ensure the shared ProgramData config.json is up to date with the user's config."""
    src = user_path or (get_user_config_dir() / "config.json")
    dst = shared_path or get_shared_config_path()
    try:
        dst.parent.mkdir(parents=True, exist_ok=True)
        if src.exists() and src.resolve() != dst.resolve():
            should_copy = (not dst.exists()) or (src.stat().st_mtime > dst.stat().st_mtime)
            if should_copy:
                shutil.copy2(src, dst)
                logger.info("Synced config from %s to shared path %s", src, dst)
    except OSError as exc:
        logger.debug("Could not sync config to shared path %s: %s", dst, exc)
    return dst


def _resolve_default_load_path() -> Path:
    if _is_windows_system():
        return get_shared_config_path()
    user_path = get_user_config_dir() / "config.json"
    shared_path = get_shared_config_path()
    if user_path.exists() and shared_path.exists():
        try:
            if shared_path.stat().st_mtime > user_path.stat().st_mtime:
                return shared_path
        except OSError:
            pass
        return user_path
    if shared_path.exists():
        return shared_path
    return user_path


@dataclass
class AgentConfig:
    host: str = "0.0.0.0"
    port: int = 8765
    discovery_enabled: bool = True
    discovery_port: int = 8766
    file_transfer_enabled: bool = True
    file_port: int = 8767
    received_files_dir: str = ""
    mic_enabled: bool = True
    mic_port: int = 8768
    mic_output_device: str = ""
    relay_enabled: bool = True
    relay_url: str = "ws://182.255.88.108:8770"
    relay_udp_port: int = 8771
    relay_p2p_enabled: bool = True
    relay_device_id: str = "akash-pc"
    relay_token: str = "rpad-7f4c9a2e8b1d6305"
    mouse_sensitivity: float = 1.0
    mouse_acceleration: float = 0.35
    cursor_smoothing: bool = True
    cursor_smoothing_ms: float = 9.0
    scroll_sensitivity: float = 1.0
    natural_scroll: bool = False
    gesture_map: dict[str, list[str]] = field(
        default_factory=lambda: {
            "task_view": ["win", "tab"],
            "show_desktop": ["win", "d"],
            "switch_next": ["alt", "tab"],
            "switch_prev": ["alt", "shift", "tab"],
            "desktop_left": ["win", "ctrl", "left"],
            "desktop_right": ["win", "ctrl", "right"],
        }
    )

    @classmethod
    def load(cls, path: Path | None = None) -> "AgentConfig":
        config_path = path if path is not None else _resolve_default_load_path()
        if not config_path.exists():
            return cls()
        try:
            raw = json.loads(config_path.read_text(encoding="utf-8"))
            if not isinstance(raw, dict):
                return cls()
            valid_fields = {f for f in cls.__dataclass_fields__}
            filtered = {k: v for k, v in raw.items() if k in valid_fields}
            return cls(**filtered)
        except Exception as exc:
            logger.warning("Failed to load config from %s: %s", config_path, exc)
            return cls()

    def save(self, path: Path | None = None) -> None:
        serialized = json.dumps(asdict(self), indent=2)
        if path is not None:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(serialized, encoding="utf-8")
            return

        primary = get_config_path()
        primary.parent.mkdir(parents=True, exist_ok=True)
        primary.write_text(serialized, encoding="utf-8")

        # Keep machine-wide shared config synced so the pre-login SYSTEM service
        # always picks up relay/sensitivity changes made from the user's tray UI.
        if not _is_windows_system():
            shared = get_shared_config_path()
            try:
                if shared.resolve() != primary.resolve():
                    shared.parent.mkdir(parents=True, exist_ok=True)
                    shared.write_text(serialized, encoding="utf-8")
            except OSError as exc:
                logger.debug("Could not mirror config to shared path %s: %s", shared, exc)
