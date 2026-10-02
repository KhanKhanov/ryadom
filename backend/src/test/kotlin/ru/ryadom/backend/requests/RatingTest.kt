package ru.ryadom.backend.requests

import io.ktor.http.HttpStatusCode
import ru.ryadom.backend.testing.FakeLiveKit
import ru.ryadom.backend.testing.TestDatabase
import ru.ryadom.backend.testing.apiTest
import ru.ryadom.backend.testing.assertError
import ru.ryadom.backend.testing.auth
import ru.ryadom.backend.testing.sendLiveKitWebhook
import ru.ryadom.shared.api.ApiErrorCodes
import ru.ryadom.shared.api.ApiPaths
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class RatingTest {
    @Test
    fun bothParticipantsRateCall() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            connect(volunteer)
            val blind = blind()
            val request = requestHelp(blind)
            acceptRequest(volunteer, request.id)
            sendLiveKitWebhook(FakeLiveKit.event("room_finished", request.id))

            assertEquals(HttpStatusCode.NoContent, rateRequest(blind, request.id, helped = true).status)
            assertEquals(HttpStatusCode.NoContent, rateRequest(volunteer, request.id, helped = false).status)
            assertEquals(2, TestDatabase.queryInt("SELECT count(*) FROM ratings"))

            // Передумал — повторная оценка заменяет прежнюю.
            rateRequest(blind, request.id, helped = false)
            assertEquals(2, TestDatabase.queryInt("SELECT count(*) FROM ratings"))
            assertEquals(0, TestDatabase.queryInt("SELECT count(*) FROM ratings WHERE helped"))
        }

    @Test
    fun ratingIsPossibleBeforeLiveKitReportsEnd() =
        apiTest {
            // room_finished приходит через несколько секунд после выхода участников, а экран оценки показывается сразу.
            val volunteer = volunteer("volunteer-1")
            connect(volunteer)
            val blind = blind()
            val request = requestHelp(blind)
            acceptRequest(volunteer, request.id)

            assertEquals(HttpStatusCode.NoContent, rateRequest(blind, request.id, helped = true).status)
        }

    @Test
    fun requestWithoutCallCannotBeRated() =
        apiTest {
            val blind = blind()
            val request = requestHelp(blind)
            rateRequest(blind, request.id, helped = true).assertError(HttpStatusCode.Conflict, ApiErrorCodes.CALL_NOT_STARTED)

            clock.advance(Duration.ofMinutes(1))
            tick()
            rateRequest(blind, request.id, helped = true).assertError(HttpStatusCode.Conflict, ApiErrorCodes.CALL_NOT_STARTED)
        }

    @Test
    fun onlyParticipantsCanRate() =
        apiTest {
            val winner = volunteer("winner")
            val loser = volunteer("loser")
            connect(winner)
            connect(loser)
            val request = requestHelp(blind())
            acceptRequest(winner, request.id)

            rateRequest(loser, request.id, helped = true).assertError(HttpStatusCode.NotFound, ApiErrorCodes.NOT_FOUND)
            rateRequest(blind("blind-2"), request.id, helped = true).assertError(HttpStatusCode.NotFound, ApiErrorCodes.NOT_FOUND)
        }

    @Test
    fun ratingBodyIsValidated() =
        apiTest {
            val blind = blind()
            val request = requestHelp(blind)

            postJson(ApiPaths.requestRating(request.id), mapOf("score" to 5)) { auth(blind) }
                .assertError(HttpStatusCode.BadRequest, ApiErrorCodes.INVALID_REQUEST)
        }
}
