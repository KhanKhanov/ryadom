package ru.ryadom.shared.help

import ru.ryadom.shared.api.RequestStatus
import ru.ryadom.shared.call.CallConnection
import ru.ryadom.shared.call.CallState
import ru.ryadom.shared.call.PeerPresence
import ru.ryadom.shared.testing.OTHER_REQUEST_ID
import ru.ryadom.shared.testing.REQUEST_ID
import ru.ryadom.shared.testing.callCredentials
import ru.ryadom.shared.testing.helpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Переходы между экранами незрячего — чистые функции из BlindHelpState.kt. */
class BlindHelpStateTest {
    private val ready = BlindState(screen = BlindScreen.Ready())
    private val searching = BlindState(screen = BlindScreen.Searching(REQUEST_ID))
    private val call = BlindState(screen = BlindScreen.Call(REQUEST_ID, callCredentials))
    private val callWithVolunteer = BlindState(screen = BlindScreen.Call(REQUEST_ID, callCredentials, volunteerJoined = true))

    private fun BlindState.with(
        status: RequestStatus,
        id: String = REQUEST_ID,
        night: Boolean = false,
        byUser: Boolean = false,
    ) = withRequest(helpRequest(status, id), night, byUser)

    @Test
    fun createdRequestStartsSearch() {
        assertEquals(BlindScreen.Searching(REQUEST_ID), ready.with(RequestStatus.SEARCHING).screen)
    }

    @Test
    fun acceptedRequestStartsCallWithCredentials() {
        assertEquals(BlindScreen.Call(REQUEST_ID, callCredentials), searching.with(RequestStatus.ACCEPTED).screen)
    }

    @Test
    fun acceptanceThatOvertookCreationResponseIsKept() {
        // Волонтёр принял запрос так быстро, что событие пришло раньше ответа на создание.
        val inCall = ready.with(RequestStatus.ACCEPTED)
        assertEquals(inCall, inCall.with(RequestStatus.SEARCHING))
    }

    @Test
    fun freshCredentialsDoNotRestartRunningCall() {
        val running = call.withCallState(REQUEST_ID, CallState(connection = CallConnection.CONNECTED))
        val refreshed = running.withRequest(helpRequest(RequestStatus.IN_CALL, call = callCredentials.copy(token = "new")), isNight = false)

        assertEquals(running, refreshed)
    }

    @Test
    fun noAnswerTextDependsOnTimeOfDay() {
        assertEquals(BlindScreen.Ready(HelpOutcome.NO_ANSWER), searching.with(RequestStatus.NO_ANSWER).screen)
        assertEquals(BlindScreen.Ready(HelpOutcome.NO_ANSWER_AT_NIGHT), searching.with(RequestStatus.NO_ANSWER, night = true).screen)
    }

    @Test
    fun cancelledSearchReturnsToButton() {
        val cancelled = searching.with(RequestStatus.CANCELLED, byUser = true)

        assertEquals(BlindScreen.Ready(HelpOutcome.CANCELLED), cancelled.screen)
        assertEquals(REQUEST_ID, cancelled.closedRequestId)
    }

    @Test
    fun callEndedAfterConversationAsksForRating() {
        assertEquals(BlindScreen.Rating(REQUEST_ID), callWithVolunteer.with(RequestStatus.ENDED).screen)
        assertEquals(BlindScreen.Rating(REQUEST_ID), callWithVolunteer.with(RequestStatus.ENDED, byUser = true).screen)
    }

    @Test
    fun volunteerEndingBeforeConversationOffersToAskAgain() {
        // Отложенная задача этапа 3: оценку не спрашиваем, а предлагаем попросить помощи снова.
        assertEquals(BlindScreen.Ready(HelpOutcome.VOLUNTEER_LEFT), call.with(RequestStatus.ENDED).screen)
    }

    @Test
    fun userEndingCallBeforeVolunteerJoinedJustEndsIt() {
        assertEquals(BlindScreen.Ready(HelpOutcome.CALL_ENDED), call.with(RequestStatus.CANCELLED, byUser = true).screen)
        val ending = BlindState(screen = BlindScreen.Call(REQUEST_ID, callCredentials, ending = true))
        assertEquals(BlindScreen.Ready(HelpOutcome.CALL_ENDED), ending.with(RequestStatus.ENDED).screen)
    }

    @Test
    fun lateEventsAboutClosedRequestAreIgnored() {
        // Волонтёр принял запрос за мгновение до отмены: событие пришло уже после ответа на отмену.
        val cancelled = searching.with(RequestStatus.CANCELLED, byUser = true)

        assertEquals(cancelled, cancelled.with(RequestStatus.ACCEPTED))
    }

    @Test
    fun eventsAboutOtherRequestsAreIgnored() {
        assertEquals(searching, searching.with(RequestStatus.ACCEPTED, id = OTHER_REQUEST_ID))
        assertEquals(call, call.with(RequestStatus.ENDED, id = OTHER_REQUEST_ID))
    }

    @Test
    fun closedRequestsWithoutTrackedOneAreIgnored() {
        assertEquals(ready, ready.with(RequestStatus.ENDED))
        assertEquals(ready, ready.with(RequestStatus.NO_ANSWER))
    }

    @Test
    fun ratingIsNotInterrupted() {
        val rating = BlindState(screen = BlindScreen.Rating(REQUEST_ID))

        assertEquals(rating, rating.with(RequestStatus.ENDED))
    }

    @Test
    fun restoredActiveRequestIsShown() {
        val loading = BlindState()

        assertEquals(BlindScreen.Searching(REQUEST_ID), loading.with(RequestStatus.SEARCHING).screen)
        assertEquals(BlindScreen.Call(REQUEST_ID, callCredentials), loading.with(RequestStatus.IN_CALL).screen)
    }

    @Test
    fun volunteerPresenceIsRemembered() {
        val joined = call.withCallState(REQUEST_ID, CallState(connection = CallConnection.CONNECTED, peer = PeerPresence.PRESENT))
        val left = joined.withCallState(REQUEST_ID, CallState(connection = CallConnection.CONNECTED, peer = PeerPresence.LEFT))

        val screen = left.screen as BlindScreen.Call
        assertTrue(screen.volunteerJoined)
        assertEquals(PeerPresence.LEFT, screen.call.peer)
    }

    @Test
    fun callStateOfAnotherCallIsIgnored() {
        assertEquals(call, call.withCallState(OTHER_REQUEST_ID, CallState(peer = PeerPresence.PRESENT)))
        assertFalse((call.screen as BlindScreen.Call).volunteerJoined)
    }

    @Test
    fun nightIsFrom22To8() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 22, 23), (0..23).filter(::isNightHour))
    }
}
