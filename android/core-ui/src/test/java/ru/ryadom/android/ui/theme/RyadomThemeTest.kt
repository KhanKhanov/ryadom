package ru.ryadom.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RyadomThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun usesLightColorsWhenSystemIsInLightMode() {
        assertEquals(lightColorScheme().background, backgroundColor())
    }

    @Test
    @Config(qualifiers = "night")
    fun usesDarkColorsWhenSystemIsInDarkMode() {
        assertEquals(darkColorScheme().background, backgroundColor())
    }

    private fun backgroundColor(): Color {
        var background = Color.Unspecified
        composeRule.setContent {
            RyadomTheme { background = MaterialTheme.colorScheme.background }
        }
        composeRule.waitForIdle()
        return background
    }
}
