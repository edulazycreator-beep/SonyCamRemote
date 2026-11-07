package com.sonycamremote.app.feature.connect

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.sonycamremote.app.data.camera.CameraDiscoveryService
import com.sonycamremote.app.data.camera.CameraSessionManager
import com.sonycamremote.app.data.camera.ConnectionProfile
import com.sonycamremote.app.data.camera.DiscoveredCamera
import com.sonycamremote.app.data.camera.wifi.WifiCameraConnector
import com.sonycamremote.app.data.camera.wifi.WifiCredentials
import com.sonycamremote.app.ui.components.ScreenScaffold
import com.sonycamremote.app.ui.components.SectionCard
import com.sonycamremote.app.ui.theme.Cinema
import com.sonycamremote.engine.model.ProtocolKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val discovery: CameraDiscoveryService,
    private val sessions: CameraSessionManager,
    private val wifi: WifiCameraConnector,
) : ViewModel() {
    private val _found = MutableStateFlow<List<DiscoveredCamera>>(emptyList())
    val found = _found.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _connected = Channel<Unit>(Channel.BUFFERED)
    val connected = _connected.receiveAsFlow()

    val canJoinWifi get() = wifi.canJoinProgrammatically

    fun scan() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            _message.value = null
            try {
                _found.value = discovery.discover()
                if (_found.value.isEmpty()) _message.value =
                    "No camera found. Check the camera's remote / PC Remote setting, or use direct Wi-Fi or a manual address."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Discovery failed: ${e.message}"
            } finally {
                _scanning.value = false
            }
        }
    }

    fun connect(profile: ConnectionProfile) {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = profile.name
            _message.value = null
            try {
                sessions.connect(profile)
                _connected.send(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.message ?: "Connection failed"
            } finally {
                _busy.value = null
            }
        }
    }

    /** Joins the camera's Wi-Fi network, then discovers cameras on it. */
    fun joinWifi(credentials: WifiCredentials) {
        viewModelScope.launch {
            _busy.value = credentials.ssid
            _message.value = null
            try {
                wifi.join(credentials)
                _found.value = discovery.discover().map { it.copy(profile = it.profile.copy(wifi = credentials)) }
                if (_found.value.isEmpty()) _message.value = "Joined ${credentials.ssid}, but no camera answered. Is the camera's remote function active?"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.message
            } finally {
                _busy.value = null
            }
        }
    }

    fun showMessage(text: String) { _message.value = text }
}

@Composable
fun ConnectScreen(onBack: () -> Unit, onConnected: () -> Unit, vm: ConnectViewModel = hiltViewModel()) {
    val found by vm.found.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    var ssid by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var manualHost by rememberSaveable { mutableStateOf("192.168.122.1") }
    var manualPort by rememberSaveable { mutableStateOf("") }
    var manualKind by rememberSaveable { mutableStateOf(ProtocolKind.LEGACY_REMOTE_API) }
    var bridgeCameraId by rememberSaveable { mutableStateOf("0") }

    LaunchedEffect(Unit) {
        vm.scan()
        vm.connected.collect { onConnected() }
    }

    val wifiPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.joinWifi(WifiCredentials(ssid.trim(), password)) else vm.showMessage("Nearby Wi-Fi permission is needed to join the camera network.")
    }
    fun join() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wifiPermission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            vm.joinWifi(WifiCredentials(ssid.trim(), password))
        }
    }
    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val creds = result.contents?.let { WifiCameraConnector.parseQr(it) }
        if (creds == null) {
            if (result.contents != null) vm.showMessage("QR code is not a camera Wi-Fi code.")
        } else {
            ssid = creds.ssid
            password = creds.password.orEmpty()
        }
    }

    ScreenScaffold(
        title = "Connect camera",
        onBack = onBack,
        actions = {
            IconButton(onClick = { vm.scan() }, enabled = !scanning) { Icon(Icons.Filled.Refresh, contentDescription = "Scan again") }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            message?.let { Text(it, color = Cinema.Warning, style = MaterialTheme.typography.bodyMedium) }
            busy?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Connecting to $it…")
                }
            }

            SectionCard("Discovered") {
                if (scanning) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Searching USB and Wi-Fi…", color = Cinema.TextSecondary)
                    }
                } else if (found.isEmpty()) {
                    Text("Nothing found yet.", color = Cinema.TextSecondary)
                }
                found.forEach { cam ->
                    Column(
                        Modifier.fillMaxWidth().clickable(enabled = busy == null) { vm.connect(cam.profile) }.padding(vertical = 8.dp),
                    ) {
                        Text(cam.title)
                        Text(cam.detail, style = MaterialTheme.typography.bodySmall, color = Cinema.TextSecondary)
                    }
                }
            }

            SectionCard("Direct Wi-Fi (camera access point)") {
                OutlinedTextField(ssid, { ssid = it }, label = { Text("SSID, e.g. DIRECT-xxxx:ILCE-xxxx") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        qrScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the QR code on the camera screen").setBeepEnabled(false))
                    }) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                        Text("  Scan QR")
                    }
                    Button(onClick = { join() }, enabled = ssid.isNotBlank() && busy == null && vm.canJoinWifi) { Text("Join & search") }
                }
                if (!vm.canJoinWifi) {
                    Text("On Android 8–9 join the camera network in system Wi-Fi settings, then tap refresh.", color = Cinema.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionCard("Manual address") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ProtocolKind.LEGACY_REMOTE_API to "Remote API",
                        ProtocolKind.SONY_PTP_IP to "PTP-IP",
                        ProtocolKind.SDK_BRIDGE to "SDK bridge",
                    ).forEach { (k, l) -> FilterChip(selected = manualKind == k, onClick = { manualKind = k }, label = { Text(l) }) }
                }
                OutlinedTextField(
                    manualHost, { manualHost = it },
                    label = { Text(if (manualKind == ProtocolKind.SDK_BRIDGE) "Bridge URL (http://host:port)" else "Camera IP address") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (manualKind == ProtocolKind.SDK_BRIDGE) {
                    OutlinedTextField(bridgeCameraId, { bridgeCameraId = it }, label = { Text("Camera id on bridge") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(
                        manualPort, { manualPort = it.filter(Char::isDigit) },
                        label = { Text(if (manualKind == ProtocolKind.SONY_PTP_IP) "Port (default 15740)" else "Port (default 8080)") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(
                    enabled = manualHost.isNotBlank() && busy == null,
                    onClick = {
                        val host = manualHost.trim()
                        val profile = when (manualKind) {
                            ProtocolKind.SDK_BRIDGE -> ConnectionProfile(name = "Bridge $host", protocol = manualKind, bridgeUrl = host, bridgeCameraId = bridgeCameraId.trim())
                            ProtocolKind.SONY_PTP_IP -> ConnectionProfile(name = "Camera $host", protocol = manualKind, host = host, port = manualPort.toIntOrNull() ?: 15740)
                            else -> ConnectionProfile(name = "Camera $host", protocol = manualKind, host = host, port = manualPort.toIntOrNull() ?: 8080)
                        }
                        vm.connect(profile)
                    },
                ) { Text("Connect") }
            }

            SectionCard("USB (OTG)") {
                Text(
                    "Set the camera's USB Connection mode to 'PC Remote', connect a USB-C cable, then tap refresh. Android will ask for permission to access the camera.",
                    color = Cinema.TextSecondary, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
