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
        // LiveKit Android берёт библиотеку audioswitch с JitPack, в Maven Central её нет.
        // С JitPack разрешено скачивать только эту группу, чтобы туда не уходили запросы за другими библиотеками.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.davidliu") }
        }
    }
}

rootProject.name = "ryadom"

include(":backend")
include(":shared")
// Android: приложение и модули (docs/ARCHITECTURE.md, раздел 7).
include(":android:app")
include(":android:core-ui")
include(":android:feature-call")
include(":android:feature-help")
include(":android:feature-volunteer")
include(":android:testing")
