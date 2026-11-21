package com.remotepad.app.network

import com.remotepad.app.data.model.ButtonMessage
import com.remotepad.app.data.model.ButtonState
import com.remotepad.app.data.model.ClickMessage
import com.remotepad.app.data.model.ConnectedDeviceInfo
import com.remotepad.app.data.model.GestureMessage
import com.remotepad.app.data.model.HelloMessage
import com.remotepad.app.data.model.IncomingMessage
import com.remotepad.app.data.model.KeyComboMessage
import com.remotepad.app.data.model.KeyEventMessage
import com.remotepad.app.data.model.MediaMessage
import com.remotepad.app.data.model.MouseButton
import com.remotepad.app.data.model.P2PEndpoint
import com.remotepad.app.data.model.P2POfferMessage
import com.remotepad.app.data.model.PROTOCOL_VERSION
import com.remotepad.app.data.model.PasteMessage
import com.remotepad.app.data.model.PingMessage
import com.remotepad.app.data.model.PowerMessage
import com.remotepad.app.data.model.RelayRegisterMessage
import com.remotepad.app.data.model.ScreenAckMessage
import com.remotepad.app.data.model.ScreenConfigMessage
import com.remotepad.app.data.model.ScreenStartMessage
import com.remotepad.app.data.model.ScreenStopMessage
import com.remotepad.app.data.model.TextMessage
import com.remotepad.app.data.model.VolumeSyncMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.round

object Protocol {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encodeHello(deviceName: String? = null, token: String? = null): String =
        json.encodeToString(HelloMessage(v = PROTOCOL_VERSION, name = deviceName, token = token))

    fun encodeMove(dx: Float, dy: Float): String {
        val rdx = round2(dx)
        val rdy = round2(dy)
        return "{\"t\":\"move\",\"dx\":$rdx,\"dy\":$rdy}"
    }

    fun encodeMoveAbs(normX: Float, normY: Float, monitor: Int = 0): String {
        val cx = round4(normX.coerceIn(0f, 1f))
        val cy = round4(normY.coerceIn(0f, 1f))
        val mon = monitor.coerceAtLeast(0)
        return "{\"t\":\"move_abs\",\"x\":$cx,\"y\":$cy,\"monitor\":$mon}"
    }

    fun encodeScreenStart(
        quality: String = "auto",
        fps: Int = 20,
        maxWidth: Int = 1280,
        monitor: Int = 0,
        preferP2p: Boolean = true,
        hideCursor: Boolean = false,
    ): String = json.encodeToString(
        ScreenStartMessage(
            quality = quality,
            fps = fps.coerceIn(1, 30),
            max_width = maxWidth.coerceIn(320, 1920),
            monitor = monitor.coerceAtLeast(0),
            prefer_p2p = preferP2p,
            hide_cursor = hideCursor,
        )
    )

    fun encodeScreenStop(): String = json.encodeToString(ScreenStopMessage())

    fun encodeScreenConfig(
        quality: String? = null,
        fps: Int? = null,
        maxWidth: Int? = null,
        monitor: Int? = null,
        preferP2p: Boolean? = null,
        hideCursor: Boolean? = null,
    ): String = json.encodeToString(
        ScreenConfigMessage(
            quality = quality,
            fps = fps?.coerceIn(1, 30),
            max_width = maxWidth?.coerceIn(320, 1920),
            monitor = monitor?.coerceAtLeast(0),
            prefer_p2p = preferP2p,
            hide_cursor = hideCursor,
        )
    )

    fun encodeScreenAck(seq: Int): String = json.encodeToString(ScreenAckMessage(seq = seq))

    fun encodeButton(button: MouseButton, state: ButtonState): String =
        json.encodeToString(ButtonMessage(b = button.wireValue, s = state.wireValue))

    fun encodeClick(button: MouseButton, count: Int = 1): String =
        json.encodeToString(ClickMessage(b = button.wireValue, n = count.coerceIn(1, 3)))

    fun encodeScroll(dx: Float, dy: Float): String {
        val rdx = round2(dx)
        val rdy = round2(dy)
        return "{\"t\":\"scroll\",\"dx\":$rdx,\"dy\":$rdy}"
    }

    fun encodeKeyCombo(keys: List<String>): String =
        json.encodeToString(KeyComboMessage(keys = keys))

    fun encodeKeyEvent(key: String, state: ButtonState): String =
        json.encodeToString(KeyEventMessage(k = key, s = state.wireValue))

    fun encodeText(value: String): String =
        json.encodeToString(TextMessage(v = value))

    fun encodePaste(value: String): String =
        json.encodeToString(PasteMessage(v = value))

    fun encodeGesture(gesture: String): String =
        json.encodeToString(GestureMessage(g = gesture))

    fun encodeMedia(action: String): String =
        json.encodeToString(MediaMessage(a = action))

    fun encodePower(action: String): String =
        json.encodeToString(PowerMessage(a = action))

    fun encodeVolumeSet(level: Float): String {
        val clamped = round2(level.coerceIn(0f, 1f))
        return "{\"t\":\"vol_set\",\"v\":$clamped}"
    }

    fun encodeVolumeSync(enable: Boolean): String =
        json.encodeToString(VolumeSyncMessage(enable = enable))

    fun encodeRelayRegister(deviceId: String, token: String, deviceName: String? = null): String =
        json.encodeToString(
            RelayRegisterMessage(
                deviceId = deviceId,
                token = token,
                name = deviceName,
            )
        )

    fun encodeP2POffer(sid: String, udpPort: Int, endpoints: List<P2PEndpoint>): String =
        json.encodeToString(
            P2POfferMessage(
                sid = sid,
                udp_port = udpPort,
                endpoints = endpoints,
            )
        )

    fun encodePing(): String = json.encodeToString(PingMessage())

    fun decodeIncoming(raw: String): IncomingMessage? {
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            val type = obj["t"]?.jsonPrimitive?.contentOrNull ?: return null
            when (type) {
                "hello_ack" -> {
                    val version = obj["v"]?.jsonPrimitive?.intOrNull ?: PROTOCOL_VERSION
                    val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Windows PC"
                    val filePort = obj["file_port"]?.jsonPrimitive?.intOrNull ?: 8767
                    val micPort = obj["mic_port"]?.jsonPrimitive?.intOrNull ?: 8768
                    val micDevice = obj["mic_device"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val connectedCount = obj["connected_count"]?.jsonPrimitive?.intOrNull ?: 1
                    IncomingMessage.HelloAck(
                        version = version,
                        ok = ok,
                        pcName = name,
                        filePort = filePort,
                        micPort = micPort,
                        micDevice = micDevice,
                        connectedCount = connectedCount,
                    )
                }
                "devices_update" -> {
                    val count = obj["count"]?.jsonPrimitive?.intOrNull ?: 1
                    val devices = obj["devices"]?.jsonArray?.mapNotNull { elem ->
                        runCatching {
                            val dObj = elem.jsonObject
                            ConnectedDeviceInfo(
                                id = dObj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                name = dObj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                transport = dObj["transport"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                ip = dObj["ip"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            )
                        }.getOrNull()
                    }.orEmpty()
                    IncomingMessage.DevicesUpdate(count = count, devices = devices)
                }
                "relay_ready" -> {
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Windows PC"
                    val udpPort = obj["udpPort"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..65535 }
                    IncomingMessage.RelayReady(pcName = name, udpPort = udpPort)
                }
                "relay_error" -> {
                    val code = obj["code"]?.jsonPrimitive?.contentOrNull ?: "error"
                    val message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "Relay error"
                    IncomingMessage.RelayError(code = code, message = message)
                }
                "relay_peer" -> {
                    val online = obj["online"]?.jsonPrimitive?.booleanOrNull ?: false
                    IncomingMessage.RelayPeer(online = online)
                }
                "p2p_answer" -> {
                    val sid = obj["sid"]?.jsonPrimitive?.contentOrNull ?: return null
                    val eps = obj["endpoints"]?.jsonArray?.mapNotNull { elem ->
                        runCatching {
                            val epObj = elem.jsonObject
                            val ip = epObj["ip"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                            val port = epObj["port"]?.jsonPrimitive?.intOrNull ?: -1
                            if (ip.isNotEmpty() && port in 1..65535) P2PEndpoint(ip, port) else null
                        }.getOrNull()
                    }.orEmpty()
                    IncomingMessage.P2PAnswer(sid = sid, endpoints = eps)
                }
                "vol_state" -> {
                    val level = obj["v"]?.jsonPrimitive?.floatOrNull?.coerceIn(0f, 1f) ?: return null
                    val muted = obj["muted"]?.jsonPrimitive?.booleanOrNull ?: false
                    IncomingMessage.VolumeState(level = level, muted = muted)
                }
                "pong" -> IncomingMessage.Pong
                else -> IncomingMessage.Unknown(type)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun round2(value: Float): Float {
        if (!value.isFinite()) return 0f
        val rounded = round(value * 100f) / 100f
        return if (rounded == 0f) 0f else rounded
    }

    private fun round4(value: Float): Float {
        if (!value.isFinite()) return 0f
        val rounded = round(value * 10000f) / 10000f
        return if (rounded == 0f) 0f else rounded
    }
}
