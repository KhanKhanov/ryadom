package ru.ryadom.shared.call

import ru.ryadom.shared.api.CallCredentials

// Звонок глазами бизнес-логики. Сам видеозвонок (LiveKit) — на платформе: в shared нельзя
// зависеть от LiveKit (CLAUDE.md, правило 7). Платформа реализует CallSession и сообщает CallState,
// а решает, когда подключаться, отключаться и что сказать пользователю, общий код.

/** Соединение с сервером видеозвонков. */
enum class CallConnection { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED }

/** Собеседник: ещё не подключался, в звонке, ушёл (закрыл приложение, пропала сеть). */
enum class PeerPresence { WAITING, PRESENT, LEFT }

/** Свой микрофон. [BLOCKED] — нет разрешения или микрофон занят. */
enum class MicrophoneState { STARTING, ON, MUTED, BLOCKED }

/** Своя камера (только у незрячего). [BLOCKED] — нет разрешения или камера занята. */
enum class CameraState { OFF, STARTING, ON, BLOCKED }

data class CallState(
    val connection: CallConnection = CallConnection.CONNECTING,
    val peer: PeerPresence = PeerPresence.WAITING,
    val microphone: MicrophoneState = MicrophoneState.STARTING,
    val camera: CameraState = CameraState.OFF,
    /** Собеседник показывает видео (у волонтёра — камера незрячего). */
    val peerVideo: Boolean = false,
)

data class CallOptions(
    /** Публиковать камеру. У волонтёра камеры нет: токен LiveKit разрешает ему только микрофон. */
    val publishCamera: Boolean,
)

/**
 * Один звонок на платформе. Методы не ждут результата: о том, что получилось,
 * реализация сообщает новым [CallState] через `onStateChanged` из [CallFactory.create] (из любого потока).
 */
interface CallSession {
    /** Подключается к комнате и включает микрофон (и камеру, если нужна). */
    fun connect()

    fun setMicrophoneEnabled(enabled: Boolean)

    /**
     * Ещё раз включить камеру и микрофон, если они [CameraState.BLOCKED] / [MicrophoneState.BLOCKED]:
     * например, пользователь вернулся из настроек, где разрешил доступ. Остальное не трогает.
     */
    fun retryBlockedDevices()

    /** Выходит из комнаты и освобождает камеру и микрофон. После этого сессия не используется. */
    fun disconnect()
}

fun interface CallFactory {
    fun create(
        credentials: CallCredentials,
        options: CallOptions,
        onStateChanged: (CallState) -> Unit,
    ): CallSession
}

/** Главное о звонке одной фразой — для строки состояния и голосового объявления. */
enum class CallStatus {
    /** Подключаемся к звонку. */
    CONNECTING,

    /** Подключились, собеседника ещё нет. */
    WAITING_FOR_PEER,

    /** Разговор идёт. */
    ACTIVE,

    /** Собеседник пропал из звонка, не завершив его: можно подождать или завершить звонок. */
    PEER_LEFT,

    /** Связь прервалась, восстанавливаем. */
    RECONNECTING,

    /** Связь потеряна окончательно — звонок нужно завершить. */
    DISCONNECTED,
}

val CallState.status: CallStatus
    get() =
        when (connection) {
            CallConnection.CONNECTING -> {
                CallStatus.CONNECTING
            }

            CallConnection.RECONNECTING -> {
                CallStatus.RECONNECTING
            }

            CallConnection.DISCONNECTED -> {
                CallStatus.DISCONNECTED
            }

            CallConnection.CONNECTED -> {
                when (peer) {
                    PeerPresence.WAITING -> CallStatus.WAITING_FOR_PEER
                    PeerPresence.PRESENT -> CallStatus.ACTIVE
                    PeerPresence.LEFT -> CallStatus.PEER_LEFT
                }
            }
        }
