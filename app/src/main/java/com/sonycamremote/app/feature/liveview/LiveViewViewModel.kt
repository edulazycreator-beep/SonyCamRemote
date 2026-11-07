package com.sonycamremote.app.feature.liveview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonycamremote.app.data.camera.CameraSession
import com.sonycamremote.app.data.camera.CameraSessionManager
import com.sonycamremote.app.data.camera.DecodedFrame
import com.sonycamremote.app.data.camera.LiveViewStreamManager
import com.sonycamremote.app.data.prefs.AppSettings
import com.sonycamremote.app.data.prefs.SettingsRepository
import com.sonycamremote.engine.diagnostics.DiagnosticsLog
import com.sonycamremote.engine.model.CameraCommand
import com.sonycamremote.engine.model.Capability
import com.sonycamremote.engine.model.PropertyId
import com.sonycamremote.engine.model.SettingValue
import com.sonycamremote.engine.model.ZoomDirection
import com.sonycamremote.engine.model.ZoomMovement
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IntervalState(
    val running: Boolean = false,
    val taken: Int = 0,
    val total: Int = 0,
    val nextShotAtMs: Long? = null,
    val lastError: String? = null,
)

@HiltViewModel
class LiveViewViewModel @Inject constructor(
    private val sessions: CameraSessionManager,
    private val settingsRepo: SettingsRepository,
    private val log: DiagnosticsLog,
) : ViewModel() {

    val session: StateFlow<CameraSession?> = sessions.active
    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _frame = MutableStateFlow<DecodedFrame?>(null)
    val frame = _frame.asStateFlow()
    private val _liveViewError = MutableStateFlow<String?>(null)
    val liveViewError = _liveViewError.asStateFlow()
    private val _capturing = MutableStateFlow(false)
    val capturing = _capturing.asStateFlow()
    private val _focusPoint = MutableStateFlow<Pair<Float, Float>?>(null)
    val focusPoint = _focusPoint.asStateFlow()
    private val _interval = MutableStateFlow(IntervalState())
    val interval = _interval.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var liveViewJob: Job? = null
    private var intervalJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun startLiveView() {
        if (liveViewJob?.isActive == true) return
        liveViewJob = viewModelScope.launch {
            combine(session, settings) { s, st -> Triple(s, st.largeLiveView, st.showHistogram || st.showClippingWarning) }
                .distinctUntilChanged()
                .collectLatest { (s, large, hist) ->
                    _frame.value = null
                    _liveViewError.value = null
                    if (s == null || Capability.LIVE_VIEW !in s.protocol.state.value.capabilities) return@collectLatest
                    try {
                        LiveViewStreamManager(log).frames(s.protocol, large, hist).collect { _frame.value = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _liveViewError.value = "Live View stopped: ${e.message}"
                    }
                }
        }
    }

    fun stopLiveView() {
        liveViewJob?.cancel()
        liveViewJob = null
    }

    fun retryLiveView() {
        stopLiveView()
        startLiveView()
    }

    private fun act(label: String, block: suspend (CameraSession) -> Unit) {
        val s = session.value ?: return
        viewModelScope.launch {
            try {
                block(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("$label: ${e.message}")
            }
        }
    }

    fun capture() {
        if (_capturing.value) return
        act("Capture") { s ->
            _capturing.value = true
            try {
                if (settings.value.halfPressBeforeCapture && Capability.HALF_PRESS_AF in s.protocol.state.value.capabilities) {
                    s.protocol.execute(CameraCommand.HalfPressDown)
                    delay(400)
                }
                s.protocol.execute(CameraCommand.TakePicture)
            } finally {
                _capturing.value = false
            }
        }
    }

    fun toggleRecording() = act("Record") { s ->
        val recording = s.protocol.state.value.recording
        s.protocol.execute(if (recording) CameraCommand.StopRecording else CameraCommand.StartRecording)
    }

    fun setProperty(id: PropertyId, value: SettingValue) = act(id.label) { it.protocol.setProperty(id, value) }

    fun touchFocus(x: Float, y: Float) {
        _focusPoint.value = x to y
        act("Touch AF") { it.protocol.execute(CameraCommand.TouchFocus(x, y)) }
    }

    fun clearFocus() {
        _focusPoint.value = null
        act("Cancel AF") { s ->
            if (Capability.TOUCH_AF in s.protocol.state.value.capabilities) s.protocol.execute(CameraCommand.CancelTouchFocus)
        }
    }

    fun halfPress(down: Boolean) = act("AF") {
        it.protocol.execute(if (down) CameraCommand.HalfPressDown else CameraCommand.HalfPressRelease)
    }

    fun focusStep(steps: Int) = act("Focus") { it.protocol.execute(CameraCommand.FocusStep(steps)) }

    fun zoom(direction: ZoomDirection, movement: ZoomMovement) = act("Zoom") {
        it.protocol.execute(CameraCommand.Zoom(direction, movement))
    }

    /** Intervalometer / timelapse: fires real captures on the connected camera. */
    fun startInterval(intervalSec: Int, count: Int, startDelaySec: Int) {
        val s = session.value ?: return
        intervalJob?.cancel()
        intervalJob = viewModelScope.launch {
            _interval.value = IntervalState(running = true, total = count)
            if (startDelaySec > 0) {
                _interval.value = _interval.value.copy(nextShotAtMs = System.currentTimeMillis() + startDelaySec * 1000L)
                delay(startDelaySec * 1000L)
            }
            var taken = 0
            while (isActive && (count == 0 || taken < count)) {
                val started = System.currentTimeMillis()
                try {
                    s.protocol.execute(CameraCommand.TakePicture)
                    taken++
                    _interval.value = _interval.value.copy(taken = taken, lastError = null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _interval.value = _interval.value.copy(lastError = e.message)
                    log.w("Interval", "Shot ${taken + 1} failed: ${e.message}")
                }
                if (count != 0 && taken >= count) break
                val wait = intervalSec * 1000L - (System.currentTimeMillis() - started)
                _interval.value = _interval.value.copy(nextShotAtMs = System.currentTimeMillis() + wait.coerceAtLeast(0))
                if (wait > 0) delay(wait)
            }
            _interval.value = _interval.value.copy(running = false, nextShotAtMs = null)
        }
    }

    fun stopInterval() {
        intervalJob?.cancel()
        _interval.value = _interval.value.copy(running = false, nextShotAtMs = null)
    }

    fun setActive(id: String) = sessions.setActive(id)
}
