// Помощники для тестов Android-модулей: проверки доступности экранов в Robolectric
// (assertScreenIsAccessible). Подключается только как testImplementation, в приложение не попадает.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "ru.ryadom.android.testing"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(
        libs.versions.jvm
            .get()
            .toInt(),
    )
}

ktlint {
    version.set(libs.versions.ktlint.cli)
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui.test.junit4)
    implementation(libs.androidx.compose.ui)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
