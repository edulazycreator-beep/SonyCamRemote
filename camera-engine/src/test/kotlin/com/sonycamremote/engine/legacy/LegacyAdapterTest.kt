package com.sonycamremote.engine.legacy

import com.sonycamremote.engine.diagnostics.DiagnosticsLog
import com.sonycamremote.engine.model.CameraCommand
import com.sonycamremote.engine.model.CameraEndpoint
import com.sonycamremote.engine.model.Capability
import com.sonycamremote.engine.model.ConnectionStatus
import com.sonycamremote.engine.model.PropertyId
import com.sonycamremote.engine.model.SettingValue
import com.sonycamremote.engine.model.UnsupportedFeatureException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * Integration test of the legacy adapter against a scripted JSON-RPC server (MockWebServer).
 * This verifies protocol handling only; it is NOT evidence of compatibility with any real camera.
 */
class LegacyAdapterTest {

    private lateinit var server: MockWebServer
    private val calls = Collections.synchronizedList(mutableListOf<String>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var apis = listOf("getAvailableApiList", "getEvent", "startRecMode", "getApplicationInfo", "getVersions")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val method = body["method"]!!.jsonPrimitive.content
                val id = body["id"]!!.jsonPrimitive.int
                calls += method
                val result = when (method) {
                    "getAvailableApiList" -> "[[${apis.joinToString { "\"$it\"" }}]]"
                    "startRecMode" -> {
                        apis = apis + listOf("actTakePicture", "startLiveview", "setIsoSpeedRate", "actZoom")
                        "[0]"
                    }
                    "getApplicationInfo" -> "[\"Smart Remote Control\",\"2.1.4\"]"
                    "getVersions" -> "[[\"1.0\",\"1.1\"]]"
                    "getEvent" -> EVENT.replace("APIS", apis.joinToString { "\"$it\"" })
                    "setIsoSpeedRate" -> "[0]"
                    "actTakePicture" -> "[[\"http://192.168.122.1:8080/postview/1.jpg\"]]"
                    else -> return MockResponse().setBody("{\"error\":[12,\"No Such Method\"],\"id\":$id}")
                }
                return MockResponse().setBody("{\"result\":$result,\"id\":$id}")
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun adapter() = LegacySonyApiAdapter(
        CameraEndpoint.LegacyHttp(
            host = server.hostName, deviceDescriptionUrl = null,
            serviceUrls = mapOf("camera" to server.url("/sony/camera").toString()),
        ),
        OkHttpClient(), DiagnosticsLog(), scope,
    )

    @Test
    fun `connect enters rec mode and derives capabilities from api list`() = runBlocking {
        val a = adapter()
        a.connect()
        val st = a.state.value
        assertEquals(ConnectionStatus.CONNECTED, st.connection.status)
        assertTrue(Capability.STILL_CAPTURE in st.capabilities)
        assertTrue(Capability.LIVE_VIEW in st.capabilities)
        assertTrue(Capability.ISO in st.capabilities)
        assertFalse("no setShutterSpeed advertised", Capability.SHUTTER_SPEED in st.capabilities)
        assertTrue("startRecMode" in calls)
        assertEquals("400", st.properties[PropertyId.ISO]!!.current!!.token)
        assertEquals(3, st.properties[PropertyId.ISO]!!.options.size)
        assertEquals(66, st.battery!!.percent)
        a.disconnect()
    }

    @Test
    fun `unsupported commands are rejected without calling the camera`() = runBlocking {
        val a = adapter()
        a.connect()
        val before = calls.size
        try {
            a.setProperty(PropertyId.SHUTTER_SPEED, SettingValue("1/125", "1/125"))
            error("expected UnsupportedFeatureException")
        } catch (_: UnsupportedFeatureException) {
        }
        try {
            a.execute(CameraCommand.FocusStep(3))
            error("expected UnsupportedFeatureException")
        } catch (_: UnsupportedFeatureException) {
        }
        assertFalse(calls.drop(before).contains("setShutterSpeed"))
        a.disconnect()
    }

    @Test
    fun `take picture records postview url`() = runBlocking {
        val a = adapter()
        a.connect()
        a.execute(CameraCommand.TakePicture)
        assertEquals(listOf("http://192.168.122.1:8080/postview/1.jpg"), a.state.value.lastCapturedUrls)
        a.disconnect()
    }

    private companion object {
        const val EVENT = """[
            {"type":"availableApiList","names":[APIS]},
            {"type":"cameraStatus","cameraStatus":"IDLE"},
            null,
            [{"type":"batteryInfo","batteryID":"1","status":"active","additionalStatus":"","levelNumer":2,"levelDenom":3,"description":""}],
            {"type":"isoSpeedRate","currentIsoSpeedRate":"400","isoSpeedRateCandidates":["AUTO","400","800"]},
            {"type":"shutterSpeed","currentShutterSpeed":"1/125","shutterSpeedCandidates":["1/60","1/125"]}
        ]"""
    }
}
