package com.sonycamremote.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonycamremote.app.ui.theme.Cinema
import com.sonycamremote.app.ui.theme.NumericStyle
import com.sonycamremote.engine.model.CameraProperty
import com.sonycamremote.engine.model.SettingValue

@Composable
fun StatusChip(text: String, color: Color = Cinema.TextSecondary, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Cinema.Surface2)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/**
 * One exposure readout (e.g. ISO 800). Tappable only when the camera says the property is writable and
 * reported at least one valid option; otherwise it is a read-only display.
 */
@Composable
fun ExposureCell(caption: String, property: CameraProperty?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val editable = property != null && property.writable && property.options.isNotEmpty()
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (editable) Cinema.Surface2 else Cinema.Surface)
            .then(if (editable) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = "$caption ${property?.current?.label ?: "unavailable"}" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(caption.uppercase(), style = MaterialTheme.typography.labelSmall, color = Cinema.TextSecondary)
        Text(
            property?.current?.label ?: "—",
            style = NumericStyle,
            color = when {
                property == null -> Cinema.TextSecondary
                editable -> Cinema.TextPrimary
                else -> Cinema.TextSecondary
            },
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ValuePickerSheet(
    title: String,
    property: CameraProperty,
    onPick: (SettingValue) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Cinema.Surface) {
        Text(
            title, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Text(
            "Values reported by the camera",
            style = MaterialTheme.typography.bodySmall, color = Cinema.TextSecondary,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        LazyColumn(contentPadding = PaddingValues(12.dp), modifier = Modifier.height(380.dp)) {
            items(property.options, key = { it.token }) { v ->
                val selected = v.token == property.current?.token
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Cinema.Accent.copy(alpha = 0.15f) else Color.Transparent)
                        .clickable { onPick(v) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(v.label, style = NumericStyle.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start),
                        color = if (selected) Cinema.Accent else Cinema.TextPrimary, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun ShutterButton(enabled: Boolean, busy: Boolean, onClick: () -> Unit, size: Int = 68) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .border(3.dp, if (enabled) Cinema.TextPrimary else Cinema.Outline, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(if (!enabled) Cinema.Surface2 else if (busy) Cinema.Accent else Cinema.TextPrimary)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Shutter" },
    )
}

@Composable
fun RecordButton(enabled: Boolean, recording: Boolean, onClick: () -> Unit, size: Int = 56) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .border(3.dp, if (enabled) Cinema.TextPrimary else Cinema.Outline, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (recording) "Stop recording" else "Start recording" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (recording) (size * 0.36).dp else (size * 0.6).dp)
                .clip(if (recording) RoundedCornerShape(4.dp) else CircleShape)
                .background(if (enabled) Cinema.Record else Cinema.Surface2),
        )
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Cinema.Surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Cinema.TextSecondary, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
fun InfoRow(label: String, value: String?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Cinema.TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value ?: "Not reported", style = MaterialTheme.typography.bodyMedium, color = if (value == null) Cinema.TextSecondary else Cinema.TextPrimary)
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Cinema.TextSecondary)
        action?.invoke()
    }
}
