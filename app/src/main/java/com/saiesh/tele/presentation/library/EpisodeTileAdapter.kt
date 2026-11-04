package com.saiesh.tele.presentation.library

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.saiesh.tele.R
import com.saiesh.tele.domain.model.Episode
import com.saiesh.tele.presentation.text.ArabicText
import java.text.DateFormat
import java.util.Date

/** One tile on a series page: the episode, and how much of it was watched. */
data class EpisodeTile(
    val episode: Episode,
    val watched: Boolean,
    val progress: Float?,
)

/** Episode tiles for a series: the video's preview, a large episode number (or its name), its length and best quality. */
class EpisodeTileAdapter(
    private val tileWidthPx: Int,
    private val onClick: (Episode) -> Unit,
    private val onLongPress: (Episode) -> Unit,
) : RecyclerView.Adapter<EpisodeTileAdapter.ViewHolder>() {
    private var tiles: List<EpisodeTile> = emptyList()

    /** "Watched" in the page's language. */
    var watchedLabel: String = ""

    init {
        setHasStableIds(true)
    }

    /**
     * The leanback grid places items inserted in front of existing ones diagonally, so any change to which
     * episodes are listed rebuilds the grid; the same episodes with new watched marks are updated in place.
     */
    fun submit(newTiles: List<EpisodeTile>) {
        val sameEpisodes = newTiles.map { it.episode.key } == tiles.map { it.episode.key }
        tiles = newTiles
        if (sameEpisodes) notifyItemRangeChanged(0, tiles.size) else notifyDataSetChanged()
    }

    override fun getItemCount(): Int = tiles.size

    override fun getItemId(position: Int): Long = tiles[position].episode.key.hashCode().toLong()

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode_tile, parent, false)
        view.layoutParams =
            view.layoutParams.apply {
                width = tileWidthPx
                height = tileWidthPx * 9 / 16
            }
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
        return ViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) = holder.bind(tiles[position])

    override fun onViewRecycled(holder: ViewHolder) {
        Thumbnails.clear(holder.image)
    }

    inner class ViewHolder(
        view: View,
    ) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.episode_image)
        private val number: TextView = view.findViewById(R.id.episode_number)
        private val meta: TextView = view.findViewById(R.id.episode_meta)
        private val watched: TextView = view.findViewById(R.id.episode_watched)
        private val progress: View = view.findViewById(R.id.episode_progress)

        fun bind(tile: EpisodeTile) {
            val episode = tile.episode
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
                ArabicText.setText(number, episode.number.toString())
                number.setTextSize(TypedValue.COMPLEX_UNIT_SP, NUMBER_SP)
            } else {
                // Without a caption there is no name to show, so the posting date tells such videos apart.
                ArabicText.setText(number, episode.title.ifEmpty { postedOn(episode.latestDate) })
                number.setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
            }
            // Every tile shows the same two facts so they line up; the full quality list is in the quality sheet.
            meta.text =
                listOfNotNull(
                    VideoLabels.duration(episode.durationSeconds),
                    episode.variants.firstNotNullOfOrNull { it.quality },
                ).joinToString(" · ")

            ArabicText.setText(watched, watchedLabel)
            watched.visibility = if (tile.watched) View.VISIBLE else View.GONE
            val fraction = tile.progress
            if (!tile.watched && fraction != null && fraction > 0f) {
                progress.visibility = View.VISIBLE
                progress.layoutParams = progress.layoutParams.apply { width = (tileWidthPx * fraction).toInt() }
            } else {
                progress.visibility = View.GONE
            }
            Thumbnails.load(image, episode.thumbnailItem)
        }
    }

    private fun postedOn(unixSeconds: Int): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(unixSeconds * 1000L))

    private companion object {
        const val FOCUSED_SCALE = 1.06f
        const val NUMBER_SP = 28f
        const val TITLE_SP = 16f
    }
}
