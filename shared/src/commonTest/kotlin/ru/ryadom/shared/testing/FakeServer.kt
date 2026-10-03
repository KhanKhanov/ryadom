package ru.ryadom.shared.testing

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.KSerializer
import ru.ryadom.shared.api.AuthResponse
import ru.ryadom.shared.api.CallCredentials
import ru.ryadom.shared.api.DoNotDisturb
import ru.ryadom.shared.api.Gender
import ru.ryadom.shared.api.GenderPreference
import ru.ryadom.shared.api.HelpRequest
import ru.ryadom.shared.api.Language
import ru.ryadom.shared.api.RequestStatus
import ru.ryadom.shared.api.Role
import ru.ryadom.shared.api.UserProfile
import ru.ryadom.shared.client.ApiClient
import ru.ryadom.shared.client.InMemorySessionStorage
import ru.ryadom.shared.client.StoredSession

/** Запрос, который клиент отправил поддельному серверу. */
data class RecordedRequest(
    val method: String,
    val path: String,
    val body: String?,
    val authorization: String?,
)

class FakeResponse(
    val status: Int,
    val body: String = "",
)

/** Сетевой сбой: обработчик бросает это исключение, клиент должен увидеть «нет связи». */
class FakeNetworkFailure : Exception("Connection refused")

/**
 * Поддельный backend для тестов клиента: отвечает так, как скажет тест, и записывает запросы.
 * Маршрут без обработчика отвечает 404 в формате API.
 * @param dispatcher поток обработки запросов; в тестах с виртуальным временем — диспетчер теста,
 *   чтобы ответы приходили детерминированно.
 */
class FakeServer(
    dispatcher: CoroutineDispatcher? = null,
) {
    val requests = mutableListOf<RecordedRequest>()
    private val handlers = mutableMapOf<String, (RecordedRequest) -> FakeResponse>()

    val engine =
        MockEngine(
            MockEngineConfig().apply {
                if (dispatcher != null) this.dispatcher = dispatcher
                addHandler { request ->
                    val recorded =
                        RecordedRequest(
                            method = request.method.value,
                            path = request.url.encodedPath,
                            body = (request.body as? TextContent)?.text,
                            authorization = request.headers[HttpHeaders.Authorization],
                        )
                    requests += recorded
                    val handler = handlers["${recorded.method} ${recorded.path}"]
                    val response = handler?.invoke(recorded) ?: error(404, "not_found")
                    respond(
                        content = response.body,
                        status = HttpStatusCode.fromValue(response.status),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            },
        )

    /** Как отвечать на `METHOD /path`. Обработчик может бросить [FakeNetworkFailure]. */
    fun on(
        method: String,
        path: String,
        handler: (RecordedRequest) -> FakeResponse,
    ) {
        handlers["$method $path"] = handler
    }

    fun requestsTo(
        method: String,
        path: String,
    ): List<RecordedRequest> = requests.filter { it.method == method && it.path == path }

    companion object {
        fun json(
            status: Int,
            body: String,
        ) = FakeResponse(status, body)

        fun <T> ok(
            serializer: KSerializer<T>,
            value: T,
            status: Int = 200,
        ) = FakeResponse(status, ApiClient.json.encodeToString(serializer, value))

        fun error(
            status: Int,
            code: String,
        ) = FakeResponse(status, """{"code":"$code","message":"test"}""")
    }
}

// --- Данные для тестов ---

const val USER_ID = "0199a1b2-0000-7000-8000-000000000001"
const val REQUEST_ID = "0199a1b2-0000-7000-8000-000000000002"
const val OTHER_REQUEST_ID = "0199a1b2-0000-7000-8000-000000000003"

fun profile(role: Role? = Role.BLIND) =
    UserProfile(
        id = USER_ID,
        role = role,
        displayName = "Анна",
        languages = listOf(Language.RU),
        gender = Gender.UNSPECIFIED,
        genderPreference = GenderPreference.ANY,
        timezone = "Europe/Moscow",
        doNotDisturb = DoNotDisturb("22:00", "08:00"),
        notificationsEnabled = true,
        createdAt = "2026-09-30T10:00:00Z",
    )

fun authResponse(
    accessToken: String = "access-1",
    refreshToken: String = "refresh-1",
    role: Role? = Role.BLIND,
) = AuthResponse(accessToken = accessToken, accessTokenExpiresIn = 900, refreshToken = refreshToken, user = profile(role))

val callCredentials = CallCredentials(url = "ws://localhost:7880", room = REQUEST_ID, token = "livekit-token")

fun helpRequest(
    status: RequestStatus,
    id: String = REQUEST_ID,
    call: CallCredentials? = if (status == RequestStatus.ACCEPTED || status == RequestStatus.IN_CALL) callCredentials else null,
) = HelpRequest(
    id = id,
    status = status,
    language = Language.RU,
    genderPreference = GenderPreference.ANY,
    createdAt = "2026-09-30T10:00:00Z",
    acceptedAt = if (call != null) "2026-09-30T10:00:12Z" else null,
    endedAt = if (status.isActive) null else "2026-09-30T10:05:00Z",
    call = call,
)

/** Хранилище с действующим входом: access-токен истекает через 15 минут после [now]. */
fun signedInStorage(now: Long = 0L) =
    InMemorySessionStorage(
        StoredSession(userId = USER_ID, accessToken = "access-0", accessTokenExpiresAt = now + 900_000, refreshToken = "refresh-0"),
    )
