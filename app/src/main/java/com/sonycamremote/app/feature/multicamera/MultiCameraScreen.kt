package com.sonycamremote.app.feature.multicamera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sonycamremote.app.data.camera.CameraSession
import com.sonycamremote.app.data.camera.CameraSessionManager
import com.sonycamremote.app.data.camera.GroupCommandResult
import com.sonycamremote.app.ui.components.ScreenScaffold
import com.sonycamremote.app.ui.components.SectionCard
import com.sonycamremote.app.ui.components.StatusChip
import com.sonycamremote.app.ui.theme.Cinema
import com.sonycamremote.engine.model.CameraCommand
import com.sonycamremote.engine.model.Capability
import com.sonycamremote.engine.model.ConnectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MultiCameraViewModel @Inject constructor(private val sessions: CameraSessionManager) : ViewModel() {
    val all = sessions.sessions
    val active = sessions.active
    private val _group = MutableStateFlow<Set<String>>(emptySet())
    val group = _group.asStateFlow()
    private val _results = MutableStateFlow<List<GroupCommandResult>>(emptyList())
    val results = _results.asStateFlow()

    fun toggle(id: String) = _group.update { if (id in it) it - id else it + id }
    fun setActive(id: String) = sessions.setActive(id)
    fun disconnect(id: String) = viewModelScope.launch { sessions.disconnect(id); _group.update { it - id } }

    fun send(command: CameraCommand) = viewModelScope.launch {
        _results.value = sessions.groupExecute(_group.value, command)
    }
}

@Composable
fun MultiCameraScreen(onBack: () -> Unit, onOpenLiveView: () -> Unit, onAdd: () -> Unit, vm: MultiCameraViewModel = hiltViewModel()) {
    val all by vm.all.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()

    ScreenScaffold("Multi-camera", onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Connected cameras (${all.size})") {
                if (all.isEmpty()) Text("No cameras connected.", color = Cinema.TextSecondary)
                all.values.forEach { s -> CameraRow(s, s.id == active?.id, s.id in group, vm::toggle, { vm.setActive(s.id); onOpenLiveView() }, { vm.disconnect(s.id) }) }
                OutlinedButton(onClick = onAdd) { Text("Add camera") }
                Text(
                    "Several cameras can be connected at once when they share a network with the phone (camera joined to a router / access point), " +
                        "or when one is on USB and another on Wi-Fi. A phone can only join one camera access point at a time.",
                    style = MaterialTheme.typography.bodySmall, color = Cinema.TextSecondary,
                )
            }

            SectionCard("Group trigger (${group.size} selected)") {
                Text(
                    "Simultaneous network requests — NOT synchronised. Each camera receives the command as soon as its own " +
                        "connection allows; the measured spread is shown below. Frame-accurate sync needs genlock/timecode hardware.",
                    style = MaterialTheme.typography.bodySmall, color = Cinema.Warning,
                )
                val sel = all.filterKeys { it in group }.values
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = sel.isNotEmpty() && sel.all { Capability.STILL_CAPTURE in it.protocol.state.value.capabilities },
                        onClick = { vm.send(CameraCommand.TakePicture) },
                    ) { Text("Shoot all") }
                    Button(
                        enabled = sel.isNotEmpty() && sel.all { Capability.MOVIE_RECORDING in it.protocol.state.value.capabilities },
                        onClick = { vm.send(CameraCommand.StartRecording) },
                    ) { Text("Rec all") }
                    OutlinedButton(
                        enabled = sel.isNotEmpty(),
                        onClick = { vm.send(CameraCommand.StopRecording) },
                    ) { Text("Stop all") }
                }
                results.forEach { r ->
                    Text(
                        String.format(
                            java.util.Locale.US, "%s: sent +%.1f ms, done +%.1f ms%s",
                            r.name, r.dispatchOffsetMs, r.completedAfterMs, r.error?.let { " — $it" } ?: "",
                        ),
                        color = if (r.error == null) Cinema.TextPrimary else Cinema.Error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraRow(
    s: CameraSession, isActive: Boolean, inGroup: Boolean,
    onToggle: (String) -> Unit, onOpen: () -> Unit, onDisconnect: () -> Unit,
) {
    val st by s.protocol.state.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = inGroup, onCheckedChange = { onToggle(s.id) })
        Column(Modifier.weight(1f)) {
            Text(s.displayName + if (isActive) "  (active)" else "")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(
                    st.connection.status.name.lowercase(),
                    if (st.connection.status == ConnectionStatus.CONNECTED) Cinema.Success else Cinema.Error,
                )
                st.battery?.percent?.let { StatusChip("$it%") }
                if (st.recording) StatusChip("REC", Cinema.Record)
            }
        }
        OutlinedButton(onClick = onOpen) { Text("View") }
        OutlinedButton(onClick = onDisconnect) { Text("✕") }
    }
}
