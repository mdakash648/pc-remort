"""Tests for AgentRuntime hot-reload, relay validation, and Windows OS integration."""

from __future__ import annotations

import sys
import threading
import uuid
from pathlib import Path

import pytest

from config import AgentConfig, sync_config_to_shared
from core.runtime import AgentRuntime, generate_relay_token, validate_relay_settings
from core.service_ipc import RemoteAgentProxy, ServiceIpcServer
from core.windows_integration import (
    SingleInstanceGuard,
    build_autostart_task_xml,
    build_boot_service_task_xml,
    get_resource_path,
    is_admin,
    is_autostart_enabled,
    is_system,
    set_autostart_enabled,
)
from input.keyboard import _encode_ascii_vk_inputs


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


def test_build_boot_service_task_xml_is_valid_system_boot_task() -> None:
    import xml.etree.ElementTree as ET

    xml_str = build_boot_service_task_xml(
        executable=r"C:\Program Files\RemotePad\RemotePad.exe",
        arguments="--service-supervisor",
    )
    root = ET.fromstring(xml_str)
    ns = {"t": "http://schemas.microsoft.com/windows/2004/02/mit/task"}
    assert root.find(".//t:Triggers/t:BootTrigger", namespaces=ns) is not None
    assert root.findtext(".//t:Triggers/t:BootTrigger/t:Enabled", namespaces=ns) == "true"
    assert root.findtext(".//t:Principal/t:UserId", namespaces=ns) == "S-1-5-18"
    assert root.findtext(".//t:RunLevel", namespaces=ns) == "HighestAvailable"
    assert root.findtext(".//t:DisallowStartIfOnBatteries", namespaces=ns) == "false"
    assert root.findtext(".//t:StopIfGoingOnBatteries", namespaces=ns) == "false"
    assert root.findtext(".//t:ExecutionTimeLimit", namespaces=ns) == "PT0S"
    assert root.findtext(".//t:RestartOnFailure/t:Interval", namespaces=ns) == "PT1M"
    assert root.findtext(".//t:RestartOnFailure/t:Count", namespaces=ns) == "3"
    assert root.findtext(".//t:Command", namespaces=ns) == r"C:\Program Files\RemotePad\RemotePad.exe"
    assert root.findtext(".//t:Arguments", namespaces=ns) == "--service-supervisor"
    assert isinstance(is_system(), bool)


def test_service_ipc_server_and_proxy_roundtrip(tmp_path: Path) -> None:
    config_file = tmp_path / "shared_config.json"
    state_file = tmp_path / "service_ipc.json"
    shutdown_called = threading.Event()

    cfg = AgentConfig(
        host="127.0.0.1",
        port=0,
        discovery_enabled=False,
        file_transfer_enabled=False,
        mic_enabled=False,
        relay_enabled=False,
        relay_url="ws://127.0.0.1:19999",
        relay_device_id="boot-pc",
        relay_token="rpad-boot12345678",
    )
    runtime = AgentRuntime(cfg, config_path=config_file)
    runtime.start(timeout=5.0)
    ipc = ServiceIpcServer(
        runtime=runtime,
        state_path=state_file,
        on_shutdown_requested=shutdown_called.set,
        running_as_system=True,
    )
    ipc.start(timeout=5.0)
    try:
        assert state_file.exists()
        assert ipc.port > 0

        proxy = RemoteAgentProxy.connect(state_path=state_file, timeout=2.0)
        assert proxy is not None
        assert proxy.is_service_backed is True
        assert proxy.running_as_system is True

        st1 = proxy.get_status()
        assert st1.running is True
        assert st1.ws_port > 0
        assert st1.relay_device_id == "boot-pc"
        assert st1.relay_state == "disabled"

        # Update relay settings via IPC -> persisted and live in runtime
        proxy.update_relay_settings(
            relay_enabled=True,
            relay_url="ws://127.0.0.1:19997",
            relay_device_id="boot-pc-updated",
            relay_token="rpad-abcdef1234567890",
            persist=True,
        )
        reloaded = AgentConfig.load(config_file)
        assert reloaded.relay_device_id == "boot-pc-updated"
        assert reloaded.relay_enabled is True
        assert proxy.get_status().relay_device_id == "boot-pc-updated"

        # Validation error propagates as ValueError
        with pytest.raises(ValueError):
            proxy.update_relay_settings(
                relay_enabled=True,
                relay_url="http://invalid-scheme",
                relay_device_id="boot-pc-updated",
                relay_token="rpad-abcdef1234567890",
                persist=True,
            )

        # Reconnect command succeeds
        proxy.reconnect_relay()

        # Bad token is rejected
        bad_proxy = RemoteAgentProxy(
            host="127.0.0.1",
            port=ipc.port,
            token="wrong-token",
            state_path=tmp_path / "missing.json",
        )
        with pytest.raises(RuntimeError):
            bad_proxy._fetch_status(timeout=1.0)

        # proxy.stop() detaches tray without stopping the background service
        proxy.stop()
        assert not shutdown_called.is_set()

        # Explicit shutdown_service() signals shutdown callback
        assert proxy.shutdown_service(timeout=2.0) is True
        assert shutdown_called.wait(timeout=2.0) is True
    finally:
        ipc.stop(timeout=3.0)
        runtime.stop(timeout=5.0)
    assert not state_file.exists()


def test_sync_config_to_shared_copies_user_config(tmp_path: Path) -> None:
    user_cfg = tmp_path / "user" / "config.json"
    shared_cfg = tmp_path / "shared" / "config.json"

    cfg = AgentConfig(relay_device_id="my-lockscreen-pc", relay_token="rpad-9999888877776666")
    cfg.save(user_cfg)

    sync_config_to_shared(user_path=user_cfg, shared_path=shared_cfg)
    assert shared_cfg.exists()
    loaded = AgentConfig.load(shared_cfg)
    assert loaded.relay_device_id == "my-lockscreen-pc"
    assert loaded.relay_token == "rpad-9999888877776666"


def test_akash_pc_id_persists_across_load(tmp_path: Path) -> None:
    cfg_file = tmp_path / "config.json"
    cfg = AgentConfig(relay_device_id="akash-pc", relay_token="@k@$h@ndn@zmul")
    cfg.save(cfg_file)

    loaded = AgentConfig.load(cfg_file)
    assert loaded.relay_device_id == "akash-pc"
    assert loaded.relay_token == "@k@$h@ndn@zmul"


def test_sync_config_preserves_configured_shared_when_user_is_empty(tmp_path: Path) -> None:
    user_cfg = tmp_path / "user" / "config.json"
    shared_cfg = tmp_path / "shared" / "config.json"

    # User config starts empty (e.g. fresh launch or unconfigured AppData)
    AgentConfig().save(user_cfg)
    # Shared config was configured by service or previous run
    AgentConfig(relay_device_id="akash-pc", relay_token="@k@$h@ndn@zmul").save(shared_cfg)

    # Syncing must NOT overwrite shared with empty user config; it must backfill user config
    sync_config_to_shared(user_path=user_cfg, shared_path=shared_cfg)

    reloaded_user = AgentConfig.load(user_cfg)
    reloaded_shared = AgentConfig.load(shared_cfg)
    assert reloaded_user.relay_device_id == "akash-pc"
    assert reloaded_shared.relay_device_id == "akash-pc"
    assert reloaded_user.relay_token == "@k@$h@ndn@zmul"


@pytest.mark.skipif(sys.platform != "win32", reason="Windows-only VkKeyScanW test")
def test_winlogon_ascii_vk_encoding() -> None:
    digit_events = _encode_ascii_vk_inputs("5")
    assert digit_events is not None
    assert len(digit_events) == 2
    assert digit_events[0].ki.wVk == 0x35

    upper_events = _encode_ascii_vk_inputs("A")
    assert upper_events is not None
    # Shift down, A down, A up, Shift up
    assert len(upper_events) == 4
    assert upper_events[0].ki.wVk == 0xA0
    assert upper_events[1].ki.wVk == 0x41

    # Non-ASCII Bangla falls back to KEYEVENTF_UNICODE (returns None here)
    assert _encode_ascii_vk_inputs("অ") is None
