package com.sonycamremote.app.data.camera.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import com.sonycamremote.engine.diagnostics.DiagnosticsLog
import com.sonycamremote.engine.model.CameraConnectionException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class WifiCredentials(val ssid: String, val password: String?, val model: String? = null)

/**
 * Joins a camera's own Wi-Fi access point (direct connection) and routes this app's sockets over it.
 * Uses WifiNetworkSpecifier on Android 10+; on Android 8–9 the user joins the network in system settings.
 */
@Singleton
class WifiCameraConnector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: DiagnosticsLog,
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var boundNetwork: Network? = null
        private set

    val canJoinProgrammatically: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    suspend fun join(credentials: WifiCredentials, timeoutMs: Int = 30_000): Network {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw CameraConnectionException("Join '${credentials.ssid}' in Android Wi-Fi settings, then return to the app.")
        }
        release()
        val specifier = WifiNetworkSpecifier.Builder().setSsid(credentials.ssid).apply {
            credentials.password?.takeIf { it.isNotEmpty() }?.let { setWpa2Passphrase(it) }
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        log.i("WiFi", "Requesting camera network ${credentials.ssid}")
        return suspendCancellableCoroutine { cont ->
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cm.bindProcessToNetwork(network)
                    boundNetwork = network
                    log.i("WiFi", "Joined ${credentials.ssid}; app traffic bound to camera network")
                    if (cont.isActive) cont.resume(network)
                }

                override fun onUnavailable() {
                    if (cont.isActive) cont.resumeWithException(CameraConnectionException("Could not join ${credentials.ssid}"))
                }

                override fun onLost(network: Network) {
                    if (network == boundNetwork) {
                        log.w("WiFi", "Camera network lost")
                        cm.bindProcessToNetwork(null)
                        boundNetwork = null
                    }
                }
            }
            callback = cb
            cm.requestNetwork(request, cb, timeoutMs)
            cont.invokeOnCancellation { release() }
        }
    }

    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        if (boundNetwork != null) cm.bindProcessToNetwork(null)
        boundNetwork = null
    }

    /** IPv4 gateway of the camera network (the camera itself when it is the access point). */
    fun gatewayAddress(): String? {
        val network = boundNetwork ?: cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(network) ?: return null
        return lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
            ?: lp.dhcpServerAddressCompat()
    }

    /** Local IPv4 address on the current Wi-Fi network (used to bind SSDP sockets). */
    fun localAddress(): java.net.InetAddress? {
        val network = boundNetwork ?: cm.activeNetwork ?: return null
        return cm.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }
    }

    inline fun <T> withMulticastLock(block: () -> T): T {
        val lock = multicastLock()
        lock.setReferenceCounted(false)
        lock.acquire()
        try {
            return block()
        } finally {
            lock.release()
        }
    }

    fun multicastLock(): WifiManager.MulticastLock = wifi.createMulticastLock("SonyCamRemote-SSDP")

    private fun android.net.LinkProperties.dhcpServerAddressCompat(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) dhcpServerAddress?.hostAddress else null

    companion object {
        /**
         * Parses the Wi-Fi QR code shown on the camera screen.
         *  - Sony format (community-documented): "W01:S:<ssid>;P:<password>;C:<model>;M:<mac>;"
         *  - Standard Wi-Fi QR: "WIFI:T:WPA;S:<ssid>;P:<password>;;"
         */
        fun parseQr(text: String): WifiCredentials? {
            val t = text.trim()
            val body = when {
                t.startsWith("W01:") -> t.removePrefix("W01:")
                t.startsWith("WIFI:", ignoreCase = true) -> t.substring(5)
                else -> return null
            }
            val fields = body.split(';').mapNotNull { part ->
                val i = part.indexOf(':')
                if (i <= 0) null else part.substring(0, i).uppercase() to part.substring(i + 1)
            }.toMap()
            var ssid = fields["S"] ?: return null
            val model = fields["C"]
            if (t.startsWith("W01:") && !ssid.startsWith("DIRECT-")) {
                ssid = "DIRECT-$ssid" + (model?.let { ":$it" } ?: "")
            }
            return WifiCredentials(ssid, fields["P"], model)
        }
    }
}
