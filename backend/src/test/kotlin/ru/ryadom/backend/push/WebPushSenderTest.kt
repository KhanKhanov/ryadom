package ru.ryadom.backend.push

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import ru.ryadom.backend.testing.TestClock
import ru.ryadom.backend.testing.TestWebPush
import ru.ryadom.shared.api.PushMessage
import ru.ryadom.shared.api.PushMessageType
import ru.ryadom.shared.api.PushProvider
import java.io.IOException
import java.security.interfaces.ECPublicKey
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class WebPushSenderTest {
    // Настоящее время: подпись VAPID проверяется библиотекой JWT по часам компьютера.
    private val clock = TestClock(Instant.now())
    private val requests = mutableListOf<HttpRequestData>()
    private var reply: () -> HttpStatusCode = { HttpStatusCode.Created }
    private val http =
        HttpClient(
            MockEngine { request ->
                requests += request
                respond("", reply())
            },
        )
    private val browser = EcKeys.generate()
    private val device =
        DeviceRecord(
            id = Uuid.random(),
            userId = Uuid.random(),
            provider = PushProvider.WEB_PUSH,
            token = "https://fcm.googleapis.com/fcm/send/abc:def",
            webPush =
                ru.ryadom.shared.api.WebPushKeys(
                    p256dh = Base64Url.encode(EcKeys.publicBytes(browser.public as ECPublicKey)),
                    auth = Base64Url.encode(ByteArray(16) { 1 }),
                ),
        )
    private val message = PushMessage(PushMessageType.REQUEST_INCOMING, "request-1")

    private fun sender() = WebPushSender(TestWebPush.settings, http, clock)

    @Test
    fun sendsAnEncryptedSignedUrgentMessage() =
        runTest {
            val result = sender().send(device, message, Duration.ofSeconds(42))

            assertEquals(PushResult.SENT, result)
            val request = requests.single()
            assertEquals(device.token, request.url.toString())
            assertEquals("42", request.headers["TTL"])
            assertEquals("high", request.headers["Urgency"])
            assertEquals("aes128gcm", request.headers[HttpHeaders.ContentEncoding])
            val body = (request.body as OutgoingContent.ByteArrayContent).bytes()
            // Тело — шифр: ни типа события, ни id запроса в открытом виде.
            assertTrue("request" !in body.decodeToString())

            // VAPID: подпись ключом сервера, aud — адрес push-сервиса без пути, срок — меньше суток.
            val authorization = checkNotNull(request.headers[HttpHeaders.Authorization])
            val (token, key) = Regex("^vapid t=([^,]+), k=(.+)$").find(authorization)!!.destructured
            assertEquals(TestWebPush.settings.publicKey, key)
            val serverKeys = VapidKeys.parse(TestWebPush.settings.publicKey, TestWebPush.settings.privateKey)
            val jwt = JWT.require(Algorithm.ECDSA256(serverKeys.publicKey, null)).build().verify(token)
            assertEquals(listOf("https://fcm.googleapis.com"), jwt.audience)
            assertEquals("mailto:test@ryadom.test", jwt.subject)
            assertTrue(jwt.expiresAtAsInstant <= clock.now.plus(Duration.ofHours(24)))
        }

    @Test
    fun vapidTokenIsReusedForHours() {
        val keys = VapidKeys.parse(TestWebPush.settings.publicKey, TestWebPush.settings.privateKey)
        val vapid = Vapid(keys, "mailto:test@ryadom.test", clock)
        val endpoint = java.net.URI(device.token)

        val first = vapid.authorization(endpoint)
        clock.advance(Duration.ofHours(5))
        assertEquals(first, vapid.authorization(endpoint), "Apple просит не выпускать токен чаще раза в час")
        clock.advance(Duration.ofHours(2))
        assertTrue(first != vapid.authorization(endpoint), "до истечения старого осталось меньше 6 часов — новый")
    }

    @Test
    fun goneSubscriptionIsReported() =
        runTest {
            for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.Gone)) {
                reply = { status }
                assertEquals(PushResult.DEVICE_GONE, sender().send(device, message, Duration.ofSeconds(10)))
            }
        }

    @Test
    fun otherErrorsKeepTheDevice() =
        runTest {
            for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.InternalServerError, HttpStatusCode.BadRequest)) {
                reply = { status }
                assertEquals(PushResult.FAILED, sender().send(device, message, Duration.ofSeconds(10)))
            }
        }

    @Test
    fun networkFailureKeepsTheDevice() =
        runTest {
            val offline = HttpClient(MockEngine { throw IOException("No route to host") })

            assertEquals(
                PushResult.FAILED,
                WebPushSender(TestWebPush.settings, offline, clock).send(device, message, Duration.ofSeconds(10)),
            )
        }

    @Test
    fun vapidAudienceKeepsANonStandardPort() {
        val keys = VapidKeys.parse(TestWebPush.settings.publicKey, TestWebPush.settings.privateKey)
        val header = Vapid(keys, "https://ryadom.test", clock).authorization(java.net.URI("https://push.example.test:8443/a/b"))

        val token = header.removePrefix("vapid t=").substringBefore(",")
        assertEquals(listOf("https://push.example.test:8443"), JWT.decode(token).audience)
    }

    @Test
    fun vapidKeysMustMatch() {
        val (otherPublic, _) = VapidKeys.generate()

        assertTrue(VapidKeys.isValidPair(TestWebPush.settings.publicKey, TestWebPush.settings.privateKey))
        assertEquals(false, VapidKeys.isValidPair(otherPublic, TestWebPush.settings.privateKey))
        assertEquals(false, VapidKeys.isValidPair("garbage", TestWebPush.settings.privateKey))
    }
}
