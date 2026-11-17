"""Tests for AgentRuntime hot-reload, relay validation, and Windows OS integration."""

from __future__ import annotations

import sys
import threading
import uuid
from pathlib import Path

import pytest

from config import AgentConfig
from core.runtime import AgentRuntime, generate_relay_token, validate_relay_settings
from core.windows_integration import (
    SingleInstanceGuard,
    build_autostart_task_xml,
    get_resource_path,
    is_admin,
    is_autostart_enabled,
    set_autostart_enabled,
)


def test_validate_relay_settings_valid_cases() -> None:
    assert validate_relay_settings(True, "ws://182.255.88.108:8770", "akash-pc", "rpad-12345678") is None
    assert validate_relay_settings(True, "wss://relay.example.com/ws", "my.PC_01", "a" * 8) is None
    # When disabled, empty/invalid fields are allowed
    assert validate_relay_settings(False, "", "", "") is None


def test_validate_relay_settings_invalid_cases() -> None:
    assert validate_relay_settings(True, "", "akash-pc", "rpad-12345678") is not None
    assert validate_relay_settings(True, "http://1.2.3.4:8770", "akash-pc", "rpad-12345678") is not None
    assert validate_relay_settings(True, "ws://", "akash-pc", "rpad-12345678") is not None
    assert validate_relay_settings(True, "ws://1.2.3.4:8770", "ab", "rpad-12345678") is not None
    assert validate_relay_settings(True, "ws://1.2.3.4:8770", "bad id!", "rpad-12345678") is not None
    assert validate_relay_settings(True, "ws://1.2.3.4:8770", "akash-pc", "short") is not None


def test_generate_relay_token_format_and_uniqueness() -> None:
    t1 = generate_relay_token()
    t2 = generate_relay_token()
    assert t1.startswith("rpad-")
    assert len(t1) == 21
    assert t1 != t2


def test_agent_runtime_start_hot_reload_and_stop(tmp_path: Path) -> None:
    config_file = tmp_path / "config.json"
    cfg = AgentConfig(
        host="127.0.0.1",
        port=0,
        discovery_enabled=False,
        file_transfer_enabled=False,
        mic_enabled=False,
        relay_enabled=False,
        relay_url="ws://127.0.0.1:19999",
        relay_device_id="initial-pc",
        relay_token="initial-token-123",
    )
    runtime = AgentRuntime(cfg, config_path=config_file)
    runtime.start(timeout=5.0)
    try:
        st1 = runtime.get_status()
        assert st1.running is True
        assert st1.ws_port > 0
        assert st1.relay_state == "disabled"
        assert st1.relay_device_id == "initial-pc"

        # Hot-update relay settings (enable + change URL, PC ID, Token)
        runtime.update_relay_settings(
            relay_enabled=True,
            relay_url="ws://127.0.0.1:19998",
            relay_device_id="new-vps-pc",
            relay_token="rpad-abcdef0123456789",
            persist=True,
        )
        assert config_file.exists()
        reloaded = AgentConfig.load(config_file)
        assert reloaded.relay_enabled is True
        assert reloaded.relay_url == "ws://127.0.0.1:19998"
        assert reloaded.relay_device_id == "new-vps-pc"
        assert reloaded.relay_token == "rpad-abcdef0123456789"

        st2 = runtime.get_status()
        assert st2.relay_enabled is True
        assert st2.relay_url == "ws://127.0.0.1:19998"
        assert st2.relay_device_id == "new-vps-pc"
        assert st2.relay_token == "rpad-abcdef0123456789"
        assert st2.relay_state in ("connecting", "error")

        # Invalid settings must raise ValueError without overwriting config
        with pytest.raises(ValueError):
            runtime.update_relay_settings(
                relay_enabled=True,
                relay_url="not-a-ws-url",
                relay_device_id="new-vps-pc",
                relay_token="rpad-abcdef0123456789",
                persist=True,
            )
        assert runtime.get_status().relay_url == "ws://127.0.0.1:19998"

        # Disable relay again
        runtime.update_relay_settings(
            relay_enabled=False,
            relay_url="ws://127.0.0.1:19998",
            relay_device_id="new-vps-pc",
            relay_token="rpad-abcdef0123456789",
            persist=True,
        )
        assert runtime.get_status().relay_state == "disabled"
    finally:
        runtime.stop(timeout=5.0)
    assert runtime.is_running is False


def test_resource_path_finds_app_icon() -> None:
    ico = get_resource_path("assets/app_icon.ico")
    assert ico.exists()


def test_build_autostart_task_xml_is_valid_elevated_logon_task() -> None:
    import xml.etree.ElementTree as ET

    xml_str = build_autostart_task_xml(
        executable=r"C:\Program Files\RemotePad\RemotePad.exe",
        arguments="--minimized",
        user_id=r"MYPC\Akash & Co",
    )
    root = ET.fromstring(xml_str)
    ns = {"t": "http://schemas.microsoft.com/windows/2004/02/mit/task"}
    assert root.findtext(".//t:RunLevel", namespaces=ns) == "HighestAvailable"
    assert root.findtext(".//t:DisallowStartIfOnBatteries", namespaces=ns) == "false"
    assert root.findtext(".//t:StopIfGoingOnBatteries", namespaces=ns) == "false"
    assert root.findtext(".//t:ExecutionTimeLimit", namespaces=ns) == "PT0S"
    assert root.findtext(".//t:Command", namespaces=ns) == r"C:\Program Files\RemotePad\RemotePad.exe"
    assert root.findtext(".//t:Arguments", namespaces=ns) == "--minimized"
    assert root.findtext(".//t:Principal/t:UserId", namespaces=ns) == r"MYPC\Akash & Co"
    assert isinstance(is_admin(), bool)


@pytest.mark.skipif(sys.platform != "win32", reason="Windows-only registry & mutex tests")
def test_single_instance_guard_and_activation_event() -> None:
    suffix = uuid.uuid4().hex
    mutex = f"Local\\RemotePad_Test_Mutex_{suffix}"
    event = f"Local\\RemotePad_Test_Event_{suffix}"

    primary = SingleInstanceGuard(mutex_name=mutex, event_name=event)
    secondary = SingleInstanceGuard(mutex_name=mutex, event_name=event)
    activated = threading.Event()
    try:
        assert primary.acquire() is True
        primary.start_activation_listener(activated.set)

        assert secondary.acquire() is False
        assert secondary.signal_existing_instance() is True
        assert activated.wait(timeout=2.0) is True
    finally:
        secondary.close()
        primary.close()


@pytest.mark.skipif(sys.platform != "win32", reason="Windows-only registry test")
def test_windows_autostart_registry_roundtrip() -> None:
    test_val_name = f"RemotePad_Test_{uuid.uuid4().hex[:8]}"
    try:
        assert is_autostart_enabled(value_name=test_val_name) is False
        assert set_autostart_enabled(True, command='"C:\\Fake\\RemotePad.exe" --minimized', value_name=test_val_name) is True
        assert is_autostart_enabled(value_name=test_val_name) is True
        assert set_autostart_enabled(False, value_name=test_val_name) is True
        assert is_autostart_enabled(value_name=test_val_name) is False
    finally:
        set_autostart_enabled(False, value_name=test_val_name)
