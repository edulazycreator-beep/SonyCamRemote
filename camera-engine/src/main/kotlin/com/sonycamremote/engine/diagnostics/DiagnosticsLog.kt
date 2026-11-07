package com.sonycamremote.engine.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val timestampMs: Long,
    val level: LogLevel,
    val source: String,
    val message: String,
    val durationMs: Long? = null,
)

/** Thread-safe ring buffer of connection and command diagnostics, observable by the Diagnostics screen. */
class DiagnosticsLog(private val capacity: Int = 1000, private val clock: () -> Long = System::currentTimeMillis) {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun log(level: LogLevel, source: String, message: String, durationMs: Long? = null) {
        val entry = LogEntry(clock(), level, source, message, durationMs)
        _entries.update { old -> (old + entry).takeLast(capacity) }
    }

    fun d(source: String, message: String) = log(LogLevel.DEBUG, source, message)
    fun i(source: String, message: String) = log(LogLevel.INFO, source, message)
    fun w(source: String, message: String) = log(LogLevel.WARN, source, message)
    fun e(source: String, message: String) = log(LogLevel.ERROR, source, message)

    fun clear() = _entries.update { emptyList() }

    fun export(): String = _entries.value.joinToString("\n") { e ->
        "${e.timestampMs}\t${e.level}\t${e.source}\t${e.message}" + (e.durationMs?.let { "\t${it}ms" } ?: "")
    }
}
