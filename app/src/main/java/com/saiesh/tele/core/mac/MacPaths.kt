package com.saiesh.tele.core.mac

import android.net.Uri

/** Paths inside the shared folder use backslashes, as SMB does; the player gets them as `smb://mac/<path>`. */
object MacPaths {
    const val SCHEME = "smb"
    private const val AUTHORITY = "mac"

    fun join(
        folder: String,
        name: String,
    ): String = if (folder.isEmpty()) name else "$folder\\$name"

    fun uriOf(path: String): Uri =
        Uri
            .Builder()
            .scheme(SCHEME)
            .authority(AUTHORITY)
            .apply { path.split('\\').forEach { appendPath(it) } }
            .build()

    fun pathOf(uri: Uri): String = uri.pathSegments.joinToString("\\")

    /** The key a video's watch position is saved under; stays the same when the Mac's address changes. */
    fun playbackKey(path: String): String = "mac:$path"
}
