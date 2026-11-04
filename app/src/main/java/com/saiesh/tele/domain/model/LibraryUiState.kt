package com.saiesh.tele.domain.model

data class LibraryUiState(
    val items: List<MediaItem> = emptyList(),
    val chatTitles: Map<Long, String> = emptyMap(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val cursor: AllVideosCursor = AllVideosCursor.START,
    /** Name of the series whose episodes are being searched for, if any. */
    val loadingSeries: String? = null,
)
