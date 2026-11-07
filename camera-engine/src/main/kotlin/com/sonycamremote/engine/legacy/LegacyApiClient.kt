package com.sonycamremote.engine.legacy

import com.sonycamremote.engine.model.CameraConnectionException
import com.sonycamremote.engine.model.CameraProtocolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Error returned by the camera: {"error":[code,"message"]}. Codes per Sony Camera Remote API reference. */
class LegacyApiException(val errorCode: Int, val errorMessage: String, method: String) :
    CameraProtocolException("$method failed: $errorCode $errorMessage", errorCode) {
    companion object {
        const val ANY = 1
        const val NO_SUCH_METHOD = 12
        const val ILLEGAL_ARGUMENT = 3
        const val NOT_AVAILABLE_NOW = 1
        const val SHOOTING_FAIL = 40400
        const val CAMERA_NOT_READY = 40401
        const val ALREADY_RUNNING_POLLING = 40402
        const val STILL_CAPTURING_NOT_FINISHED = 40403
    }
}

/** Minimal JSON-RPC 1.0-style client for Sony's legacy ScalarWebAPI. */
class LegacyApiClient(
    private val serviceUrls: Map<String, String>,
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val ids = AtomicInteger(1)

    fun hasService(service: String) = serviceUrls.containsKey(service)

    /**
     * Calls [method] on [service] and returns the "result" array.
     * @throws LegacyApiException when the camera returns an "error" member.
     */
    suspend fun call(
        service: String,
        method: String,
        params: List<JsonElement> = emptyList(),
        version: String = "1.0",
        client: OkHttpClient = http,
    ): JsonArray = withContext(Dispatchers.IO) {
        val url = serviceUrls[service]
            ?: throw CameraProtocolException("Camera does not expose the '$service' service")
        val body = buildJsonObject {
            put("method", method)
            put("params", JsonArray(params))
            put("id", ids.getAndIncrement())
            put("version", version)
        }.toString()
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        val text = try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw CameraProtocolException("$method: HTTP ${resp.code}", resp.code)
                resp.body?.string() ?: throw CameraProtocolException("$method: empty body")
            }
        } catch (e: IOException) {
            throw CameraConnectionException("$method: ${e.message}", e)
        }
        val root = json.parseToJsonElement(text) as? JsonObject
            ?: throw CameraProtocolException("$method: response is not a JSON object")
        root["error"]?.let { err ->
            if (err !is JsonNull) {
                val arr = err.jsonArray
                throw LegacyApiException(arr[0].jsonPrimitive.int, arr.getOrNull(1)?.jsonPrimitive?.content ?: "", method)
            }
        }
        (root["result"] ?: root["results"])?.let { it as? JsonArray } ?: JsonArray(emptyList())
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun p(value: String): JsonElement = JsonPrimitive(value)
        fun p(value: Int): JsonElement = JsonPrimitive(value)
        fun p(value: Double): JsonElement = JsonPrimitive(value)
        fun p(value: Boolean): JsonElement = JsonPrimitive(value)
    }
}
