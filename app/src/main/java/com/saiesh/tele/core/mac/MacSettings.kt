package com.saiesh.tele.core.mac

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How to reach the Mac's shared folder. [name] is the Mac's Bonjour name, used to find it again if its address changes. */
data class MacLogin(
    val name: String,
    val host: String,
    val user: String,
    val password: String,
    val share: String,
)

/**
 * The saved Mac login, kept in encrypted preferences, and whether the user chose to use Halqa without
 * Telegram. Some TVs have a broken keystore; the login then falls back to the app's private preferences.
 */
object MacSettings {
    private lateinit var secure: SharedPreferences
    private lateinit var plain: SharedPreferences

    private val _login = MutableStateFlow<MacLogin?>(null)
    val login: StateFlow<MacLogin?> = _login

    private val _telegramSkipped = MutableStateFlow(false)
    val telegramSkipped: StateFlow<Boolean> = _telegramSkipped

    fun init(context: Context) {
        plain = context.getSharedPreferences(PLAIN_PREFS, Context.MODE_PRIVATE)
        secure =
            try {
                EncryptedSharedPreferences.create(
                    SECURE_PREFS,
                    MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Encrypted preferences unavailable, using private preferences", e)
                context.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE)
            }
        _login.value = readLogin()
        _telegramSkipped.value = plain.getBoolean(KEY_SKIPPED, false)
    }

    fun save(login: MacLogin) {
        secure
            .edit()
            .putString(KEY_NAME, login.name)
            .putString(KEY_HOST, login.host)
            .putString(KEY_USER, login.user)
            .putString(KEY_PASSWORD, login.password)
            .putString(KEY_SHARE, login.share)
            .apply()
        _login.value = login
    }

    fun updateHost(host: String) {
        val current = _login.value ?: return
        save(current.copy(host = host))
    }

    fun clear() {
        secure.edit().clear().apply()
        _login.value = null
    }

    fun setTelegramSkipped(skipped: Boolean) {
        plain.edit().putBoolean(KEY_SKIPPED, skipped).apply()
        _telegramSkipped.value = skipped
    }

    private fun readLogin(): MacLogin? {
        val host = secure.getString(KEY_HOST, null) ?: return null
        return MacLogin(
            name = secure.getString(KEY_NAME, null).orEmpty(),
            host = host,
            user = secure.getString(KEY_USER, null).orEmpty(),
            password = secure.getString(KEY_PASSWORD, null).orEmpty(),
            share = secure.getString(KEY_SHARE, null).orEmpty(),
        )
    }

    private const val TAG = "MacSettings"
    private const val SECURE_PREFS = "mac_login_secure"
    private const val FALLBACK_PREFS = "mac_login"
    private const val PLAIN_PREFS = "halqa_settings"
    private const val KEY_NAME = "name"
    private const val KEY_HOST = "host"
    private const val KEY_USER = "user"
    private const val KEY_PASSWORD = "password"
    private const val KEY_SHARE = "share"
    private const val KEY_SKIPPED = "telegram_skipped"
}
