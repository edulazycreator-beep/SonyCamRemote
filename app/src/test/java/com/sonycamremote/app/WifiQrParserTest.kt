package com.sonycamremote.app

import com.sonycamremote.app.data.camera.wifi.WifiCameraConnector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiQrParserTest {
    @Test
    fun `parses sony camera qr`() {
        val c = WifiCameraConnector.parseQr("W01:S:aBcD;P:12345678;C:ILCE-7M4;M:AABBCCDDEEFF;")!!
        assertEquals("DIRECT-aBcD:ILCE-7M4", c.ssid)
        assertEquals("12345678", c.password)
        assertEquals("ILCE-7M4", c.model)
    }

    @Test
    fun `keeps full ssid when present`() {
        assertEquals("DIRECT-xY12:ZV-E10", WifiCameraConnector.parseQr("W01:S:DIRECT-xY12:ZV-E10;P:pw;C:ZV-E10;")!!.ssid)
    }

    @Test
    fun `parses standard wifi qr and rejects others`() {
        val c = WifiCameraConnector.parseQr("WIFI:T:WPA;S:CamNet;P:secret;;")!!
        assertEquals("CamNet", c.ssid)
        assertEquals("secret", c.password)
        assertNull(WifiCameraConnector.parseQr("https://example.com"))
    }
}
