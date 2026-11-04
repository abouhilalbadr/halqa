package com.saiesh.tele.presentation.library

import android.content.res.Resources
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.saiesh.tele.R
import com.saiesh.tele.core.cache.TeleCache
import com.saiesh.tele.presentation.text.ArabicText
import com.saiesh.tele.domain.model.Episode
import com.saiesh.tele.domain.model.MediaItem
import com.saiesh.tele.domain.model.Series
import com.saiesh.tele.presentation.media.dialog.ConfirmDeleteDialogFragment
import com.saiesh.tele.presentation.media.dialog.MediaContextMenuDialogFragment
import com.saiesh.tele.presentation.media.dialog.MediaDetailsDialogFragment
import com.saiesh.tele.presentation.player.PlaybackPositions
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * One Telegram series, laid out like a computer title's page: a cover, the name, the episode count and
 * Play/Continue on the left, the episodes in number order on the right. An Arabic name turns the page
 * right to left with Arabic labels. OK on an episode picks a quality and plays.
 */
class SeriesFragment :
    Fragment(R.layout.fragment_series),
    MediaContextMenuDialogFragment.Listener,
    ConfirmDeleteDialogFragment.Listener,
    MediaDetailsDialogFragment.Listener {
    private val viewModel: LibraryViewModel by activityViewModels()
    private val seriesKey: String by lazy { requireArguments().getString(ARG_SERIES_KEY).orEmpty() }
    private lateinit var positions: PlaybackPositions
    private var series: Series? = null
    private var chatTitles: Map<Long, String> = emptyMap()
    private var labels: Resources? = null
    private var hasFocused = false

    private lateinit var cover: ImageView
    private lateinit var title: TextView
    private lateinit var count: TextView
    private lateinit var play: TextView
    private lateinit var loadingMore: TextView
    private lateinit var grid: VerticalGridView
    private lateinit var adapter: EpisodeTileAdapter

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        positions = PlaybackPositions(requireContext())
        cover = view.findViewById(R.id.series_cover)
        title = view.findViewById(R.id.series_title)
        count = view.findViewById(R.id.series_count)
        play = view.findViewById(R.id.series_play)
        loadingMore = view.findViewById(R.id.series_loading)
        grid = view.findViewById(R.id.series_grid)
        view.findViewById<View>(R.id.series_cover_frame).apply {
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }

        val gap = dp(14)
        val gridWidth =
            resources.displayMetrics.widthPixels - 2 * dp(48) -
                resources.getDimensionPixelSize(R.dimen.series_side_width) -
                resources.getDimensionPixelSize(R.dimen.series_grid_gap)
        val tileWidth = (gridWidth - (COLUMNS - 1) * gap) / COLUMNS
        adapter =
            EpisodeTileAdapter(
                tileWidthPx = tileWidth,
                onClick = { episode -> playEpisode(episode) },
                onLongPress = { episode -> showContextMenu(episode.variants.first().item) },
            )
        grid.adapter = adapter
        grid.setNumColumns(COLUMNS)
        grid.setColumnWidth(tileWidth)
        grid.setHorizontalSpacing(gap)
        grid.setVerticalSpacing(gap)

        play.setOnClickListener {
            val current = series ?: return@setOnClickListener
            val episodes = VideoPlayer.inOrder(current)
            episodes.getOrNull(resumeIndex(episodes).index)?.let(::playEpisode)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.series, viewModel.uiState) { all, state ->
                    Triple(all.find { it.key == seriesKey }, state.loadingSeries == seriesKey, state.chatTitles)
                }.collect { (found, isSearching, titles) ->
                    loadingMore.visibility = if (isSearching) View.VISIBLE else View.GONE
                    chatTitles = titles
                    if (found == null) return@collect
                    if (series == null) {
                        applyLanguage(found)
                        viewModel.loadSeries(found)
                    }
                    series = found
                    render(found)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the player: watched marks and the Continue button change.
        series?.let(::render)
    }

    override fun onDestroyView() {
        hasFocused = false
        super.onDestroyView()
    }

    /** An Arabic name reads right to left, in Tajawal, with Arabic buttons and labels. */
    private fun applyLanguage(current: Series) {
        val arabic = ArabicText.isArabic(current.name)
        labels = if (arabic) ArabicText.arabicResources(requireContext()) else resources
        requireView().layoutDirection = if (arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        listOf(title, count, play, loadingMore).forEach { ArabicText.style(it, arabic) }
        loadingMore.text = text().getString(R.string.series_loading_more)
        adapter.watchedLabel = text().getString(R.string.mac_watched)
    }

    private fun text(): Resources = labels ?: resources

    private fun render(current: Series) {
        val res = text()
        val episodes = VideoPlayer.inOrder(current)
        title.text = current.name
        // "Channel name · 12 episodes"; the channel is left out when episodes come from several chats.
        val chats = current.episodes.flatMap { ep -> ep.variants.map { it.item.chatId } }.distinct()
        // Telegram names Saved Messages after the account owner, so it gets its own label here.
        val channel =
            chats.singleOrNull()?.let { chatId ->
                if (chatId == TeleCache.loadSavedMessagesChatId()) res.getString(R.string.series_saved_messages) else chatTitles[chatId]
            }
        count.text = listOfNotNull(channel, res.getQuantityString(R.plurals.mac_episodes, episodes.size, episodes.size)).joinToString(" · ")
        episodes.firstOrNull()?.let { Thumbnails.load(cover, it.thumbnailItem) }

        val resume = resumeIndex(episodes)
        val next = episodes.getOrNull(resume.index)
        play.text =
            when {
                next?.number == null -> res.getString(if (resume.started) R.string.mac_continue else R.string.mac_play)
                resume.started -> res.getString(R.string.mac_continue_episode, next.number)
                else -> res.getString(R.string.mac_play_episode, next.number)
            }
        adapter.submit(
            episodes.map { episode ->
                val keys = keys(episode)
                val watched = keys.any { positions.isWatched(it) }
                val progress = keys.firstOrNull { positions.hasProgress(it) }?.let { positions.fraction(it) }
                EpisodeTile(episode, watched, progress)
            },
        )
        if (!hasFocused) {
            hasFocused = true
            play.post { play.requestFocus() }
        }
    }

    /** The episode in progress, else the one after the last watched, else the first. */
    private fun resumeIndex(episodes: List<Episode>): Resume {
        val last = episodes.indexOfLast { episode -> keys(episode).any { positions.hasProgress(it) } }
        if (last < 0) return Resume(0, started = false)
        if (keys(episodes[last]).none { positions.isWatched(it) }) return Resume(last, started = true)
        return if (last + 1 < episodes.size) Resume(last + 1, started = true) else Resume(0, started = false)
    }

    private fun keys(episode: Episode): List<String> = episode.variants.map { VideoPlayer.playbackKey(it.item) }

    private fun playEpisode(episode: Episode) {
        series?.let { QualitySheetFragment.playOrChoose(this, it, episode) }
    }

    private data class Resume(
        val index: Int,
        val started: Boolean,
    )

    private fun showContextMenu(item: MediaItem) {
        if (childFragmentManager.findFragmentByTag(TAG_CONTEXT_MENU) != null) return
        MediaContextMenuDialogFragment.newInstance(item).show(childFragmentManager, TAG_CONTEXT_MENU)
    }

    override fun onContextPlay(item: MediaItem) = VideoPlayer.play(requireContext(), item, item.title)

    override fun onContextDetails(item: MediaItem) {
        if (childFragmentManager.findFragmentByTag(TAG_DETAILS) != null) return
        MediaDetailsDialogFragment.newInstance(item).show(childFragmentManager, TAG_DETAILS)
    }

    override fun onContextDelete(item: MediaItem) {
        if (childFragmentManager.findFragmentByTag(TAG_CONFIRM_DELETE) != null) return
        ConfirmDeleteDialogFragment.newInstance(item).show(childFragmentManager, TAG_CONFIRM_DELETE)
    }

    override fun onConfirmDelete(item: MediaItem) {
        viewModel.deleteMediaItem(item) { error ->
            if (error != null) {
                activity?.runOnUiThread { Toast.makeText(activity, error, Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onDetailsDismiss() {
        grid.requestFocus()
    }

    override fun onDeleteDismiss() {
        grid.requestFocus()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val ARG_SERIES_KEY = "series_key"
        private const val COLUMNS = 3
        private const val TAG_CONTEXT_MENU = "media_context_menu"
        private const val TAG_CONFIRM_DELETE = "media_confirm_delete"
        private const val TAG_DETAILS = "media_details"

        fun newInstance(seriesKey: String) = SeriesFragment().apply { arguments = bundleOf(ARG_SERIES_KEY to seriesKey) }
    }
}
