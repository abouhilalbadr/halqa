package com.saiesh.tele.presentation.mac

import android.content.res.Resources
import com.saiesh.tele.R
import com.saiesh.tele.presentation.text.ArabicText
import com.saiesh.tele.core.mac.MacPaths
import com.saiesh.tele.domain.model.MacTitle
import com.saiesh.tele.domain.model.PlaybackItem
import com.saiesh.tele.presentation.player.PlaybackPositions

/** Where to pick a title up: the video in progress, else the one after the last watched, else the first. */
data class MacResume(
    val index: Int,
    val fraction: Float?,
    val started: Boolean,
)

object MacProgress {
    /** An Arabic title gets Arabic labels and a right-to-left layout everywhere it appears. */
    fun isArabic(title: MacTitle): Boolean =
        ArabicText.isArabic(title.name) || ArabicText.isArabic(title.info?.summary)

    fun resume(
        title: MacTitle,
        positions: PlaybackPositions,
    ): MacResume {
        val keys = title.videos.map { MacPaths.playbackKey(it.path) }
        val last = keys.indexOfLast { positions.hasProgress(it) }
        if (last < 0) return MacResume(0, null, started = false)
        if (!positions.isWatched(keys[last])) return MacResume(last, positions.fraction(keys[last]), started = true)
        return if (last + 1 < keys.size) MacResume(last + 1, null, started = true) else MacResume(0, null, started = false)
    }

    /** "E3" style number for the video at [index]; files without a number count from 1. */
    fun episodeNumber(
        title: MacTitle,
        index: Int,
    ): Int = title.videos[index].number ?: (index + 1)

    /** Every video of [title] for the player, with episode names from [labels] (Arabic for an Arabic title). */
    fun queue(
        labels: Resources,
        title: MacTitle,
    ): List<PlaybackItem> =
        title.videos.mapIndexed { i, video ->
            val number = episodeNumber(title, i)
            PlaybackItem(
                key = MacPaths.playbackKey(video.path),
                title = title.name,
                subtitle = if (title.isMovie) "" else labels.getString(R.string.player_episode, number),
                episodeLabel = number.toString(),
                url = MacPaths.uriOf(video.path).toString(),
            )
        }
}
