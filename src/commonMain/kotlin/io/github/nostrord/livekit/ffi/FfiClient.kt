package io.github.nostrord.livekit.ffi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import livekit.proto.FfiEvent
import livekit.proto.FfiRequest
import livekit.proto.FfiResponse
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Typed request/response and event stream over [FfiTransport].
 *
 * The FFI is half-asynchronous: a request like `connect` returns immediately with an async id,
 * and the real outcome arrives later as an [FfiEvent] carrying that same id. [requestAsync]
 * hides the correlation, so callers get a plain suspending call.
 *
 * One instance per process — `livekit_ffi_initialize` installs a single global callback, so a
 * second client would steal the first one's events.
 */
@OptIn(ExperimentalAtomicApi::class)
object FfiClient {
    private val transport = FfiTransport()
    private val started = AtomicBoolean(false)

    private val _events = MutableSharedFlow<FfiEvent>(
        extraBufferCapacity = 256,
        // The native callback must never block, so a slow collector loses events rather than
        // stalling LiveKit's thread. 256 is well above the burst a room join produces.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every event the FFI emits: room updates, track subscriptions, async callbacks. */
    val events: SharedFlow<FfiEvent> = _events.asSharedFlow()

    /** Idempotent. [sdk] and [sdkVersion] are reported to the server for diagnostics. */
    fun initialize(sdk: String = "kmp", sdkVersion: String = VERSION, captureLogs: Boolean = false) {
        if (!started.compareAndSet(false, true)) return
        transport.start(sdk, sdkVersion, captureLogs) { bytes ->
            // Decoding here rather than in the collector keeps one malformed event from
            // killing whichever coroutine happens to be collecting.
            val event = runCatching { FfiEvent.ADAPTER.decode(bytes) }.getOrNull()
            if (event != null) _events.tryEmit(event)
        }
    }

    /** Send [request] and decode the immediate reply. */
    fun request(request: FfiRequest): FfiResponse {
        initialize()
        return FfiResponse.ADAPTER.decode(transport.request(FfiRequest.ADAPTER.encode(request)))
    }

    /**
     * Send [request] and suspend until its async callback arrives.
     *
     * [asyncId] reads the id out of the immediate response; [callback] returns non-null for
     * the event that carries the same id, and its value becomes the result.
     */
    suspend fun <T> requestAsync(
        request: FfiRequest,
        asyncId: (FfiResponse) -> Long?,
        callback: (FfiEvent, Long) -> T?,
    ): T = coroutineScope {
        initialize()
        val id = CompletableDeferred<Long>()
        // UNDISPATCHED so collection is live before the request goes out: the FFI can invoke
        // the callback before livekit_ffi_request has even returned.
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            events.mapNotNull { callback(it, id.await()) }.first()
        }
        val response = request(request)
        val value = asyncId(response)
        if (value == null) {
            result.cancel()
            throw FfiException("livekit-ffi returned no async id for the request")
        }
        id.complete(value)
        result.await()
    }

    /** Release an FFI-owned object. See [FfiTransport.dropHandle]. */
    fun dropHandle(handleId: Long) {
        transport.dropHandle(handleId)
    }

    /** Tear the FFI server down. Only for process shutdown and tests. */
    fun dispose() {
        if (!started.compareAndSet(true, false)) return
        transport.dispose()
    }

    internal const val VERSION = "0.1.0"
}
