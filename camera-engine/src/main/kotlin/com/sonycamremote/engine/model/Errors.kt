package com.sonycamremote.engine.model

/** Base class for all failures surfaced by the engine. UI shows [message]. */
open class CameraException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The connected camera does not advertise this capability. The UI should have hidden the control. */
class UnsupportedFeatureException(val capability: Capability?, detail: String) :
    CameraException("Not supported by this camera: $detail")

open class CameraConnectionException(message: String, cause: Throwable? = null) : CameraException(message, cause)

open class CameraProtocolException(message: String, val code: Int? = null, cause: Throwable? = null) :
    CameraException(message, cause)

class CameraBusyException(message: String) : CameraException(message)

class CommandTimeoutException(command: String, timeoutMs: Long) :
    CameraException("'$command' timed out after ${timeoutMs}ms")
