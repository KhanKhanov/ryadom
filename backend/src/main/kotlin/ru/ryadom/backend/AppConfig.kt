package ru.ryadom.backend

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import ru.ryadom.shared.api.Language
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

/** Настройки backend. Читаются из `application.conf`, секреты — из переменных окружения. */
data class AppConfig(
    val port: Int,
    val database: DatabaseConfig,
    val auth: AuthConfig,
    val profile: ProfileDefaults,
) {
    companion object {
        /** Минимальная длина секрета для подписи JWT (HS256). */
        const val MIN_JWT_SECRET_LENGTH = 32

        /** Загружает `application.conf` с подстановкой переменных окружения. Падает с понятным сообщением, если чего-то не хватает. */
        fun load(config: Config = ConfigFactory.load()): AppConfig {
            val yandex = config.getConfig("auth.yandex")
            val yandexClientId = yandex.getString("clientId").trim()
            val dnd = config.getConfig("profile.defaultDoNotDisturb")
            return AppConfig(
                port = config.getInt("server.port"),
                database =
                    DatabaseConfig(
                        url = config.getString("database.url"),
                        user = config.getString("database.user"),
                        password = config.requireString("database.password", "POSTGRES_PASSWORD"),
                        maxPoolSize = config.getInt("database.maxPoolSize"),
                    ),
                auth =
                    AuthConfig(
                        jwt =
                            JwtConfig(
                                secret = config.requireString("auth.jwt.secret", "JWT_SECRET"),
                                issuer = config.getString("auth.jwt.issuer"),
                                audience = config.getString("auth.jwt.audience"),
                                accessTokenTtl = config.getDuration("auth.jwt.accessTokenTtl"),
                            ),
                        refreshTokenTtl = config.getDuration("auth.refreshTokenTtl"),
                        devLoginEnabled = config.getBoolean("auth.devLoginEnabled"),
                        yandex =
                            yandexClientId.takeIf { it.isNotEmpty() }?.let { clientId ->
                                YandexConfig(
                                    clientId = clientId,
                                    clientSecret = yandex.getString("clientSecret").trim().takeIf { it.isNotEmpty() },
                                    extraClientIds =
                                        yandex
                                            .getString("extraClientIds")
                                            .split(',')
                                            .map { it.trim() }
                                            .filter { it.isNotEmpty() }
                                            .toSet(),
                                    oauthUrl = yandex.getString("oauthUrl"),
                                    loginUrl = yandex.getString("loginUrl"),
                                )
                            },
                    ),
                profile =
                    ProfileDefaults(
                        language = Language.valueOf(config.getString("profile.defaultLanguage").uppercase()),
                        timezone = ZoneId.of(config.getString("profile.defaultTimezone")),
                        doNotDisturbFrom = LocalTime.parse(dnd.getString("from")),
                        doNotDisturbTo = LocalTime.parse(dnd.getString("to")),
                    ),
            ).also { it.validate() }
        }

        private fun Config.requireString(
            path: String,
            envVariable: String,
        ): String {
            check(hasPath(path) && getString(path).isNotBlank()) {
                "Не задана переменная окружения $envVariable (локально — в infra/.env, см. infra/.env.example)"
            }
            return getString(path)
        }
    }

    private fun validate() {
        check(auth.jwt.secret.length >= MIN_JWT_SECRET_LENGTH) {
            "JWT_SECRET должен быть не короче $MIN_JWT_SECRET_LENGTH символов. Сгенерировать: openssl rand -hex 32"
        }
    }
}

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int,
) {
    // Пароль не попадает в логи, даже если конфиг случайно напечатают.
    override fun toString() = "DatabaseConfig(url=$url, user=$user, password=***, maxPoolSize=$maxPoolSize)"
}

data class AuthConfig(
    val jwt: JwtConfig,
    val refreshTokenTtl: Duration,
    val devLoginEnabled: Boolean,
    /** `null` — вход через Яндекс ID не настроен. */
    val yandex: YandexConfig?,
)

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    val accessTokenTtl: Duration,
) {
    override fun toString() = "JwtConfig(secret=***, issuer=$issuer, audience=$audience, accessTokenTtl=$accessTokenTtl)"
}

data class YandexConfig(
    val clientId: String,
    /** Нужен для обмена кода на токен без PKCE. */
    val clientSecret: String?,
    val extraClientIds: Set<String>,
    val oauthUrl: String,
    val loginUrl: String,
) {
    /** Приложения, токенам которых доверяем: наше основное и дополнительные. */
    val trustedClientIds: Set<String> get() = extraClientIds + clientId

    override fun toString() =
        "YandexConfig(clientId=$clientId, clientSecret=${clientSecret?.let { "***" }}, " +
            "extraClientIds=$extraClientIds, oauthUrl=$oauthUrl, loginUrl=$loginUrl)"
}

/** Значения профиля по умолчанию для новых пользователей. */
data class ProfileDefaults(
    val language: Language,
    val timezone: ZoneId,
    val doNotDisturbFrom: LocalTime,
    val doNotDisturbTo: LocalTime,
)
