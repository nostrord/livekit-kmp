package io.github.nostrord.livekit.ffi

import com.sun.jna.Memory
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

internal actual class FfiTransport actual constructor() {
    /**
     * Held for the process lifetime. A JNA callback is only reachable from native code, so
     * letting this reference go would leave the GC free to collect it and crash the next event.
     */
    private var callback: FfiEventCallback? = null

    actual fun start(sdk: String, sdkVersion: String, captureLogs: Boolean, onEvent: (ByteArray) -> Unit) {
        val cb = FfiEventCallback { data, len ->
            // Copy before returning: the buffer belongs to the caller and dies with this frame.
            onEvent(data.getByteArray(0, len.toInt()))
        }
        callback = cb
        FfiLibrary.INSTANCE.livekit_ffi_initialize(cb, captureLogs, sdk, sdkVersion)
    }

    actual fun request(request: ByteArray): ByteArray {
        val resPtr = PointerByReference()
        val resLen = LongByReference()

        // Memory, not a Kotlin ByteArray: the buffer needs a fixed native address for the call.
        val buffer = Memory(request.size.toLong().coerceAtLeast(1))
        try {
            buffer.write(0, request, 0, request.size)
            val handle = FfiLibrary.INSTANCE.livekit_ffi_request(buffer, request.size.toLong(), resPtr, resLen)
            if (handle == FfiLibrary.INVALID_HANDLE) {
                // The FFI logs the reason on its own side; nothing comes back across the ABI.
                throw FfiException("livekit-ffi rejected the request")
            }
            try {
                return resPtr.value.getByteArray(0, resLen.value.toInt())
            } finally {
                // The response bytes are owned by the FFI until the handle is dropped.
                FfiLibrary.INSTANCE.livekit_ffi_drop_handle(handle)
            }
        } finally {
            buffer.close()
        }
    }

    actual fun dispose() {
        FfiLibrary.INSTANCE.livekit_ffi_dispose()
        callback = null
    }
}
