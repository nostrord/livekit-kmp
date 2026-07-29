package io.github.nostrord.livekit.ffi

import com.sun.jna.Memory
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import livekit.proto.FfiEvent
import livekit.proto.FfiRequest
import livekit.proto.FfiResponse
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Request/response and event transport over the livekit-ffi C ABI.
 *
 * One instance per process: `livekit_ffi_initialize` installs a single global callback, so a
 * second client would displace the first one's event stream.
 */
object FfiClient {
    private val started = AtomicBoolean(false)

    private val _events = MutableSharedFlow<FfiEvent>(
        extraBufferCapacity = 256,
        // The FFI callback must never block, so a slow collector drops events rather than
        // stalling LiveKit's thread. 256 is far above the burst size of a room join.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Asynchronous events: room updates, track subscriptions, connection state. */
    val events: SharedFlow<FfiEvent> = _events.asSharedFlow()

    /**
     * Held for the process lifetime. JNA callbacks are only reachable from native code, so
     * dropping this reference would let the GC collect it and crash the next event.
     */
    private val callback = FfiEventCallback { data, len ->
        // Copy immediately: the buffer belongs to the caller and is invalid once this returns.
        val bytes = data.getByteArray(0, len.toInt())
        runCatching { _events.tryEmit(FfiEvent.ADAPTER.decode(bytes)) }
    }

    /** Idempotent. [sdk] and [sdkVersion] are reported to the server for diagnostics. */
    fun initialize(sdk: String = "kmp", sdkVersion: String = "0.1.0", captureLogs: Boolean = false) {
        if (!started.compareAndSet(false, true)) return
        FfiLibrary.INSTANCE.livekit_ffi_initialize(callback, captureLogs, sdk, sdkVersion)
    }

    /**
     * Send [request] and decode the reply.
     *
     * Throws [FfiException] when the FFI rejects the request, which it reports only as an
     * invalid handle: the reason goes to the FFI's own log, not back across the boundary.
     */
    fun request(request: FfiRequest): FfiResponse {
        initialize()
        val encoded = FfiRequest.ADAPTER.encode(request)
        val resPtr = PointerByReference()
        val resLen = LongByReference()

        // Memory (not a Kotlin ByteArray) so the buffer stays at a fixed native address for
        // the duration of the call.
        val buffer = Memory(encoded.size.toLong().coerceAtLeast(1))
        return buffer.use {
            buffer.write(0, encoded, 0, encoded.size)
            val handle = FfiLibrary.INSTANCE.livekit_ffi_request(buffer, encoded.size.toLong(), resPtr, resLen)
            if (handle == FfiLibrary.INVALID_HANDLE) {
                throw FfiException("livekit-ffi rejected the request: ${request.describe()}")
            }
            try {
                val bytes = resPtr.value.getByteArray(0, resLen.value.toInt())
                FfiResponse.ADAPTER.decode(bytes)
            } finally {
                // The response buffer is owned by the FFI until the handle is dropped.
                FfiLibrary.INSTANCE.livekit_ffi_drop_handle(handle)
            }
        }
    }

    /** Tear the FFI server down. Only for process shutdown and tests. */
    fun dispose() {
        if (!started.compareAndSet(true, false)) return
        FfiLibrary.INSTANCE.livekit_ffi_dispose()
    }
}

class FfiException(message: String) : RuntimeException(message)

private inline fun <T> Memory.use(block: () -> T): T = try {
    block()
} finally {
    close()
}

/** Which oneof arm was set, for error messages. The payload may hold a token, so never log it. */
private fun FfiRequest.describe(): String = when {
    connect != null -> "connect"
    disconnect != null -> "disconnect"
    new_audio_resampler != null -> "new_audio_resampler"
    else -> "unknown"
}
