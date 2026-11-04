package com.saiesh.tele.presentation.library

import android.graphics.BitmapFactory
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import com.saiesh.tele.R
import com.saiesh.tele.domain.model.MediaItem
import com.saiesh.tele.presentation.media.ImageCache
import java.io.File

object Thumbnails {
    /** Shows the downloaded thumbnail, or Telegram's tiny inline preview until it arrives. */
    fun load(
        target: ImageView,
        item: MediaItem,
    ) {
        val mini = miniThumbnail(item)
        val path = item.thumbnailPath
        if (path.isNullOrBlank()) {
            Glide.with(target).clear(target)
            if (mini != null) target.setImageBitmap(mini) else target.setImageResource(R.drawable.no_thumbnail)
            return
        }
        Glide
            .with(target)
            .load(File(path))
            .signature(ObjectKey(item.messageId.toString()))
            .diskCacheStrategy(DiskCacheStrategy.DATA)
            .let { request ->
                if (mini != null) request.thumbnail(Glide.with(target).load(mini)) else request.placeholder(R.drawable.no_thumbnail)
            }.into(target)
    }

    fun clear(target: ImageView) {
        Glide.with(target).clear(target)
    }

    private fun miniThumbnail(item: MediaItem) =
        item.miniThumbnailBytes?.let { bytes ->
            ImageCache.getMini(item.messageId)
                ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { ImageCache.putMini(item.messageId, it) }
        }
}
