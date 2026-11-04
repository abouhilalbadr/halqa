package com.saiesh.tele.presentation.player

import android.content.Context

/** Where each video was stopped, so playback resumes there; a video played to the end counts as watched. */
class PlaybackPositions(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The position to resume [key] from, or 0 when it was barely started or already finished. */
    fun resumePositionMs(key: String): Long {
        val position = prefs.getLong(positionKey(key), 0)
        val duration = prefs.getLong(durationKey(key), 0)
        if (position < MIN_RESUME_MS) return 0
        if (duration > 0 && duration - position < END_MARGIN_MS) return 0
        return position
    }

    fun isWatched(key: String): Boolean {
        val duration = prefs.getLong(durationKey(key), 0)
        return duration > 0 && duration - prefs.getLong(positionKey(key), 0) < END_MARGIN_MS
    }

    /** Whether [key] was watched past the first seconds or to the end. */
    fun hasProgress(key: String): Boolean = prefs.getLong(positionKey(key), 0) >= MIN_RESUME_MS || isWatched(key)

    /** How far into [key] playback stopped, from 0 to 1, or null when its length isn't known yet. */
    fun fraction(key: String): Float? {
        val duration = prefs.getLong(durationKey(key), 0)
        if (duration <= 0) return null
        return (prefs.getLong(positionKey(key), 0).toFloat() / duration).coerceIn(0f, 1f)
    }

    fun positionMs(key: String): Long = prefs.getLong(positionKey(key), 0)

    fun durationMs(key: String): Long = prefs.getLong(durationKey(key), 0)

    fun save(
        key: String,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (durationMs <= 0) return
        prefs
            .edit()
            .putLong(positionKey(key), positionMs.coerceIn(0, durationMs))
            .putLong(durationKey(key), durationMs)
            .apply()
    }

    private fun positionKey(key: String) = "pos:$key"

    private fun durationKey(key: String) = "dur:$key"

    private companion object {
        const val PREFS_NAME = "playback_positions"
        const val MIN_RESUME_MS = 30_000L
        const val END_MARGIN_MS = 60_000L
    }
}
