package com.saiesh.tele.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saiesh.tele.core.cache.TeleCache
import com.saiesh.tele.core.tdlib.TdLibClient
import com.saiesh.tele.data.mapper.MediaMapper
import com.saiesh.tele.data.repository.TeleRepositoryImpl
import com.saiesh.tele.domain.model.AllVideosCursor
import com.saiesh.tele.domain.model.LibraryUiState
import com.saiesh.tele.domain.model.MediaItem
import com.saiesh.tele.domain.model.MediaType
import com.saiesh.tele.domain.model.Series
import com.saiesh.tele.domain.repository.TeleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.Collections

/** Every video in the main chat list, newest first, and the same videos grouped into series and episodes. */
class LibraryViewModel(
    private val repository: TeleRepository = TeleRepositoryImpl(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState
    private var isInitialized = false
    private val requestedTitles = Collections.synchronizedSet(mutableSetOf<Long>())
    private val searchedSeries = Collections.synchronizedSet(mutableSetOf<String>())

    val series: StateFlow<List<Series>> =
        _uiState
            .map { SeriesGrouper.group(it.items, it.chatTitles) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val newMessageHandler: (TdApi.UpdateNewMessage) -> Unit = { update ->
        val item = MediaMapper.mapMessage(update.message)?.takeIf { it.type == MediaType.Video }
        if (item != null) {
            _uiState.update { current ->
                if (current.items.any { it.isSameMessage(item) }) {
                    current
                } else {
                    current.copy(items = listOf(item) + current.items)
                }
            }
            fetchThumbnails(listOf(item))
            fetchChatTitles(listOf(item))
        }
    }

    private val deleteMessageHandler: (TdApi.UpdateDeleteMessages) -> Unit = { update ->
        if (update.isPermanent) {
            _uiState.update { current ->
                current.copy(
                    items =
                        current.items.filterNot {
                            it.chatId == update.chatId && update.messageIds.contains(it.messageId)
                        },
                )
            }
        }
    }

    init {
        TdLibClient.addNewMessageHandler(newMessageHandler)
        TdLibClient.addDeleteMessageHandler(deleteMessageHandler)
    }

    override fun onCleared() {
        TdLibClient.removeNewMessageHandler(newMessageHandler)
        TdLibClient.removeDeleteMessageHandler(deleteMessageHandler)
        repository.shutdown()
        super.onCleared()
    }

    fun initialize() {
        if (isInitialized) return
        isInitialized = true
        TeleCache.loadMediaItems(CACHE_KEY)?.let { cached ->
            _uiState.update { it.copy(items = cached, chatTitles = cachedTitles(cached)) }
        }
        refresh()
    }

    fun refresh() = loadFirstPage(attempt = 0)

    private fun loadFirstPage(attempt: Int) {
        _uiState.update { it.copy(isLoading = true) }
        repository.loadAllVideosPaged(PAGE_SIZE, AllVideosCursor.START, "") { items, cursor, error ->
            // Right after signing in, Telegram can answer before the account's chats have synced,
            // so an empty first page is retried a few times before the home screen shows as empty.
            if (items.isEmpty() && _uiState.value.items.isEmpty() && attempt < EMPTY_RETRIES) {
                viewModelScope.launch {
                    delay(EMPTY_RETRY_DELAY_MS)
                    loadFirstPage(attempt + 1)
                }
                return@loadAllVideosPaged
            }
            _uiState.update { current ->
                // Keep what is on screen when the first page fails, e.g. while offline.
                if (error != null && items.isEmpty()) {
                    current.copy(isLoading = false)
                } else {
                    // Cached videos stay until the fresh pages arrive, so the series rows don't shrink and refill.
                    val merged = (items + current.items).distinctBy { it.chatId to it.messageId }.sortedByDescending { it.date }
                    current.copy(items = merged, isLoading = false, cursor = cursor)
                }
            }
            fetchThumbnails(items)
            fetchChatTitles(items)
            if (error == null) fillInitialPages()
        }
    }

    /** Series need more than one page of videos to show up, so the first screen loads several. */
    private fun fillInitialPages() {
        val state = _uiState.value
        if (state.items.size < INITIAL_VIDEO_COUNT && state.cursor.hasMore) {
            loadMore { fillInitialPages() }
        } else {
            TeleCache.saveMediaItems(state.items, CACHE_KEY)
        }
    }

    /** Loads the next page once the focused card is within a few of the end of a row of [total]. */
    fun onPositionFocused(
        position: Int,
        total: Int,
    ) {
        if (position >= total - LOAD_MORE_THRESHOLD) loadMore()
    }

    private fun loadMore(then: () -> Unit = {}) {
        val state = _uiState.value
        if (state.isLoadingMore || !state.cursor.hasMore) return
        _uiState.update { it.copy(isLoadingMore = true) }
        repository.loadAllVideosPaged(PAGE_SIZE, state.cursor, "") { items, cursor, _ ->
            mergeItems(items)
            _uiState.update { it.copy(isLoadingMore = false, cursor = cursor) }
            then()
        }
    }

    /**
     * Searches every chat for [series]'s name, so its episode list is complete even when older
     * episodes are far down the newest-first list. Runs once per series while the app is open.
     */
    fun loadSeries(series: Series) {
        if (!searchedSeries.add(series.key)) return
        _uiState.update { it.copy(loadingSeries = series.key) }

        fun page(
            cursor: AllVideosCursor,
            fetched: Int,
        ) {
            repository.loadAllVideosPaged(PAGE_SIZE, cursor, series.name) { items, next, _ ->
                mergeItems(items)
                if (items.isNotEmpty() && next.hasMore && fetched + items.size < SERIES_SEARCH_LIMIT) {
                    page(next, fetched + items.size)
                } else {
                    _uiState.update { it.copy(loadingSeries = null) }
                }
            }
        }
        page(AllVideosCursor.START, 0)
    }

    private fun mergeItems(items: List<MediaItem>) {
        if (items.isEmpty()) return
        _uiState.update { current ->
            current.copy(
                items =
                    (current.items + items)
                        .distinctBy { it.chatId to it.messageId }
                        .sortedByDescending { it.date },
            )
        }
        fetchThumbnails(items)
        fetchChatTitles(items)
    }

    fun onItemFocused(item: MediaItem) {
        if (item.thumbnailPath.isNullOrBlank()) fetchThumbnails(listOf(item))
    }

    fun deleteMediaItem(
        item: MediaItem,
        onResult: (String?) -> Unit,
    ) {
        repository.deleteMessage(item.chatId, item.messageId) { error ->
            if (error == null) {
                _uiState.update { current -> current.copy(items = current.items.filterNot { it.isSameMessage(item) }) }
            }
            onResult(error)
        }
    }

    private fun fetchThumbnails(items: List<MediaItem>) {
        items
            .filter { it.thumbnailFileId != null && it.thumbnailPath.isNullOrBlank() }
            .forEach { item ->
                repository.fetchThumbnailPath(item.thumbnailFileId!!) { path ->
                    if (!path.isNullOrBlank()) {
                        _uiState.update { current ->
                            current.copy(
                                items = current.items.map { if (it.isSameMessage(item)) it.copy(thumbnailPath = path) else it },
                            )
                        }
                    }
                }
            }
    }

    private fun fetchChatTitles(items: List<MediaItem>) {
        items
            .map { it.chatId }
            .distinct()
            .filter { it !in _uiState.value.chatTitles && requestedTitles.add(it) }
            .forEach { chatId ->
                repository.getChatTitle(chatId) { title ->
                    if (!title.isNullOrBlank()) {
                        TeleCache.saveChatTitle(chatId, title)
                        _uiState.update { it.copy(chatTitles = it.chatTitles + (chatId to title)) }
                    } else {
                        requestedTitles.remove(chatId)
                    }
                }
            }
    }

    private fun cachedTitles(items: List<MediaItem>): Map<Long, String> =
        items
            .map { it.chatId }
            .distinct()
            .mapNotNull { chatId -> TeleCache.loadChatTitle(chatId)?.let { chatId to it } }
            .toMap()

    private fun MediaItem.isSameMessage(other: MediaItem): Boolean = chatId == other.chatId && messageId == other.messageId

    companion object {
        private const val CACHE_KEY = "all_videos"
        private const val PAGE_SIZE = 50
        private const val LOAD_MORE_THRESHOLD = 4
        private const val INITIAL_VIDEO_COUNT = 300
        private const val SERIES_SEARCH_LIMIT = 600
        private const val EMPTY_RETRIES = 4
        private const val EMPTY_RETRY_DELAY_MS = 3_000L
    }
}
