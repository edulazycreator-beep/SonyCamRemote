package com.sonycamremote.engine

import com.sonycamremote.engine.capability.CameraCapabilityManager
import com.sonycamremote.engine.command.CameraCommandQueue
import com.sonycamremote.engine.compat.CompatibilityDatabase
import com.sonycamremote.engine.compat.EvidenceLevel
import com.sonycamremote.engine.diagnostics.DiagnosticsLog
import com.sonycamremote.engine.model.CameraInfo
import com.sonycamremote.engine.model.Capability
import com.sonycamremote.engine.model.CommandTimeoutException
import com.sonycamremote.engine.model.ProtocolKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CompatibilityDatabaseTest {
    @Test
    fun `nothing is claimed as hardware verified`() {
        assertTrue(CompatibilityDatabase.entries.flatMap { it.protocols }.none { it.evidence == EvidenceLevel.HARDWARE_VERIFIED })
    }

    @Test
    fun `finds variants by prefix and requested models are present`() {
        assertEquals("ILME-FX6", CompatibilityDatabase.find("ILME-FX6V")!!.model)
        listOf("ILCE-7M3", "ILCE-7M4", "ILCE-7SM3", "ILCE-6700", "ILME-FX3", "ZV-E10", "ZV-1F", "PXW-Z200", "HXR-NX800")
            .forEach { assertNotNull(it, CompatibilityDatabase.find(it)) }
        assertNull(CompatibilityDatabase.find("DSLR-A900"))
    }

    @Test
    fun `model keys are unique`() {
        val models = CompatibilityDatabase.entries.map { it.model }
        assertEquals(models.size, models.toSet().size)
    }
}

class CapabilityManagerTest {
    @Test
    fun `report flags missing expected capabilities and unknown models`() {
        val m = CameraCapabilityManager()
        val r = m.report(CameraInfo("ILCE-7M4", protocol = ProtocolKind.SONY_PTP_USB), setOf(Capability.STILL_CAPTURE))
        assertTrue(Capability.LIVE_VIEW in r.expectedButMissing)
        assertTrue(r.warnings.isNotEmpty())

        val unknown = m.report(CameraInfo("XYZ-1", protocol = ProtocolKind.SONY_PTP_USB), emptySet())
        assertNull(unknown.compatibility)
        assertTrue(unknown.warnings.first().contains("not in the compatibility database"))
    }
}

class CommandQueueTest {
    @Test
    fun `commands never overlap`() = runBlocking {
        val q = CameraCommandQueue(DiagnosticsLog(), "test")
        val inFlight = AtomicInteger(0)
        var maxInFlight = 0
        (1..10).map {
            async {
                q.submit("c$it") {
                    val n = inFlight.incrementAndGet()
                    maxInFlight = maxOf(maxInFlight, n)
                    delay(5)
                    inFlight.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals(1, maxInFlight)
    }

    @Test
    fun `timeouts are reported and queue keeps working`() = runBlocking {
        val log = DiagnosticsLog()
        val q = CameraCommandQueue(log, "test")
        try {
            q.submit("slow", timeoutMs = 20) { delay(1000) }
            error("expected timeout")
        } catch (_: CommandTimeoutException) {
        }
        assertEquals(42, q.submit("fast") { 42 })
        assertFalse(log.entries.value.isEmpty())
    }
}
