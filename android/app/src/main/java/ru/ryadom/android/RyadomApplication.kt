package ru.ryadom.android

import android.app.Application

/** Приложение: создаёт [AppContainer] при запуске процесса. */
class RyadomApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this).also { it.start() }
    }
}
