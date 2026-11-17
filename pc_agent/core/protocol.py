"""JSON message protocol parser, validator, and serializer.

Matches the specification in 01-common-overview.md:
- Every message has a string `t` (type) field.
- Unknown `t` values are safely ignored (forward-compatible).
- Invalid JSON or malformed fields never raise unhandled exceptions.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass
from typing import Any, Literal, Union

from config import PROTOCOL_VERSION

MouseButton = Literal["left", "middle", "right"]
ButtonState = Literal["down", "up"]

VALID_BUTTONS = frozenset({"left", "middle", "right"})
VALID_BUTTON_STATES = frozenset({"down", "up"})

MAX_MOVE_DELTA = 2000.0
MAX_SCROLL_DELTA = 200.0
MAX_CLICK_COUNT = 3
MAX_KEY_COMBO_LEN = 8
MAX_TEXT_LEN = 4096
MAX_PASTE_LEN = 65536


@dataclass(frozen=True, slots=True)
class HelloMessage:
    version: int
    token: str | None = None
    device_name: str | None = None


@dataclass(frozen=True, slots=True)
class MoveMessage:
    dx: float
    dy: float


@dataclass(frozen=True, slots=True)
class ButtonMessage:
    button: MouseButton
    state: ButtonState


@dataclass(frozen=True, slots=True)
class ClickMessage:
    button: MouseButton
    count: int = 1


@dataclass(frozen=True, slots=True)
class ScrollMessage:
    dx: float
    dy: float


@dataclass(frozen=True, slots=True)
class KeyComboMessage:
    keys: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class KeyEventMessage:
    key: str
    state: ButtonState


@dataclass(frozen=True, slots=True)
class TextMessage:
    value: str


@dataclass(frozen=True, slots=True)
class PasteMessage:
    value: str


@dataclass(frozen=True, slots=True)
class GestureMessage:
    gesture: str


@dataclass(frozen=True, slots=True)
class MediaMessage:
    action: str


@dataclass(frozen=True, slots=True)
class PowerMessage:
    action: str


@dataclass(frozen=True, slots=True)
class VolumeSetMessage:
    level: float


@dataclass(frozen=True, slots=True)
class VolumeSyncMessage:
    enable: bool


@dataclass(frozen=True, slots=True)
class PingMessage:
    pass


@dataclass(frozen=True, slots=True)
class UnknownMessage:
    msg_type: str


ParsedMessage = Union[
    HelloMessage,
    MoveMessage,
    ButtonMessage,
    ClickMessage,
    ScrollMessage,
    KeyComboMessage,
    KeyEventMessage,
    TextMessage,
    PasteMessage,
    GestureMessage,
    MediaMessage,
    PowerMessage,
    VolumeSetMessage,
    VolumeSyncMessage,
    PingMessage,
    UnknownMessage,
]


def _parse_finite_number(value: Any, limit: float) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    fval = float(value)
    if not math.isfinite(fval):
        return None
    if fval > limit:
        return limit
    if fval < -limit:
        return -limit
    return fval


def parse_message(raw: str | bytes) -> ParsedMessage | None:
    """Parse and validate a raw WebSocket text frame into a typed message.

    Returns None when the message is malformed or fails validation.
    Returns UnknownMessage when `t` is a valid string but not recognized yet.
    """
    try:
        data = json.loads(raw)
    except (ValueError, TypeError):
        return None

    if not isinstance(data, dict):
        return None

    msg_type = data.get("t")
    if not isinstance(msg_type, str) or not msg_type:
        return None

    if msg_type == "move":
        dx = _parse_finite_number(data.get("dx"), MAX_MOVE_DELTA)
        dy = _parse_finite_number(data.get("dy"), MAX_MOVE_DELTA)
        if dx is None or dy is None:
            return None
        return MoveMessage(dx=dx, dy=dy)

    if msg_type == "btn":
        btn = data.get("b")
        state = data.get("s")
        if btn not in VALID_BUTTONS or state not in VALID_BUTTON_STATES:
            return None
        return ButtonMessage(button=btn, state=state)  # type: ignore[arg-type]

    if msg_type == "click":
        btn = data.get("b")
        count = data.get("n", 1)
        if btn not in VALID_BUTTONS:
            return None
        if isinstance(count, bool) or not isinstance(count, int):
            return None
        if count < 1 or count > MAX_CLICK_COUNT:
            return None
        return ClickMessage(button=btn, count=count)  # type: ignore[arg-type]

    if msg_type == "scroll":
        dx = _parse_finite_number(data.get("dx", 0), MAX_SCROLL_DELTA)
        dy = _parse_finite_number(data.get("dy", 0), MAX_SCROLL_DELTA)
        if dx is None or dy is None:
            return None
        return ScrollMessage(dx=dx, dy=dy)

    if msg_type == "key":
        raw_keys = data.get("keys")
        if (
            not isinstance(raw_keys, list)
            or not (1 <= len(raw_keys) <= MAX_KEY_COMBO_LEN)
            or not all(isinstance(k, str) and 1 <= len(k.strip()) <= 32 for k in raw_keys)
        ):
            return None
        return KeyComboMessage(keys=tuple(k.strip().lower() for k in raw_keys))

    if msg_type == "keyevt":
        key = data.get("k")
        state = data.get("s")
        if (
            not isinstance(key, str)
            or not (1 <= len(key.strip()) <= 32)
            or state not in VALID_BUTTON_STATES
        ):
            return None
        return KeyEventMessage(key=key.strip().lower(), state=state)  # type: ignore[arg-type]

    if msg_type == "text":
        value = data.get("v")
        if not isinstance(value, str) or len(value) > MAX_TEXT_LEN:
            return None
        return TextMessage(value=value)

    if msg_type == "paste":
        value = data.get("v")
        if not isinstance(value, str) or len(value) > MAX_PASTE_LEN:
            return None
        return PasteMessage(value=value)

    if msg_type == "gesture":
        gesture = data.get("g")
        if not isinstance(gesture, str) or not (1 <= len(gesture.strip()) <= 64):
            return None
        return GestureMessage(gesture=gesture.strip().lower())

    if msg_type == "media":
        action = data.get("a")
        if not isinstance(action, str) or not (1 <= len(action.strip()) <= 32):
            return None
        return MediaMessage(action=action.strip().lower())

    if msg_type == "power":
        action = data.get("a")
        if not isinstance(action, str) or not (1 <= len(action.strip()) <= 32):
            return None
        return PowerMessage(action=action.strip().lower())

    if msg_type == "vol_set":
        raw_val = data.get("v")
        if isinstance(raw_val, bool) or not isinstance(raw_val, (int, float)):
            return None
        fval = float(raw_val)
        if not math.isfinite(fval):
            return None
        clamped = min(max(fval, 0.0), 1.0)
        return VolumeSetMessage(level=clamped)

    if msg_type == "vol_sync":
        enable = data.get("enable")
        if not isinstance(enable, bool):
            return None
        return VolumeSyncMessage(enable=enable)

    if msg_type == "hello":
        version = data.get("v", PROTOCOL_VERSION)
        if isinstance(version, bool) or not isinstance(version, int) or version < 1:
            return None
        token = data.get("token")
        if token is not None and not isinstance(token, str):
            return None
        device_name = data.get("name")
        if device_name is not None and not isinstance(device_name, str):
            return None
        return HelloMessage(version=version, token=token, device_name=device_name)

    if msg_type == "ping":
        return PingMessage()

    return UnknownMessage(msg_type=msg_type)


def encode_hello_ack(
    pc_name: str,
    ok: bool = True,
    file_port: int | None = None,
    mic_port: int | None = None,
    mic_device: str | None = None,
) -> str:
    payload: dict[str, Any] = {
        "t": "hello_ack",
        "v": PROTOCOL_VERSION,
        "ok": ok,
        "name": pc_name,
    }
    if file_port is not None:
        payload["file_port"] = file_port
    if mic_port is not None:
        payload["mic_port"] = mic_port
    if mic_device is not None:
        payload["mic_device"] = mic_device
    return json.dumps(payload, separators=(",", ":"))


def encode_pong() -> str:
    return '{"t":"pong"}'


def encode_vol_state(level: float, muted: bool) -> str:
    clamped = round(min(max(float(level), 0.0), 1.0), 3)
    return json.dumps(
        {
            "t": "vol_state",
            "v": clamped,
            "muted": bool(muted),
        },
        separators=(",", ":"),
    )
