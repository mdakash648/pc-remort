package com.remotepad.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object RemoteColors {
    val DeepBg = Color(0xFF03070C)
    val PanelBg = Color(0xFF060E16)
    val TileBg = Color(0xFF0A1724)
    val TilePressedBg = Color(0xFF102A42)
    val NeonCyan = Color(0xFF29B6F6)
    val NeonBorder = Color(0xFF2B7BB0)
    val NeonBorderBright = Color(0xFF38C6FF)
    val MediaOrange = Color(0xFFFF9F0A)
    val ConnectedGreen = Color(0xFF3FB950)
    val ConnectingAmber = Color(0xFFD29922)
    val DangerRed = Color(0xFFFF5252)
    val TextPrimary = Color(0xFFEAF4FF)
    val TextMuted = Color(0xFF7D95AA)
}

private val DarkColors = darkColorScheme(
    primary = RemoteColors.NeonCyan,
    onPrimary = Color(0xFF001D2B),
    secondary = Color(0xFF3FB950),
    background = RemoteColors.DeepBg,
    surface = RemoteColors.PanelBg,
    surfaceVariant = RemoteColors.TileBg,
    onBackground = RemoteColors.TextPrimary,
    onSurface = RemoteColors.TextPrimary,
    onSurfaceVariant = RemoteColors.TextMuted,
    outline = RemoteColors.NeonBorder,
    error = RemoteColors.DangerRed,
)

@Composable
fun RemotePadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
