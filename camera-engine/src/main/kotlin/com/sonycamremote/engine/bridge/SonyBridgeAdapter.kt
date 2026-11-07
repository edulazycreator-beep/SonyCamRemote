package com.sonycamremote.engine.bridge

import com.sonycamremote.engine.command.CameraCommandQueue
import com.sonycamremote.engine.diagnostics.DiagnosticsLog
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
import com.sonycamremote.engine.protocol.CameraProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.OutputStream

// ---- Bridge wire format (see docs/BRIDGE_PROTOCOL.md) ----
@Serializable data class BridgeValue(val token: String, val label: String)
@Serializable data class BridgeProperty(val current: BridgeValue? = null, val options: List<BridgeValue> = emptyList(), val writable: Boolean = false)
@Serializable data class BridgeInfo(val model: String, val firmware: String? = null, val serial: String? = null, val sdkVersion: String? = null)
@Serializable data class BridgeState(
    val capabilities: List<String> = emptyList(),
    val properties: Map<String, BridgeProperty> = emptyMap(),
    val recording: Boolean = false,
    val batteryPercent: Int? = null,
    val storagePresent: Boolean? = null,
    val remainingShots: Int? = null,
    val remainingRecordSeconds: Int? = null,
    val overheating: Boolean? = null,
)
@Serializable data class BridgeCommand(val name: String, val x: Float? = null, val y: Float? = null, val steps: Int? = null, val direction: String? = null, val movement: String? = null)
@Serializable data class BridgeSetProperty(val id: String, val token: String)
@Serializable data class BridgeMedia(val id: String, val fileName: String, val kind: String, val createdAt: String? = null, val size: Long? = null)

/**
 * Client for an optional bridge host (Linux/macOS/Windows/Raspberry Pi) that runs Sony's official
 * Camera Remote SDK and exposes it over HTTP. The SDK itself is never bundled in this app.
 */
class SonyBridgeAdapter(
    private val endpoint: CameraEndpoint.Bridge,
    http: OkHttpClient,
    private val log: DiagnosticsLog,
    private val scope: CoroutineScope,
    private val authToken: String? = null,
) : CameraProtocol {
    override val kind = ProtocolKind.SDK_BRIDGE
    private val http = http
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val queue = CameraCommandQueue(log, "Bridge")
    private val _state = MutableStateFlow(CameraState())
    override val state: StateFlow<CameraState> = _state.asStateFlow()
    private var pollJob: Job? = null
    private val base get() = endpoint.baseUrl.trimEnd('/') + "/api/v1/cameras/" + endpoint.cameraId

    override suspend fun connect(): CameraInfo {
        _state.update { it.copy(connection = ConnectionState(ConnectionStatus.CONNECTING, endpoint.baseUrl)) }
        try {
            post("/connect", "{}")
            val bi = json.decodeFromString<BridgeInfo>(get("/info"))
            val info = CameraInfo(
                modelName = bi.model, firmwareVersion = bi.firmware, serialNumber = bi.serial,
                protocol = kind, protocolVersion = bi.sdkVersion?.let { "CrSDK $it" },
            )
            _state.update { it.copy(info = info) }
            refreshState()
            _state.update { it.copy(connection = ConnectionState(ConnectionStatus.CONNECTED, endpoint.baseUrl)) }
            pollJob = scope.launch(Dispatchers.IO) {
                var failures = 0
                while (isActive) {
                    delay(500)
                    try { refreshState(); failures = 0 } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        if (++failures >= 3) {
                            _state.update { it.copy(connection = ConnectionState(ConnectionStatus.FAILED, e.message)) }
                            return@launch
                        }
                    }
                }
            }
            return info
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(connection = ConnectionState(ConnectionStatus.FAILED, e.message)) }
            throw e
        }
    }

    override suspend fun disconnect() {
        pollJob?.cancel()
        runCatching { post("/disconnect", "{}") }
        _state.update { it.copy(connection = ConnectionState(ConnectionStatus.DISCONNECTED)) }
    }

    override suspend fun refreshState() {
        val s = json.decodeFromString<BridgeState>(get("/state"))
        val caps = s.capabilities.mapNotNull { n -> Capability.entries.firstOrNull { it.name == n } }.toSet()
        val props = s.properties.mapNotNull { (k, v) ->
            val id = PropertyId.entries.firstOrNull { it.name == k } ?: return@mapNotNull null
            id to CameraProperty(
                id, v.current?.let { SettingValue(it.token, it.label) },
                v.options.map { SettingValue(it.token, it.label) }, v.writable,
            )
        }.toMap()
        _state.update {
            it.copy(
                capabilities = caps, properties = props, recording = s.recording,
                recordingStartedAtMs = if (s.recording) it.recordingStartedAtMs ?: System.currentTimeMillis() else null,
                battery = s.batteryPercent?.let { p -> BatteryInfo(p) },
                storage = s.storagePresent?.let { p -> StorageInfo(p, null, s.remainingShots, s.remainingRecordSeconds) },
                overheatingWarning = s.overheating,
                connection = it.connection.copy(lastLatencyMs = queue.lastLatencyMs),
            )
        }
    }

    override suspend fun execute(command: CameraCommand) {
        val dto = when (command) {
            is CameraCommand.TouchFocus -> BridgeCommand(command.name, x = command.x, y = command.y)
            is CameraCommand.FocusStep -> BridgeCommand(command.name, steps = command.steps)
            is CameraCommand.Zoom -> BridgeCommand(command.name, direction = command.direction.name, movement = command.movement.name)
            else -> BridgeCommand(command.name)
        }
        queue.submit(command.name, 30_000) { post("/command", json.encodeToString(dto)) }
    }

    override suspend fun setProperty(id: PropertyId, value: SettingValue) {
        queue.submit("set ${id.name}") { post("/property", json.encodeToString(BridgeSetProperty(id.name, value.token))) }
        runCatching { refreshState() }
    }

    override fun liveView(preferLargeSize: Boolean): Flow<LiveViewFrame> = flow {
        var seq = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val started = System.nanoTime()
            val bytes = getBytes("/liveview.jpg" + if (preferLargeSize) "?size=large" else "")
            if (bytes != null && bytes.isNotEmpty()) emit(LiveViewFrame(bytes, seq++, System.nanoTime()))
            val ms = (System.nanoTime() - started) / 1_000_000
            if (ms < 33) delay(33 - ms)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listMedia(offset: Int, count: Int): List<MediaItem> =
        json.decodeFromString<List<BridgeMedia>>(get("/media?offset=$offset&count=$count")).map {
            MediaItem(
                id = it.id, fileName = it.fileName,
                kind = when (it.kind) { "still" -> MediaKind.STILL; "movie" -> MediaKind.MOVIE; else -> MediaKind.OTHER },
                createdAt = it.createdAt, sizeBytes = it.size,
                thumbnailUrl = "$base/media/${it.id}/thumbnail", previewUrl = null,
                originalUrl = "$base/media/${it.id}/file",
            )
        }

    override suspend fun download(item: MediaItem, sink: OutputStream, onProgress: (Long, Long?) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            val url = item.originalUrl ?: throw CameraProtocolException("No URL")
            http.newCall(request(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) throw CameraProtocolException("Download HTTP ${resp.code}", resp.code)
                val body = resp.body!!
                val total = body.contentLength().takeIf { it > 0 }
                var written = 0L
                val buf = ByteArray(64 * 1024)
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
        }

    override suspend fun thumbnail(item: MediaItem): ByteArray? = item.thumbnailUrl?.let { url ->
        withContext(Dispatchers.IO) {
            runCatching { http.newCall(request(url).get().build()).execute().use { it.body?.bytes() } }.getOrNull()
        }
    }

    private fun request(url: String) = Request.Builder().url(url).apply {
        authToken?.let { header("Authorization", "Bearer $it") }
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(request(base + path).get().build()).execute().use { r ->
                if (!r.isSuccessful) throw CameraProtocolException("Bridge GET $path: HTTP ${r.code}", r.code)
                r.body?.string() ?: ""
            }
        } catch (e: java.io.IOException) {
            throw CameraConnectionException("Bridge unreachable: ${e.message}", e)
        }
    }

    private suspend fun getBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        http.newCall(request(base + path).get().build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    }

    private suspend fun post(path: String, body: String): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(request(base + path).post(body.toRequestBody(JSON)).build()).execute().use { r ->
                if (!r.isSuccessful) throw CameraProtocolException("Bridge POST $path: HTTP ${r.code} ${r.body?.string()}", r.code)
                r.body?.string() ?: ""
            }
        } catch (e: java.io.IOException) {
            throw CameraConnectionException("Bridge unreachable: ${e.message}", e)
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
