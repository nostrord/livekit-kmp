package io.github.nostrord.livekit.ffi

import com.sun.jna.Memory
import com.sun.jna.Pointer

internal actual object NativeMemory {
    /**
     * Allocations handed out by [allocate], kept alive by address.
     *
     * A JNA [Memory] frees its native block when the GC collects it, so handing the FFI a bare
     * address and dropping the object would let the buffer vanish mid-frame. [free] is what
     * releases it.
     */
    private val live = mutableMapOf<Long, Memory>()

    actual fun read(address: Long, size: Int): ByteArray = Pointer(address).getByteArray(0, size)

    actual fun allocate(size: Int): Long {
        val memory = Memory(size.toLong().coerceAtLeast(1))
        val address = Pointer.nativeValue(memory)
        synchronized(live) { live[address] = memory }
        return address
    }

    actual fun write(address: Long, data: ByteArray) {
        Pointer(address).write(0, data, 0, data.size)
    }

    actual fun free(address: Long) {
        val memory = synchronized(live) { live.remove(address) }
        memory?.close()
    }
}
