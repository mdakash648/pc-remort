package com.remotepad.app.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotepad.app.data.model.MouseButton
import com.remotepad.app.network.ConnectionState
import com.remotepad.app.network.TransportMode
import com.remotepad.app.ui.keyboard.KeyboardPanel
import com.remotepad.app.ui.theme.RemoteColors
import com.remotepad.app.ui.touchpad.KeyboardMiniIcon
import com.remotepad.app.ui.touchpad.TouchpadViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class FloatingSubPanel {
    NONE,
    ACTIONS,
    QUALITY,
}

private enum class TwoFingerGestureMode {
    UNDECIDED,
    SCROLL,
    ZOOM_PAN,
}

private data class FitRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

private fun computeFitRect(canvasW: Float, canvasH: Float, imgW: Int, imgH: Int): FitRect {
    if (canvasW <= 0f || canvasH <= 0f || imgW <= 0 || imgH <= 0) {
        return FitRect(0f, 0f, canvasW.coerceAtLeast(1f), canvasH.coerceAtLeast(1f))
    }
    val canvasAspect = canvasW / canvasH
    val imgAspect = imgW.toFloat() / imgH.toFloat()
    return if (imgAspect > canvasAspect) {
        val w = canvasW
        val h = w / imgAspect
        FitRect(0f, (canvasH - h) * 0.5f, w, h)
    } else {
        val h = canvasH
        val w = h * imgAspect
        FitRect((canvasW - w) * 0.5f, 0f, w, h)
    }
}

private fun clampPan(
    pan: Offset,
    zoom: Float,
    canvasW: Float,
    canvasH: Float,
    fit: FitRect,
): Offset {
    if (zoom <= 1.001f) return Offset.Zero
    val scaledW = fit.width * zoom
    val scaledH = fit.height * zoom
    val maxPanX = ((scaledW - canvasW) * 0.5f).coerceAtLeast(0f) + fit.left * (zoom - 1f) * 0f
    val maxPanY = ((scaledH - canvasH) * 0.5f).coerceAtLeast(0f)
    // Allow panning across the full zoomed content area cleanly
    val limitX = ((scaledW - canvasW) * 0.5f).coerceAtLeast(0f)
    val limitY = ((scaledH - canvasH) * 0.5f).coerceAtLeast(0f)
    return Offset(
        x = pan.x.coerceIn(-limitX.coerceAtLeast(maxPanX), limitX.coerceAtLeast(maxPanX)),
        y = pan.y.coerceIn(-limitY, limitY),
    )
}

@Composable
fun RemoteScreenView(
    screenVm: RemoteScreenViewModel,
    touchpadVm: TouchpadViewModel,
    isLandscape: Boolean,
    isFullscreen: Boolean,
    onToggleOrientation: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onGoToTouchpad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by screenVm.uiState.collectAsStateWithLifecycle()
    val connectionState by screenVm.connectionState.collectAsStateWithLifecycle()
    val modifierStates by touchpadVm.modifierStates.collectAsStateWithLifecycle()
    val isMicEnabled by touchpadVm.isMicEnabled.collectAsStateWithLifecycle()
    val micError by touchpadVm.micError.collectAsStateWithLifecycle()
    val sensitivity by touchpadVm.sensitivity.collectAsStateWithLifecycle()

    val connected = connectionState as? ConnectionState.Connected
    val isConnected = connected != null
    // AnyDesk-style 60/120fps local phone cursor on P2P & VPS (0ms lag on phone, relaxed PC sync!)
    val useLocalCursor = connected?.viaRelay == true
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            touchpadVm.setMicEnabled(true)
        } else {
            Toast.makeText(context, "Microphone permission is required for PC Mic", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(micError) {
        val err = micError
        if (!err.isNullOrBlank()) {
            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            touchpadVm.clearMicError()
        }
    }

    var showKeyboard by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var activeSubPanel by rememberSaveable { mutableStateOf(FloatingSubPanel.NONE) }
    var showTopHud by rememberSaveable { mutableStateOf(true) }
    var isZoomLocked by rememberSaveable { mutableStateOf(false) }
    var showScrollStrip by rememberSaveable { mutableStateOf(true) }

    // Zoom & pan state
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    // Local client-side AnyDesk-style cursor state (updated directly on touch at full display Hz)
    var localCursorX by remember { mutableFloatStateOf(0.5f) }
    var localCursorY by remember { mutableFloatStateOf(0.5f) }
    var localCursorInitialized by remember { mutableStateOf(false) }
    var lastLocalCursorTouchMs by remember { mutableLongStateOf(0L) }
    var isLocalDragActive by remember { mutableStateOf(false) }

    // Visual click ripple feedback
    var tapRipplePos by remember { mutableStateOf<Offset?>(null) }
    var tapRippleColor by remember { mutableStateOf(RemoteColors.NeonCyan) }
    var tapRippleTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tapRippleTick) {
        if (tapRippleTick > 0) {
            delay(280L)
            tapRipplePos = null
        }
    }

    // Activate streaming while this composable is on screen
    DisposableEffect(Unit) {
        screenVm.setScreenVisible(true)
        onDispose {
            screenVm.setScreenVisible(false)
        }
    }

    // Reset zoom & re-sync local cursor on orientation or monitor change
    LaunchedEffect(isLandscape, uiState.monitorIndex) {
        zoom = 1f
        pan = Offset.Zero
        localCursorInitialized = false
    }

    // Sync local cursor with PC's real cursor initially or when the physical PC mouse moves while phone is idle
    LaunchedEffect(uiState.cursorXNorm, uiState.cursorYNorm, uiState.hasCursor, useLocalCursor) {
        if (!useLocalCursor || !uiState.hasCursor) return@LaunchedEffect
        val now = SystemClock.elapsedRealtime()
        if (!localCursorInitialized) {
            localCursorX = uiState.cursorXNorm
            localCursorY = uiState.cursorYNorm
            localCursorInitialized = true
        } else if ((now - lastLocalCursorTouchMs) > 750L) {
            val dx = abs(uiState.cursorXNorm - localCursorX)
            val dy = abs(uiState.cursorYNorm - localCursorY)
            if (dx > 0.015f || dy > 0.015f) {
                localCursorX = uiState.cursorXNorm
                localCursorY = uiState.cursorYNorm
            }
        }
    }

    fun triggerRipple(pos: Offset, rightClick: Boolean = false) {
        tapRipplePos = pos
        tapRippleColor = if (rightClick) RemoteColors.ConnectingAmber else RemoteColors.NeonCyan
        tapRippleTick += 1
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF05070B))
            .imePadding(),
    ) {
        val containerW = with(density) { maxWidth.toPx() }
        val containerH = with(density) { maxHeight.toPx() }

        // Draggable floating bubble coordinates (default: bottom-right comfortable thumb spot)
        var fabOffsetX by remember { mutableFloatStateOf(-1f) }
        var fabOffsetY by remember { mutableFloatStateOf(-1f) }

        val fabMarginPx = with(density) { 12.dp.toPx() }
        val fabDockWidthPx = with(density) { 112.dp.toPx() }
        val fabDockHeightPx = with(density) { 50.dp.toPx() }

        val resolvedFabX = if (fabOffsetX < 0f) {
            (containerW - fabDockWidthPx - fabMarginPx).coerceAtLeast(fabMarginPx)
        } else {
            fabOffsetX.coerceIn(fabMarginPx, (containerW - fabDockWidthPx - fabMarginPx).coerceAtLeast(fabMarginPx))
        }
        val resolvedFabY = if (fabOffsetY < 0f) {
            (containerH - fabDockHeightPx - with(density) { 26.dp.toPx() }).coerceAtLeast(fabMarginPx)
        } else {
            fabOffsetY.coerceIn(fabMarginPx, (containerH - fabDockHeightPx - fabMarginPx).coerceAtLeast(fabMarginPx))
        }

        // Latest values for pointerInput gesture coroutine
        val latestState by rememberUpdatedState(uiState)
        val latestZoom by rememberUpdatedState(zoom)
        val latestPan by rememberUpdatedState(pan)
        val latestZoomLocked by rememberUpdatedState(isZoomLocked)
        val latestConnected by rememberUpdatedState(isConnected)
        val latestUseLocalCursor by rememberUpdatedState(useLocalCursor)
        val latestSensitivity by rememberUpdatedState(sensitivity)
        val latestContainerW by rememberUpdatedState(containerW)
        val latestContainerH by rememberUpdatedState(containerH)

        // Auto-follow cursor smoothly when zoomed in during Touchpad mode (uses 0ms local cursor on P2P/VPS!)
        val activeCursorX = if (useLocalCursor) localCursorX else uiState.cursorXNorm
        val activeCursorY = if (useLocalCursor) localCursorY else uiState.cursorYNorm
        val activeCursorVisible = if (useLocalCursor) true else uiState.hasCursor
        LaunchedEffect(activeCursorX, activeCursorY, activeCursorVisible, zoom) {
            if (zoom > 1.05f && activeCursorVisible && uiState.inputMode == ScreenInputMode.TOUCHPAD && uiState.frameWidth > 0) {
                val fit = computeFitRect(containerW, containerH, uiState.frameWidth, uiState.frameHeight)
                val cx = fit.left + activeCursorX * fit.width
                val cy = fit.top + activeCursorY * fit.height
                val centerX = containerW * 0.5f
                val centerY = containerH * 0.5f
                val screenX = centerX + (cx - centerX) * zoom + pan.x
                val screenY = centerY + (cy - centerY) * zoom + pan.y
                val edgeMargin = 72f
                var dxPan = 0f
                var dyPan = 0f
                if (screenX < edgeMargin) dxPan = edgeMargin - screenX
                else if (screenX > containerW - edgeMargin) dxPan = (containerW - edgeMargin) - screenX
                if (screenY < edgeMargin) dyPan = edgeMargin - screenY
                else if (screenY > containerH - edgeMargin) dyPan = (containerH - edgeMargin) - screenY
                if (dxPan != 0f || dyPan != 0f) {
                    pan = clampPan(
                        Offset(pan.x + dxPan * 0.5f, pan.y + dyPan * 0.5f),
                        zoom,
                        containerW,
                        containerH,
                        fit,
                    )
                }
            }
        }

        val tapSlopPx = with(density) { 10.dp.toPx() }
        var lastTapTimeMs by remember { mutableLongStateOf(0L) }
        var lastTapPos by remember { mutableStateOf(Offset.Zero) }
        val screenDensity = density.density
        val gestureScope = rememberCoroutineScope()

        // 1. Live PC Desktop Canvas + Full AnyDesk Gesture Engine
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        if (!latestConnected) return@awaitEachGesture

                        val downTime = SystemClock.uptimeMillis()
                        val downPos = firstDown.position
                        var maxPointerCount = 1
                        var moved = false
                        var dragStarted = false
                        var isLeftDragHold = false
                        var isDirectTouchDrag = false
                        var lastDirectPos = downPos
                        var longPressTriggered = false
                        var twoFingerMode = TwoFingerGestureMode.UNDECIDED
                        var cumulativeScale = 1f
                        var cumulativePan = Offset.Zero
                        var prevCentroid = downPos

                        fun mapScreenToMonitorNorm(pos: Offset): Pair<Float, Float>? {
                            val st = latestState
                            if (st.frameWidth <= 0 || st.frameHeight <= 0) return null
                            val cw = latestContainerW
                            val ch = latestContainerH
                            val fit = computeFitRect(cw, ch, st.frameWidth, st.frameHeight)
                            val z = latestZoom
                            val p = latestPan
                            val centerX = cw * 0.5f
                            val centerY = ch * 0.5f
                            val unzoomX = (pos.x - centerX - p.x) / z + centerX
                            val unzoomY = (pos.y - centerY - p.y) / z + centerY
                            val nx = ((unzoomX - fit.left) / fit.width).coerceIn(0f, 1f)
                            val ny = ((unzoomY - fit.top) / fit.height).coerceIn(0f, 1f)
                            return nx to ny
                        }

                        fun mapMonitorNormToScreen(nx: Float, ny: Float): Offset {
                            val st = latestState
                            val cw = latestContainerW
                            val ch = latestContainerH
                            val fit = computeFitRect(cw, ch, st.frameWidth, st.frameHeight)
                            val z = latestZoom
                            val p = latestPan
                            val centerX = cw * 0.5f
                            val centerY = ch * 0.5f
                            val dstW = fit.width * z
                            val dstH = fit.height * z
                            val dstLeft = centerX + (fit.left - centerX) * z + p.x
                            val dstTop = centerY + (fit.top - centerY) * z + p.y
                            return Offset(dstLeft + nx * dstW, dstTop + ny * dstH)
                        }

                        // Real stationary long-press timer (fires at 450ms even when finger stays completely still!)
                        var longPressJob: Job? = gestureScope.launch {
                            delay(450L)
                            if (!moved && maxPointerCount == 1 && !longPressTriggered && latestConnected) {
                                longPressTriggered = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (latestState.inputMode == ScreenInputMode.DIRECT_TOUCH) {
                                    triggerRipple(downPos, rightClick = true)
                                    val norm = mapScreenToMonitorNorm(downPos)
                                    if (norm != null) {
                                        localCursorX = norm.first
                                        localCursorY = norm.second
                                        localCursorInitialized = true
                                        lastLocalCursorTouchMs = SystemClock.elapsedRealtime()
                                        screenVm.onDirectClick(norm.first, norm.second, MouseButton.RIGHT, 1)
                                    }
                                } else if (latestUseLocalCursor) {
                                    triggerRipple(mapMonitorNormToScreen(localCursorX, localCursorY), rightClick = true)
                                    lastLocalCursorTouchMs = SystemClock.elapsedRealtime()
                                    screenVm.onDirectClick(localCursorX, localCursorY, MouseButton.RIGHT, 1)
                                } else {
                                    triggerRipple(downPos, rightClick = true)
                                    touchpadVm.onClick(MouseButton.RIGHT, count = 1)
                                }
                            }
                        }

                        // Tap-and-hold drag for Touchpad mode (quick tap then hold+drag like a laptop trackpad)
                        val isDoubleTapWindow = (downTime - lastTapTimeMs) < 280L &&
                            hypot(downPos.x - lastTapPos.x, downPos.y - lastTapPos.y) < tapSlopPx * 3.2f

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val active = event.changes.filter { it.pressed }
                                val activeCount = active.size
                                if (activeCount > maxPointerCount) {
                                    maxPointerCount = activeCount
                                    longPressJob?.cancel()
                                    if (isLeftDragHold) {
                                        if (latestUseLocalCursor) {
                                            screenVm.onDirectButton(localCursorX, localCursorY, MouseButton.LEFT, down = false)
                                        } else {
                                            touchpadVm.onButton(MouseButton.LEFT, down = false)
                                        }
                                        isLeftDragHold = false
                                        isLocalDragActive = false
                                    }
                                    if (isDirectTouchDrag) {
                                        val norm = mapScreenToMonitorNorm(lastDirectPos)
                                        if (norm != null) {
                                            screenVm.onDirectButton(norm.first, norm.second, MouseButton.LEFT, down = false)
                                        } else {
                                            touchpadVm.onButton(MouseButton.LEFT, down = false)
                                        }
                                        isDirectTouchDrag = false
                                    }
                                }

                                if (activeCount == 0) {
                                    longPressJob?.cancel()
                                    val elapsed = SystemClock.uptimeMillis() - downTime
                                    if (isDirectTouchDrag) {
                                        val norm = mapScreenToMonitorNorm(lastDirectPos)
                                        if (norm != null) {
                                            localCursorX = norm.first
                                            localCursorY = norm.second
                                            screenVm.onDirectButton(norm.first, norm.second, MouseButton.LEFT, down = false)
                                        } else {
                                            touchpadVm.onButton(MouseButton.LEFT, down = false)
                                        }
                                        isDirectTouchDrag = false
                                    } else if (isLeftDragHold) {
                                        if (latestUseLocalCursor) {
                                            screenVm.onDirectButton(localCursorX, localCursorY, MouseButton.LEFT, down = false)
                                        } else {
                                            touchpadVm.onButton(MouseButton.LEFT, down = false)
                                        }
                                        isLeftDragHold = false
                                        isLocalDragActive = false
                                    } else if (!longPressTriggered) {
                                        if (maxPointerCount == 1 && moved && latestUseLocalCursor && latestState.inputMode == ScreenInputMode.TOUCHPAD) {
                                            // Flush final exact local cursor coordinates to PC when finger lifts
                                            lastLocalCursorTouchMs = SystemClock.elapsedRealtime()
                                            screenVm.syncLocalCursorToPc(localCursorX, localCursorY, force = true)
                                        } else if (maxPointerCount == 1 && !moved && elapsed < 440L) {
                                            val now = SystemClock.uptimeMillis()
                                            lastTapTimeMs = now
                                            lastTapPos = downPos

                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                                            if (latestState.inputMode == ScreenInputMode.DIRECT_TOUCH) {
                                                triggerRipple(downPos, rightClick = false)
                                                // Each tap sends 1 clean left click at exact touch spot
                                                // (2 quick taps naturally trigger Windows double-click!)
                                                val norm = mapScreenToMonitorNorm(downPos)
                                                if (norm != null) {
                                                    localCursorX = norm.first
                                                    localCursorY = norm.second
                                                    localCursorInitialized = true
                                                    lastLocalCursorTouchMs = SystemClock.elapsedRealtime()
                                                    screenVm.onDirectClick(
                                                        norm.first,
                                                        norm.second,
                                                        MouseButton.LEFT,
                                                        count = 1,
                                                    )
                                                }
                                            } else if (latestUseLocalCursor) {
                                                triggerRipple(mapMonitorNormToScreen(localCursorX, localCursorY), rightClick = false)
                                                lastLocalCursorTouchMs = SystemClock.elapsedRealtime()
                                                screenVm.onDirectClick(
                                                    localCursorX,
                                                    localCursorY,
                                                    MouseButton.LEFT,
                                                    count = 1,
                                                )
                                            } else {
                                                triggerRipple(downPos, rightClick = false)
                                                touchpadVm.onClick(MouseButton.LEFT, count = 1)
                                            }
                                        } else if (maxPointerCount == 2 && twoFingerMode == TwoFingerGestureMode.UNDECIDED && elapsed < 360L) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            if (latestState.inputMode == ScreenInputMode.DIRECT_TOUCH) {
                                                triggerRipple(prevCentroid, rightClick = true)
                                                val norm = mapScreenToMonitorNorm(prevCentroid)
                                                if (norm != null) {
                                                    localCursorX = norm.first
                                                    localCursorY = norm.second
                                                    localCursorInitialized = true
                                                    lastLocalCursorTouchMs = SystemClock.elapsedRealtime()
                                                    screenVm.onDirectClick(norm.first, norm.second, MouseButton.RIGHT, 1)
                                                }
                                            } else if (latestUseLocalCursor) {
                                                triggerRipple(mapMonitorNormToScreen(localCursorX, localCursorY), rightClick = true)
                                                lastLocalCursorTouchMs = SystemClock.elapsedRealtime()
                                                screenVm.onDirectClick(localCursorX, localCursorY, MouseButton.RIGHT, 1)
                                            } else {
                                                triggerRipple(prevCentroid, rightClick = true)
                                                touchpadVm.onClick(MouseButton.RIGHT, count = 1)
                                            }
                                        }
                                    }
                                    break
                                }

                                if (activeCount == 1 && maxPointerCount == 1) {
                                    val change = active.first()
                                    val delta = change.positionChange()
                                    val totalDist = hypot(change.position.x - downPos.x, change.position.y - downPos.y)

                                    if (!moved && totalDist > tapSlopPx) {
                                        moved = true
                                        longPressJob?.cancel()
                                        if (!longPressTriggered) {
                                            if (latestState.inputMode == ScreenInputMode.DIRECT_TOUCH) {
                                                // Real touchscreen drag: press Left button DOWN at exact initial touch point!
                                                val startNorm = mapScreenToMonitorNorm(downPos)
                                                if (startNorm != null) {
                                                    isDirectTouchDrag = true
                                                    lastDirectPos = change.position
                                                    localCursorX = startNorm.first
                                                    localCursorY = startNorm.second
                                                    localCursorInitialized = true
                                                    lastLocalCursorTouchMs = SystemClock.elapsedRealtime()
                                                    screenVm.onDirectButton(
                                                        startNorm.first,
                                                        startNorm.second,
                                                        MouseButton.LEFT,
                                                        down = true,
                                                    )
                                                    val curNorm = mapScreenToMonitorNorm(change.position)
                                                    if (curNorm != null) {
                                                        localCursorX = curNorm.first
                                                        localCursorY = curNorm.second
                                                        screenVm.onDirectMove(curNorm.first, curNorm.second)
                                                    }
                                                }
                                            } else if (isDoubleTapWindow && !dragStarted) {
                                                isLeftDragHold = true
                                                dragStarted = true
                                                if (latestUseLocalCursor) {
                                                    isLocalDragActive = true
                                                    lastLocalCursorTouchMs = SystemClock.elapsedRealtime()
                                                    screenVm.onDirectButton(
                                                        localCursorX,
                                                        localCursorY,
                                                        MouseButton.LEFT,
                                                        down = true,
                                                    )
                                                } else {
                                                    touchpadVm.onButton(MouseButton.LEFT, down = true)
                                                }
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    }

                                    if (moved && !longPressTriggered && (abs(delta.x) > 0.01f || abs(delta.y) > 0.01f)) {
                                        if (latestState.inputMode == ScreenInputMode.DIRECT_TOUCH) {
                                            lastDirectPos = change.position
                                            val norm = mapScreenToMonitorNorm(change.position)
                                            if (norm != null) {
                                                localCursorX = norm.first
                                                localCursorY = norm.second
                                                localCursorInitialized = true
                                                lastLocalCursorTouchMs = SystemClock.elapsedRealtime()
                                                screenVm.onDirectMove(norm.first, norm.second)
                                            }
                                        } else if (latestUseLocalCursor) {
                                            // AnyDesk-style 60/120fps local phone cursor: 0ms visual lag on phone,
                                            // gently throttled absolute sync to PC (PC doesn't need to be smooth!)
                                            val st = latestState
                                            val fit = computeFitRect(
                                                latestContainerW,
                                                latestContainerH,
                                                st.frameWidth,
                                                st.frameHeight,
                                            )
                                            val zoomDamp = (1f / latestZoom.coerceAtLeast(1f)).coerceIn(0.35f, 1f)
                                            val speed = (latestSensitivity.coerceIn(0.4f, 3.0f) * 1.15f) * zoomDamp
                                            val nextX = (localCursorX + (delta.x * speed) / fit.width.coerceAtLeast(1f))
                                                .coerceIn(0f, 1f)
                                            val nextY = (localCursorY + (delta.y * speed) / fit.height.coerceAtLeast(1f))
                                                .coerceIn(0f, 1f)
                                            localCursorX = nextX
                                            localCursorY = nextY
                                            localCursorInitialized = true
                                            lastLocalCursorTouchMs = SystemClock.elapsedRealtime()
                                            screenVm.syncLocalCursorToPc(
                                                normX = nextX,
                                                normY = nextY,
                                                force = false,
                                                isDraggingButton = isLeftDragHold,
                                            )
                                        } else {
                                            // LAN Touchpad mode: smooth native relative PC cursor movement
                                            val zoomDamp = (1f / latestZoom.coerceAtLeast(1f)).coerceIn(0.45f, 1f)
                                            touchpadVm.onMoveDelta(
                                                dxPx = delta.x * zoomDamp,
                                                dyPx = delta.y * zoomDamp,
                                                density = screenDensity,
                                            )
                                        }
                                        change.consume()
                                    }
                                } else if (activeCount >= 2) {
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    if (centroid != Offset.Unspecified) {
                                        prevCentroid = centroid
                                    }

                                    cumulativeScale *= zoomChange
                                    cumulativePan += panChange

                                    if (twoFingerMode == TwoFingerGestureMode.UNDECIDED) {
                                        if (latestZoomLocked) {
                                            // Zoom Locked: 2-finger drag is 100% dedicated to PC Scroll
                                            if (hypot(cumulativePan.x, cumulativePan.y) >= tapSlopPx * 0.7f) {
                                                twoFingerMode = TwoFingerGestureMode.SCROLL
                                                if (latestUseLocalCursor) {
                                                    screenVm.syncLocalCursorToPc(localCursorX, localCursorY, force = true)
                                                }
                                                touchpadVm.onScrollDelta(cumulativePan.x, cumulativePan.y, screenDensity)
                                            }
                                        } else {
                                            // Zoom Unlocked: Smart Auto Disambiguation
                                            val clearPinch = abs(cumulativeScale - 1f) >= 0.08f
                                            val clearDrag = hypot(cumulativePan.x, cumulativePan.y) >= tapSlopPx * 1.15f
                                            if (clearPinch) {
                                                twoFingerMode = TwoFingerGestureMode.ZOOM_PAN
                                            } else if (clearDrag) {
                                                if (latestZoom > 1.05f) {
                                                    twoFingerMode = TwoFingerGestureMode.ZOOM_PAN
                                                } else {
                                                    twoFingerMode = TwoFingerGestureMode.SCROLL
                                                    if (latestUseLocalCursor) {
                                                        screenVm.syncLocalCursorToPc(localCursorX, localCursorY, force = true)
                                                    }
                                                    touchpadVm.onScrollDelta(cumulativePan.x, cumulativePan.y, screenDensity)
                                                }
                                            }
                                        }
                                    } else if (twoFingerMode == TwoFingerGestureMode.SCROLL) {
                                        if (abs(panChange.x) > 0.1f || abs(panChange.y) > 0.1f) {
                                            touchpadVm.onScrollDelta(panChange.x, panChange.y, screenDensity)
                                        }
                                    } else if (twoFingerMode == TwoFingerGestureMode.ZOOM_PAN && !latestZoomLocked) {
                                        val st = latestState
                                        val cw = latestContainerW
                                        val ch = latestContainerH
                                        val fit = computeFitRect(cw, ch, st.frameWidth, st.frameHeight)
                                        val newZoom = (latestZoom * zoomChange).coerceIn(1f, 4f)
                                        val newPan = if (newZoom <= 1.01f) {
                                            Offset.Zero
                                        } else {
                                            clampPan(latestPan + panChange, newZoom, cw, ch, fit)
                                        }
                                        zoom = newZoom
                                        pan = newPan
                                    }
                                    active.forEach { it.consume() }
                                }
                            }
                        } finally {
                            longPressJob?.cancel()
                            isLocalDragActive = false
                            if (isDirectTouchDrag) {
                                val norm = mapScreenToMonitorNorm(lastDirectPos)
                                if (norm != null) {
                                    screenVm.onDirectButton(norm.first, norm.second, MouseButton.LEFT, down = false)
                                } else {
                                    touchpadVm.onButton(MouseButton.LEFT, down = false)
                                }
                            } else if (isLeftDragHold) {
                                if (latestUseLocalCursor) {
                                    screenVm.onDirectButton(localCursorX, localCursorY, MouseButton.LEFT, down = false)
                                } else {
                                    touchpadVm.onButton(MouseButton.LEFT, down = false)
                                }
                            }
                        }
                    }
                },
        ) {
            val bitmap = uiState.frameBitmap
            if (bitmap != null && isConnected) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val fit = computeFitRect(size.width, size.height, bitmap.width, bitmap.height)
                    val cx = size.width * 0.5f
                    val cy = size.height * 0.5f

                    val dstW = fit.width * zoom
                    val dstH = fit.height * zoom
                    val dstLeft = cx + (fit.left - cx) * zoom + pan.x
                    val dstTop = cy + (fit.top - cy) * zoom + pan.y

                    drawImage(
                        image = bitmap,
                        dstOffset = IntOffset(dstLeft.roundToInt(), dstTop.roundToInt()),
                        dstSize = IntSize(dstW.roundToInt().coerceAtLeast(1), dstH.roundToInt().coerceAtLeast(1)),
                        filterQuality = FilterQuality.Low,
                    )

                    // Subtle neon border around PC frame so edges are crisp on black letterbox
                    drawRect(
                        color = RemoteColors.NeonBorder.copy(alpha = 0.35f),
                        topLeft = Offset(dstLeft, dstTop),
                        size = Size(dstW, dstH),
                        style = Stroke(width = 1.dp.toPx()),
                    )

                    // AnyDesk-style 60/120fps crisp local vector pointer cursor over P2P & VPS
                    if (useLocalCursor) {
                        val tipX = dstLeft + localCursorX * dstW
                        val tipY = dstTop + localCursorY * dstH
                        val s = 1.dp.toPx()

                        if (isLocalDragActive) {
                            drawCircle(
                                color = RemoteColors.NeonCyan.copy(alpha = 0.28f),
                                radius = 14f * s,
                                center = Offset(tipX, tipY),
                            )
                            drawCircle(
                                color = RemoteColors.NeonCyan,
                                radius = 10f * s,
                                center = Offset(tipX, tipY),
                                style = Stroke(width = 1.6f * s),
                            )
                        }

                        // Soft drop shadow for visibility on bright/white windows
                        val shadowPath = Path().apply {
                            val ox = tipX + 1.4f * s
                            val oy = tipY + 1.8f * s
                            moveTo(ox, oy)
                            lineTo(ox, oy + 20f * s)
                            lineTo(ox + 4.8f * s, oy + 15.6f * s)
                            lineTo(ox + 8.6f * s, oy + 23.2f * s)
                            lineTo(ox + 11.4f * s, oy + 21.8f * s)
                            lineTo(ox + 7.6f * s, oy + 14.2f * s)
                            lineTo(ox + 14.2f * s, oy + 14.2f * s)
                            close()
                        }
                        drawPath(path = shadowPath, color = Color(0x66000000))

                        // Crisp white Windows/AnyDesk arrow with sharp dark outline (exact hotspot at tipX, tipY)
                        val cursorPath = Path().apply {
                            moveTo(tipX, tipY)
                            lineTo(tipX, tipY + 20f * s)
                            lineTo(tipX + 4.8f * s, tipY + 15.6f * s)
                            lineTo(tipX + 8.6f * s, tipY + 23.2f * s)
                            lineTo(tipX + 11.4f * s, tipY + 21.8f * s)
                            lineTo(tipX + 7.6f * s, tipY + 14.2f * s)
                            lineTo(tipX + 14.2f * s, tipY + 14.2f * s)
                            close()
                        }
                        drawPath(path = cursorPath, color = Color.White)
                        drawPath(
                            path = cursorPath,
                            color = Color(0xFF0A0E14),
                            style = Stroke(width = 1.5f * s, join = StrokeJoin.Round),
                        )
                    }

                    // Subtle visual ripple on clicks
                    tapRipplePos?.let { rp ->
                        drawCircle(
                            color = tapRippleColor.copy(alpha = 0.25f),
                            radius = 22.dp.toPx(),
                            center = rp,
                        )
                        drawCircle(
                            color = tapRippleColor.copy(alpha = 0.85f),
                            radius = 12.dp.toPx(),
                            center = rp,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            } else {
                // Waiting for stream or disconnected placeholder
                ScreenStatusPlaceholder(
                    connectionState = connectionState,
                    isStreaming = uiState.isStreaming,
                    onGoToConnect = onGoToTouchpad,
                    onRetry = { touchpadVm.reconnectWifiFirst() },
                    onRefreshFrame = { screenVm.refreshFrame() },
                )
            }
        }

        // 2. Compact Translucent Top HUD Pill (AnyDesk-style session info + Zoom Lock + Monitor + Zoom Reset)
        if (showTopHud) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp, start = 10.dp, end = 10.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC0D131D))
                    .border(1.dp, RemoteColors.NeonBorder.copy(alpha = 0.65f), RoundedCornerShape(20.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                val isP2pActiveUi = connected?.transportMode == TransportMode.P2P_DIRECT &&
                    (uiState.receivingViaP2p || (!uiState.p2pFallbackActive && uiState.frameBitmap == null))
                val dotColor = when (connectionState) {
                    is ConnectionState.Connected -> if (isP2pActiveUi) RemoteColors.NeonCyan else RemoteColors.ConnectedGreen
                    is ConnectionState.Connecting -> RemoteColors.ConnectingAmber
                    else -> RemoteColors.DangerRed
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )

                val modeLabel = when (connected?.transportMode) {
                    TransportMode.LAN -> "Wi-Fi"
                    TransportMode.P2P_DIRECT -> if (isP2pActiveUi) "P2P" else "VPS"
                    TransportMode.P2P_CONNECTING -> "P2P…"
                    TransportMode.VPS_RELAY -> "VPS"
                    null -> "Offline"
                }
                val fpsLabel = if (isConnected && uiState.fps > 0) " • ${uiState.fps}fps" else ""
                val title = connected?.pcName?.take(12) ?: "PC Screen"
                Text(
                    text = "$title • $modeLabel$fpsLabel",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = RemoteColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Idea 1: Zoom Lock / Unlock toggle (Locked -> 2-finger Scroll only | Unlocked -> 2-finger Zoom/Pan)
                HudChip(
                    label = if (isZoomLocked) "🔒 Scroll" else "🔓 Zoom",
                    active = isZoomLocked,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        isZoomLocked = !isZoomLocked
                        val msg = if (isZoomLocked) {
                            "Zoom Locked • 2-finger Scroll active"
                        } else {
                            "Zoom Unlocked • Pinch to Zoom"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    },
                )

                // Input mode badge (1-tap toggle Touchpad <-> Direct Touch)
                HudChip(
                    label = uiState.inputMode.label,
                    active = uiState.inputMode == ScreenInputMode.DIRECT_TOUCH,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        screenVm.toggleInputMode()
                        val msg = if (uiState.inputMode == ScreenInputMode.TOUCHPAD) {
                            "Direct Touch mode"
                        } else {
                            "Touchpad mode"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    },
                )

                // Multi-monitor switch chip
                if (uiState.monitorCount > 1) {
                    HudChip(
                        label = "D${uiState.monitorIndex + 1}/${uiState.monitorCount}",
                        active = false,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            screenVm.cycleMonitor()
                        },
                    )
                }

                // Zoom reset chip
                if (zoom > 1.05f) {
                    HudChip(
                        label = "${"%.1f".format(zoom)}x ✕",
                        active = true,
                        onClick = {
                            zoom = 1f
                            pan = Offset.Zero
                        },
                    )
                }

                // Quick Rotate icon button right in top bar too
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(RemoteColors.TileBg)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleOrientation()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    RotateIcon(color = RemoteColors.NeonCyan, modifier = Modifier.size(14.dp))
                }
            }
        }

        // 2.5. Idea 2: Right-Edge Translucent 1-Finger Scroll Strip (dedicated thumb scroll bar)
        if (showScrollStrip && isConnected && !showKeyboard) {
            EdgeScrollStrip(
                onScrollDelta = { dyPx ->
                    if (useLocalCursor) {
                        screenVm.syncLocalCursorToPc(localCursorX, localCursorY, force = false)
                    }
                    touchpadVm.onScrollDelta(0f, dyPx * 1.45f, screenDensity)
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .width(28.dp)
                    .fillMaxHeight(0.46f),
            )
        }

        // 3. Expanded Floating Sub-Panels (Actions / Quality) above bottom overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AnimatedVisibility(
                visible = activeSubPanel == FloatingSubPanel.ACTIONS && !showKeyboard,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                FloatingActionsBar(
                    onKeyCombo = { keys ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        touchpadVm.sendKeyCombo(keys)
                    },
                    onSpecialKey = { key ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        touchpadVm.sendSpecialKey(key)
                    },
                    onRightClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (useLocalCursor) {
                            screenVm.onDirectClick(localCursorX, localCursorY, MouseButton.RIGHT, 1)
                        } else {
                            touchpadVm.onClick(MouseButton.RIGHT, 1)
                        }
                    },
                    onRefresh = {
                        screenVm.refreshFrame()
                        Toast.makeText(context, "Refreshing screen...", Toast.LENGTH_SHORT).show()
                    },
                    onClose = { activeSubPanel = FloatingSubPanel.NONE },
                )
            }

            AnimatedVisibility(
                visible = activeSubPanel == FloatingSubPanel.QUALITY && !showKeyboard,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                FloatingQualityBar(
                    selected = uiState.quality,
                    onSelect = { q ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        screenVm.setQuality(q)
                        activeSubPanel = FloatingSubPanel.NONE
                    },
                    onClose = { activeSubPanel = FloatingSubPanel.NONE },
                )
            }

            // 4. Floating Keyboard Overlay (Full KeyboardPanel with Live + Paragraph + Modifiers + Shortcuts)
            AnimatedVisibility(
                visible = showKeyboard,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                KeyboardPanel(
                    modifierStates = modifierStates,
                    isMicEnabled = isMicEnabled,
                    onToggleMic = {
                        if (isMicEnabled) {
                            touchpadVm.setMicEnabled(false)
                        } else if (!isConnected) {
                            Toast.makeText(context, "Connect to PC first", Toast.LENGTH_SHORT).show()
                        } else {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                touchpadVm.setMicEnabled(true)
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    onToggleModifier = touchpadVm::toggleModifier,
                    onReleaseAllModifiers = touchpadVm::releaseAllModifiers,
                    onSpecialKey = touchpadVm::sendSpecialKey,
                    onKeyCombo = touchpadVm::sendKeyCombo,
                    onTypeText = touchpadVm::sendTypedText,
                    onPasteParagraph = touchpadVm::sendParagraphText,
                    onClose = {
                        keyboardController?.hide()
                        showKeyboard = false
                    },
                )
            }
        }

        // 5. AnyDesk-Style Draggable Floating Menu + Quick Keyboard Dock
        if (!showKeyboard) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(resolvedFabX.roundToInt(), resolvedFabY.roundToInt()) }
                    .pointerInput(containerW, containerH) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val curX = if (fabOffsetX < 0f) resolvedFabX else fabOffsetX
                            val curY = if (fabOffsetY < 0f) resolvedFabY else fabOffsetY
                            fabOffsetX = (curX + dragAmount.x).coerceIn(
                                fabMarginPx,
                                (containerW - fabDockWidthPx - fabMarginPx).coerceAtLeast(fabMarginPx),
                            )
                            fabOffsetY = (curY + dragAmount.y).coerceIn(
                                fabMarginPx,
                                (containerH - fabDockHeightPx - fabMarginPx).coerceAtLeast(fabMarginPx),
                            )
                        }
                    },
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Expanded AnyDesk Quick Pie / Vertical Floating Menu
                    AnimatedVisibility(
                        visible = menuExpanded,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
                    ) {
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xE6101724))
                                .border(1.dp, RemoteColors.NeonCyan.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                                .padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            FloatingMenuRow(
                                label = if (isLandscape) "Portrait" else "Rotate",
                                accent = true,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    menuExpanded = false
                                    onToggleOrientation()
                                },
                            )
                            FloatingMenuRow(
                                label = if (uiState.inputMode == ScreenInputMode.TOUCHPAD) "Mode: Touchpad" else "Mode: Touch",
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    screenVm.toggleInputMode()
                                    menuExpanded = false
                                },
                            )
                            FloatingMenuRow(
                                label = if (isZoomLocked) "🔒 Scroll Locked" else "🔓 Zoom Unlocked",
                                accent = isZoomLocked,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    isZoomLocked = !isZoomLocked
                                    menuExpanded = false
                                },
                            )
                            FloatingMenuRow(
                                label = if (showScrollStrip) "Scroll Bar: On" else "Scroll Bar: Off",
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    showScrollStrip = !showScrollStrip
                                    menuExpanded = false
                                },
                            )
                            FloatingMenuRow(
                                label = "PC Actions",
                                onClick = {
                                    menuExpanded = false
                                    activeSubPanel = if (activeSubPanel == FloatingSubPanel.ACTIONS) {
                                        FloatingSubPanel.NONE
                                    } else {
                                        FloatingSubPanel.ACTIONS
                                    }
                                },
                            )
                            FloatingMenuRow(
                                label = "Quality: ${uiState.quality.label}",
                                onClick = {
                                    menuExpanded = false
                                    activeSubPanel = if (activeSubPanel == FloatingSubPanel.QUALITY) {
                                        FloatingSubPanel.NONE
                                    } else {
                                        FloatingSubPanel.QUALITY
                                    }
                                },
                            )
                            if (uiState.monitorCount > 1) {
                                FloatingMenuRow(
                                    label = "Monitor ${uiState.monitorIndex + 1}/${uiState.monitorCount}",
                                    onClick = {
                                        screenVm.cycleMonitor()
                                        menuExpanded = false
                                    },
                                )
                            }
                            FloatingMenuRow(
                                label = if (isFullscreen) "Show Tabs" else "Fullscreen",
                                onClick = {
                                    menuExpanded = false
                                    onToggleFullscreen()
                                },
                            )
                            FloatingMenuRow(
                                label = if (showTopHud) "Hide HUD" else "Show HUD",
                                onClick = {
                                    showTopHud = !showTopHud
                                    menuExpanded = false
                                },
                            )
                            FloatingMenuRow(
                                label = "Exit Screen",
                                danger = true,
                                onClick = {
                                    menuExpanded = false
                                    onGoToTouchpad()
                                },
                            )
                        }
                    }

                    // Always-accessible Floating Pill: [ Keyboard | AnyDesk Menu ]
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(26.dp))
                            .background(Color(0xE0111927))
                            .border(1.2.dp, RemoteColors.NeonCyan.copy(alpha = 0.7f), RoundedCornerShape(26.dp))
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 1-Tap Floating Keyboard Button
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(RemoteColors.NeonCyan.copy(alpha = 0.18f))
                                .border(1.dp, RemoteColors.NeonCyan.copy(alpha = 0.7f), CircleShape)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    menuExpanded = false
                                    activeSubPanel = FloatingSubPanel.NONE
                                    showKeyboard = true
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            KeyboardMiniIcon(
                                color = RemoteColors.NeonCyan,
                                modifier = Modifier.size(21.dp),
                            )
                        }

                        // AnyDesk Floating Quick-Menu Bubble
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (menuExpanded) RemoteColors.NeonCyan
                                    else Color(0xFF1A2538)
                                )
                                .border(
                                    1.dp,
                                    if (menuExpanded) RemoteColors.NeonCyan else RemoteColors.NeonBorderBright,
                                    CircleShape,
                                )
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    menuExpanded = !menuExpanded
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (menuExpanded) "✕" else "☰",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (menuExpanded) Color(0xFF001D2B) else RemoteColors.TextPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenStatusPlaceholder(
    connectionState: ConnectionState,
    isStreaming: Boolean,
    onGoToConnect: () -> Unit,
    onRetry: () -> Unit,
    onRefreshFrame: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(RemoteColors.PanelBg)
                .border(1.dp, RemoteColors.NeonBorder, RoundedCornerShape(16.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (connectionState) {
                is ConnectionState.Connected -> {
                    CircularProgressIndicator(
                        color = RemoteColors.NeonCyan,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        text = if (isStreaming) "Loading PC Screen..." else "Starting Screen Stream...",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = RemoteColors.TextPrimary,
                    )
                    Text(
                        text = "Connected to ${connectionState.pcName} • Make sure PC Agent is updated",
                        fontSize = 12.sp,
                        color = RemoteColors.TextMuted,
                    )
                    Button(
                        onClick = onRefreshFrame,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RemoteColors.TileBg,
                            contentColor = RemoteColors.NeonCyan,
                        ),
                    ) {
                        Text("Refresh Screen")
                    }
                }
                is ConnectionState.Connecting -> {
                    CircularProgressIndicator(
                        color = RemoteColors.ConnectingAmber,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        text = "Connecting (${connectionState.target})...",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = RemoteColors.TextPrimary,
                    )
                }
                else -> {
                    Text(
                        text = "PC Not Connected",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = RemoteColors.TextPrimary,
                    )
                    val subtitle = (connectionState as? ConnectionState.Error)?.message
                        ?: "Connect via Local Wi-Fi, P2P, or Internet (VPS) to view your PC screen."
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = RemoteColors.TextMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RemoteColors.NeonCyan,
                                contentColor = Color(0xFF001D2B),
                            ),
                        ) {
                            Text("Reconnect", fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = onGoToConnect,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RemoteColors.TileBg,
                                contentColor = RemoteColors.TextPrimary,
                            ),
                        ) {
                            Text("Settings")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HudChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) RemoteColors.NeonCyan.copy(alpha = 0.22f) else RemoteColors.TileBg)
            .border(
                1.dp,
                if (active) RemoteColors.NeonCyan else RemoteColors.NeonBorder.copy(alpha = 0.6f),
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (active) RemoteColors.NeonCyan else RemoteColors.TextPrimary,
        )
    }
}

@Composable
private fun FloatingMenuRow(
    label: String,
    accent: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val textColor = when {
        danger -> RemoteColors.DangerRed
        accent -> RemoteColors.NeonCyan
        else -> RemoteColors.TextPrimary
    }
    Box(
        modifier = Modifier
            .width(138.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(RemoteColors.TileBg.copy(alpha = 0.9f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FloatingActionsBar(
    onKeyCombo: (List<String>) -> Unit,
    onSpecialKey: (String) -> Unit,
    onRightClick: () -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xEB101724))
            .border(1.dp, RemoteColors.NeonBorderBright, RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ActionPill("Right Click", onClick = onRightClick)
            ActionPill("Alt+Tab") { onKeyCombo(listOf("alt", "tab")) }
            ActionPill("Win") { onSpecialKey("win") }
            ActionPill("Desktop") { onKeyCombo(listOf("win", "d")) }
            ActionPill("Task Mgr") { onKeyCombo(listOf("ctrl", "shift", "esc")) }
            ActionPill("Esc") { onSpecialKey("esc") }
            ActionPill("Close App") { onKeyCombo(listOf("alt", "f4")) }
            ActionPill("Refresh", onClick = onRefresh)
        }
        ActionPill("✕", danger = true, onClick = onClose)
    }
}

@Composable
private fun FloatingQualityBar(
    selected: ScreenQuality,
    onSelect: (ScreenQuality) -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xEB101724))
            .border(1.dp, RemoteColors.NeonBorderBright, RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Quality:",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = RemoteColors.TextMuted,
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ScreenQuality.entries.forEach { q ->
                val isSel = q == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isSel) RemoteColors.NeonCyan else RemoteColors.TileBg)
                        .border(
                            1.dp,
                            if (isSel) RemoteColors.NeonCyan else RemoteColors.NeonBorder,
                            RoundedCornerShape(9.dp),
                        )
                        .clickable { onSelect(q) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = q.label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSel) Color(0xFF001D2B) else RemoteColors.TextPrimary,
                    )
                }
            }
        }
        ActionPill("✕", danger = true, onClick = onClose)
    }
}

@Composable
private fun ActionPill(
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(RemoteColors.TileBg)
            .border(
                1.dp,
                if (danger) RemoteColors.DangerRed.copy(alpha = 0.7f) else RemoteColors.NeonBorder,
                RoundedCornerShape(9.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (danger) RemoteColors.DangerRed else RemoteColors.TextPrimary,
        )
    }
}

@Composable
fun ScreenTabIcon(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width
        val h = size.height
        // Monitor rectangle
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.08f, h * 0.14f),
            size = Size(w * 0.84f, h * 0.56f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx()),
            style = stroke,
        )
        // Monitor stand neck + base
        drawLine(
            color = color,
            start = Offset(w * 0.50f, h * 0.70f),
            end = Offset(w * 0.50f, h * 0.86f),
            strokeWidth = 1.9.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(w * 0.30f, h * 0.86f),
            end = Offset(w * 0.70f, h * 0.86f),
            strokeWidth = 1.9.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun RotateIcon(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val pad = size.minDimension * 0.16f
        val arcSize = size.minDimension - pad * 2f
        drawArc(
            color = color,
            startAngle = -55f,
            sweepAngle = 275f,
            useCenter = false,
            topLeft = Offset(pad, pad),
            size = Size(arcSize, arcSize),
            style = stroke,
        )
        val tipX = size.width * 0.78f
        val tipY = size.height * 0.24f
        val arrow = Path().apply {
            moveTo(tipX - 3.dp.toPx(), tipY - 0.5.dp.toPx())
            lineTo(tipX + 1.dp.toPx(), tipY + 0.5.dp.toPx())
            lineTo(tipX - 0.5.dp.toPx(), tipY - 4.dp.toPx())
        }
        drawPath(arrow, color = color, style = stroke)
    }
}

/**
 * Dedicated translucent 1-finger vertical scroll strip docked on the right edge of Screen Mode.
 * Dragging inside this bar smoothly scrolls the PC window without moving the cursor or zooming.
 */
@Composable
private fun EdgeScrollStrip(
    onScrollDelta: (dyPx: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val latestOnScroll by rememberUpdatedState(onScrollDelta)
    var isActive by remember { mutableStateOf(false) }
    var thumbFraction by remember { mutableFloatStateOf(0.5f) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isActive) Color(0xAA0D192B) else Color(0x5C0D131D))
            .border(
                width = if (isActive) 1.3.dp else 1.dp,
                color = if (isActive) RemoteColors.NeonCyan else RemoteColors.NeonBorder.copy(alpha = 0.55f),
                shape = RoundedCornerShape(14.dp),
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    isActive = true
                    val trackH = size.height.toFloat().coerceAtLeast(1f)
                    thumbFraction = (down.position.y / trackH).coerceIn(0.12f, 0.88f)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed || change.changedToUp()) {
                            change.consume()
                            break
                        }
                        val delta = change.positionChange()
                        if (abs(delta.y) > 0.05f) {
                            thumbFraction = (change.position.y / trackH).coerceIn(0.12f, 0.88f)
                            latestOnScroll(delta.y)
                        }
                        change.consume()
                    }
                    isActive = false
                    thumbFraction = 0.5f
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width * 0.5f
            val h = size.height
            val hintColor = if (isActive) RemoteColors.NeonCyan else RemoteColors.TextMuted.copy(alpha = 0.7f)
            val stroke = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

            // Up chevron hint near top
            val topY = 12.dp.toPx()
            val cw = 4.dp.toPx()
            val upArrow = Path().apply {
                moveTo(cx - cw, topY + 3.dp.toPx())
                lineTo(cx, topY)
                lineTo(cx + cw, topY + 3.dp.toPx())
            }
            drawPath(upArrow, color = hintColor, style = stroke)

            // Down chevron hint near bottom
            val botY = h - 12.dp.toPx()
            val downArrow = Path().apply {
                moveTo(cx - cw, botY - 3.dp.toPx())
                lineTo(cx, botY)
                lineTo(cx + cw, botY - 3.dp.toPx())
            }
            drawPath(downArrow, color = hintColor, style = stroke)

            // Subtle vertical center rail
            drawLine(
                color = hintColor.copy(alpha = 0.25f),
                start = Offset(cx, topY + 10.dp.toPx()),
                end = Offset(cx, botY - 10.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )

            // Active / idle thumb indicator capsule
            val thumbH = if (isActive) 44.dp.toPx() else 30.dp.toPx()
            val thumbW = if (isActive) 6.dp.toPx() else 4.dp.toPx()
            val usableTop = topY + 12.dp.toPx() + thumbH * 0.5f
            val usableBot = (botY - 12.dp.toPx() - thumbH * 0.5f).coerceAtLeast(usableTop)
            val thumbCenterY = (usableTop + (usableBot - usableTop) * ((thumbFraction - 0.12f) / 0.76f).coerceIn(0f, 1f))
            drawRoundRect(
                color = if (isActive) RemoteColors.NeonCyan else RemoteColors.NeonCyan.copy(alpha = 0.55f),
                topLeft = Offset(cx - thumbW * 0.5f, thumbCenterY - thumbH * 0.5f),
                size = Size(thumbW, thumbH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(thumbW, thumbW),
            )
        }
    }
}
