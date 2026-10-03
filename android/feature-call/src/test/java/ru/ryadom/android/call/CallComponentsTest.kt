package ru.ryadom.android.call

import android.Manifest
import android.app.Application
import android.content.pm.ServiceInfo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.ryadom.android.testing.assertScreenIsAccessible
import ru.ryadom.shared.call.MicrophoneState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ru")
class CallComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun showMicrophone(state: MicrophoneState): MutableList<Boolean> {
        val changes = mutableListOf<Boolean>()
        composeRule.setContent { MicrophoneButton(state, onSetEnabled = { changes += it }) }
        return changes
    }

    @Test
    fun microphoneButtonSaysWhatItWillDo() {
        val changes = showMicrophone(MicrophoneState.ON)

        composeRule.onNodeWithText("Выключить микрофон").performClick()

        assertEquals(listOf(false), changes)
        composeRule.assertScreenIsAccessible()
    }

    @Test
    fun mutedMicrophoneCanBeTurnedOn() {
        val changes = showMicrophone(MicrophoneState.MUTED)

        composeRule.onNodeWithText("Включить микрофон").performClick()

        assertEquals(listOf(true), changes)
    }

    @Test
    fun blockedMicrophoneIsExplained() {
        showMicrophone(MicrophoneState.BLOCKED)

        composeRule.onNodeWithText("Нет доступа к микрофону").assertIsNotEnabled()
    }

    @Test
    fun foregroundServiceTypesFollowGrantedPermissions() {
        val app: Application = RuntimeEnvironment.getApplication()
        assertEquals(0, CallForegroundService.foregroundTypes(app))

        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, CallForegroundService.foregroundTypes(app))

        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            CallForegroundService.foregroundTypes(app),
        )
    }
}
