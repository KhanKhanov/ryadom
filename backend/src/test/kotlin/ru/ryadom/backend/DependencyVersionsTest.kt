package ru.ryadom.backend

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Jackson приходит транзитивно (java-jwt, jwks-rsa) и разбирает JWT из каждого запроса.
 * Версии ниже 2.22.3 уязвимы (см. libs.versions.toml). Тест падает, если jackson-bom убрали
 * из backend/build.gradle.kts или какая-то зависимость снова притянула старую версию.
 *
 * Версия читается во время выполнения: в коде сервера Jackson не используется напрямую,
 * поэтому при компиляции его классов нет.
 */
class DependencyVersionsTest {
    private val minimumSafe = listOf(2, 22, 3)

    @Test
    fun jacksonCoreIsPatched() {
        assertPatched("jackson-core", "com.fasterxml.jackson.core.json.PackageVersion")
    }

    @Test
    fun jacksonDatabindIsPatched() {
        assertPatched("jackson-databind", "com.fasterxml.jackson.databind.cfg.PackageVersion")
    }

    private fun assertPatched(
        library: String,
        packageVersionClass: String,
    ) {
        val version =
            runCatching {
                Class
                    .forName(packageVersionClass)
                    .getField("VERSION")
                    .get(null)
                    .toString()
            }.getOrElse {
                fail("$library больше нет среди зависимостей backend: уберите jackson-bom и этот тест")
            }
        val actual = version.substringBefore('-').split('.').map { it.toInt() }
        assertTrue(isAtLeast(actual, minimumSafe), "$library $version уязвим, нужна версия не ниже ${minimumSafe.joinToString(".")}")
    }

    private fun isAtLeast(
        actual: List<Int>,
        minimum: List<Int>,
    ): Boolean {
        val difference = actual.zip(minimum).firstOrNull { (a, m) -> a != m } ?: return true
        return difference.first > difference.second
    }
}
