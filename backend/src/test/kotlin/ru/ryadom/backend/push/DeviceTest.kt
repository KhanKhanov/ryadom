package ru.ryadom.backend.push

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import ru.ryadom.backend.testing.ApiTestScope
import ru.ryadom.backend.testing.TestDatabase
import ru.ryadom.backend.testing.TestWebPush
import ru.ryadom.backend.testing.apiTest
import ru.ryadom.backend.testing.assertError
import ru.ryadom.backend.testing.auth
import ru.ryadom.backend.testing.testConfig
import ru.ryadom.shared.api.ApiErrorCodes
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.AuthResponse
import ru.ryadom.shared.api.PushConfig
import ru.ryadom.shared.api.PushProvider
import ru.ryadom.shared.api.RegisterDeviceRequest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Регистрация устройств для push-уведомлений: POST /devices, DELETE /devices/{id}, GET /push/config. */
class DeviceTest {
    @Test
    fun sameTokenIsTheSameDevice() =
        apiTest {
            val user = devLogin("volunteer-1")
            val subscription = TestWebPush.subscription()

            val first = registerDevice(user, subscription)
            val again = registerDevice(user, subscription)

            assertEquals(first.id, again.id, "клиент регистрирует устройство при каждом запуске — дубли не нужны")
            assertEquals(1, deviceCount())
        }

    @Test
    fun deviceMovesToTheUserWhoRegisteredItLast() =
        apiTest {
            val first = devLogin("volunteer-1")
            val second = devLogin("volunteer-2")
            val subscription = TestWebPush.subscription()
            val device = registerDevice(first, subscription)

            // Тот же браузер: первый вышел, второй вошёл.
            assertEquals(device.id, registerDevice(second, subscription).id)

            assertEquals(1, deviceCount("user_id = '${second.user.id}'"))
            assertEquals(0, deviceCount("user_id = '${first.user.id}'"))
        }

    @Test
    fun oldestDevicesAreRemovedOverTheLimit() =
        apiTest(testConfig().let { it.copy(push = it.push.copy(maxDevicesPerUser = 2)) }) {
            val user = devLogin("volunteer-1")
            val oldest = registerDevice(user, fcm("token-1"))
            clock.advance(Duration.ofMinutes(1))
            registerDevice(user, fcm("token-2"))
            clock.advance(Duration.ofMinutes(1))
            registerDevice(user, fcm("token-3"))

            assertEquals(2, deviceCount())
            assertEquals(0, deviceCount("id = '${oldest.id}'"))
        }

    @Test
    fun androidTokensAreStoredWithoutWebPushKeys() =
        apiTest {
            val user = devLogin("volunteer-1")

            registerDevice(user, fcm("fcm-token"))
            registerDevice(user, RegisterDeviceRequest(PushProvider.RUSTORE, token = "rustore-token"))

            assertEquals(1, deviceCount("push_provider = 'fcm' AND webpush_p256dh IS NULL"))
            assertEquals(1, deviceCount("push_provider = 'rustore'"))
        }

    @Test
    fun webPushEndpointMustBeAKnownPushService() =
        apiTest {
            val user = devLogin("volunteer-1")
            val valid = TestWebPush.subscription()
            val rejected =
                listOf(
                    // Сервер сам будет слать запросы на этот адрес — во внутреннюю сеть и на чужие сайты нельзя.
                    "http://fcm.googleapis.com/fcm/send/abc",
                    "https://localhost/fcm/send/abc",
                    "https://169.254.169.254/latest/meta-data",
                    "https://fcm.googleapis.com.attacker.test/abc",
                    "https://evil.test/?host=fcm.googleapis.com",
                    "https://fcm.googleapis.com:8443/fcm/send/abc",
                    "https://user@fcm.googleapis.com/fcm/send/abc",
                    "not a url",
                )

            for (endpoint in rejected) {
                register(user, valid.copy(token = endpoint)).assertError(HttpStatusCode.BadRequest, ApiErrorCodes.INVALID_REQUEST)
            }
            // Окончание с точкой в списке — любой поддомен.
            registerDevice(user, valid.copy(token = "https://updates.push.services.mozilla.com/wpush/v2/abc"))
            registerDevice(user, valid.copy(token = "https://web.push.apple.com/abc"))
        }

    @Test
    fun webPushKeysAreChecked() =
        apiTest {
            val user = devLogin("volunteer-1")
            val valid = TestWebPush.subscription()
            val keys = checkNotNull(valid.webPush)
            val invalid =
                listOf(
                    valid.copy(webPush = null),
                    valid.copy(webPush = keys.copy(p256dh = Base64Url.encode(ByteArray(65) { 4 }))),
                    valid.copy(webPush = keys.copy(auth = Base64Url.encode(ByteArray(8)))),
                    valid.copy(webPush = keys.copy(auth = "not base64!")),
                    // Ключи Web Push у других каналов — ошибка клиента.
                    fcm("token").copy(webPush = keys),
                    fcm(""),
                    fcm("token with spaces"),
                    fcm("x".repeat(4097)),
                )

            for (request in invalid) {
                register(user, request).assertError(HttpStatusCode.BadRequest, ApiErrorCodes.INVALID_REQUEST)
            }
            assertEquals(0, deviceCount())
        }

    @Test
    fun deviceIsDeletedOnlyByItsOwner() =
        apiTest {
            val owner = devLogin("volunteer-1")
            val stranger = devLogin("volunteer-2")
            val device = registerDevice(owner)

            assertEquals(HttpStatusCode.NoContent, client.delete(ApiPaths.device(device.id)) { auth(stranger) }.status)
            assertEquals(1, deviceCount(), "чужое устройство не удаляется")

            assertEquals(HttpStatusCode.NoContent, client.delete(ApiPaths.device(device.id)) { auth(owner) }.status)
            assertEquals(0, deviceCount())

            // Повторно и с неверным id — тоже 204: удалять уже нечего.
            assertEquals(HttpStatusCode.NoContent, client.delete(ApiPaths.device(device.id)) { auth(owner) }.status)
            assertEquals(HttpStatusCode.NoContent, client.delete(ApiPaths.device("not-a-uuid")) { auth(owner) }.status)
        }

    @Test
    fun pushConfigGivesTheWebPushKey() =
        apiTest {
            val user = devLogin("volunteer-1")

            val config = client.get(ApiPaths.PUSH_CONFIG) { auth(user) }.body<PushConfig>()

            assertEquals(TestWebPush.settings.publicKey, config.webPushPublicKey)
        }

    @Test
    fun pushConfigWithoutWebPush() =
        apiTest(testConfig().let { it.copy(push = it.push.copy(webPush = null)) }) {
            val user = devLogin("volunteer-1")

            assertNull(client.get(ApiPaths.PUSH_CONFIG) { auth(user) }.body<PushConfig>().webPushPublicKey)
        }

    @Test
    fun devicesNeedLogin() =
        apiTest {
            postJson(ApiPaths.DEVICES, TestWebPush.subscription()).assertError(HttpStatusCode.Unauthorized, ApiErrorCodes.UNAUTHORIZED)
            client.get(ApiPaths.PUSH_CONFIG).assertError(HttpStatusCode.Unauthorized, ApiErrorCodes.UNAUTHORIZED)
        }

    @Test
    fun bannedUserCannotRegisterDevices() =
        apiTest {
            val user = devLogin("volunteer-1")
            TestDatabase.execute("UPDATE users SET banned_at = now() WHERE id = '${user.user.id}'")

            register(user, TestWebPush.subscription()).assertError(HttpStatusCode.Forbidden, ApiErrorCodes.USER_BANNED)
        }

    @Test
    fun endpointCheckFollowsTheList() {
        val hosts = listOf("fcm.googleapis.com", ".push.apple.com")

        assertTrue(isAllowedEndpoint("https://fcm.googleapis.com/fcm/send/a:b", hosts))
        assertTrue(isAllowedEndpoint("https://web.push.apple.com/abc", hosts))
        assertTrue(isAllowedEndpoint("https://FCM.googleapis.com:443/fcm/send/abc", hosts))
        assertFalse(isAllowedEndpoint("https://push.apple.com/abc", hosts), "с точкой в начале — только поддомены")
        assertFalse(isAllowedEndpoint("https://notfcm.googleapis.com/abc", hosts))
        assertFalse(isAllowedEndpoint("https:///abc", hosts))
        assertNotEquals(true, isAllowedEndpoint("ftp://fcm.googleapis.com/abc", hosts))
    }

    private suspend fun ApiTestScope.register(
        user: AuthResponse,
        request: RegisterDeviceRequest,
    ) = postJson(ApiPaths.DEVICES, request) { auth(user) }

    private fun fcm(token: String) = RegisterDeviceRequest(PushProvider.FCM, token = token)

    private fun deviceCount(where: String = "true"): Int = TestDatabase.queryInt("SELECT count(*) FROM devices WHERE $where")
}
