package ru.ryadom.android

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import ru.ryadom.shared.client.SessionStorage
import ru.ryadom.shared.client.StoredSession
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Токены входа в SharedPreferences, зашифрованные ключом AES из Android Keystore: ключ не покидает
 * защищённое хранилище телефона, поэтому скопированный файл настроек без телефона бесполезен.
 * Облачный бэкап данных приложения выключен (res/xml/data_extraction_rules.xml).
 *
 * Если Keystore не работает (бывает на отдельных старых телефонах), вход живёт до закрытия приложения.
 */
class KeystoreSessionStorage(
    context: Context,
) : SessionStorage {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Последний сохранённый вход — на случай, если записать на диск не получилось. */
    private var memory: StoredSession? = null

    override fun load(): StoredSession? {
        memory?.let { return it }
        val stored = prefs.getString(KEY_SESSION, null) ?: return null
        return try {
            StoredSession.decode(decrypt(stored)).also { memory = it }
        } catch (e: Exception) {
            // Ключ пропал (например, сменили блокировку экрана на некоторых телефонах) или данные повреждены.
            Log.w(TAG, "Cannot read saved session: ${e::class.java.simpleName}")
            null
        }
    }

    // commit, а не apply: refresh-токен одноразовый. Если приложение закроется до записи на диск,
    // при следующем запуске останется старый токен, сервер примет его за украденный и завершит все сеансы.
    // Не KTX `edit {}`: нужен результат commit — записалось ли.
    @SuppressLint("ApplySharedPref", "UseKtx")
    override fun save(session: StoredSession) {
        memory = session
        val written =
            try {
                prefs.edit().putString(KEY_SESSION, encrypt(session.encode())).commit()
            } catch (e: Exception) {
                Log.w(TAG, "Cannot save session: ${e::class.java.simpleName}")
                false
            }
        if (written) return
        // На диске остался прежний, уже использованный refresh-токен — по той же причине его нужно удалить.
        // Тогда после перезапуска приложения достаточно войти снова, а остальные сеансы пользователя не пострадают.
        try {
            prefs.edit().remove(KEY_SESSION).commit()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot remove stale session: ${e::class.java.simpleName}")
        }
    }

    override fun clear() {
        memory = null
        prefs.edit(commit = true) { remove(KEY_SESSION) }
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        // Случайный вектор инициализации — перед зашифрованными данными.
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, IV_LENGTH)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_LENGTH_BITS, iv)) }
        return cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec =
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private companion object {
        const val TAG = "SessionStorage"
        const val PREFS_NAME = "session"
        const val KEY_SESSION = "session"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ryadom-session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
        const val KEY_SIZE_BITS = 256
    }
}
