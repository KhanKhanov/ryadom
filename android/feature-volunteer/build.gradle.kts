// Экраны волонтёра: «Готов помогать», входящие вызовы (в приложении и полноэкранным уведомлением),
// звонок с видео незрячего, оценка. Только отображение и платформенное (уведомления, звонок телефона,
// окно поверх экрана блокировки): состояния и решения — в VolunteerController из shared.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "ru.ryadom.android.volunteer"
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
    api(project(":shared"))
    api(project(":android:core-ui"))
    api(project(":android:feature-call"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Приложение на экране или в фоне: в фоне вызов приходит уведомлением, на экране — звонит само приложение.
    implementation(libs.androidx.lifecycle.process)

    testImplementation(project(":android:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
