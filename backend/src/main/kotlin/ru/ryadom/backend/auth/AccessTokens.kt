package ru.ryadom.backend.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import ru.ryadom.backend.JwtConfig
import java.time.Clock
import kotlin.uuid.Uuid

/**
 * Выпуск и проверка access-токенов (JWT, HS256). В токене только id пользователя (`sub`):
 * роль и блокировка проверяются по базе, чтобы изменения действовали сразу.
 */
class AccessTokens(
    private val config: JwtConfig,
    private val clock: Clock,
) {
    private val algorithm = Algorithm.HMAC256(config.secret)

    /** Проверяет подпись, издателя, аудиторию и срок действия по тем же часам [clock]. */
    val verifier: JWTVerifier =
        (JWT.require(algorithm).withIssuer(config.issuer).withAudience(config.audience) as JWTVerifier.BaseVerification)
            .build(clock)

    val ttlSeconds: Long get() = config.accessTokenTtl.seconds

    fun issue(userId: Uuid): String {
        val now = clock.instant()
        return JWT
            .create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(userId.toString())
            .withIssuedAt(now)
            .withExpiresAt(now.plus(config.accessTokenTtl))
            .sign(algorithm)
    }
}
