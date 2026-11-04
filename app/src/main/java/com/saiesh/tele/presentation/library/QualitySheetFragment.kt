package com.saiesh.tele.presentation.library

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.saiesh.tele.R
import com.saiesh.tele.presentation.text.ArabicText
import com.saiesh.tele.domain.model.Episode
import com.saiesh.tele.domain.model.MediaItem
import com.saiesh.tele.domain.model.Series

/** A sheet along the bottom of the screen with one button per quality of an episode; the best one has focus. */
class QualitySheetFragment : DialogFragment(R.layout.dialog_quality) {
    private val libraryViewModel: LibraryViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.Theme_Tele_ContextMenu)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        val args = requireArguments()
        val playTitle = args.getString(ARG_PLAY_TITLE).orEmpty()
        val seriesKey = args.getString(ARG_SERIES_KEY)
        val episodeKey = args.getString(ARG_EPISODE_KEY)
        val labels = args.getStringArrayList(ARG_LABELS).orEmpty()
        val items = BundleCompat.getParcelableArrayList(args, ARG_ITEMS, Bundle::class.java).orEmpty()
            .mapNotNull { BundleCompat.getSerializable(it, ARG_ITEM, MediaItem::class.java) }

        view.findViewById<View>(R.id.quality_overlay).setOnClickListener { dismiss() }
        ArabicText.setText(view.findViewById(R.id.quality_title), args.getString(ARG_HEADING))
        view.findViewById<TextView>(R.id.quality_subtitle).text = args.getString(ARG_SUBTITLE)

        val buttons = view.findViewById<LinearLayout>(R.id.quality_buttons)
        items.zip(labels).forEachIndexed { index, (item, label) ->
            val button =
                TextView(requireContext()).apply {
                    text = label
                    gravity = Gravity.CENTER
                    isFocusable = true
                    isClickable = true
                    minWidth = dp(96)
                    setPadding(dp(22), dp(12), dp(22), dp(12))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColorStateList(context, R.color.quality_button_text))
                    background = ContextCompat.getDrawable(context, R.drawable.quality_button_background)
                    setOnClickListener {
                        val series = libraryViewModel.series.value.find { it.key == seriesKey }
                        val episode = series?.episodes?.find { it.key == episodeKey }
                        val variant = episode?.variants?.getOrNull(index)
                        if (series != null && variant != null) {
                            VideoPlayer.playEpisode(requireContext(), series, episode, variant)
                        } else {
                            VideoPlayer.play(requireContext(), item, playTitle)
                        }
                        dismiss()
                    }
                }
            buttons.addView(
                button,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) marginStart = dp(14)
                },
            )
            if (index == 0) button.post { button.requestFocus() }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val TAG = "quality_sheet"
        private const val ARG_HEADING = "heading"
        private const val ARG_SUBTITLE = "subtitle"
        private const val ARG_PLAY_TITLE = "play_title"
        private const val ARG_LABELS = "labels"
        private const val ARG_ITEMS = "items"
        private const val ARG_ITEM = "item"
        private const val ARG_SERIES_KEY = "series_key"
        private const val ARG_EPISODE_KEY = "episode_key"

        /** Plays an episode that has one upload straight away; otherwise asks which quality to play. */
        fun playOrChoose(
            host: Fragment,
            series: Series,
            episode: Episode,
        ) {
            val playTitle = listOfNotNull(series.name, episode.number?.let { "EP $it" }).joinToString(" - ")
            if (episode.variants.size == 1) {
                VideoPlayer.playEpisode(host.requireContext(), series, episode, episode.variants.first())
                return
            }
            if (host.childFragmentManager.findFragmentByTag(TAG) != null) return
            val heading = episode.number?.let { host.getString(R.string.quality_play_episode, it) } ?: episode.title
            val subtitle =
                listOfNotNull(VideoLabels.duration(episode.durationSeconds), host.getString(R.string.quality_choose))
                    .joinToString(" · ")
            val labels = episode.variants.map { it.quality ?: host.getString(R.string.quality_unknown) }
            val items = episode.variants.map { bundleOf(ARG_ITEM to it.item) }
            QualitySheetFragment()
                .apply {
                    arguments =
                        bundleOf(
                            ARG_HEADING to heading,
                            ARG_SUBTITLE to subtitle,
                            ARG_PLAY_TITLE to playTitle,
                            ARG_LABELS to ArrayList(labels),
                            ARG_ITEMS to ArrayList(items),
                            ARG_SERIES_KEY to series.key,
                            ARG_EPISODE_KEY to episode.key,
                        )
                }.show(host.childFragmentManager, TAG)
        }
    }
}
