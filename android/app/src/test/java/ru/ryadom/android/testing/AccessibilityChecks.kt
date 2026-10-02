package ru.ryadom.android.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** Минимальный размер элемента, на который можно нажать (CLAUDE.md, правило 1). */
private val MIN_TOUCH_TARGET = 48.dp

/** Элементы, с которыми пользователь взаимодействует: нажатие, долгое нажатие, ввод текста, ползунок. */
private val isInteractive =
    hasClickAction() or
        SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) or
        hasSetTextAction() or
        SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)

/**
 * Проверяет доступность экрана для TalkBack. Для каждого интерактивного элемента:
 * - есть описание, которое TalkBack прочитает (текст или contentDescription);
 * - размер не меньше 48×48 dp. Берётся настоящий размер элемента, а не зона нажатия,
 *   которую Compose сам расширяет: расширение не срабатывает, если рядом другие элементы.
 * Если есть нарушения, тест падает со списком всех проблем.
 *
 * Вызывайте в Compose-тесте каждого экрана после setContent (CLAUDE.md, правило 1).
 *
 * Почему не Accessibility Test Framework: под Robolectric он не проверяет Compose-экраны.
 * Контраст цветов и порядок фокуса здесь не проверяются — это задача ATF в UI-тестах
 * на эмуляторе (этап 4) и ручного чек-листа TalkBack.
 */
fun ComposeTestRule.assertScreenIsAccessible() {
    val problems =
        onAllNodes(isInteractive)
            .fetchSemanticsNodes()
            .flatMap { node -> accessibilityProblems(node, density) }
    if (problems.isNotEmpty()) {
        throw AssertionError(
            "Экран недоступен для TalkBack (${problems.size}):\n" + problems.joinToString("\n") { "- $it" },
        )
    }
}

private fun accessibilityProblems(
    node: SemanticsNode,
    density: Density,
): List<String> {
    val label = labelOf(node)
    val name = label?.let { "«$it»" } ?: "без описания"
    val where = "элемент $name, координаты ${node.boundsInRoot}"
    val minSizePx = with(density) { MIN_TOUCH_TARGET.roundToPx() }

    return buildList {
        if (label == null) {
            add("$where: нет описания для TalkBack (текста или contentDescription)")
        }
        if (node.size.width < minSizePx || node.size.height < minSizePx) {
            val width = with(density) { node.size.width.toDp() }
            val height = with(density) { node.size.height.toDp() }
            add("$where: размер $width × $height, нужно не меньше $MIN_TOUCH_TARGET × $MIN_TOUCH_TARGET")
        }
    }
}

/** Что прочитает TalkBack: contentDescription или текст элемента вместе с вложенными. */
private fun labelOf(node: SemanticsNode): String? {
    val config = node.config
    val parts =
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    return parts.filter { it.isNotBlank() }.joinToString(" ").ifEmpty { null }
}
