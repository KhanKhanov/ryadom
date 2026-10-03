package ru.ryadom.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// Компоненты с правилами доступности (CLAUDE.md, правило 1): описание для TalkBack — текст кнопки,
// зона нажатия от 48 dp (у наших кнопок — от 56 dp), статусы объявляются голосом через live region.

/** Минимальная высота обычной кнопки: больше обязательных 48 dp, чтобы попадать было легче. */
private val BUTTON_MIN_HEIGHT = 56.dp

// Неактивные кнопки (пока ждём ответа сервера) — с контрастным текстом, а не бледные, как в Material
// по умолчанию: слабовидящий должен прочитать надпись и в этот момент. Нашёл Accessibility Test Framework.

/**
 * Главная кнопка экрана — во всё доступное место ([modifier] задаёт размер, обычно `weight(1f)`):
 * незрячему не нужно искать её на экране. [text] — и надпись, и то, что прочитает TalkBack.
 */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = BUTTON_MIN_HEIGHT),
        enabled = enabled,
        shape = RoundedCornerShape(32.dp),
        colors =
            colors.copy(
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/** Второстепенное действие: «Выйти», «Пропустить», «Повторить». */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = BUTTON_MIN_HEIGHT),
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

/** Заголовок экрана: TalkBack позволяет переходить к нему жестом «по заголовкам». */
@Composable
fun ScreenHeading(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.fillMaxWidth().semantics { heading() },
    )
}

/**
 * Строка состояния, которую TalkBack объявляет голосом при каждом изменении (live region).
 * Контейнер есть на экране всегда, даже без текста: TalkBack надёжно объявляет изменения внутри
 * существующего live region, а появление нового элемента может пропустить.
 * Держите один такой элемент на месте при смене состояний, а не создавайте новый на каждом экране.
 *
 * @param assertive прервать текущую речь (для важного: волонтёр найден, звонок прервался).
 */
@Composable
fun LiveStatus(
    text: String?,
    modifier: Modifier = Modifier,
    assertive: Boolean = false,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    liveRegion = if (assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
                },
        contentAlignment = Alignment.Center,
    ) {
        if (text != null) {
            Text(
                text = text,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

/**
 * Сообщение об ошибке. Объявляется сразу (assertive). Цвет — только дополнение:
 * из самого текста понятно, что это ошибка (CLAUDE.md, правило 1: никакой информации только цветом).
 */
@Composable
fun ErrorMessage(
    text: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
    ) {
        if (text != null) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}
