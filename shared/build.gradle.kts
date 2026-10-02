// Общая бизнес-логика для Android, iOS и backend (модели API, позже — клиент, WebSocket, состояния).
// Здесь запрещены зависимости от Android SDK и LiveKit: только чистый Kotlin (см. CLAUDE.md, правило 7).
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
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

ktlint {
    version.set(libs.versions.ktlint.cli)
}
