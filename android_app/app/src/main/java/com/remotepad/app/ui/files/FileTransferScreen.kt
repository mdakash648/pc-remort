package com.remotepad.app.ui.files

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotepad.app.network.ConnectionState
import com.remotepad.app.network.FileTransferClient
import com.remotepad.app.network.PcDrive
import com.remotepad.app.network.PcFileEntry
import com.remotepad.app.network.PcQuickDir
import com.remotepad.app.network.TransportMode

@Composable
fun FileTransferScreen(
    connectionState: ConnectionState,
    viewModel: FileTransferViewModel,
    onGoToTouchpad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val browserState by viewModel.browserState.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.uploadUris(uris)
        }
    }

    val connected = connectionState as? ConnectionState.Connected
    val canTransferFiles = connected != null && (
        (connected.transportMode == TransportMode.LAN && !connected.viaRelay && connected.host.isNotBlank()) ||
            connected.transportMode == TransportMode.P2P_DIRECT
        )
    if (!canTransferFiles || connected == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
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
                    text = when (connected?.transportMode) {
                        TransportMode.P2P_CONNECTING ->
                            "Establishing Direct P2P connection... File Transfer will unlock automatically once Direct P2P connects."
                        TransportMode.VPS_RELAY ->
                            "You are connected via VPS Relay. File Transfer is available on Local Wi-Fi and Direct P2P only (disabled on VPS Relay)."
                        else ->
                            "Connect to your PC on the Touchpad tab first to send or receive files over Local Wi-Fi or Direct P2P."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onGoToTouchpad) {
                    Text("Go to Touchpad")
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

    Column(
        modifier = modifier
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
                            onDownload = { viewModel.downloadEntry(entry) },
                        )
                    }
                }
            }
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
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable {
                if (entry.isDir) onOpenDir() else onDownload()
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
                .background(
                    if (entry.isDir) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    else MaterialTheme.colorScheme.surface
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (entry.isDir) "DIR" else "FILE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (entry.isDir) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
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
        } else {
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .clickable(onClick = onDownload)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Get",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SmallActionChip(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        )
    }
}
