package ru.ryadom.backend.push

import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрование уведомлений Web Push (RFC 8291, формат aes128gcm из RFC 8188). Прочитать уведомление может
 * только браузер, создавший подписку: push-сервис браузера (Google, Mozilla, Apple…) видит лишь шифр.
 *
 * Библиотеки здесь не нужны — хватает стандартной криптографии Java: обмен ключами ECDH на кривой P-256,
 * HKDF на HMAC-SHA-256 и AES-128-GCM. Правильность проверяется примером из самого RFC 8291 (WebPushCryptoTest).
 */
internal object WebPushCrypto {
    /** Размер записи aes128gcm. Уведомление занимает одну запись: оно не длиннее 4 КБ (ограничение Web Push). */
    private const val RECORD_SIZE = 4096
    private const val SALT_SIZE = 16
    private const val AUTH_SECRET_SIZE = 16
    private const val CEK_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128

    /** Байт после текста: «это последняя запись» (RFC 8188, раздел 2). */
    private const val LAST_RECORD_DELIMITER: Byte = 0x02

    /** Заголовок записи: соль, размер записи, длина и сам открытый ключ отправителя. */
    private const val HEADER_SIZE = SALT_SIZE + 4 + 1 + EcKeys.PUBLIC_KEY_SIZE

    /** Сколько байт текста помещается в уведомление (вместе с заголовком, разделителем и тегом GCM — 4096). */
    const val MAX_PLAINTEXT_SIZE = RECORD_SIZE - HEADER_SIZE - 1 - TAG_BITS / 8

    private val random = SecureRandom()

    /**
     * Шифрует [plaintext] для подписки браузера: [receiverPublicKey] — ключ `p256dh` (65 байт),
     * [authSecret] — `auth` (16 байт). [senderKeys] и [salt] — одноразовые; задаются только в тестах.
     * Возвращает тело HTTP-запроса к push-сервису (`Content-Encoding: aes128gcm`).
     */
    fun encrypt(
        plaintext: ByteArray,
        receiverPublicKey: ByteArray,
        authSecret: ByteArray,
        senderKeys: KeyPair = EcKeys.generate(),
        salt: ByteArray = ByteArray(SALT_SIZE).also(random::nextBytes),
    ): ByteArray {
        require(plaintext.size <= MAX_PLAINTEXT_SIZE) { "Web Push message is too long" }
        require(authSecret.size == AUTH_SECRET_SIZE) { "Web Push auth secret must be 16 bytes" }
        require(salt.size == SALT_SIZE) { "Salt must be 16 bytes" }
        val senderPublicKey = EcKeys.publicBytes(senderKeys.public as ECPublicKey)

        // Общий секрет браузера и сервера (ECDH), смешанный с секретом подписки auth (RFC 8291, раздел 3.4).
        val ecdhSecret =
            KeyAgreement.getInstance("ECDH").run {
                init(senderKeys.private)
                doPhase(EcKeys.publicKey(receiverPublicKey), true)
                generateSecret()
            }
        val keyInfo = "WebPush: info".toByteArray() + 0 + receiverPublicKey + senderPublicKey
        val ikm = hkdf(salt = authSecret, ikm = ecdhSecret, info = keyInfo, length = 32)

        // Ключ и nonce шифрования записи (RFC 8188, раздел 2.2–2.3).
        val contentKey = hkdf(salt, ikm, "Content-Encoding: aes128gcm".toByteArray() + 0, CEK_SIZE)
        val nonce = hkdf(salt, ikm, "Content-Encoding: nonce".toByteArray() + 0, NONCE_SIZE)
        val ciphertext =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                doFinal(plaintext + LAST_RECORD_DELIMITER)
            }

        val header =
            ByteBuffer
                .allocate(HEADER_SIZE)
                .put(salt)
                .putInt(RECORD_SIZE)
                .put(EcKeys.PUBLIC_KEY_SIZE.toByte())
                .put(senderPublicKey)
                .array()
        return header + ciphertext
    }

    /**
     * HKDF (RFC 5869) на HMAC-SHA-256 для ключей не длиннее 32 байт: такой ключ получается
     * из одного блока расширения, поэтому цикл не нужен.
     */
    private fun hkdf(
        salt: ByteArray,
        ikm: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val prk = hmac(salt, ikm)
        return hmac(prk, info + 1).copyOf(length)
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
