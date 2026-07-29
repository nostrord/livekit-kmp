package io.github.nostrord.livekit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import livekit.proto.ActiveSpeakersChanged
import livekit.proto.DisconnectReason
import livekit.proto.FfiOwnedHandle
import livekit.proto.OwnedParticipant
import livekit.proto.OwnedTrack
import livekit.proto.ParticipantConnected
import livekit.proto.ParticipantDisconnected
import livekit.proto.ParticipantInfo
import livekit.proto.ParticipantKind
import livekit.proto.ParticipantState
import livekit.proto.RoomEvent
import livekit.proto.StreamState
import livekit.proto.TrackInfo
import livekit.proto.TrackKind
import livekit.proto.TrackMuted
import livekit.proto.TrackSubscribed
import livekit.proto.TrackUnsubscribed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives the room's event reducer with synthetic events. The FFI multiplexes real rooms
 * through one global callback and needs a live server, so feeding the reducer directly is the
 * only way to cover joins, subscriptions, mutes and leaves deterministically.
 */
class RoomEventReducerTest {
    private val room = LiveKitRoom(CoroutineScope(Job()))
    private val alice = "alice__abc123"

    private fun participantInfo(identity: String) = ParticipantInfo(
        sid = "PA_$identity",
        name = "Alice",
        identity = identity,
        metadata = "",
        kind = ParticipantKind.PARTICIPANT_KIND_STANDARD,
        disconnect_reason = DisconnectReason.UNKNOWN_REASON,
        state = ParticipantState.PARTICIPANT_STATE_ACTIVE,
        joined_at = 0,
        client_protocol = 0,
    )

    private fun audioTrack(sid: String, muted: Boolean = false) = OwnedTrack(
        handle = FfiOwnedHandle(id = 1),
        info = TrackInfo(
            sid = sid,
            name = "microphone",
            kind = TrackKind.KIND_AUDIO,
            stream_state = StreamState.STATE_ACTIVE,
            muted = muted,
            remote = true,
        ),
    )

    private fun join() = room.apply(
        RoomEvent(
            room_handle = 1L,
            participant_connected = ParticipantConnected(
                info = OwnedParticipant(handle = FfiOwnedHandle(id = 2), info = participantInfo(alice)),
            ),
        ),
    )

    @Test
    fun `a join adds the participant, silent until a track arrives`() {
        join()

        val participant = assertNotNull(room.participants.value.singleOrNull())
        assertEquals(alice, participant.identity)
        assertFalse(participant.audioSubscribed, "nobody is audible before their track is subscribed")
    }

    @Test
    fun `a repeated join does not duplicate the participant`() {
        join()
        join()

        assertEquals(1, room.participants.value.size)
    }

    @Test
    fun `subscribing to audio makes the participant audible`() {
        join()
        room.apply(
            RoomEvent(
                room_handle = 1L,
                track_subscribed = TrackSubscribed(participant_identity = alice, track = audioTrack("TR_1")),
            ),
        )

        val participant = assertNotNull(room.participants.value.singleOrNull())
        assertTrue(participant.audioSubscribed)
        assertFalse(participant.audioMuted)
    }

    @Test
    fun `a muted publication is subscribed but silent`() {
        join()
        room.apply(
            RoomEvent(
                room_handle = 1L,
                track_subscribed = TrackSubscribed(participant_identity = alice, track = audioTrack("TR_1")),
            ),
        )
        room.apply(
            RoomEvent(room_handle = 1L, track_muted = TrackMuted(participant_identity = alice, track_sid = "TR_1")),
        )

        val participant = assertNotNull(room.participants.value.singleOrNull())
        assertTrue(participant.audioSubscribed, "the track is still there, it just carries silence")
        assertTrue(participant.audioMuted)
    }

    @Test
    fun `unsubscribing clears the speaking state too`() {
        join()
        room.apply(
            RoomEvent(
                room_handle = 1L,
                track_subscribed = TrackSubscribed(participant_identity = alice, track = audioTrack("TR_1")),
            ),
        )
        room.apply(
            RoomEvent(
                room_handle = 1L,
                active_speakers_changed = ActiveSpeakersChanged(participant_identities = listOf(alice)),
            ),
        )
        assertTrue(assertNotNull(room.participants.value.singleOrNull()).isSpeaking)

        room.apply(
            RoomEvent(
                room_handle = 1L,
                track_unsubscribed = TrackUnsubscribed(participant_identity = alice, track_sid = "TR_1"),
            ),
        )

        val participant = assertNotNull(room.participants.value.singleOrNull())
        assertFalse(participant.audioSubscribed)
        assertFalse(participant.isSpeaking, "a participant with no audio cannot be left mid-sentence")
    }

    @Test
    fun `active speakers only mark the identities the server listed`() {
        join()
        room.apply(
            RoomEvent(
                room_handle = 1L,
                active_speakers_changed = ActiveSpeakersChanged(participant_identities = listOf("someone-else")),
            ),
        )

        assertFalse(assertNotNull(room.participants.value.singleOrNull()).isSpeaking)
    }

    @Test
    fun `a leave removes the participant`() {
        join()
        room.apply(
            RoomEvent(
                room_handle = 1L,
                participant_disconnected = ParticipantDisconnected(
                    participant_identity = alice,
                    disconnect_reason = DisconnectReason.CLIENT_INITIATED,
                ),
            ),
        )

        assertEquals(emptyList(), room.participants.value)
    }
}
