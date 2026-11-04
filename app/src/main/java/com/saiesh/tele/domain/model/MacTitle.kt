package com.saiesh.tele.domain.model

/** One video file in a folder on the Mac. [path] is relative to the shared folder. */
data class MacVideo(
    val path: String,
    val fileName: String,
    val number: Int?,
    val sizeBytes: Long,
)

/** Details from an optional `info.json` in the title's folder; every field may be missing. */
data class MacInfo(
    val title: String?,
    val year: Int?,
    val genres: List<String>,
    val network: String?,
    val country: String?,
    val director: String?,
    val cast: List<String>,
    val summary: String?,
    val poster: String?,
)

/**
 * A folder on the Mac: a series when it holds several videos, a movie when it holds one. [posterPath]
 * is the folder's cover on the Mac; [posterFile] is the copy downloaded to the TV.
 */
data class MacTitle(
    val folder: String,
    val name: String,
    val videos: List<MacVideo>,
    val posterPath: String?,
    val posterFile: String? = null,
    /** Size and date of the poster on the Mac, so a replaced image is downloaded again. */
    val posterVersion: String = "",
    val modifiedAt: Long,
    val info: MacInfo? = null,
) {
    val key: String get() = "mac:$folder"
    val isMovie: Boolean get() = videos.size == 1
}
