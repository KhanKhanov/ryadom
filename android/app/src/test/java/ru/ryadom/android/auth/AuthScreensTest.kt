package ru.ryadom.android.auth

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.ryadom.android.testing.assertScreenIsAccessible
import ru.ryadom.android.ui.theme.RyadomTheme
import ru.ryadom.shared.api.SelectableRole
import ru.ryadom.shared.client.SessionEndReason
import ru.ryadom.shared.client.UserError

// Обычный Application вместо RyadomApplication: в Robolectric нет Android Keystore, а экранам он не нужен.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ru", application = Application::class)
class AuthScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()

    private fun showLogin(
        yandex: Boolean,
        dev: Boolean,
        reason: SessionEndReason? = null,
        error: UserError? = null,
    ) {
        composeRule.setContent {
            RyadomTheme {
                LoginScreen(
                    reason = reason,
                    error = error,
                    busy = false,
                    yandexAvailable = yandex,
                    devLoginAvailable = dev,
                    onYandexLogin = { events += "yandex" },
                    onDevLogin = { events += "dev:$it" },
                )
            }
        }
    }

    @Test
    fun loginWithYandex() {
        showLogin(yandex = true, dev = false)

        composeRule.onNodeWithText("Вход").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithText("Войти через Яндекс ID").performClick()

        assertEquals(listOf("yandex"), events)
        composeRule.assertScreenIsAccessible()
    }

    @Test
    fun devLoginNormalizesLogin() {
        showLogin(yandex = false, dev = true)

        composeRule.onNode(hasSetTextAction()).performTextReplacement(" Blind-2 ")
        composeRule.onNodeWithText("Войти без Яндекс ID (для разработки)").performClick()

        assertEquals(listOf("dev:blind-2"), events)
        composeRule.assertScreenIsAccessible()
    }

    @Test
    fun releaseBuildWithoutYandexExplainsWhy() {
        showLogin(yandex = false, dev = false)

        composeRule.onNodeWithText("Вход через Яндекс ID не настроен в этой сборке приложения.").assertIsDisplayed()
    }

    @Test
    fun reasonAndErrorAreShown() {
        showLogin(yandex = true, dev = false, reason = SessionEndReason.BANNED, error = UserError.PROVIDER_UNAVAILABLE)

        composeRule.onNodeWithText("Ваша учётная запись заблокирована.").assertIsDisplayed()
        composeRule.onNodeWithText("Яндекс ID сейчас недоступен. Попробуйте позже.").assertIsDisplayed()
    }

    @Test
    fun roleChoice() {
        composeRule.setContent {
            RyadomTheme { RoleScreen(error = null, busy = false, onChoose = { events += it.name }) }
        }

        composeRule.onNodeWithText("Мне нужна помощь").performClick()
        composeRule.onNodeWithText("Я хочу помогать").performClick()

        assertEquals(listOf(SelectableRole.BLIND.name, SelectableRole.VOLUNTEER.name), events)
        composeRule.assertScreenIsAccessible()
    }

    @Test
    fun offlineCanRetryOrLogout() {
        composeRule.setContent {
            RyadomTheme { OfflineScreen(error = UserError.NETWORK, onRetry = { events += "retry" }, onLogout = { events += "logout" }) }
        }

        composeRule.onNodeWithText("Нет связи с сервером. Проверьте интернет и попробуйте ещё раз.").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить").performClick()
        composeRule.onNodeWithText("Выйти").performClick()

        assertEquals(listOf("retry", "logout"), events)
        composeRule.assertScreenIsAccessible()
    }

    @Test
    fun adminIsSentToWebsite() {
        composeRule.setContent { RyadomTheme { UnsupportedRoleScreen(busy = false, onLogout = { events += "logout" }) } }

        composeRule.onNodeWithText("Для вашей роли в приложении нет экранов. Пользуйтесь сайтом «Рядом».").assertIsDisplayed()
        composeRule.onNodeWithText("Выйти").performClick()

        assertEquals(listOf("logout"), events)
        composeRule.assertScreenIsAccessible()
    }

    @Test
    fun loadingIsAnnounced() {
        composeRule.setContent { RyadomTheme { LoadingScreen() } }

        composeRule.onNodeWithText("Загрузка…").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en")
    fun englishStringsAreUsedForEnglishLocale() {
        showLogin(yandex = true, dev = false)

        composeRule.onNodeWithText("Sign in with Yandex ID").assertIsDisplayed()
    }
}
