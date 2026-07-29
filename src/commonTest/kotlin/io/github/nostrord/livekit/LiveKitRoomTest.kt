package io.github.nostrord.livekit

import io.github.nostrord.livekit.ffi.FfiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import livekit.proto.RoomOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Exercises the async request/callback correlation against the real FFI, with no LiveKit
 * server involved: a join that cannot succeed still has to travel out as a request, come back
 * as a `ConnectCallback` carrying the matching async id, and surface as a thrown error.
 *
 * A silently-broken correlation layer would hang here instead of failing, which is exactly the
 * failure mode worth catching.
 */
class LiveKitRoomTest {
    /** No retries and a short timeout: the point is the failure path, not waiting for it. */
    private val failFast = RoomOptions(
        auto_subscribe = true,
        join_retries = 0,
        connect_timeout_ms = 1_000,
    )

    @Test
    fun `a join that cannot succeed reports the error and leaves the room disconnected`() = runTest {
        val room = LiveKitRoom(CoroutineScope(Job()))

        assertFailsWith<FfiException> {
            room.connect(
                url = "ws://127.0.0.1:1/nowhere",
                token = "not-a-real-token",
                options = failFast,
            )
        }

        assertEquals(RoomState.Disconnected, room.state.value)
        assertEquals(emptyList(), room.participants.value)
    }

    @Test
    fun `disconnecting a room that never connected is a no-op`() = runTest {
        val room = LiveKitRoom(CoroutineScope(Job()))
        room.disconnect()
        assertEquals(RoomState.Disconnected, room.state.value)
    }
}
