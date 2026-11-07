package com.sonycamremote.engine.capability

import com.sonycamremote.engine.compat.CompatibilityDatabase
import com.sonycamremote.engine.compat.CompatibilityEntry
import com.sonycamremote.engine.compat.Expectation
import com.sonycamremote.engine.model.Capability
import com.sonycamremote.engine.model.CameraInfo
import com.sonycamremote.engine.model.CameraState

data class CapabilityReport(
    val info: CameraInfo,
    val compatibility: CompatibilityEntry?,
    val runtime: Set<Capability>,
    /** Capabilities Sony documents for this model that the camera did NOT advertise right now. */
    val expectedButMissing: Set<Capability>,
    /** Advertised by the camera although our database did not expect them (database needs updating). */
    val unexpectedlyPresent: Set<Capability>,
    val warnings: List<String>,
) {
    fun asText(): String = buildString {
        appendLine("Camera: ${info.modelName} (${info.friendlyName})")
        appendLine("Firmware: ${info.firmwareVersion ?: "not reported"}")
        appendLine("Protocol: ${info.protocol.label} ${info.protocolVersion ?: ""}".trim())
        appendLine("Database entry: ${compatibility?.model ?: "none"}")
        appendLine()
        appendLine("Runtime capabilities:")
        Capability.entries.forEach { c -> appendLine("  [${if (c in runtime) "x" else " "}] ${c.label}") }
        if (warnings.isNotEmpty()) {
            appendLine()
            appendLine("Warnings:")
            warnings.forEach { appendLine("  - $it") }
        }
    }
}

/**
 * Runtime capabilities (what the camera advertises) are the only thing that enables UI controls.
 * The compatibility database is used solely to produce warnings and the capability report.
 */
class CameraCapabilityManager(private val db: CompatibilityDatabase = CompatibilityDatabase) {

    fun isEnabled(state: CameraState, capability: Capability): Boolean = capability in state.capabilities

    fun report(info: CameraInfo, runtime: Set<Capability>): CapabilityReport {
        val entry = db.find(info.modelName)
        val expected = entry?.expected?.filterValues { it == Expectation.EXPECTED }?.keys.orEmpty()
        val notExpected = entry?.expected?.filterValues { it == Expectation.NOT_EXPECTED }?.keys.orEmpty()
        val missing = expected - runtime
        val surprising = runtime.intersect(notExpected)
        val warnings = buildList {
            if (entry == null) add("Model '${info.modelName}' is not in the compatibility database. Only camera-advertised features are enabled.")
            if (entry != null && entry.protocols.none { it.protocol == info.protocol }) {
                add("Database has no record of ${info.protocol.label} for this model.")
            }
            if (missing.isNotEmpty()) {
                add("Camera did not advertise: ${missing.joinToString { it.label }}. Check the camera's remote/PC Remote settings and firmware.")
            }
            entry?.protocols?.firstOrNull { it.protocol == info.protocol }?.firmwareRequirement?.let {
                add("Firmware requirement: $it (camera reports ${info.firmwareVersion ?: "unknown"}).")
            }
        }
        return CapabilityReport(info, entry, runtime, missing, surprising, warnings)
    }
}
