package com.sonycamremote.engine.command

import com.sonycamremote.engine.diagnostics.DiagnosticsLog
import com.sonycamremote.engine.model.CommandTimeoutException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Serialises camera commands so two controls can never race on the camera (e.g. changing ISO while a
 * capture sequence is in progress). Each command is timed and logged for the Diagnostics screen.
 *
 * Live View streaming deliberately does NOT go through this queue; it runs on its own connection
 * (legacy API) or is interleaved at the transport level (PTP sessions use their own transaction lock).
 */
class CameraCommandQueue(
    private val log: DiagnosticsLog,
    private val source: String,
    private val defaultTimeoutMs: Long = 10_000,
) {
    private val mutex = Mutex()

    @Volatile
    var lastLatencyMs: Long? = null
        private set

    suspend fun <T> submit(name: String, timeoutMs: Long = defaultTimeoutMs, block: suspend () -> T): T =
        mutex.withLock {
            val start = System.nanoTime()
            try {
                val result = withTimeout(timeoutMs) { block() }
                val ms = (System.nanoTime() - start) / 1_000_000
                lastLatencyMs = ms
                log.log(com.sonycamremote.engine.diagnostics.LogLevel.DEBUG, source, "OK $name", ms)
                result
            } catch (t: TimeoutCancellationException) {
                log.e(source, "TIMEOUT $name after ${timeoutMs}ms")
                throw CommandTimeoutException(name, timeoutMs)
            } catch (t: Throwable) {
                val ms = (System.nanoTime() - start) / 1_000_000
                log.log(com.sonycamremote.engine.diagnostics.LogLevel.ERROR, source, "FAIL $name: ${t.message}", ms)
                throw t
            }
        }
}
