package com.saiesh.tele.presentation.library

import android.os.Bundle
import android.util.TypedValue
import android.view.View
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
import com.saiesh.tele.presentation.text.ArabicText
import com.saiesh.tele.domain.model.MediaItem
import com.saiesh.tele.domain.model.Series
import com.saiesh.tele.presentation.media.dialog.ConfirmDeleteDialogFragment
import com.saiesh.tele.presentation.media.dialog.MediaContextMenuDialogFragment
import com.saiesh.tele.presentation.media.dialog.MediaDetailsDialogFragment
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** One series: its episodes as a grid of numbers, newest first. OK picks a quality and plays. */
class SeriesFragment :
    Fragment(R.layout.fragment_series),
    MediaContextMenuDialogFragment.Listener,
    ConfirmDeleteDialogFragment.Listener,
    MediaDetailsDialogFragment.Listener {
    private val viewModel: LibraryViewModel by activityViewModels()
    private val seriesKey: String by lazy { requireArguments().getString(ARG_SERIES_KEY).orEmpty() }
    private var series: Series? = null
    private var hasFocusedGrid = false

    private lateinit var grid: VerticalGridView
    private lateinit var adapter: EpisodeTileAdapter

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val title = view.findViewById<TextView>(R.id.series_title)
        val count = view.findViewById<TextView>(R.id.series_count)
        val loadingMore = view.findViewById<View>(R.id.series_loading)
        grid = view.findViewById(R.id.series_grid)

        val gap = dp(16)
        val tileWidth = (resources.displayMetrics.widthPixels - 2 * dp(48) - (COLUMNS - 1) * gap) / COLUMNS
        adapter =
            EpisodeTileAdapter(
                tileWidthPx = tileWidth,
                onClick = { episode -> series?.let { QualitySheetFragment.playOrChoose(this, it, episode) } },
                onLongPress = { episode -> showContextMenu(episode.variants.first().item) },
            )
        grid.adapter = adapter
        grid.setNumColumns(COLUMNS)
        grid.setColumnWidth(tileWidth)
        grid.setHorizontalSpacing(gap)
        grid.setVerticalSpacing(gap)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.series, viewModel.uiState) { all, state ->
                    all.find { it.key == seriesKey } to (state.loadingSeries == seriesKey)
                }.collect { (found, isSearching) ->
                    loadingMore.visibility = if (isSearching) View.VISIBLE else View.GONE
                    if (found == null) return@collect
                    if (series == null) viewModel.loadSeries(found)
                    series = found
                    ArabicText.setText(title, found.name)
                    count.text =
                        if (found.episodes.size == 1) {
                            getString(R.string.series_one_episode)
                        } else {
                            getString(R.string.series_episode_count, found.episodes.size)
                        }
                    adapter.submitList(found.episodes) {
                        if (!hasFocusedGrid && adapter.itemCount > 0) {
                            hasFocusedGrid = true
                            grid.selectedPosition = 0
                            grid.requestFocus()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        hasFocusedGrid = false
        super.onDestroyView()
    }

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
        private const val COLUMNS = 4
        private const val TAG_CONTEXT_MENU = "media_context_menu"
        private const val TAG_CONFIRM_DELETE = "media_confirm_delete"
        private const val TAG_DETAILS = "media_details"

        fun newInstance(seriesKey: String) = SeriesFragment().apply { arguments = bundleOf(ARG_SERIES_KEY to seriesKey) }
    }
}
