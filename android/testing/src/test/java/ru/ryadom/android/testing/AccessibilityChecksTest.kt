package ru.ryadom.android.testing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Контрольные тесты для assertScreenIsAccessible: на заведомо недоступных элементах проверка
 * должна падать. Если после обновления библиотек она молча перестанет что-то находить,
 * упадут эти тесты, а не пропустится ошибка на настоящем экране.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessibilityChecksTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun failsOnButtonWithoutDescription() {
        composeRule.setContent {
            Box(Modifier.size(48.dp).clickable {})
        }

        val error = assertThrows(AssertionError::class.java) { composeRule.assertScreenIsAccessible() }
        assertTrue(error.message, error.message!!.contains("нет описания"))
    }

    @Test
    fun failsOnTooSmallButton() {
        composeRule.setContent {
            Box(Modifier.size(8.dp).semantics { contentDescription = "Маленькая кнопка" }.clickable {})
        }

        val error = assertThrows(AssertionError::class.java) { composeRule.assertScreenIsAccessible() }
        assertTrue(error.message, error.message!!.contains("размер"))
    }

    @Test
    fun passesForMaterialButtonWithText() {
        composeRule.setContent {
            Button(onClick = {}) { Text("Позвать волонтёра") }
        }

        composeRule.assertScreenIsAccessible()
    }
}
