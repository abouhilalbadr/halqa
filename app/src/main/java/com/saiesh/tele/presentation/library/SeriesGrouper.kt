package com.saiesh.tele.presentation.library

import com.saiesh.tele.domain.model.Episode
import com.saiesh.tele.domain.model.EpisodeVariant
import com.saiesh.tele.domain.model.MediaItem
import com.saiesh.tele.domain.model.Series

/** Groups loose videos into series and episodes using the names and numbers in their captions. */
object SeriesGrouper {
    fun group(
        items: List<MediaItem>,
        chatTitles: Map<Long, String>,
    ): List<Series> =
        items
            .filterNot { isExtra(it) }
            .groupBy { seriesKey(it, chatTitles) }
            .map { (key, videos) ->
                val newest = videos.maxBy { it.date }
                Series(
                    key = key,
                    name = displayName(newest, chatTitles),
                    episodes =
                        videos
                            .groupBy { episodeKey(it) }
                            .map { (episodeKey, uploads) -> toEpisode(episodeKey, uploads) }
                            .sortedWith(compareByDescending<Episode> { it.number ?: Int.MIN_VALUE }.thenByDescending { it.latestDate }),
                )
            }.sortedByDescending { it.latestDate }

    fun seriesKey(
        item: MediaItem,
        chatTitles: Map<Long, String>,
    ): String = normalize(displayName(item, chatTitles))

    private fun displayName(
        item: MediaItem,
        chatTitles: Map<Long, String>,
    ): String =
        VideoLabels
            .seriesName(item.title)
            // A video posted without a caption (its title is only "1080p") takes its channel's name.
            .ifEmpty { chatTitles[item.chatId]?.let(VideoLabels::seriesName).orEmpty() }
            .ifEmpty { item.title }

    private fun episodeKey(item: MediaItem): String {
        VideoLabels.episode(item.title)?.let { return it.toString() }
        val name = normalize(VideoLabels.seriesName(item.title))
        // Uploads without a caption: copies of one video in several qualities share its length to the minute.
        return if (name.isNotEmpty()) "title:$name" else "length:${item.durationSeconds / 60}"
    }

    private fun toEpisode(
        key: String,
        uploads: List<MediaItem>,
    ): Episode {
        // A clip far shorter than the full episode (a recap or scene posted with the same caption) is not a quality of it.
        val fullLength = uploads.maxOf { it.durationSeconds }
        // One upload per quality: the newest, so a re-post replaces an older copy.
        val variants =
            uploads
                .filter { it.durationSeconds >= fullLength * MIN_LENGTH_SHARE }
                .sortedByDescending { it.date }
                .distinctBy { VideoLabels.quality(it.title) }
                .map { EpisodeVariant(VideoLabels.quality(it.title), it) }
                .sortedByDescending { VideoLabels.qualityRank(it.quality) }
        val first = variants.first().item
        return Episode(
            key = key,
            number = VideoLabels.episode(first.title),
            title = VideoLabels.seriesName(first.title),
            variants = variants,
        )
    }

    private const val MIN_LENGTH_SHARE = 0.5
    private const val MIN_LOOSE_VIDEO_SECONDS = 20 * 60

    /**
     * Trailers, countdowns and behind-the-scenes clips: anything named as a promo, and any short video
     * without an episode number (a film without one is long enough to stay).
     */
    private fun isExtra(item: MediaItem): Boolean =
        VideoLabels.isPromo(item.title) ||
            (VideoLabels.episode(item.title) == null && item.durationSeconds in 1 until MIN_LOOSE_VIDEO_SECONDS)

    /** Lower case with Arabic spelling variants folded (أ/إ/آ → ا, ة → ه, ى → ي), so "الأم" and "الام" match. */
    private fun normalize(name: String): String =
        name
            .lowercase()
            .replace(Regex("[أإآ]"), "ا")
            .replace('ة', 'ه')
            .replace('ى', 'ي')
            .replace(Regex("""\s+"""), " ")
            .trim()
}
