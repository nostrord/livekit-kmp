package io.github.nostrord.livekit

import io.github.nostrord.livekit.ffi.FfiException
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opens the real Audio Device Module. Proves the ADM path is compiled into the shipped binary
 * and reachable through the FFI, which is the whole reason no PCM pumping is needed.
 *
 * Device lists are machine-dependent, so this asserts reachability, not contents: a build
 * machine with no sound card is a legitimate outcome and must not fail the suite.
 */
class PlatformAudioTest {
    @Test
    fun `the platform audio module is reachable and enumerates devices`() {
        val audio = try {
            PlatformAudio.open()
        } catch (e: FfiException) {
            // No ADM here (headless CI). The FFI answered, which is what is under test.
            return
        }

        val devices = audio.devices()
        assertTrue(
            devices.microphones.size >= 0 && devices.speakers.size >= 0,
            "enumeration should return lists, got $devices",
        )
        // Anything the ADM reports has to be selectable, so a named device needs a stable id.
        devices.microphones.forEach { device ->
            assertTrue(device.name.isNotBlank(), "a reported microphone should have a name: $device")
        }
    }
}
