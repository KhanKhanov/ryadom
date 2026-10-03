package ru.ryadom.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import ru.ryadom.shared.client.StoredSession

/** Хранилище токенов на настоящем Android Keystore — в Robolectric его нет, поэтому тест на эмуляторе. */
@RunWith(AndroidJUnit4::class)
class KeystoreSessionStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = StoredSession("user", "secret-access", 42, "secret-refresh")

    @After
    fun clear() = KeystoreSessionStorage(context).clear()

    @Test
    fun sessionSurvivesAppRestart() {
        KeystoreSessionStorage(context).save(session)

        // Новый объект — как после перезапуска приложения: читает с диска, а не из памяти.
        assertEquals(session, KeystoreSessionStorage(context).load())
    }

    @Test
    fun tokensAreNotStoredInPlainText() {
        KeystoreSessionStorage(context).save(session)

        val raw =
            context
                .getSharedPreferences("session", 0)
                .all.values
                .joinToString()
        assertFalse(raw.contains("secret"))
    }

    @Test
    fun clearRemovesSession() {
        val storage = KeystoreSessionStorage(context)
        storage.save(session)

        storage.clear()

        assertNull(storage.load())
        assertNull(KeystoreSessionStorage(context).load())
    }
}
