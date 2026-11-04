package com.saiesh.tele.core.setup

import android.content.Context
import android.content.SharedPreferences

/** What the user is setting up: videos from Telegram, from a computer, or both. */
enum class SetupPath { Telegram, Computer, Both }

/**
 * Lets the setup guide pick up where it was when the app restarts itself. A rejected Telegram key needs a
 * fresh TDLib, so the app restarts and the guide reopens on the key step with the error showing.
 */
object SetupResume {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** The path whose key was rejected, once; reading it clears it. */
    fun takeRejectedKeyPath(): SetupPath? {
        val name = prefs.getString(KEY_REJECTED_PATH, null) ?: return null
        prefs.edit().remove(KEY_REJECTED_PATH).commit()
        return SetupPath.entries.firstOrNull { it.name == name }
    }

    /** Written synchronously: the process is restarted right after. */
    fun rememberRejectedKey(path: SetupPath) {
        prefs.edit().putString(KEY_REJECTED_PATH, path.name).commit()
    }

    /** The guide's language picked on the welcome screen ("en" or "ar"), or null to follow the TV. */
    var language: String?
        get() = prefs.getString(KEY_LANGUAGE, null)
        set(value) {
            prefs.edit().putString(KEY_LANGUAGE, value).apply()
        }

    private const val PREFS = "halqa_setup"
    private const val KEY_REJECTED_PATH = "rejected_key_path"
    private const val KEY_LANGUAGE = "language"
}
