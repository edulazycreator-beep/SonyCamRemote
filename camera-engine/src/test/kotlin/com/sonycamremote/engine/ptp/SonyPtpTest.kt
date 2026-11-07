package com.sonycamremote.engine.ptp

import com.sonycamremote.engine.diagnostics.DiagnosticsLog
import com.sonycamremote.engine.model.CameraCommand
import com.sonycamremote.engine.model.Capability
import com.sonycamremote.engine.model.PropertyId
import com.sonycamremote.engine.model.ProtocolKind
import com.sonycamremote.engine.model.UnsupportedFeatureException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class PtpCodecTest {
    @Test
    fun `ptp string round trip`() {
        val bytes = PtpWriter().string("ILCE-7M4").string("").toByteArray()
        val r = PtpReader(bytes)
        assertEquals("ILCE-7M4", r.string())
        assertEquals("", r.string())
    }

    @Test
    fun `sony value labels`() {
        assertEquals("1/125", SonyPtp.shutterLabel((1L shl 16) or 125))
        assertEquals("30\"", SonyPtp.shutterLabel((30L shl 16) or 1))
        assertEquals("0.8\"", SonyPtp.shutterLabel((8L shl 16) or 10))
        assertEquals("BULB", SonyPtp.shutterLabel(0))
        assertEquals("AUTO", SonyPtp.isoLabel(0x00FFFFFF))
        assertEquals("ISO 800", SonyPtp.isoLabel(800))
        assertEquals("f/2.8", SonyPtp.label(SonyPtp.DPC_F_NUMBER, 280))
        assertEquals("f/8", SonyPtp.label(SonyPtp.DPC_F_NUMBER, 800))
        assertEquals("-0.7", SonyPtp.label(SonyPtp.DPC_EXPOSURE_BIAS, (-700).toLong() and 0xFFFF))
        assertEquals("0x1234", SonyPtp.label(0x5005, 0x1234)) // unknown WB code is shown raw, not guessed
    }

    @Test
    fun `parses sony property descriptors`() {
        val data = PtpWriter().u64(2)
            // ISO: uint32, writable, enabled, enum of 3
            .u16(SonyPtp.DPC_ISO).u16(Ptp.DT_UINT32).u8(1).u8(1).u32(0x00FFFFFF).u32(400).u8(Ptp.FORM_ENUM)
            .u16(3).u32(0x00FFFFFF).u32(400).u32(800)
            // Battery: int8, read-only, range 0..100
            .u16(SonyPtp.DPC_BATTERY_LEVEL).u16(Ptp.DT_INT8).u8(0).u8(1).u8(0).u8(80).u8(Ptp.FORM_RANGE).u8(0).u8(100).u8(1)
            .toByteArray()
        val props = SonyPropParser.parseAll(data)
        assertEquals(2, props.size)
        assertEquals(listOf(0x00FFFFFFL, 400L, 800L), props[SonyPtp.DPC_ISO]!!.enumValues)
        assertEquals(400L, props[SonyPtp.DPC_ISO]!!.current.asLong())
        assertFalse(props[SonyPtp.DPC_BATTERY_LEVEL]!!.writable)
        assertEquals(80L, props[SonyPtp.DPC_BATTERY_LEVEL]!!.current.asLong())
    }

    @Test
    fun `unknown form flag keeps earlier properties`() {
        val data = PtpWriter().u64(2)
            .u16(SonyPtp.DPC_ISO).u16(Ptp.DT_UINT16).u8(1).u8(1).u16(1).u16(2).u8(Ptp.FORM_NONE)
            .u16(0xD2FF).u16(Ptp.DT_UINT16).u8(1).u8(1).u16(1).u16(2).u8(0x7F)
            .toByteArray()
        val warnings = mutableListOf<String>()
        val props = SonyPropParser.parseAll(data) { warnings += it }
        assertEquals(setOf(SonyPtp.DPC_ISO), props.keys)
        assertEquals(1, warnings.size)
    }

    @Test
    fun `extracts jpeg from live view object`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 5, 6, 0xFF.toByte(), 0xD9.toByte())
        val obj = ByteArray(16) + jpeg + ByteArray(8)
        assertArrayEquals(jpeg, SonyPtpAdapter.extractJpeg(obj))
    }
}

/** Scripted PTP device used to verify the adapter's handshake and command sequences. */
private class FakeSonyCamera(private val controls: Set<Int>) : PtpTransport {
    val ops = Collections.synchronizedList(mutableListOf<Pair<Int, IntArray>>())
    val dataSent = Collections.synchronizedList(mutableListOf<ByteArray>())
    override val description = "fake"
    override suspend fun open() {}
    override suspend fun close() {}

    override suspend fun transact(request: PtpRequest, timeoutMs: Int): PtpResult {
        ops += request.opCode to request.params
        request.dataOut?.let { dataSent += it }
        val data = when (request.opCode) {
            Ptp.OC_GET_DEVICE_INFO -> deviceInfo()
            SonyPtp.OC_SDIO_GET_EXT_DEVICE_INFO -> PtpWriter().u16(0x12C)
                .u32(2).u16(SonyPtp.DPC_ISO).u16(SonyPtp.DPC_BATTERY_LEVEL)
                .u32(controls.size.toLong()).apply { controls.forEach { u16(it) } }.toByteArray()
            SonyPtp.OC_GET_ALL_EXT_DEVICE_PROP_INFO -> PtpWriter().u64(1)
                .u16(SonyPtp.DPC_ISO).u16(Ptp.DT_UINT32).u8(1).u8(1).u32(400).u32(400).u8(Ptp.FORM_ENUM)
                .u16(2).u32(400).u32(800).toByteArray()
            else -> null
        }
        return PtpResult(Ptp.RC_OK, IntArray(0), data)
    }

    private fun deviceInfo(): ByteArray {
        val w = PtpWriter().u16(100).u32(0x11).u16(100).string("Sony PTP Extensions").u16(0)
        val ops = listOf(Ptp.OC_GET_DEVICE_INFO, Ptp.OC_OPEN_SESSION, Ptp.OC_GET_OBJECT, SonyPtp.OC_SDIO_CONNECT)
        w.u32(ops.size.toLong()); ops.forEach { w.u16(it) }
        repeat(4) { w.u32(0) } // events, props, capture formats, image formats
        return w.string("Sony Corporation").string("ILCE-7M4").string("3.01").string("0123").toByteArray()
    }
}

class SonyPtpAdapterTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `handshake reads device info and capabilities`() = runBlocking {
        val cam = FakeSonyCamera(setOf(SonyPtp.CTRL_CAPTURE, SonyPtp.CTRL_AUTO_FOCUS))
        val a = SonyPtpAdapter(cam, ProtocolKind.SONY_PTP_USB, DiagnosticsLog(), scope, pollIntervalMs = 60_000)
        val info = a.connect()
        assertEquals("ILCE-7M4", info.modelName)
        assertEquals("3.01", info.firmwareVersion)
        val sdio = cam.ops.filter { it.first == SonyPtp.OC_SDIO_CONNECT }.map { it.second[0] }
        assertEquals(listOf(1, 2, 3), sdio)
        val caps = a.state.value.capabilities
        assertTrue(Capability.STILL_CAPTURE in caps)
        assertTrue(Capability.ISO in caps)
        assertFalse("movie control not advertised", Capability.MOVIE_RECORDING in caps)
        assertEquals("ISO 400", a.state.value.properties[PropertyId.ISO]!!.current!!.label)
        a.disconnect()
    }

    @Test
    fun `capture presses and releases in order`() = runBlocking {
        val cam = FakeSonyCamera(setOf(SonyPtp.CTRL_CAPTURE, SonyPtp.CTRL_AUTO_FOCUS))
        val a = SonyPtpAdapter(cam, ProtocolKind.SONY_PTP_USB, DiagnosticsLog(), scope, pollIntervalMs = 60_000)
        a.connect()
        cam.ops.clear(); cam.dataSent.clear()
        a.execute(CameraCommand.TakePicture)
        val seq = cam.ops.map { it.second[0] }
        assertEquals(listOf(SonyPtp.CTRL_AUTO_FOCUS, SonyPtp.CTRL_CAPTURE, SonyPtp.CTRL_CAPTURE, SonyPtp.CTRL_AUTO_FOCUS), seq)
        assertEquals(listOf(2, 2, 1, 1), cam.dataSent.map { it[0].toInt() })
        a.disconnect()
    }

    @Test
    fun `unadvertised features throw and send nothing`() = runBlocking {
        val cam = FakeSonyCamera(setOf(SonyPtp.CTRL_CAPTURE))
        val a = SonyPtpAdapter(cam, ProtocolKind.SONY_PTP_IP, DiagnosticsLog(), scope, pollIntervalMs = 60_000)
        a.connect()
        cam.ops.clear()
        for (cmd in listOf(CameraCommand.StartRecording, CameraCommand.FocusStep(2), CameraCommand.TouchFocus(0.5f, 0.5f))) {
            try {
                a.execute(cmd)
                error("expected UnsupportedFeatureException for $cmd")
            } catch (_: UnsupportedFeatureException) {
            }
        }
        assertTrue(cam.ops.isEmpty())
        a.disconnect()
    }
}
