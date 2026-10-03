// Общее для всех Android-экранов: тема, доступные компоненты (большая кнопка, заголовок, объявления статуса),
// общие строки. Бизнес-логики здесь нет.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "ru.ryadom.android.ui"
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
        // Robolectric нужны ресурсы (строки, темы) в unit-тестах.
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
    api(project(":shared"))
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(project(":android:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
