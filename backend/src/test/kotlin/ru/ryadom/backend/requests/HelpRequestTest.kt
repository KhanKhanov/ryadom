package ru.ryadom.backend.requests

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import ru.ryadom.backend.testing.TestDatabase
import ru.ryadom.backend.testing.apiTest
import ru.ryadom.backend.testing.assertError
import ru.ryadom.backend.testing.auth
import ru.ryadom.backend.testing.testConfig
import ru.ryadom.shared.api.ApiErrorCodes
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.CreateHelpRequest
import ru.ryadom.shared.api.GenderPreference
import ru.ryadom.shared.api.HelpRequest
import ru.ryadom.shared.api.Language
import ru.ryadom.shared.api.RequestStatus
import ru.ryadom.shared.api.ServerEvent
import ru.ryadom.shared.api.UpdateProfileRequest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HelpRequestTest {
    @Test
    fun blindCreatesRequest() =
        apiTest {
            val blind = blind()

            val request = requestHelp(blind)

            assertEquals(RequestStatus.SEARCHING, request.status)
            assertEquals(Language.RU, request.language)
            assertEquals(GenderPreference.ANY, request.genderPreference)
            assertEquals("2026-09-30T10:00:00Z", request.createdAt)
            assertNull(request.acceptedAt)
            assertNull(request.endedAt)
            assertNull(request.call, "данные звонка появятся, когда волонтёр примет запрос")
            assertEquals(request, getRequest(blind, request.id).body<HelpRequest>())
            assertEquals(request, currentRequest(blind).body<HelpRequest>())
        }

    @Test
    fun languageAndGenderPreferenceComeFromProfileUnlessGiven() =
        apiTest {
            val blind = blind()
            client.patch(ApiPaths.ME) {
                auth(blind)
                contentType(ContentType.Application.Json)
                setBody(UpdateProfileRequest(languages = listOf(Language.EN, Language.RU), genderPreference = GenderPreference.FEMALE))
            }

            val fromProfile = requestHelp(blind)
            cancelRequest(blind, fromProfile.id)
            val explicit = requestHelp(blind, CreateHelpRequest(language = Language.RU, genderPreference = GenderPreference.ANY))

            assertEquals(Language.EN, fromProfile.language)
            assertEquals(GenderPreference.FEMALE, fromProfile.genderPreference)
            assertEquals(Language.RU, explicit.language)
            assertEquals(GenderPreference.ANY, explicit.genderPreference)
        }

    @Test
    fun onlyBlindCanRequestHelp() =
        apiTest {
            createRequest(volunteer("volunteer-1")).assertError(HttpStatusCode.Forbidden, ApiErrorCodes.FORBIDDEN)
            createRequest(devLogin("no-role")).assertError(HttpStatusCode.Forbidden, ApiErrorCodes.FORBIDDEN)
            client.get(ApiPaths.REQUESTS_CURRENT).assertError(HttpStatusCode.Unauthorized, ApiErrorCodes.UNAUTHORIZED)

            val blind = blind()
            TestDatabase.execute("UPDATE users SET banned_at = now() WHERE id = '${blind.user.id}'")
            createRequest(blind).assertError(HttpStatusCode.Forbidden, ApiErrorCodes.USER_BANNED)
        }

    @Test
    fun onlyOneActiveRequestAtATime() =
        apiTest {
            val blind = blind()
            val first = requestHelp(blind)

            createRequest(blind).assertError(HttpStatusCode.Conflict, ApiErrorCodes.ACTIVE_REQUEST_EXISTS)

            cancelRequest(blind, first.id)
            requestHelp(blind)
        }

    @Test
    fun doubleTapCreatesOneRequest() =
        apiTest {
            val blind = blind()

            val statuses = coroutineScope { List(5) { async { createRequest(blind).status } }.awaitAll() }

            assertEquals(1, statuses.count { it == HttpStatusCode.Created })
            assertEquals(4, statuses.count { it == HttpStatusCode.Conflict })
            assertEquals(1, TestDatabase.queryInt("SELECT count(*) FROM help_requests"))
        }

    @Test
    fun requestRateIsLimited() =
        apiTest(testConfig().let { it.copy(requests = it.requests.copy(maxRequestsPerWindow = 3)) }) {
            val blind = blind()
            repeat(3) {
                cancelRequest(blind, requestHelp(blind).id)
                clock.advance(Duration.ofMinutes(1))
            }

            createRequest(blind).assertError(HttpStatusCode.TooManyRequests, ApiErrorCodes.TOO_MANY_REQUESTS)

            // Через час после первого запроса он выходит из окна лимита. Access-токен за это время истёк — входим заново.
            clock.advance(Duration.ofMinutes(58))
            requestHelp(blind())
        }

    @Test
    fun blindCancelsSearch() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            val events = connect(volunteer)
            val blind = blind()
            val request = requestHelp(blind)
            assertEquals(request.id, events.nextOf<ServerEvent.RequestIncoming>().request.id)

            val response = cancelRequest(blind, request.id)

            assertEquals(HttpStatusCode.OK, response.status)
            val cancelled = response.body<HelpRequest>()
            assertEquals(RequestStatus.CANCELLED, cancelled.status)
            assertEquals("2026-09-30T10:00:00Z", cancelled.endedAt)
            assertEquals(cancelled, events.nextOf<ServerEvent.RequestCancelled>().request)
            assertEquals(HttpStatusCode.NoContent, currentRequest(blind).status)
            // Повторная отмена безопасна и ничего не меняет.
            assertEquals(cancelled, cancelRequest(blind, request.id).body<HelpRequest>())
            events.assertNoEvents()
        }

    @Test
    fun strangersCannotSeeOrCancelRequest() =
        apiTest {
            val notified = volunteer("volunteer-1")
            connect(notified)
            val request = requestHelp(blind())
            val otherBlind = blind("blind-2")

            getRequest(otherBlind, request.id).assertError(HttpStatusCode.NotFound, ApiErrorCodes.NOT_FOUND)
            cancelRequest(otherBlind, request.id).assertError(HttpStatusCode.NotFound, ApiErrorCodes.NOT_FOUND)
            getRequest(otherBlind, "not-a-uuid").assertError(HttpStatusCode.NotFound, ApiErrorCodes.NOT_FOUND)

            // Уведомлённый волонтёр видит запрос (без данных звонка), но отменить его не может.
            val seen = getRequest(notified, request.id).body<HelpRequest>()
            assertEquals(RequestStatus.SEARCHING, seen.status)
            assertNull(seen.call)
            cancelRequest(notified, request.id).assertError(HttpStatusCode.Forbidden, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun noCurrentRequestMeansNoContent() =
        apiTest {
            assertEquals(HttpStatusCode.NoContent, currentRequest(blind()).status)
            assertEquals(HttpStatusCode.NoContent, currentRequest(volunteer("volunteer-1")).status)
        }

    @Test
    fun invalidBodyIsRejected() =
        apiTest {
            val blind = blind()

            postJson(ApiPaths.REQUESTS, mapOf("language" to "de")) { auth(blind) }
                .assertError(HttpStatusCode.BadRequest, ApiErrorCodes.INVALID_REQUEST)
            postJson(ApiPaths.REQUESTS, mapOf("urgent" to "yes")) { auth(blind) }
                .assertError(HttpStatusCode.BadRequest, ApiErrorCodes.INVALID_REQUEST)
            assertEquals(0, TestDatabase.queryInt("SELECT count(*) FROM help_requests"))
        }
}
