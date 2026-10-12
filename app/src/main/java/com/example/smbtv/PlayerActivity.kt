package com.example.smbtv

import android.animation.LayoutTransition
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
import androidx.media3.datasource.DataSpec
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
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
        private const val CAPTION_MS = 3_000L
        private const val TAG = "SmbTvPhoto"
    }

    private lateinit var player: ExoPlayer
    private lateinit var store: Store
    private lateinit var source: SmbSource
    private lateinit var paths: List<String>
    private lateinit var uris: List<Uri>

    /** Position in [paths] of the item on screen; photos and videos share one playlist. */
    private var index = 0
    private val isPhoto get() = isImageName(paths[index])

    private lateinit var photoView: ImageView
    private lateinit var photoLoading: View
    private lateinit var photoCaption: View
    private lateinit var photoTitle: TextView
    private lateinit var photoSubtitle: TextView
    private val scope: CoroutineScope = MainScope()

    /** Decoded photos for the current item and its neighbours, so stepping through is instant. */
    private val photos = HashMap<Int, Deferred<Photo?>>()
    private lateinit var photoInfo: PhotoInfoPanel

    /** The photo on screen once it has loaded; null while loading or on a video. */
    private var currentPhoto: Photo? = null

    private class Photo(val bitmap: Bitmap, val details: PhotoDetails)
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

    private val hideCaption = Runnable {
        photoCaption.animate().alpha(0f).setDuration(250).withEndAction { photoCaption.visibility = View.INVISIBLE }.start()
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
        uris = paths.map { smbUri(sourceId, it) }
        source = sources[sourceId] ?: run {
            finish()
            return
        }
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
            // A finished video moves on to the next item, photo or video.
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !isPhoto && index < paths.lastIndex) {
                    savePosition()
                    showItem(index + 1)
                }
            }

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
        playerView.findViewById<View>(R.id.prev_button).setOnClickListener { step(-1) }
        playerView.findViewById<View>(R.id.next_button).setOnClickListener { step(1) }

        photoView = findViewById(R.id.photo)
        photoLoading = findViewById(R.id.photo_loading)
        photoCaption = findViewById(R.id.photo_caption)
        photoTitle = findViewById(R.id.photo_title)
        photoSubtitle = findViewById(R.id.photo_subtitle)
        photoInfo = PhotoInfoPanel(this, findViewById(R.id.photo_info), scope)

        seekIndicator = findViewById(R.id.seek_indicator)
        seekIcon = findViewById(R.id.seek_icon)
        seekDelta = findViewById(R.id.seek_delta)
        seekTime = findViewById(R.id.seek_time)
        seekProgress = findViewById(R.id.seek_progress)

        showItem(start, caption = true)
    }

    /** Moves [delta] items through the playlist; does nothing past either end. */
    private fun step(delta: Int) {
        val target = index + delta
        if (target !in paths.indices) {
            Toast.makeText(this, if (delta > 0) "Last item in this folder" else "First item in this folder", Toast.LENGTH_SHORT).show()
            return
        }
        savePosition()
        showItem(target)
    }

    /** Shows item [target]; the photo caption only appears when asked for, so stepping through photos stays clean. */
    private fun showItem(target: Int, caption: Boolean = false) {
        index = target
        currentPhoto = null
        // Drop any pending quick seek (e.g. one that ran the video to its end) and its indicator.
        handler.removeCallbacks(commitSeek)
        handler.removeCallbacks(hideSeekIndicator)
        seekTarget = NO_SEEK
        seekIndicator.animate().cancel()
        seekIndicator.alpha = 0f
        seekIndicator.visibility = View.INVISIBLE
        if (isPhoto) {
            player.stop()
            player.clearMediaItems()
            stats.hide()
            playerView.hideController()
            playerView.useController = false
            playerView.visibility = View.INVISIBLE
            photoView.visibility = View.VISIBLE
            showPhoto(target)
            if (photoInfo.isShowing) bindPhotoInfo()
            if (caption && !photoInfo.isShowing) showCaption() else hideCaptionNow()
        } else {
            photoView.setImageDrawable(null)
            photoView.visibility = View.GONE
            photoLoading.visibility = View.GONE
            hideCaptionNow()
            if (photoInfo.isShowing) photoInfo.hide()
            playerView.visibility = View.VISIBLE
            playerView.useController = true
            player.setMediaItem(MediaItem.fromUri(uris[target]), store.position(uris[target].toString()))
            player.prepare()
            player.play()
        }
        updateTitle()
        trimPhotoCache()
    }

    private fun showPhoto(target: Int) {
        val pending = photo(target)
        if (!pending.isCompleted) {
            photoView.setImageDrawable(null)
            photoLoading.visibility = View.VISIBLE
        }
        // Undispatched, so an already decoded photo is shown in this frame without flashing black.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            setPhoto(target, pending.await())
            // Only then warm up the neighbours, next first and one at a time, so they don't compete with
            // the photo on screen (or hold several 50 MB RAW files in memory at once).
            for (n in listOf(target + 1, target - 1)) {
                if (target != index) break
                if (n in paths.indices && isImageName(paths[n])) photo(n).await()
            }
        }
    }

    private fun setPhoto(target: Int, photo: Photo?) {
        if (target != index) return
        photoLoading.visibility = View.GONE
        currentPhoto = photo
        photoView.setImageBitmap(photo?.bitmap)
        if (photo == null) Toast.makeText(this, "Could not open photo", Toast.LENGTH_SHORT).show()
        if (photoInfo.isShowing) bindPhotoInfo()
    }

    private fun bindPhotoInfo() =
        photoInfo.bind(paths[index], "${index + 1} of ${paths.size}", currentPhoto?.details)

    private fun openMap(location: Pair<Double, Double>) {
        startActivity(
            Intent(this, MapActivity::class.java)
                .putExtra(MapActivity.EXTRA_LAT, location.first)
                .putExtra(MapActivity.EXTRA_LON, location.second)
                .putExtra(MapActivity.EXTRA_TITLE, paths[index].substringAfterLast('/'))
        )
    }

    private fun togglePhotoInfo() {
        if (photoInfo.isShowing) {
            photoInfo.hide()
        } else {
            hideCaptionNow()
            bindPhotoInfo()
            photoInfo.show()
        }
    }

    private fun photo(target: Int): Deferred<Photo?> = photos.getOrPut(target) {
        scope.async(Dispatchers.IO) {
            runCatching { decodePhoto(target) }
                .onFailure { if (it !is CancellationException) Log.w(TAG, "Photo ${paths[target]} failed", it) }
                .getOrNull()
        }
    }

    /** Keeps only the current item and its neighbours, to bound memory on 2 GB boxes. */
    private fun trimPhotoCache() {
        val keep = (index - 1)..(index + 1)
        photos.keys.filter { it !in keep }.forEach { photos.remove(it)?.cancel() }
    }

    /**
     * Reads a photo with [SmbDataSource]'s parallel read-ahead (a plain sequential SMB read is
     * several times slower), stopping between chunks once the photo is no longer wanted.
     */
    private suspend fun readPhoto(target: Int): ByteArray {
        val data = SmbDataSource { if (it == source.id) source else null }
        try {
            val length = data.open(DataSpec(uris[target]))
            val bytes = ByteArray(length.toInt())
            var got = 0
            while (got < bytes.size) {
                currentCoroutineContext().ensureActive()
                val n = data.read(bytes, got, bytes.size - got)
                if (n == C.RESULT_END_OF_INPUT) break
                got += n
            }
            return if (got == bytes.size) bytes else bytes.copyOf(got)
        } finally {
            data.close()
        }
    }

    /** Reads a photo over SMB and decodes it at roughly screen size, upright per its EXIF orientation. */
    private suspend fun decodePhoto(target: Int): Photo? {
        val started = SystemClock.elapsedRealtime()
        val bytes = readPhoto(target)
        val read = SystemClock.elapsedRealtime()
        currentCoroutineContext().ensureActive()
        val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
        val rotation = runCatching {
            when (exif!!.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)

        // Camera RAW shows its largest embedded JPEG preview: most RAW can't be decoded here at all, and
        // the ones that can (DNG) would take many seconds. Anything else undecodable gets the same fallback.
        val isRaw = paths[target].substringAfterLast('.').lowercase() in RAW_EXTENSIONS
        var offset = 0
        var bounds = if (isRaw) BitmapFactory.Options() else boundsAt(bytes, 0)
        if (bounds.outWidth <= 0) {
            jpegStarts(bytes).maxByOrNull { boundsAt(bytes, it).run { outWidth.toLong() * outHeight } }?.let { offset = it }
            bounds = boundsAt(bytes, offset)
            if (bounds.outWidth <= 0) return null
        }
        val metrics = resources.displayMetrics
        val (w, h) = if (rotation % 180 == 0) bounds.outWidth to bounds.outHeight else bounds.outHeight to bounds.outWidth
        var sample = 1
        while (w / (sample * 2) >= metrics.widthPixels && h / (sample * 2) >= metrics.heightPixels) sample *= 2

        val decoded = BitmapFactory.decodeByteArray(bytes, offset, bytes.size - offset, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val bitmap = if (rotation == 0) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        }
        val decodeMs = SystemClock.elapsedRealtime() - read
        Log.d(
            TAG,
            "${paths[target]}${if (offset > 0) " (embedded preview)" else ""}: ${bytes.size / 1024} KB read in ${read - started} ms, " +
                "${bounds.outWidth}x${bounds.outHeight} /$sample decoded in $decodeMs ms",
        )
        val details = PhotoDetails(
            path = paths[target],
            bytes = bytes.size.toLong(),
            mimeType = bounds.outMimeType,
            width = bounds.outWidth,
            height = bounds.outHeight,
            displayWidth = bitmap.width,
            displayHeight = bitmap.height,
            sample = sample,
            embeddedPreview = offset > 0,
            readMs = read - started,
            decodeMs = decodeMs,
            exif = exif,
        )
        return Photo(bitmap, details)
    }

    private fun hideCaptionNow() {
        handler.removeCallbacks(hideCaption)
        photoCaption.animate().cancel()
        photoCaption.alpha = 0f
        photoCaption.visibility = View.INVISIBLE
    }

    private fun boundsAt(bytes: ByteArray, offset: Int) = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, offset, bytes.size - offset, this)
    }

    private fun showCaption() {
        handler.removeCallbacks(hideCaption)
        photoCaption.animate().cancel()
        photoCaption.visibility = View.VISIBLE
        photoCaption.animate().alpha(1f).setDuration(150).start()
        handler.postDelayed(hideCaption, CAPTION_MS)
    }

    /**
     * Keys while a photo is on screen: Left/Right step through the folder, OK / Info toggle the
     * details panel, Up/Down scroll it (or show the caption when it is closed).
     */
    private fun onPhotoKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND ->
                if (down) step(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                if (down) step(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (down && event.repeatCount == 0) {
                // With the panel open on a geotagged photo, OK shows where it was taken.
                val location = photoInfo.location
                if (photoInfo.isShowing && location != null) openMap(location) else togglePhotoInfo()
            }
            KeyEvent.KEYCODE_INFO -> if (down && event.repeatCount == 0) togglePhotoInfo()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (down) {
                if (photoInfo.isShowing) {
                    photoInfo.scrollBy(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                } else if (event.repeatCount == 0) {
                    showCaption()
                }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (down && event.repeatCount == 0) showCaption()
            else -> return false
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::player.isInitialized) return super.dispatchKeyEvent(event)
        if (isPhoto && onPhotoKey(event)) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    step(if (event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) 1 else -1)
                }
                return true
            }

            // Back peels off overlays one at a time; only Back on the bare video leaves the player.
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    when {
                        playerView.isControllerFullyVisible -> playerView.hideController()
                        stats.isShowing -> stats.hide()
                        photoInfo.isShowing -> photoInfo.hide()
                        photoCaption.visibility == View.VISIBLE -> hideCaptionNow()
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
        val path = paths[index]
        val folder = path.substringBeforeLast('/', "").substringAfterLast('/')
        val title = path.substringAfterLast('/').substringBeforeLast('.')
        val subtitle = listOfNotNull(
            folder.takeIf { it.isNotEmpty() },
            "${index + 1} of ${paths.size}".takeIf { paths.size > 1 },
        ).joinToString("  ·  ")
        titleView.text = title
        subtitleView.text = subtitle
        photoTitle.text = title
        photoSubtitle.text = subtitle
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
        scope.cancel()
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
