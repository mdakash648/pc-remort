package com.remotepad.app.ui.files

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.SubcomposeAsyncImage
import com.remotepad.app.network.ConnectionState
import com.remotepad.app.network.FileTransferClient
import com.remotepad.app.network.PcDrive
import com.remotepad.app.network.PcFileEntry
import com.remotepad.app.network.PcQuickDir
import com.remotepad.app.network.TransportMode
import com.remotepad.app.ui.theme.RemoteColors

@Composable
fun FileTransferScreen(
    connectionState: ConnectionState,
    viewModel: FileTransferViewModel,
    onGoToTouchpad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val browserState by viewModel.browserState.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val pendingShares by viewModel.pendingShares.collectAsStateWithLifecycle()
    val isConnectingForShare by viewModel.isConnectingForShare.collectAsStateWithLifecycle()
    val activePreview by viewModel.activePreview.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler(enabled = activePreview != null) {
        viewModel.closePreview()
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.uploadUris(uris)
        }
    }

    val connected = connectionState as? ConnectionState.Connected
    val canTransferFiles = connected != null
    if (!canTransferFiles) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (pendingShares.isNotEmpty()) {
                PendingShareCard(
                    pendingShares = pendingShares,
                    isConnecting = isConnectingForShare || connectionState is ConnectionState.Connecting,
                    onRetry = viewModel::retryConnectingForShare,
                    onCancel = viewModel::cancelPendingShares,
                    onGoToTouchpad = onGoToTouchpad,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "File Transfer",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Connect to your PC on the Touchpad tab first to browse, download, or send files.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onGoToTouchpad) {
                        Text("Go to Touchpad")
                    }
                }
            }
        }
        return
    }

    val transportSuffix = when (connected.transportMode) {
        TransportMode.P2P_DIRECT -> {
            val rtt = connected.p2pRttMs?.let { " • ${it}ms" }.orEmpty()
            "Direct P2P$rtt"
        }
        else -> "Local Wi-Fi"
    }
    val statusDotColor = if (connected.transportMode == TransportMode.P2P_DIRECT) {
        Color(0xFF00E5FF)
    } else {
        Color(0xFF3FB950)
    }

    val uploadDestLabel = if (!browserState.isHomeView && browserState.currentPath.isNotBlank()) {
        "Saves to: ${browserState.currentPath}"
    } else {
        "Saves to: Downloads\\RemotePad"
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        // Phone -> PC Send Action Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(statusDotColor),
                    )
                    Column {
                        Text(
                            text = "${connected.pcName} ($transportSuffix)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = uploadDestLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Button(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text("Send to PC", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Active & Recent Transfers Card
        if (transfers.isNotEmpty()) {
            TransfersPanel(
                transfers = transfers,
                onCancel = viewModel::cancelTransfer,
                onClearFinished = viewModel::clearFinishedTransfers,
                onOpenDownloaded = { item ->
                    val uri = item.savedUri
                    if (uri != null) {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, item.mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(intent) }.onFailure {
                            Toast.makeText(
                                context,
                                "Saved to ${item.detail}",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    } else {
                        Toast.makeText(
                            context,
                            "Saved to ${item.detail}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        }

        // PC -> Phone File Browser Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Shortcut Bar: This PC + All Drives (C:, D:, E:, ...) FIRST, then Quick Folders
            val drives = browserState.pcInfo?.drives.orEmpty()
            val quickDirs = browserState.pcInfo?.quickDirs.orEmpty()
            if (drives.isNotEmpty() || quickDirs.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ShortcutChip(
                        label = "This PC",
                        selected = browserState.isHomeView,
                        onClick = viewModel::openHome,
                    )
                    drives.forEach { drive ->
                        val active = !browserState.isHomeView &&
                            browserState.currentPath.startsWith(drive.path, ignoreCase = true)
                        ShortcutChip(
                            label = drive.label,
                            selected = active,
                            onClick = { viewModel.openDirectory(drive.path) },
                        )
                    }
                    quickDirs.forEach { dir ->
                        val selected = !browserState.isHomeView &&
                            browserState.currentPath.equals(dir.path, ignoreCase = true)
                        ShortcutChip(
                            label = dir.label,
                            selected = selected,
                            onClick = { viewModel.openDirectory(dir.path) },
                        )
                    }
                }
            }

            // Current Path Bar + Up + Refresh
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!browserState.isHomeView) {
                    SmallActionChip(
                        label = if (browserState.parentPath.isBlank()) "Drives" else "Up",
                        enabled = !browserState.isLoading,
                        onClick = viewModel::navigateUp,
                    )
                }
                Text(
                    text = if (browserState.isHomeView) "This PC (All Drives)"
                    else browserState.currentPath.ifBlank { "PC Files" },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (browserState.isHomeView) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (browserState.isHomeView) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                SmallActionChip(
                    label = if (browserState.isLoading) "..." else "Refresh",
                    enabled = !browserState.isLoading,
                    onClick = viewModel::refresh,
                )
            }

            if (browserState.errorMessage != null) {
                Text(
                    text = browserState.errorMessage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (browserState.isHomeView) {
                // Dedicated "This PC" Home View showing all Drives (C:, D:, E:, ...) + Quick Folders
                if (drives.isEmpty() && quickDirs.isEmpty() && !browserState.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (browserState.errorMessage != null) "Tap Refresh to retry"
                            else "Loading PC drives...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (drives.isNotEmpty()) {
                            item(key = "header_drives") {
                                Text(
                                    text = "Drives (${drives.size})",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            items(drives, key = { "drive_${it.path}" }) { drive ->
                                PcDriveRow(
                                    drive = drive,
                                    onOpen = { viewModel.openDirectory(drive.path) },
                                )
                            }
                        }

                        if (quickDirs.isNotEmpty()) {
                            item(key = "header_quick") {
                                Text(
                                    text = "Quick Folders",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            items(quickDirs, key = { "quick_${it.path}" }) { dir ->
                                QuickFolderRow(
                                    dir = dir,
                                    onOpen = { viewModel.openDirectory(dir.path) },
                                )
                            }
                        }
                    }
                }
            } else if (browserState.entries.isEmpty() && !browserState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (browserState.errorMessage != null) "Tap Refresh to retry"
                        else "Folder is empty",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(browserState.entries, key = { it.path }) { entry ->
                        PcFileRow(
                            entry = entry,
                            onOpenDir = { viewModel.openDirectory(entry.path) },
                            onPreview = { viewModel.openPreview(entry) },
                            onDownload = { viewModel.downloadEntry(entry) },
                        )
                    }
                }
            }
        }
    }

    activePreview?.let { preview ->
        MediaPreviewOverlay(
            preview = preview,
            onClose = viewModel::closePreview,
            onDownload = {
                viewModel.downloadEntry(preview.entry)
                Toast.makeText(context, "Downloading ${preview.entry.name}...", Toast.LENGTH_SHORT).show()
            },
        )
    }
}
}

@Composable
private fun ShortcutChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PcDriveRow(
    drive: PcDrive,
    onOpen: () -> Unit,
) {
    val hasCapacity = drive.totalBytes > 0L
    val usedFraction = if (hasCapacity) {
        ((drive.totalBytes - drive.freeBytes).toFloat() / drive.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                        RoundedCornerShape(8.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = drive.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Local Disk (${drive.path})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (hasCapacity) {
                    Text(
                        text = "${FileTransferClient.formatBytes(drive.freeBytes)} free of ${FileTransferClient.formatBytes(drive.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = "Open",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (hasCapacity) {
            LinearProgressIndicator(
                progress = { usedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun QuickFolderRow(
    dir: PcQuickDir,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "DIR",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = dir.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = dir.path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "Open",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TransfersPanel(
    transfers: List<TransferItem>,
    onCancel: (String) -> Unit,
    onClearFinished: () -> Unit,
    onOpenDownloaded: (TransferItem) -> Unit,
) {
    val hasFinished = transfers.any { it.status != TransferStatus.RUNNING }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Transfers (${transfers.size})",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (hasFinished) {
                Text(
                    text = "Clear done",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onClearFinished)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 150.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(transfers, key = { it.id }) { item ->
                TransferRow(
                    item = item,
                    onCancel = { onCancel(item.id) },
                    onOpen = { onOpenDownloaded(item) },
                )
            }
        }
    }
}

@Composable
private fun TransferRow(
    item: TransferItem,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
) {
    val dirLabel = if (item.direction == TransferDirection.PHONE_TO_PC) "UP" else "DOWN"
    val statusColor = when (item.status) {
        TransferStatus.RUNNING -> MaterialTheme.colorScheme.primary
        TransferStatus.COMPLETED -> Color(0xFF3FB950)
        TransferStatus.FAILED -> MaterialTheme.colorScheme.error
        TransferStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(statusColor.copy(alpha = 0.18f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = dirLabel,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
            }
            Text(
                text = item.fileName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                item.status == TransferStatus.RUNNING -> {
                    SmallActionChip(label = "Cancel", enabled = true, onClick = onCancel)
                }
                item.status == TransferStatus.COMPLETED &&
                    item.direction == TransferDirection.PC_TO_PHONE &&
                    item.savedUri != null -> {
                    SmallActionChip(label = "Open", enabled = true, onClick = onOpen)
                }
            }
        }

        if (item.status == TransferStatus.RUNNING) {
            if (item.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
            }
        }

        val sizeText = when {
            item.status == TransferStatus.RUNNING && item.totalBytes > 0L -> {
                val pct = (item.progress * 100f).toInt()
                "${FileTransferClient.formatBytes(item.bytesTransferred)} / ${FileTransferClient.formatBytes(item.totalBytes)} ($pct%)"
            }
            item.status == TransferStatus.RUNNING -> {
                FileTransferClient.formatBytes(item.bytesTransferred)
            }
            item.status == TransferStatus.COMPLETED -> {
                "${FileTransferClient.formatBytes(item.totalBytes)} • ${item.detail}"
            }
            else -> item.detail
        }

        Text(
            text = sizeText,
            style = MaterialTheme.typography.labelSmall,
            color = if (item.status == TransferStatus.FAILED) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PcFileRow(
    entry: PcFileEntry,
    onOpenDir: () -> Unit,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
) {
    val category = entry.getCategory()
    val isMedia = category == FileCategory.IMAGE || category == FileCategory.VIDEO || category == FileCategory.AUDIO

    val badgeText = when (category) {
        FileCategory.DIRECTORY -> "DIR"
        FileCategory.IMAGE -> "IMG"
        FileCategory.VIDEO -> "VID"
        FileCategory.AUDIO -> "AUD"
        FileCategory.DOCUMENT -> "DOC"
        FileCategory.OTHER -> "FILE"
    }

    val badgeColor = when (category) {
        FileCategory.DIRECTORY -> MaterialTheme.colorScheme.primary
        FileCategory.IMAGE -> Color(0xFFB388FF)
        FileCategory.VIDEO -> RemoteColors.MediaOrange
        FileCategory.AUDIO -> RemoteColors.ConnectedGreen
        FileCategory.DOCUMENT -> Color(0xFF64B5F6)
        FileCategory.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable {
                when {
                    entry.isDir -> onOpenDir()
                    isMedia -> onPreview()
                    else -> onDownload()
                }
            }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(38.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(badgeColor.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = badgeText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = badgeColor,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (entry.isDir) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.isDir) {
                Text(
                    text = FileTransferClient.formatBytes(entry.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (entry.isDir) {
            Text(
                text = "Open",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        } else if (category == FileCategory.IMAGE) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallActionChip(label = "View", enabled = true, highlightColor = RemoteColors.NeonCyan, onClick = onPreview)
                SmallActionChip(label = "Get", enabled = true, onClick = onDownload)
            }
        } else if (category == FileCategory.VIDEO || category == FileCategory.AUDIO) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallActionChip(label = "Play", enabled = true, highlightColor = RemoteColors.MediaOrange, onClick = onPreview)
                SmallActionChip(label = "Get", enabled = true, onClick = onDownload)
            }
        } else {
            SmallActionChip(label = "Get", enabled = true, onClick = onDownload)
        }
    }
}

@Composable
private fun SmallActionChip(
    label: String,
    enabled: Boolean,
    highlightColor: Color? = null,
    onClick: () -> Unit,
) {
    val bg = highlightColor?.copy(alpha = 0.22f) ?: MaterialTheme.colorScheme.surfaceVariant
    val borderCol = highlightColor?.copy(alpha = 0.65f) ?: MaterialTheme.colorScheme.outline
    val txtCol = highlightColor ?: if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, borderCol, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (highlightColor != null) FontWeight.Bold else FontWeight.Medium,
            color = txtCol,
        )
    }
}

@Composable
private fun MediaPreviewOverlay(
    preview: ActiveMediaPreview,
    onClose: () -> Unit,
    onDownload: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when (preview.category) {
            FileCategory.IMAGE -> {
                ImageViewerModal(
                    preview = preview,
                    onClose = onClose,
                    onDownload = onDownload,
                )
            }
            FileCategory.VIDEO, FileCategory.AUDIO -> {
                VideoPlayerModal(
                    preview = preview,
                    onClose = onClose,
                    onDownload = onDownload,
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun ImageViewerModal(
    preview: ActiveMediaPreview,
    onClose: () -> Unit,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2.5f
                            offset = Offset.Zero
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.7f, 6f)
                        if (scale > 1f) {
                            offset += pan
                        } else {
                            offset = Offset.Zero
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            SubcomposeAsyncImage(
                model = preview.streamUrl,
                contentDescription = preview.entry.name,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = RemoteColors.NeonCyan,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                },
                error = {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "Failed to load image from PC",
                            color = RemoteColors.DangerRed,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Make sure PC Agent is running and file exists",
                            color = RemoteColors.TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                },
            )
        }

        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preview.entry.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${FileTransferClient.formatBytes(preview.entry.size)} • Direct Preview",
                    color = RemoteColors.NeonCyan,
                    fontSize = 11.sp,
                )
            }

            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(preview.streamUrl), "image/*")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(intent, "Open with"))
                        }
                    }
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Open In", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium)
            }

            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(RemoteColors.NeonCyan.copy(alpha = 0.25f))
                    .border(1.dp, RemoteColors.NeonCyan.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .clickable(onClick = onDownload)
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Save", fontSize = 11.sp, color = RemoteColors.NeonCyan, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun VideoPlayerModal(
    preview: ActiveMediaPreview,
    onClose: () -> Unit,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val exoPlayer = remember(preview.streamUrl) {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(Uri.parse(preview.streamUrl))
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = exoPlayer
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowFastForwardButton(true)
                    setShowRewindButton(true)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preview.entry.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${FileTransferClient.formatBytes(preview.entry.size)} • Streaming from PC",
                    color = RemoteColors.MediaOrange,
                    fontSize = 11.sp,
                )
            }

            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable {
                        val mimeType = if (preview.category == FileCategory.AUDIO) "audio/*" else "video/*"
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(preview.streamUrl), mimeType)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(intent, "Play with"))
                        }
                    }
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("External", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium)
            }

            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(RemoteColors.NeonCyan.copy(alpha = 0.25f))
                    .border(1.dp, RemoteColors.NeonCyan.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .clickable(onClick = onDownload)
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Save", fontSize = 11.sp, color = RemoteColors.NeonCyan, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PendingShareCard(
    pendingShares: List<PendingShareItem>,
    isConnecting: Boolean,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onGoToTouchpad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RemoteColors.PanelBg)
            .border(
                1.dp,
                if (isConnecting) RemoteColors.NeonCyan.copy(alpha = 0.6f)
                else RemoteColors.ConnectingAmber.copy(alpha = 0.6f),
                RoundedCornerShape(16.dp),
            )
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (isConnecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                color = RemoteColors.NeonCyan,
                strokeWidth = 3.dp,
            )
            Text(
                text = "Connecting to PC...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = RemoteColors.TextPrimary,
            )
            val fileCount = pendingShares.size
            val countLabel = if (fileCount == 1) "1 file ready to send" else "$fileCount files ready to send"
            Text(
                text = "$countLabel. Upload will begin automatically as soon as PC is connected.",
                style = MaterialTheme.typography.bodyMedium,
                color = RemoteColors.TextMuted,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(RemoteColors.DangerRed),
            )
            Text(
                text = "PC Not Connected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = RemoteColors.TextPrimary,
            )
            Text(
                text = "Could not reach PC. Make sure PC Agent is running and both devices are on the same Wi-Fi.",
                style = MaterialTheme.typography.bodyMedium,
                color = RemoteColors.TextMuted,
            )
        }

        // List of queued files
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(RemoteColors.TileBg)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            pendingShares.take(5).forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = RemoteColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = FileTransferClient.formatBytes(item.fileSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = RemoteColors.TextMuted,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (pendingShares.size > 5) {
                Text(
                    text = "+${pendingShares.size - 5} more files...",
                    style = MaterialTheme.typography.labelSmall,
                    color = RemoteColors.NeonCyan,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            if (!isConnecting) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RemoteColors.NeonCyan,
                        contentColor = Color(0xFF001D2B),
                    ),
                ) {
                    Text("Retry Connect", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = onGoToTouchpad,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text("Settings")
                }
            }
            OutlinedButton(
                onClick = onCancel,
            ) {
                Text("Cancel")
            }
        }
    }
}
