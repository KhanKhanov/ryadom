package ru.ryadom.shared.volunteer

import ru.ryadom.shared.api.HelpRequest
import ru.ryadom.shared.api.RequestStatus
import ru.ryadom.shared.api.ServerEvent
import ru.ryadom.shared.call.CallConnection
import ru.ryadom.shared.call.CallState
import ru.ryadom.shared.call.PeerPresence
import ru.ryadom.shared.client.UserError
import ru.ryadom.shared.testing.OTHER_REQUEST_ID
import ru.ryadom.shared.testing.REQUEST_ID
import ru.ryadom.shared.testing.callCredentials
import ru.ryadom.shared.testing.helpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Те же случаи, что в тестах кабинета на сайте (web/src/volunteer/volunteerState.test.ts).
class VolunteerStateTest {
    private val searching = helpRequest(RequestStatus.SEARCHING)
    private val other = helpRequest(RequestStatus.SEARCHING, id = OTHER_REQUEST_ID)
    private val accepted = helpRequest(RequestStatus.ACCEPTED)

    private fun incoming(request: HelpRequest = searching) = ServerEvent.RequestIncoming(request)

    /** Вызов принят, и собеседник уже появился в звонке. */
    private val talking =
        VolunteerState()
            .acceptSucceeded(accepted)
            .withCallState(REQUEST_ID, CallState(CallConnection.CONNECTED, PeerPresence.PRESENT))

    @Test
    fun incomingCallsAreAddedOnceAndAnnounced() {
        val state =
            VolunteerState()
                .withEvent(incoming())
                .withEvent(incoming())
                .withEvent(incoming(other))

        assertEquals(listOf(REQUEST_ID, OTHER_REQUEST_ID), state.incoming.map { it.id })
        assertEquals(Notice(2, VolunteerNotice.INCOMING), state.notice)
        assertTrue(state.ringing)
        assertEquals(VolunteerScreen.HOME, state.screen)
    }

    @Test
    fun incomingEventForClosedRequestIsIgnored() {
        assertEquals(emptyList(), VolunteerState().withEvent(incoming(helpRequest(RequestStatus.CANCELLED))).incoming)
    }

    @Test
    fun closedCallsAreRemovedWithTheirNotice() {
        val cases =
            listOf(
                ServerEvent.RequestTaken(accepted) to VolunteerNotice.TAKEN,
                // Волонтёр принял вызов на другом устройстве или в браузере.
                ServerEvent.RequestAccepted(accepted.copy(call = null)) to VolunteerNotice.ACCEPTED_ELSEWHERE,
                ServerEvent.RequestCancelled(helpRequest(RequestStatus.CANCELLED)) to VolunteerNotice.CANCELLED,
                ServerEvent.RequestNoAnswer(helpRequest(RequestStatus.NO_ANSWER)) to VolunteerNotice.NO_ANSWER,
            )
        for ((event, notice) in cases) {
            val state = VolunteerState().withEvent(incoming()).withEvent(event)

            assertEquals(emptyList(), state.incoming, "$event")
            assertEquals(notice, state.notice?.kind, "$event")
            assertFalse(state.ringing)
        }
    }

    @Test
    fun closedCallIsNotAnnouncedWhileBeingAccepted() {
        val state =
            VolunteerState()
                .withEvent(incoming())
                .acceptStarted(REQUEST_ID)
                .withEvent(ServerEvent.RequestTaken(accepted))

        assertEquals(emptyList(), state.incoming)
        assertEquals(VolunteerNotice.INCOMING, state.notice?.kind)
    }

    @Test
    fun acceptingStartsTheCallAndForgetsOtherCalls() {
        val state =
            VolunteerState()
                .withEvent(incoming())
                .withEvent(incoming(other))
                .acceptStarted(REQUEST_ID)
                .acceptSucceeded(accepted)

        assertEquals(VolunteerCall(REQUEST_ID, callCredentials), state.call)
        assertNull(state.accepting)
        assertEquals(emptyList(), state.incoming)
        assertEquals(VolunteerScreen.CALL, state.screen)
        assertFalse(state.ringing)
    }

    @Test
    fun failedAcceptRemovesClosedCall() {
        for ((reason, notice) in listOf(
            AcceptFailure.TAKEN to VolunteerNotice.TOO_LATE_TAKEN,
            AcceptFailure.CLOSED to VolunteerNotice.TOO_LATE_CLOSED,
        )) {
            val state =
                VolunteerState()
                    .withEvent(incoming())
                    .acceptStarted(REQUEST_ID)
                    .acceptFailed(REQUEST_ID, reason)

            assertEquals(emptyList(), state.incoming)
            assertNull(state.accepting)
            assertEquals(notice, state.notice?.kind)
        }
    }

    @Test
    fun acceptAnswerWithoutCallDataMeansTheCallIsClosed() {
        val state = VolunteerState().withEvent(incoming()).acceptStarted(REQUEST_ID).acceptSucceeded(helpRequest(RequestStatus.CANCELLED))

        assertNull(state.call)
        assertEquals(VolunteerNotice.TOO_LATE_CLOSED, state.notice?.kind)
    }

    @Test
    fun callStaysWhenAcceptCanBeRetried() {
        val state =
            VolunteerState()
                .withEvent(incoming())
                .acceptStarted(REQUEST_ID)
                .acceptFailed(REQUEST_ID, AcceptFailure.RETRY, UserError.NETWORK)

        assertEquals(1, state.incoming.size)
        assertNull(state.accepting)
        assertEquals(UserError.NETWORK, state.error)
    }

    @Test
    fun ownAcceptanceArrivingAsEventStaysQuiet() {
        val state =
            VolunteerState()
                .withEvent(incoming())
                .acceptStarted(REQUEST_ID)
                .withEvent(ServerEvent.RequestAccepted(accepted.copy(call = null)))
                .acceptSucceeded(accepted)

        assertEquals(REQUEST_ID, state.call?.requestId)
        assertEquals(VolunteerNotice.INCOMING, state.notice?.kind)
        assertSame(state, state.withEvent(ServerEvent.RequestAccepted(accepted.copy(call = null))))
    }

    @Test
    fun peerEndingTheCallAsksForRating() {
        val state = talking.withEvent(ServerEvent.RequestEnded(helpRequest(RequestStatus.ENDED)))

        assertNull(state.call)
        assertEquals(FinishedCall(REQUEST_ID, endedByPeer = true), state.finished)
        assertEquals(VolunteerNotice.CALL_ENDED_BY_PEER, state.notice?.kind)
        assertEquals(VolunteerScreen.RATING, state.screen)
    }

    @Test
    fun volunteerEndingTheCallAsksForRating() {
        val state = talking.endStarted(REQUEST_ID).callEnded(REQUEST_ID)

        assertNull(state.call)
        assertEquals(FinishedCall(REQUEST_ID, endedByPeer = false), state.finished)
    }

    @Test
    fun endEventBeforeTheAnswerToOwnEndDoesNotBlameThePeer() {
        val state =
            talking
                .endStarted(REQUEST_ID)
                .withEvent(ServerEvent.RequestEnded(helpRequest(RequestStatus.ENDED)))
                .callEnded(REQUEST_ID)

        assertEquals(FinishedCall(REQUEST_ID, endedByPeer = false), state.finished)
        assertNull(state.notice)
    }

    @Test
    fun noRatingWhenThePeerNeverJoined() {
        for (event in listOf(
            ServerEvent.RequestCancelled(helpRequest(RequestStatus.CANCELLED)),
            ServerEvent.RequestEnded(helpRequest(RequestStatus.ENDED)),
        )) {
            val state = VolunteerState().acceptSucceeded(accepted).withEvent(event)

            assertNull(state.call)
            assertNull(state.finished)
            assertEquals(VolunteerNotice.CALL_NOT_STARTED, state.notice?.kind)
        }
    }

    @Test
    fun noRatingWhenVolunteerEndsBeforeThePeerJoined() {
        val state = VolunteerState().acceptSucceeded(accepted).endStarted(REQUEST_ID).callEnded(REQUEST_ID)

        assertNull(state.finished)
        assertEquals(VolunteerNotice.CALL_ENDED, state.notice?.kind)
    }

    @Test
    fun nextCallStartsFresh() {
        val state =
            talking
                .endStarted(REQUEST_ID)
                .callEnded(REQUEST_ID)
                .acceptSucceeded(helpRequest(RequestStatus.ACCEPTED, id = OTHER_REQUEST_ID))

        assertEquals(VolunteerCall(OTHER_REQUEST_ID, callCredentials), state.call)
        assertNull(state.finished)
    }

    @Test
    fun activeCallIsKeptWhenItStarts() {
        val state = VolunteerState().acceptSucceeded(accepted)

        assertSame(state, state.withRequest(helpRequest(RequestStatus.IN_CALL)))
    }

    @Test
    fun callStateComesFromThePlatform() {
        val state =
            VolunteerState()
                .acceptSucceeded(accepted)
                .withCallState(REQUEST_ID, CallState(CallConnection.CONNECTED, PeerPresence.PRESENT, peerVideo = true))
                .withCallState(REQUEST_ID, CallState(CallConnection.CONNECTED, PeerPresence.LEFT))
                .withCallState(OTHER_REQUEST_ID, CallState(CallConnection.DISCONNECTED))

        assertEquals(CallState(CallConnection.CONNECTED, PeerPresence.LEFT), state.call?.call)
        assertTrue(state.call?.peerJoined == true)
    }

    @Test
    fun callFoundAfterRestartIsRestoredOnce() {
        val restored = VolunteerState().withCallRestored(accepted)

        assertEquals(VolunteerCall(REQUEST_ID, callCredentials), restored.call)
        assertSame(restored, restored.withCallRestored(accepted))
        assertSame(restored, restored.withCallRestored(helpRequest(RequestStatus.ENDED, id = OTHER_REQUEST_ID)))
    }

    @Test
    fun skipAndRating() {
        assertEquals(emptyList(), VolunteerState().withEvent(incoming()).skip(REQUEST_ID).incoming)

        val finished = talking.endStarted(REQUEST_ID).callEnded(REQUEST_ID)
        assertNull(finished.ratingDone(rated = true).finished)
        assertEquals(VolunteerNotice.RATED, finished.ratingDone(rated = true).notice?.kind)
        assertNull(finished.ratingDone(rated = false).notice)
    }

    @Test
    fun skippedListIsBounded() {
        val state = (1..60).fold(VolunteerState()) { s, i -> s.skip("request-$i") }

        assertEquals(50, state.skipped.size)
        assertEquals("request-60", state.skipped.last())
    }

    // --- Вызовы, которые ждут ответа, по данным сервера ---

    @Test
    fun syncAddsCallsThatCameWithoutConnectionAndRings() {
        val state = VolunteerState().withIncomingSynced(listOf(searching, other))

        assertEquals(listOf(REQUEST_ID, OTHER_REQUEST_ID), state.incoming.map { it.id })
        assertEquals(VolunteerNotice.INCOMING, state.notice?.kind)
    }

    @Test
    fun syncRemovesClosedCallsWithoutNewAnnouncement() {
        val shown = VolunteerState().withEvent(incoming()).withEvent(incoming(other))

        val state = shown.withIncomingSynced(listOf(other))

        assertEquals(listOf(OTHER_REQUEST_ID), state.incoming.map { it.id })
        assertEquals(shown.notice, state.notice)
    }

    @Test
    fun syncDoesNotBringBackSkippedCall() {
        val state = VolunteerState().withEvent(incoming()).skip(REQUEST_ID).withIncomingSynced(listOf(searching))

        assertEquals(emptyList(), state.incoming)
        assertEquals(emptyList(), state.withEvent(incoming()).incoming)
    }

    @Test
    fun syncKeepsTheCallBeingAccepted() {
        val state = VolunteerState().withEvent(incoming()).acceptStarted(REQUEST_ID).withIncomingSynced(emptyList())

        assertEquals(listOf(REQUEST_ID), state.incoming.map { it.id })
    }

    @Test
    fun syncIsIgnoredDuringCall() {
        assertSame(talking, talking.withIncomingSynced(listOf(other)))
    }
}
