package io.github.nostrord.livekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The capture pipeline itself needs a physical camera, which a build machine may not have and
 * a test must not grab (webcams light an LED and other apps lose the device). What is covered
 * is the part that goes wrong quietly: picking the right device arguments per platform and
 * parsing ffmpeg's dshow listing, which is stderr prose rather than a format.
 */
class CameraCaptureTest {
    @Test
    fun `dshow parsing takes the first video device and skips audio ones`() {
        val output = """
            [dshow @ 0000015] "Integrated Camera" (video)
            [dshow @ 0000015]   Alternative name "@device_pnp_1"
            [dshow @ 0000015] "OBS Virtual Camera" (video)
            [dshow @ 0000015] "Microphone Array" (audio)
        """.trimIndent()

        assertEquals("Integrated Camera", parseDshowCamera(output))
    }

    @Test
    fun `dshow parsing survives a machine with only audio devices`() {
        val output = """[dshow @ 0000015] "Microphone Array" (audio)"""
        assertNull(parseDshowCamera(output))
    }

    @Test
    fun `dshow parsing survives empty output`() {
        assertNull(parseDshowCamera(""))
    }

    @Test
    fun `the default camera args match this platform's capture stack`() {
        val args = CameraCapture.defaultCameraArgs() ?: return // no camera here: legitimate
        val os = System.getProperty("os.name").lowercase()
        val expectedFormat = when {
            os.startsWith("linux") -> "v4l2"
            os.startsWith("mac") -> "avfoundation"
            else -> "dshow"
        }
        assertEquals(expectedFormat, args[args.indexOf("-f") + 1])
    }
}
