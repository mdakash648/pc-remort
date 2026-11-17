package com.remotepad.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.remotepad.app.ui.files.FileTransferScreen
import com.remotepad.app.ui.files.FileTransferViewModel
import com.remotepad.app.ui.files.TransferStatus
import com.remotepad.app.ui.theme.RemoteColors
import com.remotepad.app.ui.theme.RemotePadTheme
import com.remotepad.app.ui.touchpad.FolderTabIcon
import com.remotepad.app.ui.touchpad.MouseTabIcon
import com.remotepad.app.ui.touchpad.TouchpadScreen
import com.remotepad.app.ui.touchpad.TouchpadViewModel
import com.remotepad.app.ui.touchpad.VolumeMediaModal

private enum class AppPage {
    TOUCHPAD,
    FILES,
}

class MainActivity : ComponentActivity() {
    private val touchpadVm: TouchpadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            RemotePadTheme {
                RemotePadApp(touchpadVm = touchpadVm)
            }
        }
    }

    /**
     * Intercept physical Volume Up / Down hardware buttons while inside the app so:
     * 1. The phone's own system volume NEVER changes.
     * 2. PC volume steps up/down and the 3-second floating Media/Volume modal opens automatically.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                touchpadVm.onHardwareVolumeKey(up = code == KeyEvent.KEYCODE_VOLUME_UP)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun RemotePadApp(touchpadVm: TouchpadViewModel) {
    val fileVm: FileTransferViewModel = viewModel()

    val connectionState by touchpadVm.connectionState.collectAsStateWithLifecycle()
    val isKeyboardOpen by touchpadVm.isKeyboardOpen.collectAsStateWithLifecycle()
    val showVolumeModal by touchpadVm.showVolumeModal.collectAsStateWithLifecycle()
    val pcVolume by touchpadVm.pcVolume.collectAsStateWithLifecycle()
    val pcMuted by touchpadVm.pcMuted.collectAsStateWithLifecycle()
    val transfers by fileVm.transfers.collectAsStateWithLifecycle()
    val activeTransfers = transfers.count { it.status == TransferStatus.RUNNING }

    var currentPage by rememberSaveable { mutableStateOf(AppPage.TOUCHPAD) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Pause gyroscope sensor & volume sync cleanly when app is backgrounded
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> touchpadVm.setAppInForeground(true)
                Lifecycle.Event.ON_PAUSE -> touchpadVm.setAppInForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            touchpadVm.setAppInForeground(false)
        }
    }

    LaunchedEffect(connectionState) {
        fileVm.syncConnection(connectionState, touchpadVm.p2pClient)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = RemoteColors.DeepBg,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    when (currentPage) {
                        AppPage.TOUCHPAD -> {
                            TouchpadScreen(
                                viewModel = touchpadVm,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        AppPage.FILES -> {
                            FileTransferScreen(
                                connectionState = connectionState,
                                viewModel = fileVm,
                                onGoToTouchpad = { currentPage = AppPage.TOUCHPAD },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                // Sleek Bottom Navigation Bar (matching screenshots, ad-free, hidden while typing)
                if (!(currentPage == AppPage.TOUCHPAD && isKeyboardOpen)) {
                    BottomNavBar(
                        currentPage = currentPage,
                        activeTransfers = activeTransfers,
                        onSelectPage = { page ->
                            if (page != currentPage) {
                                if (page == AppPage.FILES) {
                                    touchpadVm.setKeyboardOpen(false)
                                    keyboardController?.hide()
                                }
                                currentPage = page
                            }
                        },
                    )
                }
            }

            // 3-Second Floating Volume / Media Modal triggered by physical Volume Up/Down keys
            AnimatedVisibility(
                visible = showVolumeModal,
                enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.92f, animationSpec = tween(150)),
                exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.94f, animationSpec = tween(180)),
            ) {
                VolumeMediaModal(
                    volumeLevel = pcVolume,
                    isMuted = pcMuted,
                    onSetVolume = touchpadVm::setPcVolume,
                    onMediaAction = touchpadVm::sendMediaAction,
                    onInteract = touchpadVm::keepVolumeModalAlive,
                    onDismiss = touchpadVm::dismissVolumeModal,
                )
            }
        }
    }
}

@Composable
private fun BottomNavBar(
    currentPage: AppPage,
    activeTransfers: Int,
    onSelectPage: (AppPage) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(RemoteColors.NeonBorder.copy(alpha = 0.4f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .background(RemoteColors.DeepBg)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BottomNavItem(
                label = "Touchpad",
                selected = currentPage == AppPage.TOUCHPAD,
                modifier = Modifier.weight(1f),
                onClick = { onSelectPage(AppPage.TOUCHPAD) },
            ) { color ->
                MouseTabIcon(color = color, modifier = Modifier.size(22.dp))
            }

            val filesLabel = if (activeTransfers > 0) "Files ($activeTransfers)" else "Files"
            BottomNavItem(
                label = filesLabel,
                selected = currentPage == AppPage.FILES,
                modifier = Modifier.weight(1f),
                onClick = { onSelectPage(AppPage.FILES) },
            ) { color ->
                FolderTabIcon(color = color, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun BottomNavItem(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
) {
    val tint = if (selected) RemoteColors.NeonCyan else RemoteColors.TextMuted
    Column(
        modifier = modifier
            .height(50.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon(tint)
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
        )
    }
}
