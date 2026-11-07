package com.sonycamremote.engine.model

/** Transport/protocol family used to talk to a camera. */
enum class ProtocolKind(val label: String) {
    /** Legacy Sony Camera Remote API (JSON-RPC over HTTP, ~2013–2018 models). Publicly documented. */
    LEGACY_REMOTE_API("Legacy Camera Remote API (Wi-Fi)"),
    /** PTP with Sony vendor extensions over USB (Camera Remote Command / "PC Remote"). */
    SONY_PTP_USB("Sony PTP (USB)"),
    /** PTP with Sony vendor extensions over TCP/IP (PTP-IP, port 15740). */
    SONY_PTP_IP("Sony PTP-IP (Wi-Fi / LAN)"),
    /** Official Camera Remote SDK running on a separate Linux/macOS/Windows host. */
    SDK_BRIDGE("Camera Remote SDK bridge host"),
}

/** Address used to (re)open a connection. Persisted in saved camera profiles. */
sealed class CameraEndpoint {
    /** [deviceDescriptionUrl] is the SSDP LOCATION (dd.xml); [serviceUrls] are resolved services. */
    data class LegacyHttp(
        val host: String,
        val deviceDescriptionUrl: String?,
        val serviceUrls: Map<String, String>,
    ) : CameraEndpoint()

    data class PtpIp(val host: String, val port: Int = 15740) : CameraEndpoint()

    /** Android USB device identity (resolved by the app module). */
    data class Usb(val deviceName: String, val vendorId: Int, val productId: Int) : CameraEndpoint()

    data class Bridge(val baseUrl: String, val cameraId: String) : CameraEndpoint()
}

data class CameraInfo(
    val modelName: String,
    val friendlyName: String = modelName,
    val manufacturer: String = "Sony",
    val firmwareVersion: String? = null,
    val serialNumber: String? = null,
    val protocol: ProtocolKind,
    val protocolVersion: String? = null,
)

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, FAILED }

data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val message: String? = null,
    val lastLatencyMs: Long? = null,
)

/**
 * Every feature in the UI is gated by a capability. Capabilities are reported by the protocol adapter
 * at runtime from what the connected camera actually advertises — never from a hardcoded model list.
 */
enum class Capability(val label: String) {
    LIVE_VIEW("Live View"),
    LIVE_VIEW_QUALITY("Live View size selection"),
    STILL_CAPTURE("Still capture"),
    HALF_PRESS_AF("Half-press autofocus"),
    MOVIE_RECORDING("Movie recording"),
    ISO("ISO"),
    SHUTTER_SPEED("Shutter speed"),
    APERTURE("Aperture"),
    EXPOSURE_COMPENSATION("Exposure compensation"),
    WHITE_BALANCE("White balance"),
    COLOR_TEMPERATURE("Kelvin color temperature"),
    EXPOSURE_MODE("Exposure mode"),
    FOCUS_MODE("Focus mode"),
    TOUCH_AF("Touch AF position"),
    MANUAL_FOCUS_STEP("Focus near/far drive"),
    ZOOM_OPTICAL("Zoom (camera-driven)"),
    DRIVE_MODE("Drive mode"),
    METERING_MODE("Metering mode"),
    IMAGE_QUALITY("Image quality / file format"),
    ASPECT_RATIO("Aspect ratio"),
    SELF_TIMER("Self-timer"),
    SHOOT_MODE_SWITCH("Still / movie mode switch"),
    BATTERY_STATUS("Battery level"),
    STORAGE_STATUS("Storage status"),
    MEDIA_BROWSE("Browse media"),
    MEDIA_TRANSFER("Transfer media"),
    RECORDING_STATUS("Recording state"),
}

/** Normalised setting identifiers. Each adapter maps these to its own protocol codes. */
enum class PropertyId(val label: String) {
    ISO("ISO"),
    SHUTTER_SPEED("Shutter"),
    F_NUMBER("Aperture"),
    EXPOSURE_COMPENSATION("EV"),
    WHITE_BALANCE("WB"),
    COLOR_TEMPERATURE("Kelvin"),
    EXPOSURE_MODE("Mode"),
    FOCUS_MODE("Focus"),
    DRIVE_MODE("Drive"),
    METERING_MODE("Metering"),
    IMAGE_QUALITY("Quality"),
    FILE_FORMAT("Format"),
    ASPECT_RATIO("Aspect"),
    SHOOT_MODE("Shoot mode"),
    SELF_TIMER("Self-timer"),
    LIVE_VIEW_SIZE("LV size"),
}

/**
 * A camera-reported value. [token] is opaque and adapter-specific (e.g. "1/125" for the legacy API,
 * a decimal raw PTP value for Sony PTP). [label] is for display only.
 */
data class SettingValue(val token: String, val label: String)

data class CameraProperty(
    val id: PropertyId,
    val current: SettingValue?,
    /** Values the camera says are valid right now. Empty when the camera did not report candidates. */
    val options: List<SettingValue>,
    val writable: Boolean,
)

data class BatteryInfo(val percent: Int?, val description: String? = null, val externalPower: Boolean? = null)

data class StorageInfo(
    val present: Boolean,
    val description: String? = null,
    val remainingShots: Int? = null,
    val remainingRecordSeconds: Int? = null,
)

data class ZoomInfo(val positionPercent: Int?, val boxCount: Int? = null, val boxIndex: Int? = null)

enum class FocusStatus { UNKNOWN, FOCUSING, FOCUSED, FAILED }

data class CameraState(
    val connection: ConnectionState = ConnectionState(),
    val info: CameraInfo? = null,
    val capabilities: Set<Capability> = emptySet(),
    val properties: Map<PropertyId, CameraProperty> = emptyMap(),
    val recording: Boolean = false,
    val recordingStartedAtMs: Long? = null,
    val battery: BatteryInfo? = null,
    val storage: StorageInfo? = null,
    val zoom: ZoomInfo? = null,
    val focus: FocusStatus = FocusStatus.UNKNOWN,
    /** Raw camera status string, if the protocol reports one (e.g. "IDLE", "MovieRecording"). */
    val cameraStatus: String? = null,
    /** Only set when the camera actually reports a thermal warning. */
    val overheatingWarning: Boolean? = null,
    val lastCapturedUrls: List<String> = emptyList(),
) {
    fun supports(c: Capability) = c in capabilities
}

/** One decoded-from-wire Live View frame. Always real camera JPEG data, never synthesised. */
class LiveViewFrame(
    val jpeg: ByteArray,
    val sequence: Int,
    val receivedAtNanos: Long,
)

enum class ZoomDirection { IN, OUT }
enum class ZoomMovement { START, STOP, ONE_SHOT }

sealed class CameraCommand(val name: String) {
    data object TakePicture : CameraCommand("takePicture")
    data object HalfPressDown : CameraCommand("halfPressDown")
    data object HalfPressRelease : CameraCommand("halfPressRelease")
    data object StartRecording : CameraCommand("startRecording")
    data object StopRecording : CameraCommand("stopRecording")
    data class Zoom(val direction: ZoomDirection, val movement: ZoomMovement) : CameraCommand("zoom")
    /** Normalised 0..1 coordinates in the live view frame. */
    data class TouchFocus(val x: Float, val y: Float) : CameraCommand("touchFocus")
    data object CancelTouchFocus : CameraCommand("cancelTouchFocus")
    /** Negative = near, positive = far. Magnitude is protocol step size (clamped by adapter). */
    data class FocusStep(val steps: Int) : CameraCommand("focusStep")
}

enum class MediaKind { STILL, MOVIE, OTHER }

data class MediaItem(
    val id: String,
    val fileName: String,
    val kind: MediaKind,
    val createdAt: String?,
    val sizeBytes: Long?,
    val thumbnailUrl: String?,
    val previewUrl: String?,
    val originalUrl: String?,
    /** Protocol-specific handle (PTP object handle etc.). */
    val handle: Long? = null,
)
