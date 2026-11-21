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
VALID_SCREEN_QUALITIES = frozenset({"auto", "hd", "balanced", "fast"})

MAX_MOVE_DELTA = 2000.0
MAX_SCROLL_DELTA = 200.0
MAX_CLICK_COUNT = 3
MAX_KEY_COMBO_LEN = 8
MAX_TEXT_LEN = 4096
MAX_PASTE_LEN = 65536
MAX_SCREEN_FPS = 30
MAX_SCREEN_WIDTH = 1920
MIN_SCREEN_WIDTH = 320
MAX_MONITOR_INDEX = 16


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
class MoveAbsMessage:
    x: float
    y: float
    monitor: int = 0


@dataclass(frozen=True, slots=True)
class ScreenStartMessage:
    quality: str = "auto"
    fps: int = 20
    max_width: int = 1280
    monitor: int = 0
    prefer_p2p: bool = True
    hide_cursor: bool = False


@dataclass(frozen=True, slots=True)
class ScreenStopMessage:
    pass


@dataclass(frozen=True, slots=True)
class ScreenConfigMessage:
    quality: str | None = None
    fps: int | None = None
    max_width: int | None = None
    monitor: int | None = None
    prefer_p2p: bool | None = None
    hide_cursor: bool | None = None


@dataclass(frozen=True, slots=True)
class ScreenAckMessage:
    seq: int


@dataclass(frozen=True, slots=True)
class PingMessage:
    pass


@dataclass(frozen=True, slots=True)
class DevicesUpdateMessage:
    count: int
    devices: tuple[dict[str, Any], ...] = ()


@dataclass(frozen=True, slots=True)
class UnknownMessage:
    msg_type: str


ParsedMessage = Union[
    HelloMessage,
    MoveMessage,
    MoveAbsMessage,
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
    ScreenStartMessage,
    ScreenStopMessage,
    ScreenConfigMessage,
    ScreenAckMessage,
    PingMessage,
    DevicesUpdateMessage,
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

    if msg_type == "move_abs":
        raw_x = data.get("x")
        raw_y = data.get("y")
        if (
            isinstance(raw_x, bool)
            or isinstance(raw_y, bool)
            or not isinstance(raw_x, (int, float))
            or not isinstance(raw_y, (int, float))
        ):
            return None
        fx = float(raw_x)
        fy = float(raw_y)
        if not (math.isfinite(fx) and math.isfinite(fy)):
            return None
        raw_mon = data.get("monitor", 0)
        if isinstance(raw_mon, bool) or not isinstance(raw_mon, int):
            return None
        mon = min(max(raw_mon, 0), MAX_MONITOR_INDEX)
        return MoveAbsMessage(
            x=min(max(fx, 0.0), 1.0),
            y=min(max(fy, 0.0), 1.0),
            monitor=mon,
        )

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

    if msg_type == "screen_start":
        quality = data.get("quality", "auto")
        if not isinstance(quality, str) or quality.strip().lower() not in VALID_SCREEN_QUALITIES:
            quality = "auto"
        else:
            quality = quality.strip().lower()
        raw_fps = data.get("fps", 20)
        if isinstance(raw_fps, bool) or not isinstance(raw_fps, int):
            fps = 20
        else:
            fps = min(max(raw_fps, 1), MAX_SCREEN_FPS)
        raw_w = data.get("max_width", 1280)
        if isinstance(raw_w, bool) or not isinstance(raw_w, int):
            max_width = 1280
        else:
            max_width = min(max(raw_w, MIN_SCREEN_WIDTH), MAX_SCREEN_WIDTH)
        raw_mon = data.get("monitor", 0)
        if isinstance(raw_mon, bool) or not isinstance(raw_mon, int):
            monitor = 0
        else:
            monitor = min(max(raw_mon, 0), MAX_MONITOR_INDEX)
        raw_p2p = data.get("prefer_p2p", True)
        prefer_p2p = raw_p2p if isinstance(raw_p2p, bool) else True
        raw_hide_cursor = data.get("hide_cursor", False)
        hide_cursor = raw_hide_cursor if isinstance(raw_hide_cursor, bool) else False
        return ScreenStartMessage(
            quality=quality,
            fps=fps,
            max_width=max_width,
            monitor=monitor,
            prefer_p2p=prefer_p2p,
            hide_cursor=hide_cursor,
        )

    if msg_type == "screen_stop":
        return ScreenStopMessage()

    if msg_type == "screen_cfg":
        quality: str | None = None
        if "quality" in data:
            q = data.get("quality")
            if isinstance(q, str) and q.strip().lower() in VALID_SCREEN_QUALITIES:
                quality = q.strip().lower()
        fps: int | None = None
        if "fps" in data:
            raw_fps = data.get("fps")
            if not isinstance(raw_fps, bool) and isinstance(raw_fps, int):
                fps = min(max(raw_fps, 1), MAX_SCREEN_FPS)
        max_width: int | None = None
        if "max_width" in data:
            raw_w = data.get("max_width")
            if not isinstance(raw_w, bool) and isinstance(raw_w, int):
                max_width = min(max(raw_w, MIN_SCREEN_WIDTH), MAX_SCREEN_WIDTH)
        monitor: int | None = None
        if "monitor" in data:
            raw_mon = data.get("monitor")
            if not isinstance(raw_mon, bool) and isinstance(raw_mon, int):
                monitor = min(max(raw_mon, 0), MAX_MONITOR_INDEX)
        prefer_p2p: bool | None = None
        if "prefer_p2p" in data:
            raw_p2p = data.get("prefer_p2p")
            if isinstance(raw_p2p, bool):
                prefer_p2p = raw_p2p
        hide_cursor: bool | None = None
        if "hide_cursor" in data:
            raw_hide_cursor = data.get("hide_cursor")
            if isinstance(raw_hide_cursor, bool):
                hide_cursor = raw_hide_cursor
        return ScreenConfigMessage(
            quality=quality,
            fps=fps,
            max_width=max_width,
            monitor=monitor,
            prefer_p2p=prefer_p2p,
            hide_cursor=hide_cursor,
        )

    if msg_type == "screen_ack":
        raw_seq = data.get("seq")
        if isinstance(raw_seq, bool) or not isinstance(raw_seq, int) or raw_seq < 0:
            return None
        return ScreenAckMessage(seq=raw_seq & 0xFFFFFFFF)

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

    if msg_type == "devices_update":
        raw_count = data.get("count", 0)
        raw_devs = data.get("devices", [])
        devs = tuple(raw_devs) if isinstance(raw_devs, list) else ()
        cnt = int(raw_count) if isinstance(raw_count, int) else len(devs)
        return DevicesUpdateMessage(count=cnt, devices=devs)

    return UnknownMessage(msg_type=msg_type)


def encode_hello_ack(
    pc_name: str,
    ok: bool = True,
    file_port: int | None = None,
    mic_port: int | None = None,
    mic_device: str | None = None,
    connected_count: int | None = None,
) -> str:
    payload: dict[str, Any] = {
        "t": "hello_ack",
        "v": PROTOCOL_VERSION,
        "ok": ok,
        "name": pc_name,
    }
    if connected_count is not None:
        payload["connected_count"] = max(1, connected_count)
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


def encode_devices_update(count: int, devices: list[dict[str, Any]]) -> str:
    return json.dumps(
        {
            "t": "devices_update",
            "count": count,
            "devices": devices,
        },
        separators=(",", ":"),
    )
