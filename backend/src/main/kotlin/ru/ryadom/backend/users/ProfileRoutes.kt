package ru.ryadom.backend.users

import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import ru.ryadom.backend.auth.JWT_AUTH
import ru.ryadom.backend.auth.userId
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.UpdateProfileRequest

/** `GET /me` и `PATCH /me` — только для вошедшего пользователя. */
fun Route.profileRoutes(profiles: ProfileService) {
    authenticate(JWT_AUTH) {
        get(ApiPaths.ME) {
            call.respond(profiles.get(call.userId))
        }
        patch(ApiPaths.ME) {
            val request = call.receive<UpdateProfileRequest>()
            call.respond(profiles.update(call.userId, request))
        }
    }
}
