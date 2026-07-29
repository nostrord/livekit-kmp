package io.github.nostrord.livekit.ffi

/**
 * Raw memory access for the frame APIs.
 *
 * Video frames cross the FFI as `data_ptr` addresses rather than byte fields — a 1080p frame
 * copied through protobuf on every tick would be pure waste — so reading and writing them is
 * unavoidably platform work, even though everything around it is shared.
 */
internal expect object NativeMemory {
    /** Copy [size] bytes out of [address]. The caller must know the address is still valid. */
    fun read(address: Long, size: Int): ByteArray

    /** Allocate [size] bytes and return the address. Must be released with [free]. */
    fun allocate(size: Int): Long

    fun write(address: Long, data: ByteArray)

    fun free(address: Long)
}

/** Runs [block] with a freshly allocated buffer holding [data], freeing it afterwards. */
internal inline fun <T> withNativeCopy(data: ByteArray, block: (address: Long) -> T): T {
    val address = NativeMemory.allocate(data.size)
    return try {
        NativeMemory.write(address, data)
        block(address)
    } finally {
        NativeMemory.free(address)
    }
}
