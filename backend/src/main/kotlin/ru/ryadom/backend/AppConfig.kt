package ru.ryadom.backend

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import ru.ryadom.backend.push.FcmSettings
import ru.ryadom.backend.push.VapidKeys
import ru.ryadom.shared.api.Language
import ru.ryadom.shared.api.PushProvider
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
    val push: PushSettings,
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
                push = loadPush(config.getConfig("push")),
            ).also { it.validate() }
        }

        private fun loadPush(push: Config): PushSettings {
            val webPush = push.getConfig("webPush")
            val fcm = push.getConfig("fcm")
            val ruStore = push.getConfig("rustore")
            return PushSettings(
                maxDevicesPerUser = push.getInt("maxDevicesPerUser"),
                webPushHosts = push.getString("webPush.allowedHosts").commaSeparated(),
                webPush =
                    optionalGroup("Web Push", webPush, "publicKey" to "WEB_PUSH_PUBLIC_KEY", "privateKey" to "WEB_PUSH_PRIVATE_KEY")
                        ?.let { (publicKey, privateKey) ->
                            WebPushSettings(publicKey, privateKey, subject = webPush.getString("subject").trim())
                        },
                fcm =
                    fcm.getString("serviceAccountFile").trim().takeIf { it.isNotEmpty() }?.let { path ->
                        FcmSettings.fromServiceAccountFile(path)
                    },
                ruStore =
                    optionalGroup("RuStore", ruStore, "projectId" to "RUSTORE_PROJECT_ID", "serviceToken" to "RUSTORE_SERVICE_TOKEN")
                        ?.let { (projectId, serviceToken) -> RuStoreSettings(projectId, serviceToken) },
            )
        }

        /**
         * Пара настроек канала, которые задаются только вместе: обе пустые — канал выключен (`null`),
         * одна из двух — ошибка настройки, о которой лучше узнать при старте, а не по молчащим уведомлениям.
         */
        private fun optionalGroup(
            channel: String,
            config: Config,
            first: Pair<String, String>,
            second: Pair<String, String>,
        ): Pair<String, String>? {
            val a = config.getString(first.first).trim()
            val b = config.getString(second.first).trim()
            if (a.isEmpty() && b.isEmpty()) return null
            check(a.isNotEmpty() && b.isNotEmpty()) {
                "$channel: задайте обе переменные окружения ${first.second} и ${second.second} или ни одной"
            }
            return a to b
        }

        private fun String.commaSeparated(): List<String> = split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

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
        check(push.maxDevicesPerUser > 0) { "push.maxDevicesPerUser должно быть больше нуля" }
        push.webPush?.let { webPush ->
            check(webPush.subject.startsWith("mailto:") || webPush.subject.startsWith("https://")) {
                "WEB_PUSH_SUBJECT должен быть адресом mailto: или https:// — по нему push-сервисы браузеров свяжутся с владельцем сервера"
            }
            check(VapidKeys.isValidPair(webPush.publicKey, webPush.privateKey)) {
                "WEB_PUSH_PUBLIC_KEY и WEB_PUSH_PRIVATE_KEY не подходят друг к другу или повреждены. " +
                    "Создать новую пару: ./gradlew :backend:generateWebPushKeys"
            }
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

/** Push-уведомления о вызовах (docs/ARCHITECTURE.md, раздел 4). Канал без настроек выключен (`null`). */
data class PushSettings(
    /** Сколько устройств может быть у пользователя; при регистрации нового самые давние удаляются. */
    val maxDevicesPerUser: Int,
    /**
     * Адреса push-сервисов браузеров, которым сервер отправляет Web Push: точное имя (`fcm.googleapis.com`)
     * или окончание с точкой (`.push.apple.com`). Адрес подписки присылает клиент — без этого списка
     * сервер можно было бы заставить отправлять запросы куда угодно, в том числе во внутреннюю сеть.
     */
    val webPushHosts: List<String>,
    val webPush: WebPushSettings?,
    val fcm: FcmSettings?,
    val ruStore: RuStoreSettings?,
) {
    /** Каналы, через которые сервер может доставить уведомление. */
    val enabledProviders: Set<PushProvider>
        get() =
            buildSet {
                if (webPush != null) add(PushProvider.WEB_PUSH)
                if (fcm != null) add(PushProvider.FCM)
                if (ruStore != null) add(PushProvider.RUSTORE)
            }
}

/** Ключи VAPID (RFC 8292) в base64url: открытый — 65 байт, закрытый — 32 байта. */
data class WebPushSettings(
    val publicKey: String,
    val privateKey: String,
    /** `mailto:` или `https://` — как push-сервис браузера свяжется с владельцем сервера. */
    val subject: String,
) {
    override fun toString() = "WebPushSettings(publicKey=$publicKey, privateKey=***, subject=$subject)"
}

/** RuStore Push: id проекта и сервисный токен из консоли RuStore. */
data class RuStoreSettings(
    val projectId: String,
    val serviceToken: String,
) {
    override fun toString() = "RuStoreSettings(projectId=$projectId, serviceToken=***)"
}
