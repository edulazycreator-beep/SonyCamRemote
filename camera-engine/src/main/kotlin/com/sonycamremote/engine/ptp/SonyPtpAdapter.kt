package com.sonycamremote.engine.ptp

import com.sonycamremote.engine.command.CameraCommandQueue
import com.sonycamremote.engine.diagnostics.DiagnosticsLog
import com.sonycamremote.engine.model.BatteryInfo
import com.sonycamremote.engine.model.CameraCommand
import com.sonycamremote.engine.model.CameraConnectionException
import com.sonycamremote.engine.model.CameraInfo
import com.sonycamremote.engine.model.CameraProperty
import com.sonycamremote.engine.model.CameraState
import com.sonycamremote.engine.model.Capability
import com.sonycamremote.engine.model.ConnectionState
import com.sonycamremote.engine.model.ConnectionStatus
import com.sonycamremote.engine.model.FocusStatus
import com.sonycamremote.engine.model.LiveViewFrame
import com.sonycamremote.engine.model.MediaItem
import com.sonycamremote.engine.model.PropertyId
import com.sonycamremote.engine.model.ProtocolKind
import com.sonycamremote.engine.model.SettingValue
import com.sonycamremote.engine.model.UnsupportedFeatureException
import com.sonycamremote.engine.protocol.CameraProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** A Sony property descriptor as returned by GetAllExtDevicePropInfo. */
data class SonyPropDesc(
    val code: Int,
    val dataType: Int,
    val writable: Boolean,
    val enabled: Boolean,
    val current: PtpValue,
    val formFlag: Int,
    val enumValues: List<Long>,
    val range: Triple<Long, Long, Long>?,
)

/**
 * Parser for Sony's GetAllExtDevicePropInfo dataset:
 *   u64 count, then per property: code u16, type u16, getSet u8, isEnabled u8, default, current,
 *   formFlag u8, form (range: min/max/step, enum: u16 count + values).
 * Unknown form flags stop parsing (their length is unknown) and the properties read so far are kept.
 */
object SonyPropParser {
    fun parseAll(data: ByteArray, onWarning: (String) -> Unit = {}): Map<Int, SonyPropDesc> {
        val r = PtpReader(data)
        val result = linkedMapOf<Int, SonyPropDesc>()
        if (r.remaining < 8) return result
        val count = r.u64()
        var i = 0L
        while (i < count && r.remaining > 6) {
            val code = r.u16()
            try {
                val type = r.u16()
                val getSet = r.u8()
                val enabled = r.u8()
                r.value(type) // factory default
                val current = r.value(type)
                val form = r.u8()
                var enumValues = emptyList<Long>()
                var range: Triple<Long, Long, Long>? = null
                when (form) {
                    Ptp.FORM_NONE -> Unit
                    Ptp.FORM_RANGE -> range = Triple(
                        r.value(type).asLong() ?: 0, r.value(type).asLong() ?: 0, r.value(type).asLong() ?: 0,
                    )
                    Ptp.FORM_ENUM -> {
                        val n = r.u16()
                        enumValues = List(n) { r.value(type).asLong() ?: 0 }
                    }
                    else -> {
                        onWarning("Property 0x%04X has unknown form 0x%02X; stopping parse".format(code, form))
                        return result
                    }
                }
                result[code] = SonyPropDesc(code, type, getSet == 1, enabled != 0, current, form, enumValues, range)
            } catch (e: Exception) {
                onWarning("Truncated property 0x%04X: ${e.message}".format(code))
                return result
            }
            i++
        }
        return result
    }

    /** Ext device info: u16 version, u16[] property codes, optionally u16[] control codes. */
    fun parseExtDeviceInfo(data: ByteArray): Pair<Set<Int>, Set<Int>> {
        val r = PtpReader(data)
        if (r.remaining < 2) return emptySet<Int>() to emptySet()
        r.u16()
        val props = if (r.remaining >= 4) runCatching { r.u16Array().toSet() }.getOrDefault(emptySet()) else emptySet()
        val controls = if (r.remaining >= 4) runCatching { r.u16Array().toSet() }.getOrDefault(emptySet()) else emptySet()
        return props to controls
    }
}

/**
 * Sony PTP adapter shared by USB ([kind] = SONY_PTP_USB) and PTP-IP ([kind] = SONY_PTP_IP).
 * Capabilities = the property and control codes the camera advertises in its ext-device-info.
 */
class SonyPtpAdapter(
    private val transport: PtpTransport,
    override val kind: ProtocolKind,
    private val log: DiagnosticsLog,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 500,
) : CameraProtocol {

    private val session = PtpSession(transport)
    private val queue = CameraCommandQueue(log, "SonyPTP")
    private val _state = MutableStateFlow(CameraState())
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    private var deviceInfo: PtpDeviceInfo? = null
    private var advertisedProps: Set<Int> = emptySet()
    private var advertisedControls: Set<Int> = emptySet()
    @Volatile private var props: Map<Int, SonyPropDesc> = emptyMap()
    private var pollJob: Job? = null
    private var sdioVersion: Int? = null

    override suspend fun connect(): CameraInfo = withContext(Dispatchers.IO) {
        _state.update { it.copy(connection = ConnectionState(ConnectionStatus.CONNECTING, transport.description)) }
        try {
            transport.open()
            session.openSession()
            val di = PtpDeviceInfo.parse(
                session.op(Ptp.OC_GET_DEVICE_INFO).data ?: throw CameraConnectionException("Empty DeviceInfo"),
            )
            deviceInfo = di
            log.i("SonyPTP", "DeviceInfo: ${di.manufacturer} ${di.model} fw ${di.deviceVersion}, vendor ext 0x%X".format(di.vendorExtensionId))
            if (!di.manufacturer.contains("Sony", ignoreCase = true)) {
                log.w("SonyPTP", "Manufacturer '${di.manufacturer}' is not Sony; continuing with capability detection only")
            }
            if (SonyPtp.OC_SDIO_CONNECT !in di.operations) {
                throw CameraConnectionException(
                    "Camera does not advertise Sony remote operations. Set the USB/Network connection mode to 'PC Remote'.",
                )
            }
            sdioHandshake()
            refreshProps()
            val info = CameraInfo(
                modelName = di.model,
                friendlyName = di.model,
                manufacturer = di.manufacturer,
                firmwareVersion = di.deviceVersion,
                serialNumber = di.serialNumber.ifBlank { null },
                protocol = kind,
                protocolVersion = sdioVersion?.let { "SDIO ${it}" },
            )
            _state.update {
                it.copy(info = info, connection = ConnectionState(ConnectionStatus.CONNECTED, transport.description))
            }
            publish()
            startPolling()
            info
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(connection = ConnectionState(ConnectionStatus.FAILED, e.message)) }
            runCatching { transport.close() }
            throw e
        }
    }

    private suspend fun sdioHandshake() {
        session.op(SonyPtp.OC_SDIO_CONNECT, 1, 0, 0)
        session.op(SonyPtp.OC_SDIO_CONNECT, 2, 0, 0)
        var ext: ByteArray? = null
        for (v in SonyPtp.SDIO_VERSIONS) {
            try {
                ext = session.op(SonyPtp.OC_SDIO_GET_EXT_DEVICE_INFO, v).data
                sdioVersion = v
                break
            } catch (e: PtpException) {
                log.d("SonyPTP", "Ext device info v$v rejected: ${e.message}")
            }
        }
        session.op(SonyPtp.OC_SDIO_CONNECT, 3, 0, 0)
        ext?.let {
            val (p, c) = SonyPropParser.parseExtDeviceInfo(it)
            advertisedProps = p
            advertisedControls = c
        }
        log.i("SonyPTP", "SDIO v$sdioVersion: ${advertisedProps.size} properties, ${advertisedControls.size} controls")
    }

    override suspend fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { session.closeSession() }
            runCatching { transport.close() }
        }
        _state.update { it.copy(connection = ConnectionState(ConnectionStatus.DISCONNECTED)) }
    }

    override suspend fun refreshState() {
        refreshProps()
        publish()
    }

    private suspend fun refreshProps() {
        val data = session.op(SonyPtp.OC_GET_ALL_EXT_DEVICE_PROP_INFO).data ?: return
        props = SonyPropParser.parseAll(data) { log.w("SonyPTP", it) }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            var failures = 0
            while (isActive) {
                delay(pollIntervalMs)
                try {
                    refreshState()
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    log.w("SonyPTP", "Property poll failed ($failures): ${e.message}")
                    if (failures >= 3) {
                        _state.update { it.copy(connection = ConnectionState(ConnectionStatus.FAILED, "Camera stopped responding")) }
                        return@launch
                    }
                }
            }
        }
    }

    private fun publish() {
        val properties = SonyPtp.propertyCodes.mapNotNull { (id, code) ->
            val d = props[code] ?: return@mapNotNull null
            val raw = d.current.asLong()
            val options = when {
                d.enumValues.isNotEmpty() -> d.enumValues
                d.range != null -> expandRange(d.range)
                else -> emptyList()
            }.map { SettingValue(it.toString(), SonyPtp.label(code, it)) }
            id to CameraProperty(
                id,
                raw?.let { SettingValue(it.toString(), SonyPtp.label(code, it)) },
                options,
                writable = d.writable && d.enabled,
            )
        }.toMap()

        val battery = props[SonyPtp.DPC_BATTERY_LEVEL]?.current?.asLong()?.toInt()
        val focusFound = props[SonyPtp.DPC_FOCUS_FOUND]?.current?.asLong()?.toInt()
        _state.update { s ->
            s.copy(
                properties = properties,
                capabilities = capabilities(),
                battery = battery?.let { BatteryInfo(it.takeIf { v -> v in 0..100 }) } ?: s.battery,
                focus = when (focusFound) {
                    2 -> FocusStatus.FOCUSED
                    1 -> FocusStatus.UNKNOWN
                    null -> s.focus
                    else -> s.focus
                },
                connection = s.connection.copy(lastLatencyMs = queue.lastLatencyMs),
            )
        }
    }

    private fun expandRange(r: Triple<Long, Long, Long>): List<Long> {
        val (min, max, step) = r
        if (step <= 0 || max < min || (max - min) / step > 400) return emptyList()
        return (min..max step step).toList()
    }

    private fun has(code: Int) = code in advertisedProps || code in advertisedControls || code in props

    private fun capabilities(): Set<Capability> = buildSet {
        val ops = deviceInfo?.operations.orEmpty()
        if (Ptp.OC_GET_OBJECT in ops) add(Capability.LIVE_VIEW)
        if (has(SonyPtp.CTRL_CAPTURE)) add(Capability.STILL_CAPTURE)
        if (has(SonyPtp.CTRL_AUTO_FOCUS)) add(Capability.HALF_PRESS_AF)
        if (has(SonyPtp.CTRL_MOVIE)) add(Capability.MOVIE_RECORDING)
        if (has(SonyPtp.CTRL_NEAR_FAR)) add(Capability.MANUAL_FOCUS_STEP)
        fun prop(code: Int, cap: Capability) { if (props[code] != null) add(cap) }
        prop(SonyPtp.DPC_ISO, Capability.ISO)
        prop(SonyPtp.DPC_SHUTTER_SPEED, Capability.SHUTTER_SPEED)
        prop(SonyPtp.DPC_F_NUMBER, Capability.APERTURE)
        prop(SonyPtp.DPC_EXPOSURE_BIAS, Capability.EXPOSURE_COMPENSATION)
        prop(SonyPtp.DPC_WHITE_BALANCE, Capability.WHITE_BALANCE)
        prop(SonyPtp.DPC_COLOR_TEMP, Capability.COLOR_TEMPERATURE)
        prop(SonyPtp.DPC_EXPOSURE_PROGRAM, Capability.EXPOSURE_MODE)
        prop(SonyPtp.DPC_FOCUS_MODE, Capability.FOCUS_MODE)
        prop(SonyPtp.DPC_STILL_CAPTURE_MODE, Capability.DRIVE_MODE)
        prop(SonyPtp.DPC_METERING_MODE, Capability.METERING_MODE)
        prop(SonyPtp.DPC_COMPRESSION, Capability.IMAGE_QUALITY)
        prop(SonyPtp.DPC_ASPECT_RATIO, Capability.ASPECT_RATIO)
        prop(SonyPtp.DPC_BATTERY_LEVEL, Capability.BATTERY_STATUS)
        // Media browsing: in PC Remote mode Sony bodies do not expose the memory card over PTP.
    }

    // ---------------------------------------------------------------- commands

    override suspend fun execute(command: CameraCommand) {
        queue.submit(command.name, timeoutMs = 15_000) {
            when (command) {
                CameraCommand.TakePicture -> {
                    require(Capability.STILL_CAPTURE)
                    if (has(SonyPtp.CTRL_AUTO_FOCUS)) button(SonyPtp.CTRL_AUTO_FOCUS, SonyPtp.BUTTON_DOWN)
                    try {
                        button(SonyPtp.CTRL_CAPTURE, SonyPtp.BUTTON_DOWN)
                        delay(100)
                        button(SonyPtp.CTRL_CAPTURE, SonyPtp.BUTTON_UP)
                    } finally {
                        if (has(SonyPtp.CTRL_AUTO_FOCUS)) {
                            withContext(NonCancellable) { button(SonyPtp.CTRL_AUTO_FOCUS, SonyPtp.BUTTON_UP) }
                        }
                    }
                }
                CameraCommand.HalfPressDown -> { require(Capability.HALF_PRESS_AF); button(SonyPtp.CTRL_AUTO_FOCUS, SonyPtp.BUTTON_DOWN) }
                CameraCommand.HalfPressRelease -> { require(Capability.HALF_PRESS_AF); button(SonyPtp.CTRL_AUTO_FOCUS, SonyPtp.BUTTON_UP) }
                CameraCommand.StartRecording, CameraCommand.StopRecording -> {
                    require(Capability.MOVIE_RECORDING)
                    val wantRecording = command == CameraCommand.StartRecording
                    // The movie control is a toggle: only press if our known state differs.
                    if (_state.value.recording != wantRecording) {
                        button(SonyPtp.CTRL_MOVIE, SonyPtp.BUTTON_DOWN)
                        delay(100)
                        button(SonyPtp.CTRL_MOVIE, SonyPtp.BUTTON_UP)
                        _state.update {
                            it.copy(
                                recording = wantRecording,
                                recordingStartedAtMs = if (wantRecording) System.currentTimeMillis() else null,
                            )
                        }
                    }
                }
                is CameraCommand.FocusStep -> {
                    require(Capability.MANUAL_FOCUS_STEP)
                    val steps = command.steps.coerceIn(-7, 7)
                    if (steps != 0) {
                        val data = PtpWriter().value(Ptp.DT_INT16, steps.toLong()).toByteArray()
                        session.op(SonyPtp.OC_SET_CONTROL_DEVICE_B, SonyPtp.CTRL_NEAR_FAR, dataOut = data)
                    }
                }
                is CameraCommand.Zoom -> throw UnsupportedFeatureException(
                    Capability.ZOOM_OPTICAL, "Zoom over Sony PTP needs codes from the official Command Reference",
                )
                is CameraCommand.TouchFocus, CameraCommand.CancelTouchFocus -> throw UnsupportedFeatureException(
                    Capability.TOUCH_AF, "Touch AF over Sony PTP needs codes from the official Command Reference",
                )
            }
        }
    }

    private suspend fun button(code: Int, value: Int) {
        val data = PtpWriter().u16(value).toByteArray()
        session.op(SonyPtp.OC_SET_CONTROL_DEVICE_B, code, dataOut = data)
    }

    override suspend fun setProperty(id: PropertyId, value: SettingValue) {
        val code = SonyPtp.propertyCodes[id] ?: throw UnsupportedFeatureException(null, id.label)
        val desc = props[code] ?: throw UnsupportedFeatureException(null, "${id.label} not reported by camera")
        if (!desc.writable || !desc.enabled) throw UnsupportedFeatureException(null, "${id.label} is read-only right now")
        val raw = value.token.toLongOrNull() ?: throw IllegalArgumentException("Bad value token ${value.token}")
        if (desc.enumValues.isNotEmpty() && raw !in desc.enumValues) {
            throw IllegalArgumentException("${value.label} is not a value the camera currently accepts")
        }
        queue.submit("set ${id.name}=${value.label}") {
            val data = PtpWriter().value(desc.dataType, raw).toByteArray()
            session.op(SonyPtp.OC_SET_CONTROL_DEVICE_A, code, dataOut = data)
        }
        delay(150)
        runCatching { refreshState() }
    }

    private fun require(c: Capability) {
        if (c !in _state.value.capabilities) throw UnsupportedFeatureException(c, c.label)
    }

    // ---------------------------------------------------------------- live view

    override fun liveView(preferLargeSize: Boolean): Flow<LiveViewFrame> = flow {
        require(Capability.LIVE_VIEW)
        var seq = 0
        var consecutiveErrors = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val started = System.nanoTime()
            try {
                val data = session.op(Ptp.OC_GET_OBJECT, SonyPtp.HANDLE_LIVE_VIEW, timeoutMs = 3000).data
                val jpeg = data?.let { extractJpeg(it) }
                if (jpeg != null) {
                    emit(LiveViewFrame(jpeg, seq++, System.nanoTime()))
                    consecutiveErrors = 0
                }
            } catch (e: PtpException) {
                // Camera not ready yet (e.g. right after mode change): back off briefly.
                if (++consecutiveErrors > 50) throw e
                delay(100)
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            if (elapsedMs < MIN_FRAME_INTERVAL_MS) delay(MIN_FRAME_INTERVAL_MS - elapsedMs)
        }
    }.flowOn(Dispatchers.IO)

    // ---------------------------------------------------------------- media

    override suspend fun listMedia(offset: Int, count: Int): List<MediaItem> =
        throw UnsupportedFeatureException(
            Capability.MEDIA_BROWSE,
            "In PC Remote mode Sony cameras do not expose the memory card. Use the legacy Wi-Fi API or MTP mode.",
        )

    override suspend fun download(item: MediaItem, sink: OutputStream, onProgress: (Long, Long?) -> Unit): Unit =
        throw UnsupportedFeatureException(Capability.MEDIA_TRANSFER, "Not available over Sony PTP remote mode")

    override suspend fun thumbnail(item: MediaItem): ByteArray? = null

    companion object {
        private const val MIN_FRAME_INTERVAL_MS = 33L

        /** Live View object data carries a Sony header before the JPEG; locate SOI..EOI. */
        fun extractJpeg(data: ByteArray): ByteArray? {
            var start = -1
            for (i in 0 until data.size - 1) {
                if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte()) { start = i; break }
            }
            if (start < 0) return null
            var end = -1
            for (i in data.size - 2 downTo start + 2) {
                if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD9.toByte()) { end = i + 2; break }
            }
            if (end < 0) return null
            return data.copyOfRange(start, end)
        }
    }
}
