package com.remotepad.app.ui.touchpad

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotepad.app.data.model.MouseButton
import com.remotepad.app.network.ConnectionState
import com.remotepad.app.network.DiscoveredPc
import com.remotepad.app.network.TransportMode
import com.remotepad.app.ui.keyboard.KeyboardPanel
import com.remotepad.app.ui.theme.RemoteColors
import kotlin.math.abs
import kotlin.math.hypot

@Composable
fun TouchpadScreen(
    viewModel: TouchpadViewModel,
    modifier: Modifier = Modifier,
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val host by viewModel.host.collectAsStateWithLifecycle()
    val port by viewModel.port.collectAsStateWithLifecycle()
    val relayUrl by viewModel.relayUrl.collectAsStateWithLifecycle()
    val relayDeviceId by viewModel.relayDeviceId.collectAsStateWithLifecycle()
    val relayToken by viewModel.relayToken.collectAsStateWithLifecycle()
    val autoConnect by viewModel.autoConnect.collectAsStateWithLifecycle()
    val discoveredPcs by viewModel.discoveredPcs.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val sensitivity by viewModel.sensitivity.collectAsStateWithLifecycle()
    val isAirMouseEnabled by viewModel.isAirMouseEnabled.collectAsStateWithLifecycle()
    val airMouseSpeed by viewModel.airMouseSpeed.collectAsStateWithLifecycle()
    val pcVolume by viewModel.pcVolume.collectAsStateWithLifecycle()
    val pcMuted by viewModel.pcMuted.collectAsStateWithLifecycle()
    val isMicEnabled by viewModel.isMicEnabled.collectAsStateWithLifecycle()
    val micLevel by viewModel.micLevel.collectAsStateWithLifecycle()
    val micError by viewModel.micError.collectAsStateWithLifecycle()
    val isKeyboardOpen by viewModel.isKeyboardOpen.collectAsStateWithLifecycle()
    val modifierStates by viewModel.modifierStates.collectAsStateWithLifecycle()

    val connectedState = connectionState as? ConnectionState.Connected
    val isConnected = connectedState != null
    val isConnecting = connectionState is ConnectionState.Connecting
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.setMicEnabled(true)
        } else {
            Toast.makeText(context, "Microphone permission is required for PC Mic", Toast.LENGTH_SHORT).show()
        }
    }

    fun handleToggleMic() {
        if (isMicEnabled) {
            viewModel.setMicEnabled(false)
            return
        }
        if (!isConnected) {
            Toast.makeText(context, "Connect to PC first", Toast.LENGTH_SHORT).show()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.setMicEnabled(true)
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(micError) {
        val err = micError
        if (!err.isNullOrBlank()) {
            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
        }
    }

    var activePanel by rememberSaveable { mutableStateOf(ControlPanelTab.CONNECTION) }

    // Sync PC volume ONLY while the Media panel is actively open on screen
    LaunchedEffect(activePanel) {
        viewModel.setMediaPanelOpen(activePanel == ControlPanelTab.MEDIA)
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.setMediaPanelOpen(false)
        }
    }

    // Auto-collapse connection drawer once connected; auto-open when disconnected
    LaunchedEffect(isConnected) {
        if (isConnected && activePanel == ControlPanelTab.CONNECTION) {
            activePanel = ControlPanelTab.NONE
        } else if (!isConnected) {
            activePanel = ControlPanelTab.CONNECTION
        }
    }

    fun togglePanel(tab: ControlPanelTab) {
        activePanel = if (activePanel == tab) ControlPanelTab.NONE else tab
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(RemoteColors.DeepBg)
            .imePadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Sleek Top Toolbar: [ Media | Power | PPT | Web ]  |  [ Mic | KB | 3D | PC ]
        TopRemoteToolbar(
            activePanel = activePanel,
            connectionState = connectionState,
            isKeyboardOpen = isKeyboardOpen,
            isAirMouseEnabled = isAirMouseEnabled,
            isMicEnabled = isMicEnabled,
            onSelectPanel = { tab -> togglePanel(tab) },
            onToggleMic = { handleToggleMic() },
            onToggleKeyboard = {
                if (isKeyboardOpen) {
                    keyboardController?.hide()
                }
                viewModel.toggleKeyboard()
            },
            onTapAirMouse3D = {
                if (!isAirMouseEnabled && viewModel.isGyroAvailable) {
                    viewModel.setAirMouseEnabled(true)
                    activePanel = ControlPanelTab.AIR_MOUSE
                } else if (activePanel != ControlPanelTab.AIR_MOUSE) {
                    activePanel = ControlPanelTab.AIR_MOUSE
                } else {
                    viewModel.setAirMouseEnabled(false)
                    activePanel = ControlPanelTab.NONE
                }
            },
            onToggleConnectionPanel = {
                togglePanel(ControlPanelTab.CONNECTION)
            },
        )

        // Expandable Control Panel Area (Media / Power / PPT / Web / 3D Air Mouse / Connection)
        when (activePanel) {
            ControlPanelTab.MEDIA -> {
                MediaControlPanel(
                    volumeLevel = pcVolume,
                    isMuted = pcMuted,
                    onSetVolume = viewModel::setPcVolume,
                    onMediaAction = viewModel::sendMediaAction,
                )
            }
            ControlPanelTab.POWER -> {
                PowerControlPanel(onPowerAction = viewModel::sendPowerAction)
            }
            ControlPanelTab.PPT -> {
                PptControlPanel(onKeyCombo = viewModel::sendKeyCombo)
            }
            ControlPanelTab.WEB -> {
                WebControlPanel(onKeyCombo = viewModel::sendKeyCombo)
            }
            ControlPanelTab.AIR_MOUSE -> {
                AirMouseControlPanel(
                    isGyroAvailable = viewModel.isGyroAvailable,
                    isEnabled = isAirMouseEnabled,
                    airMouseSpeed = airMouseSpeed,
                    touchpadSpeed = sensitivity,
                    onToggleEnabled = viewModel::toggleAirMouse,
                    onDecreaseAirSpeed = viewModel::decreaseAirMouseSpeed,
                    onIncreaseAirSpeed = viewModel::increaseAirMouseSpeed,
                    onAirSpeedChange = viewModel::onAirMouseSpeedChanged,
                    onTouchpadSpeedChange = viewModel::onSensitivityChanged,
                )
            }
            ControlPanelTab.CONNECTION -> {
                ConnectionDrawerPanel(
                    state = connectionState,
                    host = host,
                    port = port,
                    relayUrl = relayUrl,
                    relayDeviceId = relayDeviceId,
                    relayToken = relayToken,
                    autoConnect = autoConnect,
                    sensitivity = sensitivity,
                    discoveredPcs = discoveredPcs,
                    isScanning = isScanning,
                    isConnected = isConnected,
                    isConnecting = isConnecting,
                    onHostChange = viewModel::onHostChanged,
                    onPortChange = viewModel::onPortChanged,
                    onRelayUrlChange = viewModel::onRelayUrlChanged,
                    onRelayDeviceIdChange = viewModel::onRelayDeviceIdChanged,
                    onRelayTokenChange = viewModel::onRelayTokenChanged,
                    onAutoConnectChange = viewModel::onAutoConnectChanged,
                    onSensitivityChange = viewModel::onSensitivityChanged,
                    onRefreshScan = viewModel::refreshDiscovery,
                    onSelectDiscoveredPc = { pc ->
                        keyboardController?.hide()
                        viewModel.connectToDiscovered(pc)
                    },
                    onConnect = {
                        keyboardController?.hide()
                        viewModel.connect()
                    },
                    onConnectRelay = {
                        keyboardController?.hide()
                        viewModel.connectViaRelay()
                    },
                    onDisconnect = {
                        viewModel.setKeyboardOpen(false)
                        keyboardController?.hide()
                        viewModel.disconnect()
                    },
                )
            }
            ControlPanelTab.NONE -> Unit
        }

        // Live PC Microphone status bar (shown only while Phone Mic is ON)
        if (isMicEnabled) {
            val micDevice = connectedState?.micDevice.orEmpty()
            val hasVirtualMic = micDevice.isNotBlank()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (hasVirtualMic) RemoteColors.DangerRed.copy(alpha = 0.14f)
                        else RemoteColors.ConnectingAmber.copy(alpha = 0.14f)
                    )
                    .border(
                        1.dp,
                        if (hasVirtualMic) RemoteColors.DangerRed.copy(alpha = 0.65f)
                        else RemoteColors.ConnectingAmber.copy(alpha = 0.65f),
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (hasVirtualMic) RemoteColors.DangerRed else RemoteColors.ConnectingAmber),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (hasVirtualMic) "PC Mic Live → $micDevice"
                        else "Mic streaming — install VB-Cable on PC for Virtual Mic",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = RemoteColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(
                        progress = { micLevel },
                        color = if (hasVirtualMic) RemoteColors.NeonCyan else RemoteColors.ConnectingAmber,
                        trackColor = RemoteColors.DeepBg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 3.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                }
                Text(
                    text = "Stop",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = RemoteColors.DangerRed,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { viewModel.setMicEnabled(false) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        // Main Neon-Bordered Touchpad + Glowing Vertical Cyan Scroll Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TouchpadArea(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                isConnected = isConnected,
                isAirMouseEnabled = isAirMouseEnabled,
                airMouseSpeed = airMouseSpeed,
                compact = isKeyboardOpen || activePanel != ControlPanelTab.NONE,
                onMove = viewModel::onMoveDelta,
                onScroll = viewModel::onScrollDelta,
                onClick = viewModel::onClick,
                onButton = viewModel::onButton,
                onGesture = viewModel::sendGesture,
            )

            NeonScrollStrip(
                modifier = Modifier
                    .width(38.dp)
                    .fillMaxHeight(),
                onScroll = viewModel::onScrollDelta,
            )
        }

        // Either Keyboard Panel OR the 3 Neon-Bordered Mouse Buttons
        if (isKeyboardOpen) {
            KeyboardPanel(
                modifierStates = modifierStates,
                isMicEnabled = isMicEnabled,
                onToggleMic = { handleToggleMic() },
                onToggleModifier = viewModel::toggleModifier,
                onReleaseAllModifiers = viewModel::releaseAllModifiers,
                onSpecialKey = viewModel::sendSpecialKey,
                onKeyCombo = viewModel::sendKeyCombo,
                onTypeText = viewModel::sendTypedText,
                onPasteParagraph = viewModel::sendParagraphText,
                onClose = { viewModel.setKeyboardOpen(false) },
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MouseButtonPad(
                    label = "",
                    modifier = Modifier
                        .weight(1.6f)
                        .fillMaxHeight(),
                    onStateChange = { down -> viewModel.onButton(MouseButton.LEFT, down) },
                )
                MouseButtonPad(
                    label = "",
                    modifier = Modifier
                        .weight(0.68f)
                        .fillMaxHeight(),
                    onStateChange = { down -> viewModel.onButton(MouseButton.MIDDLE, down) },
                )
                MouseButtonPad(
                    label = "",
                    modifier = Modifier
                        .weight(1.6f)
                        .fillMaxHeight(),
                    onStateChange = { down -> viewModel.onButton(MouseButton.RIGHT, down) },
                )
            }
        }
    }
}

@Composable
private fun TopRemoteToolbar(
    activePanel: ControlPanelTab,
    connectionState: ConnectionState,
    isKeyboardOpen: Boolean,
    isAirMouseEnabled: Boolean,
    isMicEnabled: Boolean,
    onSelectPanel: (ControlPanelTab) -> Unit,
    onToggleMic: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onTapAirMouse3D: () -> Unit,
    onToggleConnectionPanel: () -> Unit,
) {
    val statusDotColor = when (connectionState) {
        is ConnectionState.Connected -> RemoteColors.ConnectedGreen
        is ConnectionState.Connecting -> RemoteColors.ConnectingAmber
        is ConnectionState.Error -> RemoteColors.DangerRed
        ConnectionState.Disconnected -> RemoteColors.TextMuted
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left 4 mode buttons: Media, Power, PPT, Web
        Row(
            modifier = Modifier.weight(1.25f),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarModeItem(
                label = "Media",
                selected = activePanel == ControlPanelTab.MEDIA,
                onClick = { onSelectPanel(ControlPanelTab.MEDIA) },
            ) { color ->
                MediaNoteIcon(color = color, modifier = Modifier.size(21.dp))
            }
            ToolbarModeItem(
                label = "Power",
                selected = activePanel == ControlPanelTab.POWER,
                onClick = { onSelectPanel(ControlPanelTab.POWER) },
            ) { color ->
                PowerSymbolIcon(color = color, modifier = Modifier.size(21.dp))
            }
            ToolbarModeItem(
                label = "PPT",
                selected = activePanel == ControlPanelTab.PPT,
                onClick = { onSelectPanel(ControlPanelTab.PPT) },
            ) { color ->
                PptSlideIcon(color = color, modifier = Modifier.size(21.dp))
            }
            ToolbarModeItem(
                label = "Web",
                selected = activePanel == ControlPanelTab.WEB,
                onClick = { onSelectPanel(ControlPanelTab.WEB) },
            ) { color ->
                WebGlobeIcon(color = color, modifier = Modifier.size(21.dp))
            }
        }

        // Thin vertical cyan divider (matching screenshots)
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(34.dp)
                .background(RemoteColors.NeonBorder.copy(alpha = 0.7f)),
        )

        // Right quick actions: Mic | Keyboard | 3D Air Mouse | Connection Status
        Row(
            modifier = Modifier
                .weight(1.12f)
                .padding(start = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Phone-to-PC Microphone ON/OFF icon
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (isMicEnabled) RemoteColors.DangerRed.copy(alpha = 0.22f)
                        else Color.Transparent
                    )
                    .border(
                        width = if (isMicEnabled) 1.dp else 0.dp,
                        color = if (isMicEnabled) RemoteColors.DangerRed else Color.Transparent,
                        shape = RoundedCornerShape(9.dp),
                    )
                    .clickable(onClick = onToggleMic),
                contentAlignment = Alignment.Center,
            ) {
                MicIcon(
                    color = if (isMicEnabled) RemoteColors.DangerRed else RemoteColors.TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }

            // Keyboard toggle icon
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isKeyboardOpen) RemoteColors.NeonCyan.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable(onClick = onToggleKeyboard),
                contentAlignment = Alignment.Center,
            ) {
                KeyboardMiniIcon(
                    color = if (isKeyboardOpen) RemoteColors.NeonCyan else RemoteColors.TextPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }

            // 3D Air Mouse toggle button
            val airActive = isAirMouseEnabled || activePanel == ControlPanelTab.AIR_MOUSE
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isAirMouseEnabled) RemoteColors.NeonCyan.copy(alpha = 0.22f)
                        else Color.Transparent
                    )
                    .border(
                        width = if (isAirMouseEnabled) 1.dp else 0.dp,
                        color = if (isAirMouseEnabled) RemoteColors.NeonCyan else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable(onClick = onTapAirMouse3D)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "3D",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (airActive) RemoteColors.NeonCyan else RemoteColors.TextPrimary,
                )
            }

            // Connection Status & Settings button
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (activePanel == ControlPanelTab.CONNECTION) RemoteColors.TileBg
                        else Color.Transparent
                    )
                    .border(
                        1.dp,
                        if (activePanel == ControlPanelTab.CONNECTION) RemoteColors.NeonCyan
                        else RemoteColors.NeonBorder.copy(alpha = 0.6f),
                        RoundedCornerShape(8.dp),
                    )
                    .clickable(onClick = onToggleConnectionPanel)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusDotColor),
                    )
                    val pcChipLabel = when (val st = connectionState) {
                        is ConnectionState.Connected -> when (st.transportMode) {
                            TransportMode.LAN -> "Wi-Fi"
                            TransportMode.P2P_DIRECT -> "P2P"
                            TransportMode.P2P_CONNECTING -> "P2P…"
                            TransportMode.VPS_RELAY -> "VPS"
                        }
                        else -> "PC"
                    }
                    Text(
                        text = pcChipLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = RemoteColors.TextPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolbarModeItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val tint = if (selected) RemoteColors.NeonCyan else RemoteColors.TextPrimary.copy(alpha = 0.85f)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        icon(tint)
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) RemoteColors.NeonCyan else RemoteColors.TextMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun ConnectionDrawerPanel(
    state: ConnectionState,
    host: String,
    port: String,
    relayUrl: String,
    relayDeviceId: String,
    relayToken: String,
    autoConnect: Boolean,
    sensitivity: Float,
    discoveredPcs: List<DiscoveredPc>,
    isScanning: Boolean,
    isConnected: Boolean,
    isConnecting: Boolean,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onRelayUrlChange: (String) -> Unit,
    onRelayDeviceIdChange: (String) -> Unit,
    onRelayTokenChange: (String) -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onRefreshScan: () -> Unit,
    onSelectDiscoveredPc: (DiscoveredPc) -> Unit,
    onConnect: () -> Unit,
    onConnectRelay: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var useInternetTab by rememberSaveable { mutableStateOf(false) }

    val (statusColor, statusText) = when (state) {
        is ConnectionState.Connected -> when (state.transportMode) {
            TransportMode.LAN -> RemoteColors.ConnectedGreen to "${state.pcName} • Local Wi-Fi"
            TransportMode.P2P_DIRECT -> {
                val rtt = state.p2pRttMs?.let { " • ${it}ms" }.orEmpty()
                RemoteColors.NeonCyan to "${state.pcName} • Direct P2P$rtt"
            }
            TransportMode.P2P_CONNECTING -> RemoteColors.ConnectedGreen to "${state.pcName} • Punching P2P..."
            TransportMode.VPS_RELAY -> RemoteColors.ConnectingAmber to "${state.pcName} • VPS Relay"
        }
        is ConnectionState.Connecting -> RemoteColors.ConnectingAmber to "Connecting ${state.target}..."
        is ConnectionState.Error -> RemoteColors.DangerRed to "Error: ${state.message}"
        ConnectionState.Disconnected -> {
            val label = if (isScanning && discoveredPcs.isEmpty()) "Searching LAN for PC..." else "Disconnected"
            RemoteColors.TextMuted to label
        }
    }

    NeonPanelContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = RemoteColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isConnected) {
                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RemoteColors.TileBg,
                            contentColor = RemoteColors.DangerRed,
                        ),
                    ) {
                        Text("Off", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Local Wi-Fi vs Internet (VPS Relay) switcher when disconnected
            if (!isConnected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(RemoteColors.DeepBg)
                        .border(1.dp, RemoteColors.NeonBorder, RoundedCornerShape(10.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (!useInternetTab) RemoteColors.NeonCyan else Color.Transparent)
                            .clickable { useInternetTab = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Local Wi-Fi",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (!useInternetTab) Color(0xFF001D2B) else RemoteColors.TextMuted,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (useInternetTab) RemoteColors.NeonCyan else Color.Transparent)
                            .clickable { useInternetTab = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Internet (VPS)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (useInternetTab) Color(0xFF001D2B) else RemoteColors.TextMuted,
                        )
                    }
                }
            }

            // Touchpad cursor speed slider (always handy in Connection/Settings drawer)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Speed ${"%.1f".format(sensitivity)}x",
                    style = MaterialTheme.typography.labelSmall,
                    color = RemoteColors.TextMuted,
                )
                Slider(
                    value = sensitivity,
                    onValueChange = onSensitivityChange,
                    valueRange = 0.4f..3.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = RemoteColors.NeonCyan,
                        activeTrackColor = RemoteColors.NeonCyan,
                        inactiveTrackColor = RemoteColors.TileBg,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(24.dp),
                )
            }

            if (!isConnected && useInternetTab) {
                // Internet (VPS Relay) connection form (pre-filled & 1-tap ready!)
                OutlinedTextField(
                    value = relayUrl,
                    onValueChange = onRelayUrlChange,
                    label = { Text("VPS Relay URL") },
                    placeholder = { Text("ws://182.255.88.108:8770") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = relayDeviceId,
                        onValueChange = onRelayDeviceIdChange,
                        label = { Text("PC ID") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = relayToken,
                        onValueChange = onRelayTokenChange,
                        label = { Text("Token") },
                        singleLine = true,
                        modifier = Modifier.weight(1.2f),
                    )
                }
                Button(
                    onClick = onConnectRelay,
                    enabled = !isConnecting && relayUrl.isNotBlank() && relayDeviceId.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) {
                    Text(
                        text = if (isConnecting) "Connecting..." else "Connect via Internet",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                // Auto-connect switch + Scan button (Local Wi-Fi)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Auto-connect",
                            style = MaterialTheme.typography.bodySmall,
                            color = RemoteColors.TextPrimary,
                            fontWeight = FontWeight.Medium,
                        )
                        Switch(
                            checked = autoConnect,
                            onCheckedChange = onAutoConnectChange,
                        )
                    }

                    if (!isConnected) {
                        Box(
                            modifier = Modifier
                                .height(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(RemoteColors.TileBg)
                                .border(1.dp, RemoteColors.NeonBorder, RoundedCornerShape(8.dp))
                                .clickable(onClick = onRefreshScan)
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (isScanning) "Rescan" else "Scan LAN",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = RemoteColors.NeonCyan,
                            )
                        }
                    }
                }

                if (!isConnected) {
                    if (discoveredPcs.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "Nearby PCs",
                                style = MaterialTheme.typography.labelSmall,
                                color = RemoteColors.TextMuted,
                            )
                            discoveredPcs.forEach { pc ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(RemoteColors.TileBg)
                                        .border(1.dp, RemoteColors.NeonBorderBright.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                                        .clickable(enabled = !isConnecting) { onSelectDiscoveredPc(pc) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(RemoteColors.ConnectedGreen),
                                        )
                                        Column {
                                            Text(
                                                text = pc.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = RemoteColors.TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "${pc.host}:${pc.port}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = RemoteColors.TextMuted,
                                            )
                                        }
                                    }
                                    Text(
                                        text = "Connect",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = RemoteColors.NeonCyan,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = onHostChange,
                            label = { Text("PC IP") },
                            placeholder = { Text("192.168.1.10") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { onConnect() }),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = onPortChange,
                            label = { Text("Port") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { onConnect() }),
                            modifier = Modifier.width(84.dp),
                        )
                        Button(
                            onClick = onConnect,
                            enabled = !isConnecting && host.isNotBlank(),
                            modifier = Modifier.height(54.dp),
                        ) {
                            Text(if (isConnecting) "..." else "Go")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TouchpadArea(
    modifier: Modifier,
    isConnected: Boolean,
    isAirMouseEnabled: Boolean,
    airMouseSpeed: Float,
    compact: Boolean,
    onMove: (dxPx: Float, dyPx: Float, density: Float) -> Unit,
    onScroll: (dxPx: Float, dyPx: Float, density: Float) -> Unit,
    onClick: (MouseButton, Int) -> Unit,
    onButton: (MouseButton, Boolean) -> Unit,
    onGesture: (String) -> Unit,
) {
    val density = LocalDensity.current.density
    val tapSlopPx = with(LocalDensity.current) { 12.dp.toPx() }
    val threeFingerThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }
    val haptic = LocalHapticFeedback.current

    var isTouched by remember { mutableStateOf(false) }
    var isTapDragging by remember { mutableStateOf(false) }
    var lastSingleTapUpMs by remember { mutableLongStateOf(0L) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    isTapDragging -> RemoteColors.NeonCyan.copy(alpha = 0.14f)
                    isTouched -> RemoteColors.PanelBg
                    else -> RemoteColors.DeepBg
                }
            )
            .border(
                width = 1.dp,
                color = when {
                    isTapDragging || isAirMouseEnabled -> RemoteColors.NeonBorderBright
                    isTouched -> RemoteColors.NeonCyan.copy(alpha = 0.8f)
                    else -> RemoteColors.NeonBorder
                },
                shape = RoundedCornerShape(14.dp),
            )
            .pointerInput(density, tapSlopPx, threeFingerThresholdPx) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    firstDown.consume()
                    isTouched = true

                    val downTimeMs = System.currentTimeMillis()
                    val candidateTapDrag = (downTimeMs - lastSingleTapUpMs) <= 240L
                    var tapDragActive = false
                    var maxPointers = 1
                    var totalTravelPx = 0f
                    var threeFingerDx = 0f
                    var threeFingerDy = 0f
                    var threeFingerTriggered = false

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val activeChanges = event.changes.filter { it.pressed }
                            if (event.changes.size > maxPointers) {
                                maxPointers = event.changes.size
                            }

                            when {
                                activeChanges.size == 1 && maxPointers == 1 -> {
                                    val change = activeChanges.first()
                                    val delta = change.positionChange()
                                    if (delta.x != 0f || delta.y != 0f) {
                                        totalTravelPx += hypot(delta.x, delta.y)
                                        if (candidateTapDrag && !tapDragActive && totalTravelPx > tapSlopPx * 0.5f) {
                                            tapDragActive = true
                                            isTapDragging = true
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onButton(MouseButton.LEFT, true)
                                        }
                                        onMove(delta.x, delta.y, density)
                                        change.consume()
                                    }
                                }

                                activeChanges.size == 2 && maxPointers == 2 -> {
                                    var avgDx = 0f
                                    var avgDy = 0f
                                    activeChanges.forEach { change ->
                                        val d = change.positionChange()
                                        avgDx += d.x
                                        avgDy += d.y
                                        change.consume()
                                    }
                                    avgDx /= 2f
                                    avgDy /= 2f
                                    val step = hypot(avgDx, avgDy)
                                    totalTravelPx += step
                                    if (totalTravelPx > tapSlopPx) {
                                        onScroll(avgDx, avgDy, density)
                                    }
                                }

                                activeChanges.size >= 3 -> {
                                    var avgDx = 0f
                                    var avgDy = 0f
                                    activeChanges.forEach { change ->
                                        val d = change.positionChange()
                                        avgDx += d.x
                                        avgDy += d.y
                                        change.consume()
                                    }
                                    avgDx /= activeChanges.size
                                    avgDy /= activeChanges.size
                                    threeFingerDx += avgDx
                                    threeFingerDy += avgDy
                                    totalTravelPx += hypot(avgDx, avgDy)

                                    if (!threeFingerTriggered &&
                                        hypot(threeFingerDx, threeFingerDy) >= threeFingerThresholdPx
                                    ) {
                                        threeFingerTriggered = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val gesture = if (abs(threeFingerDy) >= abs(threeFingerDx)) {
                                            if (threeFingerDy < 0f) "task_view" else "show_desktop"
                                        } else {
                                            if (threeFingerDx > 0f) "switch_next" else "switch_prev"
                                        }
                                        onGesture(gesture)
                                    }
                                }

                                else -> {
                                    event.changes.forEach { it.consume() }
                                }
                            }

                            if (activeChanges.isEmpty()) {
                                if (tapDragActive) {
                                    tapDragActive = false
                                    isTapDragging = false
                                    onButton(MouseButton.LEFT, false)
                                    lastSingleTapUpMs = 0L
                                } else {
                                    val elapsedMs = System.currentTimeMillis() - downTimeMs
                                    val isTap = totalTravelPx <= tapSlopPx && elapsedMs <= 220L
                                    if (isTap) {
                                        when (maxPointers) {
                                            1 -> {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onClick(MouseButton.LEFT, 1)
                                                lastSingleTapUpMs = System.currentTimeMillis()
                                            }
                                            2 -> {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onClick(MouseButton.RIGHT, 1)
                                                lastSingleTapUpMs = 0L
                                            }
                                        }
                                    } else {
                                        lastSingleTapUpMs = 0L
                                    }
                                }
                                break
                            }
                        }
                    } finally {
                        if (tapDragActive) {
                            onButton(MouseButton.LEFT, false)
                        }
                        isTapDragging = false
                        isTouched = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (isTapDragging) {
                Text(
                    text = "Dragging...",
                    color = RemoteColors.NeonCyan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            } else if (isAirMouseEnabled && isConnected) {
                Text(
                    text = "3D Air Mouse Active (${"%.1f".format(airMouseSpeed)}x)",
                    color = RemoteColors.NeonCyan.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!compact) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Move phone to guide cursor • Tap to click",
                        color = RemoteColors.TextMuted.copy(alpha = 0.55f),
                        fontSize = 11.sp,
                    )
                }
            } else if (!isConnected) {
                Text(
                    text = "Searching Wi-Fi for PC or enter IP above",
                    color = RemoteColors.TextMuted.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun NeonScrollStrip(
    modifier: Modifier,
    onScroll: (dxPx: Float, dyPx: Float, density: Float) -> Unit,
) {
    val density = LocalDensity.current.density
    var isScrolling by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (isScrolling) RemoteColors.NeonCyan.copy(alpha = 0.14f)
                else RemoteColors.DeepBg
            )
            .border(
                width = 1.dp,
                color = if (isScrolling) RemoteColors.NeonBorderBright else RemoteColors.NeonBorder,
                shape = RoundedCornerShape(20.dp),
            )
            .pointerInput(density) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    isScrolling = true
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val active = event.changes.firstOrNull { it.pressed } ?: break
                            val delta = active.positionChange()
                            if (delta.y != 0f) {
                                onScroll(0f, delta.y, density)
                                active.consume()
                            }
                        }
                    } finally {
                        isScrolling = false
                    }
                }
            }
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Signature glowing vertical cyan bar from screenshots 1-4
        Box(
            modifier = Modifier
                .width(5.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(
                    if (isScrolling) RemoteColors.NeonBorderBright else RemoteColors.NeonCyan
                ),
        )
    }
}

@Composable
private fun MouseButtonPad(
    label: String,
    modifier: Modifier,
    onStateChange: (down: Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (pressed) RemoteColors.NeonCyan.copy(alpha = 0.22f)
                else RemoteColors.DeepBg
            )
            .border(
                width = 1.dp,
                color = if (pressed) RemoteColors.NeonBorderBright else RemoteColors.NeonBorder,
                shape = RoundedCornerShape(14.dp),
            )
            .pointerInput(label) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onStateChange(true)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.all { it.changedToUp() || !it.pressed }) {
                                break
                            }
                        }
                    } finally {
                        pressed = false
                        onStateChange(false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                color = if (pressed) RemoteColors.NeonCyan else RemoteColors.TextMuted,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
            )
        }
    }
}
