package com.saiesh.tele.presentation.library

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.HorizontalGridView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.saiesh.tele.R
import com.saiesh.tele.domain.model.Series
import com.saiesh.tele.presentation.auth.AuthViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Home screen: the newest episode of each series, then every series. OK on a series opens its episodes. */
class LibraryFragment : Fragment(R.layout.fragment_library) {
    private val viewModel: LibraryViewModel by activityViewModels()
    private val authViewModel: AuthViewModel by activityViewModels()
    private var seriesByKey: Map<String, Series> = emptyMap()

    // Where focus was, so coming back from a series lands on the same card.
    private var focusedRow = NEW_ROW
    private var focusedPosition = 0
    private var hasRestoredFocus = false

    private lateinit var newRow: HorizontalGridView
    private lateinit var seriesRow: HorizontalGridView
    private lateinit var newTitle: View
    private lateinit var seriesTitle: View
    private lateinit var loading: View
    private lateinit var empty: View

    private val newAdapter =
        ShelfAdapter(
            widthDp = 196,
            heightDp = 110,
            onClick = { card -> seriesByKey[card.key]?.let { QualitySheetFragment.playOrChoose(this, it, it.latestEpisode) } },
            onFocused = { position ->
                focusedRow = NEW_ROW
                focusedPosition = position
            },
        )

    private val seriesAdapter =
        ShelfAdapter(
            widthDp = 124,
            heightDp = 70,
            onClick = { card -> openSeries(card.key) },
            onFocused = { position ->
                focusedRow = SERIES_ROW
                focusedPosition = position
                viewModel.onPositionFocused(position, seriesByKey.size)
            },
        )

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        newRow = view.findViewById(R.id.library_new_row)
        seriesRow = view.findViewById(R.id.library_series_row)
        newTitle = view.findViewById(R.id.library_new_title)
        seriesTitle = view.findViewById(R.id.library_series_title)
        loading = view.findViewById(R.id.library_loading)
        empty = view.findViewById(R.id.library_empty)

        val account = view.findViewById<TextView>(R.id.library_account)
        account.setOnClickListener { SignOutSheetFragment.show(this, authViewModel.uiState.value.accountName) }
        view.findViewById<View>(R.id.library_refresh).setOnClickListener { viewModel.refresh() }

        setUpRow(newRow, newAdapter)
        setUpRow(seriesRow, seriesAdapter)

        viewModel.initialize()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.series, viewModel.uiState) { series, state -> series to state.isLoading }
                    .collect { (series, isLoading) -> render(series, isLoading) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                authViewModel.uiState.collect { state ->
                    val name = state.accountName
                    account.text = name?.let(::initials)
                    account.contentDescription = name?.let { getString(R.string.account_description, it) }
                    account.visibility = if (name == null) View.INVISIBLE else View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        hasRestoredFocus = false
        super.onDestroyView()
    }

    private fun setUpRow(
        row: HorizontalGridView,
        adapter: ShelfAdapter,
    ) {
        row.adapter = adapter
        row.setHorizontalSpacing(dp(16))
        // Keep the focused card at the left edge so the row scrolls under it like a TV shelf.
        row.windowAlignment = BaseGridView.WINDOW_ALIGN_NO_EDGE
        row.windowAlignmentOffset = dp(48)
        row.windowAlignmentOffsetPercent = BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED
        row.itemAlignmentOffsetPercent = 0f
    }

    private fun render(
        series: List<Series>,
        isLoading: Boolean,
    ) {
        seriesByKey = series.associateBy { it.key }
        val newest = series.sortedByDescending { it.latestEpisode.latestDate }.take(NEW_ROW_SIZE)
        val newCards =
            newest.map { item ->
                val episode = item.latestEpisode
                ShelfCard(
                    key = item.key,
                    thumbnail = episode.thumbnailItem,
                    label = item.name,
                    badge = episode.number?.let { getString(R.string.library_episode_short, it) },
                )
            }
        val seriesCards = series.map { ShelfCard(it.key, it.latestEpisode.thumbnailItem, it.name, null) }

        newAdapter.submitList(newCards) { restoreFocus() }
        seriesAdapter.submitList(seriesCards) { restoreFocus() }

        val hasSeries = series.isNotEmpty()
        listOf(newTitle, newRow, seriesTitle, seriesRow).forEach { it.visibility = if (hasSeries) View.VISIBLE else View.INVISIBLE }
        loading.visibility = if (isLoading && !hasSeries) View.VISIBLE else View.GONE
        val showEmpty = !isLoading && !hasSeries
        if (showEmpty && empty.visibility != View.VISIBLE) empty.post { empty.findViewById<View>(R.id.library_refresh).requestFocus() }
        empty.visibility = if (showEmpty) View.VISIBLE else View.GONE
    }

    private fun restoreFocus() {
        if (hasRestoredFocus || view == null) return
        val (row, adapter) = if (focusedRow == SERIES_ROW) seriesRow to seriesAdapter else newRow to newAdapter
        if (adapter.itemCount == 0) return
        hasRestoredFocus = true
        row.selectedPosition = focusedPosition.coerceAtMost(adapter.itemCount - 1)
        row.requestFocus()
    }

    private fun openSeries(key: String) {
        parentFragmentManager
            .beginTransaction()
            .replace(R.id.main_browse_fragment, SeriesFragment.newInstance(key))
            .addToBackStack(null)
            .commit()
    }

    /** "Jane Doe" → "JD"; one-word names give one letter. */
    private fun initials(name: String): String =
        name
            .split(' ')
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val NEW_ROW = 0
        const val SERIES_ROW = 1
        const val NEW_ROW_SIZE = 20
    }
}
