package com.sonycamremote.app.feature.liveview

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonycamremote.app.data.camera.CameraSession
import com.sonycamremote.app.data.camera.DecodedFrame
import com.sonycamremote.app.data.prefs.AppSettings
import com.sonycamremote.app.ui.components.EmptyState
import com.sonycamremote.app.ui.components.ExposureCell
import com.sonycamremote.app.ui.components.FocusMarker
import com.sonycamremote.app.ui.components.FramingOverlay
import com.sonycamremote.app.ui.components.Histogram
import com.sonycamremote.app.ui.components.RecordButton
import com.sonycamremote.app.ui.components.SectionCard
import com.sonycamremote.app.ui.components.ShutterButton
import com.sonycamremote.app.ui.components.StatusChip
import com.sonycamremote.app.ui.components.ValuePickerSheet
import com.sonycamremote.app.ui.navigation.Routes
import com.sonycamremote.app.ui.theme.Cinema
import com.sonycamremote.app.ui.theme.NumericStyle
import com.sonycamremote.engine.model.CameraState
import com.sonycamremote.engine.model.Capability
import com.sonycamremote.engine.model.ConnectionStatus
import com.sonycamremote.engine.model.FocusStatus
import com.sonycamremote.engine.model.PropertyId
import com.sonycamremote.engine.model.ZoomDirection
import com.sonycamremote.engine.model.ZoomMovement
import kotlinx.coroutines.delay

@Composable
fun LiveViewScreen(onBack: () -> Unit, onNavigate: (String) -> Unit, vm: LiveViewViewModel = hiltViewModel()) {
    val session by vm.session.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LifecycleStartEffect(Unit) {
        vm.startLiveView()
        onStopOrDispose { vm.stopLiveView() }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val s = session
    Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        if (s == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState("No camera connected", "Connect a camera to start Live View.")
                Button(onClick = onBack) { Text("Back") }
            }
        } else {
            Workspace(s, settings, vm, onBack, onNavigate)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Workspace(
    session: CameraSession,
    settings: AppSettings,
    vm: LiveViewViewModel,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val state by session.protocol.state.collectAsStateWithLifecycle()
    val frame by vm.frame.collectAsStateWithLifecycle()
    val lvError by vm.liveViewError.collectAsStateWithLifecycle()
    val capturing by vm.capturing.collectAsStateWithLifecycle()
    val focusPoint by vm.focusPoint.collectAsStateWithLifecycle()
    val attempt by session.reconnectAttempt.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf<PropertyId?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val panelWidth = if (maxWidth > 900.dp) 320.dp else 260.dp

        val preview: @Composable ColumnScope.() -> Unit = {
            TopStatusBar(session, state, frame, attempt, onBack, onMedia = { onNavigate(Routes.MEDIA) })
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                PreviewArea(state, frame, lvError, settings, focusPoint, vm)
            }
            BottomControlBar(state, capturing, onPick = { picker = it }, vm)
        }

        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight(), content = preview)
                SidePanel(state, vm, onPick = { picker = it }, Modifier.width(panelWidth).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().weight(0.62f), content = preview)
                SidePanel(state, vm, onPick = { picker = it }, Modifier.fillMaxWidth().weight(0.38f))
            }
        }
    }

    picker?.let { id ->
        val prop = state.properties[id]
        if (prop != null && prop.options.isNotEmpty()) {
            ValuePickerSheet(title = id.label, property = prop, onPick = { vm.setProperty(id, it); picker = null }, onDismiss = { picker = null })
        }
    }
}

@Composable
private fun TopStatusBar(
    session: CameraSession,
    state: CameraState,
    frame: DecodedFrame?,
    attempt: Int?,
    onBack: () -> Unit,
    onMedia: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Cinema.Background).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(session.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val (label, color) = when {
                attempt != null -> "RECONNECT $attempt" to Cinema.Warning
                state.connection.status == ConnectionStatus.CONNECTED -> "LINK" to Cinema.Success
                else -> state.connection.status.name to Cinema.Error
            }
            StatusChip(label, color)
            if (state.recording) RecordingTimer(state.recordingStartedAtMs)
            state.battery?.percent?.let {
                StatusChip("$it%", if (it <= 15) Cinema.Error else Cinema.TextPrimary, Icons.Filled.BatteryStd)
            }
            state.storage?.let { st ->
                val text = when {
                    !st.present -> "NO CARD"
                    st.remainingShots != null -> "${st.remainingShots}"
                    st.remainingRecordSeconds != null -> "${st.remainingRecordSeconds / 60}m"
                    else -> "CARD"
                }
                StatusChip(text, if (st.present) Cinema.TextPrimary else Cinema.Error, Icons.Filled.SdCard)
            }
            if (state.overheatingWarning == true) StatusChip("HOT", Cinema.Error, Icons.Filled.Thermostat)
            frame?.let { StatusChip(String.format(java.util.Locale.US, "%.0f fps", it.fps)) }
            state.connection.lastLatencyMs?.let { StatusChip("cmd ${it}ms") }
        }
        if (Capability.MEDIA_BROWSE in state.capabilities) {
            IconButton(onClick = onMedia) { Icon(Icons.Filled.PhotoLibrary, contentDescription = "Media") }
        }
    }
}

@Composable
private fun RecordingTimer(startedAt: Long?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) { now = System.currentTimeMillis(); delay(500) }
    }
    val secs = ((now - (startedAt ?: now)) / 1000).coerceAtLeast(0)
    StatusChip("● REC %02d:%02d:%02d".format(secs / 3600, (secs / 60) % 60, secs % 60), Cinema.Record)
}

@Composable
private fun PreviewArea(
    state: CameraState,
    frame: DecodedFrame?,
    error: String?,
    settings: AppSettings,
    focusPoint: Pair<Float, Float>?,
    vm: LiveViewViewModel,
) {
    if (Capability.LIVE_VIEW !in state.capabilities) {
        EmptyState("Live View not available", "This camera did not advertise Live View over the current connection. Remote controls still work.")
        return
    }
    if (frame == null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(error ?: "Waiting for Live View…", color = if (error != null) Cinema.Error else Cinema.TextSecondary)
            if (error != null) OutlinedButton(onClick = { vm.retryLiveView() }) { Text("Retry") }
        }
        return
    }
    var scale by remember { mutableFloatStateOf(1f) }
    val transform = rememberTransformableState { zoom, _, _ -> scale = (scale * zoom).coerceIn(1f, 6f) }
    val touchAf = Capability.TOUCH_AF in state.capabilities
    val bmp = frame.bitmap
    Box(
        Modifier
            .aspectRatio(bmp.width.toFloat() / bmp.height.coerceAtLeast(1))
            .transformable(transform)
            .pointerInput(touchAf, scale) {
                detectTapGestures(
                    onDoubleTap = { scale = 1f },
                    onTap = { o ->
                        if (touchAf && scale == 1f) vm.touchFocus(o.x / size.width, o.y / size.height)
                    },
                    onLongPress = { if (touchAf) vm.clearFocus() },
                )
            },
    ) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Camera Live View",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale },
        )
        FramingOverlay(settings.grid, settings.showSafeArea)
        focusPoint?.let { (x, y) -> if (scale == 1f) FocusMarker(x, y, state.focus == FocusStatus.FOCUSED) }
        Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (settings.showHistogram) frame.histogram?.let { Histogram(it, if (settings.showClippingWarning) frame.highlightClipping else null) }
            if (settings.showClippingWarning && (frame.highlightClipping ?: 0f) > 0.02f) {
                StatusChip("HIGHLIGHTS CLIPPING", Cinema.Error)
            }
            if (scale > 1f) StatusChip(String.format(java.util.Locale.US, "Preview ×%.1f (display only)", scale), Cinema.Accent)
        }
    }
}

@Composable
private fun BottomControlBar(state: CameraState, capturing: Boolean, onPick: (PropertyId) -> Unit, vm: LiveViewViewModel) {
    Row(
        Modifier.fillMaxWidth().background(Cinema.Background).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                PropertyId.EXPOSURE_MODE to "Mode",
                PropertyId.SHUTTER_SPEED to "Shutter",
                PropertyId.F_NUMBER to "Iris",
                PropertyId.ISO to "ISO",
                PropertyId.EXPOSURE_COMPENSATION to "EV",
                PropertyId.WHITE_BALANCE to "WB",
            ).forEach { (id, cap) ->
                if (state.properties.containsKey(id)) ExposureCell(cap, state.properties[id], onClick = { onPick(id) })
            }
        }
        if (Capability.MOVIE_RECORDING in state.capabilities) {
            RecordButton(enabled = state.connection.status == ConnectionStatus.CONNECTED, recording = state.recording, onClick = vm::toggleRecording)
        }
        if (Capability.STILL_CAPTURE in state.capabilities) {
            ShutterButton(enabled = state.connection.status == ConnectionStatus.CONNECTED, busy = capturing, onClick = vm::capture)
        }
    }
}

@Composable
private fun SidePanel(state: CameraState, vm: LiveViewViewModel, onPick: (PropertyId) -> Unit, modifier: Modifier) {
    val interval by vm.interval.collectAsStateWithLifecycle()
    var showInterval by remember { mutableStateOf(false) }
    Column(
        modifier.background(Cinema.Background).verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val focusCaps = setOf(Capability.HALF_PRESS_AF, Capability.MANUAL_FOCUS_STEP, Capability.FOCUS_MODE, Capability.TOUCH_AF)
        if (state.capabilities.any { it in focusCaps }) {
            SectionCard("Focus") {
                PropertyGrid(state, listOf(PropertyId.FOCUS_MODE), onPick)
                if (Capability.HALF_PRESS_AF in state.capabilities) {
                    HoldButton("AF (hold)", onPress = { vm.halfPress(true) }, onRelease = { vm.halfPress(false) })
                }
                if (Capability.MANUAL_FOCUS_STEP in state.capabilities) {
                    Text("Focus drive", style = MaterialTheme.typography.labelSmall, color = Cinema.TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(-7 to "≪", -2 to "<", 2 to ">", 7 to "≫").forEach { (n, l) ->
                            OutlinedButton(onClick = { vm.focusStep(n) }, modifier = Modifier.weight(1f)) { Text(l) }
                        }
                    }
                    Text("Near ← → Far", style = MaterialTheme.typography.labelSmall, color = Cinema.TextSecondary)
                }
                if (Capability.TOUCH_AF in state.capabilities) {
                    Text("Tap the preview to set the AF point; long-press to clear.", style = MaterialTheme.typography.bodySmall, color = Cinema.TextSecondary)
                }
            }
        }

        if (Capability.ZOOM_OPTICAL in state.capabilities) {
            SectionCard("Zoom (camera)") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HoldButton("W −", Modifier.weight(1f),
                        onPress = { vm.zoom(ZoomDirection.OUT, ZoomMovement.START) },
                        onRelease = { vm.zoom(ZoomDirection.OUT, ZoomMovement.STOP) })
                    HoldButton("T +", Modifier.weight(1f),
                        onPress = { vm.zoom(ZoomDirection.IN, ZoomMovement.START) },
                        onRelease = { vm.zoom(ZoomDirection.IN, ZoomMovement.STOP) })
                }
                state.zoom?.positionPercent?.let { Text("Position $it%", style = NumericStyle) }
                Text(
                    "Camera zoom. The camera decides whether this is optical or its own digital zoom; pinch on the preview is display-only.",
                    style = MaterialTheme.typography.bodySmall, color = Cinema.TextSecondary,
                )
            }
        }

        SectionCard("Exposure & image") {
            PropertyGrid(
                state,
                listOf(
                    PropertyId.COLOR_TEMPERATURE, PropertyId.METERING_MODE, PropertyId.DRIVE_MODE,
                    PropertyId.FILE_FORMAT, PropertyId.IMAGE_QUALITY, PropertyId.ASPECT_RATIO,
                    PropertyId.SELF_TIMER, PropertyId.SHOOT_MODE,
                ),
                onPick,
            )
        }

        if (Capability.STILL_CAPTURE in state.capabilities) {
            SectionCard("Intervalometer") {
                if (interval.running) {
                    Text(
                        "Shot ${interval.taken}" + (if (interval.total > 0) " / ${interval.total}" else ""),
                        style = NumericStyle,
                    )
                    interval.lastError?.let { Text(it, color = Cinema.Error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = vm::stopInterval) { Text("Stop") }
                } else {
                    if (interval.taken > 0) Text("Last run: ${interval.taken} shots", color = Cinema.TextSecondary)
                    OutlinedButton(onClick = { showInterval = true }) { Text("Set up timelapse") }
                }
            }
        }
    }
    if (showInterval) IntervalDialog(onDismiss = { showInterval = false }) { i, c, d -> vm.startInterval(i, c, d); showInterval = false }
}

@Composable
private fun PropertyGrid(state: CameraState, ids: List<PropertyId>, onPick: (PropertyId) -> Unit) {
    val present = ids.filter { state.properties.containsKey(it) }
    if (present.isEmpty()) return
    present.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pair.forEach { id -> ExposureCell(id.label, state.properties[id], onClick = { onPick(id) }, modifier = Modifier.weight(1f)) }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun HoldButton(label: String, modifier: Modifier = Modifier, onPress: () -> Unit, onRelease: () -> Unit) {
    Box(
        modifier
            .height(44.dp)
            .background(Cinema.Surface2, MaterialTheme.shapes.small)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onPress()
                    tryAwaitRelease()
                    onRelease()
                })
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label) }
}

@Composable
private fun IntervalDialog(onDismiss: () -> Unit, onStart: (Int, Int, Int) -> Unit) {
    var interval by remember { mutableStateOf("5") }
    var count by remember { mutableStateOf("100") }
    var startDelay by remember { mutableStateOf("0") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Timelapse / intervalometer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(interval, { interval = it.filter(Char::isDigit) }, label = { Text("Interval (seconds)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(count, { count = it.filter(Char::isDigit) }, label = { Text("Number of shots (0 = until stopped)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(startDelay, { startDelay = it.filter(Char::isDigit) }, label = { Text("Start delay (seconds)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text(
                    "Keep the app open while it runs. The interval cannot be shorter than the camera's own capture and write time.",
                    style = MaterialTheme.typography.bodySmall, color = Cinema.TextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = (interval.toIntOrNull() ?: 0) >= 1,
                onClick = { onStart(interval.toInt(), count.toIntOrNull() ?: 0, startDelay.toIntOrNull() ?: 0) },
            ) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
