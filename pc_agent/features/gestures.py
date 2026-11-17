"""Touchpad multi-finger gesture to Windows keyboard shortcut mapping."""

from __future__ import annotations

import logging
from typing import Mapping

from input.keyboard import KeyboardController

logger = logging.getLogger(__name__)

DEFAULT_GESTURE_MAP: dict[str, list[str]] = {
    "task_view": ["win", "tab"],
    "show_desktop": ["win", "d"],
    "switch_next": ["alt", "tab"],
    "switch_prev": ["alt", "shift", "tab"],
    "desktop_left": ["win", "ctrl", "left"],
    "desktop_right": ["win", "ctrl", "right"],
}


class GestureHandler:
    """Translates high-level gesture identifiers into keyboard shortcuts."""

    def __init__(
        self,
        keyboard: KeyboardController,
        gesture_map: Mapping[str, list[str]] | None = None,
    ) -> None:
        self.keyboard = keyboard
        self.gesture_map: dict[str, list[str]] = dict(DEFAULT_GESTURE_MAP)
        if gesture_map:
            for name, keys in gesture_map.items():
                if isinstance(name, str) and isinstance(keys, list) and keys:
                    self.gesture_map[name] = [str(k) for k in keys]

    def handle(self, gesture: str) -> bool:
        keys = self.gesture_map.get(gesture)
        if not keys:
            logger.debug("Unmapped gesture: %r", gesture)
            return False
        return self.keyboard.key_combo(keys)
