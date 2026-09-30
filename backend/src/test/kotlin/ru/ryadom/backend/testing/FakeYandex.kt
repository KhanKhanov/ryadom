package ru.ryadom.backend.testing

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseUrlEncodedParameters
import ru.ryadom.backend.YandexConfig
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Поддельный Яндекс ID: отвечает как `oauth.yandex.ru/token` и `login.yandex.ru/info`,
 * но без сети. Тест настраивает, какие коды и токены «существуют».
 */
class FakeYandex {
    /** Код авторизации → токен Яндекса. */
    val codes = mutableMapOf<String, String>()

    /** Токен Яндекса → ответ `/info`. */
    val users = mutableMapOf<String, UserInfo>()

    /** Если задан — все запросы получают этот статус (имитация сбоя Яндекса). */
    var failWith: HttpStatusCode? = null

    /** Формы, отправленные на `/token`. */
    val tokenRequests = CopyOnWriteArrayList<Parameters>()

    data class UserInfo(
        val id: String,
        val clientId: String = CLIENT_ID,
        val firstName: String? = null,
    )

    val httpClient = HttpClient(MockEngine { request -> handle(request) })

    fun addUser(
        token: String,
        info: UserInfo,
    ) {
        users[token] = info
    }

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        failWith?.let { return respondError(it) }
        return when ("${request.url.host}${request.url.encodedPath}") {
            "oauth.test/token" -> token(request)
            "login.test/info" -> info(request)
            else -> respondError(HttpStatusCode.NotFound)
        }
    }

    private fun MockRequestHandleScope.token(request: HttpRequestData): HttpResponseData {
        val form = ((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).parseUrlEncodedParameters()
        tokenRequests += form
        if (form["client_id"] != CLIENT_ID) return json("""{"error":"invalid_client"}""", HttpStatusCode.BadRequest)
        val token =
            codes[form["code"]]
                ?: return json("""{"error":"invalid_grant","error_description":"Code has expired"}""", HttpStatusCode.BadRequest)
        return json("""{"access_token":"$token","token_type":"bearer","expires_in":31536000}""")
    }

    private fun MockRequestHandleScope.info(request: HttpRequestData): HttpResponseData {
        val token = request.headers[HttpHeaders.Authorization]?.removePrefix("OAuth ")
        val user = users[token] ?: return respondError(HttpStatusCode.Unauthorized)
        val firstName = user.firstName?.let { ""","first_name":"$it"""" }.orEmpty()
        // Лишние поля (login, psuid) — как в настоящем ответе: сервер должен их игнорировать.
        return json("""{"id":"${user.id}","login":"user${user.id}","client_id":"${user.clientId}","psuid":"x"$firstName}""")
    }

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

    companion object {
        const val CLIENT_ID = "test-client-id"

        val config =
            YandexConfig(
                clientId = CLIENT_ID,
                clientSecret = "test-client-secret",
                extraClientIds = setOf("android-client-id"),
                oauthUrl = "https://oauth.test",
                loginUrl = "https://login.test",
            )
    }
}
