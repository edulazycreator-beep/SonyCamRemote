package com.sonycamremote.app.data.camera

import com.sonycamremote.app.data.camera.wifi.WifiCameraConnector
import com.sonycamremote.app.data.db.SessionEventDao
import com.sonycamremote.app.data.db.SessionEventEntity
import com.sonycamremote.app.data.prefs.SettingsRepository
import com.sonycamremote.engine.capability.CameraCapabilityManager
import com.sonycamremote.engine.capability.CapabilityReport
import com.sonycamremote.engine.diagnostics.DiagnosticsLog
import com.sonycamremote.engine.model.CameraCommand
import com.sonycamremote.engine.model.CameraConnectionException
import com.sonycamremote.engine.model.ConnectionStatus
import com.sonycamremote.engine.protocol.CameraProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/** One live connection. Each session owns its scope; cancelling it releases every resource. */
class CameraSession(
    val id: String,
    val profile: ConnectionProfile,
    val protocol: CameraProtocol,
    val scope: CoroutineScope,
    val report: CapabilityReport,
) {
    /** Non-null while the recovery manager is retrying: the current attempt number. */
    val reconnectAttempt = MutableStateFlow<Int?>(null)
    val displayName: String get() = report.info.modelName.ifBlank { profile.name }
}

data class GroupCommandResult(
    val sessionId: String,
    val name: String,
    /** Offset from the first dispatch, in ms. Shows real-world spread: network sends are NOT frame-synchronised. */
    val dispatchOffsetMs: Double,
    val completedAfterMs: Double,
    val error: String?,
)

interface CameraRepository {
    val sessions: StateFlow<Map<String, CameraSession>>
    val active: StateFlow<CameraSession?>
    suspend fun connect(profile: ConnectionProfile): CameraSession
    suspend fun disconnect(sessionId: String)
    fun setActive(sessionId: String)
    suspend fun groupExecute(sessionIds: Collection<String>, command: CameraCommand): List<GroupCommandResult>
}

/**
 * Owns all camera sessions (multi-camera), the active-camera selection, and per-session
 * connection recovery (automatic reconnection with exponential back-off).
 */
@Singleton
class CameraSessionManager @Inject constructor(
    private val factory: ProtocolFactory,
    private val log: DiagnosticsLog,
    private val settings: SettingsRepository,
    private val wifi: WifiCameraConnector,
    private val saved: SavedCameraRepository,
    private val history: SessionEventDao,
) : CameraRepository {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val capabilityManager = CameraCapabilityManager()

    private val _sessions = MutableStateFlow<Map<String, CameraSession>>(emptyMap())
    override val sessions: StateFlow<Map<String, CameraSession>> = _sessions.asStateFlow()

    private val activeId = MutableStateFlow<String?>(null)
    override val active: StateFlow<CameraSession?> = combine(_sessions, activeId) { s, id -> id?.let { s[it] } ?: s.values.firstOrNull() }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    override suspend fun connect(profile: ConnectionProfile): CameraSession {
        _sessions.value[profile.id]?.let { disconnect(it.id) }

        profile.wifi?.let { creds ->
            if (wifi.canJoinProgrammatically && wifi.boundNetwork == null) wifi.join(creds)
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val protocol = factory.create(profile, scope)
        val info = try {
            withTimeout(CONNECT_TIMEOUT_MS) { protocol.connect() }
        } catch (e: CancellationException) {
            scope.cancel()
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                throw CameraConnectionException("Connection timed out after ${CONNECT_TIMEOUT_MS / 1000}s")
            }
            throw e
        } catch (e: Exception) {
            scope.cancel()
            log.e("Session", "Connect ${profile.name} failed: ${e.message}")
            throw e
        }

        val report = capabilityManager.report(info, protocol.state.value.capabilities)
        report.warnings.forEach { log.w("Capability", it) }
        val session = CameraSession(profile.id, profile, protocol, scope, report)
        _sessions.update { it + (session.id to session) }
        activeId.value = session.id

        saved.save(profile.copy(modelName = info.modelName, lastConnectedAt = System.currentTimeMillis()))
        record(session.displayName, "Connected via ${info.protocol.label}")
        watchForRecovery(session)
        return session
    }

    override suspend fun disconnect(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        _sessions.update { it - sessionId }
        if (activeId.value == sessionId) activeId.value = _sessions.value.keys.firstOrNull()
        runCatching { session.protocol.disconnect() }
        session.scope.cancel()
        record(session.displayName, "Disconnected")
        if (_sessions.value.isEmpty()) wifi.release()
    }

    suspend fun disconnectAll() = _sessions.value.keys.toList().forEach { disconnect(it) }

    override fun setActive(sessionId: String) {
        if (sessionId in _sessions.value) activeId.value = sessionId
    }

    /**
     * Sends [command] to several cameras at the same time. These are simultaneous network requests:
     * timing depends on Wi-Fi/USB latency and on each camera, so they are NOT frame-accurate. The
     * result reports measured dispatch spread so the user can see the real offset.
     */
    override suspend fun groupExecute(sessionIds: Collection<String>, command: CameraCommand): List<GroupCommandResult> =
        coroutineScope {
            val targets = sessionIds.mapNotNull { _sessions.value[it] }
            val t0 = System.nanoTime()
            targets.map { s ->
                async(Dispatchers.IO) {
                    val start = System.nanoTime()
                    val err = try {
                        s.protocol.execute(command); null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.message ?: e.javaClass.simpleName
                    }
                    GroupCommandResult(
                        s.id, s.displayName,
                        dispatchOffsetMs = (start - t0) / 1e6,
                        completedAfterMs = (System.nanoTime() - t0) / 1e6,
                        error = err,
                    )
                }
            }.awaitAll()
        }

    private fun watchForRecovery(session: CameraSession) {
        session.scope.launch {
            session.protocol.state.collect { st ->
                if (st.connection.status != ConnectionStatus.FAILED) return@collect
                if (session.reconnectAttempt.value != null) return@collect
                if (!settings.settings.first().autoReconnect) return@collect
                recover(session)
            }
        }
    }

    /** ConnectionRecoveryManager: exponential back-off 1 s → 16 s, 6 attempts, then give up. */
    private suspend fun recover(session: CameraSession) {
        record(session.displayName, "Connection lost — reconnecting")
        var delayMs = 1000L
        for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
            session.reconnectAttempt.value = attempt
            delay(delayMs)
            try {
                runCatching { session.protocol.disconnect() }
                withTimeout(CONNECT_TIMEOUT_MS) { session.protocol.connect() }
                log.i("Recovery", "${session.displayName} reconnected on attempt $attempt")
                record(session.displayName, "Reconnected")
                session.reconnectAttempt.value = null
                return
            } catch (e: CancellationException) {
                if (e is kotlinx.coroutines.TimeoutCancellationException) {
                    log.w("Recovery", "Attempt $attempt timed out")
                } else {
                    throw e
                }
            } catch (e: Exception) {
                log.w("Recovery", "Attempt $attempt failed: ${e.message}")
            }
            delayMs = (delayMs * 2).coerceAtMost(16_000)
        }
        session.reconnectAttempt.value = null
        log.e("Recovery", "Giving up on ${session.displayName}")
        record(session.displayName, "Reconnect failed")
    }

    private fun record(camera: String, event: String) {
        appScope.launch { runCatching { history.insert(SessionEventEntity(0, System.currentTimeMillis(), camera, event)) } }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val MAX_RECONNECT_ATTEMPTS = 6
    }
}
