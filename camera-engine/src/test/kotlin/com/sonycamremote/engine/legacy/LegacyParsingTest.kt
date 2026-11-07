package com.sonycamremote.engine.legacy

import com.sonycamremote.engine.discovery.SsdpDiscovery
import com.sonycamremote.engine.model.CameraProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LegacyParsingTest {

    private fun packet(type: Int, seq: Int, payload: ByteArray, padding: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), type.toByte(), (seq shr 8).toByte(), seq.toByte(), 0, 0, 0, 1))
        val header = ByteArray(128)
        header[0] = 0x24; header[1] = 0x35; header[2] = 0x68; header[3] = 0x79
        header[4] = (payload.size shr 16).toByte(); header[5] = (payload.size shr 8).toByte(); header[6] = payload.size.toByte()
        header[7] = padding.toByte()
        out.write(header)
        out.write(payload)
        out.write(ByteArray(padding))
        return out.toByteArray()
    }

    @Test
    fun `parses jpeg frames and skips frame-info payloads`() {
        val jpeg1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val jpeg2 = ByteArray(5000) { it.toByte() }
        val stream = packet(1, 1, jpeg1, padding = 3) + packet(2, 2, ByteArray(40)) + packet(1, 3, jpeg2)
        val parser = LiveViewStreamParser(ByteArrayInputStream(stream))

        val f1 = parser.nextFrame()!!
        assertArrayEquals(jpeg1, f1.jpeg)
        assertEquals(1, f1.sequence)
        val f2 = parser.nextFrame()!!
        assertArrayEquals(jpeg2, f2.jpeg)
        assertEquals(3, f2.sequence)
        assertNull(parser.nextFrame())
    }

    @Test(expected = CameraProtocolException::class)
    fun `rejects bad start code`() {
        val bad = packet(1, 1, ByteArray(10)).also { it[8] = 0 }
        LiveViewStreamParser(ByteArrayInputStream(bad)).nextFrame()
    }

    @Test
    fun `resynchronises on garbage before a packet`() {
        val jpeg = byteArrayOf(9, 9, 9)
        val parser = LiveViewStreamParser(ByteArrayInputStream(byteArrayOf(1, 2, 3) + packet(1, 7, jpeg)))
        assertArrayEquals(jpeg, parser.nextFrame()!!.jpeg)
    }

    @Test
    fun `parses device description services`() {
        val xml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <friendlyName>ILCE-6300</friendlyName>
                <manufacturer>Sony Corporation</manufacturer>
                <modelName>SonyImagingDevice</modelName>
                <av:X_ScalarWebAPI_DeviceInfo xmlns:av="urn:schemas-sony-com:av">
                  <av:X_ScalarWebAPI_Version>1.0</av:X_ScalarWebAPI_Version>
                  <av:X_ScalarWebAPI_ServiceList>
                    <av:X_ScalarWebAPI_Service>
                      <av:X_ScalarWebAPI_ServiceType>camera</av:X_ScalarWebAPI_ServiceType>
                      <av:X_ScalarWebAPI_ActionList_URL>http://192.168.122.1:8080/sony</av:X_ScalarWebAPI_ActionList_URL>
                    </av:X_ScalarWebAPI_Service>
                    <av:X_ScalarWebAPI_Service>
                      <av:X_ScalarWebAPI_ServiceType>avContent</av:X_ScalarWebAPI_ServiceType>
                      <av:X_ScalarWebAPI_ActionList_URL>http://192.168.122.1:8080/sony/</av:X_ScalarWebAPI_ActionList_URL>
                    </av:X_ScalarWebAPI_Service>
                  </av:X_ScalarWebAPI_ServiceList>
                </av:X_ScalarWebAPI_DeviceInfo>
              </device>
            </root>
        """.trimIndent()
        val dd = DeviceDescriptionParser.parse(xml)
        assertEquals("ILCE-6300", dd.friendlyName)
        assertEquals("http://192.168.122.1:8080/sony/camera", dd.serviceUrls["camera"])
        assertEquals("http://192.168.122.1:8080/sony/avContent", dd.serviceUrls["avContent"])
    }

    @Test
    fun `parses ssdp response`() {
        val text = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://192.168.122.1:64321/DmsRmtDesc.xml\r\n" +
            "SERVER: UPnP/1.0 SonyImagingDevice/1.0\r\nST: urn:schemas-sony-com:service:ScalarWebAPI:1\r\n\r\n"
        val r = SsdpDiscovery.parseResponse(text, "192.168.122.1")!!
        assertEquals("http://192.168.122.1:64321/DmsRmtDesc.xml", r.location)
        assertEquals("urn:schemas-sony-com:service:ScalarWebAPI:1", r.st)
        assertNull(SsdpDiscovery.parseResponse("NOTIFY * HTTP/1.1\r\n", "x"))
    }
}
