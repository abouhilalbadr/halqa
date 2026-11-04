package com.saiesh.tele.domain.repository

import com.saiesh.tele.domain.model.AllVideosCursor
import com.saiesh.tele.domain.model.MediaItem
import com.saiesh.tele.domain.model.VideoChatItem

interface TeleRepository {
    fun getCachedSavedMessagesChatId(): Long?

    fun loadChatsWithVideos(
        limit: Int,
        selectedChatId: Long?,
        onResult: (List<VideoChatItem>, String?) -> Unit,
    )

    fun loadLatestMediaPaged(
        limit: Int,
        fromMessageId: Long?,
        onResult: (List<MediaItem>, Long, String?) -> Unit,
    )

    fun loadChatMediaPaged(
        chatId: Long,
        limit: Int,
        fromMessageId: Long?,
        onResult: (List<MediaItem>, Long, String?) -> Unit,
    )

    fun getMessage(
        chatId: Long,
        messageId: Long,
        onResult: (MediaItem?, String?) -> Unit,
    )

    fun deleteMessage(
        chatId: Long,
        messageId: Long,
        onResult: (String?) -> Unit,
    )

    fun fetchThumbnailPath(
        fileId: Int,
        onResult: (String?) -> Unit,
    )

    /** Videos from every chat in the main list whose text contains [query] (empty: all), newest first. */
    fun loadAllVideosPaged(
        limit: Int,
        cursor: AllVideosCursor,
        query: String,
        onResult: (List<MediaItem>, AllVideosCursor, String?) -> Unit,
    )

    fun getChatTitle(
        chatId: Long,
        onResult: (String?) -> Unit,
    )

    fun shutdown()
}
