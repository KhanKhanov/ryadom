package ru.ryadom.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityViewCheckException
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.ryadom.android.auth.LoginScreen
import ru.ryadom.android.auth.OfflineScreen
import ru.ryadom.android.auth.RoleScreen
import ru.ryadom.android.auth.UnsupportedRoleScreen
import ru.ryadom.android.help.BlindHelpActions
import ru.ryadom.android.help.BlindHelpScreen
import ru.ryadom.android.ui.theme.RyadomTheme
import ru.ryadom.shared.api.CallCredentials
import ru.ryadom.shared.api.Role
import ru.ryadom.shared.call.CallConnection
import ru.ryadom.shared.call.CallState
import ru.ryadom.shared.call.CameraState
import ru.ryadom.shared.call.MicrophoneState
import ru.ryadom.shared.call.PeerPresence
import ru.ryadom.shared.client.RealtimeStatus
import ru.ryadom.shared.client.SessionEndReason
import ru.ryadom.shared.client.UserError
import ru.ryadom.shared.help.BlindScreen
import ru.ryadom.shared.help.BlindState
import ru.ryadom.shared.help.HelpOutcome

/**
 * Accessibility Test Framework (docs/ARCHITECTURE.md, раздел 12): на эмуляторе проверяет то, что не
 * проверить в Robolectric, — контраст текста, размер зоны нажатия на реальном экране, подписи, повторяющиеся
 * описания. Каждый экран — в светлой и тёмной теме. Запуск: `./gradlew :android:app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class ScreensAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val credentials = CallCredentials("ws://localhost:7880", "room", "token")

    @Before
    fun enableChecks() {
        composeRule.enableAccessibilityChecks(
            AccessibilityValidator()
                .setRunChecksFromRootView(true)
                // Без снимка экрана ATF не может проверить контраст текста.
                .setCaptureScreenshots(true)
                .setCheckPreset(AccessibilityCheckPreset.LATEST)
                // Контраст текста в Compose ATF оценивает по снимку экрана и сообщает как предупреждение, а не ошибку.
                .setThrowExceptionFor(AccessibilityCheckResultType.WARNING),
        )
    }

    // Контрольные проверки: на заведомо недоступных элементах ATF должен падать. Если после обновления
    // библиотек проверка молча перестанет работать, упадут эти тесты, а не пропустится ошибка на экране.

    @Test
    fun controlTinyUnlabeledButtonIsReported() {
        composeRule.setContent { Box(Modifier.size(16.dp).clickable {}) }

        assertThrows(AccessibilityViewCheckException::class.java) { composeRule.onRoot().tryPerformAccessibilityChecks() }
    }

    @Test
    fun controlLowContrastTextIsReported() {
        composeRule.setContent {
            Text("Позвать волонтёра", color = Color(0xFFDDDDDD), modifier = Modifier.background(Color.White))
        }

        assertThrows(AccessibilityViewCheckException::class.java) { composeRule.onRoot().tryPerformAccessibilityChecks() }
    }

    /** Показывает экран в светлой и тёмной теме; ATF бросает AssertionError при нарушениях. */
    private fun check(content: @Composable () -> Unit) {
        var dark by mutableStateOf(false)
        composeRule.setContent { RyadomTheme(darkTheme = dark) { content() } }
        composeRule.onRoot().tryPerformAccessibilityChecks()
        dark = true
        composeRule.waitForIdle()
        composeRule.onRoot().tryPerformAccessibilityChecks()
    }

    private fun blind(
        state: BlindState,
        permissionsDenied: Boolean = false,
    ) = check {
        BlindHelpScreen(
            state = state,
            actions = NoActions,
            permissionsDenied = permissionsDenied,
            // Вместо камеры — тёмный прямоугольник, как изображение на экране звонка.
            videoPreview = { Box(it.background(Color.DarkGray)) },
        )
    }

    private fun call(
        call: CallState,
        ending: Boolean = false,
    ) = BlindState(screen = BlindScreen.Call("id", credentials, call, volunteerJoined = true, ending = ending))

    @Test
    fun login() =
        check {
            LoginScreen(
                reason = SessionEndReason.EXPIRED,
                error = UserError.LOGIN_FAILED,
                busy = false,
                yandexAvailable = true,
                devLoginAvailable = true,
                onYandexLogin = {},
                onDevLogin = {},
            )
        }

    @Test
    fun role() = check { RoleScreen(error = null, busy = false, onChoose = {}) }

    @Test
    fun offline() = check { OfflineScreen(error = UserError.NETWORK, onRetry = {}, onLogout = {}) }

    @Test
    fun volunteerNotYet() =
        check {
            UnsupportedRoleScreen(role = Role.VOLUNTEER, error = UserError.ACTIVE_REQUEST, busy = false, onNeedHelp = {}, onLogout = {})
        }

    @Test
    fun ready() = blind(BlindState(screen = BlindScreen.Ready(HelpOutcome.NO_ANSWER_AT_NIGHT)))

    @Test
    fun readyWithoutPermissionsAndConnection() =
        blind(
            BlindState(screen = BlindScreen.Ready(), error = UserError.NETWORK, connection = RealtimeStatus.RECONNECTING),
            permissionsDenied = true,
        )

    @Test
    fun readyWhileBusy() = blind(BlindState(screen = BlindScreen.Ready(), busy = true))

    @Test
    fun searching() = blind(BlindState(screen = BlindScreen.Searching("id")))

    @Test
    fun callActive() = blind(call(CallState(CallConnection.CONNECTED, PeerPresence.PRESENT, MicrophoneState.ON, CameraState.ON)))

    @Test
    fun callWithMutedMicrophoneAndBlockedCamera() =
        blind(call(CallState(CallConnection.CONNECTED, PeerPresence.LEFT, MicrophoneState.MUTED, CameraState.BLOCKED)))

    @Test
    fun callEnding() = blind(call(CallState(CallConnection.CONNECTED, PeerPresence.PRESENT, MicrophoneState.ON), ending = true))

    @Test
    fun rating() = blind(BlindState(screen = BlindScreen.Rating("id")))

    private object NoActions : BlindHelpActions {
        override fun requestHelp() = Unit

        override fun cancelSearch() = Unit

        override fun endCall() = Unit

        override fun setMicrophoneEnabled(enabled: Boolean) = Unit

        override fun rate(helped: Boolean) = Unit

        override fun skipRating() = Unit

        override fun openSettings() = Unit

        override fun logout() = Unit
    }
}
