package ru.ryadom.shared.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthResponseTest {
    @Test
    fun serializesToContractJson() {
        val json = Json.encodeToString(HealthResponse(HealthResponse.STATUS_OK))
        assertEquals("""{"status":"ok"}""", json)
    }

    @Test
    fun deserializesFromContractJson() {
        val response = Json.decodeFromString<HealthResponse>("""{"status":"ok"}""")
        assertEquals(HealthResponse(HealthResponse.STATUS_OK), response)
    }
}
