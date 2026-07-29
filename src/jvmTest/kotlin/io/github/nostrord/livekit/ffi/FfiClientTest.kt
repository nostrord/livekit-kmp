package io.github.nostrord.livekit.ffi

import livekit.proto.FfiRequest
import livekit.proto.NewAudioResamplerRequest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves the whole native path end to end: the bundled `liblivekit_ffi` loads, a protobuf
 * request crosses the C ABI, and a decodable response comes back.
 *
 * `new_audio_resampler` is deliberate — it allocates a local object with no network, no
 * media devices and no permissions, so the test exercises the transport and nothing else.
 */
class FfiClientTest {
    @Test
    fun `a request crosses the C ABI and returns a decodable response`() {
        val response = FfiClient.request(
            FfiRequest(new_audio_resampler = NewAudioResamplerRequest()),
        )

        val resampler = assertNotNull(
            response.new_audio_resampler,
            "expected the new_audio_resampler arm to be set, got $response",
        )
        val handle = assertNotNull(resampler.resampler?.handle)
        assertTrue(handle.id > 0L, "the FFI should hand back a live object handle")
    }
}
