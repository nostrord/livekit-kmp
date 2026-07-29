package io.github.nostrord.livekit

import io.github.nostrord.livekit.ffi.FfiClient
import io.github.nostrord.livekit.ffi.FfiException
import livekit.proto.FfiRequest
import livekit.proto.GetAudioDevicesRequest
import livekit.proto.NewPlatformAudioRequest
import livekit.proto.SetPlayoutDeviceRequest
import livekit.proto.SetRecordingDeviceRequest

/**
 * An audio device: a microphone or a speaker.
 *
 * Prefer [id] over [index] when remembering a user's choice. Indices shift as devices come and
 * go; the platform GUID does not.
 */
data class AudioDevice(
    val index: Int,
    val name: String,
    val id: String?,
)

/** The microphones and speakers the platform currently exposes. */
data class AudioDevices(
    val microphones: List<AudioDevice>,
    val speakers: List<AudioDevice>,
)

/**
 * Platform audio devices, through WebRTC's Audio Device Module.
 *
 * The ADM owns capture and playback, so nothing here pumps PCM by hand: a track built on
 * [handle] records from the selected microphone, and received audio reaches the speakers on its
 * own. That also puts echo cancellation on the right side of the device loop, which hand-fed
 * frames cannot achieve.
 *
 * The ADM is shared: creating a second instance reuses the same underlying device, and the ADM
 * shuts down once every handle is released.
 */
class PlatformAudio private constructor(internal val handle: Long) {

    /** Enumerate the devices currently attached. */
    fun devices(): AudioDevices {
        val response = FfiClient.request(
            FfiRequest(get_audio_devices = GetAudioDevicesRequest(platform_audio_handle = handle)),
        ).get_audio_devices ?: throw FfiException("the FFI returned no device list")

        response.error?.takeIf { it.isNotBlank() }?.let { throw FfiException("could not list audio devices: $it") }
        return AudioDevices(
            microphones = response.recording_devices.map { it.toDevice() },
            speakers = response.playout_devices.map { it.toDevice() },
        )
    }

    /** Record from [device]. Takes effect on the next track that starts capturing. */
    fun selectMicrophone(device: AudioDevice) {
        val id = device.id ?: throw FfiException("device '${device.name}' has no stable id to select by")
        FfiClient.request(
            FfiRequest(set_recording_device = SetRecordingDeviceRequest(platform_audio_handle = handle, device_id = id)),
        )
    }

    /** Play through [device]. */
    fun selectSpeaker(device: AudioDevice) {
        val id = device.id ?: throw FfiException("device '${device.name}' has no stable id to select by")
        FfiClient.request(
            FfiRequest(set_playout_device = SetPlayoutDeviceRequest(platform_audio_handle = handle, device_id = id)),
        )
    }

    companion object {
        /**
         * Open the platform's audio devices.
         *
         * Throws [FfiException] where no ADM is available, which is how a headless or
         * sound-less machine reports itself.
         */
        fun open(): PlatformAudio {
            val response = FfiClient.request(FfiRequest(new_platform_audio = NewPlatformAudioRequest()))
                .new_platform_audio ?: throw FfiException("the FFI returned no platform audio handle")

            response.error?.takeIf { it.isNotBlank() }?.let { throw FfiException("could not open audio devices: $it") }
            val owned = response.platform_audio ?: throw FfiException("could not open audio devices")
            return PlatformAudio(owned.handle.id)
        }
    }
}

private fun livekit.proto.AudioDeviceInfo.toDevice() = AudioDevice(
    index = index,
    name = name,
    id = guid?.takeIf { it.isNotBlank() },
)
