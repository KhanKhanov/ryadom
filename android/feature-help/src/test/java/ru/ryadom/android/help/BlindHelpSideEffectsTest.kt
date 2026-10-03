package ru.ryadom.android.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.ryadom.android.call.CallServiceStatus
import ru.ryadom.android.ui.HapticPattern
import ru.ryadom.shared.api.CallCredentials
import ru.ryadom.shared.call.CallConnection
import ru.ryadom.shared.call.CallState
import ru.ryadom.shared.call.CameraState
import ru.ryadom.shared.call.MicrophoneState
import ru.ryadom.shared.call.PeerPresence
import ru.ryadom.shared.client.RealtimeStatus
import ru.ryadom.shared.help.BlindScreen
import ru.ryadom.shared.help.BlindState
import ru.ryadom.shared.help.HelpOutcome

/** Когда вибрировать и когда держать foreground service. */
class BlindHelpSideEffectsTest {
    private val credentials = CallCredentials("ws://localhost:7880", "room", "token")
    private val ready = BlindState(BlindScreen.Ready())
    private val searching = BlindState(BlindScreen.Searching("id"))

    private fun call(
        peer: PeerPresence,
        connection: CallConnection = CallConnection.CONNECTED,
        microphone: MicrophoneState = MicrophoneState.ON,
        camera: CameraState = CameraState.ON,
        ending: Boolean = false,
    ) = BlindState(
        BlindScreen.Call("id", credentials, CallState(connection, peer, microphone, camera), volunteerJoined = true, ending = ending),
    )

    @Test
    fun searchAndItsEndHaveTheirVibration() {
        assertEquals(HapticPattern.TICK, hapticFor(ready, searching))
        assertEquals(HapticPattern.END, hapticFor(searching, BlindState(BlindScreen.Ready(HelpOutcome.NO_ANSWER))))
        assertEquals(HapticPattern.END, hapticFor(searching, BlindState(BlindScreen.Ready(HelpOutcome.CANCELLED))))
    }

    @Test
    fun goodNewsInCallVibratesTwice() {
        assertEquals(HapticPattern.SUCCESS, hapticFor(searching, call(PeerPresence.WAITING, CallConnection.CONNECTING)))
        // Волонтёр появился в звонке — главное событие, его нужно почувствовать.
        assertEquals(HapticPattern.SUCCESS, hapticFor(call(PeerPresence.WAITING), call(PeerPresence.PRESENT)))
        assertEquals(HapticPattern.SUCCESS, hapticFor(call(PeerPresence.LEFT), call(PeerPresence.PRESENT)))
    }

    @Test
    fun lostVolunteerOrConnectionVibratesLong() {
        assertEquals(HapticPattern.END, hapticFor(call(PeerPresence.PRESENT), call(PeerPresence.LEFT)))
        assertEquals(HapticPattern.END, hapticFor(call(PeerPresence.PRESENT), call(PeerPresence.PRESENT, CallConnection.DISCONNECTED)))
        assertEquals(HapticPattern.END, hapticFor(call(PeerPresence.PRESENT), BlindState(BlindScreen.Rating("id"))))
    }

    @Test
    fun everyOtherStatusChangeIsATick() {
        // Каждое изменение строки состояния (docs/ARCHITECTURE.md, раздел 7): подключились и ждём волонтёра,
        // связь в звонке восстанавливается, микрофон выключен, звонок завершается, оценка отправлена, нет связи с сервером.
        assertEquals(HapticPattern.TICK, hapticFor(call(PeerPresence.WAITING, CallConnection.CONNECTING), call(PeerPresence.WAITING)))
        assertEquals(HapticPattern.TICK, hapticFor(call(PeerPresence.PRESENT), call(PeerPresence.PRESENT, CallConnection.RECONNECTING)))
        assertEquals(
            HapticPattern.TICK,
            hapticFor(call(PeerPresence.PRESENT), call(PeerPresence.PRESENT, microphone = MicrophoneState.MUTED)),
        )
        assertEquals(HapticPattern.TICK, hapticFor(call(PeerPresence.PRESENT), call(PeerPresence.PRESENT, camera = CameraState.BLOCKED)))
        assertEquals(HapticPattern.TICK, hapticFor(call(PeerPresence.PRESENT), call(PeerPresence.PRESENT, ending = true)))
        assertEquals(HapticPattern.TICK, hapticFor(BlindState(BlindScreen.Rating("id")), BlindState(BlindScreen.Ready(HelpOutcome.RATED))))
        assertEquals(HapticPattern.TICK, hapticFor(searching, searching.copy(connection = RealtimeStatus.RECONNECTING)))
    }

    @Test
    fun noVibrationWithoutNews() {
        // Восстановление после запуска — не событие.
        assertNull(hapticFor(BlindState(BlindScreen.Loading), searching))
        // Строка состояния не изменилась: камера включилась, ошибка, ответ сервера.
        assertNull(hapticFor(call(PeerPresence.PRESENT, camera = CameraState.STARTING), call(PeerPresence.PRESENT)))
        assertNull(hapticFor(searching, searching.copy(busy = true)))
        // Строка опустела — сказать нечего (например, связь с сервером восстановилась на главном экране).
        assertNull(hapticFor(ready.copy(connection = RealtimeStatus.RECONNECTING), ready.copy(connection = RealtimeStatus.CONNECTED)))
        assertNull(hapticFor(BlindState(BlindScreen.Ready(HelpOutcome.RATED)), ready))
    }

    @Test
    fun foregroundServiceRunsDuringSearchAndCall() {
        assertEquals(CallServiceStatus.SEARCHING, serviceStatus(searching.screen))
        assertEquals(CallServiceStatus.CALL, serviceStatus(call(PeerPresence.PRESENT).screen))
        assertNull(serviceStatus(ready.screen))
        assertNull(serviceStatus(BlindScreen.Rating("id")))
        assertNull(serviceStatus(BlindScreen.Loading))
    }
}
