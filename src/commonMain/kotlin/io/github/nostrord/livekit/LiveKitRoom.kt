package io.github.nostrord.livekit

import io.github.nostrord.livekit.ffi.FfiClient
import io.github.nostrord.livekit.ffi.FfiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import livekit.proto.AudioSourceOptions
import livekit.proto.AudioSourceType
import livekit.proto.ConnectRequest
import livekit.proto.ContinualGatheringPolicy
import livekit.proto.CreateAudioTrackRequest
import livekit.proto.CreateVideoTrackRequest
import livekit.proto.DisconnectReason
import livekit.proto.DisconnectRequest
import livekit.proto.FfiRequest
import livekit.proto.IceTransportType
import livekit.proto.LocalTrackMuteRequest
import livekit.proto.NewAudioSourceRequest
import livekit.proto.PublishTrackRequest
import livekit.proto.RoomOptions
import livekit.proto.RtcConfig
import livekit.proto.TrackKind
import livekit.proto.TrackPublishOptions
import livekit.proto.TrackSource

/** Where the connection to a room currently stands. */
enum class RoomState { Disconnected, Connecting, Connected, Reconnecting }

/**
 * Why a room ended.
 *
 * The difference matters to a caller deciding what to do next: [ConnectionLost] is worth
 * rejoining, [Removed] and [DuplicateIdentity] are not — the second one comes back the moment
 * the same identity joins from somewhere else, so retrying just fights the other session.
 */
enum class DisconnectCause {
    ClientLeft,
    DuplicateIdentity,
    Removed,
    RoomClosed,
    ServerShutdown,
    JoinFailure,
    ConnectionLost,
    Unknown,
}

/**
 * One participant in the room.
 *
 * [identity] comes from the JWT `sub` and is how a caller maps a participant back to its own
 * user model. LiveKit keeps it unique within a room, which is exactly why NIP-29 has the relay
 * append a random suffix to the pubkey: that lets one user join twice as two distinct
 * identities rather than the second connection displacing the first.
 *
 * [audioSubscribed] means their audio track is being received; combined with [audioMuted] it is
 * the difference between "not sending" and "sending silence", which a UI shows differently.
 * [videoSubscribed] is when [LiveKitRoom.videoFrames] has something to hand back.
 */
data class Participant(
    val sid: String,
    val identity: String,
    val name: String,
    val isLocal: Boolean = false,
    val isSpeaking: Boolean = false,
    val audioSubscribed: Boolean = false,
    val audioMuted: Boolean = false,
    val videoSubscribed: Boolean = false,
)

/**
 * A LiveKit room.
 *
 * Wraps the FFI's request/callback protocol in flows: [state] and [participants] track what the
 * server reports, updated from the room's event stream. One instance per room; create a new one
 * after [disconnect].
 *
 * Audio is handled by WebRTC's device module, so nothing here pumps PCM. Video is not.
 */
class LiveKitRoom(
    private val scope: CoroutineScope,
    /**
     * The platform's audio devices.
     *
     * Required to hear anyone: the ADM renders every subscribed remote track, and it only runs
     * while a handle is held. A room built without one joins deaf, which is the right shape
     * for a headless or video-only client but a bug anywhere else.
     */
    private val audio: PlatformAudio? = null,
) {
    private val _state = MutableStateFlow(RoomState.Disconnected)
    val state: StateFlow<RoomState> = _state.asStateFlow()

    private val _disconnectCause = MutableStateFlow<DisconnectCause?>(null)

    /** Why the room last ended, null while it has never been up. Cleared on [connect]. */
    val disconnectCause: StateFlow<DisconnectCause?> = _disconnectCause.asStateFlow()

    private val _participants = MutableStateFlow<List<Participant>>(emptyList())

    /** Everyone in the room, local participant first. */
    val participants: StateFlow<List<Participant>> = _participants.asStateFlow()

    /** The FFI's handle for this room; also the key its events are tagged with. */
    private var roomHandle: Long? = null
    private var localParticipantHandle: Long? = null
    private var eventJob: Job? = null

    /** The published microphone track, once [setMicrophoneEnabled] has turned it on. */
    private var microphoneTrackHandle: Long? = null

    /** Remote video tracks by participant identity, so [videoFrames] can find them. */
    private val remoteVideoTracks = mutableMapOf<String, Long>()

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
        _disconnectCause.value = null
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
    suspend fun setMicrophoneEnabled(enabled: Boolean) = onLiveEngine {
        val participant = localParticipantHandle ?: throw FfiException("join a room before publishing audio")
        val audio = audio ?: throw FfiException("this room was built without platform audio, so it cannot publish")

        val track = microphoneTrackHandle ?: run {
            if (!enabled) return@onLiveEngine
            publishMicrophone(participant, audio).also { microphoneTrackHandle = it }
        }
        FfiClient.request(
            FfiRequest(local_track_mute = LocalTrackMuteRequest(track_handle = track, mute = !enabled)),
        )
        _microphoneEnabled.value = enabled
    }

    /**
     * Publish a video track fed by the returned [VideoSource].
     *
     * There is no camera here on purpose: the JDK has no capture API, and libwebrtc's desktop
     * capturer is not exposed through the FFI, so the pixels are the caller's to produce.
     * [width] and [height] size the encoder's simulcast layers; frames of other sizes still go
     * through. Set [isScreencast] for screen content, which tells the encoder to favour detail
     * over frame rate.
     */
    suspend fun publishVideo(width: Int, height: Int, isScreencast: Boolean = false): VideoPublication = onLiveEngine {
        val participant = localParticipantHandle ?: throw FfiException("join a room before publishing video")
        val source = VideoSource.create(width, height, isScreencast)

        val track = FfiClient.request(
            FfiRequest(
                create_video_track = CreateVideoTrackRequest(
                    name = if (isScreencast) "screen" else "camera",
                    source_handle = source.handle,
                ),
            ),
        ).create_video_track?.track ?: throw FfiException("could not create the video track")

        val published = FfiClient.requestAsync(
            request = FfiRequest(
                publish_track = PublishTrackRequest(
                    local_participant_handle = participant,
                    track_handle = track.handle.id,
                    options = TrackPublishOptions(
                        source = if (isScreencast) TrackSource.SOURCE_SCREENSHARE else TrackSource.SOURCE_CAMERA,
                    ),
                ),
            ),
            asyncId = { it.publish_track?.async_id },
            callback = { event, id -> event.publish_track?.takeIf { it.async_id == id } },
        )
        published.error?.takeIf { it.isNotBlank() }?.let { throw FfiException("could not publish video: $it") }
        VideoPublication(source, track.handle.id)
    }

    /**
     * Run an FFI call that needs a live RTC engine, noticing when it does not have one.
     *
     * The engine can die without the FFI emitting a disconnect event — a failed ICE
     * negotiation ends as `engine is closed` on the next publish instead. Left alone, [state]
     * would keep claiming Connected for a room nobody can be heard in, so the failure is
     * treated as the disconnect it really is and the caller can rejoin.
     */
    private suspend inline fun <T> onLiveEngine(block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        if (e.message?.contains(ENGINE_CLOSED) == true) markDisconnected(DisconnectCause.ConnectionLost)
        throw e
    }

    private fun markDisconnected(cause: DisconnectCause) {
        roomHandle = null
        localParticipantHandle = null
        microphoneTrackHandle = null
        _microphoneEnabled.value = false
        _disconnectCause.value = cause
        _state.value = RoomState.Disconnected
        _participants.value = emptyList()
    }

    /**
     * Frames from [identity]'s video track, or null while they are not publishing one.
     *
     * The flow ends when the track goes away. Call again after a later subscription; the
     * participant list's own updates are the signal that there is something to call for.
     */
    fun videoFrames(identity: String): Flow<VideoFrame>? =
        remoteVideoTracks[identity]?.let { videoStream(it) }

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

    internal fun apply(event: livekit.proto.RoomEvent) {
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

            event.track_subscribed != null -> {
                val subscribed = event.track_subscribed!!
                when (subscribed.track.info.kind) {
                    TrackKind.KIND_AUDIO -> updateParticipant(subscribed.participant_identity) {
                        it.copy(audioSubscribed = true, audioMuted = subscribed.track.info.muted)
                    }

                    TrackKind.KIND_VIDEO -> {
                        remoteVideoTracks[subscribed.participant_identity] = subscribed.track.handle.id
                        updateParticipant(subscribed.participant_identity) { it.copy(videoSubscribed = true) }
                    }

                    else -> Unit
                }
            }

            event.track_unsubscribed != null -> {
                val identity = event.track_unsubscribed!!.participant_identity
                remoteVideoTracks.remove(identity)
                updateParticipant(identity) {
                    it.copy(audioSubscribed = false, audioMuted = false, isSpeaking = false, videoSubscribed = false)
                }
            }

            // Mute events carry no track kind, so they are applied to audio unconditionally.
            // A video-only mute would set a flag no audio UI reads, which is harmless; the
            // alternative is tracking every publication's kind for no gain here.
            event.track_muted != null -> {
                updateParticipant(event.track_muted!!.participant_identity) { it.copy(audioMuted = true) }
            }

            event.track_unmuted != null -> {
                updateParticipant(event.track_unmuted!!.participant_identity) { it.copy(audioMuted = false) }
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
                markDisconnected(event.disconnected!!.reason.toCause())
            }

            event.reconnecting != null -> _state.value = RoomState.Reconnecting
            event.reconnected != null -> _state.value = RoomState.Connected
        }
    }

    private fun updateParticipant(identity: String, block: (Participant) -> Participant) {
        _participants.update { current ->
            current.map { if (it.identity == identity) block(it) else it }
        }
    }

    private fun MutableStateFlow<List<Participant>>.update(block: (List<Participant>) -> List<Participant>) {
        value = block(value)
    }

    private companion object {
        /** What the FFI says when the RTC engine is gone and only a rejoin can fix it. */
        const val ENGINE_CLOSED = "engine is closed"

        /**
         * Auto-subscribe so remote tracks arrive without a round trip, adaptive stream and
         * dynacast so an unrendered or unheard track stops costing bandwidth.
         *
         * ICE gathers continually and over every transport, which is what survives a network
         * that moves under the connection: a VPN coming up, a laptop changing Wi-Fi. Gathering
         * once freezes the candidate set at join time, so the first path to break takes the
         * room down with it. TURN over TCP stays in the set for networks that drop UDP, and
         * the server's own ICE servers still arrive in the join response.
         */
        val defaultOptions = RoomOptions(
            auto_subscribe = true,
            adaptive_stream = true,
            dynacast = true,
            join_retries = 3,
            rtc_config = RtcConfig(
                ice_transport_type = IceTransportType.TRANSPORT_ALL,
                continual_gathering_policy = ContinualGatheringPolicy.GATHER_CONTINUALLY,
            ),
        )
    }
}

private fun DisconnectReason.toCause(): DisconnectCause = when (this) {
    DisconnectReason.CLIENT_INITIATED -> DisconnectCause.ClientLeft
    DisconnectReason.DUPLICATE_IDENTITY -> DisconnectCause.DuplicateIdentity
    DisconnectReason.PARTICIPANT_REMOVED -> DisconnectCause.Removed
    DisconnectReason.ROOM_DELETED, DisconnectReason.ROOM_CLOSED -> DisconnectCause.RoomClosed
    DisconnectReason.SERVER_SHUTDOWN -> DisconnectCause.ServerShutdown
    DisconnectReason.JOIN_FAILURE -> DisconnectCause.JoinFailure
    DisconnectReason.SIGNAL_CLOSE,
    DisconnectReason.CONNECTION_TIMEOUT,
    DisconnectReason.MEDIA_FAILURE,
    DisconnectReason.STATE_MISMATCH,
    DisconnectReason.MIGRATION,
    -> DisconnectCause.ConnectionLost

    else -> DisconnectCause.Unknown
}

private fun livekit.proto.ParticipantInfo.toParticipant(isLocal: Boolean) = Participant(
    sid = sid ?: "",
    identity = identity,
    name = name,
    isLocal = isLocal,
)
