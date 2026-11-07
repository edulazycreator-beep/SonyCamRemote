package com.sonycamremote.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import com.sonycamremote.app.BuildConfig
import com.sonycamremote.app.data.prefs.AppSettings
import com.sonycamremote.app.data.prefs.GridMode
import com.sonycamremote.app.data.prefs.SettingsRepository
import com.sonycamremote.app.ui.components.ScreenScaffold
import com.sonycamremote.app.ui.components.SectionCard
import com.sonycamremote.app.ui.theme.Cinema
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repo: SettingsRepository) : ViewModel() {
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    fun update(t: (AppSettings) -> AppSettings) = viewModelScope.launch { repo.update(t) }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    ScreenScaffold("Settings", onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Live View overlays") {
                Text("Grid", color = Cinema.TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GridMode.entries.forEach { g ->
                        FilterChip(selected = s.grid == g, onClick = { vm.update { it.copy(grid = g) } }, label = { Text(g.label) })
                    }
                }
                Toggle("Histogram", s.showHistogram) { v -> vm.update { it.copy(showHistogram = v) } }
                Toggle("Highlight clipping warning", s.showClippingWarning) { v -> vm.update { it.copy(showClippingWarning = v) } }
                Toggle("Safe area guides", s.showSafeArea) { v -> vm.update { it.copy(showSafeArea = v) } }
                Toggle("Large Live View (if camera offers it)", s.largeLiveView) { v -> vm.update { it.copy(largeLiveView = v) } }
            }
            SectionCard("Shooting") {
                Toggle("Autofocus before capture (half-press)", s.halfPressBeforeCapture) { v -> vm.update { it.copy(halfPressBeforeCapture = v) } }
                Toggle("Keep screen on in Live View", s.keepScreenOn) { v -> vm.update { it.copy(keepScreenOn = v) } }
            }
            SectionCard("Connection") {
                Toggle("Reconnect automatically", s.autoReconnect) { v -> vm.update { it.copy(autoReconnect = v) } }
            }
            SectionCard("About & privacy") {
                Text("SonyCam Remote ${BuildConfig.VERSION_NAME}")
                Text(
                    "This app only talks to cameras and bridge hosts on your local network or USB. It collects no analytics, " +
                        "has no internet backend and stores camera Wi-Fi passwords encrypted with the Android Keystore. " +
                        "Not affiliated with or endorsed by Sony.",
                    style = MaterialTheme.typography.bodySmall, color = Cinema.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
