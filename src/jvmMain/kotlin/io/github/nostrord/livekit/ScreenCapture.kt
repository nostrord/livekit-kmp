package io.github.nostrord.livekit

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

/**
 * Screen capture for a [VideoSource], using the JDK's own `Robot`.
 *
 * The JDK has no camera API and libwebrtc's desktop capturer is not exposed through the FFI,
 * so this is the one video source a desktop app gets without pulling in native dependencies.
 * `Robot` is a full-screen grab and readback per frame, which is fine for slides, a terminal or
 * a code review, and not fine for motion: expect single-digit to low-teens frames per second at
 * 1080p. Anything smoother needs a real capturer.
 */
class ScreenCapture private constructor(
    private val robot: Robot,
    private val area: Rectangle,
) {
    val width: Int get() = area.width
    val height: Int get() = area.height

    /** Reused so a 1080p grab does not allocate 8 MB per frame. */
    private val rgba = ByteArray(area.width * area.height * 4)

    /**
     * Grab the screen and push it to [source].
     *
     * [timestampUs] must advance with real time; the encoder reads it as capture time, and a
     * frozen clock makes the receiver's playout stutter.
     */
    fun captureInto(source: VideoSource, timestampUs: Long) {
        val image = robot.createScreenCapture(area)
        image.toRgba(rgba)
        source.capture(width = area.width, height = area.height, rgba = rgba, timestampUs = timestampUs)
    }

    companion object {
        /**
         * Capture the primary display, or [area] of it.
         *
         * Returns null on a headless JVM, which is a normal state for a server or a CI box
         * rather than an error worth throwing over.
         */
        fun ofPrimaryDisplay(area: Rectangle? = null): ScreenCapture? {
            if (GraphicsEnvironment.isHeadless()) return null
            val bounds = area ?: GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice.defaultConfiguration.bounds
            return runCatching { ScreenCapture(Robot(), bounds) }.getOrNull()
        }
    }
}

/**
 * Write [image] into [out] as tightly packed RGBA.
 *
 * `Robot` hands back TYPE_INT_RGB, whose backing array is directly readable; the per-pixel
 * `getRGB` path would be several times slower at video rates.
 */
private fun BufferedImage.toRgba(out: ByteArray) {
    val buffer = raster.dataBuffer
    if (buffer is DataBufferInt && (type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_INT_ARGB)) {
        val pixels = buffer.data
        var target = 0
        for (pixel in pixels) {
            out[target++] = (pixel shr 16 and 0xFF).toByte()
            out[target++] = (pixel shr 8 and 0xFF).toByte()
            out[target++] = (pixel and 0xFF).toByte()
            out[target++] = 0xFF.toByte()
        }
        return
    }
    // Any other layout: correctness over speed, since this path should not be reached.
    var target = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val pixel = getRGB(x, y)
            out[target++] = (pixel shr 16 and 0xFF).toByte()
            out[target++] = (pixel shr 8 and 0xFF).toByte()
            out[target++] = (pixel and 0xFF).toByte()
            out[target++] = 0xFF.toByte()
        }
    }
}
