package ru.ryadom.android.call

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.util.LoggingLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import livekit.org.webrtc.RendererCommon
import ru.ryadom.shared.api.CallCredentials
import ru.ryadom.shared.call.CallConnection
import ru.ryadom.shared.call.CallFactory
import ru.ryadom.shared.call.CallOptions
import ru.ryadom.shared.call.CallSession
import ru.ryadom.shared.call.CallState
import ru.ryadom.shared.call.CameraState
import ru.ryadom.shared.call.MicrophoneState
import ru.ryadom.shared.call.PeerPresence

/**
 * Звонки через LiveKit (https://docs.livekit.io/reference/client-sdk-android/). Комната звонка
 * называется по id запроса; в ней двое: незрячий (задняя камера и микрофон) и волонтёр (только микрофон —
 * так разрешает его токен). Звук собеседника по умолчанию идёт в громкую связь: телефон держат перед собой.
 *
 * [current] — идущий звонок, чтобы экран мог показать изображение: незрячему — своей камеры,
 * волонтёру — камеры собеседника.
 *
 * @param debugLogging писать в logcat подробности подключения LiveKit — для отладочной сборки.
 */
class LiveKitCallFactory(
    context: Context,
    debugLogging: Boolean = false,
) : CallFactory {
    private val appContext = context.applicationContext
    private val currentFlow = MutableStateFlow<LiveKitCallSession?>(null)
    val current: StateFlow<LiveKitCallSession?> = currentFlow.asStateFlow()

    init {
        if (debugLogging) LiveKit.loggingLevel = LoggingLevel.INFO
    }

    override fun create(
        credentials: CallCredentials,
        options: CallOptions,
        onStateChanged: (CallState) -> Unit,
    ): CallSession =
        LiveKitCallSession(appContext, credentials, options, onStateChanged) { closed ->
            if (currentFlow.value === closed) currentFlow.value = null
        }.also { currentFlow.value = it }
}

/** Один звонок LiveKit. Все методы — из главного потока. */
class LiveKitCallSession internal constructor(
    context: Context,
    private val credentials: CallCredentials,
    private val options: CallOptions,
    private val onStateChanged: (CallState) -> Unit,
    private val onClosed: (LiveKitCallSession) -> Unit,
) : CallSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val room: Room =
        LiveKit.create(
            context,
            RoomOptions(
                // Качество видео подстраивается под канал связи.
                adaptiveStream = true,
                dynacast = true,
                // Незрячий показывает то, что перед ним, — задней камерой.
                videoTrackCaptureDefaults = LocalVideoTrackOptions(position = CameraPosition.BACK),
            ),
        )
    private var state = CallState(camera = if (options.publishCamera) CameraState.STARTING else CameraState.OFF)

    /** Элементы экрана, где показывается своя камера. */
    private val renderers = mutableSetOf<TextureViewRenderer>()
    private var localVideo: VideoTrack? = null

    /** Элементы экрана, где показывается камера собеседника (у волонтёра). */
    private val peerRenderers = mutableSetOf<TextureViewRenderer>()
    private var peerVideo: VideoTrack? = null
    private var closing = false
    private var released = false

    override fun connect() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { room.events.collect(::onEvent) }
        scope.launch {
            try {
                room.connect(credentials.url, credentials.token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Сервер звонков недоступен или токен не подошёл.
                if (!closing) update(state.copy(connection = CallConnection.DISCONNECTED))
                return@launch
            }
            update(state.copy(connection = CallConnection.CONNECTED))
            // Собеседник мог войти в комнату раньше нас — и уже показывать видео.
            updatePeer()
            room.remoteParticipants.values
                .firstNotNullOfOrNull { participant -> participant.videoTrackPublications.firstOrNull { it.second is VideoTrack } }
                ?.let { (publication, track) -> showPeerVideo(track as VideoTrack, publication.muted) }
            enableMicrophone(true)
            if (options.publishCamera) enableCamera()
        }
    }

    override fun setMicrophoneEnabled(enabled: Boolean) {
        scope.launch { enableMicrophone(enabled) }
    }

    override fun retryBlockedDevices() {
        if (closing || state.connection != CallConnection.CONNECTED) return
        scope.launch {
            if (state.microphone == MicrophoneState.BLOCKED) enableMicrophone(true)
            if (options.publishCamera && state.camera == CameraState.BLOCKED) enableCamera()
        }
    }

    override fun disconnect() {
        if (closing) return
        closing = true
        scope.cancel()
        room.disconnect()
        onClosed(this)
        // Освободить комнату можно, только когда экран отпустил изображение камеры (detachRenderer).
        releaseIfUnused()
    }

    /** Показывать свою камеру в [renderer]. Вызывает экран, когда элемент создан. */
    fun attachRenderer(renderer: TextureViewRenderer) {
        if (closing) return
        room.initVideoRenderer(renderer)
        renderers += renderer
        localVideo?.addRenderer(renderer)
    }

    /** Элемент экрана убран. */
    fun detachRenderer(renderer: TextureViewRenderer) {
        if (!renderers.remove(renderer)) return
        localVideo?.removeRenderer(renderer)
        renderer.release()
        releaseIfUnused()
    }

    /**
     * Показывать камеру собеседника в [renderer] (у волонтёра). Кадр вписывается целиком, без обрезки:
     * волонтёру нужно видеть весь документ или упаковку, а не середину.
     */
    fun attachPeerRenderer(renderer: TextureViewRenderer) {
        if (closing) return
        room.initVideoRenderer(renderer)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        peerRenderers += renderer
        peerVideo?.addRenderer(renderer)
    }

    /** Элемент экрана с камерой собеседника убран. */
    fun detachPeerRenderer(renderer: TextureViewRenderer) {
        if (!peerRenderers.remove(renderer)) return
        peerVideo?.removeRenderer(renderer)
        renderer.release()
        releaseIfUnused()
    }

    private fun releaseIfUnused() {
        if (closing && renderers.isEmpty() && peerRenderers.isEmpty() && !released) {
            released = true
            room.release()
        }
    }

    private fun onEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.Reconnecting -> {
                update(state.copy(connection = CallConnection.RECONNECTING))
            }

            is RoomEvent.Reconnected -> {
                update(state.copy(connection = CallConnection.CONNECTED))
            }

            is RoomEvent.Disconnected -> {
                if (!closing) update(state.copy(connection = CallConnection.DISCONNECTED))
            }

            is RoomEvent.ParticipantConnected, is RoomEvent.ParticipantDisconnected -> {
                updatePeer()
            }

            is RoomEvent.TrackSubscribed -> {
                (event.track as? VideoTrack)?.let { showPeerVideo(it, event.publication.muted) }
            }

            is RoomEvent.TrackUnsubscribed -> {
                if (event.track === peerVideo) showPeerVideo(null, muted = false)
            }

            // Собеседник выключил или включил камеру, не уходя из звонка («Скрыть видео», этап 7).
            is RoomEvent.TrackMuted -> {
                if (event.participant is RemoteParticipant &&
                    event.publication.track === peerVideo
                ) {
                    updatePeerVideo(muted = true)
                }
            }

            is RoomEvent.TrackUnmuted -> {
                if (event.participant is RemoteParticipant &&
                    event.publication.track === peerVideo
                ) {
                    updatePeerVideo(muted = false)
                }
            }

            else -> {
                Unit
            }
        }
    }

    /** Видео собеседника появилось ([track]) или пропало (`null`). */
    private fun showPeerVideo(
        track: VideoTrack?,
        muted: Boolean,
    ) {
        if (track !== peerVideo) {
            peerRenderers.forEach { peerVideo?.removeRenderer(it) }
            peerVideo = track
            peerRenderers.forEach { track?.addRenderer(it) }
        }
        updatePeerVideo(muted)
    }

    private fun updatePeerVideo(muted: Boolean) {
        if (!closing) update(state.copy(peerVideo = peerVideo != null && !muted))
    }

    /** Собеседник — единственный другой участник комнаты. Ушёл, если раньше был, а теперь его нет. */
    private fun updatePeer() {
        val present = room.remoteParticipants.isNotEmpty()
        val peer =
            when {
                present -> PeerPresence.PRESENT
                state.peer == PeerPresence.WAITING -> PeerPresence.WAITING
                else -> PeerPresence.LEFT
            }
        update(state.copy(peer = peer))
    }

    private suspend fun enableMicrophone(enabled: Boolean) {
        val ok =
            try {
                room.localParticipant.setMicrophoneEnabled(enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false // Нет разрешения или микрофон занят другим приложением.
            }
        val microphone =
            when {
                !ok && enabled -> MicrophoneState.BLOCKED
                enabled -> MicrophoneState.ON
                else -> MicrophoneState.MUTED
            }
        if (!closing) update(state.copy(microphone = microphone))
    }

    private suspend fun enableCamera() {
        val ok =
            try {
                room.localParticipant.setCameraEnabled(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        if (closing) return
        if (!ok) {
            update(state.copy(camera = CameraState.BLOCKED))
            return
        }
        val track = room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
        localVideo = track
        renderers.forEach { track?.addRenderer(it) }
        update(state.copy(camera = CameraState.ON))
    }

    private fun update(next: CallState) {
        if (next == state) return
        state = next
        onStateChanged(next)
    }
}
