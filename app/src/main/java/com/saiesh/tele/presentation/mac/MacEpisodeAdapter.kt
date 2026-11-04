package com.saiesh.tele.presentation.mac

import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.saiesh.tele.R
import com.saiesh.tele.presentation.text.ArabicText
import java.io.File

/** One tile on a Mac title's page: a still from the video, the episode number, its length and how much was watched. */
data class MacEpisodeTile(
    val index: Int,
    val number: Int,
    val meta: String,
    val watched: Boolean,
    val progress: Float?,
    val stillFile: String?,
)

class MacEpisodeAdapter(
    private val tileWidthPx: Int,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<MacEpisodeAdapter.ViewHolder>() {
    private var tiles: List<MacEpisodeTile> = emptyList()

    /** "Watched" in the page's language, and whether the page uses the Arabic font. */
    var watchedLabel: String = ""
    var arabic: Boolean = false

    fun submit(newTiles: List<MacEpisodeTile>) {
        val sameCount = newTiles.size == tiles.size
        tiles = newTiles
        // Same tiles with new numbers keep focus where it is; a different count rebuilds the grid.
        if (sameCount) notifyItemRangeChanged(0, tiles.size) else notifyDataSetChanged()
    }

    override fun getItemCount(): Int = tiles.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_mac_episode, parent, false)
        view.layoutParams = view.layoutParams.apply { width = tileWidthPx }
        return ViewHolder(view)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        Glide.with(holder.image).clear(holder.image)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) = holder.bind(tiles[position])

    inner class ViewHolder(
        view: View,
    ) : RecyclerView.ViewHolder(view) {
        private val number: TextView = view.findViewById(R.id.mac_episode_number)
        private val meta: TextView = view.findViewById(R.id.mac_episode_meta)
        private val watched: TextView = view.findViewById(R.id.mac_episode_watched)
        private val text: View = view.findViewById(R.id.mac_episode_text)
        private val progress: View = view.findViewById(R.id.mac_episode_progress)
        val image: ImageView = view.findViewById(R.id.mac_episode_image)
        private val scrim: View = view.findViewById(R.id.mac_episode_scrim)

        init {
            // Round the still's corners to the card's shape.
            view.outlineProvider = ViewOutlineProvider.BACKGROUND
            view.clipToOutline = true
        }

        fun bind(tile: MacEpisodeTile) {
            itemView.setOnClickListener { onClick(tile.index) }
            number.text = tile.number.toString()
            meta.text = tile.meta
            watched.text = watchedLabel
            listOf(number, meta, watched).forEach { ArabicText.style(it, arabic) }
            if (tile.stillFile != null) {
                Glide.with(image).load(File(tile.stillFile)).into(image)
                scrim.visibility = View.VISIBLE
            } else {
                Glide.with(image).clear(image)
                image.setImageDrawable(null)
                scrim.visibility = View.GONE
            }
            image.alpha = if (tile.watched) WATCHED_ALPHA else 1f
            meta.visibility = if (tile.meta.isEmpty()) View.GONE else View.VISIBLE
            watched.visibility = if (tile.watched) View.VISIBLE else View.GONE
            text.alpha = if (tile.watched) WATCHED_ALPHA else 1f
            val fraction = tile.progress
            if (fraction != null && fraction > 0f && !tile.watched) {
                progress.visibility = View.VISIBLE
                progress.layoutParams = progress.layoutParams.apply { width = (tileWidthPx * fraction).toInt() }
            } else {
                progress.visibility = View.GONE
            }
        }
    }

    private companion object {
        const val WATCHED_ALPHA = 0.55f
    }
}
