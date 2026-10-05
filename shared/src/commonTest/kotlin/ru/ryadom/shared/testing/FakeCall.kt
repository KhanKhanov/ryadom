package ru.ryadom.shared.testing

import ru.ryadom.shared.api.CallCredentials
import ru.ryadom.shared.call.CallFactory
import ru.ryadom.shared.call.CallOptions
import ru.ryadom.shared.call.CallSession
import ru.ryadom.shared.call.CallState

/** Поддельный звонок: запоминает, что с ним сделали; [report] — сообщить новое состояние, как LiveKit. */
class FakeCall(
    val credentials: CallCredentials,
    val options: CallOptions,
    val report: (CallState) -> Unit,
) : CallSession {
    var connected = false
    var disconnected = false
    var microphone: Boolean? = null
    var retries = 0

    override fun connect() {
        connected = true
    }

    override fun setMicrophoneEnabled(enabled: Boolean) {
        microphone = enabled
    }

    override fun retryBlockedDevices() {
        retries++
    }

    override fun disconnect() {
        disconnected = true
    }
}

/** Создаёт [FakeCall] и запоминает их по порядку. */
class FakeCalls : CallFactory {
    val created = mutableListOf<FakeCall>()
    val last: FakeCall get() = created.last()

    override fun create(
        credentials: CallCredentials,
        options: CallOptions,
        onStateChanged: (CallState) -> Unit,
    ): CallSession = FakeCall(credentials, options, onStateChanged).also { created += it }
}
