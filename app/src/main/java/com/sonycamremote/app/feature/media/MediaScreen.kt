package com.sonycamremote.app.feature.media

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sonycamremote.app.data.camera.CameraSessionManager
import com.sonycamremote.app.data.camera.MediaSaver
import com.sonycamremote.app.ui.components.EmptyState
import com.sonycamremote.app.ui.components.ScreenScaffold
import com.sonycamremote.app.ui.theme.Cinema
import com.sonycamremote.engine.model.Capability
import com.sonycamremote.engine.model.MediaItem
import com.sonycamremote.engine.model.MediaKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class TransferState(val current: String, val index: Int, val total: Int, val bytes: Long, val totalBytes: Long?)

@HiltViewModel
class MediaViewModel @Inject constructor(
    private val sessions: CameraSessionManager,
    private val saver: MediaSaver,
) : ViewModel() {
    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items = _items.asStateFlow()
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected = _selected.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _transfer = MutableStateFlow<TransferState?>(null)
    val transfer = _transfer.asStateFlow()
    private val _saved = MutableStateFlow<List<Uri>>(emptyList())
    val saved = _saved.asStateFlow()

    val supported: Boolean
        get() = sessions.active.value?.protocol?.state?.value?.capabilities?.contains(Capability.MEDIA_BROWSE) == true

    fun load(more: Boolean = false) {
        val s = sessions.active.value ?: return
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val page = s.protocol.listMedia(if (more) _items.value.size else 0, 50)
                _items.value = if (more) _items.value + page else page
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    fun toggle(item: MediaItem) = _selected.update { if (item.id in it) it - item.id else it + item.id }

    fun clearSelection() { _selected.value = emptySet() }

    suspend fun thumbnail(item: MediaItem): ImageBitmap? {
        val s = sessions.active.value ?: return null
        val bytes = runCatching { s.protocol.thumbnail(item) }.getOrNull() ?: return null
        return withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    }

    /** Batch download. Cancelling the transfer job stops between (or within) files. */
    fun downloadSelected() {
        val s = sessions.active.value ?: return
        val targets = _items.value.filter { it.id in _selected.value }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val done = mutableListOf<Uri>()
            try {
                targets.forEachIndexed { i, item ->
                    _transfer.value = TransferState(item.fileName, i + 1, targets.size, 0, item.sizeBytes)
                    val uri = saver.save(s.protocol, item) { b, t ->
                        _transfer.value = TransferState(item.fileName, i + 1, targets.size, b, t)
                    }
                    done += uri
                }
                _selected.value = emptySet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Transfer failed: ${e.message}"
            } finally {
                _saved.value = _saved.value + done
                _transfer.value = null
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaScreen(onBack: () -> Unit, vm: MediaViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val transfer by vm.transfer.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val supported = remember { vm.supported }

    LaunchedEffect(Unit) { if (supported) vm.load() }
    // Android 8–9 need WRITE_EXTERNAL_STORAGE to add files to the shared gallery.
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.downloadSelected()
    }
    val startDownload: () -> Unit = {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else vm.downloadSelected()
    }

    ScreenScaffold(
        title = if (selected.isEmpty()) "Camera media" else "${selected.size} selected",
        onBack = onBack,
        actions = {
            if (selected.isNotEmpty()) {
                TextButton(onClick = vm::clearSelection) { Text("Clear") }
                IconButton(onClick = startDownload, enabled = transfer == null) {
                    Icon(Icons.Filled.Download, contentDescription = "Download to phone")
                }
            }
            if (saved.isNotEmpty()) {
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(saved))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Share"))
                }) { Icon(Icons.Filled.Share, contentDescription = "Share downloaded files") }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (!supported) {
                EmptyState(
                    "Media browsing not available",
                    "This camera does not expose its memory card over the current connection. " +
                        "Sony PC Remote (USB / PTP-IP) mode does not provide card access; use the camera's " +
                        "Wi-Fi 'Send to Smartphone' / legacy remote mode, or connect the card to the phone.",
                )
                return@Column
            }
            transfer?.let { t ->
                Column(Modifier.padding(16.dp)) {
                    Text("Downloading ${t.index}/${t.total}: ${t.current}", style = MaterialTheme.typography.bodySmall)
                    val total = t.totalBytes
                    if (total != null && total > 0) {
                        LinearProgressIndicator(progress = { (t.bytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            error?.let { Text(it, color = Cinema.Error, modifier = Modifier.padding(16.dp)) }
            if (saved.isNotEmpty()) Text("${saved.size} file(s) saved to Pictures/Movies › SonyCamRemote", color = Cinema.Success, modifier = Modifier.padding(horizontal = 16.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(items, key = { it.id }) { item ->
                    val thumb by produceState<ImageBitmap?>(null, item.id) { value = vm.thumbnail(item) }
                    val isSel = item.id in selected
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .background(Cinema.Surface)
                            .border(if (isSel) 3.dp else 0.dp, Cinema.Accent)
                            .combinedClickable(onClick = { vm.toggle(item) }, onLongClick = { vm.toggle(item) }),
                    ) {
                        thumb?.let { Image(it, contentDescription = item.fileName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        Text(
                            (if (item.kind == MediaKind.MOVIE) "▶ " else "") + item.fileName,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.BottomStart).background(Cinema.Background.copy(alpha = 0.7f)).padding(2.dp),
                            maxLines = 1,
                        )
                    }
                }
                if (items.isNotEmpty() && items.size % 50 == 0) {
                    item {
                        TextButton(onClick = { vm.load(more = true) }, enabled = !loading) { Text("Load more") }
                    }
                }
            }
            if (loading && items.isEmpty()) Text("Loading…", color = Cinema.TextSecondary, modifier = Modifier.padding(16.dp))
            if (!loading && items.isEmpty() && error == null) Text("No files found.", color = Cinema.TextSecondary, modifier = Modifier.padding(16.dp))
        }
    }
}
