package ru.ryadom.backend.push

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import ru.ryadom.backend.RuStoreSettings
import ru.ryadom.backend.testing.TestClock
import ru.ryadom.shared.api.PushMessage
import ru.ryadom.shared.api.PushMessageType
import ru.ryadom.shared.api.PushProvider
import java.io.File
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Отправка в FCM и RuStore Push — с поддельными серверами Google и VK. */
class FcmSenderTest {
    private val clock = TestClock(Instant.now())
    private val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val settings =
        FcmSettings(
            projectId = "ryadom-test",
            clientEmail = "push@ryadom-test.iam.gserviceaccount.com",
            privateKey = rsa.private as RSAPrivateKey,
            tokenUri = "https://oauth2.test/token",
        )
    private val requests = mutableListOf<HttpRequestData>()
    private var accessTokensIssued = 0
    private var sendReply: () -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to """{"name":"projects/ryadom-test/messages/1"}""" }
    private val http =
        HttpClient(
            MockEngine { request ->
                requests += request
                when (request.url.toString()) {
                    "https://oauth2.test/token" -> {
                        issueAccessToken(request)
                    }

                    "https://fcm.test/v1/projects/ryadom-test/messages:send" -> {
                        sendReply().let { (status, body) -> json(body, status) }
                    }

                    "https://rustore.test/v1/projects/rustore-project/messages:send" -> {
                        sendReply().let { (status, body) ->
                            json(body, status)
                        }
                    }

                    else -> {
                        json("{}", HttpStatusCode.NotFound)
                    }
                }
            },
        )
    private val device = DeviceRecord(Uuid.random(), Uuid.random(), PushProvider.FCM, token = "fcm-device-token", webPush = null)
    private val message = PushMessage(PushMessageType.REQUEST_INCOMING, "request-1")

    private fun fcm() = FcmSender(settings, http, clock, apiUrl = "https://fcm.test")

    @Test
    fun sendsHighPriorityDataMessageWithTtl() =
        runTest {
            assertEquals(PushResult.SENT, fcm().send(device, message, Duration.ofSeconds(42)))

            val send = requests.last()
            assertEquals("Bearer access-1", send.headers[HttpHeaders.Authorization])
            assertEquals(
                Json.parseToJsonElement(
                    """{"message":{"fid":"fcm-device-token","data":{"type":"request.incoming","requestId":"request-1"},""" +
                        """"android":{"priority":"high","ttl":"42s"}}}""",
                ),
                Json.parseToJsonElement((send.body as TextContent).text),
            )
        }

    @Test
    fun serviceAccountJwtIsExchangedForAnAccessTokenOnce() =
        runTest {
            val sender = fcm()
            sender.send(device, message, Duration.ofSeconds(10))
            sender.send(device, message, Duration.ofSeconds(10))

            assertEquals(1, accessTokensIssued, "токен доступа живёт час — второй раз не нужен")
            val form = formOf(requests.first())
            assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form["grant_type"])
            val assertion =
                JWT
                    .require(Algorithm.RSA256(rsa.public as RSAPublicKey, null))
                    .withIssuer(settings.clientEmail)
                    .withAudience(settings.tokenUri)
                    .withClaim("scope", "https://www.googleapis.com/auth/firebase.messaging")
                    .build()
                    .verify(form["assertion"])
            assertTrue(Duration.between(assertion.issuedAtAsInstant, assertion.expiresAtAsInstant) <= Duration.ofHours(1))
        }

    @Test
    fun accessTokenIsRenewedBeforeItExpires() =
        runTest {
            val sender = fcm()
            sender.send(device, message, Duration.ofSeconds(10))
            clock.advance(Duration.ofMinutes(56))

            sender.send(device, message, Duration.ofSeconds(10))

            assertEquals(2, accessTokensIssued)
        }

    @Test
    fun rejectedAccessTokenIsRenewedAndTheMessageResent() =
        runTest {
            var calls = 0
            sendReply = {
                calls++
                if (calls == 1) HttpStatusCode.Unauthorized to "{}" else HttpStatusCode.OK to "{}"
            }

            assertEquals(PushResult.SENT, fcm().send(device, message, Duration.ofSeconds(10)))
            assertEquals(2, accessTokensIssued)
        }

    @Test
    fun unregisteredTokenMeansTheDeviceIsGone() =
        runTest {
            sendReply = { HttpStatusCode.NotFound to fcmError(404, "NOT_FOUND", "UNREGISTERED") }
            assertEquals(PushResult.DEVICE_GONE, fcm().send(device, message, Duration.ofSeconds(10)))

            sendReply = { HttpStatusCode.Forbidden to fcmError(403, "PERMISSION_DENIED", "SENDER_ID_MISMATCH") }
            assertEquals(PushResult.DEVICE_GONE, fcm().send(device, message, Duration.ofSeconds(10)))
        }

    @Test
    fun otherErrorsKeepTheDevice() =
        runTest {
            // INVALID_ARGUMENT бывает и из-за самого сообщения — тогда удалять устройство нельзя.
            for ((status, code) in listOf(400 to "INVALID_ARGUMENT", 429 to "QUOTA_EXCEEDED", 503 to "UNAVAILABLE")) {
                sendReply = { HttpStatusCode.fromValue(status) to fcmError(status, code, code) }
                assertEquals(PushResult.FAILED, fcm().send(device, message, Duration.ofSeconds(10)))
            }
        }

    @Test
    fun rejectedServiceAccountFailsWithoutSending() =
        runTest {
            val broken =
                HttpClient(
                    MockEngine { request ->
                        requests += request
                        json("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest)
                    },
                )

            assertEquals(
                PushResult.FAILED,
                FcmSender(settings, broken, clock, "https://fcm.test").send(device, message, Duration.ofSeconds(10)),
            )
            assertEquals(1, requests.size)
        }

    @Test
    fun serviceAccountFileIsRead() {
        val pem =
            "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(rsa.private.encoded) + "\n-----END PRIVATE KEY-----\n"
        val file = File.createTempFile("service-account", ".json").apply { deleteOnExit() }
        file.writeText(
            """{"type":"service_account","project_id":"ryadom-test","private_key_id":"k1",""" +
                """"private_key":${Json.encodeToString(pem)},"client_email":"push@ryadom-test.iam.gserviceaccount.com",""" +
                """"token_uri":"https://oauth2.googleapis.com/token"}""",
        )

        val loaded = FcmSettings.fromServiceAccountFile(file.path)

        assertEquals("ryadom-test", loaded.projectId)
        assertEquals("push@ryadom-test.iam.gserviceaccount.com", loaded.clientEmail)
        assertEquals(rsa.private, loaded.privateKey)
        assertTrue("PRIVATE KEY" !in loaded.toString() && "k1" !in loaded.toString())
        assertFailsWith<IllegalStateException> { FcmSettings.fromServiceAccountFile(file.path + ".missing") }
        file.writeText("""{"project_id":"x"}""")
        assertFailsWith<IllegalStateException> { FcmSettings.fromServiceAccountFile(file.path) }
    }

    @Test
    fun ruStoreUsesTheServiceTokenAndTheFcmFormat() =
        runTest {
            val ruStore = RuStoreSender(RuStoreSettings("rustore-project", "service-token"), http, apiUrl = "https://rustore.test")
            sendReply = { HttpStatusCode.OK to "{}" }

            assertEquals(PushResult.SENT, ruStore.send(device.copy(provider = PushProvider.RUSTORE), message, Duration.ofSeconds(30)))

            val send = requests.single()
            assertEquals("Bearer service-token", send.headers[HttpHeaders.Authorization])
            // В RuStore нет android.priority — только ttl.
            assertEquals(
                Json.parseToJsonElement(
                    """{"message":{"token":"fcm-device-token","data":{"type":"request.incoming","requestId":"request-1"},""" +
                        """"android":{"ttl":"30s"}}}""",
                ),
                Json.parseToJsonElement((send.body as TextContent).text),
            )
        }

    @Test
    fun ruStoreReportsExpiredTokens() =
        runTest {
            val ruStore = RuStoreSender(RuStoreSettings("rustore-project", "service-token"), http, apiUrl = "https://rustore.test")

            // Так RuStore отвечает на устаревший токен (документация RuStore Push, «Отправка push-уведомлений»).
            sendReply =
                { HttpStatusCode.NotFound to """{"error":{"code":404,"message":"Requested entity was not found.","status":"NOT_FOUND"}}""" }
            assertEquals(PushResult.DEVICE_GONE, ruStore.send(device, message, Duration.ofSeconds(10)))

            sendReply = { HttpStatusCode.BadRequest to """{"error":{"code":400,"message":"bad","status":"INVALID_ARGUMENT"}}""" }
            assertEquals(PushResult.FAILED, ruStore.send(device, message, Duration.ofSeconds(10)))
        }

    private fun MockRequestHandleScope.issueAccessToken(request: HttpRequestData): HttpResponseData {
        accessTokensIssued++
        assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", formOf(request)["grant_type"])
        return json("""{"access_token":"access-$accessTokensIssued","token_type":"Bearer","expires_in":3600}""")
    }

    private fun formOf(request: HttpRequestData) =
        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString().parseUrlEncodedParameters()

    private fun fcmError(
        status: Int,
        code: String,
        errorCode: String,
    ) = """{"error":{"code":$status,"message":"test","status":"$code",""" +
        """"details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"$errorCode"}]}}"""

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
}
