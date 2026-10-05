package ru.ryadom.shared.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.ryadom.shared.api.ApiErrorCodes
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.AuthResponse
import ru.ryadom.shared.api.CreateHelpRequest
import ru.ryadom.shared.api.DevLoginRequest
import ru.ryadom.shared.api.Device
import ru.ryadom.shared.api.HelpRequest
import ru.ryadom.shared.api.IncomingHelpRequests
import ru.ryadom.shared.api.OAuthLoginRequest
import ru.ryadom.shared.api.OAuthProvider
import ru.ryadom.shared.api.PushConfig
import ru.ryadom.shared.api.Rating
import ru.ryadom.shared.api.RefreshTokenRequest
import ru.ryadom.shared.api.RegisterDeviceRequest
import ru.ryadom.shared.api.UpdateProfileRequest
import ru.ryadom.shared.api.UserProfile
import kotlin.time.Clock

/** Access-токен и время его истечения (миллисекунды Unix-времени). */
data class AccessToken(
    val value: String,
    val expiresAt: Long,
) {
    override fun toString(): String = "AccessToken(expiresAt=$expiresAt)"
}

/**
 * Клиент REST API (`docs/api/openapi.yaml`): вход, профиль, запросы помощи, устройства для push-уведомлений.
 *
 * Сам добавляет access-токен, заранее обновляет его и один раз повторяет запрос, если сервер ответил 401.
 * Если войти заново нужно самому пользователю (refresh-токен недействителен, пользователь заблокирован),
 * удаляет токены, сообщает об этом в [sessionEnds] и бросает [ApiClientException.SessionEnded].
 *
 * Совместимость с новыми версиями сервера (docs/ARCHITECTURE.md, раздел 6): неизвестные поля в ответах
 * пропускаются, неизвестные коды ошибок становятся [UserError.UNKNOWN].
 *
 * @param baseUrl адрес сервера без `/` в конце, например `http://10.0.2.2:8080`.
 * @param engine движок HTTP платформы (на Android — OkHttp), в тестах — `MockEngine`.
 * @param now текущее время в миллисекундах Unix-времени; в тестах — поддельные часы.
 */
class ApiClient(
    baseUrl: String,
    engine: HttpClientEngine,
    private val storage: SessionStorage,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : RealtimeAuth {
    private val baseUrl = baseUrl.trimEnd('/')

    /** Общий HTTP-клиент; через него же открывается WebSocket ([KtorRealtimeTransport]). */
    val http: HttpClient =
        HttpClient(engine) {
            // Ответы с ошибками разбираем сами, исключения Ktor на них не нужны.
            expectSuccess = false
            install(WebSockets) { pingIntervalMillis = RealtimeConnection.PING_INTERVAL_MS }
        }

    /** Адрес WebSocket событий: `http` → `ws`, `https` → `wss`. */
    val realtimeUrl: String = "ws" + this.baseUrl.removePrefix("http") + ApiPaths.REALTIME

    /**
     * Обновление токенов — по одному: refresh-токен одноразовый, и два одновременных обновления
     * сервер принял бы за кражу и завершил бы все сеансы пользователя.
     */
    private val refreshLock = Mutex()

    private val sessionEndEvents = MutableSharedFlow<SessionEndReason>(extraBufferCapacity = 8)

    /** Сеанс закончился — нужно показать вход. */
    val sessionEnds: SharedFlow<SessionEndReason> = sessionEndEvents.asSharedFlow()

    /** Задачи перед выходом ([onBeforeLogout]). */
    private val beforeLogout = mutableListOf<suspend () -> Unit>()

    /** Есть ли сохранённый вход (токены могли истечь — это выяснится при первом запросе). */
    fun hasSession(): Boolean = storage.load() != null

    // --- Вход ---

    /** Вход без OAuth, только если на сервере `AUTH_DEV_ENABLED=true`. */
    suspend fun loginDev(login: String): UserProfile =
        login(ApiPaths.AUTH_DEV, encode(DevLoginRequest.serializer(), DevLoginRequest(login)))

    /** Вход через Яндекс ID: токен, который вернул Яндекс LoginSDK. */
    suspend fun loginYandex(yandexAccessToken: String): UserProfile =
        login(
            ApiPaths.authOAuth(OAuthProvider.YANDEX),
            encode(OAuthLoginRequest.serializer(), OAuthLoginRequest(accessToken = yandexAccessToken)),
        )

    /**
     * Выход на этом устройстве: сначала задачи [onBeforeLogout], пока вход ещё действует, затем токены
     * удаляются, а сервер отзывает refresh-токен.
     */
    suspend fun logout() {
        val session = storage.load()
        if (session != null) runBeforeLogout()
        endSession(SessionEndReason.LOGGED_OUT)
        if (session == null) return
        try {
            send(HttpMethod.Post, ApiPaths.AUTH_LOGOUT, encode(RefreshTokenRequest.serializer(), RefreshTokenRequest(session.refreshToken)))
        } catch (e: ApiClientException.Network) {
            // Токены уже удалены. Если сервер недоступен, refresh-токен просто истечёт.
        }
    }

    /**
     * Задача перед выходом, пока вход ещё действует: завершить идущий звонок (иначе он остался бы открытым
     * на сервере), выключить push на устройстве (иначе вызовы приходили бы вышедшему волонтёру).
     * Возвращает функцию, которая убирает задачу. Ошибка задачи или нет связи выход не останавливают:
     * каждая задача ждёт не дольше [BEFORE_LOGOUT_TIMEOUT_MS]. Так же устроен клиент сайта.
     */
    fun onBeforeLogout(task: suspend () -> Unit): () -> Unit {
        beforeLogout += task
        return { beforeLogout.remove(task) }
    }

    private suspend fun runBeforeLogout() =
        coroutineScope {
            beforeLogout.toList().forEach { task ->
                launch {
                    try {
                        withTimeoutOrNull(BEFORE_LOGOUT_TIMEOUT_MS) { task() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Нет связи или сервер отказал — выход всё равно продолжается.
                    }
                }
            }
        }

    // --- Профиль ---

    suspend fun getMe(): UserProfile = decode(UserProfile.serializer(), authorized(HttpMethod.Get, ApiPaths.ME))

    suspend fun updateMe(update: UpdateProfileRequest): UserProfile =
        decode(UserProfile.serializer(), authorized(HttpMethod.Patch, ApiPaths.ME, encode(UpdateProfileRequest.serializer(), update)))

    // --- Запросы помощи ---

    /** Незрячий просит помощи. */
    suspend fun createRequest(body: CreateHelpRequest = CreateHelpRequest()): HelpRequest =
        decode(HelpRequest.serializer(), authorized(HttpMethod.Post, ApiPaths.REQUESTS, encode(CreateHelpRequest.serializer(), body)))

    /** Активный запрос или идущий звонок пользователя; `null` — нет. */
    suspend fun currentRequest(): HelpRequest? {
        val response = authorized(HttpMethod.Get, ApiPaths.REQUESTS_CURRENT)
        return if (response.status == HTTP_NO_CONTENT) null else decode(HelpRequest.serializer(), response)
    }

    /**
     * Вызовы, которые ждут ответа волонтёра. События без соединения сервер не повторяет, поэтому
     * список перечитывается после каждого подключения WebSocket и при открытии из push-уведомления.
     */
    suspend fun incomingRequests(): List<HelpRequest> =
        decode(IncomingHelpRequests.serializer(), authorized(HttpMethod.Get, ApiPaths.REQUESTS_INCOMING)).requests

    suspend fun getRequest(requestId: String): HelpRequest =
        decode(HelpRequest.serializer(), authorized(HttpMethod.Get, ApiPaths.request(requestId)))

    /** Незрячий отменяет поиск или завершает звонок; волонтёр завершает звонок. Повторный вызов безопасен. */
    suspend fun cancelRequest(requestId: String): HelpRequest =
        decode(HelpRequest.serializer(), authorized(HttpMethod.Delete, ApiPaths.request(requestId)))

    /** Волонтёр принимает запрос; в ответе — данные для входа в звонок. */
    suspend fun acceptRequest(requestId: String): HelpRequest =
        decode(HelpRequest.serializer(), authorized(HttpMethod.Post, ApiPaths.requestAccept(requestId)))

    suspend fun rateRequest(
        requestId: String,
        helped: Boolean,
    ) {
        authorized(HttpMethod.Post, ApiPaths.requestRating(requestId), encode(Rating.serializer(), Rating(helped)))
    }

    // --- Push-уведомления ---

    suspend fun pushConfig(): PushConfig = decode(PushConfig.serializer(), authorized(HttpMethod.Get, ApiPaths.PUSH_CONFIG))

    /** Включает push-уведомления на устройстве. Повторная регистрация того же токена безопасна. */
    suspend fun registerDevice(request: RegisterDeviceRequest): Device =
        decode(Device.serializer(), authorized(HttpMethod.Post, ApiPaths.DEVICES, encode(RegisterDeviceRequest.serializer(), request)))

    /** Выключает push-уведомления на устройстве (перед выходом). Повторный вызов безопасен. */
    suspend fun deleteDevice(deviceId: String) {
        authorized(HttpMethod.Delete, ApiPaths.device(deviceId))
    }

    // --- Токены и сеанс ---

    /** Access-токен, который будет действовать ещё хотя бы [minValidityMs]; при необходимости обновляет токены. */
    override suspend fun accessToken(minValidityMs: Long): AccessToken {
        val session = storage.load() ?: throw sessionEnded(SessionEndReason.LOGGED_OUT)
        if (session.accessTokenExpiresAt - now() > minValidityMs) return session.token()
        return refresh(session.accessToken, minValidityMs)
    }

    /** Обновляет токены, даже если access-токен ещё не истёк: сервер его не принял (WebSocket закрыт с кодом 4401). */
    override suspend fun refreshNow(): AccessToken {
        val session = storage.load() ?: throw sessionEnded(SessionEndReason.LOGGED_OUT)
        return refresh(session.accessToken, MIN_TOKEN_VALIDITY_MS)
    }

    /** Удаляет токены и сообщает в [sessionEnds], что сеанс закончился. */
    override fun endSession(reason: SessionEndReason) {
        storage.clear()
        sessionEndEvents.tryEmit(reason)
    }

    private fun sessionEnded(reason: SessionEndReason): ApiClientException.SessionEnded {
        endSession(reason)
        return ApiClientException.SessionEnded(reason)
    }

    private suspend fun login(
        path: String,
        body: String,
    ): UserProfile {
        val response = send(HttpMethod.Post, path, body)
        if (!response.isSuccess) throw errorOf(response)
        val auth = decode(AuthResponse.serializer(), response)
        save(auth)
        return auth.user
    }

    /**
     * Обновляет токены. [staleAccessToken] — токен, который не подошёл или истекает: если пока мы ждали
     * своей очереди, его уже заменил другой запрос, второй раз обновлять не нужно.
     */
    private suspend fun refresh(
        staleAccessToken: String,
        minValidityMs: Long = MIN_TOKEN_VALIDITY_MS,
    ): AccessToken =
        refreshLock.withLock {
            val session = storage.load() ?: throw sessionEnded(SessionEndReason.LOGGED_OUT)
            if (session.accessToken != staleAccessToken && session.accessTokenExpiresAt - now() > minValidityMs) {
                return@withLock session.token()
            }
            val response =
                send(
                    HttpMethod.Post,
                    ApiPaths.AUTH_REFRESH,
                    encode(RefreshTokenRequest.serializer(), RefreshTokenRequest(session.refreshToken)),
                )
            if (response.isSuccess) return@withLock save(decode(AuthResponse.serializer(), response)).token()
            val error = errorOf(response)
            if (response.status == HTTP_UNAUTHORIZED) throw sessionEnded(SessionEndReason.EXPIRED)
            if (error.apiErrorCode == ApiErrorCodes.USER_BANNED) throw sessionEnded(SessionEndReason.BANNED)
            throw error
        }

    private fun save(auth: AuthResponse): StoredSession {
        val session =
            StoredSession(
                userId = auth.user.id,
                accessToken = auth.accessToken,
                accessTokenExpiresAt = now() + auth.accessTokenExpiresIn * 1000,
                refreshToken = auth.refreshToken,
            )
        storage.save(session)
        return session
    }

    /** Запрос от имени пользователя. Ответ 401 — один раз обновить токены и повторить. */
    private suspend fun authorized(
        method: HttpMethod,
        path: String,
        body: String? = null,
    ): RawResponse {
        var token = accessToken(MIN_TOKEN_VALIDITY_MS)
        var response = send(method, path, body, token.value)
        if (response.status == HTTP_UNAUTHORIZED) {
            token = refresh(token.value)
            response = send(method, path, body, token.value)
            if (response.status == HTTP_UNAUTHORIZED) throw sessionEnded(SessionEndReason.EXPIRED)
        }
        if (!response.isSuccess) {
            val error = errorOf(response)
            if (error.apiErrorCode == ApiErrorCodes.USER_BANNED) throw sessionEnded(SessionEndReason.BANNED)
            throw error
        }
        return response
    }

    private suspend fun send(
        method: HttpMethod,
        path: String,
        body: String? = null,
        accessToken: String? = null,
    ): RawResponse =
        try {
            val response =
                http.request(baseUrl + path) {
                    this.method = method
                    accept(ContentType.Application.Json)
                    if (accessToken != null) bearerAuth(accessToken)
                    if (body != null) setBody(TextContent(body, ContentType.Application.Json))
                }
            RawResponse(response.status.value, response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Нет сети, сервер недоступен, соединение оборвалось.
            throw ApiClientException.Network(e)
        }

    private fun <T> encode(
        serializer: KSerializer<T>,
        value: T,
    ): String = json.encodeToString(serializer, value)

    private fun <T> decode(
        serializer: KSerializer<T>,
        response: RawResponse,
    ): T =
        try {
            json.decodeFromString(serializer, response.body)
        } catch (e: IllegalArgumentException) {
            throw ApiClientException.UnexpectedResponse("Cannot parse response: ${e.message}", e)
        }

    /**
     * Ошибка из ответа сервера. Ответ 5xx без тела в формате API — это не наш сервер, а прокси перед ним
     * (backend выключен или перезапускается), поэтому для пользователя это «нет связи с сервером».
     */
    private fun errorOf(response: RawResponse): ApiClientException {
        val code =
            try {
                ((json.parseToJsonElement(response.body) as? JsonObject)?.get("code") as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
            } catch (e: IllegalArgumentException) {
                null
            }
        return when {
            code != null -> ApiClientException.Server(response.status, code, "")
            response.status >= HTTP_SERVER_ERROR -> ApiClientException.Network()
            else -> ApiClientException.Server(response.status, UNKNOWN_ERROR_CODE, "")
        }
    }

    private fun StoredSession.token() = AccessToken(accessToken, accessTokenExpiresAt)

    private class RawResponse(
        val status: Int,
        val body: String,
    ) {
        val isSuccess: Boolean get() = status in 200..299
    }

    companion object {
        /** Access-токен обновляется заранее, если до его истечения осталось меньше этого. */
        const val MIN_TOKEN_VALIDITY_MS = 30_000L

        /** Сколько ждать каждую задачу перед выходом ([onBeforeLogout]), если нет связи. */
        const val BEFORE_LOGOUT_TIMEOUT_MS = 5_000L

        /** Код ошибки, если ответ не в формате API. */
        const val UNKNOWN_ERROR_CODE = "unknown"

        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_SERVER_ERROR = 500

        /**
         * JSON ответов и запросов. Неизвестные поля в ответах пропускаются (их может добавить новый сервер),
         * `null` в запросах не отправляется: отсутствующее поле сервер понимает как «не менять».
         */
        internal val json: Json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
    }
}
