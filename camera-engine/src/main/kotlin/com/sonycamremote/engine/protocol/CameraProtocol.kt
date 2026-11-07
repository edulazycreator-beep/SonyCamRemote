package com.sonycamremote.engine.protocol

import com.sonycamremote.engine.model.CameraCommand
import com.sonycamremote.engine.model.CameraInfo
import com.sonycamremote.engine.model.CameraState
import com.sonycamremote.engine.model.LiveViewFrame
import com.sonycamremote.engine.model.MediaItem
import com.sonycamremote.engine.model.PropertyId
import com.sonycamremote.engine.model.ProtocolKind
import com.sonycamremote.engine.model.SettingValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.OutputStream

/**
 * Common contract implemented by every adapter (legacy Remote API, Sony PTP over USB/IP, SDK bridge).
 *
 * Rules every implementation follows:
 *  - [state] reflects only what the camera reported; nothing is invented.
 *  - Operations the camera did not advertise throw [com.sonycamremote.engine.model.UnsupportedFeatureException].
 *  - All functions are main-safe (they switch to IO internally).
 */
interface CameraProtocol {
    val kind: ProtocolKind
    val state: StateFlow<CameraState>

    /** Opens the session, performs any handshake, reads capabilities and initial state. */
    suspend fun connect(): CameraInfo

    /** Releases sockets / USB interfaces. Safe to call more than once. */
    suspend fun disconnect()

    /** Re-reads all properties from the camera (used by the state monitor). */
    suspend fun refreshState()

    suspend fun execute(command: CameraCommand)

    suspend fun setProperty(id: PropertyId, value: SettingValue)

    /**
     * Cold flow of real camera Live View frames. Collecting starts the stream; cancelling stops it.
     * [preferLargeSize] is honoured only if the camera offers a size choice.
     */
    fun liveView(preferLargeSize: Boolean = false): Flow<LiveViewFrame>

    suspend fun listMedia(offset: Int, count: Int): List<MediaItem>

    /** Streams the original file into [sink]. [onProgress] receives (bytesWritten, totalOrNull). */
    suspend fun download(item: MediaItem, sink: OutputStream, onProgress: (Long, Long?) -> Unit)

    /** Small thumbnail bytes, or null if the protocol cannot provide one. */
    suspend fun thumbnail(item: MediaItem): ByteArray?
}
