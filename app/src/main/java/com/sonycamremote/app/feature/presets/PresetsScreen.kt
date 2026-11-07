package com.sonycamremote.app.feature.presets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sonycamremote.app.data.camera.CameraSessionManager
import com.sonycamremote.app.data.db.PresetDao
import com.sonycamremote.app.data.db.PresetEntity
import com.sonycamremote.app.ui.components.ScreenScaffold
import com.sonycamremote.app.ui.components.SectionCard
import com.sonycamremote.app.ui.theme.Cinema
import com.sonycamremote.engine.model.PropertyId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/** Properties that make sense to store in an exposure preset / shooting recipe. */
private val PRESET_PROPERTIES = listOf(
    PropertyId.EXPOSURE_MODE, PropertyId.SHUTTER_SPEED, PropertyId.F_NUMBER, PropertyId.ISO,
    PropertyId.EXPOSURE_COMPENSATION, PropertyId.WHITE_BALANCE, PropertyId.COLOR_TEMPERATURE,
    PropertyId.FOCUS_MODE, PropertyId.METERING_MODE, PropertyId.DRIVE_MODE, PropertyId.FILE_FORMAT,
)

@HiltViewModel
class PresetsViewModel @Inject constructor(
    private val sessions: CameraSessionManager,
    private val dao: PresetDao,
) : ViewModel() {
    val presets = dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()
    val hasCamera get() = sessions.active.value != null

    fun saveCurrent(name: String) {
        val st = sessions.active.value?.protocol?.state?.value ?: return
        val values = PRESET_PROPERTIES.mapNotNull { id -> st.properties[id]?.current?.let { id.name to JsonPrimitive(it.label) } }.toMap()
        if (values.isEmpty()) { _status.value = "Camera reported no settings to save."; return }
        viewModelScope.launch {
            dao.insert(PresetEntity(name = name, valuesJson = JsonObject(values).toString(), createdAt = System.currentTimeMillis()))
            _status.value = "Saved '$name' (${values.size} settings)."
        }
    }

    /**
     * Applies a preset in a safe order (mode first, so dependent values become writable). Values are matched
     * by label against what the connected camera currently offers; anything it does not offer is skipped.
     */
    fun apply(preset: PresetEntity) {
        val s = sessions.active.value ?: return
        viewModelScope.launch {
            val wanted = Json.parseToJsonElement(preset.valuesJson).jsonObject
            var applied = 0
            val skipped = mutableListOf<String>()
            for (id in PRESET_PROPERTIES) {
                val label = wanted[id.name]?.jsonPrimitive?.content ?: continue
                val prop = s.protocol.state.value.properties[id]
                val option = prop?.options?.firstOrNull { it.label == label }
                if (prop == null || !prop.writable || option == null) { skipped += id.label; continue }
                if (prop.current?.label == label) { applied++; continue }
                runCatching { s.protocol.setProperty(id, option) }
                    .onSuccess { applied++ }
                    .onFailure { skipped += id.label }
            }
            _status.value = "Applied $applied setting(s)" + if (skipped.isNotEmpty()) "; skipped: ${skipped.joinToString()}" else ""
        }
    }

    fun delete(p: PresetEntity) = viewModelScope.launch { dao.delete(p) }
}

@Composable
fun PresetsScreen(onBack: () -> Unit, vm: PresetsViewModel = hiltViewModel()) {
    val presets by vm.presets.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }

    ScreenScaffold("Presets & recipes", onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            status?.let { Text(it, color = Cinema.Accent) }
            SectionCard("Save current camera settings") {
                OutlinedTextField(name, { name = it }, label = { Text("Preset name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(enabled = name.isNotBlank() && vm.hasCamera, onClick = { vm.saveCurrent(name.trim()); name = "" }) { Text("Save preset") }
            }
            SectionCard("Saved presets") {
                if (presets.isEmpty()) Text("No presets yet.", color = Cinema.TextSecondary)
                presets.forEach { p ->
                    val summary = runCatching {
                        Json.parseToJsonElement(p.valuesJson).jsonObject.values.joinToString(" · ") { it.jsonPrimitive.content }
                    }.getOrDefault("")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name)
                            Text(summary, style = MaterialTheme.typography.bodySmall, color = Cinema.TextSecondary)
                        }
                        OutlinedButton(enabled = vm.hasCamera, onClick = { vm.apply(p) }) { Text("Apply") }
                        IconButton(onClick = { vm.delete(p) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${p.name}") }
                    }
                }
            }
        }
    }
}
