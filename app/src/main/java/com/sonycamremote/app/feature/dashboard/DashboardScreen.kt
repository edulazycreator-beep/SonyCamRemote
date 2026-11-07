package com.sonycamremote.app.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sonycamremote.app.data.camera.CameraSession
import com.sonycamremote.app.data.camera.CameraSessionManager
import com.sonycamremote.app.data.camera.ConnectionProfile
import com.sonycamremote.app.data.camera.SavedCameraRepository
import com.sonycamremote.app.ui.components.ScreenScaffold
import com.sonycamremote.app.ui.components.SectionCard
import com.sonycamremote.app.ui.components.StatusChip
import com.sonycamremote.app.ui.navigation.Routes
import com.sonycamremote.app.ui.theme.Cinema
import com.sonycamremote.engine.model.ConnectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val sessions: CameraSessionManager,
    private val saved: SavedCameraRepository,
) : ViewModel() {
    val active: StateFlow<CameraSession?> = sessions.active
    val sessionCount = sessions.sessions
    val profiles = saved.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _connecting = MutableStateFlow<String?>(null)
    val connecting = _connecting.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _connected = Channel<Unit>(Channel.BUFFERED)
    val connectedEvents = _connected.receiveAsFlow()

    fun connect(p: ConnectionProfile) {
        if (_connecting.value != null) return
        viewModelScope.launch {
            _connecting.value = p.id
            _error.value = null
            try {
                sessions.connect(p)
                _connected.send(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Connection failed"
            } finally {
                _connecting.value = null
            }
        }
    }

    fun delete(p: ConnectionProfile) = viewModelScope.launch { saved.delete(p) }
}

private data class NavTile(val label: String, val icon: ImageVector, val route: String)

@Composable
fun DashboardScreen(onNavigate: (String) -> Unit, vm: DashboardViewModel = hiltViewModel()) {
    val active by vm.active.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val connecting by vm.connecting.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val sessions by vm.sessionCount.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.connectedEvents.collect { onNavigate(Routes.LIVE_VIEW) } }

    val tiles = listOf(
        NavTile("Connect", Icons.Filled.Add, Routes.CONNECT),
        NavTile("Live View", Icons.Filled.Videocam, Routes.LIVE_VIEW),
        NavTile("Camera info", Icons.Filled.Info, Routes.INFO),
        NavTile("Media", Icons.Filled.PhotoLibrary, Routes.MEDIA),
        NavTile("Multi-camera", Icons.Filled.ViewModule, Routes.MULTI),
        NavTile("Presets", Icons.Filled.Tune, Routes.PRESETS),
        NavTile("Diagnostics", Icons.Filled.BugReport, Routes.DIAGNOSTICS),
        NavTile("Compatibility", Icons.Filled.CameraAlt, Routes.COMPAT),
        NavTile("Settings", Icons.Filled.Settings, Routes.SETTINGS),
    )

    ScreenScaffold(title = "SonyCam Remote", onBack = null) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(pad),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ActiveCameraCard(active, sessions.size, onOpen = { onNavigate(Routes.LIVE_VIEW) }, onConnect = { onNavigate(Routes.CONNECT) })
            }
            items(tiles, key = { it.route }) { t ->
                val needsCamera = t.route in setOf(Routes.LIVE_VIEW, Routes.INFO, Routes.MEDIA)
                val enabled = !needsCamera || active != null
                Column(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Cinema.Surface)
                        .clickable(enabled = enabled) { onNavigate(t.route) }
                        .padding(16.dp),
                ) {
                    Icon(t.icon, contentDescription = null, tint = if (enabled) Cinema.Accent else Cinema.TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    Text(t.label, color = if (enabled) Cinema.TextPrimary else Cinema.TextSecondary)
                }
            }
            if (profiles.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionCard("Saved cameras") {
                        error?.let { Text(it, color = Cinema.Error, style = MaterialTheme.typography.bodySmall) }
                        profiles.forEach { p ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = connecting == null) { vm.connect(p) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.modelName ?: p.name)
                                    Text(p.summary, style = MaterialTheme.typography.bodySmall, color = Cinema.TextSecondary)
                                }
                                if (connecting == p.id) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    IconButton(onClick = { vm.delete(p) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Forget ${p.name}", tint = Cinema.TextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveCameraCard(session: CameraSession?, count: Int, onOpen: () -> Unit, onConnect: () -> Unit) {
    SectionCard(if (count > 1) "Active camera ($count connected)" else "Active camera") {
        if (session == null) {
            Text("No camera connected", style = MaterialTheme.typography.titleMedium)
            Text(
                "Connect over Wi-Fi, USB (PC Remote) or a Camera Remote SDK bridge.",
                color = Cinema.TextSecondary, style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onConnect) { Text("Connect a camera") }
        } else {
            val st by session.protocol.state.collectAsStateWithLifecycle()
            val attempt by session.reconnectAttempt.collectAsStateWithLifecycle()
            Text(session.displayName, style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (label, color) = when {
                    attempt != null -> "Reconnecting ($attempt)" to Cinema.Warning
                    st.connection.status == ConnectionStatus.CONNECTED -> "Connected" to Cinema.Success
                    st.connection.status == ConnectionStatus.FAILED -> "Lost" to Cinema.Error
                    else -> st.connection.status.name.lowercase() to Cinema.TextSecondary
                }
                StatusChip(label, color)
                StatusChip(session.report.info.protocol.label)
                st.battery?.percent?.let { StatusChip("$it%", icon = Icons.Filled.BatteryStd) }
            }
            Button(onClick = onOpen) { Text("Open Live View") }
        }
    }
}
