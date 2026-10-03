package ru.ryadom.backend.livekit

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import ru.ryadom.backend.requests.acceptRequest
import ru.ryadom.backend.requests.cancelRequest
import ru.ryadom.backend.requests.currentRequest
import ru.ryadom.backend.requests.getRequest
import ru.ryadom.backend.requests.requestHelp
import ru.ryadom.backend.testing.ApiTestScope
import ru.ryadom.backend.testing.FakeLiveKit
import ru.ryadom.backend.testing.apiTest
import ru.ryadom.backend.testing.assertError
import ru.ryadom.backend.testing.sendLiveKitWebhook
import ru.ryadom.shared.api.ApiErrorCodes
import ru.ryadom.shared.api.AuthResponse
import ru.ryadom.shared.api.HelpRequest
import ru.ryadom.shared.api.RequestStatus
import ru.ryadom.shared.api.ServerEvent
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LiveKitTest {
    /** Незрячий, волонтёр и принятый волонтёром запрос. */
    private class Call(
        val blind: AuthResponse,
        val volunteer: AuthResponse,
        val request: HelpRequest,
    )

    private suspend fun ApiTestScope.acceptedCall(): Call {
        val volunteer = volunteer("volunteer-1")
        connect(volunteer)
        val blind = blind()
        val request = requestHelp(blind)
        assertEquals(HttpStatusCode.OK, acceptRequest(volunteer, request.id).status)
        return Call(blind, volunteer, request)
    }

    @Test
    fun tokensGrantOneRoomAndRoleSpecificSources() =
        apiTest {
            val call = acceptedCall()
            val volunteerToken = assertNotNull(currentRequest(call.volunteer).body<HelpRequest>().call).token
            val blindToken = assertNotNull(currentRequest(call.blind).body<HelpRequest>().call).token

            val volunteerJwt = JWT.decode(volunteerToken)
            val blindJwt = JWT.decode(blindToken)
            // Срок считается по тестовым часам сервера, поэтому подпись проверяем отдельно от срока.
            for (jwt in listOf(volunteerJwt, blindJwt)) {
                Algorithm.HMAC256(FakeLiveKit.config.apiSecret).verify(jwt)
                assertEquals(FakeLiveKit.config.apiKey, jwt.issuer)
            }

            assertEquals(call.volunteer.user.id, volunteerJwt.subject)
            assertEquals(call.blind.user.id, blindJwt.subject)
            assertEquals("volunteer-1", volunteerJwt.getClaim("name").asString(), "собеседник слышит имя из профиля")
            for (jwt in listOf(volunteerJwt, blindJwt)) {
                val video = jwt.getClaim("video").asMap()
                assertEquals(call.request.id, video["room"], "токен только для комнаты этого запроса")
                assertEquals(true, video["roomJoin"])
                assertEquals(true, video["canPublishData"], "команды фонарика")
                assertEquals(Duration.ofHours(2), Duration.between(clock.instant(), jwt.expiresAtAsInstant))
            }
            assertEquals(listOf("microphone"), volunteerJwt.getClaim("video").asMap()["canPublishSources"], "волонтёр только говорит")
            assertEquals(listOf("camera", "microphone"), blindJwt.getClaim("video").asMap()["canPublishSources"])
        }

    @Test
    fun participantJoinedStartsCall() =
        apiTest {
            val call = acceptedCall()

            val response = sendLiveKitWebhook(FakeLiveKit.event("participant_joined", call.request.id, call.blind.user.id))

            assertEquals(HttpStatusCode.NoContent, response.status)
            val current = getRequest(call.blind, call.request.id).body<HelpRequest>()
            assertEquals(RequestStatus.IN_CALL, current.status)
            assertNotNull(current.call, "во время звонка можно переподключиться")
        }

    @Test
    fun roomFinishedEndsCall() =
        apiTest {
            val call = acceptedCall()
            val blindEvents = connect(call.blind)
            val volunteerEvents = connect(call.volunteer)
            sendLiveKitWebhook(FakeLiveKit.event("participant_joined", call.request.id, call.blind.user.id))
            clock.advance(Duration.ofMinutes(4))

            sendLiveKitWebhook(FakeLiveKit.event("room_finished", call.request.id))

            val ended = getRequest(call.blind, call.request.id).body<HelpRequest>()
            assertEquals(RequestStatus.ENDED, ended.status)
            assertEquals("2026-09-30T10:04:00Z", ended.endedAt)
            assertNull(ended.call)
            assertEquals(ended, blindEvents.nextOf<ServerEvent.RequestEnded>().request, "незрячему — экран оценки")
            assertEquals(ended, volunteerEvents.nextOf<ServerEvent.RequestEnded>().request)
            assertEquals(HttpStatusCode.NoContent, currentRequest(call.volunteer).status)
            // Повтор события ничего не меняет.
            sendLiveKitWebhook(FakeLiveKit.event("room_finished", call.request.id))
            blindEvents.assertNoEvents()
        }

    @Test
    fun webhookWithBadSignatureIsRejected() =
        apiTest {
            val call = acceptedCall()
            val body = FakeLiveKit.event("room_finished", call.request.id)

            sendLiveKitWebhook(body, signature = null).assertError(HttpStatusCode.Unauthorized, ApiErrorCodes.UNAUTHORIZED)
            sendLiveKitWebhook(body, signature = FakeLiveKit.signature(body, secret = "another-secret-at-least-32-characters-long"))
                .assertError(HttpStatusCode.Unauthorized, ApiErrorCodes.UNAUTHORIZED)
            // Подпись от другого тела: SHA-256 не совпадает.
            sendLiveKitWebhook(body, signature = FakeLiveKit.signature(FakeLiveKit.event("participant_joined", call.request.id)))
                .assertError(HttpStatusCode.Unauthorized, ApiErrorCodes.UNAUTHORIZED)

            assertEquals(RequestStatus.ACCEPTED, getRequest(call.blind, call.request.id).body<HelpRequest>().status)
        }

    @Test
    fun unknownRoomsAndEventsAreIgnored() =
        apiTest {
            val call = acceptedCall()

            for (body in listOf(
                FakeLiveKit.event("room_finished", "not-a-request"),
                FakeLiveKit.event("room_finished", "0199a1b2-0000-7000-8000-000000000000"),
                FakeLiveKit.event("track_published", call.request.id),
            )) {
                assertEquals(HttpStatusCode.NoContent, sendLiveKitWebhook(body).status)
            }
            assertEquals(RequestStatus.ACCEPTED, getRequest(call.blind, call.request.id).body<HelpRequest>().status)
        }

    @Test
    fun blindHangsUpDuringCall() =
        apiTest {
            val call = acceptedCall()
            val volunteerEvents = connect(call.volunteer)
            sendLiveKitWebhook(FakeLiveKit.event("participant_joined", call.request.id, call.blind.user.id))

            val ended = cancelRequest(call.blind, call.request.id).body<HelpRequest>()

            assertEquals(RequestStatus.ENDED, ended.status)
            assertEquals(ended, volunteerEvents.nextOf<ServerEvent.RequestEnded>().request)
        }

    @Test
    fun volunteerHangsUpDuringCall() =
        apiTest {
            val call = acceptedCall()
            val blindEvents = connect(call.blind)
            val volunteerEvents = connect(call.volunteer)
            sendLiveKitWebhook(FakeLiveKit.event("participant_joined", call.request.id, call.blind.user.id))
            clock.advance(Duration.ofMinutes(2))

            val response = cancelRequest(call.volunteer, call.request.id)

            assertEquals(HttpStatusCode.OK, response.status)
            val ended = response.body<HelpRequest>()
            assertEquals(RequestStatus.ENDED, ended.status)
            assertEquals("2026-09-30T10:02:00Z", ended.endedAt)
            assertNull(ended.call)
            assertEquals(ended, blindEvents.nextOf<ServerEvent.RequestEnded>().request, "незрячий выходит из комнаты и видит экран оценки")
            assertEquals(ended, volunteerEvents.nextOf<ServerEvent.RequestEnded>().request, "другие вкладки волонтёра тоже узнают")
            assertEquals(HttpStatusCode.NoContent, currentRequest(call.volunteer).status, "волонтёр снова может принимать вызовы")
            assertEquals(HttpStatusCode.NoContent, currentRequest(call.blind).status)

            // Повторный вызов и запоздавший room_finished ничего не меняют.
            assertEquals(ended, cancelRequest(call.volunteer, call.request.id).body<HelpRequest>())
            sendLiveKitWebhook(FakeLiveKit.event("room_finished", call.request.id))
            blindEvents.assertNoEvents()
            volunteerEvents.assertNoEvents()
        }

    @Test
    fun volunteerEndsCallBeforeBlindJoins() =
        apiTest {
            val call = acceptedCall()
            val blindEvents = connect(call.blind)

            val ended = cancelRequest(call.volunteer, call.request.id).body<HelpRequest>()

            assertEquals(RequestStatus.ENDED, ended.status)
            assertEquals(ended, blindEvents.nextOf<ServerEvent.RequestEnded>().request)
            // Незрячий тоже не может «отменить» уже завершённый запрос — ответ тот же.
            assertEquals(ended, cancelRequest(call.blind, call.request.id).body<HelpRequest>())
        }

    @Test
    fun callNobodyJoinedIsClosedAfterJoinTimeout() =
        apiTest {
            val call = acceptedCall()
            val blindEvents = connect(call.blind)
            val volunteerEvents = connect(call.volunteer)

            clock.advance(Duration.ofMinutes(2))
            tick()
            assertEquals(RequestStatus.ACCEPTED, getRequest(call.blind, call.request.id).body<HelpRequest>().status)

            clock.advance(Duration.ofSeconds(1))
            tick()

            val ended = blindEvents.nextOf<ServerEvent.RequestEnded>().request
            assertEquals(RequestStatus.ENDED, ended.status)
            assertEquals(ended, volunteerEvents.nextOf<ServerEvent.RequestEnded>().request)
            assertEquals(HttpStatusCode.NoContent, currentRequest(call.volunteer).status, "волонтёр снова может принимать вызовы")
            assertEquals(RequestStatus.SEARCHING, requestHelp(call.blind).status, "незрячий может позвать помощь снова")
        }

    @Test
    fun joinedCallIsNotClosedByJoinTimeout() =
        apiTest {
            val call = acceptedCall()
            sendLiveKitWebhook(FakeLiveKit.event("participant_joined", call.request.id, call.blind.user.id))

            clock.advance(Duration.ofMinutes(10))
            tick()

            assertEquals(RequestStatus.IN_CALL, getRequest(call.blind, call.request.id).body<HelpRequest>().status)
        }

    @Test
    fun forgottenCallIsClosedBySafetyTimeout() =
        apiTest {
            val call = acceptedCall()
            val blindEvents = connect(call.blind)
            sendLiveKitWebhook(FakeLiveKit.event("participant_joined", call.request.id, call.blind.user.id))

            clock.advance(Duration.ofHours(3))
            tick()
            // Access-токен живёт 15 минут — входим заново, как сделало бы приложение.
            val blind = devLogin("blind-1")
            assertEquals(RequestStatus.IN_CALL, getRequest(blind, call.request.id).body<HelpRequest>().status)

            clock.advance(Duration.ofSeconds(1))
            tick()

            assertEquals(RequestStatus.ENDED, blindEvents.nextOf<ServerEvent.RequestEnded>().request.status)
            assertEquals(HttpStatusCode.NoContent, currentRequest(blind).status)
        }
}
