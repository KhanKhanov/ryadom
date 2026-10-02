package ru.ryadom.backend.errors

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import ru.ryadom.backend.testing.apiTest
import ru.ryadom.backend.testing.assertError
import ru.ryadom.shared.api.ApiErrorCodes
import ru.ryadom.shared.api.ApiPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ErrorHandlingTest {
    @Test
    fun unknownPathReturnsJsonError() =
        apiTest {
            client.get("/no-such-path").assertError(HttpStatusCode.NotFound, ApiErrorCodes.NOT_FOUND)
        }

    @Test
    fun malformedJsonReturnsInvalidRequest() =
        apiTest {
            client
                .post(ApiPaths.AUTH_DEV) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"login": """)
                }.assertError(HttpStatusCode.BadRequest, ApiErrorCodes.INVALID_REQUEST)
        }

    @Test
    fun missingContentTypeIsReported() =
        apiTest {
            client
                .post(ApiPaths.AUTH_DEV) { setBody("""{"login":"a"}""") }
                .assertError(HttpStatusCode.BadRequest, ApiErrorCodes.INVALID_REQUEST)
        }

    @Test
    fun wrongMethodReturnsJsonError() =
        apiTest {
            client.put(ApiPaths.ME).assertError(HttpStatusCode.MethodNotAllowed, ApiErrorCodes.INVALID_REQUEST)
        }

    @Test
    fun loggedExceptionHasNoMessages() {
        val original = IllegalStateException("Key (display_name)=(Анна) violates check", RuntimeException("secret token"))

        val hidden = original.withoutMessages()

        assertFalse("Анна" in hidden.message.orEmpty())
        assertFalse("secret" in hidden.cause?.message.orEmpty())
        assertEquals(original.stackTrace.toList(), hidden.stackTrace.toList())
        assertNull(hidden.cause?.cause)
    }
}
