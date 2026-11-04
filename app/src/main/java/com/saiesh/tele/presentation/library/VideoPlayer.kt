package com.saiesh.tele.presentation.library

import android.content.Context
import com.saiesh.tele.R
import com.saiesh.tele.domain.model.Episode
import com.saiesh.tele.domain.model.EpisodeVariant
import com.saiesh.tele.domain.model.MediaItem
import com.saiesh.tele.domain.model.PlaybackItem
import com.saiesh.tele.domain.model.Series
import com.saiesh.tele.presentation.player.PlayerActivity

/** Opens Telegram videos in Halqa's built-in player. */
object VideoPlayer {
    /** Plays one video on its own. */
    fun play(
        context: Context,
        item: MediaItem,
        title: String,
    ) {
        PlayerActivity.start(context, listOf(playbackItem(item, title, subtitle = "", label = "1")), 0)
    }

    /**
     * Plays [chosen] and queues the rest of [series] in episode order, so "Next episode" and the
     * episode picker work. Other episodes play in the same quality when they have it, else the best.
     */
    fun playEpisode(
        context: Context,
        series: Series,
        episode: Episode,
        chosen: EpisodeVariant,
    ) {
        val ordered = inOrder(series)
        val queue =
            ordered.mapIndexed { i, ep ->
                val variant = if (ep.key == episode.key) chosen else ep.variants.firstOrNull { it.quality == chosen.quality } ?: ep.variants.first()
                val name = ep.number?.let { context.getString(R.string.player_episode, it) } ?: ep.title.ifEmpty { null }
                playbackItem(
                    variant.item,
                    title = series.name,
                    subtitle = listOfNotNull(name, variant.quality).joinToString(" · "),
                    label = ep.number?.toString() ?: (i + 1).toString(),
                )
            }
        PlayerActivity.start(context, queue, ordered.indexOfFirst { it.key == episode.key }.coerceAtLeast(0))
    }

    /** Episodes by number, then the ones without a number by posting date. */
    fun inOrder(series: Series): List<Episode> =
        series.episodes.sortedWith(compareBy({ it.number == null }, { it.number }, { it.latestDate }))

    /** The key the player saves this video's position under. */
    fun playbackKey(item: MediaItem): String = "tg:${item.chatId}:${item.messageId}"

    private fun playbackItem(
        item: MediaItem,
        title: String,
        subtitle: String,
        label: String,
    ) = PlaybackItem(
        key = playbackKey(item),
        title = title,
        subtitle = subtitle,
        episodeLabel = label,
        telegramFileId = item.fileId,
    )
}
