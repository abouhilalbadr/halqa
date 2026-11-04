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
import com.saiesh.tele.presentation.text.ArabicText
import com.saiesh.tele.domain.model.MediaItem

/** One card on a home-screen row: a thumbnail, an optional corner badge and a name underneath. */
data class ShelfCard(
    val key: String,
    val thumbnail: MediaItem,
    val label: String,
    val badge: String?,
)

/** A row of [ShelfCard]s at [widthDp]×[heightDp]; the focused card grows and gets a white outline. */
class ShelfAdapter(
    private val widthDp: Int,
    private val heightDp: Int,
    private val onClick: (ShelfCard) -> Unit,
    private val onFocused: (Int) -> Unit,
) : ListAdapter<ShelfCard, ShelfAdapter.ViewHolder>(DIFF) {
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).key.hashCode().toLong()

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_shelf_card, parent, false)
        val metrics = parent.resources.displayMetrics
        val frame = view.findViewById<View>(R.id.shelf_card_frame)
        frame.layoutParams =
            frame.layoutParams.apply {
                width = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, widthDp.toFloat(), metrics).toInt()
                height = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, heightDp.toFloat(), metrics).toInt()
            }
        view.layoutParams = view.layoutParams.apply { width = frame.layoutParams.width }
        frame.outlineProvider = ViewOutlineProvider.BACKGROUND
        frame.clipToOutline = true
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
        val image: ImageView = view.findViewById(R.id.shelf_card_image)
        private val frame: View = view.findViewById(R.id.shelf_card_frame)
        private val badge: TextView = view.findViewById(R.id.shelf_card_badge)
        private val label: TextView = view.findViewById(R.id.shelf_card_label)

        fun bind(card: ShelfCard) {
            itemView.setOnClickListener { onClick(card) }
            itemView.setOnFocusChangeListener { _, hasFocus ->
                val scale = if (hasFocus) FOCUSED_SCALE else 1f
                frame.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
                if (hasFocus) onFocused(bindingAdapterPosition)
            }
            badge.text = card.badge
            badge.visibility = if (card.badge.isNullOrEmpty()) View.GONE else View.VISIBLE
            ArabicText.setText(label, card.label)
            Thumbnails.load(image, card.thumbnail)
        }
    }

    companion object {
        private const val FOCUSED_SCALE = 1.06f

        private val DIFF =
            object : DiffUtil.ItemCallback<ShelfCard>() {
                override fun areItemsTheSame(
                    oldItem: ShelfCard,
                    newItem: ShelfCard,
                ): Boolean = oldItem.key == newItem.key

                override fun areContentsTheSame(
                    oldItem: ShelfCard,
                    newItem: ShelfCard,
                ): Boolean =
                    oldItem.label == newItem.label &&
                        oldItem.badge == newItem.badge &&
                        oldItem.thumbnail.messageId == newItem.thumbnail.messageId &&
                        oldItem.thumbnail.thumbnailPath == newItem.thumbnail.thumbnailPath
            }
    }
}
