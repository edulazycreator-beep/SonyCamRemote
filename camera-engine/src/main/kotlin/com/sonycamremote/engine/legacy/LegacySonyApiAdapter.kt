package com.sonycamremote.engine.legacy

import com.sonycamremote.engine.command.CameraCommandQueue
import com.sonycamremote.engine.diagnostics.DiagnosticsLog
import com.sonycamremote.engine.legacy.LegacyApiClient.Companion.p
import com.sonycamremote.engine.model.BatteryInfo
import com.sonycamremote.engine.model.CameraCommand
import com.sonycamremote.engine.model.CameraConnectionException
import com.sonycamremote.engine.model.CameraEndpoint
import com.sonycamremote.engine.model.CameraInfo
import com.sonycamremote.engine.model.CameraProperty
import com.sonycamremote.engine.model.CameraProtocolException
import com.sonycamremote.engine.model.CameraState
import com.sonycamremote.engine.model.Capability
import com.sonycamremote.engine.model.ConnectionState
import com.sonycamremote.engine.model.ConnectionStatus
import com.sonycamremote.engine.model.LiveViewFrame
import com.sonycamremote.engine.model.MediaItem
import com.sonycamremote.engine.model.MediaKind
import com.sonycamremote.engine.model.PropertyId
import com.sonycamremote.engine.model.ProtocolKind
import com.sonycamremote.engine.model.SettingValue
import com.sonycamremote.engine.model.StorageInfo
import com.sonycamremote.engine.model.UnsupportedFeatureException
import com.sonycamremote.engine.model.ZoomDirection
import com.sonycamremote.engine.model.ZoomInfo
import com.sonycamremote.engine.model.ZoomMovement
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
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Adapter for Sony's legacy Camera Remote API (Wi-Fi, JSON-RPC over HTTP).
 * Capabilities come exclusively from getAvailableApiList / getEvent.
 */
class LegacySonyApiAdapter(
    private val endpoint: CameraEndpoint.LegacyHttp,
    http: OkHttpClient,
    private val log: DiagnosticsLog,
    private val scope: CoroutineScope,
) : CameraProtocol {

    override val kind = ProtocolKind.LEGACY_REMOTE_API

    private val http = http.newBuilder().readTimeout(10, TimeUnit.SECONDS).build()
    private val pollHttp = http.newBuilder().readTimeout(70, TimeUnit.SECONDS).build()
    private val streamHttp = http.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()

    private val _state = MutableStateFlow(CameraState())
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    private val queue = CameraCommandQueue(log, "LegacyAPI")
    private lateinit var api: LegacyApiClient
    @Volatile private var availableApis: Set<String> = emptySet()
    private var eventJob: Job? = null
    private var eventVersion = "1.2"
    @Volatile private var contentsMode = false
    private var whiteBalanceModes: List<Pair<String, IntArray?>> = emptyList()

    override suspend fun connect(): CameraInfo = withContext(Dispatchers.IO) {
        _state.update { it.copy(connection = ConnectionState(ConnectionStatus.CONNECTING)) }
        try {
            var description: DeviceDescription? = null
            val urls = when {
                endpoint.serviceUrls.isNotEmpty() -> endpoint.serviceUrls
                endpoint.deviceDescriptionUrl != null -> {
                    description = fetchDescription(endpoint.deviceDescriptionUrl)
                    description.serviceUrls.ifEmpty { DeviceDescriptionParser.defaultServiceUrls(endpoint.host) }
                }
                else -> DeviceDescriptionParser.defaultServiceUrls(endpoint.host)
            }
            api = LegacyApiClient(urls, http)
            log.i("LegacyAPI", "Services: ${urls.keys}")

            refreshApis()
            if ("startRecMode" in availableApis) {
                api.call("camera", "startRecMode")
                waitForApi("actTakePicture", "startLiveview", timeoutMs = 10_000)
            }

            val appInfo = runCatching { api.call("camera", "getApplicationInfo") }.getOrNull()
            val versions = runCatching { api.call("camera", "getVersions") }.getOrNull()
                ?.firstOrNull()?.let { it as? JsonArray }?.map { it.jsonPrimitive.content }
            versions?.let { if ("1.2" !in it) eventVersion = if ("1.1" in it) "1.1" else "1.0" }

            val info = CameraInfo(
                modelName = description?.modelName ?: appInfo?.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: "Sony camera",
                friendlyName = description?.friendlyName ?: description?.modelName ?: "Sony camera",
                firmwareVersion = null, // The legacy API does not report body firmware.
                protocol = kind,
                protocolVersion = appInfo?.getOrNull(1)?.jsonPrimitive?.contentOrNull?.let { "Remote app $it" },
            )
            if ("getAvailableWhiteBalance" in availableApis) loadWhiteBalanceModes()

            _state.update { it.copy(info = info, capabilities = deriveCapabilities()) }
            applyEvent(api.call("camera", "getEvent", listOf(p(false)), version = eventVersion))
            _state.update { it.copy(connection = ConnectionState(ConnectionStatus.CONNECTED)) }
            startEventLoop()
            info
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(connection = ConnectionState(ConnectionStatus.FAILED, e.message)) }
            throw if (e is CameraProtocolException || e is CameraConnectionException) e
            else CameraConnectionException("Could not connect: ${e.message}", e)
        }
    }

    override suspend fun disconnect() {
        eventJob?.cancel()
        eventJob = null
        withContext(NonCancellable + Dispatchers.IO) {
            if (::api.isInitialized && "stopRecMode" in availableApis) runCatching { api.call("camera", "stopRecMode") }
        }
        _state.update { it.copy(connection = ConnectionState(ConnectionStatus.DISCONNECTED)) }
    }

    override suspend fun refreshState() {
        val r = api.call("camera", "getEvent", listOf(p(false)), version = eventVersion)
        applyEvent(r)
        _state.update { it.copy(connection = it.connection.copy(lastLatencyMs = queue.lastLatencyMs)) }
    }

    // ---------------------------------------------------------------- commands

    override suspend fun execute(command: CameraCommand) {
        ensureShootingMode()
        queue.submit(command.name, timeoutMs = if (command is CameraCommand.TakePicture) 60_000 else 10_000) {
            when (command) {
                CameraCommand.TakePicture -> takePicture()
                CameraCommand.HalfPressDown -> call(Capability.HALF_PRESS_AF, "actHalfPressShutter")
                CameraCommand.HalfPressRelease -> call(Capability.HALF_PRESS_AF, "cancelHalfPressShutter")
                CameraCommand.StartRecording -> {
                    val mode = _state.value.properties[PropertyId.SHOOT_MODE]?.current?.token
                    if (mode != null && mode != "movie" && "setShootMode" in availableApis) {
                        api.call("camera", "setShootMode", listOf(p("movie")))
                        waitForApi("startMovieRec", timeoutMs = 5_000)
                    }
                    call(Capability.MOVIE_RECORDING, "startMovieRec")
                    _state.update { it.copy(recording = true, recordingStartedAtMs = System.currentTimeMillis()) }
                }
                CameraCommand.StopRecording -> {
                    call(Capability.MOVIE_RECORDING, "stopMovieRec")
                    _state.update { it.copy(recording = false, recordingStartedAtMs = null) }
                }
                is CameraCommand.Zoom -> call(
                    Capability.ZOOM_OPTICAL, "actZoom",
                    p(if (command.direction == ZoomDirection.IN) "in" else "out"),
                    p(
                        when (command.movement) {
                            ZoomMovement.START -> "start"
                            ZoomMovement.STOP -> "stop"
                            ZoomMovement.ONE_SHOT -> "1shot"
                        },
                    ),
                )
                is CameraCommand.TouchFocus -> call(
                    Capability.TOUCH_AF, "setTouchAFPosition",
                    p((command.x * 100.0).coerceIn(0.0, 100.0)), p((command.y * 100.0).coerceIn(0.0, 100.0)),
                )
                CameraCommand.CancelTouchFocus -> call(Capability.TOUCH_AF, "cancelTouchAFPosition")
                is CameraCommand.FocusStep -> throw UnsupportedFeatureException(
                    Capability.MANUAL_FOCUS_STEP, "The legacy Camera Remote API has no focus drive command",
                )
            }
        }
    }

    private suspend fun takePicture() {
        requireApi(Capability.STILL_CAPTURE, "actTakePicture")
        val result = try {
            api.call("camera", "actTakePicture")
        } catch (e: LegacyApiException) {
            if (e.errorCode == LegacyApiException.STILL_CAPTURING_NOT_FINISHED) {
                api.call("camera", "awaitTakePicture", client = pollHttp)
            } else throw e
        }
        val urls = (result.firstOrNull() as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        _state.update { it.copy(lastCapturedUrls = urls) }
    }

    override suspend fun setProperty(id: PropertyId, value: SettingValue) {
        ensureShootingMode()
        queue.submit("set $id=${value.token}") {
            when (id) {
                PropertyId.ISO -> call(Capability.ISO, "setIsoSpeedRate", p(value.token))
                PropertyId.SHUTTER_SPEED -> call(Capability.SHUTTER_SPEED, "setShutterSpeed", p(value.token))
                PropertyId.F_NUMBER -> call(Capability.APERTURE, "setFNumber", p(value.token))
                PropertyId.EXPOSURE_COMPENSATION ->
                    call(Capability.EXPOSURE_COMPENSATION, "setExposureCompensation", p(value.token.toInt()))
                PropertyId.EXPOSURE_MODE -> call(Capability.EXPOSURE_MODE, "setExposureMode", p(value.token))
                PropertyId.FOCUS_MODE -> call(Capability.FOCUS_MODE, "setFocusMode", p(value.token))
                PropertyId.SHOOT_MODE -> call(Capability.SHOOT_MODE_SWITCH, "setShootMode", p(value.token))
                PropertyId.SELF_TIMER -> call(Capability.SELF_TIMER, "setSelfTimer", p(value.token.toInt()))
                PropertyId.WHITE_BALANCE -> call(Capability.WHITE_BALANCE, "setWhiteBalance", p(value.token), p(false), p(0))
                PropertyId.COLOR_TEMPERATURE ->
                    call(Capability.COLOR_TEMPERATURE, "setWhiteBalance", p(COLOR_TEMP_MODE), p(true), p(value.token.toInt()))
                PropertyId.ASPECT_RATIO -> {
                    val size = _state.value.properties[PropertyId.IMAGE_QUALITY]?.current?.token ?: "L"
                    call(Capability.ASPECT_RATIO, "setStillSize", p(value.token), p(size))
                }
                PropertyId.FILE_FORMAT -> call(Capability.IMAGE_QUALITY, "setStillQuality", p(value.token))
                else -> throw UnsupportedFeatureException(null, "${id.label} via legacy API")
            }
        }
        runCatching { refreshState() }
    }

    // ---------------------------------------------------------------- live view

    override fun liveView(preferLargeSize: Boolean): Flow<LiveViewFrame> = flow {
        ensureShootingMode()
        requireApi(Capability.LIVE_VIEW, "startLiveview")
        val result = if (preferLargeSize && "startLiveviewWithSize" in availableApis) {
            api.call("camera", "startLiveviewWithSize", listOf(p("L")))
        } else {
            api.call("camera", "startLiveview")
        }
        val url = result.firstOrNull()?.jsonPrimitive?.contentOrNull
            ?: throw CameraProtocolException("startLiveview returned no URL")
        log.i("LegacyAPI", "Live View stream $url")
        val call = streamHttp.newCall(Request.Builder().url(url).get().build())
        val ctx = currentCoroutineContext()
        val handle = ctx.job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw CameraProtocolException("Live View HTTP ${resp.code}", resp.code)
                val parser = LiveViewStreamParser(resp.body!!.byteStream())
                while (true) {
                    ctx.ensureActive()
                    val frame = parser.nextFrame() ?: break
                    emit(frame)
                }
            }
        } finally {
            handle.dispose()
            withContext(NonCancellable) {
                if ("stopLiveview" in availableApis) runCatching { api.call("camera", "stopLiveview") }
            }
        }
    }.flowOn(Dispatchers.IO)

    // ---------------------------------------------------------------- media

    override suspend fun listMedia(offset: Int, count: Int): List<MediaItem> = queue.submit("listMedia", 30_000) {
        if (!api.hasService("avContent")) throw UnsupportedFeatureException(Capability.MEDIA_BROWSE, "no avContent service")
        enterContentsMode()
        val sources = api.call("avContent", "getSourceList", listOf(buildJsonObject { put("scheme", "storage") }))
        val source = (sources.firstOrNull() as? JsonArray)?.firstOrNull()?.jsonObject?.get("source")
            ?.jsonPrimitive?.contentOrNull ?: "storage:memoryCard1"
        val list = api.call(
            "avContent", "getContentList",
            listOf(
                buildJsonObject {
                    put("uri", source)
                    put("stIdx", offset)
                    put("cnt", count.coerceAtMost(100))
                    put("view", "flat")
                    put("sort", "descending")
                },
            ),
            version = "1.3",
        )
        (list.firstOrNull() as? JsonArray).orEmpty().mapNotNull { el -> parseContent(el.jsonObject) }
    }

    override suspend fun download(item: MediaItem, sink: OutputStream, onProgress: (Long, Long?) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            val url = item.originalUrl ?: item.previewUrl
                ?: throw UnsupportedFeatureException(Capability.MEDIA_TRANSFER, "no download URL for ${item.fileName}")
            val call = streamHttp.newCall(Request.Builder().url(url).get().build())
            val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw CameraProtocolException("Download HTTP ${resp.code}", resp.code)
                    val body = resp.body!!
                    val total = body.contentLength().takeIf { it > 0 }
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            written += n
                            onProgress(written, total)
                        }
                    }
                }
            } finally {
                handle.dispose()
            }
        }

    override suspend fun thumbnail(item: MediaItem): ByteArray? = withContext(Dispatchers.IO) {
        val url = item.thumbnailUrl ?: return@withContext null
        runCatching {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { it.body?.bytes() }
        }.getOrNull()
    }

    private fun parseContent(o: JsonObject): MediaItem? {
        val content = o["content"]?.jsonObject ?: return null
        val original = content["original"]?.let { it as? JsonArray }?.firstOrNull()?.jsonObject
        val kindStr = o.str("contentKind") ?: ""
        return MediaItem(
            id = o.str("uri") ?: return null,
            fileName = original?.str("fileName") ?: o.str("title") ?: "file",
            kind = when {
                kindStr.startsWith("still") -> MediaKind.STILL
                kindStr.startsWith("movie") -> MediaKind.MOVIE
                else -> MediaKind.OTHER
            },
            createdAt = o.str("createdTime"),
            sizeBytes = null,
            thumbnailUrl = content.str("thumbnailUrl"),
            previewUrl = content.str("largeUrl") ?: content.str("smallUrl"),
            originalUrl = original?.str("url"),
        )
    }

    private suspend fun enterContentsMode() {
        if (contentsMode) return
        if ("setCameraFunction" in availableApis) {
            api.call("camera", "setCameraFunction", listOf(p("Contents Transfer")))
            // Wait until avContent is ready.
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                if (runCatching { api.call("avContent", "getSchemeList") }.isSuccess) break
                delay(300)
            }
        }
        contentsMode = true
    }

    /** Media browsing puts some cameras in "Contents Transfer"; switch back before any shooting call. */
    private suspend fun ensureShootingMode() {
        if (!contentsMode) return
        if ("setCameraFunction" in availableApis) {
            api.call("camera", "setCameraFunction", listOf(p("Remote Shooting")))
            waitForApi("actTakePicture", "startLiveview", timeoutMs = 10_000)
        }
        contentsMode = false
    }

    // ---------------------------------------------------------------- events

    private fun startEventLoop() {
        eventJob?.cancel()
        eventJob = scope.launch(Dispatchers.IO) {
            var failures = 0
            while (isActive) {
                try {
                    val r = api.call("camera", "getEvent", listOf(p(true)), version = eventVersion, client = pollHttp)
                    applyEvent(r)
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LegacyApiException) {
                    if (e.errorCode == LegacyApiException.NO_SUCH_METHOD && eventVersion != "1.0") {
                        eventVersion = "1.0"
                    } else {
                        delay(500)
                    }
                } catch (e: Exception) {
                    failures++
                    log.w("LegacyAPI", "getEvent failed ($failures): ${e.message}")
                    if (failures >= 3) {
                        _state.update { it.copy(connection = ConnectionState(ConnectionStatus.FAILED, "Camera stopped responding")) }
                        return@launch
                    }
                    delay(1000)
                }
            }
        }
    }

    private fun applyEvent(result: JsonArray) {
        val objects = result.flatMap { el ->
            when (el) {
                is JsonObject -> listOf(el)
                is JsonArray -> el.mapNotNull { it as? JsonObject }
                else -> emptyList()
            }
        }
        var s = _state.value
        val props = s.properties.toMutableMap()
        for (o in objects) {
            when (o.str("type")) {
                "availableApiList" -> {
                    availableApis = o["names"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: availableApis
                }
                "cameraStatus" -> {
                    val status = o.str("cameraStatus")
                    val rec = status == "MovieRecording"
                    s = s.copy(
                        cameraStatus = status,
                        recording = rec,
                        recordingStartedAtMs = if (rec) s.recordingStartedAtMs ?: System.currentTimeMillis() else null,
                    )
                }
                "zoomInformation" -> s = s.copy(
                    zoom = ZoomInfo(o.int("zoomPosition"), o.int("zoomNumberBox"), o.int("zoomIndexCurrentBox")),
                )
                "storageInformation" -> if (o.bool("recordTarget") != false) s = s.copy(
                    storage = StorageInfo(
                        present = o.str("storageID") != "No Media",
                        description = o.str("storageDescription"),
                        remainingShots = o.int("numberOfRecordableImages")?.takeIf { it >= 0 },
                        remainingRecordSeconds = o.int("recordableTime")?.takeIf { it >= 0 }?.let { it * 60 },
                    ),
                )
                "batteryInfo" -> {
                    val num = o.int("levelNumer")
                    val den = o.int("levelDenom")
                    val pct = if (num != null && den != null && den > 0 && num >= 0) num * 100 / den else null
                    s = s.copy(battery = BatteryInfo(pct, o.str("additionalStatus") ?: o.str("status")))
                }
                "takePicture" -> s = s.copy(
                    lastCapturedUrls = o["takePictureUrl"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                )
                "isoSpeedRate" -> simple(props, PropertyId.ISO, o, "currentIsoSpeedRate", "isoSpeedRateCandidates", "setIsoSpeedRate")
                "shutterSpeed" -> simple(props, PropertyId.SHUTTER_SPEED, o, "currentShutterSpeed", "shutterSpeedCandidates", "setShutterSpeed")
                "fNumber" -> simple(props, PropertyId.F_NUMBER, o, "currentFNumber", "fNumberCandidates", "setFNumber") { "f/$it" }
                "exposureMode" -> simple(props, PropertyId.EXPOSURE_MODE, o, "currentExposureMode", "exposureModeCandidates", "setExposureMode")
                "focusMode" -> simple(props, PropertyId.FOCUS_MODE, o, "currentFocusMode", "focusModeCandidates", "setFocusMode")
                "shootMode" -> simple(props, PropertyId.SHOOT_MODE, o, "currentShootMode", "shootModeCandidates", "setShootMode")
                "selfTimer" -> simple(props, PropertyId.SELF_TIMER, o, "currentSelfTimer", "selfTimerCandidates", "setSelfTimer") { "${it}s" }
                "stillQuality" -> simple(props, PropertyId.FILE_FORMAT, o, "stillQuality", "candidate", "setStillQuality")
                "exposureCompensation" -> exposureCompensation(props, o)
                "whiteBalance" -> whiteBalance(props, o)
            }
        }
        s = s.copy(properties = props)
        _state.value = s.copy(capabilities = deriveCapabilities(s))
    }

    private fun simple(
        props: MutableMap<PropertyId, CameraProperty>, id: PropertyId, o: JsonObject,
        currentKey: String, candidatesKey: String, setter: String, label: (String) -> String = { it },
    ) {
        val current = o[currentKey]?.let { (it as? JsonPrimitive)?.contentOrNull }
        val options = o[candidatesKey]?.let { it as? JsonArray }?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.map { SettingValue(it, label(it)) }
            ?: props[id]?.options.orEmpty()
        props[id] = CameraProperty(id, current?.let { SettingValue(it, label(it)) }, options, setter in availableApis)
    }

    private fun exposureCompensation(props: MutableMap<PropertyId, CameraProperty>, o: JsonObject) {
        val cur = o.int("currentExposureCompensation") ?: return
        val min = o.int("minExposureCompensation") ?: cur
        val max = o.int("maxExposureCompensation") ?: cur
        val stepEv = when (o.int("stepIndexOfExposureCompensation")) { 2 -> 0.5; else -> 1.0 / 3.0 }
        fun label(i: Int): String {
            val ev = i * stepEv
            return (if (ev > 0) "+" else "") + String.format(java.util.Locale.US, "%.1f", ev)
        }
        props[PropertyId.EXPOSURE_COMPENSATION] = CameraProperty(
            PropertyId.EXPOSURE_COMPENSATION,
            SettingValue(cur.toString(), label(cur)),
            (min..max).map { SettingValue(it.toString(), label(it)) },
            "setExposureCompensation" in availableApis,
        )
    }

    private fun whiteBalance(props: MutableMap<PropertyId, CameraProperty>, o: JsonObject) {
        val mode = o.str("currentWhiteBalanceMode") ?: return
        val writable = "setWhiteBalance" in availableApis
        props[PropertyId.WHITE_BALANCE] = CameraProperty(
            PropertyId.WHITE_BALANCE, SettingValue(mode, mode),
            whiteBalanceModes.map { SettingValue(it.first, it.first) }, writable,
        )
        val range = whiteBalanceModes.firstOrNull { it.first == COLOR_TEMP_MODE }?.second
        if (range != null && range.size == 3 && range[2] > 0) {
            val (maxK, minK, step) = Triple(range[0], range[1], range[2])
            val kelvin = o.int("currentColorTemperature")
            props[PropertyId.COLOR_TEMPERATURE] = CameraProperty(
                PropertyId.COLOR_TEMPERATURE,
                kelvin?.takeIf { mode == COLOR_TEMP_MODE && it > 0 }?.let { SettingValue(it.toString(), "${it}K") },
                (minK..maxK step step).map { SettingValue(it.toString(), "${it}K") },
                writable,
            )
        }
    }

    private suspend fun loadWhiteBalanceModes() {
        runCatching {
            val r = api.call("camera", "getAvailableWhiteBalance")
            whiteBalanceModes = (r.getOrNull(1) as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el.jsonObject
                val name = o.str("whiteBalanceMode") ?: return@mapNotNull null
                val range = o["colorTemperatureRange"]?.let { it as? JsonArray }
                    ?.mapNotNull { it.jsonPrimitive.intOrNull }?.toIntArray()
                name to range?.takeIf { it.isNotEmpty() }
            }
        }.onFailure { log.w("LegacyAPI", "getAvailableWhiteBalance: ${it.message}") }
    }

    private fun deriveCapabilities(s: CameraState = _state.value): Set<Capability> {
        val a = availableApis
        return buildSet {
            if ("startLiveview" in a) add(Capability.LIVE_VIEW)
            if ("startLiveviewWithSize" in a) add(Capability.LIVE_VIEW_QUALITY)
            if ("actTakePicture" in a) add(Capability.STILL_CAPTURE)
            if ("actHalfPressShutter" in a) add(Capability.HALF_PRESS_AF)
            if ("startMovieRec" in a || "stopMovieRec" in a ||
                s.properties[PropertyId.SHOOT_MODE]?.options?.any { it.token == "movie" } == true
            ) add(Capability.MOVIE_RECORDING)
            if ("setIsoSpeedRate" in a) add(Capability.ISO)
            if ("setShutterSpeed" in a) add(Capability.SHUTTER_SPEED)
            if ("setFNumber" in a) add(Capability.APERTURE)
            if ("setExposureCompensation" in a) add(Capability.EXPOSURE_COMPENSATION)
            if ("setWhiteBalance" in a) add(Capability.WHITE_BALANCE)
            if (s.properties.containsKey(PropertyId.COLOR_TEMPERATURE)) add(Capability.COLOR_TEMPERATURE)
            if ("setExposureMode" in a) add(Capability.EXPOSURE_MODE)
            if ("setFocusMode" in a) add(Capability.FOCUS_MODE)
            if ("setTouchAFPosition" in a) add(Capability.TOUCH_AF)
            if ("actZoom" in a) add(Capability.ZOOM_OPTICAL)
            if ("setSelfTimer" in a) add(Capability.SELF_TIMER)
            if ("setShootMode" in a) add(Capability.SHOOT_MODE_SWITCH)
            if ("setStillQuality" in a) add(Capability.IMAGE_QUALITY)
            if ("setStillSize" in a) add(Capability.ASPECT_RATIO)
            if (s.battery != null) add(Capability.BATTERY_STATUS)
            if (s.storage != null) add(Capability.STORAGE_STATUS)
            if (s.cameraStatus != null) add(Capability.RECORDING_STATUS)
            if (::api.isInitialized && api.hasService("avContent")) {
                add(Capability.MEDIA_BROWSE)
                add(Capability.MEDIA_TRANSFER)
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun call(cap: Capability, method: String, vararg params: JsonElement): JsonArray {
        requireApi(cap, method)
        return api.call("camera", method, params.toList())
    }

    private fun requireApi(cap: Capability, method: String) {
        if (method !in availableApis) throw UnsupportedFeatureException(cap, "$method is not available on this camera right now")
    }

    private suspend fun refreshApis() {
        val r = api.call("camera", "getAvailableApiList")
        availableApis = (r.firstOrNull() as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
        log.d("LegacyAPI", "APIs: ${availableApis.sorted()}")
    }

    private suspend fun waitForApi(vararg any: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            refreshApis()
            if (any.any { it in availableApis }) return
            delay(300)
        }
        log.w("LegacyAPI", "Timed out waiting for ${any.joinToString()}")
    }

    private suspend fun fetchDescription(url: String): DeviceDescription = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw CameraConnectionException("Device description HTTP ${resp.code}")
            DeviceDescriptionParser.parse(resp.body!!.string())
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    companion object {
        const val COLOR_TEMP_MODE = "Color Temperature"
    }
}
