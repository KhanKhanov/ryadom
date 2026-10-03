package ru.ryadom.android

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.ryadom.shared.client.StoredSession

/**
 * Хранилище токенов, когда зашифровать их не получается. В Robolectric нет Android Keystore — как на
 * отдельных старых телефонах, поэтому этот случай проверяется здесь; обычная работа — в androidTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class KeystoreSessionStorageFailureTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val session = StoredSession("user", "access", 42, "refresh-2")

    @Test
    fun unsavedSessionRemovesTheOldOneFromDisk() {
        // Прежний вход, записанный, когда Keystore ещё работал. Его refresh-токен сервер уже сменил.
        prefs.edit().putString("session", "old-encrypted-session").commit()
        val storage = KeystoreSessionStorage(context)

        storage.save(session)

        assertEquals("до закрытия приложения вход работает", session, storage.load())
        // Иначе после перезапуска старый refresh-токен ушёл бы на сервер, и тот завершил бы все сеансы пользователя.
        assertFalse(prefs.contains("session"))
        assertNull("после перезапуска нужно просто войти снова", KeystoreSessionStorage(context).load())
    }
}
