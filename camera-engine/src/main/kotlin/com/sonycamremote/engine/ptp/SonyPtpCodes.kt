package com.sonycamremote.engine.ptp

import com.sonycamremote.engine.model.PropertyId
import java.util.Locale

/**
 * Sony PTP vendor-extension codes.
 *
 * SOURCE AND STATUS: Sony publishes the authoritative list in the "Camera Remote Command" Command
 * Reference, which is licensed to corporate customers and is NOT bundled here. The values below are the
 * ones documented by the open-source libgphoto2 project (camlibs/ptp2/ptp.h). They must be checked
 * against the official Command Reference before a production release — every code is isolated in this
 * file so a correction is a one-line change. See docs/PROTOCOLS.md.
 */
object SonyPtp {
    // Vendor operations
    const val OC_SDIO_CONNECT = 0x9201
    const val OC_SDIO_GET_EXT_DEVICE_INFO = 0x9202
    const val OC_SET_CONTROL_DEVICE_A = 0x9205 // set a device property value
    const val OC_SET_CONTROL_DEVICE_B = 0x9207 // press/release a control ("button")
    const val OC_GET_ALL_EXT_DEVICE_PROP_INFO = 0x9209

    /** Object handle that returns the current Live View JPEG. */
    const val HANDLE_LIVE_VIEW = 0xFFFFC002.toInt()

    /** Ext-device-info protocol versions offered during the SDIO handshake, newest first. */
    val SDIO_VERSIONS = listOf(0x012C, 0x00C8)

    // Device properties (standard PTP codes used by Sony)
    const val DPC_WHITE_BALANCE = 0x5005
    const val DPC_F_NUMBER = 0x5007
    const val DPC_FOCUS_MODE = 0x500A
    const val DPC_METERING_MODE = 0x500B
    const val DPC_EXPOSURE_PROGRAM = 0x500E
    const val DPC_EXPOSURE_BIAS = 0x5010
    const val DPC_STILL_CAPTURE_MODE = 0x5013
    const val DPC_COMPRESSION = 0x5004

    // Sony device properties
    const val DPC_IMAGE_SIZE = 0xD203
    const val DPC_SHUTTER_SPEED = 0xD20D
    const val DPC_COLOR_TEMP = 0xD20F
    const val DPC_ASPECT_RATIO = 0xD211
    const val DPC_FOCUS_FOUND = 0xD213
    const val DPC_BATTERY_LEVEL = 0xD218
    const val DPC_ISO = 0xD21E

    // Sony controls (SetControlDeviceB): value 2 = press, 1 = release
    const val CTRL_AUTO_FOCUS = 0xD2C1
    const val CTRL_CAPTURE = 0xD2C2
    const val CTRL_MOVIE = 0xD2C8
    const val CTRL_NEAR_FAR = 0xD2D1
    const val BUTTON_DOWN = 2
    const val BUTTON_UP = 1

    val propertyCodes: Map<PropertyId, Int> = mapOf(
        PropertyId.ISO to DPC_ISO,
        PropertyId.SHUTTER_SPEED to DPC_SHUTTER_SPEED,
        PropertyId.F_NUMBER to DPC_F_NUMBER,
        PropertyId.EXPOSURE_COMPENSATION to DPC_EXPOSURE_BIAS,
        PropertyId.WHITE_BALANCE to DPC_WHITE_BALANCE,
        PropertyId.COLOR_TEMPERATURE to DPC_COLOR_TEMP,
        PropertyId.EXPOSURE_MODE to DPC_EXPOSURE_PROGRAM,
        PropertyId.FOCUS_MODE to DPC_FOCUS_MODE,
        PropertyId.DRIVE_MODE to DPC_STILL_CAPTURE_MODE,
        PropertyId.METERING_MODE to DPC_METERING_MODE,
        PropertyId.IMAGE_QUALITY to DPC_IMAGE_SIZE,
        PropertyId.FILE_FORMAT to DPC_COMPRESSION,
        PropertyId.ASPECT_RATIO to DPC_ASPECT_RATIO,
    )

    private val whiteBalance = mapOf(
        0x0002 to "Auto", 0x0004 to "Daylight", 0x8011 to "Shade", 0x8010 to "Cloudy",
        0x0006 to "Incandescent", 0x8001 to "Fluor. Warm White", 0x8002 to "Fluor. Cool White",
        0x8003 to "Fluor. Day White", 0x8004 to "Fluor. Daylight", 0x0007 to "Flash",
        0x8012 to "C.Temp/Filter", 0x8020 to "Custom", 0x8021 to "Custom 1", 0x8022 to "Custom 2",
        0x8023 to "Custom 3", 0x8030 to "Underwater Auto",
    )
    private val exposureProgram = mapOf(
        0x0001 to "M", 0x0002 to "P", 0x0003 to "A", 0x0004 to "S",
        0x8000 to "Intelligent Auto", 0x8001 to "Superior Auto",
        0x8050 to "Movie P", 0x8051 to "Movie A", 0x8052 to "Movie S", 0x8053 to "Movie M",
    )
    private val focusMode = mapOf(0x0001 to "MF", 0x0002 to "AF-S", 0x8004 to "AF-C", 0x8005 to "AF-A", 0x8006 to "DMF")
    private val aspect = mapOf(1 to "3:2", 2 to "16:9", 3 to "4:3", 4 to "1:1")
    private val imageSize = mapOf(1 to "L", 2 to "M", 3 to "S")

    /** Human-readable label for a raw property value. Unknown codes are shown in hex — never guessed. */
    fun label(code: Int, raw: Long): String = when (code) {
        DPC_ISO -> isoLabel(raw)
        DPC_SHUTTER_SPEED -> shutterLabel(raw)
        DPC_F_NUMBER -> if (raw % 100 == 0L) "f/${raw / 100}" else String.format(Locale.US, "f/%.1f", raw / 100.0)
        DPC_EXPOSURE_BIAS -> {
            val ev = raw.toShort() / 1000.0
            (if (ev > 0) "+" else "") + String.format(Locale.US, "%.1f", ev)
        }
        DPC_COLOR_TEMP -> "${raw}K"
        DPC_BATTERY_LEVEL -> "$raw%"
        DPC_WHITE_BALANCE -> whiteBalance[raw.toInt()] ?: hex(raw)
        DPC_EXPOSURE_PROGRAM -> exposureProgram[raw.toInt()] ?: hex(raw)
        DPC_FOCUS_MODE -> focusMode[raw.toInt()] ?: hex(raw)
        DPC_ASPECT_RATIO -> aspect[raw.toInt()] ?: hex(raw)
        DPC_IMAGE_SIZE -> imageSize[raw.toInt()] ?: hex(raw)
        else -> hex(raw)
    }

    fun isoLabel(raw: Long): String {
        val value = raw and 0xFFFFFF
        val prefix = if ((raw shr 24) and 0xFF != 0L) "MFNR " else ""
        return prefix + if (value == 0xFFFFFFL) "AUTO" else "ISO $value"
    }

    /** Sony encodes shutter speed as (numerator << 16) | denominator; 0 = BULB. */
    fun shutterLabel(raw: Long): String {
        if (raw == 0L) return "BULB"
        val num = (raw shr 16) and 0xFFFF
        val den = raw and 0xFFFF
        if (den == 0L) return hex(raw)
        return when {
            num == 1L && den > 1 -> "1/$den"
            den == 1L -> "$num\""
            num % den == 0L -> "${num / den}\""
            else -> String.format(Locale.US, "%.1f\"", num.toDouble() / den)
        }
    }

    private fun hex(raw: Long) = "0x%X".format(raw)
}
