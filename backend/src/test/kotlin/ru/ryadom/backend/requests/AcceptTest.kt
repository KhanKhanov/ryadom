package ru.ryadom.backend.requests

import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import ru.ryadom.backend.testing.FakeLiveKit
import ru.ryadom.backend.testing.TestDatabase
import ru.ryadom.backend.testing.apiTest
import ru.ryadom.backend.testing.assertError
import ru.ryadom.shared.api.ApiErrorCodes
import ru.ryadom.shared.api.HelpRequest
import ru.ryadom.shared.api.RequestStatus
import ru.ryadom.shared.api.ServerEvent
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AcceptTest {
    @Test
    fun firstVolunteerWins() =
        apiTest {
            val winner = volunteer("winner")
            val other = volunteer("other")
            val winnerEvents = connect(winner)
            val otherEvents = connect(other)
            val blind = blind()
            val blindEvents = connect(blind)
            val request = requestHelp(blind)
            winnerEvents.nextOf<ServerEvent.RequestIncoming>()
            otherEvents.nextOf<ServerEvent.RequestIncoming>()

            clock.advance(Duration.ofSeconds(12))
            val response = acceptRequest(winner, request.id)

            assertEquals(HttpStatusCode.OK, response.status)
            val accepted = response.body<HelpRequest>()
            assertEquals(RequestStatus.ACCEPTED, accepted.status)
            assertEquals("2026-09-30T10:00:12Z", accepted.acceptedAt)
            val volunteerCall = assertNotNull(accepted.call)
            assertEquals(FakeLiveKit.config.url, volunteerCall.url)
            assertEquals(request.id, volunteerCall.room)

            val blindCall = assertNotNull(blindEvents.nextOf<ServerEvent.RequestAccepted>().request.call, "незрячий получает свой токен")
            assertEquals(request.id, blindCall.room)
            assertNotEquals(volunteerCall.token, blindCall.token)

            val taken = otherEvents.nextOf<ServerEvent.RequestTaken>().request
            assertEquals(RequestStatus.ACCEPTED, taken.status)
            assertNull(taken.call, "проигравший волонтёр не получает доступа к звонку")
            acceptRequest(other, request.id).assertError(HttpStatusCode.Conflict, ApiErrorCodes.REQUEST_TAKEN)
            // Завершить чужой звонок нельзя.
            cancelRequest(other, request.id).assertError(HttpStatusCode.Forbidden, ApiErrorCodes.FORBIDDEN)

            assertEquals(1, TestDatabase.queryInt("SELECT count(*) FROM request_notifications WHERE result = 'accepted'"))
            assertEquals(1, TestDatabase.queryInt("SELECT count(*) FROM request_notifications WHERE result = 'too_late'"))
            winnerEvents.nextOf<ServerEvent.RequestAccepted>()
            winnerEvents.assertNoEvents()
        }

    @Test
    fun otherConnectionsOfWinnerStopRinging() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            val firstTab = connect(volunteer)
            val secondTab = connect(volunteer)
            val request = requestHelp(blind())
            firstTab.nextOf<ServerEvent.RequestIncoming>()
            secondTab.nextOf<ServerEvent.RequestIncoming>()

            acceptRequest(volunteer, request.id)

            for (tab in listOf(firstTab, secondTab)) {
                val accepted = tab.nextOf<ServerEvent.RequestAccepted>().request
                assertEquals(RequestStatus.ACCEPTED, accepted.status)
                assertNull(accepted.call, "в звонок входит соединение, которое принимало вызов, а не все вкладки")
            }
        }

    @Test
    fun repeatedAcceptBySameVolunteerIsSafe() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            connect(volunteer)
            val request = requestHelp(blind())

            val first = acceptRequest(volunteer, request.id).body<HelpRequest>()
            val second = acceptRequest(volunteer, request.id)

            assertEquals(HttpStatusCode.OK, second.status)
            assertEquals(first.copy(call = null), second.body<HelpRequest>().copy(call = null))
            assertNotNull(second.body<HelpRequest>().call)
        }

    @Test
    fun simultaneousAcceptsHaveOneWinner() =
        apiTest {
            val volunteers = List(5) { volunteer("volunteer-$it") }
            volunteers.forEach { connect(it) }
            val request = requestHelp(blind())

            val statuses = coroutineScope { volunteers.map { async { acceptRequest(it, request.id).status } }.awaitAll() }

            assertEquals(1, statuses.count { it == HttpStatusCode.OK })
            assertEquals(4, statuses.count { it == HttpStatusCode.Conflict })
        }

    @Test
    fun onlyNotifiedVolunteerCanAccept() =
        apiTest {
            val notNotified = volunteer("not-notified")
            val blind = blind()
            val request = requestHelp(blind)

            acceptRequest(notNotified, request.id).assertError(HttpStatusCode.NotFound, ApiErrorCodes.NOT_FOUND)
            acceptRequest(blind, request.id).assertError(HttpStatusCode.Forbidden, ApiErrorCodes.FORBIDDEN)
            acceptRequest(notNotified, "0199a1b2-0000-7000-8000-000000000000").assertError(HttpStatusCode.NotFound, ApiErrorCodes.NOT_FOUND)
        }

    @Test
    fun closedRequestCannotBeAccepted() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            connect(volunteer)
            val blind = blind()
            val request = requestHelp(blind)
            cancelRequest(blind, request.id)

            acceptRequest(volunteer, request.id).assertError(HttpStatusCode.Conflict, ApiErrorCodes.REQUEST_CLOSED)
        }

    @Test
    fun volunteerInCallIsNotOfferedNewRequests() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            connect(volunteer)
            val first = requestHelp(blind("blind-1"))
            acceptRequest(volunteer, first.id)

            val second = requestHelp(blind("blind-2"))

            assertFalse(isNotified(second.id, volunteer))
        }

    @Test
    fun volunteerCannotTakeTwoCalls() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            connect(volunteer)
            val first = requestHelp(blind("blind-1"))
            val second = requestHelp(blind("blind-2"))
            assertEquals(HttpStatusCode.OK, acceptRequest(volunteer, first.id).status)

            acceptRequest(volunteer, second.id).assertError(HttpStatusCode.Conflict, ApiErrorCodes.ACTIVE_REQUEST_EXISTS)
        }

    @Test
    fun volunteerSeesCurrentCall() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            connect(volunteer)
            val request = requestHelp(blind())
            acceptRequest(volunteer, request.id)

            val current = currentRequest(volunteer).body<HelpRequest>()

            assertEquals(request.id, current.id)
            assertNotNull(current.call, "после перезапуска приложения волонтёр может вернуться в звонок")
        }

    @Test
    fun cancelAfterAcceptNotifiesVolunteer() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            val events = connect(volunteer)
            val blind = blind()
            val request = requestHelp(blind)
            events.nextOf<ServerEvent.RequestIncoming>()
            acceptRequest(volunteer, request.id)
            events.nextOf<ServerEvent.RequestAccepted>()

            val cancelled = cancelRequest(blind, request.id).body<HelpRequest>()

            assertEquals(RequestStatus.CANCELLED, cancelled.status)
            assertEquals(cancelled, events.nextOf<ServerEvent.RequestCancelled>().request)
            assertEquals(HttpStatusCode.NoContent, currentRequest(volunteer).status)
        }
}
