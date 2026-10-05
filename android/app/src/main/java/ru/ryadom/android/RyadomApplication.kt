package ru.ryadom.android

import android.app.Application
import ru.ryadom.android.volunteer.VolunteerHost

/** Приложение: создаёт [AppContainer] при запуске процесса. */
class RyadomApplication :
    Application(),
    VolunteerHost {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this).also { it.start() }
    }

    override fun skipIncomingCall(requestId: String) = container.skipIncomingCall(requestId)
}
