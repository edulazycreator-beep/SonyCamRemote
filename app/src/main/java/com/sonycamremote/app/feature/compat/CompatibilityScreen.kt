package com.sonycamremote.app.feature.compat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sonycamremote.app.ui.components.ScreenScaffold
import com.sonycamremote.app.ui.components.SectionCard
import com.sonycamremote.app.ui.components.StatusChip
import com.sonycamremote.app.ui.theme.Cinema
import com.sonycamremote.engine.compat.CompatibilityDatabase
import com.sonycamremote.engine.compat.EvidenceLevel
import com.sonycamremote.engine.compat.Expectation

@Composable
fun CompatibilityScreen(onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    val results = remember(query) { CompatibilityDatabase.search(query) }

    ScreenScaffold("Compatibility database", onBack) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(query, { query = it }, label = { Text("Search model, name or family") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    "No model is marked verified until it passes the hardware test suite. The app always enables " +
                        "features from what the connected camera reports, not from this list.",
                    style = MaterialTheme.typography.bodySmall, color = Cinema.TextSecondary, modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(results, key = { it.model }) { e ->
                SectionCard("${e.family} · ${e.model}", Modifier.clickable { expanded = if (expanded == e.model) null else e.model }) {
                    Text(e.marketingName, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        e.protocols.forEach { p ->
                            StatusChip(
                                p.protocol.label.substringBefore(" ("),
                                when (p.evidence) {
                                    EvidenceLevel.HARDWARE_VERIFIED -> Cinema.Success
                                    EvidenceLevel.LISTED_BY_SONY -> Cinema.Accent
                                    else -> Cinema.TextSecondary
                                },
                            )
                        }
                    }
                    if (expanded == e.model) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            e.protocols.forEach { p ->
                                Text(
                                    "• ${p.protocol.label}: ${p.evidence.label}" +
                                        (p.firmwareRequirement?.let { " · FW $it" } ?: "") + (p.note?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text("Expected (verify at runtime):", style = MaterialTheme.typography.labelSmall, color = Cinema.TextSecondary)
                            Text(
                                e.expected.entries.joinToString { (c, x) ->
                                    c.label + when (x) { Expectation.EXPECTED -> " ✓"; Expectation.NOT_EXPECTED -> " ✗"; Expectation.UNKNOWN -> " ?" }
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            e.knownLimitations.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = Cinema.Warning) }
                        }
                    }
                }
            }
        }
    }
}
