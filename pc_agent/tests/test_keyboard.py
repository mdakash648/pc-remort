"""Unit tests for pc_agent.input.keyboard and pc_agent.features.gestures."""

from __future__ import annotations

from features.gestures import GestureHandler
from input.keyboard import (
    KEYEVENTF_EXTENDEDKEY,
    KEYEVENTF_KEYUP,
    KEYEVENTF_UNICODE,
    VK_MAP,
    KeyboardController,
)
from input.mouse import INPUT, INPUT_KEYBOARD


def test_key_combo_presses_in_order_and_releases_in_reverse() -> None:
    captured: list[INPUT] = []
    kb = KeyboardController(sender=lambda events: (captured.extend(events), len(events))[1])

    assert kb.key_combo(["ctrl", "shift", "c"]) is True
    assert len(captured) == 6
    assert all(e.type == INPUT_KEYBOARD for e in captured)

    # Down order: ctrl, shift, c
    assert [e.ki.wVk for e in captured[:3]] == [VK_MAP["ctrl"], VK_MAP["shift"], VK_MAP["c"]]
    assert all((e.ki.dwFlags & KEYEVENTF_KEYUP) == 0 for e in captured[:3])

    # Up order: c, shift, ctrl
    assert [e.ki.wVk for e in captured[3:]] == [VK_MAP["c"], VK_MAP["shift"], VK_MAP["ctrl"]]
    assert all((e.ki.dwFlags & KEYEVENTF_KEYUP) != 0 for e in captured[3:])


def test_extended_key_flag_set_for_arrows_and_win() -> None:
    captured: list[INPUT] = []
    kb = KeyboardController(sender=lambda events: (captured.extend(events), len(events))[1])

    assert kb.key_event("win", "down") is True
    assert kb.key_event("right", "up") is True
    assert (captured[0].ki.dwFlags & KEYEVENTF_EXTENDEDKEY) != 0
    assert (captured[1].ki.dwFlags & (KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP)) == (
        KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP
    )


def test_type_text_unicode_bangla_and_newlines() -> None:
    captured: list[INPUT] = []
    kb = KeyboardController(sender=lambda events: (captured.extend(events), len(events))[1])

    text = "বাংলা\nHi"
    sent = kb.type_text(text)
    assert sent == len(captured)

    # First char 'ব' (U+09AC) -> unicode down + up
    assert captured[0].ki.wVk == 0
    assert captured[0].ki.wScan == ord("ব")
    assert captured[0].ki.dwFlags == KEYEVENTF_UNICODE
    assert captured[1].ki.wScan == ord("ব")
    assert captured[1].ki.dwFlags == (KEYEVENTF_UNICODE | KEYEVENTF_KEYUP)

    # Newline becomes VK_RETURN down + up
    enter_events = [e for e in captured if e.ki.wVk == VK_MAP["enter"]]
    assert len(enter_events) == 2


def test_unknown_key_rejected() -> None:
    captured: list[INPUT] = []
    kb = KeyboardController(sender=lambda events: (captured.extend(events), len(events))[1])
    assert kb.key_event("nonexistent_key", "down") is False
    assert kb.key_combo(["ctrl", "nonexistent_key"]) is False
    assert len(captured) == 0


def test_gesture_handler_maps_to_windows_shortcuts() -> None:
    captured: list[INPUT] = []
    kb = KeyboardController(sender=lambda events: (captured.extend(events), len(events))[1])
    gestures = GestureHandler(kb)

    assert gestures.handle("task_view") is True
    assert [e.ki.wVk for e in captured] == [
        VK_MAP["win"],
        VK_MAP["tab"],
        VK_MAP["tab"],
        VK_MAP["win"],
    ]

    captured.clear()
    assert gestures.handle("show_desktop") is True
    assert [e.ki.wVk for e in captured] == [
        VK_MAP["win"],
        VK_MAP["d"],
        VK_MAP["d"],
        VK_MAP["win"],
    ]

    assert gestures.handle("unknown_gesture") is False


def test_paste_text_uses_clipboard_and_ctrl_v_without_enter() -> None:
    captured: list[INPUT] = []
    copied: list[str] = []
    kb = KeyboardController(
        sender=lambda events: (captured.extend(events), len(events))[1],
        clipboard_setter=lambda txt: (copied.append(txt), True)[1],
    )

    multi_para = "প্রথম প্যারা\n\nদ্বিতীয় প্যারা\nতৃতীয় লাইন"
    assert kb.paste_text(multi_para) is True
    assert copied == [multi_para]
    # Must send Ctrl+V and NEVER trigger VK_RETURN (Enter)
    vks = [e.ki.wVk for e in captured]
    assert vks == [VK_MAP["ctrl"], VK_MAP["v"], VK_MAP["v"], VK_MAP["ctrl"]]
    assert VK_MAP["enter"] not in vks
