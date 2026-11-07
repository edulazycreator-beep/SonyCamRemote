package com.sonycamremote.app.feature.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonycamremote.app.data.camera.CameraSession
import com.sonycamremote.app.data.camera.CameraSessionManager
import com.sonycamremote.app.ui.components.EmptyState
import com.sonycamremote.app.ui.components.InfoRow
import com.sonycamremote.app.ui.components.ScreenScaffold
import com.sonycamremote.app.ui.components.SectionCard
import com.sonycamremote.app.ui.theme.Cinema
import com.sonycamremote.engine.model.PropertyId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class ActiveSessionViewModel @Inject constructor(sessions: CameraSessionManager) : ViewModel() {
    val session: StateFlow<CameraSession?> = sessions.active
}

/** Camera information dashboard: everything the camera reports, nothing more. */
@Composable
fun CameraInfoScreen(onBack: () -> Unit, vm: ActiveSessionViewModel = hiltViewModel()) {
    val session by vm.session.collectAsStateWithLifecycle()
    ScreenScaffold("Camera info", onBack) { pad ->
        val s = session
        if (s == null) {
            EmptyState("No camera connected", "Connect a camera first.", Modifier.padding(pad))
            return@ScreenScaffold
        }
        val st by s.protocol.state.collectAsStateWithLifecycle()
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Camera") {
                InfoRow("Model", st.info?.modelName)
                InfoRow("Firmware", st.info?.firmwareVersion)
                InfoRow("Serial", st.info?.serialNumber)
                InfoRow("Protocol", st.info?.let { "${it.protocol.label} ${it.protocolVersion ?: ""}".trim() })
                InfoRow("Connection", st.connection.status.name.lowercase())
                InfoRow("Command latency", st.connection.lastLatencyMs?.let { "$it ms" })
                InfoRow("Status", st.cameraStatus)
            }
            SectionCard("Power & media") {
                InfoRow("Battery", st.battery?.percent?.let { "$it%" } ?: st.battery?.description)
                InfoRow("External power", st.battery?.externalPower?.let { if (it) "Yes" else "No" })
                InfoRow("Memory card", st.storage?.let { if (it.present) (it.description ?: "Present") else "No card" })
                InfoRow("Remaining shots", st.storage?.remainingShots?.toString())
                InfoRow("Remaining record time", st.storage?.remainingRecordSeconds?.let { "${it / 60} min" })
                InfoRow("Temperature warning", st.overheatingWarning?.let { if (it) "OVERHEATING" else "Normal" })
            }
            SectionCard("Current settings") {
                PropertyId.entries.forEach { id ->
                    st.properties[id]?.let { InfoRow(id.label, it.current?.label) }
                }
                InfoRow("Recording", if (st.recording) "Yes" else "No")
            }
            Text(
                "Values marked 'Not reported' are not exposed by this camera over the current protocol.",
                color = Cinema.TextSecondary,
            )
        }
    }
}
