package io.github.nostrord.livekit

import io.github.nostrord.livekit.ffi.FfiClient
import io.github.nostrord.livekit.ffi.FfiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import livekit.proto.AudioSourceOptions
import livekit.proto.AudioSourceType
import livekit.proto.ConnectRequest
import livekit.proto.CreateAudioTrackRequest
import livekit.proto.DisconnectRequest
import livekit.proto.FfiRequest
import livekit.proto.LocalTrackMuteRequest
import livekit.proto.NewAudioSourceRequest
import livekit.proto.PublishTrackRequest
import livekit.proto.RoomOptions
import livekit.proto.TrackPublishOptions
import livekit.proto.TrackSource

/** Where the connection to a room currently stands. */
enum class RoomState { Disconnected, Connecting, Connected, Reconnecting }

/**
 * One participant in the room.
 *
 * [identity] comes from the JWT `sub` and is how a caller maps a participant back to its own
 * user model. LiveKit keeps it unique within a room, which is exactly why NIP-29 has the relay
 * append a random suffix to the pubkey: that lets one user join twice as two distinct
 * identities rather than the second connection displacing the first.
 */
data class Participant(
    val sid: String,
    val identity: String,
    val name: String,
    val isLocal: Boolean = false,
    val isSpeaking: Boolean = false,
)

/**
 * A LiveKit room.
 *
 * Wraps the FFI's request/callback protocol in flows: [state] and [participants] track what the
 * server reports, updated from the room's event stream. One instance per room; create a new one
 * after [disconnect].
 *
 * Media is deliberately absent here — the FFI does no device I/O, so capture and playback are
 * the caller's, fed through the audio and video source APIs.
 */
class LiveKitRoom(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(RoomState.Disconnected)
    val state: StateFlow<RoomState> = _state.asStateFlow()

    private val _participants = MutableStateFlow<List<Participant>>(emptyList())

    /** Everyone in the room, local participant first. */
    val participants: StateFlow<List<Participant>> = _participants.asStateFlow()

    /** The FFI's handle for this room; also the key its events are tagged with. */
    private var roomHandle: Long? = null
    private var localParticipantHandle: Long? = null
    private var eventJob: Job? = null

    /** The published microphone track, once [setMicrophoneEnabled] has turned it on. */
    private var microphoneTrackHandle: Long? = null

    private val _microphoneEnabled = MutableStateFlow(false)

    /** Whether this client is currently sending audio. */
    val microphoneEnabled: StateFlow<Boolean> = _microphoneEnabled.asStateFlow()

    /**
     * Join the room [token] grants access to, at [url] (the LiveKit server's `wss://` URL).
     *
     * Suspends until the server accepts or rejects the join. Nothing is published on connect:
     * the microphone and camera stay off until their tracks are added.
     */
    suspend fun connect(url: String, token: String, options: RoomOptions = defaultOptions) {
        check(roomHandle == null) { "already connected; create a new LiveKitRoom per room" }
        _state.value = RoomState.Connecting
        try {
            val result = FfiClient.requestAsync(
                request = FfiRequest(connect = ConnectRequest(url = url, token = token, options = options)),
                asyncId = { it.connect?.async_id },
                callback = { event, id -> event.connect?.takeIf { it.async_id == id } },
            )
            result.error?.let { throw FfiException("could not join the room: $it") }
            val joined = result.result ?: throw FfiException("the FFI reported neither a room nor an error")

            roomHandle = joined.room.handle.id
            localParticipantHandle = joined.local_participant.handle.id
            _participants.value = buildList {
                add(joined.local_participant.info.toParticipant(isLocal = true))
                joined.participants.forEach { add(it.participant.info.toParticipant(isLocal = false)) }
            }
            _state.value = RoomState.Connected
            watchEvents()
        } catch (e: Throwable) {
            _state.value = RoomState.Disconnected
            roomHandle = null
            localParticipantHandle = null
            throw e
        }
    }

    /**
     * Start or stop sending microphone audio.
     *
     * The first enable publishes a track backed by [audio]; later toggles mute and unmute that
     * same publication rather than republishing, which avoids a renegotiation round trip and
     * keeps the participant visible as a publisher throughout.
     *
     * Capture itself belongs to WebRTC's device module, so no PCM is pumped from Kotlin and
     * echo cancellation runs on the right side of the device loop.
     */
    suspend fun setMicrophoneEnabled(enabled: Boolean, audio: PlatformAudio) {
        val participant = localParticipantHandle ?: throw FfiException("join a room before publishing audio")

        val track = microphoneTrackHandle ?: run {
            if (!enabled) return
            publishMicrophone(participant, audio).also { microphoneTrackHandle = it }
        }
        FfiClient.request(
            FfiRequest(local_track_mute = LocalTrackMuteRequest(track_handle = track, mute = !enabled)),
        )
        _microphoneEnabled.value = enabled
    }

    private suspend fun publishMicrophone(participant: Long, audio: PlatformAudio): Long {
        val source = FfiClient.request(
            FfiRequest(
                new_audio_source = NewAudioSourceRequest(
                    type = AudioSourceType.AUDIO_SOURCE_PLATFORM,
                    platform_audio_handle = audio.handle,
                    // Let WebRTC clean up the signal: without these a speaker on a laptop
                    // feeds straight back into its own microphone.
                    options = AudioSourceOptions(
                        echo_cancellation = true,
                        noise_suppression = true,
                        auto_gain_control = true,
                    ),
                ),
            ),
        ).new_audio_source?.source ?: throw FfiException("could not open the microphone")

        val track = FfiClient.request(
            FfiRequest(
                create_audio_track = CreateAudioTrackRequest(name = "microphone", source_handle = source.handle.id),
            ),
        ).create_audio_track?.track ?: throw FfiException("could not create the microphone track")

        val published = FfiClient.requestAsync(
            request = FfiRequest(
                publish_track = PublishTrackRequest(
                    local_participant_handle = participant,
                    track_handle = track.handle.id,
                    options = TrackPublishOptions(source = TrackSource.SOURCE_MICROPHONE),
                ),
            ),
            asyncId = { it.publish_track?.async_id },
            callback = { event, id -> event.publish_track?.takeIf { it.async_id == id } },
        )
        published.error?.takeIf { it.isNotBlank() }?.let { throw FfiException("could not publish audio: $it") }
        return track.handle.id
    }

    /** Leave the room and release its FFI handle. Idempotent. */
    suspend fun disconnect() {
        val handle = roomHandle ?: return
        roomHandle = null
        localParticipantHandle = null
        microphoneTrackHandle = null
        _microphoneEnabled.value = false
        eventJob?.cancel()
        eventJob = null
        try {
            FfiClient.requestAsync(
                request = FfiRequest(disconnect = DisconnectRequest(room_handle = handle)),
                asyncId = { it.disconnect?.async_id },
                callback = { event, id -> event.disconnect?.takeIf { it.async_id == id } },
            )
        } finally {
            _state.value = RoomState.Disconnected
            _participants.value = emptyList()
        }
    }

    /**
     * Track the room's own event stream.
     *
     * Events are filtered by room handle: the FFI multiplexes every room through one global
     * callback, so an app in two rooms would otherwise cross their state.
     */
    private fun watchEvents() {
        eventJob = scope.launch {
            FfiClient.events.collect { event ->
                val roomEvent = event.room_event ?: return@collect
                if (roomEvent.room_handle != roomHandle) return@collect
                apply(roomEvent)
            }
        }
    }

    private fun apply(event: livekit.proto.RoomEvent) {
        when {
            event.participant_connected != null -> {
                val joined = event.participant_connected!!.info.info.toParticipant(isLocal = false)
                _participants.update { current ->
                    // The server can resend a join; key on identity so a replay cannot duplicate.
                    current.filterNot { it.identity == joined.identity } + joined
                }
            }

            event.participant_disconnected != null -> {
                val identity = event.participant_disconnected!!.participant_identity
                _participants.update { current -> current.filterNot { it.identity == identity } }
            }

            event.active_speakers_changed != null -> {
                val speaking = event.active_speakers_changed!!.participant_identities.toSet()
                _participants.update { current ->
                    current.map { it.copy(isSpeaking = it.identity in speaking) }
                }
            }

            event.disconnected != null -> {
                // Server-initiated close: the handle is dead, so drop straight to Disconnected
                // rather than waiting for a disconnect() that is never coming.
                roomHandle = null
                localParticipantHandle = null
                microphoneTrackHandle = null
                _microphoneEnabled.value = false
                _state.value = RoomState.Disconnected
                _participants.value = emptyList()
            }

            event.reconnecting != null -> _state.value = RoomState.Reconnecting
            event.reconnected != null -> _state.value = RoomState.Connected
        }
    }

    private fun MutableStateFlow<List<Participant>>.update(block: (List<Participant>) -> List<Participant>) {
        value = block(value)
    }

    private companion object {
        /**
         * Auto-subscribe so remote tracks arrive without a round trip, adaptive stream and
         * dynacast so an unrendered or unheard track stops costing bandwidth.
         */
        val defaultOptions = RoomOptions(
            auto_subscribe = true,
            adaptive_stream = true,
            dynacast = true,
        )
    }
}

private fun livekit.proto.ParticipantInfo.toParticipant(isLocal: Boolean) = Participant(
    sid = sid ?: "",
    identity = identity,
    name = name,
    isLocal = isLocal,
)
