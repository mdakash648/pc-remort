"""Central registry and manager for connected remote devices across LAN and Relay."""

from __future__ import annotations

import logging
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from config import AgentConfig

logger = logging.getLogger(__name__)


@dataclass
class ConnectedDevice:
    id: str                  # e.g. "lan:192.168.1.15:52341" or "relay:c_abc123"
    name: str                # e.g. "Samsung Galaxy S24"
    transport: str           # "Local Wi-Fi" or "VPS Relay"
    ip: str                  # "192.168.1.15"
    connected_at: float      # epoch seconds
    disconnect_fn: Callable[[], None] | None = None
    is_relay: bool = False
    relay_cid: str = ""

    @property
    def uptime_seconds(self) -> int:
        return max(0, int(time.time() - self.connected_at))


class DeviceManager:
    """Thread-safe registry of currently connected devices and persistent blocklist."""

    def __init__(self, config: AgentConfig) -> None:
        self.config = config
        self._devices: dict[str, ConnectedDevice] = {}
        self._listeners: list[Callable[[], None]] = []
        self._broadcast_fn: Callable[[dict[str, Any]], None] | None = None

    def set_broadcast_fn(self, fn: Callable[[dict[str, Any]], None]) -> None:
        self._broadcast_fn = fn

    def add_listener(self, listener: Callable[[], None]) -> None:
        self._listeners.append(listener)

    def _notify(self) -> None:
        for listener in list(self._listeners):
            try:
                listener()
            except Exception as exc:
                logger.debug("Device listener error: %s", exc)
        self.broadcast_devices_update()

    def get_devices(self) -> list[ConnectedDevice]:
        return list(self._devices.values())

    def get_device(self, device_id: str) -> ConnectedDevice | None:
        return self._devices.get(device_id)

    def get_count(self) -> int:
        return len(self._devices)

    def is_blocked(self, name: str = "", ip: str = "", dev_id: str = "") -> bool:
        return self.config.is_device_blocked(name=name, ip=ip, dev_id=dev_id)

    def register_device(self, device: ConnectedDevice) -> bool:
        if self.is_blocked(name=device.name, ip=device.ip, dev_id=device.id):
            logger.info("Rejected connection from blocked device %s (%s)", device.name, device.ip)
            return False
        self._devices[device.id] = device
        logger.info(
            "Device registered: %s (%s via %s, total=%d)",
            device.name,
            device.id,
            device.transport,
            len(self._devices),
        )
        self._notify()
        return True

    def unregister_device(self, device_id: str) -> None:
        if device_id in self._devices:
            dev = self._devices.pop(device_id)
            logger.info("Device unregistered: %s (%s, remaining=%d)", dev.name, device_id, len(self._devices))
            self._notify()

    def disconnect_device(self, device_id: str) -> bool:
        dev = self._devices.get(device_id)
        if dev is None:
            return False
        logger.info("Disconnecting device by user request: %s (%s)", dev.name, device_id)
        fn = dev.disconnect_fn
        if fn is not None:
            try:
                fn()
            except Exception as exc:
                logger.warning("Error disconnecting device %s: %s", device_id, exc)
        self.unregister_device(device_id)
        return True

    def block_device(self, device_id: str) -> bool:
        dev = self._devices.get(device_id)
        if dev is None:
            return False
        logger.info("Blocking device by user request: %s (%s, IP=%s)", dev.name, device_id, dev.ip)
        self.config.block_device(name=dev.name, ip=dev.ip, dev_id=device_id)
        self.disconnect_device(device_id)
        return True

    def unblock_device(self, identifier: str) -> bool:
        ok = self.config.unblock_device(identifier)
        if ok:
            logger.info("Unblocked device identifier: %s", identifier)
            self._notify()
        return ok

    def build_devices_payload(self) -> dict[str, Any]:
        dev_list = [
            {
                "id": d.id,
                "name": d.name,
                "transport": d.transport,
                "ip": d.ip,
                "connected_at": int(d.connected_at),
                "uptime_s": d.uptime_seconds,
            }
            for d in self._devices.values()
        ]
        return {
            "t": "devices_update",
            "count": len(dev_list),
            "devices": dev_list,
        }

    def broadcast_devices_update(self) -> None:
        if self._broadcast_fn is not None:
            try:
                self._broadcast_fn(self.build_devices_payload())
            except Exception as exc:
                logger.debug("Failed to broadcast devices update: %s", exc)
