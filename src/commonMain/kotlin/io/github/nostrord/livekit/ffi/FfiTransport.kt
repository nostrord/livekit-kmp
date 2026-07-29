package io.github.nostrord.livekit.ffi

/**
 * The four livekit-ffi C entry points. Everything above this is shared Kotlin: the protocol
 * itself is protobuf, so only the byte-level crossing is platform work.
 *
 * ```c
 * void     livekit_ffi_initialize(FfiCallbackFn cb, bool capture_logs, const char* sdk, const char* sdk_version);
 * uint64_t livekit_ffi_request(const uint8_t* data, size_t len, const uint8_t** res_ptr, size_t* res_len);
 * bool     livekit_ffi_drop_handle(uint64_t handle_id);
 * void     livekit_ffi_dispose(void);
 * ```
 */
internal expect class FfiTransport() {
    /**
     * Load the library, install [onEvent] and start the FFI server.
     *
     * [onEvent] receives encoded `FfiEvent`s on LiveKit's own threads. It must not block and
     * must not throw.
     */
    fun start(sdk: String, sdkVersion: String, captureLogs: Boolean, onEvent: (ByteArray) -> Unit)

    /**
     * Send an encoded `FfiRequest` and return the encoded `FfiResponse`.
     *
     * Implementations must release the FFI's response handle before returning; the bytes are
     * only valid until then, so they have to be copied out first.
     *
     * Throws [FfiException] when the FFI rejects the request.
     */
    fun request(request: ByteArray): ByteArray

    /**
     * Release an FFI-owned object (a video buffer, a track, a room).
     *
     * Anything the protocol calls `Owned*` belongs to the caller once handed over. Frames are
     * the case that bites: at 30 fps a leaked buffer per frame is tens of megabytes a second.
     */
    fun dropHandle(handleId: Long)

    fun dispose()
}

/** The FFI refused a request, or the native library could not be loaded. */
class FfiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
