package ru.ryadom.backend.realtime

import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import ru.ryadom.backend.requests.requestHelp
import ru.ryadom.backend.testing.ApiTestScope
import ru.ryadom.backend.testing.TestDatabase
import ru.ryadom.backend.testing.apiTest
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.AuthResponse
import ru.ryadom.shared.api.ClientMessage
import ru.ryadom.shared.api.Realtime
import ru.ryadom.shared.api.RefreshTokenRequest
import ru.ryadom.shared.api.ServerEvent
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/** Протокол WebSocket `/ws`: вход первым сообщением, продление токеном, коды закрытия. */
class RealtimeTest {
    @Test
    fun connectionWithoutAuthIsClosed() =
        apiTest {
            val connection = openRealtime()

            assertEquals(Realtime.CLOSE_UNAUTHORIZED, connection.awaitClose()?.code)
        }

    @Test
    fun invalidFirstMessageClosesConnection() =
        apiTest {
            for (first in listOf(Realtime.encode(ClientMessage.Auth("not-a-jwt")), """{"type":"ping"}""", "hello")) {
                val connection = openRealtime()
                connection.sendText(first)
                assertEquals(Realtime.CLOSE_UNAUTHORIZED, connection.awaitClose()?.code, "first message: $first")
            }
        }

    @Test
    fun bannedUserIsDisconnected() =
        apiTest {
            val user = volunteer("volunteer-1")
            TestDatabase.execute("UPDATE users SET banned_at = now()")
            val connection = openRealtime()

            connection.send(ClientMessage.Auth(user.accessToken))

            assertEquals(Realtime.CLOSE_BANNED, connection.awaitClose()?.code)
        }

    @Test
    fun connectionClosesWhenTokenExpires() =
        apiTest {
            val connection = connect(volunteer("volunteer-1"))

            clock.advance(Duration.ofMinutes(16))
            // Любое сообщение будит соединение; на настоящем сервере оно закроется само по таймеру.
            connection.sendText("""{"type":"ping"}""")

            assertEquals(Realtime.CLOSE_UNAUTHORIZED, connection.awaitClose()?.code)
        }

    @Test
    fun newAuthMessageExtendsConnection() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            val connection = connect(volunteer)

            clock.advance(Duration.ofMinutes(14))
            val refreshed = refresh(volunteer)
            connection.send(ClientMessage.Auth(refreshed.accessToken))
            clock.advance(Duration.ofMinutes(2))
            connection.sendText("""{"type":"ping"}""")

            // Соединение живо: событие о новом запросе доходит.
            val request = requestHelp(blind())
            assertEquals(request.id, connection.nextOf<ServerEvent.RequestIncoming>().request.id)
        }

    @Test
    fun tokenOfAnotherUserClosesConnection() =
        apiTest {
            val connection = connect(volunteer("volunteer-1"))

            connection.send(ClientMessage.Auth(volunteer("volunteer-2").accessToken))

            assertEquals(Realtime.CLOSE_UNAUTHORIZED, connection.awaitClose()?.code)
        }

    @Test
    fun unknownMessagesAreIgnored() =
        apiTest {
            val connection = connect(volunteer("volunteer-1"))

            connection.sendText("""{"type":"typing","text":"x"}""")
            connection.assertNoEvents()

            val request = requestHelp(blind())
            assertEquals(request.id, connection.nextOf<ServerEvent.RequestIncoming>().request.id)
        }

    private suspend fun ApiTestScope.refresh(user: AuthResponse): AuthResponse {
        val response = postJson(ApiPaths.AUTH_REFRESH, RefreshTokenRequest(user.refreshToken))
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }
}
