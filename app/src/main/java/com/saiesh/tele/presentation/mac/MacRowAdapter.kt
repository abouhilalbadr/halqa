package com.saiesh.tele.presentation.mac

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.saiesh.tele.R
import com.saiesh.tele.domain.model.MacTitle
import com.saiesh.tele.presentation.text.ArabicText
import java.io.File

/** A card on the "On your Mac" row: a title with its poster, or an action such as adding the Mac. */
sealed class MacCard {
    abstract val id: String

    data class Title(
        val title: MacTitle,
        val detail: String,
        val progress: Float?,
    ) : MacCard() {
        override val id: String get() = title.key
    }

    data class Action(
        val action: MacAction,
        val text: String,
        val label: String,
        val detail: String,
    ) : MacCard() {
        override val id: String get() = "action:$action"
    }
}

enum class MacAction(
    val icon: Int,
) {
    Add(R.drawable.ic_add),

    /** A placeholder while the Mac is read for the first time; it shows a spinner and does nothing when clicked. */
    Loading(0),
    Retry(R.drawable.ic_refresh),
    Settings(R.drawable.ic_mac),
}

class MacRowAdapter(
    private val onClick: (MacCard) -> Unit,
    private val onFocused: (Int) -> Unit,
) : ListAdapter<MacCard, MacRowAdapter.ViewHolder>(DIFF) {
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_poster_card, parent, false)
        val frame = view.findViewById<View>(R.id.poster_frame)
        frame.outlineProvider = ViewOutlineProvider.BACKGROUND
        frame.clipToOutline = true
        return ViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: ViewHolder) {
        Glide.with(holder.image).clear(holder.image)
    }

    inner class ViewHolder(
        view: View,
    ) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.poster_image)
        private val frame: View = view.findViewById(R.id.poster_frame)
        private val name: TextView = view.findViewById(R.id.poster_name)
        private val action: View = view.findViewById(R.id.poster_action)
        private val actionIcon: ImageView = view.findViewById(R.id.poster_action_icon)
        private val actionSpinner: View = view.findViewById(R.id.poster_action_spinner)
        private val actionText: TextView = view.findViewById(R.id.poster_action_text)
        private val progress: View = view.findViewById(R.id.poster_progress)
        private val label: TextView = view.findViewById(R.id.poster_label)
        private val detail: TextView = view.findViewById(R.id.poster_detail)

        fun bind(card: MacCard) {
            itemView.setOnClickListener { onClick(card) }
            itemView.setOnFocusChangeListener { _, hasFocus ->
                val scale = if (hasFocus) FOCUSED_SCALE else 1f
                frame.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
                if (hasFocus) onFocused(bindingAdapterPosition)
            }
            when (card) {
                is MacCard.Title -> bindTitle(card)
                is MacCard.Action -> bindAction(card)
            }
        }

        private fun bindTitle(card: MacCard.Title) {
            action.visibility = View.GONE
            ArabicText.setText(label, card.title.name)
            ArabicText.setText(detail, card.detail)
            val poster = card.title.posterFile
            if (poster != null) {
                name.visibility = View.GONE
                name.background = null
                Glide.with(image).load(File(poster)).into(image)
            } else {
                Glide.with(image).clear(image)
                image.setImageDrawable(null)
                name.visibility = View.VISIBLE
                ArabicText.setText(name, card.title.name)
                name.setBackgroundResource(R.drawable.poster_name_scrim)
            }
            val fraction = card.progress
            if (fraction != null && fraction > 0f) {
                progress.visibility = View.VISIBLE
                frame.post {
                    progress.layoutParams = progress.layoutParams.apply { width = (frame.width * fraction).toInt() }
                }
            } else {
                progress.visibility = View.GONE
            }
        }

        private fun bindAction(card: MacCard.Action) {
            Glide.with(image).clear(image)
            image.setImageDrawable(null)
            name.visibility = View.GONE
            progress.visibility = View.GONE
            action.visibility = View.VISIBLE
            val loading = card.action == MacAction.Loading
            actionSpinner.visibility = if (loading) View.VISIBLE else View.GONE
            actionIcon.visibility = if (loading) View.GONE else View.VISIBLE
            if (!loading) actionIcon.setImageResource(card.action.icon)
            actionText.text = card.text
            ArabicText.setText(label, card.label)
            ArabicText.setText(detail, card.detail)
        }
    }

    companion object {
        private const val FOCUSED_SCALE = 1.06f

        private val DIFF =
            object : DiffUtil.ItemCallback<MacCard>() {
                override fun areItemsTheSame(
                    oldItem: MacCard,
                    newItem: MacCard,
                ): Boolean = oldItem.id == newItem.id

                override fun areContentsTheSame(
                    oldItem: MacCard,
                    newItem: MacCard,
                ): Boolean = oldItem == newItem
            }
    }
}
