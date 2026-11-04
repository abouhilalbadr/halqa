package com.saiesh.tele.presentation.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.saiesh.tele.R
import com.saiesh.tele.core.stream.LocalStreamServer
import com.saiesh.tele.domain.model.PlaybackItem

/**
 * Full-screen player built for the TV remote. OK pauses, ◀ ▶ skip 10 seconds (faster while held),
 * and Back first hides the controls, then leaves. Each video resumes where it was stopped, and the
 * next episode in [queue] is offered near the end and plays when the current one finishes.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : FragmentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var positions: PlaybackPositions
    private lateinit var queue: List<PlaybackItem>
    private var index = 0
    private var nextOffered = false
    private var lastSavedAt = 0L

    private val handler = Handler(Looper.getMainLooper())

    // Key-ups whose key-down this screen already acted on, so the focused view doesn't act on them too.
    private val swallowedKeyUps = mutableSetOf<Int>()

    private lateinit var playerView: PlayerView
    private lateinit var loading: View
    private lateinit var error: View
    private lateinit var controls: View
    private lateinit var bar: ViewGroup
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var seek: SeekBar
    private lateinit var position: TextView
    private lateinit var remaining: TextView
    private lateinit var playPause: TextView
    private lateinit var episodes: View
    private lateinit var next: View
    private lateinit var episodePanel: View
    private lateinit var episodeList: LinearLayout

    private val hideControls = Runnable { hideControls() }
    private val ticker =
        object : Runnable {
            override fun run() {
                onTick()
                handler.postDelayed(this, TICK_MS)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        @Suppress("UNCHECKED_CAST")
        queue = (IntentCompat.getSerializableExtra(intent, EXTRA_QUEUE, ArrayList::class.java) as? ArrayList<PlaybackItem>).orEmpty()
        if (queue.isEmpty()) {
            finish()
            return
        }
        index = intent.getIntExtra(EXTRA_START, 0).coerceIn(queue.indices)
        positions = PlaybackPositions(this)

        playerView = findViewById(R.id.player_view)
        loading = findViewById(R.id.player_loading)
        error = findViewById(R.id.player_error)
        controls = findViewById(R.id.player_controls)
        bar = findViewById(R.id.player_bar)
        title = findViewById(R.id.player_title)
        subtitle = findViewById(R.id.player_subtitle)
        seek = findViewById(R.id.player_seek)
        position = findViewById(R.id.player_position)
        remaining = findViewById(R.id.player_remaining)
        playPause = findViewById(R.id.player_play_pause)
        episodes = findViewById(R.id.player_episodes)
        next = findViewById(R.id.player_next)
        episodePanel = findViewById(R.id.player_episode_panel)
        episodeList = findViewById(R.id.player_episode_list)

        // Telegram videos stream from TDLib through the local server, which can take a while to
        // send the first bytes, so the timeouts are generous.
        val httpFactory =
            DefaultHttpDataSource
                .Factory()
                .setConnectTimeoutMs(HTTP_TIMEOUT_MS)
                .setReadTimeoutMs(HTTP_TIMEOUT_MS)
        player =
            ExoPlayer
                .Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory)))
                .setAudioAttributes(AudioAttributes.DEFAULT, true)
                .build()
        player.addListener(listener)
        playerView.player = player

        seek.max = SEEK_STEPS
        playPause.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.player_back_10).setOnClickListener { seekBy(-SKIP_MS) }
        findViewById<View>(R.id.player_forward_10).setOnClickListener { seekBy(SKIP_MS) }
        next.setOnClickListener { playAt(index + 1) }
        episodes.setOnClickListener { showEpisodePanel() }

        playAt(index)
    }

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(hideControls)
        if (::player.isInitialized) {
            saveProgress()
            player.pause()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private val listener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                loading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_ENDED) {
                    saveProgress(finished = true)
                    if (index < queue.lastIndex) playAt(index + 1) else finish()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // The TV turns its screen off after a few idle minutes unless a window asks it not to.
                playerView.keepScreenOn = isPlaying
                updatePlayPause()
                if (isPlaying) scheduleHide() else if (player.playbackState == Player.STATE_READY) showControls(autoHide = false)
            }

            override fun onPlayerError(e: PlaybackException) {
                Log.e(TAG, "Playback failed", e)
                loading.visibility = View.GONE
                hideControls()
                error.visibility = View.VISIBLE
            }
        }

    private fun playAt(newIndex: Int) {
        if (newIndex !in queue.indices) return
        if (newIndex != index) saveProgress()
        index = newIndex
        nextOffered = false
        val item = queue[index]
        val uri = item.telegramFileId?.let { LocalStreamServer.urlFor(it) } ?: item.url
        title.text = item.title
        subtitle.text = item.subtitle
        next.visibility = if (index < queue.lastIndex) View.VISIBLE else View.GONE
        episodes.visibility = if (queue.size > 1) View.VISIBLE else View.GONE
        hideEpisodePanel()
        error.visibility = View.GONE
        if (uri == null) {
            error.visibility = View.VISIBLE
            return
        }
        player.setMediaItem(MediaItem.fromUri(uri), positions.resumePositionMs(item.key))
        player.prepare()
        player.play()
        showControls(focus = seek)
    }

    private fun onTick() {
        if (controls.visibility == View.VISIBLE) updateProgress()
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val now = System.currentTimeMillis()
        if (player.isPlaying && now - lastSavedAt > SAVE_EVERY_MS) {
            lastSavedAt = now
            saveProgress()
        }
        if (!nextOffered && index < queue.lastIndex && player.isPlaying && duration - player.currentPosition < OFFER_NEXT_MS) {
            nextOffered = true
            showControls(focus = next, autoHide = false)
        }
    }

    private fun saveProgress(finished: Boolean = false) {
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        positions.save(queue[index].key, if (finished) duration else player.currentPosition, duration)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP) {
            if (swallowedKeyUps.remove(code)) return true
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && handleKey(code, event.repeatCount)) {
            swallowedKeyUps.add(code)
            return true
        }
        if (controls.visibility == View.VISIBLE) scheduleHide()
        return super.dispatchKeyEvent(event)
    }

    /** Remote keys that act on playback rather than move focus. Returns true when the key was used. */
    private fun handleKey(
        code: Int,
        repeatCount: Int,
    ): Boolean {
        if (error.visibility == View.VISIBLE) return false
        val step = SKIP_MS * (1 + repeatCount / 4).coerceAtMost(MAX_SKIP_MULTIPLIER)
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePlay()
            KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(step)
            KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-step)
            KeyEvent.KEYCODE_MEDIA_NEXT -> playAt(index + 1)
            KeyEvent.KEYCODE_BACK ->
                when {
                    episodePanel.visibility == View.VISIBLE -> {
                        hideEpisodePanel()
                        episodes.requestFocus()
                    }
                    controls.visibility == View.VISIBLE -> hideControls()
                    else -> return false
                }
            else -> return handleNavigationKey(code, step)
        }
        return true
    }

    private fun handleNavigationKey(
        code: Int,
        step: Long,
    ): Boolean {
        val select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (controls.visibility != View.VISIBLE) {
            when {
                select -> togglePlay()
                code == KeyEvent.KEYCODE_DPAD_LEFT -> seekBy(-step)
                code == KeyEvent.KEYCODE_DPAD_RIGHT -> seekBy(step)
                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN -> showControls(focus = seek)
                else -> return false
            }
            return true
        }
        if (!seek.isFocused) return false
        when {
            select -> togglePlay()
            code == KeyEvent.KEYCODE_DPAD_LEFT -> seekBy(-step)
            code == KeyEvent.KEYCODE_DPAD_RIGHT -> seekBy(step)
            else -> return false
        }
        return true
    }

    private fun togglePlay() {
        when {
            player.isPlaying -> player.pause()
            player.playbackState == Player.STATE_ENDED -> {
                player.seekTo(0)
                player.play()
            }
            else -> player.play()
        }
        if (controls.visibility != View.VISIBLE) showControls(focus = seek)
    }

    private fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, duration))
        if (controls.visibility != View.VISIBLE) showControls(focus = seek) else scheduleHide()
        updateProgress()
    }

    private fun showControls(
        focus: View? = null,
        autoHide: Boolean = true,
    ) {
        controls.visibility = View.VISIBLE
        updateProgress()
        updatePlayPause()
        when {
            focus != null -> focus.requestFocus()
            currentFocus == null -> seek.requestFocus()
        }
        if (autoHide) scheduleHide() else handler.removeCallbacks(hideControls)
    }

    private fun hideControls() {
        handler.removeCallbacks(hideControls)
        hideEpisodePanel()
        controls.visibility = View.GONE
    }

    /** Controls fade away after a few seconds of playback with no key presses; they stay while paused. */
    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (player.isPlaying && episodePanel.visibility != View.VISIBLE) handler.postDelayed(hideControls, HIDE_AFTER_MS)
    }

    private fun updateProgress() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val current = player.currentPosition.coerceIn(0, duration)
        seek.progress = (current * SEEK_STEPS / duration).toInt()
        position.text = formatTime(current)
        remaining.text = getString(R.string.player_remaining, formatTime(duration - current))
    }

    private fun updatePlayPause() {
        val playing = player.isPlaying
        playPause.setText(if (playing) R.string.player_pause else R.string.player_play)
        playPause.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play,
            0,
            0,
            0,
        )
    }

    private fun showEpisodePanel() {
        if (episodeList.childCount == 0) {
            queue.forEachIndexed { i, item -> episodeList.addView(episodeChip(i, item), chipLayoutParams(i)) }
        }
        episodePanel.visibility = View.VISIBLE
        // The bar underneath stays visible but out of reach, so ▲ ▼ can't wander onto it.
        bar.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        handler.removeCallbacks(hideControls)
        episodeList.getChildAt(index)?.requestFocus()
    }

    private fun hideEpisodePanel() {
        episodePanel.visibility = View.GONE
        bar.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    private fun episodeChip(
        i: Int,
        item: PlaybackItem,
    ) = TextView(this).apply {
        text = item.episodeLabel
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        minWidth = dp(72)
        setPadding(dp(20), dp(12), dp(20), dp(12))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(ContextCompat.getColorStateList(context, R.color.quality_button_text))
        background = ContextCompat.getDrawable(context, R.drawable.quality_button_background)
        setOnClickListener { playAt(i) }
    }

    private fun chipLayoutParams(i: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (i > 0) marginStart = dp(12)
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val TAG = "PlayerActivity"
        private const val EXTRA_QUEUE = "queue"
        private const val EXTRA_START = "start"
        private const val SEEK_STEPS = 1000
        private const val SKIP_MS = 10_000L
        private const val MAX_SKIP_MULTIPLIER = 6
        private const val HIDE_AFTER_MS = 5_000L
        private const val TICK_MS = 500L
        private const val SAVE_EVERY_MS = 5_000L
        private const val OFFER_NEXT_MS = 20_000L
        private const val HTTP_TIMEOUT_MS = 60_000

        fun start(
            context: Context,
            queue: List<PlaybackItem>,
            startIndex: Int,
        ) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_QUEUE, ArrayList(queue))
                    .putExtra(EXTRA_START, startIndex),
            )
        }

        /** "1:02:03" or "2:03". */
        fun formatTime(ms: Long): String {
            val total = ms / 1000
            val hours = total / 3600
            val minutes = (total % 3600) / 60
            val seconds = total % 60
            return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
        }
    }
}
