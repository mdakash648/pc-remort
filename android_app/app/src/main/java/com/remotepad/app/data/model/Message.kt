package com.remotepad.app.data.model

import kotlinx.serialization.Serializable

const val PROTOCOL_VERSION = 1

enum class MouseButton(val wireValue: String) {
    LEFT("left"),
    MIDDLE("middle"),
    RIGHT("right"),
}

enum class ButtonState(val wireValue: String) {
    DOWN("down"),
    UP("up"),
}

enum class ModifierKey(val label: String, val wireValue: String) {
    CTRL("Ctrl", "ctrl"),
    WIN("Win", "win"),
    ALT("Alt", "alt"),
    SHIFT("Shift", "shift"),
}

enum class ModifierState {
    OFF,
    ACTIVE,
    LOCKED,
}

@Serializable
data class HelloMessage(
    val t: String = "hello",
    val v: Int = PROTOCOL_VERSION,
    val name: String? = null,
    val token: String? = null,
)

@Serializable
data class MoveMessage(
    val t: String = "move",
    val dx: Float,
    val dy: Float,
)

@Serializable
data class MoveAbsMessage(
    val t: String = "move_abs",
    val x: Float,
    val y: Float,
    val monitor: Int = 0,
)

@Serializable
data class ScreenStartMessage(
    val t: String = "screen_start",
    val quality: String = "auto",
    val fps: Int = 20,
    val max_width: Int = 1280,
    val monitor: Int = 0,
    val prefer_p2p: Boolean = true,
    val hide_cursor: Boolean = false,
)

@Serializable
data class ScreenStopMessage(
    val t: String = "screen_stop",
)

@Serializable
data class ScreenConfigMessage(
    val t: String = "screen_cfg",
    val quality: String? = null,
    val fps: Int? = null,
    val max_width: Int? = null,
    val monitor: Int? = null,
    val prefer_p2p: Boolean? = null,
    val hide_cursor: Boolean? = null,
)

@Serializable
data class ScreenAckMessage(
    val t: String = "screen_ack",
    val seq: Int,
)

@Serializable
data class ButtonMessage(
    val t: String = "btn",
    val b: String,
    val s: String,
)

@Serializable
data class ClickMessage(
    val t: String = "click",
    val b: String,
    val n: Int = 1,
)

@Serializable
data class ScrollMessage(
    val t: String = "scroll",
    val dx: Float,
    val dy: Float,
)

@Serializable
data class KeyComboMessage(
    val t: String = "key",
    val keys: List<String>,
)

@Serializable
data class KeyEventMessage(
    val t: String = "keyevt",
    val k: String,
    val s: String,
)

@Serializable
data class TextMessage(
    val t: String = "text",
    val v: String,
)

@Serializable
data class PasteMessage(
    val t: String = "paste",
    val v: String,
)

@Serializable
data class GestureMessage(
    val t: String = "gesture",
    val g: String,
)

@Serializable
data class MediaMessage(
    val t: String = "media",
    val a: String,
)

@Serializable
data class PowerMessage(
    val t: String = "power",
    val a: String,
)

@Serializable
data class VolumeSetMessage(
    val t: String = "vol_set",
    val v: Float,
)

@Serializable
data class VolumeSyncMessage(
    val t: String = "vol_sync",
    val enable: Boolean,
)

@Serializable
data class PingMessage(
    val t: String = "ping",
)

@Serializable
data class RelayRegisterMessage(
    val t: String = "relay_register",
    val role: String = "controller",
    val deviceId: String,
    val token: String,
    val name: String? = null,
)

@Serializable
data class P2PEndpoint(
    val ip: String,
    val port: Int,
)

@Serializable
data class P2POfferMessage(
    val t: String = "p2p_offer",
    val sid: String,
    val udp_port: Int,
    val endpoints: List<P2PEndpoint>,
)

@Serializable
data class ConnectedDeviceInfo(
    val id: String = "",
    val name: String = "",
    val transport: String = "",
    val ip: String = "",
)

sealed interface IncomingMessage {
    data class HelloAck(
        val version: Int,
        val ok: Boolean,
        val pcName: String,
        val filePort: Int = 8767,
        val micPort: Int = 8768,
        val micDevice: String = "",
        val connectedCount: Int = 1,
    ) : IncomingMessage
    data class DevicesUpdate(
        val count: Int,
        val devices: List<ConnectedDeviceInfo> = emptyList(),
    ) : IncomingMessage
    data class RelayReady(
        val pcName: String,
        val udpPort: Int? = null,
    ) : IncomingMessage
    data class RelayError(
        val code: String,
        val message: String,
    ) : IncomingMessage
    data class RelayPeer(
        val online: Boolean,
    ) : IncomingMessage
    data class P2PAnswer(
        val sid: String,
        val endpoints: List<P2PEndpoint>,
    ) : IncomingMessage
    data class VolumeState(
        val level: Float,
        val muted: Boolean,
    ) : IncomingMessage
    data object Pong : IncomingMessage
    data class Unknown(val type: String) : IncomingMessage
}
