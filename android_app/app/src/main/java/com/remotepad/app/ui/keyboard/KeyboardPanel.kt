package com.remotepad.app.ui.keyboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remotepad.app.data.model.ModifierKey
import com.remotepad.app.data.model.ModifierState
import com.remotepad.app.ui.theme.RemoteColors
import com.remotepad.app.ui.touchpad.MicIcon

private const val SENTINEL = "\u200B"

private enum class TypingMode {
    LIVE,
    PARAGRAPH,
}

private data class ExtraKeySpec(val label: String, val wireValue: String)
private data class ShortcutSpec(val label: String, val keys: List<String>)

private val QUICK_SHORTCUTS = listOf(
    ShortcutSpec("Copy", listOf("ctrl", "c")),
    ShortcutSpec("Paste", listOf("ctrl", "v")),
    ShortcutSpec("Cut", listOf("ctrl", "x")),
    ShortcutSpec("Undo", listOf("ctrl", "z")),
    ShortcutSpec("Redo", listOf("ctrl", "y")),
    ShortcutSpec("Select All", listOf("ctrl", "a")),
    ShortcutSpec("Screenshot", listOf("win", "shift", "s")),
    ShortcutSpec("Save", listOf("ctrl", "s")),
    ShortcutSpec("Find", listOf("ctrl", "f")),
    ShortcutSpec("Close", listOf("ctrl", "w")),
)

private val CORE_EXTRA_KEYS = listOf(
    ExtraKeySpec("Esc", "esc"),
    ExtraKeySpec("Tab", "tab"),
    ExtraKeySpec("Caps", "capslock"),
    ExtraKeySpec("Del", "del"),
)

private val NAV_KEYS = listOf(
    ExtraKeySpec("←", "left"),
    ExtraKeySpec("↑", "up"),
    ExtraKeySpec("↓", "down"),
    ExtraKeySpec("→", "right"),
    ExtraKeySpec("Home", "home"),
    ExtraKeySpec("End", "end"),
    ExtraKeySpec("PgUp", "pgup"),
    ExtraKeySpec("PgDn", "pgdn"),
    ExtraKeySpec("PrtSc", "prtsc"),
    ExtraKeySpec("Ins", "ins"),
    ExtraKeySpec("Bksp", "backspace"),
    ExtraKeySpec("Enter", "enter"),
)

private val FUNCTION_KEYS = (1..12).map { n -> ExtraKeySpec("F$n", "f$n") }

@Composable
fun KeyboardPanel(
    modifierStates: Map<ModifierKey, ModifierState>,
    isMicEnabled: Boolean,
    onToggleMic: () -> Unit,
    onToggleModifier: (ModifierKey) -> Unit,
    onReleaseAllModifiers: () -> Unit,
    onSpecialKey: (String) -> Unit,
    onKeyCombo: (List<String>) -> Unit,
    onTypeText: (String) -> Unit,
    onPasteParagraph: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val liveFocusRequester = remember { FocusRequester() }
    val paraFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current

    var typingMode by rememberSaveable { mutableStateOf(TypingMode.LIVE) }
    var showFunctionBar by rememberSaveable { mutableStateOf(false) }
    var showExtraKeys by rememberSaveable { mutableStateOf(false) }

    var liveFieldValue by remember {
        mutableStateOf(TextFieldValue(text = SENTINEL, selection = TextRange(SENTINEL.length)))
    }
    var paragraphText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(typingMode) {
        if (typingMode == TypingMode.LIVE) {
            liveFocusRequester.requestFocus()
        } else {
            paraFocusRequester.requestFocus()
        }
        keyboardController?.show()
    }

    fun flushLivePendingText(sendEnterAfter: Boolean = false) {
        val pending = liveFieldValue.text.replace(SENTINEL, "")
        if (pending.isNotEmpty()) {
            onTypeText(pending)
        }
        liveFieldValue = TextFieldValue(text = SENTINEL, selection = TextRange(SENTINEL.length))
        if (sendEnterAfter) {
            onSpecialKey("enter")
        }
    }

    fun sendParagraphNow() {
        if (paragraphText.isEmpty()) return
        onPasteParagraph(paragraphText)
        paragraphText = ""
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RemoteColors.PanelBg)
            .border(1.dp, RemoteColors.NeonBorder, RoundedCornerShape(14.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Compact Header Row: [ Live | Paragraph ] [ Mic ] [ Switch ]   ...   [ Fn ] [ ✕ ]
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(RemoteColors.DeepBg)
                        .border(1.dp, RemoteColors.NeonBorder, RoundedCornerShape(8.dp))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ModeSegmentButton(
                        label = "Live",
                        selected = typingMode == TypingMode.LIVE,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            typingMode = TypingMode.LIVE
                        },
                    )
                    ModeSegmentButton(
                        label = "Paragraph",
                        selected = typingMode == TypingMode.PARAGRAPH,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            typingMode = TypingMode.PARAGRAPH
                        },
                    )
                }

                // PC Mic ON/OFF button right next to Live | Paragraph
                Row(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isMicEnabled) RemoteColors.DangerRed.copy(alpha = 0.22f)
                            else RemoteColors.TileBg
                        )
                        .border(
                            1.dp,
                            if (isMicEnabled) RemoteColors.DangerRed else RemoteColors.NeonBorder,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleMic()
                        }
                        .padding(horizontal = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    MicIcon(
                        color = if (isMicEnabled) RemoteColors.DangerRed else RemoteColors.NeonCyan,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = if (isMicEnabled) "Mic ON" else "Mic",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isMicEnabled) RemoteColors.DangerRed else RemoteColors.TextPrimary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                // Window Switch (Alt+Tab) button right next to Mic
                SmallBarButton(
                    label = "Switch",
                    highlighted = false,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onKeyCombo(listOf("alt", "tab"))
                    },
                )

                // Screenshot (Win+Shift+S) button right next to Switch
                SmallBarButton(
                    label = "Screenshot",
                    highlighted = false,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onKeyCombo(listOf("win", "shift", "s"))
                    },
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallBarButton(
                    label = if (showFunctionBar) "Fn ▾" else "Fn ▴",
                    highlighted = showFunctionBar,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showFunctionBar = !showFunctionBar
                        if (!showFunctionBar) {
                            showExtraKeys = false
                        }
                    },
                )
                SmallBarButton(
                    label = "✕",
                    highlighted = false,
                    onClick = {
                        keyboardController?.hide()
                        onClose()
                    },
                )
            }
        }

        // Input Row: either Live instant typing OR Multi-paragraph paste-without-Enter box
        if (typingMode == TypingMode.LIVE) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(RemoteColors.DeepBg)
                        .border(1.dp, RemoteColors.NeonBorder, RoundedCornerShape(10.dp))
                        .clickable {
                            liveFocusRequester.requestFocus()
                            keyboardController?.show()
                        }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val visibleText = liveFieldValue.text.replace(SENTINEL, "")
                    if (visibleText.isEmpty()) {
                        Text(
                            text = "Live typing (English / বাংলা)...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = liveFieldValue,
                        onValueChange = { next ->
                            if (next.text.isEmpty()) {
                                onSpecialKey("backspace")
                                liveFieldValue = TextFieldValue(
                                    text = SENTINEL,
                                    selection = TextRange(SENTINEL.length),
                                )
                                return@BasicTextField
                            }

                            if (next.composition != null) {
                                liveFieldValue = next
                                return@BasicTextField
                            }

                            val committed = next.text.replace(SENTINEL, "")
                            if (committed.isNotEmpty()) {
                                onTypeText(committed)
                                liveFieldValue = TextFieldValue(
                                    text = SENTINEL,
                                    selection = TextRange(SENTINEL.length),
                                )
                            } else if (!next.text.contains(SENTINEL)) {
                                onSpecialKey("backspace")
                                liveFieldValue = TextFieldValue(
                                    text = SENTINEL,
                                    selection = TextRange(SENTINEL.length),
                                )
                            } else {
                                liveFieldValue = TextFieldValue(
                                    text = SENTINEL,
                                    selection = TextRange(SENTINEL.length),
                                )
                            }
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                        ),
                        cursorBrush = SolidColor(RemoteColors.NeonCyan),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = { flushLivePendingText(sendEnterAfter = true) },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(liveFocusRequester),
                    )
                }

                ActionChip(
                    label = "Send",
                    highlighted = liveFieldValue.text.replace(SENTINEL, "").isNotEmpty(),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        flushLivePendingText(sendEnterAfter = false)
                    },
                )
            }
        } else {
            // Paragraph Mode: paste or compose multi-paragraph text freely; sends via PC clipboard + Ctrl+V (NO Enter triggered!)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp, max = 112.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(RemoteColors.DeepBg)
                        .border(1.dp, RemoteColors.NeonBorderBright.copy(alpha = 0.75f), RoundedCornerShape(10.dp))
                        .clickable {
                            paraFocusRequester.requestFocus()
                            keyboardController?.show()
                        }
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    if (paragraphText.isEmpty()) {
                        Text(
                            text = "Paste or write paragraph here...\n(Sends to PC without triggering Enter)",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                    }
                    BasicTextField(
                        value = paragraphText,
                        onValueChange = { paragraphText = it },
                        singleLine = false,
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        ),
                        cursorBrush = SolidColor(RemoteColors.NeonCyan),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            autoCorrectEnabled = true,
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Default,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(paraFocusRequester),
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (paragraphText.isNotEmpty()) {
                        SmallBarButton(
                            label = "Clear",
                            highlighted = false,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                paragraphText = ""
                            },
                        )
                    }
                    ActionChip(
                        label = "Send",
                        highlighted = paragraphText.isNotEmpty(),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            sendParagraphNow()
                        },
                    )
                }
            }
        }

        // Collapsible Function & Shortcuts Section (opens ONLY when user taps Fn)
        AnimatedVisibility(
            visible = showFunctionBar,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Single-line Essential Shortcuts Row: Copy/Paste/Cut/Undo/Redo..., and Ctrl/F1 toggle last
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QUICK_SHORTCUTS.forEach { shortcut ->
                        ShortcutKeyChip(
                            label = shortcut.label,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onKeyCombo(shortcut.keys)
                            },
                        )
                    }

                    TogglePillChip(
                        label = if (showExtraKeys) "Keys ▾" else "Ctrl/F1 ▸",
                        active = showExtraKeys || modifierStates.values.any { it != ModifierState.OFF },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showExtraKeys = !showExtraKeys
                        },
                    )
                }

                // Extra PC Keys (Ctrl, Win, Alt, Shift, Esc, Tab, Arrows, F1-F12) — hidden by default until user expands
                AnimatedVisibility(
                    visible = showExtraKeys,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ModifierKey.entries.forEach { mod ->
                                val state = modifierStates[mod] ?: ModifierState.OFF
                                ModifierKeyChip(
                                    label = mod.label,
                                    state = state,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onToggleModifier(mod)
                                    },
                                )
                            }

                            CORE_EXTRA_KEYS.forEach { spec ->
                                KeyChip(
                                    label = spec.label,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSpecialKey(spec.wireValue)
                                    },
                                )
                            }

                            if (modifierStates.values.any { it != ModifierState.OFF }) {
                                KeyChip(
                                    label = "Clear",
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onReleaseAllModifiers()
                                    },
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NAV_KEYS.forEach { spec ->
                                KeyChip(
                                    label = spec.label,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSpecialKey(spec.wireValue)
                                    },
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FUNCTION_KEYS.forEach { spec ->
                                KeyChip(
                                    label = spec.label,
                                    compact = true,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSpecialKey(spec.wireValue)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeSegmentButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) RemoteColors.NeonCyan else RemoteColors.DeepBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else RemoteColors.TextMuted,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun SmallBarButton(
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (highlighted) RemoteColors.NeonCyan
                else RemoteColors.TileBg
            )
            .border(
                1.dp,
                if (highlighted) RemoteColors.NeonBorderBright else RemoteColors.NeonBorder,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (highlighted) MaterialTheme.colorScheme.onPrimary else RemoteColors.TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun ShortcutKeyChip(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .widthIn(min = 50.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(RemoteColors.TileBg)
            .border(1.dp, RemoteColors.NeonBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = RemoteColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TogglePillChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (active) RemoteColors.NeonCyan.copy(alpha = 0.22f)
                else RemoteColors.DeepBg
            )
            .border(
                1.dp,
                if (active) RemoteColors.NeonBorderBright else RemoteColors.NeonBorder,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) RemoteColors.NeonCyan else RemoteColors.TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ModifierKeyChip(
    label: String,
    state: ModifierState,
    onClick: () -> Unit,
) {
    val bg = when (state) {
        ModifierState.OFF -> RemoteColors.TileBg
        ModifierState.ACTIVE -> RemoteColors.NeonCyan.copy(alpha = 0.28f)
        ModifierState.LOCKED -> RemoteColors.NeonCyan
    }
    val border = when (state) {
        ModifierState.OFF -> RemoteColors.NeonBorder
        ModifierState.ACTIVE, ModifierState.LOCKED -> RemoteColors.NeonBorderBright
    }
    val textColor = when (state) {
        ModifierState.OFF -> RemoteColors.TextPrimary
        ModifierState.ACTIVE -> RemoteColors.NeonCyan
        ModifierState.LOCKED -> MaterialTheme.colorScheme.onPrimary
    }
    val displayLabel = if (state == ModifierState.LOCKED) "$label•" else label

    Box(
        modifier = Modifier
            .height(34.dp)
            .widthIn(min = 46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = displayLabel,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun KeyChip(
    label: String,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(if (compact) 30.dp else 34.dp)
            .widthIn(min = if (compact) 38.dp else 42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(RemoteColors.TileBg)
            .border(1.dp, RemoteColors.NeonBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 8.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = RemoteColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ActionChip(
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (highlighted) RemoteColors.NeonCyan
                else RemoteColors.TileBg
            )
            .border(
                1.dp,
                if (highlighted) RemoteColors.NeonBorderBright
                else RemoteColors.NeonBorder,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (highlighted) MaterialTheme.colorScheme.onPrimary
            else RemoteColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
