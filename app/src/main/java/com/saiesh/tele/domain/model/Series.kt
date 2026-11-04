package com.saiesh.tele.domain.model

/** One upload of an episode; channels often post the same episode once per quality. */
data class EpisodeVariant(
    val quality: String?,
    val item: MediaItem,
)

/** An episode of a series, or a single video when no episode number was found. */
data class Episode(
    val key: String,
    val number: Int?,
    val title: String,
    val variants: List<EpisodeVariant>,
) {
    val latestDate: Int get() = variants.maxOf { it.item.date }
    val durationSeconds: Int get() = variants.first().item.durationSeconds
    val thumbnailItem: MediaItem get() = variants.firstOrNull { !it.item.thumbnailPath.isNullOrBlank() }?.item ?: variants.first().item
}

/** Videos grouped under the series name found in their captions, newest episode first. */
data class Series(
    val key: String,
    val name: String,
    val episodes: List<Episode>,
) {
    val latestDate: Int get() = episodes.maxOf { it.latestDate }
    val latestEpisode: Episode get() = episodes.maxBy { it.latestDate }
}
