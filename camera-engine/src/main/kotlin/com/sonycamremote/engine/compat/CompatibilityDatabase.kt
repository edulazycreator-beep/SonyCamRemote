package com.sonycamremote.engine.compat

import com.sonycamremote.engine.model.Capability
import com.sonycamremote.engine.model.ProtocolKind

/**
 * Where a compatibility statement comes from. Nothing in this database is HARDWARE_VERIFIED until a
 * person records a passing run of the compatibility test suite (docs/COMPATIBILITY.md) on that body.
 */
enum class EvidenceLevel(val label: String) {
    /** Model appears on Sony's official supported-model list for the protocol. */
    LISTED_BY_SONY("Listed by Sony"),
    /** Reported to work by open-source projects (e.g. libgphoto2) — not confirmed by Sony. */
    COMMUNITY_REPORTED("Community reported"),
    /** No known evidence. */
    UNKNOWN("Unknown"),
    /** Passed this app's compatibility test suite on real hardware. */
    HARDWARE_VERIFIED("Verified on hardware"),
}

enum class Expectation { EXPECTED, NOT_EXPECTED, UNKNOWN }

data class ProtocolSupport(
    val protocol: ProtocolKind,
    val evidence: EvidenceLevel,
    val firmwareRequirement: String? = null,
    val note: String? = null,
)

data class CompatibilityEntry(
    val model: String,
    val marketingName: String,
    val family: String,
    val protocols: List<ProtocolSupport>,
    /** Expectations only — the UI always uses the capabilities the camera reports at runtime. */
    val expected: Map<Capability, Expectation>,
    val knownLimitations: List<String>,
) {
    fun expectation(c: Capability) = expected[c] ?: Expectation.UNKNOWN
}

/**
 * Static compatibility knowledge used for: the Compatibility Database screen, warnings when a connected
 * camera behaves differently from what is expected, and suggesting which connection method to try.
 *
 * Sources:
 *  - Camera Remote SDK / Camera Remote Command supported-model lists, support.d-imaging.sony.co.jp (2026-11).
 *  - Sony Camera Remote API (legacy) beta compatibility list (archived Sony developer documentation).
 *  - libgphoto2 camera list for community-reported Sony PTP/USB "PC Remote" support.
 */
object CompatibilityDatabase {

    private val SDK_CORE = mapOf(
        Capability.LIVE_VIEW to Expectation.EXPECTED,
        Capability.STILL_CAPTURE to Expectation.EXPECTED,
        Capability.MOVIE_RECORDING to Expectation.EXPECTED,
        Capability.ISO to Expectation.EXPECTED,
        Capability.SHUTTER_SPEED to Expectation.EXPECTED,
        Capability.APERTURE to Expectation.EXPECTED,
        Capability.EXPOSURE_COMPENSATION to Expectation.EXPECTED,
        Capability.WHITE_BALANCE to Expectation.EXPECTED,
        Capability.FOCUS_MODE to Expectation.EXPECTED,
        Capability.BATTERY_STATUS to Expectation.EXPECTED,
        Capability.MEDIA_TRANSFER to Expectation.UNKNOWN,
        Capability.ZOOM_OPTICAL to Expectation.UNKNOWN,
    )

    private val LEGACY_CORE = mapOf(
        Capability.LIVE_VIEW to Expectation.EXPECTED,
        Capability.STILL_CAPTURE to Expectation.EXPECTED,
        Capability.MOVIE_RECORDING to Expectation.UNKNOWN,
        Capability.ISO to Expectation.UNKNOWN,
        Capability.SHUTTER_SPEED to Expectation.UNKNOWN,
        Capability.APERTURE to Expectation.UNKNOWN,
        Capability.TOUCH_AF to Expectation.UNKNOWN,
        Capability.ZOOM_OPTICAL to Expectation.UNKNOWN,
        Capability.MANUAL_FOCUS_STEP to Expectation.NOT_EXPECTED,
        Capability.MEDIA_TRANSFER to Expectation.UNKNOWN,
    )

    private val COMMUNITY_PTP = mapOf(
        Capability.LIVE_VIEW to Expectation.UNKNOWN,
        Capability.STILL_CAPTURE to Expectation.UNKNOWN,
        Capability.ISO to Expectation.UNKNOWN,
        Capability.SHUTTER_SPEED to Expectation.UNKNOWN,
        Capability.APERTURE to Expectation.UNKNOWN,
    )

    private const val SDK_NOTE =
        "Sony supports only the latest system software. Interface support (USB / wired LAN / Wi-Fi) varies by model — see the README in Sony's SDK package."
    private const val LEGACY_APP_NOTE =
        "Needs the 'Smart Remote Control' camera app. The PlayMemories Camera Apps store has closed, so it must already be installed."
    private const val LEGACY_EMBEDDED_NOTE = "Uses the built-in 'Smart Remote Embedded' (Ctrl with Smartphone) function."

    private fun sdkModel(
        model: String, name: String, family: String,
        fw: String? = null, limitations: List<String> = emptyList(),
    ) = CompatibilityEntry(
        model = model, marketingName = name, family = family,
        protocols = listOf(
            ProtocolSupport(ProtocolKind.SONY_PTP_USB, EvidenceLevel.LISTED_BY_SONY, fw, "Camera Remote Command / PC Remote over USB"),
            ProtocolSupport(ProtocolKind.SONY_PTP_IP, EvidenceLevel.LISTED_BY_SONY, fw, "Only on bodies whose README lists LAN/Wi-Fi control"),
            ProtocolSupport(ProtocolKind.SDK_BRIDGE, EvidenceLevel.LISTED_BY_SONY, fw, "Official SDK on a separate host"),
        ),
        expected = SDK_CORE,
        knownLimitations = listOf(SDK_NOTE) + limitations,
    )

    private fun legacyModel(
        model: String, name: String, family: String, embedded: Boolean,
        limitations: List<String> = emptyList(), extra: Map<Capability, Expectation> = emptyMap(),
    ) = CompatibilityEntry(
        model = model, marketingName = name, family = family,
        protocols = listOf(
            ProtocolSupport(
                ProtocolKind.LEGACY_REMOTE_API, EvidenceLevel.LISTED_BY_SONY,
                if (embedded) "Built-in remote function" else "Smart Remote Control app 2.x or later",
            ),
        ),
        expected = LEGACY_CORE + extra,
        knownLimitations = listOf(if (embedded) LEGACY_EMBEDDED_NOTE else LEGACY_APP_NOTE) + limitations,
    )

    private fun communityModel(
        model: String, name: String, family: String, limitations: List<String> = emptyList(),
    ) = CompatibilityEntry(
        model = model, marketingName = name, family = family,
        protocols = listOf(
            ProtocolSupport(ProtocolKind.SONY_PTP_USB, EvidenceLevel.COMMUNITY_REPORTED, null, "Set USB Connection to 'PC Remote'"),
        ),
        expected = COMMUNITY_PTP,
        knownLimitations = listOf(
            "Not on Sony's Camera Remote SDK / Command model list. Sony PTP extension behaviour unverified.",
        ) + limitations,
    )

    val entries: List<CompatibilityEntry> = listOf(
        // ---- Alpha 1 / 9 / 7 (Sony SDK list) ----
        sdkModel("ILCE-1M2", "Alpha 1 II", "Alpha 1"),
        sdkModel("ILCE-1", "Alpha 1", "Alpha 1"),
        sdkModel("ILCE-9M3", "Alpha 9 III", "Alpha 9"),
        sdkModel("ILCE-9M2", "Alpha 9 II", "Alpha 9"),
        sdkModel("ILCE-7RM6", "Alpha 7R VI", "Alpha 7"),
        sdkModel("ILCE-7RM5", "Alpha 7R V", "Alpha 7"),
        sdkModel("ILCE-7RM4A", "Alpha 7R IVA", "Alpha 7"),
        sdkModel("ILCE-7RM4", "Alpha 7R IV", "Alpha 7"),
        sdkModel("ILCE-7CR", "Alpha 7CR", "Alpha 7"),
        sdkModel("ILCE-7SM3", "Alpha 7S III", "Alpha 7"),
        sdkModel("ILCE-7M5", "Alpha 7 V", "Alpha 7"),
        sdkModel("ILCE-7M4", "Alpha 7 IV", "Alpha 7"),
        sdkModel("ILCE-7CM2", "Alpha 7C II", "Alpha 7"),
        sdkModel("ILCE-7C", "Alpha 7C", "Alpha 7"),
        sdkModel("ILCE-6700", "Alpha 6700", "Alpha 6xxx"),
        sdkModel("ILX-LR1", "ILX-LR1", "Industrial"),
        // ---- Cinema Line / professional video ----
        sdkModel("MPC-2610", "BURANO", "Cinema Line"),
        sdkModel("ILME-FX6", "FX6 (FX6V / FX6T)", "Cinema Line", fw = "Ver. 3.00 or later"),
        sdkModel("ILME-FX3A", "FX3A", "Cinema Line"),
        sdkModel("ILME-FX3", "FX3", "Cinema Line", fw = "Ver. 2.00 or later"),
        sdkModel("ILME-FX2", "FX2", "Cinema Line"),
        sdkModel("ILME-FX30", "FX30", "Cinema Line"),
        sdkModel("ILME-FR7", "FR7", "Cinema Line PTZ", fw = "Ver. 3.00 or later"),
        sdkModel("PXW-Z300", "PXW-Z300", "PXW"),
        sdkModel("PXW-Z380", "PXW-Z380", "PXW"),
        sdkModel("PXW-Z200", "PXW-Z200", "PXW"),
        sdkModel("HXR-NX800", "HXR-NX800", "HXR"),
        sdkModel("BRC-AM7", "BRC-AM7", "PTZ"),
        // ---- ZV / RX (Sony SDK list) ----
        sdkModel("ZV-E1", "ZV-E1", "ZV"),
        sdkModel("ZV-E10M2", "ZV-E10 II", "ZV"),
        sdkModel("DSC-RX1RM3", "RX1R III", "RX"),
        sdkModel("DSC-RX0M2", "RX0 II", "RX", fw = "Ver. 3.00 or later"),
        // ---- Requested models NOT on Sony's SDK list ----
        communityModel("ILCE-7M3", "Alpha 7 III", "Alpha 7",
            listOf("Wi-Fi remote uses Imaging Edge Mobile's PTP-IP pairing; LAN control not documented by Sony.")),
        communityModel("ILCE-6400", "Alpha 6400", "Alpha 6xxx"),
        communityModel("ILCE-6100", "Alpha 6100", "Alpha 6xxx"),
        communityModel("ZV-E10", "ZV-E10", "ZV"),
        communityModel("ZV-1", "ZV-1", "ZV", listOf("Sony lists 'some ZV-1 models' for Camera Remote Command — check the README for your variant.")),
        communityModel("ZV-1F", "ZV-1F", "ZV"),
        communityModel("DSC-RX100M7", "RX100 VII", "RX"),
        communityModel("DSC-RX100M6", "RX100 VI", "RX"),
        communityModel("DSC-RX10M4", "RX10 IV", "RX"),
        communityModel("DSC-RX0", "RX0", "RX"),
        // ---- Legacy Camera Remote API models ----
        legacyModel("ILCE-7M2", "Alpha 7 II", "Alpha 7", embedded = false),
        legacyModel("ILCE-7RM2", "Alpha 7R II", "Alpha 7", embedded = false),
        legacyModel("ILCE-7SM2", "Alpha 7S II", "Alpha 7", embedded = false),
        legacyModel("ILCE-7", "Alpha 7", "Alpha 7", embedded = false),
        legacyModel("ILCE-7R", "Alpha 7R", "Alpha 7", embedded = false),
        legacyModel("ILCE-7S", "Alpha 7S", "Alpha 7", embedded = false),
        legacyModel("ILCE-6500", "Alpha 6500", "Alpha 6xxx", embedded = true),
        legacyModel("ILCE-6300", "Alpha 6300", "Alpha 6xxx", embedded = true),
        legacyModel("ILCE-6000", "Alpha 6000", "Alpha 6xxx", embedded = false),
        legacyModel("ILCE-5100", "Alpha 5100", "Alpha 5xxx", embedded = false),
        legacyModel("ILCE-5000", "Alpha 5000", "Alpha 5xxx", embedded = false),
        legacyModel("ILCE-QX1", "QX1", "QX", embedded = true,
            extra = mapOf(Capability.ZOOM_OPTICAL to Expectation.NOT_EXPECTED)),
        legacyModel("DSC-QX100", "QX100", "QX", embedded = true,
            extra = mapOf(Capability.ZOOM_OPTICAL to Expectation.EXPECTED)),
        legacyModel("DSC-QX30", "QX30", "QX", embedded = true,
            extra = mapOf(Capability.ZOOM_OPTICAL to Expectation.EXPECTED)),
        legacyModel("DSC-QX10", "QX10", "QX", embedded = true,
            extra = mapOf(Capability.ZOOM_OPTICAL to Expectation.EXPECTED)),
        legacyModel("DSC-RX100M5", "RX100 V", "RX", embedded = true),
        legacyModel("DSC-RX100M4", "RX100 IV", "RX", embedded = false),
        legacyModel("DSC-RX100M3", "RX100 III", "RX", embedded = false),
        legacyModel("DSC-RX100M2", "RX100 II", "RX", embedded = true),
        legacyModel("DSC-RX10M3", "RX10 III", "RX", embedded = false),
        legacyModel("DSC-RX10M2", "RX10 II", "RX", embedded = false),
        legacyModel("DSC-RX1RM2", "RX1R II", "RX", embedded = false),
        legacyModel("DSC-HX90V", "HX90V", "Cyber-shot", embedded = true),
        legacyModel("DSC-HX80", "HX80", "Cyber-shot", embedded = true),
        legacyModel("FDR-X3000", "FDR-X3000", "Action Cam", embedded = true,
            limitations = listOf("Action Cams do not expose exposure triangle controls.")),
        legacyModel("HDR-AS300", "HDR-AS300", "Action Cam", embedded = true,
            limitations = listOf("Action Cams do not expose exposure triangle controls.")),
    )

    private val byModel = entries.associateBy { normalise(it.model) }

    fun find(model: String?): CompatibilityEntry? {
        if (model.isNullOrBlank()) return null
        val key = normalise(model)
        byModel[key]?.let { return it }
        // Regional / bundle variants such as "ILME-FX6V", "ILME-FX6T": exactly one trailing letter.
        // (A looser prefix match would wrongly map e.g. ILCE-7RM3 to the ILCE-7R entry.)
        return entries.firstOrNull {
            val base = normalise(it.model)
            key.length == base.length + 1 && key.startsWith(base) && key.last().isLetter()
        }
    }

    fun search(query: String): List<CompatibilityEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return entries
        return entries.filter {
            it.model.lowercase().contains(q) || it.marketingName.lowercase().contains(q) || it.family.lowercase().contains(q)
        }
    }

    private fun normalise(s: String) = s.uppercase().replace(" ", "").trim()
}
