package ru.ryadom.backend.requests

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import ru.ryadom.backend.testing.ApiTestScope
import ru.ryadom.backend.testing.TestDatabase
import ru.ryadom.backend.testing.auth
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.AuthResponse
import ru.ryadom.shared.api.CreateHelpRequest
import ru.ryadom.shared.api.HelpRequest
import ru.ryadom.shared.api.Rating
import kotlin.test.assertEquals

// Короткие вызовы API запросов помощи для тестов.

suspend fun ApiTestScope.createRequest(
    blind: AuthResponse,
    body: CreateHelpRequest = CreateHelpRequest(),
): HttpResponse = postJson(ApiPaths.REQUESTS, body) { auth(blind) }

/** Незрячий просит помощи; запрос должен создаться. */
suspend fun ApiTestScope.requestHelp(
    blind: AuthResponse,
    body: CreateHelpRequest = CreateHelpRequest(),
): HelpRequest {
    val response = createRequest(blind, body)
    assertEquals(HttpStatusCode.Created, response.status)
    return response.body()
}

suspend fun ApiTestScope.getRequest(
    user: AuthResponse,
    requestId: String,
): HttpResponse = client.get(ApiPaths.request(requestId)) { auth(user) }

suspend fun ApiTestScope.currentRequest(user: AuthResponse): HttpResponse = client.get(ApiPaths.REQUESTS_CURRENT) { auth(user) }

suspend fun ApiTestScope.cancelRequest(
    user: AuthResponse,
    requestId: String,
): HttpResponse = client.delete(ApiPaths.request(requestId)) { auth(user) }

suspend fun ApiTestScope.acceptRequest(
    volunteer: AuthResponse,
    requestId: String,
): HttpResponse = client.post(ApiPaths.requestAccept(requestId)) { auth(volunteer) }

suspend fun ApiTestScope.rateRequest(
    user: AuthResponse,
    requestId: String,
    helped: Boolean,
): HttpResponse = postJson(ApiPaths.requestRating(requestId), Rating(helped)) { auth(user) }

/** Сколько волонтёров уведомлено о запросе (по базе). */
fun notifiedCount(requestId: String): Int =
    TestDatabase.queryInt("SELECT count(*) FROM request_notifications WHERE request_id = '$requestId'")

/** Уведомлён ли волонтёр о запросе (по базе). */
fun isNotified(
    requestId: String,
    volunteer: AuthResponse,
): Boolean =
    TestDatabase.queryInt(
        "SELECT count(*) FROM request_notifications WHERE request_id = '$requestId' AND volunteer_id = '${volunteer.user.id}'",
    ) == 1
