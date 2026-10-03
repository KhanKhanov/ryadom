import java.util.Properties

// Android-приложение: только UI, видео и платформенные API. Бизнес-логика — в модуле shared.
// Kotlin подключён встроенной поддержкой AGP 9, отдельный плагин kotlin-android не нужен.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

/**
 * Настройка сборки: из параметра `-P`, `gradle.properties` (в том числе `~/.gradle/gradle.properties`)
 * или `local.properties` в корне репозитория (этот файл не попадает в git).
 */
fun buildSetting(
    name: String,
    default: String,
): String {
    providers.gradleProperty(name).orNull?.let { return it }
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        val local = Properties().apply { localFile.inputStream().use { load(it) } }
        local.getProperty(name)?.let { return it }
    }
    return default
}

// Адрес backend. По умолчанию — localhost: эмулятор или телефон по USB попадает на компьютер разработчика
// через `adb reverse` (README, «Android»). Для телефона по Wi-Fi укажите адрес компьютера в локальной сети,
// например ryadom.apiUrl=http://192.168.1.10:8080.
val apiUrl = buildSetting("ryadom.apiUrl", "http://localhost:8080")

// client_id приложения в Яндекс ID (https://oauth.yandex.ru). Не секрет, но зависит от регистрации,
// поэтому тоже настройка. Пусто — кнопки «Войти через Яндекс ID» нет.
val yandexClientId = buildSetting("ryadom.yandexClientId", "")

android {
    namespace = "ru.ryadom.android"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "ru.ryadom"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "API_URL", "\"$apiUrl\"")
        buildConfigField("String", "YANDEX_CLIENT_ID", "\"$yandexClientId\"")
        // Яндекс LoginSDK берёт client_id из манифеста.
        manifestPlaceholders["YANDEX_CLIENT_ID"] = yandexClientId

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Вход по логину без Яндекс ID (POST /auth/dev) — только в отладочной сборке.
            buildConfigField("boolean", "DEV_LOGIN", "true")
        }
        release {
            buildConfigField("boolean", "DEV_LOGIN", "false")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Robolectric нужны ресурсы приложения (строки, темы) в unit-тестах.
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        checkDependencies = true
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
    implementation(project(":shared"))
    implementation(project(":android:core-ui"))
    implementation(project(":android:feature-call"))
    implementation(project(":android:feature-help"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Движок HTTP и WebSocket для клиента API из shared.
    implementation(libs.ktor.client.okhttp)
    implementation(libs.yandex.authsdk)

    testImplementation(project(":android:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // UI-тесты на эмуляторе: Accessibility Test Framework проверяет экраны (контраст, размеры, подписи).
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
