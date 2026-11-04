package com.saiesh.tele.domain.model

/** Where the next page of the all-videos search starts; a null offset means that search is exhausted. */
data class AllVideosCursor(
    val videoOffset: String?,
    val documentOffset: String?,
) {
    val hasMore: Boolean get() = videoOffset != null || documentOffset != null

    companion object {
        val START = AllVideosCursor("", "")
    }
}
