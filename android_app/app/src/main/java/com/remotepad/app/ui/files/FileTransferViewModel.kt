package com.remotepad.app.ui.files

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

class FileTransferViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val client = FileTransferClient()
    private val p2pFileClient = P2PFileClient(client)

    private val _browserState = MutableStateFlow(FileBrowserUiState())
    val browserState: StateFlow<FileBrowserUiState> = _browserState.asStateFlow()

    private val _transfers = MutableStateFlow<List<TransferItem>>(emptyList())
    val transfers: StateFlow<List<TransferItem>> = _transfers.asStateFlow()

    private val activeCancelers = ConcurrentHashMap<String, () -> Unit>()

    @Volatile
    private var connectedTarget: ConnectionState.Connected? = null

    @Volatile
    private var activeP2PClient: P2PUdpClient? = null
    private var lastLoadedEndpoint: String? = null

    fun syncConnection(state: ConnectionState, p2pClient: P2PUdpClient? = null) {
        val lanAllowed = state is ConnectionState.Connected &&
            state.transportMode == TransportMode.LAN &&
            !state.viaRelay &&
            state.host.isNotBlank()
        val p2pAllowed = state is ConnectionState.Connected &&
            state.transportMode == TransportMode.P2P_DIRECT &&
            p2pClient != null &&
            p2pClient.isConnected

        if (lanAllowed) {
            connectedTarget = state as ConnectionState.Connected
            activeP2PClient = null
            val endpoint = "lan:${state.host}:${state.filePort}"
            if (lastLoadedEndpoint != endpoint) {
                lastLoadedEndpoint = endpoint
                loadInitial()
            }
        } else if (p2pAllowed) {
            connectedTarget = state as ConnectionState.Connected
            activeP2PClient = p2pClient
            val endpoint = "p2p:${state.target}"
            if (lastLoadedEndpoint != endpoint) {
                lastLoadedEndpoint = endpoint
                loadInitial()
            }
        } else {
            // Disconnected, Punching, or VPS Relay -> file transfer intentionally disabled
            connectedTarget = null
            activeP2PClient = null
            lastLoadedEndpoint = null
            _browserState.value = FileBrowserUiState()
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
                }.onFailure { err ->
                    val cancelled = cancelToken.get() || activeCall?.isCanceled() == true
                    updateTransfer(id) { item ->
                        item.copy(
                            status = if (cancelled) TransferStatus.CANCELLED else TransferStatus.FAILED,
                            detail = if (cancelled) "Cancelled" else (err.message ?: "Upload failed"),
                        )
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
