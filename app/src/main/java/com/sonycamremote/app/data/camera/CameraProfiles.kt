package com.sonycamremote.app.data.camera

import com.sonycamremote.app.data.camera.usb.UsbCameraManager
import com.sonycamremote.app.data.camera.usb.UsbPtpTransport
import com.sonycamremote.app.data.camera.wifi.WifiCredentials
import com.sonycamremote.app.data.db.SavedCameraDao
import com.sonycamremote.app.data.db.SavedCameraEntity
import com.sonycamremote.app.data.security.SecureStore
import com.sonycamremote.engine.bridge.SonyBridgeAdapter
import com.sonycamremote.engine.diagnostics.DiagnosticsLog
import com.sonycamremote.engine.legacy.DeviceDescriptionParser
import com.sonycamremote.engine.legacy.LegacySonyApiAdapter
import com.sonycamremote.engine.model.CameraConnectionException
import com.sonycamremote.engine.model.CameraEndpoint
import com.sonycamremote.engine.model.ProtocolKind
import com.sonycamremote.engine.protocol.CameraProtocol
import com.sonycamremote.engine.ptp.PtpIpTransport
import com.sonycamremote.engine.ptp.SonyPtpAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Everything needed to open (or re-open) a connection to one camera. */
data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: ProtocolKind,
    val host: String? = null,
    val port: Int? = null,
    val deviceDescriptionUrl: String? = null,
    val usbDeviceName: String? = null,
    val bridgeUrl: String? = null,
    val bridgeCameraId: String? = null,
    val wifi: WifiCredentials? = null,
    val modelName: String? = null,
    val lastConnectedAt: Long? = null,
) {
    val summary: String
        get() = when (protocol) {
            ProtocolKind.LEGACY_REMOTE_API -> "Wi-Fi · ${host ?: deviceDescriptionUrl ?: "?"}"
            ProtocolKind.SONY_PTP_IP -> "PTP-IP · $host:${port ?: 15740}"
            ProtocolKind.SONY_PTP_USB -> "USB · ${usbDeviceName ?: "?"}"
            ProtocolKind.SDK_BRIDGE -> "Bridge · $bridgeUrl"
        }
}

/** Builds the right [CameraProtocol] adapter for a profile. */
@Singleton
class ProtocolFactory @Inject constructor(
    private val http: OkHttpClient,
    private val usb: UsbCameraManager,
    private val log: DiagnosticsLog,
) {
    suspend fun create(profile: ConnectionProfile, scope: CoroutineScope): CameraProtocol = when (profile.protocol) {
        ProtocolKind.LEGACY_REMOTE_API -> LegacySonyApiAdapter(
            CameraEndpoint.LegacyHttp(
                host = profile.host ?: throw CameraConnectionException("No camera address"),
                deviceDescriptionUrl = profile.deviceDescriptionUrl,
                serviceUrls = if (profile.deviceDescriptionUrl == null && profile.port != null) {
                    DeviceDescriptionParser.defaultServiceUrls(profile.host.orEmpty(), profile.port)
                } else {
                    emptyMap()
                },
            ),
            http, log, scope,
        )
        ProtocolKind.SONY_PTP_IP -> SonyPtpAdapter(
            PtpIpTransport(profile.host ?: throw CameraConnectionException("No camera address"), profile.port ?: 15740, log),
            ProtocolKind.SONY_PTP_IP, log, scope,
        )
        ProtocolKind.SONY_PTP_USB -> {
            val device = profile.usbDeviceName?.let { usb.find(it) }
                ?: usb.usbManager.deviceList.values.firstOrNull {
                    it.vendorId == UsbPtpTransport.SONY_VENDOR_ID && UsbPtpTransport.findPtpInterface(it) != null
                }
                ?: throw CameraConnectionException("No USB camera attached. Connect the camera with a USB-C/OTG cable in 'PC Remote' mode.")
            if (!usb.requestPermission(device)) throw CameraConnectionException("USB permission denied")
            SonyPtpAdapter(UsbPtpTransport(usb.usbManager, device, log), ProtocolKind.SONY_PTP_USB, log, scope)
        }
        ProtocolKind.SDK_BRIDGE -> SonyBridgeAdapter(
            CameraEndpoint.Bridge(
                profile.bridgeUrl ?: throw CameraConnectionException("No bridge URL"),
                profile.bridgeCameraId ?: "0",
            ),
            http, log, scope,
        )
    }
}

@Singleton
class SavedCameraRepository @Inject constructor(
    private val dao: SavedCameraDao,
    private val secure: SecureStore,
) {
    val profiles: Flow<List<ConnectionProfile>> = dao.observeAll().map { list -> list.map { it.toProfile() } }

    suspend fun save(profile: ConnectionProfile) = dao.upsert(profile.toEntity())

    suspend fun delete(profile: ConnectionProfile) = dao.delete(profile.toEntity())

    suspend fun get(id: String): ConnectionProfile? = dao.get(id)?.toProfile()

    private fun SavedCameraEntity.toProfile() = ConnectionProfile(
        id = id, name = name,
        protocol = ProtocolKind.entries.firstOrNull { it.name == protocol } ?: ProtocolKind.LEGACY_REMOTE_API,
        host = host, port = port, deviceDescriptionUrl = deviceDescriptionUrl, usbDeviceName = usbDeviceName,
        bridgeUrl = bridgeUrl, bridgeCameraId = bridgeCameraId,
        wifi = wifiSsid?.let { WifiCredentials(it, wifiPasswordEncrypted?.let(secure::decrypt), modelName) },
        modelName = modelName, lastConnectedAt = lastConnectedAt,
    )

    private fun ConnectionProfile.toEntity() = SavedCameraEntity(
        id = id, name = name, protocol = protocol.name, host = host, port = port,
        deviceDescriptionUrl = deviceDescriptionUrl, usbDeviceName = usbDeviceName,
        bridgeUrl = bridgeUrl, bridgeCameraId = bridgeCameraId,
        wifiSsid = wifi?.ssid, wifiPasswordEncrypted = wifi?.password?.takeIf { it.isNotEmpty() }?.let(secure::encrypt),
        modelName = modelName, lastConnectedAt = lastConnectedAt,
    )
}
