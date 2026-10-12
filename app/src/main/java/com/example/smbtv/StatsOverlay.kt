package com.example.smbtv

import android.app.Activity
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Display
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * "Stats for nerds" panel: live network/buffer/frame graphs plus decoded-stream details
 * (codec, profile, color, HDR metadata, audio, display mode). Refreshes twice a second while shown.
 */
class StatsOverlay(private val activity: Activity, private val panel: View, private val player: ExoPlayer) {

    private val handler = Handler(Looper.getMainLooper())
    private val netGraph: GraphView = panel.findViewById(R.id.graph_net)
    private val bufGraph: GraphView = panel.findViewById(R.id.graph_buf)
    private val fpsGraph: GraphView = panel.findViewById(R.id.graph_fps)
    private val netValue: TextView = panel.findViewById(R.id.net_value)
    private val bufValue: TextView = panel.findViewById(R.id.buf_value)
    private val fpsValue: TextView = panel.findViewById(R.id.fps_value)
    private val table: LinearLayout = panel.findViewById(R.id.stats_table)
    private val cells = HashMap<String, TextView>()

    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    private var lastTick = 0L
    private var lastBytes = 0L
    private var lastRendered = -1
    private var lastDropped = -1

    private val tick = object : Runnable {
        override fun run() {
            sample()
            handler.postDelayed(this, TICK_MS)
        }
    }

    val isShowing: Boolean get() = panel.visibility == View.VISIBLE

    init {
        netGraph.color = 0xFF8AB4F8.toInt()
        netGraph.floor = 5f
        netGraph.axisLabel = { "%.0f MB/s".format(it) }
        bufGraph.color = 0xFF81C995.toInt()
        bufGraph.floor = 10f
        bufGraph.axisLabel = { "%.0f s".format(it) }
        fpsGraph.color = 0xFFFDD663.toInt()
        fpsGraph.floor = 30f
        fpsGraph.axisLabel = { "%.0f fps".format(it) }

        section("Video")
        row("Codec"); row("Resolution"); row("Bitrate"); row("Decoder")
        section("Color")
        row("Dynamic range"); row("Transfer / primaries"); row("Range / depth"); row("HDR metadata")
        section("Audio")
        row("Format"); row("Audio decoder")
        section("Display")
        row("Output"); row("HDR support")

        // Decoder names are only reported at init time, so listen from the start.
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long,
            ) {
                videoDecoder = decoderName
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long,
            ) {
                audioDecoder = decoderName
            }
        })
    }

    fun toggle() = if (isShowing) hide() else show()

    fun show() {
        panel.alpha = 0f
        panel.visibility = View.VISIBLE
        panel.animate().alpha(1f).setDuration(180).start()
        lastTick = 0L
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun hide() {
        handler.removeCallbacks(tick)
        panel.animate().alpha(0f).setDuration(180).withEndAction { panel.visibility = View.GONE }.start()
    }

    fun stop() = handler.removeCallbacks(tick)

    /** Restarts sampling after [stop] if the panel is still open. */
    fun resume() {
        if (!isShowing) return
        lastTick = 0L
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun sample() {
        val now = SystemClock.elapsedRealtime()
        val dt = (now - lastTick).coerceAtLeast(1) / 1000f
        val bytes = SmbStats.bytesRead.get()
        if (lastTick > 0) {
            val mbps = (bytes - lastBytes) / 1e6f / dt
            netGraph.push(mbps)
            netValue.text = "%.1f MB/s · %.0f Mbit/s".format(mbps, mbps * 8)
        }
        lastBytes = bytes

        val bufferedS = player.totalBufferedDuration / 1000f
        bufGraph.push(bufferedS)
        bufValue.text = "%.1f s".format(bufferedS)

        player.videoDecoderCounters?.let { c ->
            c.ensureUpdated()
            val rendered = c.renderedOutputBufferCount
            val dropped = c.droppedBufferCount
            // Counters restart when a new decoder is created (next playlist item).
            if (lastTick > 0 && lastRendered in 0..rendered) {
                val fps = (rendered - lastRendered) / dt
                fpsGraph.push(fps, marker = dropped > lastDropped)
                fpsValue.text = "%.1f fps · %d dropped".format(fps, dropped)
            }
            lastRendered = rendered
            lastDropped = dropped
        }
        lastTick = now
        updateTable()
    }

    private fun updateTable() {
        val v = player.videoFormat
        val a = player.audioFormat
        val color = v?.colorInfo

        set("Codec", v?.let { listOfNotNull(codecName(it.sampleMimeType), profile(it.codecs)).joinToString(" · ") })
        set("Resolution", v?.let {
            val fps = if (it.frameRate > 0) " · %.3f fps".format(it.frameRate).replace(".000", "") else ""
            val par = if (it.pixelWidthHeightRatio != 1f) " · PAR %.2f".format(it.pixelWidthHeightRatio) else ""
            "${it.width} × ${it.height}$fps$par"
        })
        set("Bitrate", v?.let { videoBitrate(it) })
        set("Decoder", videoDecoder)

        set("Dynamic range", v?.let { dynamicRange(it) })
        set("Transfer / primaries", color?.let { "${transferName(it.colorTransfer)} / ${primariesName(it.colorSpace)}" } ?: "Not signaled")
        set("Range / depth", v?.let {
            val range = when (color?.colorRange) {
                C.COLOR_RANGE_LIMITED -> "Limited (16–235)"
                C.COLOR_RANGE_FULL -> "Full (0–255)"
                else -> "Unspecified"
            }
            "$range · ${bitDepth(it)}"
        })
        set("HDR metadata", hdrMetadata(color?.hdrStaticInfo) ?: "None")

        set("Format", a?.let { audio ->
            listOfNotNull(
                codecName(audio.sampleMimeType),
                channels(audio.channelCount),
                if (audio.sampleRate > 0) "%.1f kHz".format(audio.sampleRate / 1000f).replace(".0 ", " ") else null,
                audio.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbit/s" },
                audio.language?.takeIf { it != "und" },
            ).joinToString(" · ")
        })
        set("Audio decoder", audioDecoder)

        @Suppress("DEPRECATION")
        val display: Display = activity.windowManager.defaultDisplay
        val mode = display.mode
        val match = v?.frameRate?.takeIf { it > 0 }?.let { fps ->
            val ratio = mode.refreshRate / fps
            if (abs(ratio - ratio.roundToInt()) < 0.01f) "matches content" else "≠ %.3f fps (judder)".format(fps).replace(".000", "")
        }
        set("Output", listOfNotNull("${mode.physicalWidth} × ${mode.physicalHeight} @ %.2f Hz".format(mode.refreshRate), match).joinToString(" · "))
        // Display.Mode.getSupportedHdrTypes() replaces this only from API 34.
        @Suppress("DEPRECATION")
        val hdrTypes = display.hdrCapabilities?.supportedHdrTypes
        set("HDR support", hdrTypes?.takeIf { it.isNotEmpty() }?.joinToString(", ") { hdrTypeName(it) } ?: "SDR only")
    }

    private fun videoBitrate(f: Format): String {
        val declared = listOf(f.averageBitrate, f.bitrate, f.peakBitrate).firstOrNull { it > 0 }
        if (declared != null) {
            val peak = f.peakBitrate.takeIf { it > 0 && it != declared }?.let { " (peak %.1f)".format(it / 1e6) } ?: ""
            return "%.1f Mbit/s%s".format(declared / 1e6, peak)
        }
        // MP4s often don't declare a bitrate: estimate from file size and duration.
        val uri = player.currentMediaItem?.localConfiguration?.uri?.toString()
        val size = uri?.let { SmbStats.fileSizes[it] }
        val durationMs = player.duration
        return if (size != null && durationMs > 0) "≈ %.1f Mbit/s (file average)".format(size * 8 / (durationMs / 1000.0) / 1e6) else "Unknown"
    }

    private fun dynamicRange(f: Format): String = when {
        f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> if (f.colorInfo?.hdrStaticInfo != null) "HDR10 (PQ)" else "HDR (PQ)"
        f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
        else -> "SDR"
    }

    private fun bitDepth(f: Format): String {
        val luma = f.colorInfo?.lumaBitdepth ?: Format.NO_VALUE
        if (luma != Format.NO_VALUE) return "$luma-bit"
        val p = profile(f.codecs).orEmpty()
        return if ("10" in p) "10-bit" else "8-bit"
    }

    private fun codecName(mime: String?): String? = when (mime) {
        null -> null
        MimeTypes.VIDEO_H265 -> "HEVC (H.265)"
        MimeTypes.VIDEO_H264 -> "AVC (H.264)"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_VP8 -> "VP8"
        MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        MimeTypes.VIDEO_MP4V -> "MPEG-4 Part 2"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital (AC-3)"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+ (E-AC-3)"
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+ Atmos"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_RAW -> "PCM"
        else -> mime.substringAfter('/')
    }

    /** Human-readable profile/level from an RFC 6381 codecs string, e.g. "hvc1.2.4.L153.B0" → "Main 10 · L5.1". */
    private fun profile(codecs: String?): String? {
        if (codecs == null) return null
        val parts = codecs.split('.')
        return when (parts.firstOrNull()) {
            "hvc1", "hev1" -> {
                val profile = when (parts.getOrNull(1)?.trimStart('A', 'B', 'C')) {
                    "1" -> "Main"; "2" -> "Main 10"; "3" -> "Main Still"; "4" -> "Range Ext"; else -> null
                }
                val level = parts.firstOrNull { it.startsWith("L") || it.startsWith("H") }?.drop(1)?.toIntOrNull()
                    ?.let { "L%.1f".format(it / 30f) }
                listOfNotNull(profile, level).joinToString(" · ").ifEmpty { codecs }
            }
            "avc1", "avc3" -> {
                val hex = parts.getOrNull(1)?.takeIf { it.length == 6 } ?: return codecs
                val profile = when (hex.substring(0, 2).uppercase()) {
                    "42" -> "Baseline"; "4D" -> "Main"; "58" -> "Extended"; "64" -> "High"; "6E" -> "High 10"
                    "7A" -> "High 4:2:2"; "F4" -> "High 4:4:4"; else -> null
                }
                val level = hex.substring(4).toIntOrNull(16)?.let { "L%.1f".format(it / 10f) }
                listOfNotNull(profile, level).joinToString(" · ").ifEmpty { codecs }
            }
            else -> codecs
        }
    }

    private fun transferName(t: Int) = when (t) {
        C.COLOR_TRANSFER_SDR -> "BT.709 (SDR)"
        C.COLOR_TRANSFER_ST2084 -> "PQ (ST 2084)"
        C.COLOR_TRANSFER_HLG -> "HLG"
        C.COLOR_TRANSFER_SRGB -> "sRGB"
        C.COLOR_TRANSFER_LINEAR -> "Linear"
        C.COLOR_TRANSFER_GAMMA_2_2 -> "Gamma 2.2"
        else -> "Unspecified"
    }

    private fun primariesName(s: Int) = when (s) {
        C.COLOR_SPACE_BT709 -> "BT.709"
        C.COLOR_SPACE_BT601 -> "BT.601"
        C.COLOR_SPACE_BT2020 -> "BT.2020"
        else -> "Unspecified"
    }

    private fun channels(n: Int) = when (n) {
        Format.NO_VALUE -> null
        1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"
        else -> "$n ch"
    }

    private fun hdrTypeName(t: Int) = when (t) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        4 -> "HDR10+"
        else -> "Type $t"
    }

    /** Parses Media3's CTA-861.3 static metadata blob (type byte + 12 little-endian shorts). */
    private fun hdrMetadata(info: ByteArray?): String? {
        if (info == null || info.size < 25) return null
        val b = ByteBuffer.wrap(info).order(ByteOrder.LITTLE_ENDIAN)
        b.position(1 + 8 * 2) // skip type byte, RGB primaries and white point
        val maxLum = b.short.toInt() and 0xffff
        val minLum = (b.short.toInt() and 0xffff) / 10_000f
        val maxCll = b.short.toInt() and 0xffff
        val maxFall = b.short.toInt() and 0xffff
        if (maxLum == 0 && maxCll == 0 && maxFall == 0) return null
        return "Mastering %d / %.4f nits · MaxCLL %d · MaxFALL %d".format(maxLum, minLum, maxCll, maxFall)
    }

    private fun section(title: String) {
        table.addView(TextView(activity).apply {
            text = title.uppercase()
            letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(0xFF8A94A3.toInt())
            setPadding(0, if (table.childCount == 0) 0 else dp(10), 0, dp(3))
        })
    }

    private fun row(label: String) {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(activity).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF8A94A3.toInt())
        }, LinearLayout.LayoutParams(dp(130), LinearLayout.LayoutParams.WRAP_CONTENT))
        val value = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFFFFFFFF.toInt())
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            fontFeatureSettings = "tnum"
        }
        row.addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.setPadding(0, dp(1), 0, dp(1))
        table.addView(row)
        cells[label] = value
    }

    private fun set(label: String, value: String?) {
        cells[label]?.text = value ?: "—"
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TICK_MS = 500L
    }
}
