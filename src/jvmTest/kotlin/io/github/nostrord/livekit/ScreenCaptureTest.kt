package io.github.nostrord.livekit

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Grabs the real screen and pushes it through the real encoder. A headless build machine has
 * no display, which is a legitimate outcome and skips rather than fails.
 */
class ScreenCaptureTest {
    @Test
    fun `a screen grab reaches the encoder as a frame of the requested size`() {
        val capture = ScreenCapture.ofPrimaryDisplay(Rectangle(0, 0, 64, 48)) ?: return

        assertEquals(64, capture.width)
        assertEquals(48, capture.height)

        val source = VideoSource.create(capture.width, capture.height, isScreencast = true)
        repeat(2) { index -> capture.captureInto(source, timestampUs = index * 100_000L) }
    }

    @Test
    fun `the primary display reports a usable size`() {
        val capture = ScreenCapture.ofPrimaryDisplay() ?: return
        assertTrue(capture.width > 0 && capture.height > 0, "a display should have a size")
    }
}
