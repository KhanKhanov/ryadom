// Корневая Gradle-сборка монорепозитория «Рядом».
// web/ собирается отдельно через npm, ios/ появится на этапе 11.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Автоматически скачивает нужный JDK, если его нет на машине.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ryadom"

include(":backend")
include(":shared")
include(":android:app")
