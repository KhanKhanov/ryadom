package ru.ryadom.backend.auth

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import ru.ryadom.backend.db.AppDatabase
import ru.ryadom.backend.db.RefreshTokensTable
import ru.ryadom.backend.db.toDb
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.uuid.Uuid

/** Результат обмена refresh-токена на новый. */
sealed interface RotationResult {
    data class Rotated(
        val userId: Uuid,
        val newToken: String,
    ) : RotationResult

    /** Токен неизвестен или истёк. */
    data object Invalid : RotationResult

    /** Токен уже был использован — похоже на кражу. Все токены пользователя отозваны. */
    data class Reused(
        val userId: Uuid,
    ) : RotationResult
}

/**
 * Refresh-токены: случайные 256 бит, в базе хранится только SHA-256.
 * Каждый токен одноразовый: при обмене старый отзывается, выдаётся новый (ротация).
 */
class RefreshTokenStore(
    private val db: AppDatabase,
    private val clock: Clock,
    private val ttl: Duration,
) {
    private val random = SecureRandom()

    suspend fun issue(userId: Uuid): String = db.query { insertToken(userId, clock.instant()) }

    suspend fun rotate(token: String): RotationResult {
        val now = clock.instant()
        return db.query {
            // FOR UPDATE: два одновременных обмена одного токена не выдадут два новых.
            val row =
                RefreshTokensTable
                    .selectAll()
                    .where { RefreshTokensTable.tokenHash eq hash(token) }
                    .forUpdate()
                    .singleOrNull()
                    ?: return@query RotationResult.Invalid
            val userId = row[RefreshTokensTable.userId]
            when {
                row[RefreshTokensTable.revokedAt] != null -> {
                    revokeAllActive(userId, now)
                    RotationResult.Reused(userId)
                }

                !row[RefreshTokensTable.expiresAt].toInstant().isAfter(now) -> {
                    RotationResult.Invalid
                }

                else -> {
                    RefreshTokensTable.update({ RefreshTokensTable.id eq row[RefreshTokensTable.id] }) {
                        it[revokedAt] = now.toDb()
                    }
                    RotationResult.Rotated(userId, insertToken(userId, now))
                }
            }
        }
    }

    /** Отзывает один токен (выход на устройстве). Неизвестный токен молча игнорируется. */
    suspend fun revoke(token: String) {
        val now = clock.instant()
        db.query {
            RefreshTokensTable.update({
                (RefreshTokensTable.tokenHash eq hash(token)) and RefreshTokensTable.revokedAt.isNull()
            }) {
                it[revokedAt] = now.toDb()
            }
        }
    }

    /** Отзывает все токены пользователя (выход на всех устройствах, блокировка). */
    suspend fun revokeAll(userId: Uuid) {
        val now = clock.instant()
        db.query { revokeAllActive(userId, now) }
    }

    private fun JdbcTransaction.insertToken(
        userId: Uuid,
        now: Instant,
    ): String {
        // Заодно удаляем истёкшие токены этого пользователя, чтобы таблица не росла бесконечно.
        RefreshTokensTable.deleteWhere {
            (RefreshTokensTable.userId eq userId) and (expiresAt less now.toDb())
        }
        val token = newToken()
        RefreshTokensTable.insert {
            it[id] = Uuid.random()
            it[this.userId] = userId
            it[tokenHash] = hash(token)
            it[createdAt] = now.toDb()
            it[expiresAt] = now.plus(ttl).toDb()
        }
        return token
    }

    private fun JdbcTransaction.revokeAllActive(
        userId: Uuid,
        now: Instant,
    ) {
        RefreshTokensTable.update({
            (RefreshTokensTable.userId eq userId) and RefreshTokensTable.revokedAt.isNull()
        }) {
            it[revokedAt] = now.toDb()
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
