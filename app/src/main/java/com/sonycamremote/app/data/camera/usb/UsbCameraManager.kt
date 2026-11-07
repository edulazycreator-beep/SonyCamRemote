package com.sonycamremote.app.data.camera.usb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class UsbCameraCandidate(val deviceName: String, val label: String, val vendorId: Int, val productId: Int, val hasPermission: Boolean)

@Singleton
class UsbCameraManager @Inject constructor(@ApplicationContext private val context: Context) {

    val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** Attached devices that expose a PTP (Still Image) interface. Sony devices are listed first. */
    fun candidates(): List<UsbCameraCandidate> = usbManager.deviceList.values
        .filter { UsbPtpTransport.findPtpInterface(it) != null }
        .sortedByDescending { it.vendorId == UsbPtpTransport.SONY_VENDOR_ID }
        .map {
            UsbCameraCandidate(
                deviceName = it.deviceName,
                label = listOfNotNull(it.manufacturerName, it.productName).joinToString(" ").ifBlank { it.deviceName },
                vendorId = it.vendorId,
                productId = it.productId,
                hasPermission = usbManager.hasPermission(it),
            )
        }

    fun find(deviceName: String): UsbDevice? = usbManager.deviceList[deviceName]

    /**
     * Shows the system USB permission dialog; resumes with the user's answer.
     * The PendingIntent must be mutable (the system adds EXTRA_PERMISSION_GRANTED / EXTRA_DEVICE) and is
     * restricted to this package via setPackage, which satisfies Android 14's implicit-intent rule.
     */
    @SuppressLint("MutableImplicitPendingIntent", "UnspecifiedImmutableFlag")
    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            val action = "${context.packageName}.USB_PERMISSION"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != action) return
                    runCatching { context.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), flags)
            usbManager.requestPermission(device, pi)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        }
    }
}
