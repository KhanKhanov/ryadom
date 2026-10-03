package ru.ryadom.backend.push

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/**
 * Ключи на эллиптической кривой P-256 в том виде, в каком их передаёт Web Push: открытый ключ —
 * 65 байт (`0x04`, затем координаты X и Y по 32 байта), закрытый — 32 байта; в тексте — base64url без `=`.
 * Java работает с ключами в другом виде, отсюда эти преобразования.
 */
internal object EcKeys {
    const val PUBLIC_KEY_SIZE = 65
    const val PRIVATE_KEY_SIZE = 32
    private const val COORDINATE_SIZE = 32
    private const val UNCOMPRESSED_POINT: Byte = 0x04

    private val p256: ECParameterSpec =
        AlgorithmParameters
            .getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)

    private val keyFactory get() = KeyFactory.getInstance("EC")

    fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** Открытый ключ из 65 байт. Бросает [IllegalArgumentException], если это не точка кривой P-256. */
    fun publicKey(bytes: ByteArray): ECPublicKey {
        require(bytes.size == PUBLIC_KEY_SIZE && bytes[0] == UNCOMPRESSED_POINT) { "Not an uncompressed P-256 point" }
        val x = BigInteger(1, bytes.copyOfRange(1, 1 + COORDINATE_SIZE))
        val y = BigInteger(1, bytes.copyOfRange(1 + COORDINATE_SIZE, PUBLIC_KEY_SIZE))
        // KeyFactory не проверяет, что точка лежит на кривой, — проверяем сами: y² = x³ + ax + b (mod p).
        val p = (p256.curve.field as ECFieldFp).p
        require(
            x < p && y < p && y
                .pow(2)
                .subtract(x.pow(3).add(p256.curve.a.multiply(x)).add(p256.curve.b))
                .mod(p)
                .signum() == 0,
        ) {
            "Not a P-256 point"
        }
        return keyFactory.generatePublic(ECPublicKeySpec(ECPoint(x, y), p256)) as ECPublicKey
    }

    fun privateKey(bytes: ByteArray): ECPrivateKey {
        require(bytes.size == PRIVATE_KEY_SIZE) { "P-256 private key must be 32 bytes" }
        return keyFactory.generatePrivate(ECPrivateKeySpec(BigInteger(1, bytes), p256)) as ECPrivateKey
    }

    fun publicBytes(key: ECPublicKey): ByteArray = byteArrayOf(UNCOMPRESSED_POINT) + key.w.affineX.toFixed() + key.w.affineY.toFixed()

    fun privateBytes(key: ECPrivateKey): ByteArray = key.s.toFixed()

    /** Число как ровно 32 байта: BigInteger отдаёт лишний нулевой байт знака или, наоборот, короче. */
    private fun BigInteger.toFixed(): ByteArray {
        val bytes = toByteArray()
        return when {
            bytes.size == COORDINATE_SIZE -> bytes
            bytes.size > COORDINATE_SIZE -> bytes.copyOfRange(bytes.size - COORDINATE_SIZE, bytes.size)
            else -> ByteArray(COORDINATE_SIZE - bytes.size) + bytes
        }
    }
}

/** base64url без `=` — так ключи и подписи записываются в Web Push. */
internal object Base64Url {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** Принимает и `=` в конце, и обычный base64 (`+`, `/`): браузеры и библиотеки пишут по-разному. */
    fun decode(text: String): ByteArray =
        Base64.getUrlDecoder().decode(
            text
                .trim()
                .trimEnd('=')
                .replace('+', '-')
                .replace('/', '_'),
        )
}
