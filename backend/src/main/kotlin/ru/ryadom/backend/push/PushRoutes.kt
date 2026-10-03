package ru.ryadom.backend.push

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import ru.ryadom.backend.auth.JWT_AUTH
import ru.ryadom.backend.auth.userId
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.RegisterDeviceRequest
import kotlin.uuid.Uuid

/** Устройства для push-уведомлений — только для вошедшего пользователя. */
fun Route.pushRoutes(devices: DeviceService) {
    authenticate(JWT_AUTH) {
        post(ApiPaths.DEVICES) {
            val request = call.receive<RegisterDeviceRequest>()
            call.respond(devices.register(call.userId, request))
        }
        delete(ApiPaths.DEVICE) {
            // Неверный id — такого устройства нет, удалять нечего: тоже 204, как обещает контракт.
            devices.delete(call.userId, call.parameters[ApiPaths.DEVICE_ID_PARAM]?.let { Uuid.parseOrNull(it) })
            call.respond(HttpStatusCode.NoContent)
        }
        get(ApiPaths.PUSH_CONFIG) {
            call.respond(devices.config(call.userId))
        }
    }
}
