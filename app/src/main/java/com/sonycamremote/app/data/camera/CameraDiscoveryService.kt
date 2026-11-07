package com.sonycamremote.app.data.camera

import com.sonycamremote.app.data.camera.usb.UsbCameraManager
import com.sonycamremote.app.data.camera.wifi.WifiCameraConnector
import com.sonycamremote.engine.diagnostics.DiagnosticsLog
import com.sonycamremote.engine.discovery.PortProbe
import com.sonycamremote.engine.discovery.SsdpDiscovery
import com.sonycamremote.engine.legacy.DeviceDescriptionParser
import com.sonycamremote.engine.model.ProtocolKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

data class DiscoveredCamera(
    val key: String,
    val title: String,
    val detail: String,
    val profile: ConnectionProfile,
)

/**
 * Finds cameras via:
 *  - USB: attached devices exposing a PTP interface;
 *  - SSDP: legacy Camera Remote API cameras (ScalarWebAPI);
 *  - Port probe of the Wi-Fi gateway: PTP-IP (15740) and legacy HTTP (8080/10000) when the phone is
 *    connected directly to the camera's access point.
 */
@Singleton
class CameraDiscoveryService @Inject constructor(
    private val usb: UsbCameraManager,
    private val wifi: WifiCameraConnector,
    private val http: OkHttpClient,
    private val log: DiagnosticsLog,
) {
    private val ssdp = SsdpDiscovery(log)

    suspend fun discover(): List<DiscoveredCamera> = coroutineScope {
        val usbFound = usb.candidates().map {
            DiscoveredCamera(
                key = "usb:${it.deviceName}",
                title = it.label,
                detail = "USB · PTP" + if (it.hasPermission) "" else " · permission needed",
                profile = ConnectionProfile(name = it.label, protocol = ProtocolKind.SONY_PTP_USB, usbDeviceName = it.deviceName),
            )
        }

        val ssdpJob = async { discoverSsdp() }
        val probeJob = async { probeGateway() }
        val ssdpFound = ssdpJob.await()
        val probed = probeJob.await().filter { p -> ssdpFound.none { it.profile.host == p.profile.host && p.profile.protocol == ProtocolKind.LEGACY_REMOTE_API } }
        (usbFound + ssdpFound + probed).distinctBy { it.key }
    }

    private suspend fun discoverSsdp(): List<DiscoveredCamera> = withContext(Dispatchers.IO) {
        val responses = try {
            val lock = wifi.multicastLock().apply { setReferenceCounted(false); acquire() }
            try {
                ssdp.search(bindAddress = wifi.localAddress())
            } finally {
                lock.release()
            }
        } catch (e: Exception) {
            log.w("Discovery", "SSDP failed: ${e.message}")
            emptyList()
        }
        responses.mapNotNull { r ->
            runCatching {
                val xml = http.newCall(Request.Builder().url(r.location).build()).execute().use { it.body?.string() } ?: return@runCatching null
                val dd = DeviceDescriptionParser.parse(xml)
                if (dd.serviceUrls.isEmpty()) return@runCatching null
                val host = URI(r.location).host
                val name = dd.friendlyName ?: dd.modelName ?: "Sony camera"
                DiscoveredCamera(
                    key = "legacy:$host",
                    title = name,
                    detail = "Wi-Fi · Camera Remote API · $host",
                    profile = ConnectionProfile(
                        name = name, protocol = ProtocolKind.LEGACY_REMOTE_API, host = host,
                        deviceDescriptionUrl = r.location, modelName = dd.modelName,
                    ),
                )
            }.onFailure { log.w("Discovery", "dd.xml ${r.location}: ${it.message}") }.getOrNull()
        }
    }

    private suspend fun probeGateway(): List<DiscoveredCamera> {
        val gw = wifi.gatewayAddress() ?: return emptyList()
        val out = mutableListOf<DiscoveredCamera>()
        if (PortProbe.isOpen(gw, PortProbe.PTP_IP_PORT)) {
            out += DiscoveredCamera(
                key = "ptpip:$gw", title = "Camera at $gw", detail = "Wi-Fi · PTP-IP (PC Remote) · $gw:15740",
                profile = ConnectionProfile(name = "Camera at $gw", protocol = ProtocolKind.SONY_PTP_IP, host = gw, port = PortProbe.PTP_IP_PORT),
            )
        }
        for (port in PortProbe.LEGACY_PORTS) {
            if (PortProbe.isOpen(gw, port)) {
                out += DiscoveredCamera(
                    key = "legacy:$gw", title = "Camera at $gw", detail = "Wi-Fi · HTTP port $port (legacy API?)",
                    profile = ConnectionProfile(name = "Camera at $gw", protocol = ProtocolKind.LEGACY_REMOTE_API, host = gw, port = port),
                )
                break
            }
        }
        return out
    }
}
