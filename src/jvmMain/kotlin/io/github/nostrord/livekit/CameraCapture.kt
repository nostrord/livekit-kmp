package io.github.nostrord.livekit

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Camera capture for a [VideoSource], through an `ffmpeg` subprocess.
 *
 * The JDK has no camera API and the FFI does not expose libwebrtc's capturers, so the camera
 * has to come from somewhere. ffmpeg is the pragmatic somewhere: it speaks every platform's
 * capture stack (v4l2 / AVFoundation / DirectShow), and reading rawvideo RGBA off its stdout
 * needs no bindings at all. The cost is a runtime dependency on an installed ffmpeg — absent
 * one, [open] returns null and the caller reports camera unavailable, the same graceful shape
 * as everything else optional on desktop.
 */
class CameraCapture private constructor(
    private val process: Process,
    val width: Int,
    val height: Int,
) {
    @Volatile
    private var running = true
    private var pump: Thread? = null

    /**
     * Start reading frames, invoking [onFrame] with tightly packed RGBA and a microsecond
     * timestamp until [stop] or the camera goes away. The callback runs on the pump thread;
     * pushing into a [VideoSource] there is fine, blocking is not.
     */
    fun start(onFrame: (rgba: ByteArray, timestampUs: Long) -> Unit) {
        check(pump == null) { "capture already started" }
        val frameSize = width * height * 4
        val startedNs = System.nanoTime()
        pump = thread(name = "camera-capture", isDaemon = true) {
            val input = process.inputStream
            // Reused: a fresh 1-2 MB frame buffer per tick would be allocation churn for
            // nothing, and the consumer copies into native memory anyway.
            val frame = ByteArray(frameSize)
            while (running) {
                var read = 0
                while (read < frameSize) {
                    val n = try {
                        input.read(frame, read, frameSize - read)
                    } catch (e: Exception) {
                        -1
                    }
                    if (n < 0) return@thread // ffmpeg exited: device unplugged or stop()
                    read += n
                }
                onFrame(frame, (System.nanoTime() - startedNs) / 1_000)
            }
        }
    }

    fun stop() {
        running = false
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        pump?.join(2_000)
        pump = null
    }

    companion object {
        /** Whether ffmpeg answers on this machine. Checked once; installing mid-run is rare. */
        val ffmpegAvailable: Boolean by lazy {
            runCatching {
                ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(3, TimeUnit.SECONDS)
            }.getOrDefault(false)
        }

        /**
         * Open the default camera at [width]x[height].
         *
         * Null when ffmpeg is missing or no camera input exists — states to report, not throw
         * over. A camera that exists but fails to deliver shows up as the pump ending early.
         */
        fun open(width: Int, height: Int, fps: Int = 24): CameraCapture? {
            if (!ffmpegAvailable) return null
            val input = defaultCameraArgs() ?: return null
            val command = buildList {
                add("ffmpeg")
                add("-hide_banner")
                add("-loglevel")
                add("error")
                addAll(input)
                // Fill the requested size: scale up to cover, then centre-crop, so a 4:3
                // camera fills a 16:9 tile instead of stretching faces.
                add("-vf")
                add("scale=$width:$height:force_original_aspect_ratio=increase,crop=$width:$height")
                add("-r")
                add(fps.toString())
                add("-pix_fmt")
                add("rgba")
                add("-f")
                add("rawvideo")
                add("-")
            }
            val process = runCatching {
                ProcessBuilder(command)
                    // Discarded rather than inherited: ffmpeg chats on stderr, and a filled,
                    // unread pipe would wedge the capture loop.
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }.getOrNull() ?: return null
            return CameraCapture(process, width, height)
        }

        /** The ffmpeg input arguments for this platform's default camera, or null if none. */
        internal fun defaultCameraArgs(): List<String>? {
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.startsWith("linux") -> firstV4l2Device()?.let { listOf("-f", "v4l2", "-i", it) }
                os.startsWith("mac") -> listOf("-f", "avfoundation", "-framerate", "30", "-i", "0")
                os.startsWith("windows") -> firstDshowCamera()?.let { listOf("-f", "dshow", "-i", "video=$it") }
                else -> null
            }
        }

        private fun firstV4l2Device(): String? =
            File("/dev").listFiles { file -> file.name.startsWith("video") }
                ?.minByOrNull { it.name }
                ?.absolutePath

        /** dshow only enumerates through ffmpeg itself; the list arrives on stderr. */
        private fun firstDshowCamera(): String? = runCatching {
            val process = ProcessBuilder(
                "ffmpeg", "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(5, TimeUnit.SECONDS)
            parseDshowCamera(output)
        }.getOrNull()
    }
}

/**
 * First video device name in `-list_devices` output. Lines look like
 * `[dshow @ ...] "Integrated Camera" (video)`; audio devices carry `(audio)` instead.
 */
internal fun parseDshowCamera(output: String): String? =
    output.lineSequence()
        .filter { "(video)" in it }
        .mapNotNull { line ->
            val start = line.indexOf('"')
            val end = line.indexOf('"', start + 1)
            if (start >= 0 && end > start) line.substring(start + 1, end) else null
        }
        .firstOrNull()
