package ru.ryadom.backend.requests

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import ru.ryadom.backend.auth.JWT_AUTH
import ru.ryadom.backend.auth.userId
import ru.ryadom.backend.errors.ApiException
import ru.ryadom.backend.livekit.LiveKitService
import ru.ryadom.shared.api.ApiErrorCodes
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.CreateHelpRequest
import ru.ryadom.shared.api.Rating
import kotlin.uuid.Uuid

/** Запросы помощи (`/requests/...`) — только для вошедшего пользователя. */
fun Route.requestRoutes(requests: HelpRequestService) {
    authenticate(JWT_AUTH) {
        post(ApiPaths.REQUESTS) {
            val body = call.receive<CreateHelpRequest>()
            call.respond(HttpStatusCode.Created, requests.create(call.userId, body))
        }
        get(ApiPaths.REQUESTS_CURRENT) {
            val current = requests.current(call.userId)
            if (current == null) call.respond(HttpStatusCode.NoContent) else call.respond(current)
        }
        get(ApiPaths.REQUESTS_INCOMING) {
            call.respond(requests.incoming(call.userId))
        }
        get(ApiPaths.REQUEST) {
            call.respond(requests.get(call.userId, call.requestId))
        }
        delete(ApiPaths.REQUEST) {
            call.respond(requests.cancel(call.userId, call.requestId))
        }
        post(ApiPaths.REQUEST_ACCEPT) {
            call.respond(requests.accept(call.userId, call.requestId))
        }
        post(ApiPaths.REQUEST_RATING) {
            val body = call.receive<Rating>()
            requests.rate(call.userId, call.requestId, body)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** События комнат от сервера LiveKit. Без входа пользователя: подлинность проверяется по подписи. */
fun Route.liveKitWebhookRoutes(
    requests: HelpRequestService,
    liveKit: LiveKitService,
) {
    post(ApiPaths.WEBHOOKS_LIVEKIT) {
        // Тело читается как есть: подпись LiveKit содержит SHA-256 именно этих байтов (JSON в UTF-8).
        val body = call.receive<ByteArray>().toString(Charsets.UTF_8)
        val event =
            liveKit.parseWebhook(body, call.request.headers[HttpHeaders.Authorization])
                ?: throw ApiException(HttpStatusCode.Unauthorized, ApiErrorCodes.UNAUTHORIZED, "Invalid webhook signature")
        requests.onLiveKitEvent(event)
        call.respond(HttpStatusCode.NoContent)
    }
}

/** id запроса из пути. Неверный формат — 404, как и несуществующий запрос. */
private val ApplicationCall.requestId: Uuid
    get() =
        parameters[ApiPaths.REQUEST_ID_PARAM]?.let { Uuid.parseOrNull(it) }
            ?: throw ApiException.notFound("Help request not found")
