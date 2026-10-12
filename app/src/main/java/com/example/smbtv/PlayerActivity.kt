package com.example.smbtv

import android.animation.LayoutTransition
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import kotlin.math.abs

class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_SOURCE = "source"
        const val EXTRA_PATHS = "paths"
        const val EXTRA_INDEX = "index"

        private const val TAP_SEEK_MS = 10_000L
        private const val SEEK_COMMIT_DELAY_MS = 450L
        private const val AUTOSAVE_MS = 10_000L
        private const val NO_SEEK = -1L
    }

    private lateinit var player: ExoPlayer
    private lateinit var store: Store
    private lateinit var paths: List<String>
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var stats: StatsOverlay
    private lateinit var seekIndicator: View
    private lateinit var seekIcon: ImageView
    private lateinit var seekDelta: TextView
    private lateinit var seekTime: TextView
    private lateinit var seekProgress: ProgressBar
    private val handler = Handler(Looper.getMainLooper())

    // Quick seek (Left/Right while the controller is hidden). Taps and holds accumulate into
    // seekTarget, and the player seeks once input settles, so the SMB stream isn't reopened per key.
    private var seekTarget = NO_SEEK
    private var seekOrigin = 0L
    private var holdStart = 0L
    private var lastRepeat = 0L

    /** Set when Back closed an overlay, so the matching key-up doesn't also close the player. */
    private var backConsumed = false

    private val commitSeek = Runnable {
        if (seekTarget != NO_SEEK) player.seekTo(seekTarget)
        seekTarget = NO_SEEK
        handler.postDelayed(hideSeekIndicator, 700)
    }

    private val hideSeekIndicator = Runnable {
        seekIndicator.animate().alpha(0f).setDuration(250).withEndAction { seekIndicator.visibility = View.INVISIBLE }.start()
    }

    private val autoSave = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, AUTOSAVE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = Store(this)

        val sources = store.sources().associateBy { it.id }
        val sourceId = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
        paths = intent.getStringArrayListExtra(EXTRA_PATHS).orEmpty()
        val start = intent.getIntExtra(EXTRA_INDEX, 0)
        val uris = paths.map { smbUri(sourceId, it) }
        if (uris.isEmpty() || start !in uris.indices) {
            Toast.makeText(this, "Nothing to play", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        player = ExoPlayer.Builder(this)
            .setRenderersFactory(DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 15_000,
                        /* maxBufferMs = */ 40_000,
                        /* bufferForPlaybackMs = */ 1_000,
                        /* bufferForPlaybackAfterRebufferMs = */ 2_500,
                    )
                    // Cap buffered bytes so a 2 GB box doesn't run out of heap on high-bitrate files.
                    .setTargetBufferBytes(48 * 1024 * 1024)
                    .build()
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(SmbDataSource.Factory { sources[it] }))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updateTitle()

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Playback failed: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
            }
        })

        playerView = findViewById(R.id.player)
        playerView.player = player
        // Fade the controller in/out (Media3's own animation is disabled in the layout).
        playerView.layoutTransition = LayoutTransition().apply {
            setDuration(200)
            disableTransitionType(LayoutTransition.CHANGE_APPEARING)
            disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING)
        }
        titleView = playerView.findViewById(R.id.player_title)
        subtitleView = playerView.findViewById(R.id.player_subtitle)
        // D-pad left/right on the progress bar scrubs in 10 s steps.
        playerView.findViewById<DefaultTimeBar>(R.id.exo_progress).setKeyTimeIncrement(10_000)

        stats = StatsOverlay(this, findViewById(R.id.stats_panel), player)
        playerView.findViewById<View>(R.id.info_button).setOnClickListener { stats.toggle() }

        seekIndicator = findViewById(R.id.seek_indicator)
        seekIcon = findViewById(R.id.seek_icon)
        seekDelta = findViewById(R.id.seek_delta)
        seekTime = findViewById(R.id.seek_time)
        seekProgress = findViewById(R.id.seek_progress)

        player.setMediaItems(
            uris.map { MediaItem.fromUri(it) },
            start,
            store.position(uris[start].toString()),
        )
        updateTitle()
        player.prepare()
        player.play()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::player.isInitialized) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            // Back peels off overlays one at a time; only Back on the bare video leaves the player.
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    when {
                        playerView.isControllerFullyVisible -> playerView.hideController()
                        stats.isShowing -> stats.hide()
                        else -> return super.dispatchKeyEvent(event)
                    }
                    backConsumed = true
                    return true
                }
                if (backConsumed) {
                    if (event.action == KeyEvent.ACTION_UP) backConsumed = false
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (!playerView.isControllerFullyVisible) {
                    onQuickSeekKey(event)
                    return true
                }

            KeyEvent.KEYCODE_INFO -> {
                if (event.action == KeyEvent.ACTION_UP) stats.toggle()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * A tap jumps 10 s. Holding accelerates with hold time (1, 3, then 10 minutes per second)
     * so short clips stay controllable and feature films are still quick to cross.
     */
    private fun onQuickSeekKey(event: KeyEvent) {
        val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                handler.removeCallbacks(commitSeek)
                handler.removeCallbacks(hideSeekIndicator)
                if (seekTarget == NO_SEEK) {
                    seekTarget = player.currentPosition
                    seekOrigin = seekTarget
                }
                val step = if (event.repeatCount == 0) {
                    holdStart = event.eventTime
                    lastRepeat = event.eventTime
                    TAP_SEEK_MS
                } else {
                    val held = event.eventTime - holdStart
                    val dt = (event.eventTime - lastRepeat).coerceIn(1, 250)
                    lastRepeat = event.eventTime
                    val speed = when {
                        held < 1_500 -> 60L
                        held < 4_000 -> 180L
                        else -> 600L
                    }
                    dt * speed
                }
                seekTarget = clampPosition(seekTarget + if (forward) step else -step)
                showSeekIndicator(forward)
            }

            KeyEvent.ACTION_UP -> handler.postDelayed(commitSeek, SEEK_COMMIT_DELAY_MS)
        }
    }

    private fun clampPosition(ms: Long): Long {
        val duration = player.duration
        val max = if (duration == C.TIME_UNSET) Long.MAX_VALUE else (duration - 1_000).coerceAtLeast(0)
        return ms.coerceIn(0, max)
    }

    private fun showSeekIndicator(forward: Boolean) {
        val delta = seekTarget - seekOrigin
        val ahead = if (delta == 0L) forward else delta > 0
        seekIcon.setImageResource(if (ahead) R.drawable.ic_forward else R.drawable.ic_rewind)
        val sign = if (delta < 0) "−" else "+"
        val abs = abs(delta)
        seekDelta.text = if (abs < 60_000) "$sign${abs / 1000} s" else "$sign${formatClock(abs)}"
        val duration = player.duration
        seekTime.text = if (duration == C.TIME_UNSET) formatClock(seekTarget) else "${formatClock(seekTarget)} / ${formatClock(duration)}"
        seekProgress.progress = if (duration > 0) (seekTarget * 1000 / duration).toInt() else 0
        if (seekIndicator.visibility != View.VISIBLE) {
            seekIndicator.visibility = View.VISIBLE
            seekIndicator.animate().alpha(1f).setDuration(120).start()
        } else {
            seekIndicator.animate().cancel()
            seekIndicator.alpha = 1f
        }
    }

    private fun updateTitle() {
        val index = player.currentMediaItemIndex
        val path = paths.getOrNull(index) ?: return
        titleView.text = path.substringAfterLast('/').substringBeforeLast('.')
        val folder = path.substringBeforeLast('/', "").substringAfterLast('/')
        subtitleView.text = listOfNotNull(
            folder.takeIf { it.isNotEmpty() },
            "${index + 1} of ${paths.size}".takeIf { paths.size > 1 },
        ).joinToString("  ·  ")
    }

    override fun onStart() {
        super.onStart()
        // Periodic saves keep the resume point even if the process is killed (e.g. app update).
        if (::player.isInitialized) {
            handler.postDelayed(autoSave, AUTOSAVE_MS)
            stats.resume()
        }
    }

    override fun onStop() {
        if (::player.isInitialized) {
            handler.removeCallbacks(autoSave)
            stats.stop()
            savePosition()
            player.pause()
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private fun savePosition() {
        val uri = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        val duration = player.duration
        val position = player.currentPosition
        // Finished files (within 30 s of the end) start from the beginning next time.
        val finished = duration != C.TIME_UNSET && position > duration - 30_000
        store.savePosition(uri, if (finished) 0L else position)
    }
}
