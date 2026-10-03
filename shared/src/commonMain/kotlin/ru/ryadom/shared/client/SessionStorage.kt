package ru.ryadom.shared.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Токены вошедшего пользователя. */
@Serializable
data class StoredSession(
    /** id пользователя: по нему видно, что вошли под другим именем. */
    val userId: String,
    val accessToken: String,
    /** Когда истечёт [accessToken], миллисекунды Unix-времени по часам этого устройства. */
    val accessTokenExpiresAt: Long,
    /** Одноразовый: после обновления токенов становится недействительным. */
    val refreshToken: String,
) {
    /** В виде строки — для платформенного хранилища (оно шифрует и сохраняет её целиком). */
    fun encode(): String = json.encodeToString(serializer(), this)

    // В toString токены не попадают: объект может оказаться в логе или отчёте о падении.
    override fun toString(): String = "StoredSession(userId=$userId)"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Обратно из [encode]. `null` — в хранилище мусор или формат старой версии: считаем, что входа нет. */
        fun decode(text: String): StoredSession? =
            try {
                json.decodeFromString(serializer(), text)
            } catch (e: IllegalArgumentException) {
                null
            }
    }
}

/**
 * Где хранятся токены между запусками приложения. Реализация — на платформе:
 * на Android — с шифрованием ключом из Android Keystore (`android/app`), на iOS — Keychain (этап 11).
 * Методы вызываются из одного потока и должны работать быстро (без сети).
 */
interface SessionStorage {
    fun load(): StoredSession?

    fun save(session: StoredSession)

    fun clear()
}

/** Хранилище в памяти: вход живёт до закрытия приложения. Для тестов и как запасной вариант. */
class InMemorySessionStorage(
    private var session: StoredSession? = null,
) : SessionStorage {
    override fun load(): StoredSession? = session

    override fun save(session: StoredSession) {
        this.session = session
    }

    override fun clear() {
        session = null
    }
}
