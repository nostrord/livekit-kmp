package io.github.nostrord.livekit

import io.github.nostrord.livekit.ffi.FfiClient
import io.github.nostrord.livekit.ffi.FfiException
import io.github.nostrord.livekit.ffi.NativeMemory
import io.github.nostrord.livekit.ffi.withNativeCopy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.takeWhile
import livekit.proto.CaptureVideoFrameRequest
import livekit.proto.FfiRequest
import livekit.proto.LocalTrackMuteRequest
import livekit.proto.NewVideoSourceRequest
import livekit.proto.NewVideoStreamRequest
import livekit.proto.VideoBufferInfo
import livekit.proto.VideoBufferType
import livekit.proto.VideoConvertRequest
import livekit.proto.VideoRotation
import livekit.proto.VideoSourceResolution
import livekit.proto.VideoSourceType
import livekit.proto.VideoStreamType

/**
 * One decoded frame, as tightly packed RGBA.
 *
 * RGBA rather than the I420 the wire carries: every UI toolkit draws RGBA, and the conversion
 * is done by the FFI's own converter, which is faster than any Kotlin YUV loop and already
 * ships in the binary.
 */
data class VideoFrame(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
    val rotationDegrees: Int,
) {
    // ByteArray identity would make two identical frames unequal, and comparing megabytes of
    // pixels on every emission is worse. Frames are values in a stream, not map keys.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = width * 31 + height
}

/**
 * Frames arriving from a remote video track.
 *
 * The FFI hands each frame over as a native pointer plus a handle it owns. The bytes are only
 * valid until that handle is dropped, so every frame is copied out and released immediately;
 * holding a [VideoFrame] is safe, holding the FFI's buffer is not.
 */
internal fun videoStream(trackHandle: Long): Flow<VideoFrame> {
    val stream = FfiClient.request(
        FfiRequest(
            new_video_stream = NewVideoStreamRequest(
                track_handle = trackHandle,
                type = VideoStreamType.VIDEO_STREAM_NATIVE,
            ),
        ),
    ).new_video_stream?.stream ?: throw FfiException("could not open the video stream")

    val streamHandle = stream.handle.id
    return FfiClient.events
        .mapNotNull { it.video_stream_event?.takeIf { event -> event.stream_handle == streamHandle } }
        .takeWhile { it.eos == null }
        .mapNotNull { event -> event.frame_received?.let { it.buffer.toRgbaFrame(it.rotation.toDegrees()) } }
}

/**
 * Convert to RGBA through the FFI, copy the pixels out, then release both buffers.
 *
 * Both are `Owned`, so both leak without the drops — and at 30 fps that is tens of megabytes a
 * second, which is why the copy happens eagerly instead of handing the address upwards.
 */
private fun livekit.proto.OwnedVideoBuffer.toRgbaFrame(rotationDegrees: Int): VideoFrame? {
    try {
        val converted = FfiClient.request(
            FfiRequest(video_convert = VideoConvertRequest(buffer = info, dst_type = VideoBufferType.RGBA)),
        ).video_convert ?: return null

        converted.error?.takeIf { it.isNotBlank() }?.let { throw FfiException("could not convert a video frame: $it") }
        val rgba = converted.buffer ?: return null
        return try {
            VideoFrame(
                width = rgba.info.width,
                height = rgba.info.height,
                rgba = NativeMemory.read(rgba.info.data_ptr, rgba.info.width * rgba.info.height * 4),
                rotationDegrees = rotationDegrees,
            )
        } finally {
            FfiClient.dropHandle(rgba.handle.id)
        }
    } finally {
        FfiClient.dropHandle(handle.id)
    }
}

private fun VideoRotation.toDegrees(): Int = when (this) {
    VideoRotation.VIDEO_ROTATION_0 -> 0
    VideoRotation.VIDEO_ROTATION_90 -> 90
    VideoRotation.VIDEO_ROTATION_180 -> 180
    VideoRotation.VIDEO_ROTATION_270 -> 270
}

/**
 * A video track this client publishes, fed frame by frame.
 *
 * Unlike audio, there is no device module behind this: the FFI encodes and sends whatever is
 * pushed, and where the pixels come from — a camera, the screen, a rendering — is the caller's
 * problem. [resolution] only sizes the encoder's simulcast layers; frames of other sizes are
 * still accepted.
 */
class VideoSource internal constructor(internal val handle: Long, private val isScreencast: Boolean) {

    /**
     * Push one RGBA frame.
     *
     * [timestampUs] should advance with real capture time; a frozen clock makes the encoder
     * treat every frame as instantaneous and the receiver's playout stutter.
     */
    fun capture(width: Int, height: Int, rgba: ByteArray, timestampUs: Long) {
        require(rgba.size >= width * height * 4) {
            "an RGBA frame of ${width}x$height needs ${width * height * 4} bytes, got ${rgba.size}"
        }
        withNativeCopy(rgba) { address ->
            FfiClient.request(
                FfiRequest(
                    capture_video_frame = CaptureVideoFrameRequest(
                        source_handle = handle,
                        buffer = VideoBufferInfo(
                            type = VideoBufferType.RGBA,
                            width = width,
                            height = height,
                            data_ptr = address,
                            stride = width * 4,
                        ),
                        timestamp_us = timestampUs,
                        rotation = VideoRotation.VIDEO_ROTATION_0,
                    ),
                ),
            )
        }
    }

    internal companion object {
        fun create(width: Int, height: Int, isScreencast: Boolean): VideoSource {
            val source = FfiClient.request(
                FfiRequest(
                    new_video_source = NewVideoSourceRequest(
                        type = VideoSourceType.VIDEO_SOURCE_NATIVE,
                        resolution = VideoSourceResolution(width = width, height = height),
                        is_screencast = isScreencast,
                    ),
                ),
            ).new_video_source?.source ?: throw FfiException("could not create the video source")
            return VideoSource(source.handle.id, isScreencast)
        }
    }
}

/**
 * A published video track: the [source] frames go into, plus mute control.
 *
 * Muting keeps the publication alive, so a camera toggled back on skips the renegotiation
 * round trip and the participant never disappears as a publisher. Stop feeding frames while
 * muted; the encoder happily encodes a frozen image otherwise.
 */
class VideoPublication internal constructor(
    val source: VideoSource,
    private val trackHandle: Long,
) {
    fun setMuted(muted: Boolean) {
        FfiClient.request(
            FfiRequest(local_track_mute = LocalTrackMuteRequest(track_handle = trackHandle, mute = muted)),
        )
    }
}
