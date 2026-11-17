"""Unit tests for pc_agent.core.protocol."""

from __future__ import annotations

import json
import math

from core.protocol import (
    ButtonMessage,
    ClickMessage,
    GestureMessage,
    HelloMessage,
    KeyComboMessage,
    KeyEventMessage,
    MAX_MOVE_DELTA,
    MAX_SCROLL_DELTA,
    MoveMessage,
    PingMessage,
    ScrollMessage,
    TextMessage,
    UnknownMessage,
    encode_hello_ack,
    encode_pong,
    parse_message,
)


def test_parse_move_valid_and_clamped() -> None:
    assert parse_message('{"t":"move","dx":5,"dy":-3.5}') == MoveMessage(dx=5.0, dy=-3.5)
    clamped = parse_message('{"t":"move","dx":99999,"dy":-99999}')
    assert clamped == MoveMessage(dx=MAX_MOVE_DELTA, dy=-MAX_MOVE_DELTA)


def test_parse_move_invalid() -> None:
    assert parse_message('{"t":"move","dx":"abc","dy":1}') is None
    assert parse_message('{"t":"move","dx":true,"dy":1}') is None
    assert parse_message('{"t":"move","dx":1}') is None
    assert parse_message(json.dumps({"t": "move", "dx": math.nan, "dy": 0})) is None


def test_parse_btn() -> None:
    assert parse_message('{"t":"btn","b":"left","s":"down"}') == ButtonMessage("left", "down")
    assert parse_message('{"t":"btn","b":"middle","s":"up"}') == ButtonMessage("middle", "up")
    assert parse_message('{"t":"btn","b":"right","s":"down"}') == ButtonMessage("right", "down")
    assert parse_message('{"t":"btn","b":"extra","s":"down"}') is None
    assert parse_message('{"t":"btn","b":"left","s":"hold"}') is None


def test_parse_click() -> None:
    assert parse_message('{"t":"click","b":"left"}') == ClickMessage("left", 1)
    assert parse_message('{"t":"click","b":"left","n":2}') == ClickMessage("left", 2)
    assert parse_message('{"t":"click","b":"right","n":1}') == ClickMessage("right", 1)
    assert parse_message('{"t":"click","b":"left","n":0}') is None
    assert parse_message('{"t":"click","b":"left","n":5}') is None
    assert parse_message('{"t":"click","b":"left","n":true}') is None


def test_parse_scroll() -> None:
    assert parse_message('{"t":"scroll","dx":0,"dy":-2}') == ScrollMessage(0.0, -2.0)
    assert parse_message('{"t":"scroll","dy":1.5}') == ScrollMessage(0.0, 1.5)
    assert parse_message('{"t":"scroll","dx":999,"dy":-999}') == ScrollMessage(
        MAX_SCROLL_DELTA, -MAX_SCROLL_DELTA
    )


def test_parse_hello_and_ping() -> None:
    assert parse_message('{"t":"hello","v":1,"name":"Pixel"}') == HelloMessage(
        version=1, token=None, device_name="Pixel"
    )
    assert parse_message('{"t":"ping"}') == PingMessage()


def test_unknown_type_ignored_gracefully() -> None:
    assert parse_message('{"t":"future_feature","payload":123}') == UnknownMessage("future_feature")


def test_malformed_json_returns_none() -> None:
    assert parse_message("{not valid json") is None
    assert parse_message("[]") is None
    assert parse_message("{}") is None
    assert parse_message('{"t":123}') is None


def test_encoders() -> None:
    ack = json.loads(encode_hello_ack("MY-PC", ok=True))
    assert ack == {"t": "hello_ack", "v": 1, "ok": True, "name": "MY-PC"}
    assert json.loads(encode_pong()) == {"t": "pong"}


def test_parse_keyboard_text_and_gesture() -> None:
    assert parse_message('{"t":"key","keys":["Ctrl","C"]}') == KeyComboMessage(("ctrl", "c"))
    assert parse_message('{"t":"key","keys":[]}') is None
    assert parse_message('{"t":"keyevt","k":"Shift","s":"down"}') == KeyEventMessage("shift", "down")
    assert parse_message('{"t":"keyevt","k":"shift","s":"invalid"}') is None
    assert parse_message('{"t":"text","v":"আমার সোনার বাংলা"}') == TextMessage("আমার সোনার বাংলা")
    assert parse_message('{"t":"text","v":123}') is None
    assert parse_message('{"t":"gesture","g":"task_view"}') == GestureMessage("task_view")
    assert parse_message('{"t":"gesture","g":""}') is None
