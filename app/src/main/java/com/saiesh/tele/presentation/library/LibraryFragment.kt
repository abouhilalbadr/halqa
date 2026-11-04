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
import androidx.recyclerview.widget.RecyclerView
import com.saiesh.tele.R
import com.saiesh.tele.core.mac.MacSettings
import com.saiesh.tele.domain.model.AuthStep
import com.saiesh.tele.domain.model.Series
import com.saiesh.tele.presentation.auth.AuthViewModel
import com.saiesh.tele.presentation.mac.AddMacFragment
import com.saiesh.tele.presentation.mac.MacAction
import com.saiesh.tele.presentation.mac.MacCard
import com.saiesh.tele.presentation.mac.MacLibraryState
import com.saiesh.tele.presentation.mac.MacLibraryViewModel
import com.saiesh.tele.presentation.mac.MacProgress
import com.saiesh.tele.presentation.mac.MacRowAdapter
import com.saiesh.tele.presentation.text.ArabicText
import com.saiesh.tele.presentation.mac.MacTitleFragment
import com.saiesh.tele.presentation.player.PlaybackPositions
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Home screen: what is on the Mac, then the newest Telegram episode of each series, then every series.
 * Without Telegram (the user chose to skip it) only the Mac row shows.
 */
class LibraryFragment : Fragment(R.layout.fragment_library) {
    private val viewModel: LibraryViewModel by activityViewModels()
    private val authViewModel: AuthViewModel by activityViewModels()
    private val macViewModel: MacLibraryViewModel by activityViewModels()
    private var seriesByKey: Map<String, Series> = emptyMap()
    private var telegramOn = false
    private var telegramSeries: List<Series> = emptyList()
    private var telegramLoading = false
    private lateinit var positions: PlaybackPositions

    // Where focus was, so coming back from a series lands on the same card.
    private var focusedRow = MAC_ROW
    private var focusedPosition = 0
    private var hasRestoredFocus = false

    private lateinit var macRow: HorizontalGridView
    private lateinit var macTitle: View
    private lateinit var macSubtitle: TextView
    private lateinit var macChecking: View
    private lateinit var newRow: HorizontalGridView
    private lateinit var seriesRow: HorizontalGridView
    private lateinit var newTitle: View
    private lateinit var seriesTitle: View
    private lateinit var loading: View
    private lateinit var empty: View

    private val macAdapter =
        MacRowAdapter(
            onClick = ::onMacCard,
            onFocused = { position ->
                focusedRow = MAC_ROW
                focusedPosition = position
            },
        )

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
        positions = PlaybackPositions(requireContext())
        macRow = view.findViewById(R.id.library_mac_row)
        macTitle = view.findViewById(R.id.library_mac_title)
        macSubtitle = view.findViewById(R.id.library_mac_subtitle)
        macChecking = view.findViewById(R.id.library_mac_checking)
        newRow = view.findViewById(R.id.library_new_row)
        seriesRow = view.findViewById(R.id.library_series_row)
        newTitle = view.findViewById(R.id.library_new_title)
        seriesTitle = view.findViewById(R.id.library_series_title)
        loading = view.findViewById(R.id.library_loading)
        empty = view.findViewById(R.id.library_empty)

        val account = view.findViewById<TextView>(R.id.library_account)
        account.setOnClickListener { SignOutSheetFragment.show(this, authViewModel.uiState.value.accountName) }
        view.findViewById<View>(R.id.library_refresh).setOnClickListener { viewModel.refresh() }
        view.findViewById<View>(R.id.library_add_mac).setOnClickListener { openPage(AddMacFragment()) }
        val signIn = view.findViewById<View>(R.id.library_sign_in)
        signIn.setOnClickListener { MacSettings.setTelegramSkipped(false) }

        setUpRow(macRow, macAdapter)
        setUpRow(newRow, newAdapter)
        setUpRow(seriesRow, seriesAdapter)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.series, viewModel.uiState) { series, state -> series to state.isLoading }
                    .collect { (series, isLoading) ->
                        telegramSeries = series
                        telegramLoading = isLoading
                        render()
                    }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                macViewModel.state.collect { render() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                authViewModel.uiState.collect { state ->
                    val signedIn = state.step == AuthStep.Authorized
                    if (signedIn) viewModel.initialize()
                    if (signedIn != telegramOn) {
                        telegramOn = signedIn
                        render()
                    }
                    val name = state.accountName.takeIf { signedIn }
                    account.text = name?.let(::initials)
                    account.contentDescription = name?.let { getString(R.string.account_description, it) }
                    account.visibility = if (name == null) View.GONE else View.VISIBLE
                    signIn.visibility = if (signedIn) View.GONE else View.VISIBLE
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        macViewModel.refresh()
    }

    override fun onResume() {
        super.onResume()
        // Back from the player or a Mac title: progress on the Mac posters may have changed.
        if (view != null) render()
    }

    override fun onDestroyView() {
        hasRestoredFocus = false
        super.onDestroyView()
    }

    private fun setUpRow(
        row: HorizontalGridView,
        adapter: RecyclerView.Adapter<*>,
    ) {
        row.adapter = adapter
        row.setHorizontalSpacing(dp(16))
        // Keep the focused card at the left edge so the row scrolls under it like a TV shelf.
        row.windowAlignment = BaseGridView.WINDOW_ALIGN_NO_EDGE
        row.windowAlignmentOffset = dp(48)
        row.windowAlignmentOffsetPercent = BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED
        row.itemAlignmentOffsetPercent = 0f
    }

    private fun render() {
        if (view == null) return
        val macCards = macCards(macViewModel.state.value)
        val series = if (telegramOn) telegramSeries else emptyList()
        val hasSeries = series.isNotEmpty()
        // The Mac row always shows without Telegram; with Telegram it waits until a Mac is set up or
        // there are Telegram videos, so an empty account still gets the centred empty screen.
        val showMac = macCards.isNotEmpty() && (!telegramOn || macViewModel.state.value.login != null || hasSeries)

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

        macAdapter.submitList(if (showMac) macCards else emptyList()) { restoreFocus() }
        newAdapter.submitList(newCards) { restoreFocus() }
        seriesAdapter.submitList(seriesCards) { restoreFocus() }

        val macState = macViewModel.state.value
        macSubtitle.text = macSubtitle(macState)
        macChecking.visibility = if (macState.isScanning && macState.titles.isNotEmpty()) View.VISIBLE else View.GONE
        listOf(macTitle, macRow).forEach { it.visibility = if (showMac) View.VISIBLE else View.GONE }
        listOf(newTitle, newRow, seriesTitle, seriesRow).forEach { it.visibility = if (hasSeries) View.VISIBLE else View.GONE }
        loading.visibility = if (telegramOn && telegramLoading && !hasSeries && !showMac) View.VISIBLE else View.GONE
        val showEmpty = telegramOn && !telegramLoading && !hasSeries && !showMac
        if (showEmpty && empty.visibility != View.VISIBLE) empty.post { empty.findViewById<View>(R.id.library_refresh).requestFocus() }
        empty.visibility = if (showEmpty) View.VISIBLE else View.GONE
    }

    private fun macCards(state: MacLibraryState): List<MacCard> {
        if (state.login == null) {
            return listOf(
                MacCard.Action(
                    MacAction.Add,
                    getString(R.string.mac_add_title),
                    getString(R.string.mac_your_mac),
                    "",
                ),
            )
        }
        val settings =
            MacCard.Action(
                MacAction.Settings,
                getString(R.string.mac_change),
                state.login.name.ifEmpty { state.login.host },
                state.login.share,
            )
        if (state.titles.isEmpty()) {
            if (!state.hasScanned) {
                val loading = MacCard.Action(MacAction.Loading, getString(R.string.mac_loading), state.login.share, "")
                return listOf(loading, settings)
            }
            val retry = MacCard.Action(MacAction.Retry, getString(R.string.mac_retry), getString(R.string.mac_retry_label), "")
            return listOf(retry, settings)
        }
        val arabicLabels by lazy { ArabicText.arabicResources(requireContext()) }
        return state.titles.map { title ->
            val resume = MacProgress.resume(title, positions)
            // The line under the poster follows the title's language, like its page.
            val res = if (MacProgress.isArabic(title)) arabicLabels else resources
            val detail =
                when {
                    title.isMovie -> res.getString(R.string.mac_movie)
                    resume.started -> res.getString(R.string.mac_continue_episode, MacProgress.episodeNumber(title, resume.index))
                    else -> res.getQuantityString(R.plurals.mac_episodes, title.videos.size, title.videos.size)
                }
            MacCard.Title(title, detail, resume.fraction)
        } + settings
    }

    private fun macSubtitle(state: MacLibraryState): String =
        when {
            state.login == null -> getString(R.string.library_mac_subtitle)
            state.error != null -> state.error
            state.hasScanned && state.titles.isEmpty() -> getString(R.string.mac_empty, state.login.share)
            state.isScanning && state.titles.isNotEmpty() -> getString(R.string.mac_checking)
            else -> getString(R.string.library_mac_subtitle)
        }

    private fun onMacCard(card: MacCard) {
        when (card) {
            is MacCard.Title -> openPage(MacTitleFragment.newInstance(card.title.key))
            is MacCard.Action ->
                when (card.action) {
                    MacAction.Add, MacAction.Settings -> openPage(AddMacFragment())
                    MacAction.Retry -> macViewModel.refresh(force = true)
                    MacAction.Loading -> Unit
                }
        }
    }

    private fun restoreFocus() {
        if (hasRestoredFocus || view == null) return
        val macState = macViewModel.state.value
        // On first open, wait for the Mac's titles rather than jumping to a Telegram row and back.
        if (focusedRow == MAC_ROW && macAdapter.itemCount == 0 && macState.login != null && !macState.hasScanned) return
        val rows =
            listOf(
                RowRef(MAC_ROW, macRow, macAdapter.itemCount, hasContent = macState.titles.isNotEmpty()),
                RowRef(NEW_ROW, newRow, newAdapter.itemCount, hasContent = true),
                RowRef(SERIES_ROW, seriesRow, seriesAdapter.itemCount, hasContent = true),
            ).filter { it.count > 0 }
        // A Mac row holding only "Add your Mac" is not where a Telegram user wants to start.
        val target =
            rows.firstOrNull { it.id == focusedRow && (it.hasContent || rows.none { other -> other.id != MAC_ROW }) }
                ?: rows.firstOrNull { it.hasContent }
                ?: rows.firstOrNull()
                ?: return
        hasRestoredFocus = true
        target.row.selectedPosition = if (target.id == focusedRow) focusedPosition.coerceAtMost(target.count - 1) else 0
        target.row.requestFocus()
    }

    private class RowRef(
        val id: Int,
        val row: HorizontalGridView,
        val count: Int,
        val hasContent: Boolean,
    )

    private fun openPage(page: Fragment) {
        parentFragmentManager
            .beginTransaction()
            .replace(R.id.main_browse_fragment, page)
            .addToBackStack(null)
            .commit()
    }

    private fun openSeries(key: String) = openPage(SeriesFragment.newInstance(key))

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
        const val MAC_ROW = 2
        const val NEW_ROW_SIZE = 20
    }
}
