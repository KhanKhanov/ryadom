package ru.ryadom.backend.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import ru.ryadom.backend.JwtConfig
import ru.ryadom.backend.testing.TEST_JWT_SECRET
import ru.ryadom.backend.testing.TestClock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class AccessTokensTest {
    private val config = JwtConfig(TEST_JWT_SECRET, "ryadom", "ryadom-api", Duration.ofMinutes(15))
    private val clock = TestClock()
    private val tokens = AccessTokens(config, clock)
    private val userId = Uuid.random()

    @Test
    fun tokenContainsOnlyUserIdAndServiceClaims() {
        val decoded = tokens.verifier.verify(tokens.issue(userId))

        assertEquals(userId.toString(), decoded.subject)
        assertEquals(listOf("ryadom-api"), decoded.audience)
        assertEquals("ryadom", decoded.issuer)
        assertEquals(setOf("sub", "aud", "iss", "iat", "exp"), decoded.claims.keys)
        assertEquals(clock.now.plus(Duration.ofMinutes(15)), decoded.expiresAtAsInstant)
    }

    @Test
    fun expiredTokenIsRejected() {
        val token = tokens.issue(userId)
        clock.advance(Duration.ofMinutes(15).plusSeconds(1))

        assertFailsWith<JWTVerificationException> { tokens.verifier.verify(token) }
    }

    @Test
    fun tokenSignedWithAnotherSecretIsRejected() {
        val forged =
            JWT
                .create()
                .withIssuer("ryadom")
                .withAudience("ryadom-api")
                .withSubject(userId.toString())
                .withExpiresAt(clock.now.plusSeconds(60))
                .sign(Algorithm.HMAC256("another-secret-another-secret-another"))

        assertFailsWith<JWTVerificationException> { tokens.verifier.verify(forged) }
    }

    @Test
    fun tokenForAnotherAudienceIsRejected() {
        val otherService = AccessTokens(config.copy(audience = "other-api"), clock)

        assertFailsWith<JWTVerificationException> { tokens.verifier.verify(otherService.issue(userId)) }
    }
}
