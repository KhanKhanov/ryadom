package ru.ryadom.shared.api

import kotlinx.serialization.Serializable

/** Внешний провайдер входа. [pathValue] — значение в пути `/auth/oauth/{provider}`. */
enum class OAuthProvider(
    val pathValue: String,
) {
    YANDEX("yandex"),
    ;

    companion object {
        fun fromPathValue(value: String): OAuthProvider? = entries.firstOrNull { it.pathValue == value }
    }
}

/** Тело `POST /auth/dev`. Вход без OAuth работает только в dev-окружении. */
@Serializable
data class DevLoginRequest(
    val login: String,
) {
    companion object {
        /** Допустимый логин: латиница в нижнем регистре, цифры, `_` и `-`, до 32 символов. */
        val LOGIN_REGEX = Regex("^[a-z0-9_-]{1,32}$")
    }
}

/**
 * Тело `POST /auth/oauth/{provider}`. Передаётся ровно одно из полей:
 * [code] (веб, вместе с [codeVerifier] при PKCE) или [accessToken] (мобильный SDK провайдера).
 */
@Serializable
data class OAuthLoginRequest(
    val code: String? = null,
    val codeVerifier: String? = null,
    val accessToken: String? = null,
)

/** Тело `POST /auth/refresh` и `POST /auth/logout`. */
@Serializable
data class RefreshTokenRequest(
    val refreshToken: String,
)

/** Ответ на успешный вход или обновление токенов. */
@Serializable
data class AuthResponse(
    /** JWT для заголовка `Authorization: Bearer`. */
    val accessToken: String,
    /** Через сколько секунд истечёт [accessToken]. */
    val accessTokenExpiresIn: Long,
    /** Одноразовый: после `POST /auth/refresh` становится недействительным. */
    val refreshToken: String,
    val user: UserProfile,
)
