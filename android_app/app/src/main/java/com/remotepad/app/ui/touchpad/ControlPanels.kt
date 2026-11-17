package com.remotepad.app.ui.touchpad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remotepad.app.ui.theme.RemoteColors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class ControlPanelTab {
    NONE,
    MEDIA,
    POWER,
    PPT,
    WEB,
    AIR_MOUSE,
    CONNECTION,
}

@Composable
fun NeonPanelContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RemoteColors.PanelBg)
            .border(1.dp, RemoteColors.NeonBorder, RoundedCornerShape(14.dp))
            .padding(10.dp),
    ) {
        content()
    }
}

@Composable
private fun NeonTileButton(
    modifier: Modifier = Modifier,
    height: Dp = 50.dp,
    borderColor: Color = RemoteColors.NeonBorder,
    backgroundColor: Color = RemoteColors.TileBg,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// =====================================================================
// 1. MEDIA CONTROL PANEL (Orange Volume Arc + Center Mute + 5 Buttons)
// =====================================================================
@Composable
fun MediaControlPanel(
    volumeLevel: Float,
    isMuted: Boolean,
    onSetVolume: (level: Float, immediate: Boolean) -> Unit,
    onMediaAction: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val volumePct = kotlin.math.round(volumeLevel.coerceIn(0f, 1f) * 100f).toInt()

    NeonPanelContainer {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(178.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Left: Circular Orange Volume Arc (0-100% synced) + Center Mute + Vol - / + buttons
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    VolumeArcDial(
                        level = volumeLevel,
                        onVolumeChange = { newLevel, finished ->
                            onSetVolume(newLevel, finished)
                        },
                    )

                    // Center circular Mute button + live synced percentage
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(RemoteColors.PanelBg)
                                .border(
                                    1.2.dp,
                                    if (isMuted) RemoteColors.DangerRed else RemoteColors.NeonBorder,
                                    CircleShape,
                                )
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onMediaAction("mute")
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            MuteSpeakerIcon(
                                color = RemoteColors.MediaOrange,
                                muted = isMuted,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        Text(
                            text = if (isMuted) "MUTED" else "$volumePct%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isMuted) RemoteColors.DangerRed else RemoteColors.MediaOrange,
                        )
                    }
                }

                // Direct Volume - / + tap buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    NeonTileButton(
                        modifier = Modifier.width(62.dp),
                        height = 34.dp,
                        onClick = { onMediaAction("vol_down") },
                    ) {
                        Text("Vol −", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RemoteColors.MediaOrange)
                    }
                    NeonTileButton(
                        modifier = Modifier.width(62.dp),
                        height = 34.dp,
                        onClick = { onMediaAction("vol_up") },
                    ) {
                        Text("Vol +", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RemoteColors.MediaOrange)
                    }
                }
            }

            // Right: 5 Media Transport Buttons in exact diamond layout
            Column(
                modifier = Modifier
                    .weight(1.05f)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    NeonTileButton(
                        modifier = Modifier.width(64.dp),
                        height = 52.dp,
                        onClick = { onMediaAction("prev") },
                    ) {
                        PrevTrackIcon(Color.White, Modifier.size(24.dp))
                    }
                    NeonTileButton(
                        modifier = Modifier.width(64.dp),
                        height = 52.dp,
                        onClick = { onMediaAction("next") },
                    ) {
                        NextTrackIcon(Color.White, Modifier.size(24.dp))
                    }
                }

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    NeonTileButton(
                        modifier = Modifier.width(68.dp),
                        height = 52.dp,
                        borderColor = RemoteColors.NeonBorderBright,
                        onClick = { onMediaAction("play_pause") },
                    ) {
                        PlayPauseIcon(Color.White, Modifier.size(26.dp))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    NeonTileButton(
                        modifier = Modifier.width(64.dp),
                        height = 52.dp,
                        onClick = { onMediaAction("rewind") },
                    ) {
                        RewindIcon(Color.White, Modifier.size(24.dp))
                    }
                    NeonTileButton(
                        modifier = Modifier.width(64.dp),
                        height = 52.dp,
                        onClick = { onMediaAction("forward") },
                    ) {
                        FastForwardIcon(Color.White, Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun VolumeMediaModal(
    volumeLevel: Float,
    isMuted: Boolean,
    onSetVolume: (level: Float, immediate: Boolean) -> Unit,
    onMediaAction: (String) -> Unit,
    onInteract: (dragging: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val volumePct = kotlin.math.round(volumeLevel.coerceIn(0f, 1f) * 100f).toInt()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(292.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(RemoteColors.PanelBg)
                .border(1.2.dp, RemoteColors.NeonBorder, RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    // Consume taps inside the card so they don't dismiss the modal
                    detectTapGestures { onInteract(false) }
                }
                .padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Big Orange Circular Volume Arc + Large Centered Cyan Mute Circle
            Box(
                modifier = Modifier.size(214.dp),
                contentAlignment = Alignment.Center,
            ) {
                VolumeArcDial(
                    level = volumeLevel,
                    diameter = 214.dp,
                    strokeWidth = 17.dp,
                    onVolumeChange = { newLevel, finished ->
                        onInteract(!finished)
                        onSetVolume(newLevel, finished)
                    },
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(RemoteColors.DeepBg)
                            .border(
                                1.2.dp,
                                if (isMuted) RemoteColors.DangerRed else RemoteColors.NeonBorder,
                                CircleShape,
                            )
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onInteract(false)
                                onMediaAction("mute")
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        MuteSpeakerIcon(
                            color = if (isMuted) RemoteColors.DangerRed else RemoteColors.NeonCyan,
                            muted = isMuted,
                            modifier = Modifier.size(38.dp),
                        )
                    }
                    Text(
                        text = if (isMuted) "MUTED" else "$volumePct%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isMuted) RemoteColors.DangerRed else RemoteColors.MediaOrange,
                    )
                }
            }

            // Bottom Row: 5 Circular Neon-Bordered Media Buttons (exact reference layout)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularMediaButton(
                    diameter = 42.dp,
                    onClick = {
                        onInteract(false)
                        onMediaAction("prev")
                    },
                ) {
                    PrevTrackIcon(Color.White, Modifier.size(18.dp))
                }
                CircularMediaButton(
                    diameter = 42.dp,
                    onClick = {
                        onInteract(false)
                        onMediaAction("rewind")
                    },
                ) {
                    ArrowIcon(Color.White, pointRight = false, Modifier.size(19.dp))
                }
                CircularMediaButton(
                    diameter = 58.dp,
                    borderColor = RemoteColors.NeonBorderBright,
                    onClick = {
                        onInteract(false)
                        onMediaAction("play_pause")
                    },
                ) {
                    PlayPauseIcon(Color.White, Modifier.size(26.dp))
                }
                CircularMediaButton(
                    diameter = 42.dp,
                    onClick = {
                        onInteract(false)
                        onMediaAction("forward")
                    },
                ) {
                    ArrowIcon(Color.White, pointRight = true, Modifier.size(19.dp))
                }
                CircularMediaButton(
                    diameter = 42.dp,
                    onClick = {
                        onInteract(false)
                        onMediaAction("next")
                    },
                ) {
                    NextTrackIcon(Color.White, Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun CircularMediaButton(
    diameter: Dp,
    borderColor: Color = RemoteColors.NeonBorder,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(RemoteColors.DeepBg)
            .border(1.2.dp, borderColor, CircleShape)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun VolumeArcDial(
    level: Float,
    diameter: Dp = 134.dp,
    strokeWidth: Dp = 14.dp,
    onVolumeChange: (newLevel: Float, finished: Boolean) -> Unit,
) {
    // Arc starts at 160 deg (bottom-left) and sweeps 220 deg clockwise to 20 deg (bottom-right)
    val startAngle = 160f
    val sweepTotal = 220f
    var lastDragLevel by remember { mutableFloatStateOf(level) }

    fun angleToLevel(offset: Offset, size: Size): Float? {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val dx = offset.x - cx
        val dy = offset.y - cy
        // Ignore taps inside the center mute circle
        val dist = kotlin.math.hypot(dx, dy)
        if (dist < size.minDimension * 0.24f) return null

        var deg = (atan2(dy, dx) * 180.0 / PI).toFloat()
        if (deg < 0f) deg += 360f
        var rel = deg - startAngle
        if (rel < 0f) rel += 360f
        if (rel > sweepTotal) {
            // Snap cleanly to 0% or 100% when dragging past either end
            rel = if (rel < sweepTotal + (360f - sweepTotal) / 2f) sweepTotal else 0f
        }
        return (rel / sweepTotal).coerceIn(0f, 1f)
    }

    Canvas(
        modifier = Modifier
            .size(diameter)
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val newLevel = angleToLevel(pos, Size(size.width.toFloat(), size.height.toFloat()))
                    if (newLevel != null) {
                        lastDragLevel = newLevel
                        onVolumeChange(newLevel, true)
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val newLevel = angleToLevel(pos, Size(size.width.toFloat(), size.height.toFloat()))
                        if (newLevel != null) {
                            lastDragLevel = newLevel
                            onVolumeChange(newLevel, false)
                        }
                    },
                    onDragEnd = {
                        onVolumeChange(lastDragLevel, true)
                    },
                    onDragCancel = {
                        onVolumeChange(lastDragLevel, true)
                    },
                ) { change, _ ->
                    change.consume()
                    val newLevel = angleToLevel(change.position, Size(size.width.toFloat(), size.height.toFloat()))
                    if (newLevel != null) {
                        lastDragLevel = newLevel
                        onVolumeChange(newLevel, false)
                    }
                }
            },
    ) {
        val strokePx = strokeWidth.toPx()
        val pad = strokePx / 2f + 8.dp.toPx()
        val arcSize = Size(size.width - pad * 2, size.height - pad * 2)
        val topLeft = Offset(pad, pad)

        // Inactive dark track arc
        drawArc(
            color = Color(0xFF0A141F),
            startAngle = startAngle,
            sweepAngle = sweepTotal,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Butt),
        )

        // Active bright orange arc
        val activeSweep = sweepTotal * level.coerceIn(0f, 1f)
        if (activeSweep > 0.5f) {
            drawArc(
                color = RemoteColors.MediaOrange,
                startAngle = startAngle,
                sweepAngle = activeSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Butt),
            )
        }

        // Thumb indicator on the arc
        val thumbAngleRad = ((startAngle + activeSweep) * PI / 180.0)
        val radius = arcSize.width / 2f
        val cx = size.width / 2f + radius * cos(thumbAngleRad).toFloat()
        val cy = size.height / 2f + radius * sin(thumbAngleRad).toFloat()
        val outerThumb = (strokePx * 0.68f).coerceAtLeast(9.dp.toPx())
        drawCircle(
            color = RemoteColors.NeonCyan,
            radius = outerThumb,
            center = Offset(cx, cy),
        )
        drawCircle(
            color = RemoteColors.MediaOrange,
            radius = outerThumb - 2.5.dp.toPx(),
            center = Offset(cx, cy),
        )
    }
}

// =====================================================================
// 2. POWER CONTROL PANEL (Sleep, Hibernate, Shutdown, Restart, Lock...)
// =====================================================================
@Composable
fun PowerControlPanel(
    onPowerAction: (String) -> Unit,
) {
    var pendingConfirm by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun triggerPower(label: String, action: String, confirm: Boolean) {
        if (confirm) {
            pendingConfirm = label to action
        } else {
            onPowerAction(action)
        }
    }

    NeonPanelContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PowerTile("SLEEP", Modifier.weight(1f)) {
                    triggerPower("Sleep PC", "sleep", confirm = true)
                }
                PowerTile("HIBERNATE", Modifier.weight(1f)) {
                    triggerPower("Hibernate PC", "hibernate", confirm = true)
                }
                PowerTile("SHUTDOWN", Modifier.weight(1f), danger = true) {
                    triggerPower("Shutdown PC", "shutdown", confirm = true)
                }
                PowerTile("RESTART", Modifier.weight(1f), danger = true) {
                    triggerPower("Restart PC", "restart", confirm = true)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PowerTile("LOCK", Modifier.weight(1f)) {
                    triggerPower("Lock PC", "lock", confirm = false)
                }
                PowerTile("SHUT OFF DISPLAY", Modifier.weight(1.15f)) {
                    triggerPower("Turn off PC display", "display_off", confirm = false)
                }
                PowerTile("SIGN OUT", Modifier.weight(1f), danger = true) {
                    triggerPower("Sign out Windows", "sign_out", confirm = true)
                }
            }
        }
    }

    pendingConfirm?.let { (label, action) ->
        AlertDialog(
            onDismissRequest = { pendingConfirm = null },
            containerColor = RemoteColors.PanelBg,
            title = { Text(label, color = RemoteColors.TextPrimary) },
            text = {
                Text(
                    "Are you sure you want to run \"$label\" on your PC?",
                    color = RemoteColors.TextMuted,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingConfirm = null
                        onPowerAction(action)
                    },
                ) {
                    Text("Confirm", color = RemoteColors.DangerRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirm = null }) {
                    Text("Cancel", color = RemoteColors.NeonCyan)
                }
            },
        )
    }
}

@Composable
private fun PowerTile(
    label: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    NeonTileButton(
        modifier = modifier,
        height = 54.dp,
        borderColor = if (danger) RemoteColors.NeonBorder else RemoteColors.NeonBorder,
        onClick = onClick,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = RemoteColors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// =====================================================================
// 3. PPT (PRESENTATION) CONTROL PANEL
// =====================================================================
@Composable
fun PptControlPanel(
    onKeyCombo: (List<String>) -> Unit,
) {
    NeonPanelContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PptTile("Cursor", Modifier.weight(1f)) { onKeyCombo(listOf("ctrl", "a")) }
                PptTile("Pen", Modifier.weight(1f)) { onKeyCombo(listOf("ctrl", "p")) }
                PptTile("Eraser", Modifier.weight(1f)) { onKeyCombo(listOf("ctrl", "e")) }
                PptTile("Clean", Modifier.weight(1f)) { onKeyCombo(listOf("e")) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PptTile("Play", Modifier.weight(1f)) { onKeyCombo(listOf("f5")) }
                PptTile("Play Here", Modifier.weight(1f)) { onKeyCombo(listOf("shift", "f5")) }
                PptTile("Begin", Modifier.weight(1f)) { onKeyCombo(listOf("home")) }
                PptTile("End", Modifier.weight(1f)) { onKeyCombo(listOf("end")) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PptTile("Previous", Modifier.weight(1f)) { onKeyCombo(listOf("left")) }
                PptTile("Stop Play", Modifier.weight(1f)) { onKeyCombo(listOf("esc")) }
                PptTile("Next", Modifier.weight(1f)) { onKeyCombo(listOf("right")) }
            }
        }
    }
}

@Composable
private fun PptTile(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    NeonTileButton(
        modifier = modifier,
        height = 46.dp,
        onClick = onClick,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = RemoteColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// =====================================================================
// 4. WEB (BROWSER) CONTROL PANEL
// =====================================================================
@Composable
fun WebControlPanel(
    onKeyCombo: (List<String>) -> Unit,
) {
    NeonPanelContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NeonTileButton(
                    modifier = Modifier.weight(1f),
                    height = 54.dp,
                    onClick = { onKeyCombo(listOf("ctrl", "l")) },
                ) {
                    SearchIcon(Color.White, Modifier.size(24.dp))
                }
                NeonTileButton(
                    modifier = Modifier.weight(1f),
                    height = 54.dp,
                    onClick = { onKeyCombo(listOf("alt", "home")) },
                ) {
                    HomeIcon(Color.White, Modifier.size(24.dp))
                }
                NeonTileButton(
                    modifier = Modifier.weight(1f),
                    height = 54.dp,
                    onClick = { onKeyCombo(listOf("ctrl", "d")) },
                ) {
                    HeartIcon(Color.White, Modifier.size(24.dp))
                }
                NeonTileButton(
                    modifier = Modifier.weight(1f),
                    height = 54.dp,
                    onClick = { onKeyCombo(listOf("f5")) },
                ) {
                    RefreshIcon(Color.White, Modifier.size(24.dp))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NeonTileButton(
                    modifier = Modifier.weight(1f),
                    height = 54.dp,
                    onClick = { onKeyCombo(listOf("browser_stop")) },
                ) {
                    CloseIcon(Color.White, Modifier.size(22.dp))
                }
                NeonTileButton(
                    modifier = Modifier.weight(1f),
                    height = 54.dp,
                    onClick = { onKeyCombo(listOf("alt", "right")) },
                ) {
                    ArrowIcon(Color.White, pointRight = true, Modifier.size(24.dp))
                }
                NeonTileButton(
                    modifier = Modifier.weight(1f),
                    height = 54.dp,
                    onClick = { onKeyCombo(listOf("alt", "left")) },
                ) {
                    ArrowIcon(Color.White, pointRight = false, Modifier.size(24.dp))
                }
            }
        }
    }
}

// =====================================================================
// 5. 3D AIR MOUSE (GYROSCOPE) CONTROL PANEL (with Speed - / +)
// =====================================================================
@Composable
fun AirMouseControlPanel(
    isGyroAvailable: Boolean,
    isEnabled: Boolean,
    airMouseSpeed: Float,
    touchpadSpeed: Float,
    onToggleEnabled: () -> Unit,
    onDecreaseAirSpeed: () -> Unit,
    onIncreaseAirSpeed: () -> Unit,
    onAirSpeedChange: (Float) -> Unit,
    onTouchpadSpeedChange: (Float) -> Unit,
) {
    NeonPanelContainer {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "3D Air Mouse (Gyroscope)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = RemoteColors.TextPrimary,
                    )
                    Text(
                        text = when {
                            !isGyroAvailable -> "Gyroscope sensor not found on this phone"
                            isEnabled -> "Active — move phone in the air to move cursor"
                            else -> "Paused — tap ON to control cursor with phone motion"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isEnabled) RemoteColors.NeonCyan else RemoteColors.TextMuted,
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isEnabled) RemoteColors.NeonCyan else RemoteColors.TileBg
                        )
                        .border(
                            1.dp,
                            if (isEnabled) RemoteColors.NeonBorderBright else RemoteColors.NeonBorder,
                            RoundedCornerShape(10.dp),
                        )
                        .clickable(enabled = isGyroAvailable, onClick = onToggleEnabled)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isEnabled) "ON" else "OFF",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isEnabled) Color(0xFF001D2B) else RemoteColors.TextPrimary,
                    )
                }
            }

            // Air Mouse Speed  -  [ Slider / Value ]  +
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NeonTileButton(
                    modifier = Modifier.width(46.dp),
                    height = 40.dp,
                    onClick = onDecreaseAirSpeed,
                ) {
                    Text("−", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = RemoteColors.NeonCyan)
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "3D Speed  ${"%.1f".format(airMouseSpeed)}x",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = RemoteColors.TextPrimary,
                    )
                    Slider(
                        value = airMouseSpeed,
                        onValueChange = onAirSpeedChange,
                        valueRange = 0.2f..4.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = RemoteColors.NeonCyan,
                            activeTrackColor = RemoteColors.NeonCyan,
                            inactiveTrackColor = RemoteColors.TileBg,
                        ),
                        modifier = Modifier.height(24.dp),
                    )
                }

                NeonTileButton(
                    modifier = Modifier.width(46.dp),
                    height = 40.dp,
                    onClick = onIncreaseAirSpeed,
                ) {
                    Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = RemoteColors.NeonCyan)
                }
            }

            // Compact Touchpad speed adjustment row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Pad ${"%.1f".format(touchpadSpeed)}x",
                    fontSize = 11.sp,
                    color = RemoteColors.TextMuted,
                )
                Slider(
                    value = touchpadSpeed,
                    onValueChange = onTouchpadSpeedChange,
                    valueRange = 0.4f..3.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = RemoteColors.NeonBorderBright,
                        activeTrackColor = RemoteColors.NeonBorder,
                        inactiveTrackColor = RemoteColors.TileBg,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(22.dp),
                )
            }
        }
    }
}

// =====================================================================
// CRISP CUSTOM CANVAS ICONS (Zero extra APK dependency bloat!)
// =====================================================================

@Composable
fun MediaNoteIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Two stems + top beam
        drawLine(color, Offset(w * 0.34f, h * 0.72f), Offset(w * 0.34f, h * 0.24f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.78f, h * 0.64f), Offset(w * 0.78f, h * 0.16f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.34f, h * 0.24f), Offset(w * 0.78f, h * 0.16f), strokeWidth = stroke.width * 1.2f, cap = StrokeCap.Round)
        drawCircle(color, radius = w * 0.14f, center = Offset(w * 0.24f, h * 0.76f))
        drawCircle(color, radius = w * 0.14f, center = Offset(w * 0.68f, h * 0.68f))
    }
}

@Composable
fun PowerSymbolIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.1f
        val pad = w * 0.14f
        drawArc(
            color = color,
            startAngle = -55f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(pad, pad),
            size = Size(w - pad * 2, h - pad * 2),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(w * 0.5f, h * 0.10f),
            end = Offset(w * 0.5f, h * 0.48f),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun PptSlideIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.10f, h * 0.16f),
            size = Size(w * 0.80f, h * 0.68f),
            cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
            style = Stroke(width = sw),
        )
        // Vertical P stem + loop
        drawLine(color, Offset(w * 0.34f, h * 0.68f), Offset(w * 0.34f, h * 0.32f), strokeWidth = sw, cap = StrokeCap.Round)
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.26f, h * 0.32f),
            size = Size(w * 0.30f, h * 0.20f),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        // Small slide bars on right
        drawLine(color, Offset(w * 0.62f, h * 0.44f), Offset(w * 0.78f, h * 0.44f), strokeWidth = sw * 0.85f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.62f, h * 0.60f), Offset(w * 0.78f, h * 0.60f), strokeWidth = sw * 0.85f, cap = StrokeCap.Round)
    }
}

@Composable
fun WebGlobeIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        val r = w * 0.38f
        val c = Offset(w / 2f, h / 2f)
        drawCircle(color, radius = r, center = c, style = Stroke(width = sw))
        drawOval(
            color = color,
            topLeft = Offset(w * 0.32f, h * 0.12f),
            size = Size(w * 0.36f, h * 0.76f),
            style = Stroke(width = sw * 0.85f),
        )
        drawLine(color, Offset(w * 0.14f, h * 0.50f), Offset(w * 0.86f, h * 0.50f), strokeWidth = sw * 0.85f)
    }
}

@Composable
fun KeyboardMiniIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.08f
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.08f, h * 0.22f),
            size = Size(w * 0.84f, h * 0.56f),
            cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
            style = Stroke(width = sw),
        )
        // Key dots
        val cols = listOf(0.24f, 0.41f, 0.58f, 0.75f)
        for (x in cols) {
            drawCircle(color, radius = w * 0.045f, center = Offset(w * x, h * 0.40f))
        }
        // Spacebar
        drawLine(color, Offset(w * 0.28f, h * 0.62f), Offset(w * 0.72f, h * 0.62f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun MicIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.10f
        // Mic capsule
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.34f, h * 0.10f),
            size = Size(w * 0.32f, h * 0.48f),
            cornerRadius = CornerRadius(w * 0.16f, w * 0.16f),
        )
        // Cradle U-arc
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.20f, h * 0.28f),
            size = Size(w * 0.60f, h * 0.44f),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        // Stand stem + base
        drawLine(color, Offset(w * 0.50f, h * 0.72f), Offset(w * 0.50f, h * 0.88f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.32f, h * 0.88f), Offset(w * 0.68f, h * 0.88f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun MouseTabIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.24f, h * 0.10f),
            size = Size(w * 0.52f, h * 0.80f),
            cornerRadius = CornerRadius(w * 0.26f, w * 0.26f),
            style = Stroke(width = sw),
        )
        drawLine(color, Offset(w * 0.50f, h * 0.22f), Offset(w * 0.50f, h * 0.42f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun FolderTabIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        val path = Path().apply {
            moveTo(w * 0.12f, h * 0.26f)
            lineTo(w * 0.42f, h * 0.26f)
            lineTo(w * 0.50f, h * 0.36f)
            lineTo(w * 0.88f, h * 0.36f)
            lineTo(w * 0.88f, h * 0.78f)
            lineTo(w * 0.12f, h * 0.78f)
            close()
        }
        drawPath(path, color = color, style = Stroke(width = sw, join = StrokeJoin.Round))
    }
}

@Composable
private fun MuteSpeakerIcon(color: Color, muted: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val speaker = Path().apply {
            moveTo(w * 0.16f, h * 0.38f)
            lineTo(w * 0.34f, h * 0.38f)
            lineTo(w * 0.56f, h * 0.20f)
            lineTo(w * 0.56f, h * 0.80f)
            lineTo(w * 0.34f, h * 0.62f)
            lineTo(w * 0.16f, h * 0.62f)
            close()
        }
        drawPath(speaker, color = color)
        val sw = w * 0.08f
        drawArc(
            color = color,
            startAngle = -40f,
            sweepAngle = 80f,
            useCenter = false,
            topLeft = Offset(w * 0.48f, h * 0.30f),
            size = Size(w * 0.30f, h * 0.40f),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = -45f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(w * 0.54f, h * 0.20f),
            size = Size(w * 0.38f, h * 0.60f),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        // Diagonal slash (always shown faintly or boldly when muted like screenshot 1)
        drawLine(
            color = if (muted) RemoteColors.DangerRed else color,
            start = Offset(w * 0.18f, h * 0.18f),
            end = Offset(w * 0.84f, h * 0.84f),
            strokeWidth = w * 0.10f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawTriangle(color: Color, p1: Offset, p2: Offset, p3: Offset) {
    val path = Path().apply {
        moveTo(p1.x, p1.y)
        lineTo(p2.x, p2.y)
        lineTo(p3.x, p3.y)
        close()
    }
    drawPath(path, color = color)
}

@Composable
private fun PrevTrackIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRect(color, Offset(w * 0.18f, h * 0.20f), Size(w * 0.13f, h * 0.60f))
        drawTriangle(color, Offset(w * 0.32f, h * 0.50f), Offset(w * 0.82f, h * 0.20f), Offset(w * 0.82f, h * 0.80f))
    }
}

@Composable
private fun NextTrackIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawTriangle(color, Offset(w * 0.18f, h * 0.20f), Offset(w * 0.68f, h * 0.50f), Offset(w * 0.18f, h * 0.80f))
        drawRect(color, Offset(w * 0.69f, h * 0.20f), Size(w * 0.13f, h * 0.60f))
    }
}

@Composable
private fun PlayPauseIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawTriangle(color, Offset(w * 0.12f, h * 0.20f), Offset(w * 0.50f, h * 0.50f), Offset(w * 0.12f, h * 0.80f))
        drawRect(color, Offset(w * 0.58f, h * 0.22f), Size(w * 0.11f, h * 0.56f))
        drawRect(color, Offset(w * 0.76f, h * 0.22f), Size(w * 0.11f, h * 0.56f))
    }
}

@Composable
private fun RewindIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawTriangle(color, Offset(w * 0.12f, h * 0.50f), Offset(w * 0.50f, h * 0.22f), Offset(w * 0.50f, h * 0.78f))
        drawTriangle(color, Offset(w * 0.48f, h * 0.50f), Offset(w * 0.86f, h * 0.22f), Offset(w * 0.86f, h * 0.78f))
    }
}

@Composable
private fun FastForwardIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawTriangle(color, Offset(w * 0.14f, h * 0.22f), Offset(w * 0.52f, h * 0.50f), Offset(w * 0.14f, h * 0.78f))
        drawTriangle(color, Offset(w * 0.50f, h * 0.22f), Offset(w * 0.88f, h * 0.50f), Offset(w * 0.50f, h * 0.78f))
    }
}

@Composable
private fun SearchIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.11f
        drawCircle(color, radius = w * 0.26f, center = Offset(w * 0.42f, h * 0.42f), style = Stroke(width = sw))
        drawLine(color, Offset(w * 0.62f, h * 0.62f), Offset(w * 0.84f, h * 0.84f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
private fun HomeIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.50f, h * 0.14f)
            lineTo(w * 0.88f, h * 0.48f)
            lineTo(w * 0.76f, h * 0.48f)
            lineTo(w * 0.76f, h * 0.84f)
            lineTo(w * 0.58f, h * 0.84f)
            lineTo(w * 0.58f, h * 0.60f)
            lineTo(w * 0.42f, h * 0.60f)
            lineTo(w * 0.42f, h * 0.84f)
            lineTo(w * 0.24f, h * 0.84f)
            lineTo(w * 0.24f, h * 0.48f)
            lineTo(w * 0.12f, h * 0.48f)
            close()
        }
        drawPath(path, color = color)
    }
}

@Composable
private fun HeartIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.50f, h * 0.84f)
            cubicTo(w * 0.10f, h * 0.56f, w * 0.10f, h * 0.20f, w * 0.34f, h * 0.20f)
            cubicTo(w * 0.44f, h * 0.20f, w * 0.50f, h * 0.28f, w * 0.50f, h * 0.32f)
            cubicTo(w * 0.50f, h * 0.28f, w * 0.56f, h * 0.20f, w * 0.66f, h * 0.20f)
            cubicTo(w * 0.90f, h * 0.20f, w * 0.90f, h * 0.56f, w * 0.50f, h * 0.84f)
            close()
        }
        drawPath(path, color = color)
    }
}

@Composable
private fun RefreshIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.11f
        val pad = w * 0.18f
        drawArc(
            color = color,
            startAngle = 35f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(pad, pad),
            size = Size(w - pad * 2, h - pad * 2),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        drawTriangle(
            color,
            Offset(w * 0.82f, h * 0.18f),
            Offset(w * 0.84f, h * 0.44f),
            Offset(w * 0.60f, h * 0.36f),
        )
    }
}

@Composable
private fun CloseIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.12f
        drawLine(color, Offset(w * 0.20f, h * 0.20f), Offset(w * 0.80f, h * 0.80f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.80f, h * 0.20f), Offset(w * 0.20f, h * 0.80f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
private fun ArrowIcon(color: Color, pointRight: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.11f
        drawLine(color, Offset(w * 0.18f, h * 0.50f), Offset(w * 0.82f, h * 0.50f), strokeWidth = sw, cap = StrokeCap.Round)
        if (pointRight) {
            drawLine(color, Offset(w * 0.56f, h * 0.24f), Offset(w * 0.82f, h * 0.50f), strokeWidth = sw, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.56f, h * 0.76f), Offset(w * 0.82f, h * 0.50f), strokeWidth = sw, cap = StrokeCap.Round)
        } else {
            drawLine(color, Offset(w * 0.44f, h * 0.24f), Offset(w * 0.18f, h * 0.50f), strokeWidth = sw, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.44f, h * 0.76f), Offset(w * 0.18f, h * 0.50f), strokeWidth = sw, cap = StrokeCap.Round)
        }
    }
}
