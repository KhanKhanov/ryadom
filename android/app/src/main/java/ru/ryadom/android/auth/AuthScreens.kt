package ru.ryadom.android.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.ryadom.android.R
import ru.ryadom.android.ui.BigButton
import ru.ryadom.android.ui.ErrorMessage
import ru.ryadom.android.ui.LiveStatus
import ru.ryadom.android.ui.ScreenHeading
import ru.ryadom.android.ui.SecondaryButton
import ru.ryadom.android.ui.messageRes
import ru.ryadom.shared.api.SelectableRole
import ru.ryadom.shared.client.SessionEndReason
import ru.ryadom.shared.client.UserError
import ru.ryadom.android.ui.R as UiR

/** Общая раскладка экранов входа: заголовок, текст, ошибка и кнопки сверху вниз — в порядке чтения TalkBack. */
@Composable
private fun AuthLayout(
    title: String,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeading(title)
            content()
        }
    }
}

@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            LiveStatus(text = stringResource(UiR.string.loading))
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.clearAndSetSemantics {})
            }
        }
    }
}

/**
 * Вход. [reason] — почему пришлось войти снова. Кнопка Яндекс ID есть, если приложение
 * зарегистрировано в Яндекс ID ([yandexAvailable]); вход по логину — только в отладочной сборке.
 */
@Composable
fun LoginScreen(
    reason: SessionEndReason?,
    error: UserError?,
    busy: Boolean,
    yandexAvailable: Boolean,
    devLoginAvailable: Boolean,
    onYandexLogin: () -> Unit,
    onDevLogin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AuthLayout(title = stringResource(R.string.login_title), modifier = modifier, scrollable = true) {
        Text(stringResource(R.string.login_intro), style = MaterialTheme.typography.bodyLarge)
        val reasonText =
            when (reason) {
                SessionEndReason.EXPIRED -> R.string.login_reason_expired
                SessionEndReason.BANNED -> R.string.login_reason_banned
                SessionEndReason.LOGGED_OUT, null -> null
            }
        LiveStatus(text = reasonText?.let { stringResource(it) })
        ErrorMessage(text = error?.let { stringResource(it.messageRes) })
        if (yandexAvailable) {
            BigButton(text = stringResource(R.string.login_yandex), onClick = onYandexLogin, enabled = !busy)
        } else if (!devLoginAvailable) {
            Text(stringResource(R.string.login_not_configured), style = MaterialTheme.typography.bodyLarge)
        }
        if (devLoginAvailable) DevLogin(busy, onDevLogin)
    }
}

/** Вход по логину без OAuth — для разработки, сервер должен работать с `AUTH_DEV_ENABLED=true`. */
@Composable
private fun DevLogin(
    busy: Boolean,
    onDevLogin: (String) -> Unit,
) {
    var login by rememberSaveable { mutableStateOf("blind-1") }
    val submit = { onDevLogin(login.trim().lowercase()) }
    OutlinedTextField(
        value = login,
        onValueChange = { login = it },
        label = { Text(stringResource(R.string.login_dev_label)) },
        singleLine = true,
        // Рамка темнее, чем в Material по умолчанию: поле должно быть заметно слабовидящему.
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant),
        enabled = !busy,
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Go,
            ),
        keyboardActions = KeyboardActions(onGo = { if (!busy && login.isNotBlank()) submit() }),
        modifier = Modifier.fillMaxWidth(),
    )
    SecondaryButton(text = stringResource(R.string.login_dev), onClick = submit, enabled = !busy && login.isNotBlank())
}

/** Первый вход: кто вы. Роль можно потом сменить: волонтёр — кнопкой «Мне нужна помощь», на сайте — в обе стороны. */
@Composable
fun RoleScreen(
    error: UserError?,
    busy: Boolean,
    onChoose: (SelectableRole) -> Unit,
    modifier: Modifier = Modifier,
) {
    AuthLayout(title = stringResource(R.string.role_title), modifier = modifier) {
        ErrorMessage(text = error?.let { stringResource(it.messageRes) })
        BigButton(
            text = stringResource(R.string.role_blind),
            onClick = { onChoose(SelectableRole.BLIND) },
            enabled = !busy,
            modifier = Modifier.weight(1f),
        )
        BigButton(
            text = stringResource(R.string.role_volunteer),
            onClick = { onChoose(SelectableRole.VOLUNTEER) },
            enabled = !busy,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
        )
    }
}

/** Профиль не загрузить: нет связи. */
@Composable
fun OfflineScreen(
    error: UserError?,
    onRetry: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AuthLayout(title = stringResource(R.string.offline_title), modifier = modifier) {
        ErrorMessage(text = stringResource((error ?: UserError.NETWORK).messageRes))
        BigButton(text = stringResource(R.string.offline_retry), onClick = onRetry, modifier = Modifier.weight(1f))
        SecondaryButton(text = stringResource(UiR.string.logout), onClick = onLogout)
    }
}

/** Роль, для которой в приложении нет экранов: администратор (админка — только на сайте). */
@Composable
fun UnsupportedRoleScreen(
    busy: Boolean,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AuthLayout(title = stringResource(R.string.app_name), modifier = modifier) {
        Text(stringResource(R.string.role_unsupported), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        SecondaryButton(text = stringResource(UiR.string.logout), onClick = onLogout, enabled = !busy)
    }
}
