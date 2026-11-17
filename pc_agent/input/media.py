"""Windows media & system volume controller built on KeyboardController."""

from __future__ import annotations

import logging

from input.keyboard import KeyboardController

logger = logging.getLogger(__name__)

MEDIA_KEY_MAP: dict[str, list[str]] = {
    "vol_up": ["vol_up"],
    "vol_down": ["vol_down"],
    "mute": ["vol_mute"],
    "play_pause": ["media_play_pause"],
    "next": ["media_next"],
    "prev": ["media_prev"],
    "stop": ["media_stop"],
    # Left / Right seek works broadly across YouTube, VLC, WMP, Netflix, browsers
    "rewind": ["left"],
    "forward": ["right"],
}


class MediaController:
    """Dispatches media transport and system volume actions."""

    def __init__(self, keyboard: KeyboardController) -> None:
        self.keyboard = keyboard

    def handle(self, action: str) -> bool:
        keys = MEDIA_KEY_MAP.get(action.strip().lower())
        if not keys:
            logger.debug("Ignoring unknown media action '%s'", action)
            return False
        return self.keyboard.key_combo(keys)
