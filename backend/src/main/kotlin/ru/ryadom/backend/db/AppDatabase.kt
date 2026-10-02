package ru.ryadom.backend.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import ru.ryadom.backend.DatabaseConfig as DbSettings

/**
 * Подключение к PostgreSQL: пул соединений, миграции Flyway (`resources/db/migration`) и Exposed.
 * Миграции применяются при старте сервера.
 */
class AppDatabase(
    settings: DbSettings,
) : AutoCloseable {
    private val dataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = settings.url
                username = settings.user
                password = settings.password
                maximumPoolSize = settings.maxPoolSize
                poolName = "ryadom-db"
            },
        )

    private val database: Database

    init {
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        database =
            Database.connect(
                dataSource,
                databaseConfig =
                    DatabaseConfig {
                        // Exposed по умолчанию повторяет транзакцию до 3 раз при ошибке SQL.
                        // Наши блоки не всегда можно безопасно повторить, поэтому повторы выключены.
                        defaultMaxAttempts = 1
                    },
            )
    }

    /** Выполняет [block] в транзакции на пуле потоков для блокирующего ввода-вывода. */
    suspend fun <T> query(block: JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            transaction(database) { block() }
        }

    override fun close() = dataSource.close()
}

/** В базе время хранится как `timestamptz`; в коде — [Instant]. */
internal fun Instant.toDb(): OffsetDateTime = atOffset(ZoneOffset.UTC)
