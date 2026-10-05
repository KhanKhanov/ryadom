package ru.ryadom.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.ryadom.android.testing.assertScreenIsAccessible
import ru.ryadom.shared.client.UserError

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ru")
class ComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun buttonsAreAccessibleAndClickable() {
        var clicks = 0
        composeRule.setContent {
            Column {
                BigButton(text = "Позвать волонтёра", onClick = { clicks++ })
                SecondaryButton(text = "Выйти", onClick = { clicks++ })
            }
        }

        composeRule.onNodeWithText("Позвать волонтёра").performClick()
        composeRule.onNodeWithText("Выйти").performClick()

        assertEquals(2, clicks)
        composeRule.assertScreenIsAccessible()
    }

    @Test
    fun headingIsMarkedForTalkBack() {
        composeRule.setContent { ScreenHeading("Вход") }

        composeRule.onNodeWithText("Вход").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun statusIsLiveRegion() {
        composeRule.setContent {
            Column {
                LiveStatus("Ищем волонтёра…")
                LiveStatus("Волонтёр найден", assertive = true)
            }
        }

        composeRule
            .onNode(hasText("Ищем волонтёра…"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule
            .onNode(hasText("Волонтёр найден"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
    }

    @Test
    fun errorIsAnnouncedAtOnce() {
        composeRule.setContent { ErrorMessage("Нет связи") }

        composeRule
            .onNode(hasText("Нет связи"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
    }

    @Test
    fun everyUserErrorHasText() {
        var texts = emptyList<String>()
        composeRule.setContent {
            texts = UserError.entries.map { stringResource(it.messageRes) }
        }

        assertEquals(UserError.entries.size, texts.toSet().size)
    }

    @Test
    fun switchRowIsOneAccessibleSwitch() {
        var checked by mutableStateOf(false)
        composeRule.setContent { SwitchRow("Готов помогать", checked, onCheckedChange = { checked = it }) }

        composeRule
            .onNode(hasText("Готов помогать"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .performClick()

        assertTrue(checked)
        composeRule
            .onNode(
                hasText("Готов помогать"),
            ).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        composeRule.assertScreenIsAccessible()
    }
}
