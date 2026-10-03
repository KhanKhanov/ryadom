package ru.ryadom.backend.push

import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebPushCryptoTest {
    /** Пример из RFC 8291, раздел 5: те же ключи, соль и текст должны дать ровно те же байты. */
    @Test
    fun matchesTheExampleFromRfc8291() {
        val senderKeys =
            KeyPair(
                EcKeys.publicKey(
                    Base64Url.decode("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"),
                ),
                EcKeys.privateKey(Base64Url.decode("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")),
            )

        val body =
            WebPushCrypto.encrypt(
                plaintext = "When I grow up, I want to be a watermelon".toByteArray(),
                receiverPublicKey =
                    Base64Url.decode("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
                authSecret = Base64Url.decode("BTBZMqHH6r4Tts7J_aSIgg"),
                senderKeys = senderKeys,
                salt = Base64Url.decode("DGv6ra1nlYgDCS1FRnbzlw"),
            )

        assertEquals(
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_y" +
                "l95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
            Base64Url.encode(body),
        )
    }

    @Test
    fun browserDecryptsWhatServerEncrypted() {
        val browser = EcKeys.generate()
        val auth = ByteArray(16) { (it * 7).toByte() }
        val message = """{"type":"request.incoming","requestId":"0199a1b2-0000-7000-8000-000000000002"}"""

        val body = WebPushCrypto.encrypt(message.toByteArray(), EcKeys.publicBytes(browser.public as ECPublicKey), auth)

        assertEquals(message, decryptLikeBrowser(body, browser, auth).decodeToString())
        // Каждый раз — новые одноразовые ключ и соль: одинаковые уведомления выглядят по-разному.
        val again = WebPushCrypto.encrypt(message.toByteArray(), EcKeys.publicBytes(browser.public as ECPublicKey), auth)
        assertEquals(false, body.contentEquals(again))
    }

    @Test
    fun publicKeyMustBeAPointOnTheCurve() {
        val valid = EcKeys.publicBytes(EcKeys.generate().public as ECPublicKey)
        val broken = valid.copyOf().also { it[64] = (it[64] + 1).toByte() }

        assertContentEquals(valid, EcKeys.publicBytes(EcKeys.publicKey(valid)))
        assertFailsWith<IllegalArgumentException> { EcKeys.publicKey(broken) }
        assertFailsWith<IllegalArgumentException> { EcKeys.publicKey(valid.copyOf(33)) }
    }

    @Test
    fun tooLongMessageIsRejected() {
        val browser = EcKeys.generate()
        val receiver = EcKeys.publicBytes(browser.public as ECPublicKey)

        WebPushCrypto.encrypt(ByteArray(WebPushCrypto.MAX_PLAINTEXT_SIZE), receiver, ByteArray(16))
        assertFailsWith<IllegalArgumentException> {
            WebPushCrypto.encrypt(ByteArray(WebPushCrypto.MAX_PLAINTEXT_SIZE + 1), receiver, ByteArray(16))
        }
    }

    /** Расшифровка так, как её делает браузер (RFC 8291, RFC 8188), — независимо от кода сервера. */
    private fun decryptLikeBrowser(
        body: ByteArray,
        browser: KeyPair,
        auth: ByteArray,
    ): ByteArray {
        val salt = body.copyOfRange(0, 16)
        val recordSize = ByteBuffer.wrap(body, 16, 4).int
        val keyLength = body[20].toInt()
        val senderPublic = body.copyOfRange(21, 21 + keyLength)
        val ciphertext = body.copyOfRange(21 + keyLength, body.size)
        assertEquals(4096, recordSize)

        val ecdh =
            KeyAgreement.getInstance("ECDH").run {
                init(browser.private)
                doPhase(EcKeys.publicKey(senderPublic), true)
                generateSecret()
            }
        val browserPublic = EcKeys.publicBytes(browser.public as ECPublicKey)
        val ikm = hmac(hmac(auth, ecdh), "WebPush: info".toByteArray() + 0 + browserPublic + senderPublic + 1)
        val prk = hmac(salt, ikm)
        val key = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + 0 + 1).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + 0 + 1).copyOf(12)
        val padded =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                doFinal(ciphertext)
            }
        // Текст, затем 0x02 (последняя запись) и, возможно, нули выравнивания.
        val delimiter = padded.indexOfLast { it != 0.toByte() }
        assertEquals(2.toByte(), padded[delimiter])
        return padded.copyOf(delimiter)
    }

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private operator fun ByteArray.plus(byte: Int): ByteArray = this + byte.toByte()
}
