"""Tests for MediaController, PowerController, and media/power protocol parsing."""

from __future__ import annotations

from core.protocol import MediaMessage, PowerMessage, parse_message
from input.keyboard import KEYEVENTF_KEYUP, VK_MAP, KeyboardController
from input.media import MediaController
from input.mouse import INPUT
from input.power import PowerController


def test_protocol_parses_media_and_power_messages() -> None:
    assert parse_message('{"t":"media","a":"vol_up"}') == MediaMessage(action="vol_up")
    assert parse_message('{"t":"media","a":"play_pause"}') == MediaMessage(action="play_pause")
    assert parse_message('{"t":"media","a":""}') is None

    assert parse_message('{"t":"power","a":"lock"}') == PowerMessage(action="lock")
    assert parse_message('{"t":"power","a":"display_off"}') == PowerMessage(action="display_off")
    assert parse_message('{"t":"power","a":123}') is None


def test_media_controller_dispatches_windows_media_keys() -> None:
    captured: list[INPUT] = []
    kb = KeyboardController(sender=lambda events: (captured.extend(events), len(events))[1])
    media = MediaController(kb)

    assert media.handle("vol_up") is True
    assert media.handle("mute") is True
    assert media.handle("play_pause") is True
    assert media.handle("next") is True
    assert media.handle("prev") is True
    assert media.handle("rewind") is True
    assert media.handle("forward") is True
    assert media.handle("unknown_action") is False

    vks_down = [e.ki.wVk for e in captured if (e.ki.dwFlags & KEYEVENTF_KEYUP) == 0]
    assert vks_down == [
        VK_MAP["vol_up"],
        VK_MAP["vol_mute"],
        VK_MAP["media_play_pause"],
        VK_MAP["media_next"],
        VK_MAP["media_prev"],
        VK_MAP["left"],
        VK_MAP["right"],
    ]


def test_power_controller_validates_and_dispatches() -> None:
    executed: list[str] = []
    power = PowerController(enabled=True, executor=lambda a: (executed.append(a), True)[1])

    for action in ("sleep", "hibernate", "shutdown", "restart", "lock", "display_off", "sign_out"):
        assert power.handle(action) is True
    assert power.handle("format_c_drive") is False
    assert executed == ["sleep", "hibernate", "shutdown", "restart", "lock", "display_off", "sign_out"]

    disabled = PowerController(enabled=False, executor=lambda a: (executed.append(a), True)[1])
    assert disabled.handle("shutdown") is False
