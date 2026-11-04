package com.saiesh.tele.presentation.library

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.saiesh.tele.R
import com.saiesh.tele.domain.model.Episode
import java.text.DateFormat
import java.util.Date

/** Episode tiles for a series: the video's preview, a large episode number (or its name), its length and best quality. */
class EpisodeTileAdapter(
    private val tileWidthPx: Int,
    private val onClick: (Episode) -> Unit,
    private val onLongPress: (Episode) -> Unit,
) : ListAdapter<Episode, EpisodeTileAdapter.ViewHolder>(DIFF) {
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).key.hashCode().toLong()

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode_tile, parent, false)
        view.layoutParams = view.layoutParams.apply { width = tileWidthPx }
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
        return ViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: ViewHolder) {
        Thumbnails.clear(holder.image)
    }

    inner class ViewHolder(
        view: View,
    ) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.episode_image)
        private val number: TextView = view.findViewById(R.id.episode_number)
        private val meta: TextView = view.findViewById(R.id.episode_meta)

        fun bind(episode: Episode) {
            itemView.setOnClickListener { onClick(episode) }
            itemView.setOnLongClickListener {
                onLongPress(episode)
                true
            }
            itemView.setOnFocusChangeListener { view, hasFocus ->
                val scale = if (hasFocus) FOCUSED_SCALE else 1f
                view.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            }
            if (episode.number != null) {
                number.text = episode.number.toString()
                number.setTextSize(TypedValue.COMPLEX_UNIT_SP, NUMBER_SP)
            } else {
                // Without a caption there is no name to show, so the posting date tells such videos apart.
                number.text = episode.title.ifEmpty { postedOn(episode.latestDate) }
                number.setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
            }
            // Every tile shows the same two facts so they line up; the full quality list is in the quality sheet.
            meta.text =
                listOfNotNull(
                    VideoLabels.duration(episode.durationSeconds),
                    episode.variants.firstNotNullOfOrNull { it.quality },
                ).joinToString(" · ")
            Thumbnails.load(image, episode.thumbnailItem)
        }
    }

    private fun postedOn(unixSeconds: Int): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(unixSeconds * 1000L))

    companion object {
        private const val FOCUSED_SCALE = 1.06f
        private const val NUMBER_SP = 34f
        private const val TITLE_SP = 16f

        private val DIFF =
            object : DiffUtil.ItemCallback<Episode>() {
                override fun areItemsTheSame(
                    oldItem: Episode,
                    newItem: Episode,
                ): Boolean = oldItem.key == newItem.key

                override fun areContentsTheSame(
                    oldItem: Episode,
                    newItem: Episode,
                ): Boolean = oldItem == newItem
            }
    }
}
