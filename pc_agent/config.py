"""Configuration management for RemotePad PC Agent."""

from __future__ import annotations

import json
import logging
import os
from dataclasses import asdict, dataclass, field
from pathlib import Path

logger = logging.getLogger(__name__)

APP_NAME = "RemotePad"
PROTOCOL_VERSION = 1


def get_config_dir() -> Path:
    """Return the per-user configuration directory (%APPDATA%\\RemotePad on Windows)."""
    appdata = os.environ.get("APPDATA")
    if appdata:
        return Path(appdata) / APP_NAME
    return Path.home() / f".{APP_NAME.lower()}"


def get_config_path() -> Path:
    return get_config_dir() / "config.json"


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
        config_path = path or get_config_path()
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
        config_path = path or get_config_path()
        config_path.parent.mkdir(parents=True, exist_ok=True)
        config_path.write_text(
            json.dumps(asdict(self), indent=2),
            encoding="utf-8",
        )
