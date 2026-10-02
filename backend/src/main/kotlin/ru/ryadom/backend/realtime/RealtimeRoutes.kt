package ru.ryadom.backend.realtime

import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import ru.ryadom.backend.RealtimeConfig
import ru.ryadom.backend.auth.AccessTokens
import ru.ryadom.backend.users.UserRepository
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.ClientMessage
import ru.ryadom.shared.api.Realtime
import ru.ryadom.shared.api.ServerEvent
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.time.toKotlinDuration
import kotlin.uuid.Uuid

private val log = LoggerFactory.getLogger("ru.ryadom.backend.realtime")

/**
 * WebSocket событий [ApiPaths.REALTIME]. Протокол описан в `docs/api/openapi.yaml` (операция `connectRealtime`):
 * первое сообщение — `auth` с access-токеном, затем сервер присылает `ready` и события.
 */
fun Route.realtimeRoutes(
    hub: RealtimeHub,
    accessTokens: AccessTokens,
    users: UserRepository,
    config: RealtimeConfig,
    clock: Clock,
) {
    webSocket(ApiPaths.REALTIME) {
        RealtimeConnection(this, accessTokens, users, clock).run(hub, config)
    }
}

/** Вход, проверенный по access-токену. Соединение живёт, пока не истёк последний присланный токен. */
private data class Login(
    val userId: Uuid,
    val expiresAt: Instant,
)

private class RealtimeConnection(
    private val session: DefaultWebSocketServerSession,
    private val accessTokens: AccessTokens,
    private val users: UserRepository,
    private val clock: Clock,
) {
    suspend fun run(
        hub: RealtimeHub,
        config: RealtimeConfig,
    ) {
        val firstFrame = withTimeoutOrNull(config.authTimeout.toKotlinDuration()) { session.incoming.receiveCatching() }
        if (firstFrame == null) return closeUnauthorized("No auth message")
        // Клиент закрыл соединение, не успев войти.
        val frame = firstFrame.getOrNull() ?: return
        var login = authenticate(parse(frame)) ?: return

        hub.connect(login.userId, session)
        try {
            session.outgoing.send(Frame.Text(Realtime.encode(ServerEvent.Ready)))
            while (true) {
                val untilExpiry = Duration.between(clock.instant(), login.expiresAt)
                if (untilExpiry.isNegative || untilExpiry.isZero) return closeUnauthorized("Access token expired")
                val next = withTimeoutOrNull(untilExpiry.toKotlinDuration()) { session.incoming.receiveCatching() }
                // Таймаут — токен истёк, это проверит следующий виток цикла.
                if (next == null) continue
                val message = parse(next.getOrNull() ?: return)
                // Новое сообщение auth продлевает соединение; другие сообщения пока не нужны и игнорируются.
                if (message is ClientMessage.Auth) {
                    val renewed = authenticate(message) ?: return
                    if (renewed.userId != login.userId) return closeUnauthorized("Token belongs to another user")
                    login = renewed
                }
            }
        } finally {
            hub.disconnect(login.userId, session)
        }
    }

    /** Проверяет сообщение `auth`. При ошибке закрывает соединение и возвращает `null`. */
    private suspend fun authenticate(message: ClientMessage?): Login? {
        if (message !is ClientMessage.Auth) {
            closeUnauthorized("Expected auth message")
            return null
        }
        val token =
            try {
                accessTokens.verifier.verify(message.accessToken)
            } catch (e: JWTVerificationException) {
                closeUnauthorized("Invalid or expired access token")
                return null
            }
        val userId = token.subject?.let { Uuid.parseOrNull(it) }
        val user = userId?.let { users.findById(it) }
        if (user == null) {
            closeUnauthorized("User not found")
            return null
        }
        if (user.isBanned) {
            session.close(CloseReason(Realtime.CLOSE_BANNED, "User is banned"))
            return null
        }
        return Login(user.id, token.expiresAtAsInstant)
    }

    /** `null` — не текст, не JSON или неизвестный тип сообщения. */
    private fun parse(frame: Frame): ClientMessage? {
        if (frame !is Frame.Text) return null
        return try {
            Realtime.json.decodeFromString(ClientMessage.serializer(), frame.readText())
        } catch (e: SerializationException) {
            null
        }
    }

    private suspend fun closeUnauthorized(reason: String) {
        log.debug("Realtime connection closed: {}", reason)
        session.close(CloseReason(Realtime.CLOSE_UNAUTHORIZED, reason))
    }
}
