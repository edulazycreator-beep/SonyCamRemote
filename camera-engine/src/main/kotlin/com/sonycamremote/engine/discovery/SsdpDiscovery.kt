package com.sonycamremote.engine.discovery

import com.sonycamremote.engine.diagnostics.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

data class SsdpResponse(val location: String, val server: String?, val st: String?, val usn: String?, val fromHost: String)

/**
 * SSDP (UPnP) discovery used by legacy Sony cameras (ScalarWebAPI service).
 * On Android the caller must hold a WifiManager.MulticastLock while this runs.
 */
class SsdpDiscovery(private val log: DiagnosticsLog) {

    suspend fun search(
        searchTarget: String = SONY_SCALAR_WEB_API,
        timeoutMs: Int = 3000,
        bindAddress: InetAddress? = null,
    ): List<SsdpResponse> = withContext(Dispatchers.IO) {
        val message = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: 239.255.255.250:1900\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 1\r\n")
            append("ST: ").append(searchTarget).append("\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)

        val results = linkedMapOf<String, SsdpResponse>()
        val socket = if (bindAddress != null) DatagramSocket(InetSocketAddress(bindAddress, 0)) else DatagramSocket()
        socket.use { s ->
            s.soTimeout = 500
            val group = InetAddress.getByName("239.255.255.250")
            repeat(3) { s.send(DatagramPacket(message, message.size, group, 1900)) }
            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline && coroutineContext.isActive) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    s.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                parseResponse(text, packet.address.hostAddress ?: "")?.let { r ->
                    if (results.put(r.location, r) == null) log.i("SSDP", "Found ${r.location} (${r.server})")
                }
            }
        }
        results.values.toList()
    }

    companion object {
        const val SONY_SCALAR_WEB_API = "urn:schemas-sony-com:service:ScalarWebAPI:1"

        fun parseResponse(text: String, fromHost: String): SsdpResponse? {
            val lines = text.split("\r\n", "\n")
            if (lines.isEmpty() || !lines[0].startsWith("HTTP/1.1 200", ignoreCase = true)) return null
            val headers = lines.drop(1).mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim().uppercase() to line.substring(i + 1).trim()
            }.toMap()
            val location = headers["LOCATION"] ?: return null
            return SsdpResponse(location, headers["SERVER"], headers["ST"], headers["USN"], fromHost)
        }
    }
}

/** Probes a host for known Sony control ports (legacy HTTP 8080/10000, PTP-IP 15740). */
object PortProbe {
    const val PTP_IP_PORT = 15740
    val LEGACY_PORTS = listOf(8080, 10000)

    suspend fun isOpen(host: String, port: Int, timeoutMs: Int = 600): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            true
        } catch (_: Exception) {
            false
        }
    }
}
