package ru.ryadom.backend.push

/**
 * Создаёт пару ключей VAPID для Web Push: `./gradlew :backend:generateWebPushKeys`.
 * Ключи печатаются строками для infra/.env; в репозиторий их не добавляйте.
 * Если сменить ключи на работающем сервере, старые подписки браузеров перестанут получать уведомления,
 * пока волонтёр не откроет сайт: сайт подпишется заново с новым ключом.
 */
fun main() {
    val (publicKey, privateKey) = VapidKeys.generate()
    println("WEB_PUSH_PUBLIC_KEY=$publicKey")
    println("WEB_PUSH_PRIVATE_KEY=$privateKey")
}
