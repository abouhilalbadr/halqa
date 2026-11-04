package com.saiesh.tele.domain.model

import java.io.Serializable

/**
 * One video in the player's queue. A Telegram video is streamed by its TDLib [telegramFileId] when it
 * comes up; anything else plays from [url]. [key] identifies the video for its saved position.
 */
data class PlaybackItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val episodeLabel: String,
    val telegramFileId: Int? = null,
    val url: String? = null,
) : Serializable
