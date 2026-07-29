package io.github.nostrord.livekit.ffi

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * The livekit-ffi C ABI. Four entry points; everything else is protobuf on the wire.
 *
 * `size_t` is mapped to `Long`, which is correct on every 64-bit platform. The desktop
 * binaries LiveKit publishes are all 64-bit, so there is no 32-bit case to handle here;
 * a 32-bit target (android armv7) would need a size mapper.
 */
internal interface FfiLibrary : Library {
    /**
     * Install the event callback and start the FFI server. Call once per process.
     *
     * [cb] is invoked from LiveKit's own threads and must neither block nor throw.
     */
    fun livekit_ffi_initialize(cb: FfiEventCallback, captureLogs: Boolean, sdk: String, sdkVersion: String)

    /**
     * Send an encoded `FfiRequest` and receive an encoded `FfiResponse`.
     *
     * The response bytes stay owned by the FFI until the returned handle is passed to
     * [livekit_ffi_drop_handle]; reading after the drop is a use-after-free. Returns
     * [INVALID_HANDLE] when the request could not be decoded or handled.
     */
    fun livekit_ffi_request(data: Pointer, len: Long, resPtr: PointerByReference, resLen: LongByReference): Long

    fun livekit_ffi_drop_handle(handleId: Long): Boolean

    fun livekit_ffi_dispose()

    companion object {
        const val INVALID_HANDLE = 0L

        /**
         * JNA extracts the library from the classpath under its platform resource prefix
         * (`linux-x86-64/liblivekit_ffi.so` and friends), which is where the build's
         * `downloadFfiNatives` task puts them.
         */
        val INSTANCE: FfiLibrary by lazy {
            Native.load("livekit_ffi", FfiLibrary::class.java)
        }
    }
}

/** `void (*)(const uint8_t *data, size_t len)` — an encoded `FfiEvent`. */
internal fun interface FfiEventCallback : Callback {
    fun invoke(data: Pointer, len: Long)
}
