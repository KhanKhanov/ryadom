package ru.ryadom.backend.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Контрольные проверки [OpenApiContract]: на заведомо неверных ответах он должен находить нарушения.
 * Если после обновления библиотек проверка молча перестанет работать, упадут эти тесты,
 * а не пропустится расхождение сервера и спецификации.
 */
class OpenApiContractTest {
    private val json = "application/json; charset=UTF-8"

    private val profile =
        """{"id":"0199a1b2-0000-7000-8000-000000000001","role":"volunteer","displayName":"Анна","languages":["ru"],""" +
            """"gender":"unspecified","genderPreference":"any","timezone":"Europe/Moscow",""" +
            """"doNotDisturb":{"from":"22:00","to":"08:00"},"notificationsEnabled":true,"createdAt":"2026-09-30T10:00:00Z"}"""

    @Test
    fun correctResponsesPass() {
        assertEquals(emptyList(), OpenApiContract.check("GET", "/me", 200, json, profile))
        assertEquals(emptyList(), OpenApiContract.check("GET", "/requests/current", 204, null, ""))
        assertEquals(emptyList(), OpenApiContract.check("GET", "/me", 401, json, """{"code":"unauthorized","message":"No token"}"""))
        // Пути нет в спецификации — но ошибка всё равно в общем формате.
        assertEquals(emptyList(), OpenApiContract.check("GET", "/no-such-path", 404, json, """{"code":"not_found","message":"x"}"""))
    }

    @Test
    fun missingRequiredFieldIsReported() {
        val problems = OpenApiContract.check("GET", "/me", 200, json, profile.replace(""""timezone":"Europe/Moscow",""", ""))

        assertTrue(problems.any { "timezone" in it }, problems.toString())
    }

    @Test
    fun wrongEnumValueIsReported() {
        val problems = OpenApiContract.check("GET", "/me", 200, json, profile.replace(""""role":"volunteer"""", """"role":"pilot""""))

        assertTrue(problems.isNotEmpty())
    }

    @Test
    fun schemaReferencedFromSharedResponseIsChecked() {
        // 401 у /me — ссылка на '#/components/responses/Unauthorized' со схемой Error.
        val problems = OpenApiContract.check("GET", "/me", 401, json, """{"message":"no code"}""")

        assertTrue(problems.any { "code" in it }, problems.toString())
    }

    @Test
    fun undocumentedStatusIsReported() {
        val problems = OpenApiContract.check("GET", "/me", 418, json, """{"code":"teapot","message":"x"}""")

        assertTrue(problems.single().contains("не описан ответ 418"), problems.toString())
    }

    @Test
    fun bodyWhereNoneIsDocumentedIsReported() {
        assertTrue(OpenApiContract.check("GET", "/requests/current", 204, json, "{}").isNotEmpty())
    }

    @Test
    fun undocumentedOperationIsReported() {
        assertTrue(OpenApiContract.check("PUT", "/me", 200, json, profile).isNotEmpty())
    }

    @Test
    fun wrongContentTypeIsReported() {
        assertTrue(OpenApiContract.check("GET", "/me", 200, "text/plain", profile).isNotEmpty())
    }

    @Test
    fun literalPathWinsOverTemplate() {
        // /requests/current — своя операция, а не /requests/{requestId} с id «current».
        assertEquals(emptyList(), OpenApiContract.check("GET", "/requests/current", 204, null, ""))
        assertTrue(OpenApiContract.check("GET", "/requests/0199a1b2-0000-7000-8000-000000000002", 204, null, "").isNotEmpty())
    }
}
