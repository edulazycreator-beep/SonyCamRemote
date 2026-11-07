package com.sonycamremote.app.feature.diagnostics

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sonycamremote.app.data.camera.CameraSessionManager
import com.sonycamremote.app.data.db.SessionEventDao
import com.sonycamremote.app.ui.components.ScreenScaffold
import com.sonycamremote.app.ui.theme.Cinema
import com.sonycamremote.engine.diagnostics.DiagnosticsLog
import com.sonycamremote.engine.diagnostics.LogLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val log: DiagnosticsLog,
    private val history: SessionEventDao,
    private val sessions: CameraSessionManager,
) : ViewModel() {
    val entries = log.entries
    val events = history.observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val active = sessions.active

    fun clear() {
        log.clear()
        viewModelScope.launch { history.clear() }
    }

    fun exportText(): String = buildString {
        sessions.active.value?.let {
            appendLine("=== Capability report ===")
            appendLine(it.report.asText())
        }
        appendLine("=== Log ===")
        append(log.export())
    }
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit, vm: DiagnosticsViewModel = hiltViewModel()) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    ScreenScaffold(
        "Diagnostics", onBack,
        actions = {
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, vm.exportText()) }
                context.startActivity(Intent.createChooser(send, "Share diagnostics"))
            }) { Icon(Icons.Filled.Share, contentDescription = "Share diagnostics") }
            IconButton(onClick = vm::clear) { Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear") }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Commands & connection", "Capability report", "Session history").forEachIndexed { i, l ->
                    FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(l) })
                }
            }
            when (tab) {
                0 -> LazyColumn(contentPadding = PaddingValues(12.dp), reverseLayout = true) {
                    items(entries.asReversed()) { e ->
                        Text(
                            "${fmt.format(Date(e.timestampMs))} ${e.level.name.first()} [${e.source}] ${e.message}" + (e.durationMs?.let { " (${it}ms)" } ?: ""),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (e.level) {
                                LogLevel.ERROR -> Cinema.Error
                                LogLevel.WARN -> Cinema.Warning
                                LogLevel.INFO -> Cinema.TextPrimary
                                LogLevel.DEBUG -> Cinema.TextSecondary
                            },
                        )
                    }
                }
                1 -> Text(
                    active?.report?.asText() ?: "Connect a camera to generate a capability report.",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
                else -> LazyColumn(contentPadding = PaddingValues(12.dp)) {
                    items(events, key = { it.id }) { e ->
                        Text("${fmt.format(Date(e.timestamp))}  ${e.cameraName}: ${e.event}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
