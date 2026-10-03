package ru.ryadom.android.help

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.ryadom.android.testing.assertScreenIsAccessible
import ru.ryadom.android.ui.theme.RyadomTheme
import ru.ryadom.shared.api.CallCredentials
import ru.ryadom.shared.call.CallConnection
import ru.ryadom.shared.call.CallState
import ru.ryadom.shared.call.CameraState
import ru.ryadom.shared.call.MicrophoneState
import ru.ryadom.shared.call.PeerPresence
import ru.ryadom.shared.client.RealtimeStatus
import ru.ryadom.shared.client.UserError
import ru.ryadom.shared.help.BlindScreen
import ru.ryadom.shared.help.BlindState
import ru.ryadom.shared.help.HelpOutcome

/** Экраны незрячего: тексты, объявления для TalkBack, доступность и действия кнопок. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ru")
class BlindHelpScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val actions = RecordingActions()
    private val credentials = CallCredentials("ws://localhost:7880", "room", "token")

    private fun show(
        state: BlindState,
        permissionsDenied: Boolean = false,
    ) {
        composeRule.setContent {
            RyadomTheme {
                BlindHelpScreen(state, actions, permissionsDenied, videoPreview = { Box(it) })
            }
        }
    }

    private fun call(
        call: CallState,
        ending: Boolean = false,
    ) = BlindState(screen = BlindScreen.Call("id", credentials, call, volunteerJoined = true, ending = ending))

    /** Узел с этим текстом объявляется TalkBack при изменении (live region). */
    private fun assertAnnounced(text: String) {
        composeRule
            .onNode(hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
            .assertIsDisplayed()
    }

    @Test
    fun readyScreenIsOneBigButton() {
        show(BlindState(screen = BlindScreen.Ready()))

        composeRule.onNodeWithText("Позвать волонтёра").assertIsDisplayed().performClick()

        assertEquals(listOf("requestHelp"), actions.calls)
        composeRule.assertScreenIsAccessible()
    }

    @Test
    fun searchIsAnnouncedAndCanBeCancelled() {
        show(BlindState(screen = BlindScreen.Searching("id")))

        assertAnnounced("Ищем волонтёра…")
        composeRule.onNodeWithText("Отменить поиск").performClick()

        assertEquals(listOf("cancelSearch"), actions.calls)
        composeRule.assertScreenIsAccessible()
    }

    @Test
    fun nightNoAnswerExplainsWhy() {
        show(BlindState(screen = BlindScreen.Ready(HelpOutcome.NO_ANSWER_AT_NIGHT)))

        assertAnnounced(
            "Сейчас никто не ответил: ночью большинство волонтёров не принимают вызовы. Попробуйте ещё раз или утром.",
        )
    }

    @Test
    fun volunteerLeavingBeforeCallOffersToAskAgain() {
        show(BlindState(screen = BlindScreen.Ready(HelpOutcome.VOLUNTEER_LEFT)))

        assertAnnounced("Волонтёр не смог подключиться. Позовите помощь ещё раз — найдём другого.")
        composeRule.onNodeWithText("Позвать волонтёра").assertIsDisplayed()
    }

    @Test
    fun foundVolunteerIsAnnouncedImmediately() {
        show(call(CallState()))

        composeRule
            .onNode(hasText("Волонтёр найден. Подключаемся…"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
    }

    @Test
    fun activeCallHasMicrophoneAndEndButtons() {
        show(call(CallState(CallConnection.CONNECTED, PeerPresence.PRESENT, MicrophoneState.ON, CameraState.ON)))

        assertAnnounced("Волонтёр на связи. Наведите камеру на то, что нужно показать.")
        composeRule.onNodeWithText("Выключить микрофон").performClick()
        composeRule.onNodeWithText("Завершить звонок").performClick()

        assertEquals(listOf("setMicrophoneEnabled(false)", "endCall"), actions.calls)
        composeRule.assertScreenIsAccessible()
    }

    @Test
    fun volunteerDisappearingIsAnnouncedWithAdvice() {
        show(call(CallState(CallConnection.CONNECTED, PeerPresence.LEFT, MicrophoneState.ON, CameraState.ON)))

        composeRule
            .onNode(hasText("Волонтёр отключился. Подождите немного или завершите звонок."))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        composeRule.onNodeWithText("Завершить звонок").assertIsDisplayed()
    }

    @Test
    fun blockedCameraIsAnnouncedImmediatelyAndLeadsToSettings() {
        show(call(CallState(CallConnection.CONNECTED, PeerPresence.PRESENT, MicrophoneState.MUTED, CameraState.BLOCKED)))

        // «Наведите камеру» не говорим: волонтёр изображения не видит.
        composeRule
            .onNode(
                hasText(
                    "Волонтёр на связи. Нет доступа к камере: волонтёр вас не видит. Разрешите доступ в настройках. " +
                        "Ваш микрофон выключен.",
                ),
            ).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        composeRule.onNodeWithText("Открыть настройки").performClick()
        composeRule.onNodeWithText("Включить микрофон").performClick()

        assertEquals(listOf("openSettings", "setMicrophoneEnabled(true)"), actions.calls)
        composeRule.assertScreenIsAccessible()
    }

    @Test
    fun blockedMicrophoneIsAnnounced() {
        show(call(CallState(CallConnection.CONNECTED, PeerPresence.PRESENT, MicrophoneState.BLOCKED, CameraState.ON)))

        assertAnnounced(
            "Волонтёр на связи. Наведите камеру на то, что нужно показать. " +
                "Нет доступа к микрофону: волонтёр вас не слышит. Разрешите доступ в настройках.",
        )
        composeRule.onNodeWithText("Открыть настройки").assertIsDisplayed()
    }

    @Test
    fun mutedMicrophoneIsSaidInWords() {
        show(call(CallState(CallConnection.CONNECTED, PeerPresence.PRESENT, MicrophoneState.MUTED, CameraState.ON)))

        assertAnnounced("Волонтёр на связи. Наведите камеру на то, что нужно показать. Ваш микрофон выключен.")
        // Микрофон выключил сам пользователь — в настройки идти незачем.
        composeRule.onNodeWithText("Открыть настройки").assertDoesNotExist()
    }

    @Test
    fun endingCallHidesButtonsSoTheyCannotBePressedTwice() {
        show(call(CallState(CallConnection.CONNECTED, PeerPresence.PRESENT, MicrophoneState.ON), ending = true))

        assertAnnounced("Завершаем звонок…")
        composeRule.onNode(hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun ratingAsksAndSendsAnswer() {
        show(BlindState(screen = BlindScreen.Rating("id")))

        assertAnnounced("Звонок завершён. Удалось получить помощь?")
        composeRule.onNodeWithText("Да, помогли").performClick()
        composeRule.onNodeWithText("Нет").performClick()
        composeRule.onNodeWithText("Пропустить").performClick()

        assertEquals(listOf("rate(true)", "rate(false)", "skipRating"), actions.calls)
        composeRule.assertScreenIsAccessible()
    }

    @Test
    fun errorsAndLostConnectionAreAnnounced() {
        show(BlindState(screen = BlindScreen.Ready(), error = UserError.NETWORK, connection = RealtimeStatus.RECONNECTING))

        assertAnnounced("Нет связи с сервером. Проверьте интернет и попробуйте ещё раз.")
        assertAnnounced("Нет связи с сервером. Переподключаемся…")
    }

    @Test
    fun lostConnectionIsPartOfTheOneStatusLine() {
        show(BlindState(screen = BlindScreen.Searching("id"), connection = RealtimeStatus.RECONNECTING))

        assertAnnounced("Ищем волонтёра… Нет связи с сервером. Переподключаемся…")
        // Только строка состояния и строка ошибки: новый live region рядом TalkBack может не объявить.
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(2)
    }

    @Test
    fun busyButtonIsDisabled() {
        show(BlindState(screen = BlindScreen.Ready(), busy = true))

        composeRule.onNode(hasText("Позвать волонтёра") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun deniedPermissionsLeadToSettings() {
        show(BlindState(screen = BlindScreen.Ready()), permissionsDenied = true)

        assertAnnounced("Чтобы волонтёр видел и слышал вас, разрешите приложению доступ к камере и микрофону.")
        composeRule.onNodeWithText("Открыть настройки").performClick()

        assertEquals(listOf("openSettings"), actions.calls)
        composeRule.assertScreenIsAccessible()
    }

    @Test
    fun logoutIsConfirmed() {
        show(BlindState(screen = BlindScreen.Ready()))

        composeRule.onNodeWithText("Выйти").performClick()
        composeRule.onNodeWithText("Выйти из приложения?").assertIsDisplayed()
        composeRule.assertScreenIsAccessible()
        composeRule.onNodeWithText("Отмена").performClick()

        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    @Config(qualifiers = "en")
    fun englishStringsAreUsedForEnglishLocale() {
        show(BlindState(screen = BlindScreen.Ready(HelpOutcome.RATED)))

        composeRule.onNodeWithText("Call a volunteer").assertIsDisplayed()
        assertAnnounced("Thank you for your answer!")
    }

    private class RecordingActions : BlindHelpActions {
        val calls = mutableListOf<String>()

        override fun requestHelp() {
            calls += "requestHelp"
        }

        override fun cancelSearch() {
            calls += "cancelSearch"
        }

        override fun endCall() {
            calls += "endCall"
        }

        override fun setMicrophoneEnabled(enabled: Boolean) {
            calls += "setMicrophoneEnabled($enabled)"
        }

        override fun rate(helped: Boolean) {
            calls += "rate($helped)"
        }

        override fun skipRating() {
            calls += "skipRating"
        }

        override fun openSettings() {
            calls += "openSettings"
        }

        override fun logout() {
            calls += "logout"
        }
    }
}
