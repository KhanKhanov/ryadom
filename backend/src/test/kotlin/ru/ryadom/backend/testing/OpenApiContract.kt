package ru.ryadom.backend.testing

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import tools.jackson.databind.JsonNode
import tools.jackson.dataformat.yaml.YAMLMapper
import java.io.File

/**
 * Контракт API в тестах: каждый ответ сервера, который получают тесты через [apiTest], сверяется
 * с `docs/api/openapi.yaml` — статус описан у этой операции, тело соответствует схеме ответа
 * (JSON Schema 2020-12, диалект OpenAPI 3.1). Так спецификация и сервер не расходятся незаметно:
 * поменяли ответ сервера и забыли `openapi.yaml` (или наоборот) — тесты падают.
 *
 * Тела запросов не проверяются: тесты нарочно отправляют неверные запросы, чтобы проверить ошибки 400.
 * Ответы на пути и методы, которых в спецификации нет (тест неизвестного адреса), должны быть ошибкой
 * в формате `Error`: это общее обещание API.
 */
object OpenApiContract {
    private val specFile =
        File(System.getProperty("ryadom.openapi") ?: "../docs/api/openapi.yaml").also {
            check(it.isFile) { "Не найден docs/api/openapi.yaml: ${it.absolutePath}" }
        }
    private val spec: JsonNode = YAMLMapper().readTree(specFile)

    /** Адрес, под которым валидатор знает спецификацию (сеть не нужна: содержимое отдаётся из файла). */
    private const val DOCUMENT_IRI = "https://ryadom.test/openapi.yaml"
    private val registry =
        SchemaRegistry.withDialect(Dialects.getOpenApi31()) { builder ->
            builder.schemas(mapOf(DOCUMENT_IRI to specFile.readText()))
        }
    private val document = SchemaLocation.of(DOCUMENT_IRI)

    /** Шаблоны путей спецификации: литеральные (`/requests/current`) раньше шаблонов с параметрами (`/requests/{requestId}`). */
    private val paths: List<Pair<String, Regex>> =
        spec["paths"]
            .propertyNames()
            .map { template -> template to Regex("^" + template.split('/').joinToString("/") { segmentPattern(it) } + "$") }
            .sortedBy { (template, _) -> template.count { it == '{' } }

    /** Плагин клиента теста: проверяет каждый ответ. Нарушение контракта — падение теста с объяснением. */
    val plugin =
        createClientPlugin("OpenApiContract") {
            onResponse { response ->
                // Переход на WebSocket: тела нет, дальше идут сообщения — их проверяют тесты /ws.
                if (response.status.value == HTTP_SWITCHING_PROTOCOLS) return@onResponse
                val problems =
                    check(
                        method = response.call.request.method.value,
                        path = response.call.request.url.encodedPath,
                        status = response.status.value,
                        contentType = response.headers[HttpHeaders.ContentType],
                        body = response.bodyAsText(),
                    )
                if (problems.isNotEmpty()) {
                    throw AssertionError(
                        "Ответ ${response.call.request.method.value} ${response.call.request.url.encodedPath} → ${response.status.value} " +
                            "не соответствует docs/api/openapi.yaml:\n" + problems.joinToString("\n") { "- $it" },
                    )
                }
            }
        }

    /** Нарушения контракта в ответе; пустой список — всё по спецификации. */
    fun check(
        method: String,
        path: String,
        status: Int,
        contentType: String?,
        body: String,
    ): List<String> {
        val template = paths.firstOrNull { (_, regex) -> regex.matches(path) }?.first
        val operation = template?.let { spec["paths"][it][method.lowercase()] }
        if (template == null || operation == null) {
            if (status < HTTP_BAD_REQUEST) return listOf("операции $method $path нет в спецификации")
            return validate(document.append("components").append("schemas").append("Error"), contentType, body)
        }
        val responses = operation["responses"]
        val code = status.toString()
        if (!responses.has(code)) {
            return listOf("у операции $method $template не описан ответ $status (описаны: ${responses.propertyNames().joinToString()})")
        }
        // Ответ может быть ссылкой на общий: '#/components/responses/Unauthorized'.
        var location =
            document
                .append("paths")
                .append(template)
                .append(method.lowercase())
                .append("responses")
                .append(code)
        var response = responses[code]
        response["\$ref"]?.let { ref ->
            val name = ref.asString().removePrefix("#/components/responses/")
            location = document.append("components").append("responses").append(name)
            response = spec["components"]["responses"][name]
        }
        val json = response["content"]?.get("application/json")
        if (json == null) {
            return if (body.isEmpty()) emptyList() else listOf("у ответа $status не должно быть тела, а пришло: ${body.take(MAX_SHOWN)}")
        }
        return validate(location.append("content").append("application/json").append("schema"), contentType, body)
    }

    private fun validate(
        schemaLocation: SchemaLocation,
        contentType: String?,
        body: String,
    ): List<String> {
        if (contentType?.startsWith("application/json") != true) return listOf("тип ответа $contentType, а нужен application/json")
        return registry
            .getSchema(schemaLocation)
            .validate(body, InputFormat.JSON)
            .map { "${it.instanceLocation}: ${it.message} (ответ: ${body.take(MAX_SHOWN)})" }
    }

    private fun segmentPattern(segment: String) = if (segment.startsWith("{") && segment.endsWith("}")) "[^/]+" else Regex.escape(segment)

    private const val HTTP_SWITCHING_PROTOCOLS = 101
    private const val HTTP_BAD_REQUEST = 400
    private const val MAX_SHOWN = 300
}
