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
    val matching: MatchingConfig,
    val requests: RequestsConfig,
    val realtime: RealtimeConfig,
    val liveKit: LiveKitConfig,
) {
    companion object {
        /** Минимальная длина секрета для подписи JWT (HS256). */
        const val MIN_JWT_SECRET_LENGTH = 32

        /** LiveKit не принимает секреты API короче 32 символов. */
        const val MIN_LIVEKIT_SECRET_LENGTH = 32

        /** Токены LiveKit живут не дольше 2 часов (docs/ARCHITECTURE.md, раздел 9). */
        val MAX_LIVEKIT_TOKEN_TTL: Duration = Duration.ofHours(2)

        /** Загружает `application.conf` с подстановкой переменных окружения. Падает с понятным сообщением, если чего-то не хватает. */
        fun load(config: Config = ConfigFactory.load()): AppConfig {
            val yandex = config.getConfig("auth.yandex")
            val yandexClientId = yandex.getString("clientId").trim()
            val dnd = config.getConfig("profile.defaultDoNotDisturb")
            val matching = config.getConfig("matching")
            val requests = config.getConfig("requests")
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
                matching =
                    MatchingConfig(
                        firstWaveSize = matching.getInt("firstWaveSize"),
                        nextWaveSize = matching.getInt("nextWaveSize"),
                        waveInterval = matching.getDuration("waveInterval"),
                        searchTimeout = matching.getDuration("searchTimeout"),
                        checkInterval = matching.getDuration("checkInterval"),
                    ),
                requests =
                    RequestsConfig(
                        maxRequestsPerWindow = requests.getInt("rateLimit.maxRequests"),
                        rateLimitWindow = requests.getDuration("rateLimit.window"),
                        maxCallDuration = requests.getDuration("maxCallDuration"),
                        joinTimeout = requests.getDuration("joinTimeout"),
                    ),
                realtime =
                    RealtimeConfig(
                        authTimeout = config.getDuration("realtime.authTimeout"),
                        pingInterval = config.getDuration("realtime.pingInterval"),
                    ),
                liveKit =
                    LiveKitConfig(
                        url = config.getString("livekit.url"),
                        apiKey = config.requireString("livekit.apiKey", "LIVEKIT_API_KEY"),
                        apiSecret = config.requireString("livekit.apiSecret", "LIVEKIT_API_SECRET"),
                        tokenTtl = config.getDuration("livekit.tokenTtl"),
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
        check(liveKit.url.startsWith("ws://") || liveKit.url.startsWith("wss://")) {
            "LIVEKIT_URL должен начинаться с ws:// или wss://, например ws://localhost:7880"
        }
        check(liveKit.apiSecret.length >= MIN_LIVEKIT_SECRET_LENGTH) {
            "LIVEKIT_API_SECRET должен быть не короче $MIN_LIVEKIT_SECRET_LENGTH символов. Сгенерировать: openssl rand -hex 32"
        }
        check(
            liveKit.tokenTtl.isPositive && liveKit.tokenTtl <= MAX_LIVEKIT_TOKEN_TTL,
        ) { "livekit.tokenTtl должен быть от 1 секунды до 2 часов" }
        check(matching.firstWaveSize > 0 && matching.nextWaveSize > 0) { "Размеры волн (matching.*WaveSize) должны быть больше нуля" }
        check(matching.waveInterval.isPositive && matching.checkInterval.isPositive) {
            "matching.waveInterval и matching.checkInterval должны быть больше нуля"
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

/** Параметры подбора волонтёров (docs/ARCHITECTURE.md, раздел 4). */
data class MatchingConfig(
    /** Сколько волонтёров уведомляется сразу после запроса. */
    val firstWaveSize: Int,
    /** Сколько волонтёров добавляется в каждой следующей волне. */
    val nextWaveSize: Int,
    val waveInterval: Duration,
    /** Сколько длится поиск до статуса `no_answer`. */
    val searchTimeout: Duration,
    /** Как часто проверять, не пора ли отправить следующую волну. */
    val checkInterval: Duration,
)

data class RequestsConfig(
    /** Сколько запросов один пользователь может создать за [rateLimitWindow]. */
    val maxRequestsPerWindow: Int,
    val rateLimitWindow: Duration,
    /** Через сколько закрыть звонок, о завершении которого LiveKit не сообщил. */
    val maxCallDuration: Duration,
    /** Через сколько после принятия закрыть запрос, в комнату звонка которого никто не вошёл. */
    val joinTimeout: Duration,
)

/** Настройки WebSocket `/ws`. */
data class RealtimeConfig(
    /** Сколько ждать сообщения `auth` после открытия соединения. */
    val authTimeout: Duration,
    val pingInterval: Duration,
)

data class LiveKitConfig(
    /** Адрес LiveKit для клиентов (`ws://` или `wss://`). */
    val url: String,
    val apiKey: String,
    val apiSecret: String,
    val tokenTtl: Duration,
) {
    override fun toString() = "LiveKitConfig(url=$url, apiKey=$apiKey, apiSecret=***, tokenTtl=$tokenTtl)"
}
