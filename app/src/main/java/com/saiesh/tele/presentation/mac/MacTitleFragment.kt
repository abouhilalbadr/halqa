package com.saiesh.tele.presentation.mac

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
import com.bumptech.glide.Glide
import com.saiesh.tele.R
import com.saiesh.tele.core.mac.MacPaths
import com.saiesh.tele.domain.model.MacTitle
import com.saiesh.tele.presentation.player.PlaybackPositions
import com.saiesh.tele.presentation.player.PlayerActivity
import com.saiesh.tele.presentation.text.ArabicText
import android.content.res.Resources
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/** A series or movie from the Mac: poster, Continue and Delete on the left, the episodes on the right. */
class MacTitleFragment : Fragment(R.layout.fragment_mac_title) {
    private val viewModel: MacLibraryViewModel by activityViewModels()
    private val titleKey: String by lazy { requireArguments().getString(ARG_KEY).orEmpty() }
    private lateinit var positions: PlaybackPositions
    private var title: MacTitle? = null
    private var durations: Map<String, Long> = emptyMap()
    private var stills: Map<String, String> = emptyMap()

    /** Labels in the title's language: Arabic for an Arabic title, the app's language otherwise. */
    private var labels: Resources? = null
    private var arabic = false
    private var hasFocused = false
    private var deleting = false

    private lateinit var poster: ImageView
    private lateinit var posterName: TextView
    private lateinit var name: TextView
    private lateinit var meta: TextView
    private lateinit var play: TextView
    private lateinit var delete: View
    private lateinit var summary: TextView
    private lateinit var credits: TextView
    private lateinit var grid: VerticalGridView
    private lateinit var adapter: MacEpisodeAdapter

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        positions = PlaybackPositions(requireContext())
        poster = view.findViewById(R.id.mac_title_poster)
        posterName = view.findViewById(R.id.mac_title_poster_name)
        name = view.findViewById(R.id.mac_title_name)
        meta = view.findViewById(R.id.mac_title_meta)
        play = view.findViewById(R.id.mac_title_play)
        delete = view.findViewById(R.id.mac_title_delete)
        summary = view.findViewById(R.id.mac_title_summary)
        credits = view.findViewById(R.id.mac_title_credits)
        grid = view.findViewById(R.id.mac_title_grid)
        view.findViewById<View>(R.id.mac_title_poster_frame).apply {
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }

        val gap = dp(14)
        val gridWidth = resources.displayMetrics.widthPixels - 2 * dp(48) - dp(196) - dp(32)
        val tileWidth = (gridWidth - (COLUMNS - 1) * gap) / COLUMNS
        adapter = MacEpisodeAdapter(tileWidth) { index -> playFrom(index) }
        grid.adapter = adapter
        grid.setNumColumns(COLUMNS)
        grid.setColumnWidth(tileWidth)
        grid.setHorizontalSpacing(gap)
        grid.setVerticalSpacing(gap)

        play.setOnClickListener {
            val current = title ?: return@setOnClickListener
            playFrom(MacProgress.resume(current, positions).index)
        }
        delete.setOnClickListener {
            val current = title ?: return@setOnClickListener
            MacDeleteSheetFragment.show(this, current.name, current.videos.size)
        }
        childFragmentManager.setFragmentResultListener(MacDeleteSheetFragment.RESULT_KEY, viewLifecycleOwner) { _, _ -> deleteTitle() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.state, viewModel.durations, viewModel.stills) { state, lengths, frames ->
                    Triple(state.titles.find { it.key == titleKey }, lengths, frames)
                }.collect { (found, lengths, frames) ->
                        durations = lengths
                        stills = frames
                        if (found == null) {
                            // Deleted here, or gone from the Mac since the last scan.
                            if (title != null && !deleting) parentFragmentManager.popBackStack()
                            return@collect
                        }
                        if (title == null) {
                            applyLanguage(found)
                            viewModel.loadDetails(found)
                        }
                        title = found
                        render(found)
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the player: watched marks and the Continue button change.
        title?.let(::render)
    }

    override fun onDestroyView() {
        hasFocused = false
        super.onDestroyView()
    }

    /** An Arabic title reads right to left, in Tajawal, with Arabic buttons and labels. */
    private fun applyLanguage(current: MacTitle) {
        arabic = MacProgress.isArabic(current)
        labels = if (arabic) ArabicText.arabicResources(requireContext()) else resources
        requireView().layoutDirection = if (arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        listOf(posterName, name, meta, play, summary, credits).forEach { ArabicText.style(it, arabic) }
        adapter.arabic = arabic
        adapter.watchedLabel = text().getString(R.string.mac_watched)
        delete.contentDescription = text().getString(R.string.mac_delete)
    }

    private fun text(): Resources = labels ?: resources

    private fun render(current: MacTitle) {
        val res = text()
        name.text = current.name
        val posterFile = current.posterFile
        if (posterFile != null) {
            posterName.visibility = View.GONE
            Glide.with(poster).load(File(posterFile)).into(poster)
        } else {
            posterName.visibility = View.VISIBLE
            posterName.text = current.name
        }

        val info = current.info
        summary.text = info?.summary
        summary.visibility = if (info?.summary == null) View.GONE else View.VISIBLE
        val creditLines =
            listOfNotNull(
                info?.cast?.takeIf { it.isNotEmpty() }?.let { res.getString(R.string.mac_starring, it.take(MAX_CAST).joinToString("، ")) },
                info?.director?.let { res.getString(R.string.mac_directed_by, it) },
            )
        credits.text = creditLines.joinToString("\n")
        credits.visibility = if (creditLines.isEmpty()) View.GONE else View.VISIBLE
        // "2025 · دراما · ON" from info.json, ahead of the episode count.
        val about = listOfNotNull(info?.year?.toString(), info?.genres?.firstOrNull(), info?.network)

        val resume = MacProgress.resume(current, positions)
        if (current.isMovie) {
            val length = durationOf(current, 0)
            meta.text = (about + listOfNotNull(res.getString(R.string.mac_movie), length?.let(::formatLength))).joinToString(" · ")
            play.text = res.getString(if (resume.started) R.string.mac_continue else R.string.mac_play)
            grid.visibility = View.GONE
        } else {
            meta.text = (about + res.getQuantityString(R.plurals.mac_episodes, current.videos.size, current.videos.size)).joinToString(" · ")
            val number = MacProgress.episodeNumber(current, resume.index)
            play.text = res.getString(if (resume.started) R.string.mac_continue_episode else R.string.mac_play_episode, number)
            grid.visibility = View.VISIBLE
            adapter.submit(tiles(current))
        }
        if (!hasFocused) {
            hasFocused = true
            play.post { play.requestFocus() }
        }
    }

    private fun tiles(current: MacTitle): List<MacEpisodeTile> =
        current.videos.mapIndexed { i, video ->
            val key = MacPaths.playbackKey(video.path)
            val watched = positions.isWatched(key)
            val length = durationOf(current, i)
            val resumeAt = positions.resumePositionMs(key)
            val left =
                if (!watched && resumeAt > 0 && length != null) {
                    text().getString(R.string.mac_minutes_left, ((length - resumeAt) / 60_000).coerceAtLeast(1))
                } else {
                    null
                }
            MacEpisodeTile(
                index = i,
                number = MacProgress.episodeNumber(current, i),
                meta = listOfNotNull(length?.let(::formatLength), left).joinToString(" · "),
                watched = watched,
                progress = if (resumeAt > 0) positions.fraction(key) else null,
                stillFile = stills[video.path],
            )
        }

    /** The video's length as last played, or as read from the file. */
    private fun durationOf(
        current: MacTitle,
        index: Int,
    ): Long? {
        val video = current.videos[index]
        val played = positions.durationMs(MacPaths.playbackKey(video.path))
        return played.takeIf { it > 0 } ?: durations[video.path]
    }

    private fun playFrom(index: Int) {
        val current = title ?: return
        PlayerActivity.start(requireContext(), MacProgress.queue(text(), current), index)
    }

    private fun deleteTitle() {
        val current = title ?: return
        deleting = true
        delete.isEnabled = false
        viewModel.delete(current) { error ->
            if (!isAdded) return@delete
            if (error == null) {
                Toast.makeText(requireContext(), text().getString(R.string.mac_deleted, current.name), Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            } else {
                deleting = false
                delete.isEnabled = true
                Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun formatLength(ms: Long): String = PlayerActivity.formatTime(ms)

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val ARG_KEY = "title_key"
        private const val COLUMNS = 4
        private const val MAX_CAST = 5

        fun newInstance(key: String) = MacTitleFragment().apply { arguments = bundleOf(ARG_KEY to key) }
    }
}
