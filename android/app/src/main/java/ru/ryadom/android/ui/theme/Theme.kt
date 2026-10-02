package ru.ryadom.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Тема приложения. Светлая или тёмная — по настройке системы: многим слабовидящим
 * удобнее тёмная тема. Динамические цвета Android 12+ не используем, чтобы контраст
 * не зависел от обоев. Тема окна (res/values/themes.xml) должна переключаться так же.
 */
@Composable
fun RyadomTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
