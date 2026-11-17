"""Integration tests for LocalWebSocketServer."""

from __future__ import annotations

import asyncio
import json

import websockets

from config import AgentConfig
from core.server import LocalWebSocketServer
from input.keyboard import KEYEVENTF_KEYUP, VK_MAP, KeyboardController
from input.mouse import (
    INPUT,
    MOUSEEVENTF_LEFTDOWN,
    MOUSEEVENTF_LEFTUP,
    MOUSEEVENTF_MOVE,
    MOUSEEVENTF_WHEEL,
    MouseController,
)


def test_websocket_server_end_to_end_and_disconnect_cleanup() -> None:
    asyncio.run(_run_server_flow())


async def _run_server_flow() -> None:
    captured_mouse: list[INPUT] = []
    captured_kb: list[INPUT] = []
    mouse = MouseController(
        sensitivity=1.0,
        acceleration=0.0,
        sender=lambda events: (captured_mouse.extend(events), len(events))[1],
    )
    keyboard = KeyboardController(
        sender=lambda events: (captured_kb.extend(events), len(events))[1],
    )
    config = AgentConfig(host="127.0.0.1", port=0)
    server = LocalWebSocketServer(config=config, mouse=mouse, keyboard=keyboard)
    await server.start()

    try:
        uri = f"ws://127.0.0.1:{server.port}"
        async with websockets.connect(uri) as ws:
            # Handshake
            await ws.send(json.dumps({"t": "hello", "v": 1, "name": "TestPhone"}))
            ack = json.loads(await ws.recv())
            assert ack["t"] == "hello_ack"
            assert ack["ok"] is True
            assert ack["v"] == 1

            # Ping / Pong
            await ws.send('{"t":"ping"}')
            pong = json.loads(await ws.recv())
            assert pong == {"t": "pong"}

            # Mouse move, scroll, keyboard combo, text, gesture, unknown type, malformed JSON, held button & key
            await ws.send('{"t":"move","dx":10,"dy":-5}')
            await ws.send('{"t":"scroll","dx":0,"dy":1}')
            await ws.send('{"t":"key","keys":["ctrl","c"]}')
            await ws.send('{"t":"text","v":"Hi"}')
            await ws.send('{"t":"gesture","g":"show_desktop"}')
            await ws.send('{"t":"unknown_future_msg","foo":"bar"}')
            await ws.send("{broken_json")
            await ws.send('{"t":"btn","b":"left","s":"down"}')
            await ws.send('{"t":"keyevt","k":"shift","s":"down"}')

            # Sync with a final ping to guarantee prior messages were processed
            await ws.send('{"t":"ping"}')
            assert json.loads(await ws.recv()) == {"t": "pong"}

        # Wait briefly for server disconnect cleanup to release held left button & shift key
        await asyncio.sleep(0.05)

        flags = [e.mi.dwFlags for e in captured_mouse]
        assert MOUSEEVENTF_MOVE in flags
        assert MOUSEEVENTF_WHEEL in flags
        assert MOUSEEVENTF_LEFTDOWN in flags
        assert flags[-1] == MOUSEEVENTF_LEFTUP

        # Held shift key must be auto-released on disconnect!
        assert captured_kb[-1].ki.wVk == VK_MAP["shift"]
        assert (captured_kb[-1].ki.dwFlags & KEYEVENTF_KEYUP) != 0
    finally:
        await server.stop()
