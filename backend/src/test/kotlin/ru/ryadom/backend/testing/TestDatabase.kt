package ru.ryadom.backend.testing

import org.testcontainers.postgresql.PostgreSQLContainer
import ru.ryadom.backend.DatabaseConfig
import java.sql.Connection
import java.sql.DriverManager

/**
 * Настоящий PostgreSQL в Docker, один на все тесты (запускается при первом обращении).
 * Нужен запущенный Docker; контейнер удаляется автоматически после тестов.
 */
object TestDatabase {
    /** Та же версия, что в infra/docker-compose.yml. */
    private const val IMAGE = "postgres:18-alpine"

    private val container by lazy { PostgreSQLContainer(IMAGE).also { it.start() } }

    val config: DatabaseConfig by lazy {
        DatabaseConfig(url = container.jdbcUrl, user = container.username, password = container.password, maxPoolSize = 4)
    }

    /** Очищает данные между тестами. Схема (миграции) остаётся. */
    fun clean() {
        // Таблицы может ещё не быть, если миграции в этом запуске тестов ещё не применялись.
        execute("DO $$ BEGIN TRUNCATE users CASCADE; EXCEPTION WHEN undefined_table THEN END $$")
    }

    /** Выполняет SQL напрямую, в обход API — например, чтобы заблокировать пользователя. */
    fun execute(sql: String) {
        connection().use { it.createStatement().execute(sql) }
    }

    /** Число из первой строки результата, например `SELECT count(*) FROM users`. */
    fun queryInt(sql: String): Int =
        connection().use { connection ->
            connection.createStatement().executeQuery(sql).use { result ->
                check(result.next()) { "Запрос не вернул строк: $sql" }
                result.getInt(1)
            }
        }

    private fun connection(): Connection = DriverManager.getConnection(config.url, config.user, config.password)
}
