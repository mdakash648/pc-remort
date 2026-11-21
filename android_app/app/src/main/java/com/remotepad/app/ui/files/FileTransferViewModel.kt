package com.remotepad.app.ui.files

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remotepad.app.network.ConnectionManager
import com.remotepad.app.network.ConnectionState
import com.remotepad.app.network.FileTransferClient
import com.remotepad.app.network.P2PFileClient
import com.remotepad.app.network.P2PUdpClient
import com.remotepad.app.network.PcDirectoryListing
import com.remotepad.app.network.PcFileEntry
import com.remotepad.app.network.PcFileInfo
import com.remotepad.app.network.TransportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Call
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class TransferDirection {
    PHONE_TO_PC,
    PC_TO_PHONE,
}

enum class TransferStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class TransferItem(
    val id: String,
    val fileName: String,
    val direction: TransferDirection,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val status: TransferStatus,
    val detail: String = "",
    val savedUri: Uri? = null,
    val mimeType: String = "application/octet-stream",
) {
    val progress: Float
        get() = when {
            status == TransferStatus.COMPLETED -> 1f
            totalBytes > 0L -> (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
}

data class FileBrowserUiState(
    val pcInfo: PcFileInfo? = null,
    val isHomeView: Boolean = true,
    val currentPath: String = "",
    val parentPath: String = "",
    val entries: List<PcFileEntry> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

data class PendingShareItem(
    val uri: Uri,
    val fileName: String,
    val fileSize: Long,
)

val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "ico"
)
val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "webm", "avi", "mov", "flv", "ts", "m4v", "3gp", "wmv"
)
val AUDIO_EXTENSIONS = setOf(
    "mp3", "wav", "flac", "aac", "ogg", "m4a", "wma"
)

enum class FileCategory {
    DIRECTORY,
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    OTHER,
}

fun PcFileEntry.getCategory(): FileCategory {
    if (isDir) return FileCategory.DIRECTORY
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in IMAGE_EXTENSIONS -> FileCategory.IMAGE
        in VIDEO_EXTENSIONS -> FileCategory.VIDEO
        in AUDIO_EXTENSIONS -> FileCategory.AUDIO
        "pdf", "doc", "docx", "txt", "rtf", "md", "xls", "xlsx", "ppt", "pptx" -> FileCategory.DOCUMENT
        else -> FileCategory.OTHER
    }
}

data class ActiveMediaPreview(
    val entry: PcFileEntry,
    val streamUrl: String,
    val category: FileCategory,
)

class FileTransferViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val client = FileTransferClient()
    private val p2pFileClient = P2PFileClient(client)

    private val _browserState = MutableStateFlow(FileBrowserUiState())
    val browserState: StateFlow<FileBrowserUiState> = _browserState.asStateFlow()

    private val _transfers = MutableStateFlow<List<TransferItem>>(emptyList())
    val transfers: StateFlow<List<TransferItem>> = _transfers.asStateFlow()

    private val _activePreview = MutableStateFlow<ActiveMediaPreview?>(null)
    val activePreview: StateFlow<ActiveMediaPreview?> = _activePreview.asStateFlow()

    fun getStreamUrl(remotePath: String): String? {
        val target = connectedTarget ?: return null
        if (target.host.isNotBlank() && target.filePort > 0) {
            val encoded = Uri.encode(remotePath)
            return "http://${target.host}:${target.filePort}/api/download?path=$encoded"
        }
        return null
    }

    fun openPreview(entry: PcFileEntry) {
        if (entry.isDir) return
        val cat = entry.getCategory()
        val streamUrl = getStreamUrl(entry.path)
        if (streamUrl != null) {
            _activePreview.value = ActiveMediaPreview(
                entry = entry,
                streamUrl = streamUrl,
                category = cat,
            )
        } else {
            postToast("Direct preview requires Local Wi-Fi connection")
            downloadEntry(entry)
        }
    }

    fun closePreview() {
        _activePreview.value = null
    }

    private val _pendingShares = MutableStateFlow<List<PendingShareItem>>(emptyList())
    val pendingShares: StateFlow<List<PendingShareItem>> = _pendingShares.asStateFlow()

    private val _isConnectingForShare = MutableStateFlow(false)
    val isConnectingForShare: StateFlow<Boolean> = _isConnectingForShare.asStateFlow()

    private val _navigateToFilesEvent = MutableStateFlow(false)
    val navigateToFilesEvent: StateFlow<Boolean> = _navigateToFilesEvent.asStateFlow()

    fun onNavigatedToFiles() {
        _navigateToFilesEvent.value = false
    }

    private val activeCancelers = ConcurrentHashMap<String, () -> Unit>()

    @Volatile
    private var connectedTarget: ConnectionState.Connected? = null

    @Volatile
    private var activeP2PClient: P2PUdpClient? = null
    private var lastLoadedEndpoint: String? = null

    fun syncConnection(state: ConnectionState, p2pClient: P2PUdpClient? = null) {
        if (state is ConnectionState.Connected) {
            connectedTarget = state
            val isDirectLan = state.transportMode == TransportMode.LAN && !state.viaRelay && state.host.isNotBlank()
            val endpoint = if (isDirectLan) {
                activeP2PClient = null
                "lan:${state.host}:${state.filePort}"
            } else {
                activeP2PClient = p2pClient
                "p2p:${state.target}:${state.transportMode}"
            }
            if (lastLoadedEndpoint != endpoint) {
                lastLoadedEndpoint = endpoint
                loadInitial()
            }
            flushPendingSharesIfReady()
        } else {
            connectedTarget = null
            activeP2PClient = null
            lastLoadedEndpoint = null
            _browserState.value = FileBrowserUiState()
            if (state is ConnectionState.Disconnected || state is ConnectionState.Error) {
                _isConnectingForShare.value = false
            }
        }
    }

    fun onReceiveSharedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _navigateToFilesEvent.value = true

        val items = uris.map { uri ->
            val meta = client.queryLocalFileMeta(appContext, uri)
            PendingShareItem(
                uri = uri,
                fileName = meta.displayName,
                fileSize = meta.size,
            )
        }

        val target = connectedTarget
        val canTransfer = target != null && (
            (target.transportMode == TransportMode.LAN && !target.viaRelay && target.host.isNotBlank()) ||
                (target.transportMode == TransportMode.P2P_DIRECT && activeP2PClient?.isConnected == true)
        )

        if (canTransfer) {
            _isConnectingForShare.value = false
            _pendingShares.value = emptyList()
            postToast(
                if (items.size == 1) "Sending ${items.first().fileName} to PC..." else "Sending ${items.size} files to PC..."
            )
            uploadUris(uris)
        } else {
            _pendingShares.update { current ->
                val existing = current.map { it.uri }.toSet()
                current + items.filter { it.uri !in existing }
            }
            _isConnectingForShare.value = true
            postToast(
                if (items.size == 1) "Connecting to PC to send ${items.first().fileName}..." else "Connecting to PC to send ${items.size} files..."
            )
            ConnectionManager.getInstance(appContext).reconnectWifiFirst()
        }
    }

    private fun flushPendingSharesIfReady() {
        val pending = _pendingShares.value
        if (pending.isNotEmpty() && connectedTarget != null) {
            _pendingShares.value = emptyList()
            _isConnectingForShare.value = false
            postToast(
                if (pending.size == 1) "Connected! Sending ${pending.first().fileName}..." else "Connected! Sending ${pending.size} files..."
            )
            uploadUris(pending.map { it.uri })
        }
    }

    fun retryConnectingForShare() {
        if (_pendingShares.value.isNotEmpty()) {
            _isConnectingForShare.value = true
            postToast("Reconnecting to PC...")
            ConnectionManager.getInstance(appContext).reconnectWifiFirst()
        }
    }

    fun cancelPendingShares() {
        _pendingShares.value = emptyList()
        _isConnectingForShare.value = false
        postToast("File send cancelled")
    }

    private fun postToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun fetchInfoForTarget(target: ConnectionState.Connected): PcFileInfo {
        return if (target.transportMode == TransportMode.P2P_DIRECT) {
            val p2p = activeP2PClient ?: throw IOException("Direct P2P is not connected")
            p2pFileClient.fetchInfo(p2p, target.pcName)
        } else {
            client.fetchInfo(target.host, target.filePort)
        }
    }

    private fun listDirectoryForTarget(target: ConnectionState.Connected, path: String?): PcDirectoryListing {
        return if (target.transportMode == TransportMode.P2P_DIRECT) {
            val p2p = activeP2PClient ?: throw IOException("Direct P2P is not connected")
            p2pFileClient.listDirectory(p2p, path)
        } else {
            client.listDirectory(target.host, target.filePort, path)
        }
    }

    fun loadInitial() {
        val target = connectedTarget ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _browserState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                val info = fetchInfoForTarget(target)
                val savedPath = getSavedLastPath(info.pcName)
                if (!savedPath.isNullOrBlank()) {
                    val listing = runCatching {
                        listDirectoryForTarget(target, savedPath)
                    }.getOrNull()
                    if (listing != null) {
                        return@runCatching Triple(info, listing, false)
                    }
                    // Saved drive/folder no longer exists -> clear and fall back to Home (all drives)
                    saveLastPath(info.pcName, "")
                }
                Triple(info, null, true)
            }.onSuccess { (info, listing, showHome) ->
                if (showHome || listing == null) {
                    _browserState.value = FileBrowserUiState(
                        pcInfo = info,
                        isHomeView = true,
                        currentPath = "",
                        parentPath = "",
                        entries = emptyList(),
                        isLoading = false,
                        errorMessage = null,
                    )
                } else {
                    _browserState.value = FileBrowserUiState(
                        pcInfo = info,
                        isHomeView = false,
                        currentPath = listing.path,
                        parentPath = listing.parent,
                        entries = listing.entries,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
            }.onFailure { err ->
                _browserState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Could not reach PC file service",
                    )
                }
            }
        }
    }

    fun openHome() {
        val pcName = _browserState.value.pcInfo?.pcName ?: connectedTarget?.pcName
        if (!pcName.isNullOrBlank()) {
            saveLastPath(pcName, "")
        }
        _browserState.update {
            it.copy(
                isHomeView = true,
                currentPath = "",
                parentPath = "",
                entries = emptyList(),
                errorMessage = null,
            )
        }
        refreshHomeInfo()
    }

    fun openDirectory(path: String) {
        val target = connectedTarget ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _browserState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                listDirectoryForTarget(target, path)
            }.onSuccess { listing ->
                val pcName = _browserState.value.pcInfo?.pcName ?: target.pcName
                saveLastPath(pcName, listing.path)
                _browserState.update {
                    it.copy(
                        isHomeView = false,
                        currentPath = listing.path,
                        parentPath = listing.parent,
                        entries = listing.entries,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
            }.onFailure { err ->
                _browserState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Failed to open folder",
                    )
                }
            }
        }
    }

    fun navigateUp() {
        val state = _browserState.value
        if (state.isHomeView) return
        if (state.parentPath.isNotBlank()) {
            openDirectory(state.parentPath)
        } else {
            // At drive root (e.g., C:\ or D:\) -> go up to This PC (all drives)
            openHome()
        }
    }

    fun refresh() {
        val state = _browserState.value
        if (state.pcInfo == null) {
            loadInitial()
        } else if (state.isHomeView) {
            refreshHomeInfo()
        } else if (state.currentPath.isNotBlank()) {
            openDirectory(state.currentPath)
        } else {
            loadInitial()
        }
    }

    private fun refreshHomeInfo() {
        val target = connectedTarget ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _browserState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                fetchInfoForTarget(target)
            }.onSuccess { info ->
                _browserState.update {
                    it.copy(
                        pcInfo = info,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
            }.onFailure { err ->
                _browserState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Could not refresh PC drives",
                    )
                }
            }
        }
    }

    fun uploadUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val target = connectedTarget ?: return
        val state = _browserState.value
        val destDir = if (!state.isHomeView && state.currentPath.isNotBlank()) {
            state.currentPath
        } else {
            state.pcInfo?.receivedDir
        }

        viewModelScope.launch(Dispatchers.IO) {
            var anySucceeded = false
            for (uri in uris) {
                val meta = client.queryLocalFileMeta(appContext, uri)
                val id = UUID.randomUUID().toString()
                addTransfer(
                    TransferItem(
                        id = id,
                        fileName = meta.displayName,
                        direction = TransferDirection.PHONE_TO_PC,
                        bytesTransferred = 0L,
                        totalBytes = meta.size,
                        status = TransferStatus.RUNNING,
                        detail = "Sending to PC...",
                    )
                )

                var activeCall: Call? = null
                val cancelToken = AtomicBoolean(false)
                runCatching {
                    if (target.transportMode == TransportMode.P2P_DIRECT) {
                        val p2p = activeP2PClient ?: throw IOException("Direct P2P is not connected")
                        activeCancelers[id] = { cancelToken.set(true) }
                        p2pFileClient.uploadFile(
                            context = appContext,
                            p2p = p2p,
                            targetDir = destDir,
                            uri = uri,
                            meta = meta,
                            cancelToken = cancelToken,
                            onProgress = { sent, total ->
                                updateTransfer(id) { item ->
                                    item.copy(bytesTransferred = sent, totalBytes = total)
                                }
                            },
                        )
                    } else {
                        client.uploadFile(
                            context = appContext,
                            host = target.host,
                            filePort = target.filePort,
                            targetDir = destDir,
                            uri = uri,
                            meta = meta,
                            onCallReady = { call ->
                                activeCall = call
                                activeCancelers[id] = { call.cancel() }
                            },
                            onProgress = { sent, total ->
                                updateTransfer(id) { item ->
                                    item.copy(bytesTransferred = sent, totalBytes = total)
                                }
                            },
                        )
                    }
                }.onSuccess { result ->
                    anySucceeded = true
                    updateTransfer(id) { item ->
                        item.copy(
                            fileName = result.savedName,
                            bytesTransferred = result.size,
                            totalBytes = result.size,
                            status = TransferStatus.COMPLETED,
                            detail = "Saved on PC",
                        )
                    }
                    postToast("Sent to PC: ${result.savedName}")
                }.onFailure { err ->
                    val cancelled = cancelToken.get() || activeCall?.isCanceled() == true
                    updateTransfer(id) { item ->
                        item.copy(
                            status = if (cancelled) TransferStatus.CANCELLED else TransferStatus.FAILED,
                            detail = if (cancelled) "Cancelled" else (err.message ?: "Upload failed"),
                        )
                    }
                    if (!cancelled) {
                        postToast("Failed to send ${meta.displayName}: ${err.message ?: "Unknown error"}")
                    }
                }
                activeCancelers.remove(id)
            }

            if (anySucceeded && !_browserState.value.isHomeView) {
                refresh()
            }
        }
    }

    fun downloadEntry(entry: PcFileEntry) {
        if (entry.isDir) return
        val target = connectedTarget ?: return
        val id = UUID.randomUUID().toString()

        addTransfer(
            TransferItem(
                id = id,
                fileName = entry.name,
                direction = TransferDirection.PC_TO_PHONE,
                bytesTransferred = 0L,
                totalBytes = entry.size,
                status = TransferStatus.RUNNING,
                detail = "Downloading...",
            )
        )

        viewModelScope.launch(Dispatchers.IO) {
            var activeCall: Call? = null
            val cancelToken = AtomicBoolean(false)
            runCatching {
                if (target.transportMode == TransportMode.P2P_DIRECT) {
                    val p2p = activeP2PClient ?: throw IOException("Direct P2P is not connected")
                    activeCancelers[id] = { cancelToken.set(true) }
                    p2pFileClient.downloadFile(
                        context = appContext,
                        p2p = p2p,
                        remotePath = entry.path,
                        fileName = entry.name,
                        expectedSize = entry.size,
                        cancelToken = cancelToken,
                        onProgress = { received, total ->
                            updateTransfer(id) { item ->
                                item.copy(bytesTransferred = received, totalBytes = total)
                            }
                        },
                    )
                } else {
                    client.downloadFile(
                        context = appContext,
                        host = target.host,
                        filePort = target.filePort,
                        remotePath = entry.path,
                        fileName = entry.name,
                        expectedSize = entry.size,
                        onCallReady = { call ->
                            activeCall = call
                            activeCancelers[id] = { call.cancel() }
                        },
                        onProgress = { received, total ->
                            updateTransfer(id) { item ->
                                item.copy(bytesTransferred = received, totalBytes = total)
                            }
                        },
                    )
                }
            }.onSuccess { result ->
                updateTransfer(id) { item ->
                    item.copy(
                        bytesTransferred = result.size,
                        totalBytes = result.size,
                        status = TransferStatus.COMPLETED,
                        detail = result.displayPath,
                        savedUri = result.savedUri,
                        mimeType = result.mimeType,
                    )
                }
            }.onFailure { err ->
                val cancelled = cancelToken.get() || activeCall?.isCanceled() == true
                updateTransfer(id) { item ->
                    item.copy(
                        status = if (cancelled) TransferStatus.CANCELLED else TransferStatus.FAILED,
                        detail = if (cancelled) "Cancelled" else (err.message ?: "Download failed"),
                    )
                }
            }
            activeCancelers.remove(id)
        }
    }

    fun cancelTransfer(id: String) {
        activeCancelers[id]?.invoke()
    }

    fun clearFinishedTransfers() {
        _transfers.update { list ->
            list.filter { it.status == TransferStatus.RUNNING }
        }
    }

    private fun getSavedLastPath(pcName: String): String? {
        val perPc = prefs.getString("$KEY_LAST_PATH_PREFIX${pcName.lowercase()}", null)
        return perPc ?: prefs.getString(KEY_LAST_PATH_FALLBACK, null)
    }

    private fun saveLastPath(pcName: String, path: String) {
        prefs.edit()
            .putString("$KEY_LAST_PATH_PREFIX${pcName.lowercase()}", path)
            .putString(KEY_LAST_PATH_FALLBACK, path)
            .apply()
    }

    private fun addTransfer(item: TransferItem) {
        _transfers.update { list -> (listOf(item) + list).take(25) }
    }

    private fun updateTransfer(id: String, transform: (TransferItem) -> TransferItem) {
        _transfers.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeCancelers.values.forEach { runCatching { it() } }
        activeCancelers.clear()
        client.shutdown()
    }

    companion object {
        private const val PREFS_NAME = "remotepad_file_prefs"
        private const val KEY_LAST_PATH_PREFIX = "last_path_"
        private const val KEY_LAST_PATH_FALLBACK = "last_path_default"
    }
}
