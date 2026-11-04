package com.saiesh.tele.presentation.library

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.saiesh.tele.core.stream.LocalStreamServer
import com.saiesh.tele.core.stream.StreamKeepAliveService
import com.saiesh.tele.domain.model.MediaItem

/** Streams a Telegram video through [LocalStreamServer] into EZV Player, or any video player if EZV is missing. */
object VideoPlayer {
    private const val PLAYER_PACKAGE = "com.player.easy"

    fun play(
        context: Context,
        item: MediaItem,
        title: String,
    ) {
        val fileId = item.fileId ?: return
        val url = LocalStreamServer.urlFor(fileId)
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(url), "video/*")
                .putExtra(Intent.EXTRA_TITLE, title)
                // EZV Player closes an http link unless it is flagged as a network stream and
                // carries the link as "path" plus a title as "anime_title". A "title" extra would
                // make EZV expect an episode quality list, so it is left out.
                .putExtra("from_network", true)
                .putExtra("anime_title", title)
                .putExtra("path", url)
        if (isInstalled(context, PLAYER_PACKAGE)) intent.setPackage(PLAYER_PACKAGE)
        try {
            StreamKeepAliveService.start(context)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("Tele", "Failed to launch video player", e)
            Toast.makeText(context, "No video player found", Toast.LENGTH_LONG).show()
        }
    }

    private fun isInstalled(
        context: Context,
        packageName: String,
    ): Boolean =
        try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
}
