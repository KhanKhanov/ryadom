package ru.ryadom.shared.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** JSON устройств и push-уведомлений должен совпадать со схемами в `docs/api/openapi.yaml`. */
class PushModelsSerializationTest {
    @Test
    fun providersUseContractNames() {
        assertEquals(listOf("\"fcm\"", "\"rustore\"", "\"webpush\""), PushProvider.entries.map { Json.encodeToString(it) })
    }

    @Test
    fun webPushRegistrationCarriesKeys() {
        val request =
            RegisterDeviceRequest(
                provider = PushProvider.WEB_PUSH,
                token = "https://fcm.googleapis.com/fcm/send/abc",
                webPush = WebPushKeys(p256dh = "BPub", auth = "secret"),
            )

        assertEquals(
            """{"provider":"webpush","token":"https://fcm.googleapis.com/fcm/send/abc","webPush":{"p256dh":"BPub","auth":"secret"}}""",
            Json.encodeToString(request),
        )
    }

    @Test
    fun tokensAndKeysAreNotPrinted() {
        val request = RegisterDeviceRequest(PushProvider.FCM, token = "device-token", webPush = WebPushKeys("p256dh-key", "auth-key"))

        val printed = request.toString() + request.webPush.toString()

        for (secret in listOf("device-token", "p256dh-key", "auth-key")) assertFalse(secret in printed)
    }

    @Test
    fun pushConfigAllowsMissingWebPush() {
        assertEquals(PushConfig(null), Json.decodeFromString<PushConfig>("""{"webPushPublicKey":null}"""))
        assertEquals(PushConfig("BKey"), Json.decodeFromString<PushConfig>("""{"webPushPublicKey":"BKey"}"""))
    }

    @Test
    fun pushMessageDataHasOnlyTypeAndRequestId() {
        val message = PushMessage(PushMessageType.REQUEST_INCOMING, "request-1")

        assertEquals(mapOf("type" to "request.incoming", "requestId" to "request-1"), message.toData())
        assertEquals(message, PushMessage.fromData(message.toData()))
        assertEquals(
            PushMessage(PushMessageType.REQUEST_CLOSED, "request-1"),
            PushMessage.fromData(mapOf("type" to "request.closed", "requestId" to "request-1", "extra" to "ignored")),
        )
    }

    @Test
    fun unknownOrIncompletePushMessagesAreSkipped() {
        assertNull(PushMessage.fromData(mapOf("type" to "request.reminder", "requestId" to "request-1")))
        assertNull(PushMessage.fromData(mapOf("type" to "request.incoming")))
        assertNull(PushMessage.fromData(mapOf("type" to "request.incoming", "requestId" to " ")))
    }

    @Test
    fun devicePathIsBuiltFromId() {
        assertEquals("/devices/abc", ApiPaths.device("abc"))
        assertEquals("/devices/{deviceId}", ApiPaths.DEVICE)
    }
}
