"""Tests for two-way Windows volume sync (only active when Media panel is open)."""

from __future__ import annotations

import asyncio
import json

import websockets

from config import AgentConfig
from core.protocol import VolumeSetMessage, VolumeSyncMessage, encode_vol_state, parse_message
from core.server import LocalWebSocketServer
from input.mouse import MouseController
from input.volume import VolumeController


class FakeVolumeBackend:
    def __init__(self, level: float = 0.25, muted: bool = False) -> None:
        self.level = level
        self.muted = muted

    def get_state(self) -> tuple[float, bool]:
        return (self.level, self.muted)

    def set_volume(self, level: float, unmute_if_positive: bool = True) -> tuple[float, bool]:
        self.level = min(max(level, 0.0), 1.0)
        if unmute_if_positive and self.level > 0.0:
            self.muted = False
        return (self.level, self.muted)

    def set_mute(self, muted: bool) -> tuple[float, bool]:
        self.muted = muted
        return (self.level, self.muted)


def test_volume_protocol_parsing_and_encoding() -> None:
    assert parse_message('{"t":"vol_set","v":0.75}') == VolumeSetMessage(level=0.75)
    assert parse_message('{"t":"vol_set","v":1.5}') == VolumeSetMessage(level=1.0)
    assert parse_message('{"t":"vol_set","v":-0.2}') == VolumeSetMessage(level=0.0)
    assert parse_message('{"t":"vol_set","v":true}') is None

    assert parse_message('{"t":"vol_sync","enable":true}') == VolumeSyncMessage(enable=True)
    assert parse_message('{"t":"vol_sync","enable":false}') == VolumeSyncMessage(enable=False)
    assert parse_message('{"t":"vol_sync","enable":"yes"}') is None

    assert json.loads(encode_vol_state(0.42, False)) == {"t": "vol_state", "v": 0.42, "muted": False}


def test_bidirectional_volume_sync_only_when_enabled() -> None:
    asyncio.run(_run_volume_sync_flow())


async def _run_volume_sync_flow() -> None:
    backend = FakeVolumeBackend(level=0.20, muted=False)
    volume = VolumeController(backend=backend)
    mouse = MouseController(sender=lambda events: len(events))
    server = LocalWebSocketServer(
        config=AgentConfig(host="127.0.0.1", port=0),
        mouse=mouse,
        volume=volume,
    )
    server._VOL_SYNC_INTERVAL_S = 0.03
    await server.start()

    try:
        uri = f"ws://127.0.0.1:{server.port}"
        async with websockets.connect(uri) as ws:
            # 1. While vol_sync is OFF, PC volume changes must NOT push anything to phone
            backend.level = 0.35
            await asyncio.sleep(0.08)
            await ws.send('{"t":"ping"}')
            assert json.loads(await ws.recv()) == {"t": "pong"}

            # 2. Enable vol_sync (Media panel opened) -> immediately receives PC's current 35%
            await ws.send('{"t":"vol_sync","enable":true}')
            msg = json.loads(await ws.recv())
            assert msg == {"t": "vol_state", "v": 0.35, "muted": False}

            # 3. Phone slides to 100% -> PC becomes 100% and confirms
            await ws.send('{"t":"vol_set","v":1.0}')
            msg2 = json.loads(await ws.recv())
            assert backend.level == 1.0
            assert msg2 == {"t": "vol_state", "v": 1.0, "muted": False}

            # 4. PC volume changed externally (e.g. PC keyboard) to 0% + muted -> phone receives update
            backend.level = 0.0
            backend.muted = True
            msg3 = json.loads(await asyncio.wait_for(ws.recv(), timeout=1.0))
            assert msg3 == {"t": "vol_state", "v": 0.0, "muted": True}

            # 5. Disable vol_sync (Media panel closed) -> further PC changes must NOT push
            await ws.send('{"t":"vol_sync","enable":false}')
            await ws.send('{"t":"ping"}')
            assert json.loads(await ws.recv()) == {"t": "pong"}

            backend.level = 0.80
            backend.muted = False
            await asyncio.sleep(0.08)
            await ws.send('{"t":"ping"}')
            # Next received message must be pong (no unsolicited vol_state!)
            assert json.loads(await ws.recv()) == {"t": "pong"}
    finally:
        await server.stop()
