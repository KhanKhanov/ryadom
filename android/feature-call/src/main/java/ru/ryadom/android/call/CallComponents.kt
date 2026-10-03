package ru.ryadom.android.call

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.livekit.android.renderer.TextureViewRenderer
import ru.ryadom.android.ui.SecondaryButton
import ru.ryadom.shared.call.MicrophoneState

/**
 * Изображение своей камеры. Незрячему оно не нужно, но помогает слабовидящим навести камеру
 * и зрячим тестировщикам. Для TalkBack элемент скрыт: о камере сообщает строка состояния.
 */
@Composable
fun LocalCameraPreview(
    calls: LiveKitCallFactory,
    modifier: Modifier = Modifier,
) {
    val session by calls.current.collectAsStateWithLifecycle()
    val current = session ?: return
    key(current) {
        AndroidView(
            factory = { context -> TextureViewRenderer(context).also { current.attachRenderer(it) } },
            onRelease = { current.detachRenderer(it) },
            modifier = modifier.clearAndSetSemantics {},
        )
    }
}

/** Выключить или включить свой микрофон. Надпись говорит, что произойдёт при нажатии. */
@Composable
fun MicrophoneButton(
    microphone: MicrophoneState,
    onSetEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text =
        when (microphone) {
            MicrophoneState.ON, MicrophoneState.STARTING -> R.string.call_microphone_off
            MicrophoneState.MUTED -> R.string.call_microphone_on
            MicrophoneState.BLOCKED -> R.string.call_microphone_blocked
        }
    SecondaryButton(
        text = stringResource(text),
        onClick = { onSetEnabled(microphone == MicrophoneState.MUTED) },
        enabled = microphone == MicrophoneState.ON || microphone == MicrophoneState.MUTED,
        modifier = modifier,
    )
}

/** Экран не гаснет, пока этот элемент на экране (во время звонка). */
@Composable
fun KeepScreenOn() {
    val view: View = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
