package ru.ryadom.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yandex.authsdk.YandexAuthLoginOptions
import com.yandex.authsdk.YandexAuthOptions
import com.yandex.authsdk.YandexAuthResult
import com.yandex.authsdk.YandexAuthSdk
import ru.ryadom.android.auth.LoadingScreen
import ru.ryadom.android.auth.LoginScreen
import ru.ryadom.android.auth.OfflineScreen
import ru.ryadom.android.auth.RoleScreen
import ru.ryadom.android.auth.UnsupportedRoleScreen
import ru.ryadom.android.call.LocalCameraPreview
import ru.ryadom.android.help.BlindHelpRoute
import ru.ryadom.android.ui.theme.RyadomTheme
import ru.ryadom.shared.api.Role
import ru.ryadom.shared.api.SelectableRole
import ru.ryadom.shared.auth.AuthScreen
import ru.ryadom.shared.client.UserError

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RyadomApplication).container
        setContent {
            RyadomTheme {
                AppRoot(container)
            }
        }
    }
}

/** Какой экран показать: вход, выбор роли или экраны роли пользователя. */
@Composable
private fun AppRoot(container: AppContainer) {
    val auth by container.auth.state.collectAsStateWithLifecycle()
    when (val screen = auth.screen) {
        AuthScreen.Loading -> {
            LoadingScreen()
        }

        AuthScreen.Offline -> {
            OfflineScreen(error = auth.error, onRetry = container.auth::retry, onLogout = container.auth::logout)
        }

        is AuthScreen.Login -> {
            LoginRoute(container, screen)
        }

        is AuthScreen.ChooseRole -> {
            RoleScreen(error = auth.error, busy = auth.busy, onChoose = container.auth::chooseRole)
        }

        is AuthScreen.SignedIn -> {
            if (screen.profile.role == Role.BLIND) {
                val session by container.blindSession.collectAsStateWithLifecycle()
                val current = session?.takeIf { it.userId == screen.profile.id }
                if (current == null) {
                    LoadingScreen()
                } else {
                    BlindHelpRoute(
                        controller = current.controller,
                        onLogout = container.auth::logout,
                        videoPreview = { modifier -> LocalCameraPreview(container.calls, modifier) },
                    )
                }
            } else {
                UnsupportedRoleScreen(
                    role = screen.profile.role,
                    error = auth.error,
                    busy = auth.busy,
                    onNeedHelp = { container.auth.changeRole(SelectableRole.BLIND) },
                    onLogout = container.auth::logout,
                )
            }
        }
    }
}

/** Экран входа и вход через Яндекс LoginSDK (если приложение зарегистрировано в Яндекс ID). */
@Composable
private fun LoginRoute(
    container: AppContainer,
    screen: AuthScreen.Login,
) {
    val auth by container.auth.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Ошибка Яндекс LoginSDK — до сервера дело не дошло, поэтому её показывает экран, а не AuthController.
    var yandexError by rememberSaveable { mutableStateOf<UserError?>(null) }
    val yandexAvailable = BuildConfig.YANDEX_CLIENT_ID.isNotEmpty()
    val yandexSdk = remember { if (yandexAvailable) YandexAuthSdk.create(YandexAuthOptions(context)) else null }
    val yandexLauncher =
        yandexSdk?.let { sdk ->
            rememberLauncherForActivityResult(sdk.contract) { result ->
                when (result) {
                    is YandexAuthResult.Success -> container.auth.loginYandex(result.token.value)
                    is YandexAuthResult.Failure -> yandexError = UserError.LOGIN_FAILED
                    YandexAuthResult.Cancelled -> Unit
                }
            }
        }
    LoginScreen(
        reason = screen.reason,
        error = auth.error ?: yandexError,
        busy = auth.busy,
        yandexAvailable = yandexLauncher != null,
        devLoginAvailable = BuildConfig.DEV_LOGIN,
        onYandexLogin = {
            yandexError = null
            yandexLauncher?.launch(YandexAuthLoginOptions())
        },
        onDevLogin = { login ->
            yandexError = null
            container.auth.loginDev(login)
        },
    )
}
