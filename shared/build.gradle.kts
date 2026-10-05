// Общая бизнес-логика для Android, iOS и backend: модели API, клиент API и WebSocket, вход,
// состояния запроса помощи и звонка.
// Здесь запрещены зависимости от Android SDK и LiveKit: только чистый Kotlin (см. CLAUDE.md, правило 7).
// Платформенное (хранение токенов, сам видеозвонок, движок HTTP) приходит снаружи через интерфейсы.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(
        libs.versions.jvm
            .get()
            .toInt(),
    )

    // JVM — для backend и быстрых тестов.
    jvm()

    android {
        namespace = "ru.ryadom.shared"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        // Общие тесты (commonTest) запускаются и на JVM-хосте для Android-таргета.
        withHostTest {}
    }

    // iOS-таргеты собираются в CI на macOS, чтобы в shared не попал Android-код.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            // Клиент HTTP и WebSocket без движка: движок (OkHttp на Android) передаёт платформа.
            api(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        // Виртуальное время в тестах (currentTime, runCurrent, advanceTimeBy) помечено как экспериментальное:
        // без этого — сотни одинаковых предупреждений в логе сборки.
        matching { it.name.endsWith("Test") }.configureEach {
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
    }
}

ktlint {
    version.set(libs.versions.ktlint.cli)
}
