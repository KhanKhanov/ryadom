package ru.ryadom.shared.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.ryadom.shared.api.ApiErrorCodes
import ru.ryadom.shared.api.SelectableRole
import ru.ryadom.shared.api.UpdateProfileRequest
import ru.ryadom.shared.api.UserProfile
import ru.ryadom.shared.client.ApiClient
import ru.ryadom.shared.client.ApiClientException
import ru.ryadom.shared.client.SessionEndReason
import ru.ryadom.shared.client.UserError
import ru.ryadom.shared.client.toUserError

/** Что показать до основного экрана: вход, выбор роли или сам пользователь. */
sealed interface AuthScreen {
    /** Читаем профиль сохранённого входа. */
    data object Loading : AuthScreen

    /** Вход сохранён, но профиль не загрузить (нет связи): «Повторить». */
    data object Offline : AuthScreen

    /** Экран входа. [reason] — почему пришлось войти снова (`null` — первый вход или пользователь вышел сам). */
    data class Login(
        val reason: SessionEndReason? = null,
    ) : AuthScreen

    /** Первый вход: роль ещё не выбрана. */
    data class ChooseRole(
        val profile: UserProfile,
    ) : AuthScreen

    /** Вошёл и выбрал роль — платформа показывает экраны этой роли. */
    data class SignedIn(
        val profile: UserProfile,
    ) : AuthScreen
}

data class AuthState(
    val screen: AuthScreen = AuthScreen.Loading,
    /** Ждём ответа сервера — кнопки неактивны. */
    val busy: Boolean = false,
    /** Ошибка последнего действия. */
    val error: UserError? = null,
)

/**
 * Вход, выбор роли и выход. Как и [ru.ryadom.shared.help.BlindHelpController], вызывается
 * из главного потока, [scope] — однопоточный.
 *
 * @param timeZoneId часовой пояс устройства (IANA), сохраняется в профиле при выборе роли.
 */
class AuthController(
    private val api: ApiClient,
    private val scope: CoroutineScope,
    private val timeZoneId: () -> String,
) {
    private val stateFlow = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = stateFlow.asStateFlow()

    private var current: AuthState
        get() = stateFlow.value
        set(value) {
            stateFlow.value = value
        }

    fun start() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            api.sessionEnds.collect { reason ->
                current = AuthState(AuthScreen.Login(reason.takeUnless { it == SessionEndReason.LOGGED_OUT }))
            }
        }
        if (api.hasSession()) loadProfile() else current = AuthState(AuthScreen.Login())
    }

    /**
     * «Повторить» на экране [AuthScreen.Offline]. Платформа вызывает его и сама, когда появилась сеть:
     * незрячему не нужно искать кнопку. На других экранах ничего не делает.
     */
    fun retry() {
        if (current.screen == AuthScreen.Offline && !current.busy) loadProfile()
    }

    /** Вход без OAuth — только для разработки (на сервере `AUTH_DEV_ENABLED=true`). */
    fun loginDev(login: String) = login { api.loginDev(login) }

    /** Вход через Яндекс ID: токен от Яндекс LoginSDK. */
    fun loginYandex(yandexAccessToken: String) = login { api.loginYandex(yandexAccessToken) }

    fun chooseRole(role: SelectableRole) {
        if (current.screen !is AuthScreen.ChooseRole) return
        run {
            val profile =
                try {
                    api.updateMe(UpdateProfileRequest(role = role, timezone = timeZoneId()))
                } catch (e: ApiClientException.Server) {
                    // Сервер не знает пояс, который сообщил телефон, — сохраняем роль без него, как на сайте.
                    // Пояс останется прежним (по умолчанию — из конфига сервера).
                    if (e.code != ApiErrorCodes.INVALID_REQUEST) throw e
                    api.updateMe(UpdateProfileRequest(role = role))
                }
            show(profile)
        }
    }

    /**
     * Смена уже выбранной роли — например, незрячий по ошибке нажал «Я хочу помогать», а экранов
     * волонтёра в приложении пока нет. Часовой пояс не меняется: его сохранил первый выбор роли.
     * Пока есть активный запрос или идёт звонок, сервер роль не меняет ([UserError.ACTIVE_REQUEST]).
     */
    fun changeRole(role: SelectableRole) {
        val screen = current.screen as? AuthScreen.SignedIn ?: return
        if (screen.profile.role?.name == role.name) return
        run { show(api.updateMe(UpdateProfileRequest(role = role))) }
    }

    /**
     * Профиль изменили не здесь — например, волонтёр включил или выключил вызовы ([ru.ryadom.shared.volunteer.VolunteerController]).
     * Показать новый; если сменилась роль — экраны другой роли.
     */
    fun profileChanged(profile: UserProfile) {
        val screen = current.screen as? AuthScreen.SignedIn ?: return
        if (screen.profile.id != profile.id) return
        current = current.copy(screen = screenFor(profile))
    }

    fun logout() {
        if (current.busy) return
        current = current.copy(busy = true)
        // Экран входа покажет подписка на sessionEnds.
        scope.launch { api.logout() }
    }

    private fun loadProfile() {
        current = AuthState(AuthScreen.Loading)
        scope.launch {
            current =
                try {
                    AuthState(screenFor(api.getMe()))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiClientException.SessionEnded) {
                    return@launch // Экран входа покажет подписка на sessionEnds.
                } catch (e: Exception) {
                    AuthState(AuthScreen.Offline, error = e.toUserError())
                }
        }
    }

    private fun login(block: suspend () -> UserProfile) {
        if (current.screen !is AuthScreen.Login) return
        run {
            try {
                show(block())
            } catch (e: ApiClientException.Server) {
                when (e.code) {
                    ApiErrorCodes.USER_BANNED -> current = AuthState(AuthScreen.Login(SessionEndReason.BANNED))

                    // Такого входа на сервере нет: вход без OAuth на боевом сервере или Яндекс ID не настроен.
                    ApiErrorCodes.NOT_FOUND -> current = current.copy(error = UserError.LOGIN_UNAVAILABLE)

                    else -> throw e
                }
            }
        }
    }

    private fun show(profile: UserProfile) {
        current = AuthState(screenFor(profile))
    }

    private fun screenFor(profile: UserProfile): AuthScreen =
        if (profile.role ==
            null
        ) {
            AuthScreen.ChooseRole(profile)
        } else {
            AuthScreen.SignedIn(profile)
        }

    private fun run(block: suspend () -> Unit) {
        if (current.busy) return
        current = current.copy(busy = true, error = null)
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiClientException.SessionEnded) {
                // Экран входа покажет подписка на sessionEnds.
            } catch (e: Exception) {
                current = current.copy(error = e.toUserError())
            } finally {
                current = current.copy(busy = false)
            }
        }
    }
}
