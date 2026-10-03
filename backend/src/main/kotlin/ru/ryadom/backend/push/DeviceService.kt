package ru.ryadom.backend.push

import org.slf4j.LoggerFactory
import ru.ryadom.backend.PushSettings
import ru.ryadom.backend.db.dbValue
import ru.ryadom.backend.errors.ApiException
import ru.ryadom.backend.users.UserRepository
import ru.ryadom.backend.users.requireActiveUser
import ru.ryadom.shared.api.Device
import ru.ryadom.shared.api.PushConfig
import ru.ryadom.shared.api.PushProvider
import ru.ryadom.shared.api.RegisterDeviceRequest
import ru.ryadom.shared.api.WebPushKeys
import java.net.URI
import java.net.URISyntaxException
import java.time.Clock
import kotlin.uuid.Uuid

/** Регистрация устройств для push-уведомлений: `POST /devices`, `DELETE /devices/{id}`, `GET /push/config`. */
class DeviceService(
    private val devices: DeviceRepository,
    private val users: UserRepository,
    private val settings: PushSettings,
    private val clock: Clock,
) {
    suspend fun register(
        userId: Uuid,
        request: RegisterDeviceRequest,
    ): Device {
        val user = users.requireActiveUser(userId)
        val webPush =
            try {
                request.validated(settings.webPushHosts)
            } catch (e: ApiException) {
                // Неизвестный push-сервис браузера: имя хоста (без пути — в нём токен) подскажет, что добавить
                // в push.webPush.allowedHosts, если это настоящий браузер.
                if (request.provider == PushProvider.WEB_PUSH) {
                    runCatching { URI(request.token).host }.getOrNull()?.let {
                        log.warn("Web Push registration rejected, endpoint host: {}", it.take(MAX_LOGGED_HOST_LENGTH))
                    }
                }
                throw e
            }
        val id = devices.register(user.id, request.provider, request.token, webPush, clock.instant(), settings.maxDevicesPerUser)
        log.debug("Device {} ({}) registered by {}", id, request.provider.dbValue, user.id)
        return Device(id.toString())
    }

    /** Удаляет устройство пользователя. [deviceId] `null` (неверный id) — удалять нечего. */
    suspend fun delete(
        userId: Uuid,
        deviceId: Uuid?,
    ) {
        users.requireActiveUser(userId)
        if (deviceId != null) devices.delete(userId, deviceId)
    }

    suspend fun config(userId: Uuid): PushConfig {
        users.requireActiveUser(userId)
        return PushConfig(webPushPublicKey = settings.webPush?.publicKey)
    }

    private companion object {
        const val MAX_LOGGED_HOST_LENGTH = 100
        val log = LoggerFactory.getLogger(DeviceService::class.java)
    }
}

private const val MAX_TOKEN_LENGTH = 4096
private const val WEB_PUSH_AUTH_SIZE = 16

/**
 * Проверяет запрос; для Web Push возвращает ключи подписки. Бросает [ApiException] с понятной причиной.
 * Адрес подписки Web Push — только `https://` и только известные push-сервисы браузеров ([allowedHosts]):
 * по этому адресу сервер сам будет отправлять запросы.
 */
internal fun RegisterDeviceRequest.validated(allowedHosts: List<String>): WebPushKeys? {
    if (token.isEmpty() || token.length > MAX_TOKEN_LENGTH || token.any { it.isWhitespace() || it.isISOControl() }) {
        throw ApiException.invalidRequest("token must be 1..$MAX_TOKEN_LENGTH characters without spaces")
    }
    if (provider != PushProvider.WEB_PUSH) {
        if (webPush != null) throw ApiException.invalidRequest("webPush keys are allowed only for provider webpush")
        return null
    }
    val keys = webPush ?: throw ApiException.invalidRequest("webPush keys are required for provider webpush")
    if (!isAllowedEndpoint(token, allowedHosts)) {
        throw ApiException.invalidRequest("Web Push endpoint must be an https:// address of a known browser push service")
    }
    val validKeys =
        try {
            EcKeys.publicKey(Base64Url.decode(keys.p256dh))
            Base64Url.decode(keys.auth).size == WEB_PUSH_AUTH_SIZE
        } catch (e: IllegalArgumentException) {
            false
        }
    if (!validKeys) throw ApiException.invalidRequest("webPush.p256dh must be a P-256 public key and webPush.auth 16 bytes, base64url")
    return keys
}

/** Адрес подписки ведёт на известный push-сервис по HTTPS на стандартном порту. */
internal fun isAllowedEndpoint(
    endpoint: String,
    allowedHosts: List<String>,
): Boolean {
    val uri =
        try {
            URI(endpoint)
        } catch (e: URISyntaxException) {
            return false
        }
    val host = uri.host?.lowercase() ?: return false
    if (uri.scheme != "https" || uri.rawUserInfo != null || (uri.port != -1 && uri.port != HTTPS_PORT)) return false
    return allowedHosts.any { allowed -> if (allowed.startsWith(".")) host.endsWith(allowed) else host == allowed }
}

private const val HTTPS_PORT = 443
