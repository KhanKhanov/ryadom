plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.ktlint)
}

group = "ru.ryadom"

kotlin {
    jvmToolchain(
        libs.versions.jvm
            .get()
            .toInt(),
    )
}

application {
    mainClass.set("ru.ryadom.backend.ApplicationKt")
}

ktlint {
    version.set(libs.versions.ktlint.cli)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    // Принудительная версия Jackson для зависимостей ktor-server-auth-jwt (см. libs.versions.toml).
    implementation(platform(libs.jackson.bom))
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)
    // HTTP-клиент — для запросов к Яндекс ID.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.logback.classic)
    implementation(libs.typesafe.config)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)

    // Токены для входа в комнаты LiveKit и проверка подписи его webhook.
    implementation(libs.livekit.server)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.kotlin.test)
    // Настоящий PostgreSQL в Docker для интеграционных тестов.
    testImplementation(libs.testcontainers.postgresql)
    // Каждый ответ сервера в тестах сверяется с docs/api/openapi.yaml (testing/OpenApiContract.kt).
    testImplementation(libs.json.schema.validator)
}

tasks.test {
    useJUnitPlatform()
    // Контракт API, по которому тесты проверяют ответы. Изменился файл — тесты запускаются заново.
    val openApi = rootProject.file("docs/api/openapi.yaml")
    inputs.file(openApi).withPropertyName("openApi").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("ryadom.openapi", openApi.absolutePath)
}

// Новая пара ключей VAPID для Web Push (WEB_PUSH_PUBLIC_KEY и WEB_PUSH_PRIVATE_KEY в infra/.env).
tasks.register<JavaExec>("generateWebPushKeys") {
    group = "application"
    description = "Prints a new VAPID key pair for Web Push"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ru.ryadom.backend.push.GenerateWebPushKeysKt")
}

// Для локального запуска (`./gradlew :backend:run`) переменные окружения берутся из infra/.env —
// того же файла, что читает docker-compose (пароль PostgreSQL, JWT_SECRET и др.).
// Переменные, уже заданные в окружении, имеют приоритет над файлом. Пустое значение (`LIVEKIT_URL=`)
// считается незаданным: действует значение по умолчанию из application.conf.
tasks.named<JavaExec>("run") {
    val envFile = rootProject.file("infra/.env")
    if (envFile.exists()) {
        envFile
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .map { line -> line.substringBefore("=").trim() to line.substringAfter("=").trim().removeSurrounding("\"") }
            .filter { (key, value) -> value.isNotEmpty() && !providers.environmentVariable(key).isPresent }
            .forEach { (key, value) -> environment(key, value) }
    }
}
