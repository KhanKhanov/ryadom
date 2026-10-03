package ru.ryadom.backend.requests

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import ru.ryadom.backend.testing.ApiTestScope
import ru.ryadom.backend.testing.FakePush
import ru.ryadom.backend.testing.TestDatabase
import ru.ryadom.backend.testing.apiTest
import ru.ryadom.backend.testing.auth
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.AuthResponse
import ru.ryadom.shared.api.IncomingHelpRequests
import ru.ryadom.shared.api.PushMessage
import ru.ryadom.shared.api.PushMessageType
import ru.ryadom.shared.api.PushProvider
import ru.ryadom.shared.api.RegisterDeviceRequest
import ru.ryadom.shared.api.RequestStatus
import ru.ryadom.shared.api.ServerEvent
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Вызовы волонтёрам push-уведомлениями: волны, «вызов закрыт», ожидающие вызовы (GET /requests/incoming). */
class PushFlowTest {
    @Test
    fun volunteerWithoutWebSocketGetsTheCallByPush() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            registerDevice(volunteer)

            val request = requestHelp(blind())

            assertTrue(isNotified(request.id, volunteer), "push-устройства достаточно, WebSocket не нужен")
            val sent = pushes().sentTo(volunteer).single()
            assertEquals(PushMessage(PushMessageType.REQUEST_INCOMING, request.id), sent.message)
            // Push-сервис хранит вызов, пока идёт поиск, — потом он никому не нужен.
            assertEquals(Duration.ofSeconds(60), sent.ttl)
        }

    @Test
    fun laterWavesLiveOnlyUntilTheSearchEnds() =
        apiTest {
            val request = requestHelp(blind())
            val late = volunteer("volunteer-late")
            registerDevice(late)

            clock.advance(Duration.ofSeconds(40))
            tick()

            assertEquals(Duration.ofSeconds(20), pushes().sentTo(late).single().ttl)
            assertTrue(isNotified(request.id, late))
        }

    @Test
    fun volunteerWithWebSocketAndDeviceGetsBoth() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            val events = connect(volunteer)
            registerDevice(volunteer)

            val request = requestHelp(blind())

            assertEquals(request.id, events.nextOf<ServerEvent.RequestIncoming>().request.id)
            assertEquals(1, pushes().sentTo(volunteer).size)
        }

    @Test
    fun deviceInAChannelNotConfiguredOnTheServerDoesNotCount() =
        apiTest(push = FakePush(providers = setOf(PushProvider.WEB_PUSH))) {
            val androidOnly = volunteer("android-only")
            registerDevice(androidOnly, RegisterDeviceRequest(PushProvider.FCM, token = "fcm-token"))

            val request = requestHelp(blind())

            assertFalse(isNotified(request.id, androidOnly), "без настроек FCM вызов до него не дойдёт — место в волне не тратится")
            assertTrue(pushes().sent.isEmpty())
        }

    @Test
    fun volunteerWhoIsNotReadyGetsNoPush() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            registerDevice(volunteer)
            TestDatabase.execute("UPDATE users SET notifications_enabled = false WHERE id = '${volunteer.user.id}'")

            val request = requestHelp(blind())

            assertFalse(isNotified(request.id, volunteer))
            assertTrue(pushes().sent.isEmpty())
        }

    @Test
    fun acceptedCallStopsRingingOnAllAndroidDevices() =
        apiTest {
            val winner = volunteer("winner")
            val other = volunteer("other")
            registerDevice(winner, android("winner-phone"))
            registerDevice(other, android("other-phone"))
            val request = requestHelp(blind())

            assertEquals(HttpStatusCode.OK, acceptRequest(winner, request.id).status)

            val closed = PushMessage(PushMessageType.REQUEST_CLOSED, request.id)
            val push = pushes()
            assertEquals(closed, push.sentTo(other).last().message)
            // И у принявшего: на его других устройствах вызов ещё звонит.
            assertEquals(closed, push.sentTo(winner).last().message)
        }

    @Test
    fun cancelledSearchClosesTheNotification() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            registerDevice(volunteer, android("phone"))
            val blind = blind()
            val request = requestHelp(blind)

            cancelRequest(blind, request.id)

            assertEquals(
                listOf(PushMessageType.REQUEST_INCOMING, PushMessageType.REQUEST_CLOSED),
                pushes().sentTo(volunteer).map { it.message.type },
            )
        }

    @Test
    fun browsersGetOnlyNewCalls() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            registerDevice(volunteer)
            val blind = blind()
            val request = requestHelp(blind)

            cancelRequest(blind, request.id)

            // На каждое Web Push сайт обязан показать уведомление, а «вызов закрыт» показывать незачем.
            assertEquals(listOf(PushMessageType.REQUEST_INCOMING), pushes().sentTo(volunteer).map { it.message.type })
        }

    @Test
    fun noAnswerClosesTheNotification() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            registerDevice(volunteer, android("phone"))
            val request = requestHelp(blind())

            clock.advance(Duration.ofSeconds(60))
            tick()

            val last = pushes().sentTo(volunteer).last()
            assertEquals(PushMessage(PushMessageType.REQUEST_CLOSED, request.id), last.message)
            assertEquals(Duration.ofSeconds(10), last.ttl, "поиск закончился, но уведомлению нужно время дойти")
        }

    @Test
    fun deviceUnknownToThePushServiceIsRemoved() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            registerDevice(volunteer, RegisterDeviceRequest(PushProvider.RUSTORE, token = "uninstalled"))
            pushes().goneTokens += "uninstalled"

            requestHelp(blind())
            pushes()

            assertEquals(0, TestDatabase.queryInt("SELECT count(*) FROM devices"))
        }

    @Test
    fun incomingListsCallsWaitingForAnswerOldestFirst() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            registerDevice(volunteer)
            val first = requestHelp(blind("blind-1"))
            clock.advance(Duration.ofSeconds(1))
            val second = requestHelp(blind("blind-2"))

            val incoming = incoming(volunteer)

            assertEquals(listOf(first.id, second.id), incoming.map { it.id })
            assertTrue(incoming.all { it.status == RequestStatus.SEARCHING && it.call == null })
        }

    @Test
    fun incomingSkipsAcceptedAndClosedCalls() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            val other = volunteer("volunteer-2")
            registerDevice(volunteer)
            registerDevice(other)
            val blind = blind("blind-1")
            val cancelled = requestHelp(blind)
            cancelRequest(blind, cancelled.id)
            val takenByOther = requestHelp(blind("blind-2"))
            acceptRequest(other, takenByOther.id)
            val waiting = requestHelp(blind("blind-3"))

            assertEquals(listOf(waiting.id), incoming(volunteer).map { it.id })
            assertEquals(emptyList(), incoming(other).map { it.id }, "у волонтёра в звонке новых вызовов нет")
            assertEquals(emptyList(), incoming(blind).map { it.id }, "незрячему вызовы не приходят")
        }

    private fun android(token: String) = RegisterDeviceRequest(PushProvider.RUSTORE, token = token)

    private suspend fun ApiTestScope.incoming(user: AuthResponse) =
        client
            .get(ApiPaths.REQUESTS_INCOMING) { auth(user) }
            .also { assertEquals(HttpStatusCode.OK, it.status) }
            .body<IncomingHelpRequests>()
            .requests
}
