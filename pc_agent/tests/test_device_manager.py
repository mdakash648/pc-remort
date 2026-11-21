"""Tests for DeviceManager and multi-client device management."""

from __future__ import annotations

import time

from config import AgentConfig
from core.device_manager import ConnectedDevice, DeviceManager


def test_device_registration_and_unregister() -> None:
    config = AgentConfig()
    dm = DeviceManager(config)

    notifications = []
    dm.add_listener(lambda: notifications.append(dm.get_count()))

    d1 = ConnectedDevice(
        id="lan:192.168.1.10:5000",
        name="Pixel 8",
        transport="Local Wi-Fi",
        ip="192.168.1.10",
        connected_at=time.time(),
    )
    d2 = ConnectedDevice(
        id="relay:c_abc123",
        name="Galaxy S24",
        transport="VPS Relay",
        ip="100.64.0.5",
        connected_at=time.time(),
        is_relay=True,
        relay_cid="c_abc123",
    )

    assert dm.register_device(d1) is True
    assert dm.register_device(d2) is True
    assert dm.get_count() == 2
    assert len(notifications) >= 2

    assert dm.get_device(d1.id) == d1
    assert dm.get_device(d2.id) == d2
    assert dm.get_device("non_existent") is None

    devs = dm.get_devices()
    assert len(devs) == 2
    assert {d.name for d in devs} == {"Pixel 8", "Galaxy S24"}

    dm.unregister_device(d1.id)
    assert dm.get_count() == 1
    assert dm.get_devices()[0].id == d2.id


def test_device_blocking() -> None:
    config = AgentConfig()
    dm = DeviceManager(config)

    disconnected = []
    d1 = ConnectedDevice(
        id="lan:192.168.1.10:5000",
        name="Pixel 8",
        transport="Local Wi-Fi",
        ip="192.168.1.10",
        connected_at=time.time(),
        disconnect_fn=lambda: disconnected.append("d1"),
    )

    assert dm.register_device(d1) is True
    assert dm.block_device(d1.id) is True

    # Device was disconnected
    assert disconnected == ["d1"]
    assert dm.get_count() == 0

    # Device is now blocked
    assert dm.is_blocked(name="Pixel 8") is True
    assert dm.is_blocked(ip="192.168.1.10") is True

    # New registration attempt from same device is rejected
    d_retry = ConnectedDevice(
        id="lan:192.168.1.10:5001",
        name="Pixel 8",
        transport="Local Wi-Fi",
        ip="192.168.1.10",
        connected_at=time.time(),
    )
    assert dm.register_device(d_retry) is False

    # Unblock
    assert dm.unblock_device("Pixel 8") is True
    assert dm.is_blocked(name="Pixel 8") is False
    assert dm.register_device(d_retry) is True
    assert dm.get_count() == 1
