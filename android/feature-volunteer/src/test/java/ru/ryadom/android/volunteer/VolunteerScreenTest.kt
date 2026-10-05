package ru.ryadom.android.volunteer

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.ryadom.android.testing.assertScreenIsAccessible
import ru.ryadom.android.ui.theme.RyadomTheme
import ru.ryadom.shared.api.CallCredentials
import ru.ryadom.shared.api.DoNotDisturb
import ru.ryadom.shared.api.Gender
import ru.ryadom.shared.api.GenderPreference
import ru.ryadom.shared.api.HelpRequest
import ru.ryadom.shared.api.Language
import ru.ryadom.shared.api.RequestStatus
import ru.ryadom.shared.api.Role
import ru.ryadom.shared.api.UserProfile
import ru.ryadom.shared.call.CallConnection
import ru.ryadom.shared.call.CallState
import ru.ryadom.shared.call.MicrophoneState
import ru.ryadom.shared.call.PeerPresence
import ru.ryadom.shared.client.RealtimeStatus
import ru.ryadom.shared.client.UserError
import ru.ryadom.shared.push.PushStatus
import ru.ryadom.shared.volunteer.FinishedCall
import ru.ryadom.shared.volunteer.Notice
import ru.ryadom.shared.volunteer.VolunteerCall
import ru.ryadom.shared.volunteer.VolunteerNotice
import ru.ryadom.shared.volunteer.VolunteerState

/** Экраны волонтёра: тексты, объявления для TalkBack, доступность и действия кнопок. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ru")
class VolunteerScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val actions = RecordingActions()

    private fun show(
        state: VolunteerState,
        extras: VolunteerExtras = VolunteerExtras(volunteerProfile),
    ) {
        composeRule.setContent {
            RyadomTheme {
                VolunteerScreen(state, extras, actions, peerVideo = { Box(it) })
            }
        }
    }

    /** Узел с этим текстом объявляется TalkBack при изменении (live region). */
    private fun assertAnnounced(
        text: String,
        mode: LiveRegionMode = LiveRegionMode.Polite,
    ) {
        composeRule
            .onNode(hasText(text, substring = true) and SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, mode))
            .assertIsDisplayed()
    }

    @Test
    fun incomingCallIsAnnouncedAndCanBeAccepted() {
        show(VolunteerState(incoming = listOf(request), notice = Notice(1, VolunteerNotice.INCOMING)))

        assertAnnounced("Входящий вызов: нужна помощь.", LiveRegionMode.Assertive)
        composeRule.onNodeWithText("Язык: русский").assertIsDisplayed()
        composeRule.onNodeWithText("Принять вызов").performClick()
        composeRule.onNodeWithText("Пропустить").performClick()

        assertEquals(listOf("accept $REQUEST_ID", "skip $REQUEST_ID"), actions.calls)
        composeRule.assertScreenIsAccessible()
    }

    @Test
    fun buttonsWaitWhileAccepting() {
        show(VolunteerState(incoming = listOf(request), accepting = REQUEST_ID))

        composeRule.onNodeWithText("Принимаем вызов…").assertIsNotEnabled()
        composeRule.onNodeWithText("Пропустить").assertIsNotEnabled()
    }

    @Test
    fun readySwitchAndQuietHours() {
        show(VolunteerState(synced = true), VolunteerExtras(volunteerProfile, quietNow = true))

        composeRule.onNodeWithText("Вызовы приходят в приложение", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("С 22:00 до 08:00 (часовой пояс Europe/Moscow) вызовы не приходят", substring = true).assertExists()
        composeRule.onNodeWithText("Сейчас время тишины: вызовы не придут до 08:00.").assertExists()
        composeRule.onNodeWithText("Готов помогать").performClick()

        assertEquals(listOf("ready false"), actions.calls)
        composeRule.assertScreenIsAccessible()
    }

    @Test
    fun withoutQuietHoursCallsComeAnyTime() {
        show(
            VolunteerState(),
            VolunteerExtras(volunteerProfile.copy(doNotDisturb = DoNotDisturb("00:00", "00:00"), notificationsEnabled = false)),
        )

        composeRule.onNodeWithText("Вызовы приходят в любое время суток.").assertExists()
        composeRule.onNodeWithText("Вызовы не приходят. Включите, когда будете готовы помочь.").assertExists()
    }

    @Test
    fun missingPermissionsAreExplainedWithButtons() {
        show(
            VolunteerState(),
            VolunteerExtras(
                volunteerProfile,
                CallReadiness(microphoneGranted = false, notificationsGranted = false),
            ),
        )

        composeRule.onNodeWithText("Разрешить микрофон").performScrollTo().performClick()
        composeRule.onNodeWithText("Разрешить уведомления").performScrollTo().performClick()

        assertEquals(listOf("allowMicrophone", "allowNotifications"), actions.calls)
        composeRule.assertScreenIsAccessible()
    }

    @Test
    fun deniedPermissionLeadsToSettingsOthersStillAsk() {
        show(
            VolunteerState(),
            VolunteerExtras(
                volunteerProfile,
                CallReadiness(microphoneGranted = false, notificationsGranted = false, notificationsDeniedBefore = true),
            ),
        )

        composeRule.onNodeWithText("Разрешить микрофон").performScrollTo().performClick()
        composeRule.onNodeWithText("Разрешить уведомления").assertDoesNotExist()
        composeRule.onNodeWithText("Открыть настройки").performScrollTo().performClick()

        assertEquals(listOf("allowMicrophone", "openSettings"), actions.calls)
        composeRule.assertScreenIsAccessible()
    }

    @Test
    fun fullScreenCallsCanBeAllowed() {
        show(VolunteerState(), VolunteerExtras(volunteerProfile, CallReadiness(fullScreenAllowed = false)))

        composeRule.onNodeWithText("Разрешить вызовы на весь экран").performScrollTo().performClick()

        assertEquals(listOf("allowFullScreen"), actions.calls)
    }

    @Test
    fun pushStateIsExplained() {
        val cases =
            mapOf(
                CallReadiness(push = PushAvailability.NO_GOOGLE_SERVICES) to "нет сервисов Google",
                CallReadiness(push = PushAvailability.NOT_CONFIGURED) to "уведомления о вызовах не настроены",
                CallReadiness(registration = PushStatus.FAILED) to "Не удалось подключить уведомления",
                CallReadiness() to "Вызов придёт, даже когда приложение закрыто.",
            )
        var readiness by mutableStateOf(CallReadiness())
        composeRule.setContent {
            RyadomTheme { VolunteerScreen(VolunteerState(), VolunteerExtras(volunteerProfile, readiness), actions, peerVideo = {}) }
        }
        for ((case, text) in cases) {
            readiness = case
            composeRule.onNodeWithText(text, substring = true).assertExists()
        }
    }

    @Test
    fun microphoneRefusalIsAnError() {
        show(VolunteerState(incoming = listOf(request)), VolunteerExtras(volunteerProfile, microphoneRefused = true))

        composeRule
            .onNode(hasText("Без микрофона вызов не принять", substring = true))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
    }

    @Test
    fun roleCanBeChangedAndLogoutIsConfirmed() {
        show(VolunteerState())

        composeRule.onNodeWithText("Мне нужна помощь").performScrollTo().performClick()
        composeRule.onNodeWithText("Выйти").performScrollTo().performClick()
        composeRule.onNodeWithText("Пока вы не войдёте снова", substring = true).assertIsDisplayed()
        composeRule.onNode(hasText("Выйти") and hasAnyAncestor(isDialog())).performClick()

        assertEquals(listOf("needHelp", "logout"), actions.calls)
    }

    @Test
    fun lostConnectionIsAnnounced() {
        show(VolunteerState(connection = RealtimeStatus.RECONNECTING))

        assertAnnounced("Нет связи с сервером. Переподключаемся…")
    }

    @Test
    fun callShowsVideoStatusAndCanBeEnded() {
        show(
            VolunteerState(
                call =
                    VolunteerCall(
                        REQUEST_ID,
                        credentials,
                        CallState(CallConnection.CONNECTED, PeerPresence.PRESENT, MicrophoneState.ON),
                    ),
            ),
        )

        assertAnnounced("Звонок идёт.")
        composeRule.onNodeWithText("Видео пока нет").assertIsDisplayed()
        composeRule.onNodeWithText("Выключить микрофон").performClick()
        composeRule.onNodeWithText("Завершить звонок").performClick()

        assertEquals(listOf("microphone false", "endCall"), actions.calls)
        composeRule.assertScreenIsAccessible()
    }

    @Test
    fun peerVideoHidesThePlaceholder() {
        show(
            VolunteerState(
                call = VolunteerCall(REQUEST_ID, credentials, CallState(CallConnection.CONNECTED, PeerPresence.PRESENT, peerVideo = true)),
            ),
        )

        composeRule.onNodeWithText("Видео пока нет").assertDoesNotExist()
    }

    @Test
    fun peerLeavingIsUrgent() {
        show(
            VolunteerState(
                call =
                    VolunteerCall(
                        REQUEST_ID,
                        credentials,
                        CallState(CallConnection.CONNECTED, PeerPresence.LEFT, MicrophoneState.BLOCKED),
                    ),
            ),
        )

        assertAnnounced("Собеседник отключился.", LiveRegionMode.Assertive)
        assertAnnounced("Нет доступа к микрофону", LiveRegionMode.Assertive)
        composeRule.onNodeWithText("Открыть настройки").performClick()
        assertEquals(listOf("openSettings"), actions.calls)
    }

    @Test
    fun endingCallHasNoButtons() {
        show(VolunteerState(call = VolunteerCall(REQUEST_ID, credentials, ending = true), error = UserError.NETWORK))

        assertAnnounced("Завершаем звонок…")
        composeRule.onNodeWithText("Завершить звонок").assertDoesNotExist()
        composeRule.onNodeWithText("Нет связи с сервером. Проверьте интернет и попробуйте ещё раз.").assertIsDisplayed()
    }

    @Test
    fun ratingAfterThePeerEndedTheCall() {
        show(VolunteerState(finished = FinishedCall(REQUEST_ID, endedByPeer = true), incoming = listOf(request)))

        assertAnnounced("Собеседник завершил звонок. Удалось помочь?", LiveRegionMode.Assertive)
        assertAnnounced("Входящий вызов", LiveRegionMode.Assertive)
        composeRule.onNodeWithText("Да").performClick()
        composeRule.onNodeWithText("Нет").performClick()
        composeRule.onNodeWithText("Пропустить").performClick()

        assertEquals(listOf("rate true", "rate false", "skipRating"), actions.calls)
        composeRule.assertScreenIsAccessible()
    }

    @Test
    @Config(qualifiers = "en")
    fun englishStringsAreUsedForEnglishLocale() {
        show(VolunteerState(incoming = listOf(request)))

        composeRule.onNodeWithText("Accept the call").assertIsDisplayed()
        composeRule.onNodeWithText("Volunteer page").assertIsDisplayed()
    }

    // --- Строка состояния ---

    @Test
    fun staleIncomingNoticeIsNotRepeated() {
        // Вызовов уже нет (закрылись при сверке с сервером), а последнее сообщение — о входящем.
        assertEquals(emptyList<Int>(), statusLine(VolunteerState(notice = Notice(1, VolunteerNotice.INCOMING))).parts)
    }

    @Test
    fun peerEndedNoticeBelongsToTheRatingScreen() {
        assertEquals(emptyList<Int>(), statusLine(VolunteerState(notice = Notice(1, VolunteerNotice.CALL_ENDED_BY_PEER))).parts)
    }

    @Test
    fun everyNoticeHasText() {
        val texts = VolunteerNotice.entries.map(::noticeText)

        assertEquals(VolunteerNotice.entries.size, texts.toSet().size)
    }

    private class RecordingActions : VolunteerActions {
        val calls = mutableListOf<String>()

        override fun accept(requestId: String) {
            calls += "accept $requestId"
        }

        override fun skip(requestId: String) {
            calls += "skip $requestId"
        }

        override fun setReady(ready: Boolean) {
            calls += "ready $ready"
        }

        override fun endCall() {
            calls += "endCall"
        }

        override fun setMicrophoneEnabled(enabled: Boolean) {
            calls += "microphone $enabled"
        }

        override fun rate(helped: Boolean) {
            calls += "rate $helped"
        }

        override fun skipRating() {
            calls += "skipRating"
        }

        override fun allowMicrophone() {
            calls += "allowMicrophone"
        }

        override fun allowNotifications() {
            calls += "allowNotifications"
        }

        override fun allowFullScreen() {
            calls += "allowFullScreen"
        }

        override fun openSettings() {
            calls += "openSettings"
        }

        override fun needHelp() {
            calls += "needHelp"
        }

        override fun logout() {
            calls += "logout"
        }
    }

    companion object {
        const val REQUEST_ID = "0199a1b2-0000-7000-8000-000000000002"

        val credentials = CallCredentials("ws://localhost:7880", REQUEST_ID, "token")

        val request =
            HelpRequest(
                id = REQUEST_ID,
                status = RequestStatus.SEARCHING,
                language = Language.RU,
                genderPreference = GenderPreference.ANY,
                createdAt = "2026-10-05T10:00:00Z",
                acceptedAt = null,
                endedAt = null,
                call = null,
            )

        val volunteerProfile =
            UserProfile(
                id = "0199a1b2-0000-7000-8000-000000000001",
                role = Role.VOLUNTEER,
                displayName = "Анна",
                languages = listOf(Language.RU),
                gender = Gender.UNSPECIFIED,
                genderPreference = GenderPreference.ANY,
                timezone = "Europe/Moscow",
                doNotDisturb = DoNotDisturb("22:00", "08:00"),
                notificationsEnabled = true,
                createdAt = "2026-09-30T10:00:00Z",
            )
    }
}
