package ru.ryadom.backend.push

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.net.URI
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Пара ключей VAPID сервера. */
internal class VapidKeys(
    val publicKey: ECPublicKey,
    val privateKey: ECPrivateKey,
) {
    companion object {
        /** Ключи из настроек (base64url). Бросает [IllegalArgumentException], если они повреждены. */
        fun parse(
            publicKey: String,
            privateKey: String,
        ) = VapidKeys(EcKeys.publicKey(Base64Url.decode(publicKey)), EcKeys.privateKey(Base64Url.decode(privateKey)))

        /** Ключи читаются и подходят друг к другу: подпись закрытым ключом проверяется открытым. */
        fun isValidPair(
            publicKey: String,
            privateKey: String,
        ): Boolean =
            try {
                val keys = parse(publicKey, privateKey)
                val data = "ryadom".toByteArray()
                val signature =
                    Signature.getInstance("SHA256withECDSA").run {
                        initSign(keys.privateKey)
                        update(data)
                        sign()
                    }
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(keys.publicKey)
                    update(data)
                    verify(signature)
                }
            } catch (e: IllegalArgumentException) {
                false
            } catch (e: java.security.GeneralSecurityException) {
                false
            }

        /** Новая пара ключей: (открытый, закрытый) в base64url. */
        fun generate(): Pair<String, String> {
            val pair = EcKeys.generate()
            return Base64Url.encode(EcKeys.publicBytes(pair.public as ECPublicKey)) to
                Base64Url.encode(EcKeys.privateBytes(pair.private as ECPrivateKey))
        }
    }
}

/**
 * Подпись запросов к push-сервисам браузеров (VAPID, RFC 8292). Браузер подписывается на уведомления
 * с открытым ключом сервера, и push-сервис принимает уведомления только с подписью этим ключом —
 * посторонний, узнавший адрес подписки, ничего не отправит.
 *
 * @param subject контакт владельца сервера (`mailto:` или `https://`) — для связи, если с уведомлениями что-то не так.
 */
internal class Vapid(
    private val keys: VapidKeys,
    private val subject: String,
    private val clock: Clock,
) {
    private val publicKeyText = Base64Url.encode(EcKeys.publicBytes(keys.publicKey))

    /** Подписанные токены по адресам push-сервисов: Apple просит не выпускать новый чаще раза в час. */
    private val tokens = ConcurrentHashMap<String, Token>()

    /** Заголовок `Authorization` для запроса к адресу подписки [endpoint]. */
    fun authorization(endpoint: URI): String {
        // aud — только схема, хост и порт адреса подписки (RFC 8292, раздел 2).
        val audience = origin(endpoint)
        val now = clock.instant()
        val token =
            tokens.compute(audience) { _, current ->
                current?.takeIf { now.isBefore(it.issuedAt.plus(TOKEN_REUSE)) } ?: Token(sign(audience, now), now)
            }!!
        return "vapid t=${token.value}, k=$publicKeyText"
    }

    private fun sign(
        audience: String,
        now: Instant,
    ): String =
        JWT
            .create()
            .withAudience(audience)
            .withExpiresAt(now.plus(TOKEN_TTL))
            .withSubject(subject)
            .sign(Algorithm.ECDSA256(keys.publicKey, keys.privateKey))

    private fun origin(endpoint: URI): String =
        "${endpoint.scheme}://${endpoint.host}" + (if (endpoint.port == -1) "" else ":${endpoint.port}")

    private class Token(
        val value: String,
        val issuedAt: Instant,
    )

    private companion object {
        /** RFC 8292 разрешает срок не больше суток; 12 часов — с запасом на расхождение часов. */
        val TOKEN_TTL: Duration = Duration.ofHours(12)

        /** Сколько повторно использовать токен: до истечения остаётся не меньше 6 часов. */
        val TOKEN_REUSE: Duration = Duration.ofHours(6)
    }
}
