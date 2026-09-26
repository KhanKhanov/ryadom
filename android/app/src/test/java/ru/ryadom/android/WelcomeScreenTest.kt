package ru.ryadom.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ru")
class WelcomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun titleIsDisplayedAsHeadingForTalkBack() {
        composeRule.setContent { WelcomeScreen() }

        composeRule
            .onNodeWithText("Рядом")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    @Config(qualifiers = "en")
    fun englishStringsAreUsedForEnglishLocale() {
        composeRule.setContent { WelcomeScreen() }

        composeRule.onNodeWithText("Ryadom").assertIsDisplayed()
    }
}
