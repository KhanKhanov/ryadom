package ru.ryadom.backend

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import ru.ryadom.shared.api.Language
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppConfigTest {
    private val requiredEnv =
        mapOf(
            "POSTGRES_PASSWORD" to "db-password",
            "JWT_SECRET" to "s".repeat(32),
            "LIVEKIT_API_KEY" to "livekit-key",
            "LIVEKIT_API_SECRET" to "l".repeat(32),
        )

    /**
     * Загружает настоящий application.conf, подставляя [env] вместо переменных окружения компьютера:
     * `${?PORT}` сначала ищется как ключ `PORT` в самом конфиге, и только потом — в окружении (здесь выключено).
     */
    private fun load(env: Map<String, String>): AppConfig {
        val config =
            ConfigFactory
                .parseResources("application.conf")
                .withFallback(ConfigFactory.parseMap(env))
                .resolve(ConfigResolveOptions.noSystem())
        return AppConfig.load(config)
    }

    @Test
    fun defaultsAreSafeForProduction() {
        val config = load(requiredEnv)

        assertEquals(8080, config.port)
        assertEquals("jdbc:postgresql://localhost:5432/ryadom", config.database.url)
        assertFalse(config.auth.devLoginEnabled, "вход без OAuth по умолчанию выключен")
        assertNull(config.auth.yandex, "без YANDEX_CLIENT_ID вход через Яндекс выключен")
        assertEquals(Duration.ofMinutes(15), config.auth.jwt.accessTokenTtl)
        assertEquals(Duration.ofDays(90), config.auth.refreshTokenTtl)
        assertEquals(Language.RU, config.profile.language)
        assertEquals(ZoneId.of("Europe/Moscow"), config.profile.timezone)
        assertEquals(LocalTime.of(22, 0), config.profile.doNotDisturbFrom)
        assertEquals(LocalTime.of(8, 0), config.profile.doNotDisturbTo)
    }

    @Test
    fun matchingDefaultsFollowArchitecture() {
        val config = load(requiredEnv)

        // docs/ARCHITECTURE.md, раздел 4: сразу 5 человек, далее каждые 10 секунд по 10, всего до 60 секунд.
        with(config.matching) {
            assertEquals(5, firstWaveSize)
            assertEquals(10, nextWaveSize)
            assertEquals(Duration.ofSeconds(10), waveInterval)
            assertEquals(Duration.ofSeconds(60), searchTimeout)
        }
        assertEquals("ws://localhost:7880", config.liveKit.url)
        assertEquals(Duration.ofHours(2), config.liveKit.tokenTtl)
        assertEquals("livekit-key", config.liveKit.apiKey)
    }

    @Test
    fun liveKitUrlCanBeSetFromEnvironment() {
        val config = load(requiredEnv + ("LIVEKIT_URL" to "ws://192.168.1.10:7880"))

        assertEquals("ws://192.168.1.10:7880", config.liveKit.url)
    }

    @Test
    fun liveKitKeysAreRequired() {
        val noKey = assertFailsWith<IllegalStateException> { load(requiredEnv - "LIVEKIT_API_KEY") }
        assertContains(noKey.message.orEmpty(), "LIVEKIT_API_KEY")

        val shortSecret = assertFailsWith<IllegalStateException> { load(requiredEnv + ("LIVEKIT_API_SECRET" to "short")) }
        assertContains(shortSecret.message.orEmpty(), "LIVEKIT_API_SECRET")
    }

    @Test
    fun liveKitUrlMustBeWebSocket() {
        for (url in listOf("", "http://localhost:7880")) {
            val error = assertFailsWith<IllegalStateException> { load(requiredEnv + ("LIVEKIT_URL" to url)) }
            assertContains(error.message.orEmpty(), "LIVEKIT_URL")
        }
    }

    @Test
    fun environmentOverridesDefaults() {
        val config =
            load(
                requiredEnv +
                    mapOf(
                        "PORT" to "9000",
                        "DATABASE_URL" to "jdbc:postgresql://db:5432/other",
                        "AUTH_DEV_ENABLED" to "true",
                        "YANDEX_CLIENT_ID" to "web-id",
                        "YANDEX_CLIENT_SECRET" to "web-secret",
                        "YANDEX_EXTRA_CLIENT_IDS" to " android-id , ,ios-id",
                    ),
            )

        assertEquals(9000, config.port)
        assertEquals("jdbc:postgresql://db:5432/other", config.database.url)
        assertTrue(config.auth.devLoginEnabled)
        val yandex = checkNotNull(config.auth.yandex)
        assertEquals("web-secret", yandex.clientSecret)
        assertEquals(setOf("web-id", "android-id", "ios-id"), yandex.trustedClientIds)
    }

    @Test
    fun databaseNameFollowsDockerCompose() {
        val config = load(requiredEnv + ("POSTGRES_DB" to "ryadom_dev"))

        assertEquals("jdbc:postgresql://localhost:5432/ryadom_dev", config.database.url)
    }

    @Test
    fun missingSecretsFailWithClearMessage() {
        val noPassword = assertFailsWith<IllegalStateException> { load(requiredEnv - "POSTGRES_PASSWORD") }
        assertContains(noPassword.message.orEmpty(), "POSTGRES_PASSWORD")

        val noJwt = assertFailsWith<IllegalStateException> { load(requiredEnv - "JWT_SECRET") }
        assertContains(noJwt.message.orEmpty(), "JWT_SECRET")
    }

    @Test
    fun shortJwtSecretIsRejected() {
        val error = assertFailsWith<IllegalStateException> { load(requiredEnv + ("JWT_SECRET" to "short")) }

        assertContains(error.message.orEmpty(), "32")
    }

    @Test
    fun secretsAreHiddenInToString() {
        val config = load(requiredEnv + mapOf("YANDEX_CLIENT_ID" to "id", "YANDEX_CLIENT_SECRET" to "yandex-secret"))

        val printed = config.toString()
        assertFalse("db-password" in printed)
        assertFalse("s".repeat(32) in printed)
        assertFalse("yandex-secret" in printed)
        assertFalse("l".repeat(32) in printed)
    }
}
