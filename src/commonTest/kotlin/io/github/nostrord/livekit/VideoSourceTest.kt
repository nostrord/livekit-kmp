package io.github.nostrord.livekit

import io.github.nostrord.livekit.ffi.NativeMemory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Video frames cross the FFI as raw addresses rather than protobuf bytes, so the memory piece
 * is where a mistake turns into a crash or a leak instead of a failed request. These cover it
 * without a room: a source can be created, frames can be pushed into the real encoder, and the
 * allocate/write/read cycle round-trips.
 */
class VideoSourceTest {
    @Test
    fun `native memory round-trips a frame`() {
        val pixels = ByteArray(64) { (it * 3).toByte() }
        val address = NativeMemory.allocate(pixels.size)
        try {
            assertNotEquals(0L, address, "an allocation should return a real address")
            NativeMemory.write(address, pixels)
            assertContentEquals(pixels, NativeMemory.read(address, pixels.size))
        } finally {
            NativeMemory.free(address)
        }
    }

    @Test
    fun `a source accepts frames pushed into the real encoder`() {
        val source = VideoSource.create(width = 32, height = 24, isScreencast = false)
        val frame = ByteArray(32 * 24 * 4) { 0x7F }

        // Timestamps advance: a frozen clock makes the encoder treat frames as instantaneous.
        repeat(3) { index ->
            source.capture(width = 32, height = 24, rgba = frame, timestampUs = index * 33_333L)
        }
    }

    @Test
    fun `a frame smaller than its declared size is rejected before reaching native code`() {
        val source = VideoSource.create(width = 32, height = 24, isScreencast = false)

        // Passing this through would have the encoder read past the buffer.
        assertFailsWith<IllegalArgumentException> {
            source.capture(width = 32, height = 24, rgba = ByteArray(16), timestampUs = 0)
        }
    }
}
