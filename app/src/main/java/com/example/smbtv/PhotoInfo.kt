package com.example.smbtv

import android.app.Activity
import android.graphics.Typeface
import android.media.ExifInterface
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What was learned while loading one photo; shown by [PhotoInfoPanel]. */
class PhotoDetails(
    val path: String,
    val bytes: Long,
    val mimeType: String?,
    val width: Int,
    val height: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val sample: Int,
    val embeddedPreview: Boolean,
    val readMs: Long,
    val decodeMs: Long,
    val exif: ExifInterface?,
)

/** Side panel with file, camera, exposure, location and raw EXIF details for the photo on screen. */
class PhotoInfoPanel(private val activity: Activity, private val panel: View, private val scope: CoroutineScope) {

    private val scroll: ScrollView = panel.findViewById(R.id.photo_info_scroll)
    private val table: LinearLayout = panel.findViewById(R.id.photo_info_table)

    val isShowing get() = panel.visibility == View.VISIBLE

    /** GPS position of the photo shown in the panel, if it has one; OK opens it on the map. */
    var location: Pair<Double, Double>? = null
        private set

    /** Bumped on every bind so a slow place lookup can't land on the next photo's panel. */
    private var generation = 0

    fun show() {
        panel.animate().cancel()
        panel.alpha = 0f
        panel.visibility = View.VISIBLE
        panel.animate().alpha(1f).setDuration(180).start()
    }

    fun hide() {
        panel.animate().alpha(0f).setDuration(180).withEndAction { panel.visibility = View.GONE }.start()
    }

    /** Scrolls the panel by roughly a third of a screen; [direction] is -1 (up) or 1 (down). */
    fun scrollBy(direction: Int) = scroll.smoothScrollBy(0, direction * dp(220))

    /** Shows [details], or a loading line for [path] while the photo is still on its way. */
    fun bind(path: String, position: String, details: PhotoDetails?) {
        table.removeAllViews()
        scroll.scrollTo(0, 0)
        location = null
        val bound = ++generation

        section("File")
        row("Name", path.substringAfterLast('/'))
        row("Folder", path.substringBeforeLast('/', "").ifEmpty { "/" })
        row("Position", position)
        if (details == null) {
            row("Status", "Loading…")
            return
        }
        row("Size", formatSize(details.bytes))
        row("Format", listOfNotNull(path.substringAfterLast('.').uppercase(), details.mimeType).joinToString("  ·  "))

        val exif = details.exif
        section("Image")
        val fullW = exif?.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)?.takeIf { it > 0 }
            ?: exif?.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)?.takeIf { it > 0 && !details.embeddedPreview }
            ?: details.width
        val fullH = exif?.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)?.takeIf { it > 0 }
            ?: exif?.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)?.takeIf { it > 0 && !details.embeddedPreview }
            ?: details.height
        row("Resolution", "$fullW × $fullH  (%.1f MP)".format(fullW * fullH / 1e6))
        if (details.embeddedPreview) row("Shown", "Embedded JPEG preview ${details.width} × ${details.height}")
        row("On screen", "${details.displayWidth} × ${details.displayHeight}" + if (details.sample > 1) "  (1/${details.sample})" else "")
        row("Orientation", orientationName(exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) ?: 0))
        row("Color space", when (exif?.getAttributeInt(ExifInterface.TAG_COLOR_SPACE, 0)) {
            1 -> "sRGB"
            65535 -> "Uncalibrated (Adobe RGB / P3)"
            else -> null
        })

        if (exif != null) {
            section("Camera")
            row("Camera", listOfNotNull(exif.text(ExifInterface.TAG_MAKE), exif.text(ExifInterface.TAG_MODEL)).distinct().joinToString(" ").ifEmpty { null })
            row("Lens", listOfNotNull(exif.text(TAG_LENS_MAKE), exif.text(TAG_LENS_MODEL)).joinToString(" ").ifEmpty { null })
            row("Taken", exifDate(exif.text(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.text(ExifInterface.TAG_DATETIME), exif.text(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)))
            row("Software", exif.text(ExifInterface.TAG_SOFTWARE))
            row("Artist", exif.text(ExifInterface.TAG_ARTIST))

            section("Exposure")
            row("Shutter", exif.number(ExifInterface.TAG_EXPOSURE_TIME)?.takeIf { it > 0 }?.let(::shutter))
            row("Aperture", exif.number(ExifInterface.TAG_F_NUMBER)?.takeIf { it > 0 }?.let { "f/%.1f".format(it) })
            row("ISO", exif.text(TAG_PHOTOGRAPHIC_SENSITIVITY) ?: exif.text(ExifInterface.TAG_ISO_SPEED_RATINGS))
            row("Focal length", exif.number(ExifInterface.TAG_FOCAL_LENGTH)?.takeIf { it > 0 }?.let { mm ->
                val eq = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
                "%.1f mm".format(mm) + if (eq > 0) "  ($eq mm equiv.)" else ""
            })
            row("Exposure bias", exif.number(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)?.let { "%+.1f EV".format(it) })
            row("Program", programName(exif.getAttributeInt(ExifInterface.TAG_EXPOSURE_PROGRAM, -1)))
            row("Metering", meteringName(exif.getAttributeInt(ExifInterface.TAG_METERING_MODE, -1)))
            row("Flash", exif.text(ExifInterface.TAG_FLASH)?.toIntOrNull()?.let { if (it and 1 == 1) "Fired" else "Off" })
            row("White balance", when (exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, -1)) {
                0 -> "Auto"
                1 -> "Manual"
                else -> null
            })

            val latLong = FloatArray(2)
            if (exif.getLatLong(latLong)) {
                val lat = latLong[0].toDouble()
                val lon = latLong[1].toDouble()
                location = lat to lon
                section("Location")
                val place = row("Place", "Locating…")
                scope.launch {
                    val name = Places.lookup(activity, lat, lon)
                    if (bound == generation) place?.text = name ?: "Unknown"
                }
                row("Coordinates", "%.6f, %.6f".format(Locale.US, lat, lon))?.setTextColor(activity.getColor(R.color.accent))
                row("Map", "Press OK to show on map")?.setTextColor(activity.getColor(R.color.accent))
                exif.getAltitude(Double.NaN).takeIf { !it.isNaN() }?.let { row("Altitude", "%.0f m".format(it)) }
                row("GPS time", listOfNotNull(exif.text(ExifInterface.TAG_GPS_DATESTAMP), exif.text(ExifInterface.TAG_GPS_TIMESTAMP)).joinToString(" ").ifEmpty { null })
            }
        }

        section("Loading")
        row("Read", "${details.readMs} ms" + if (details.readMs > 0) "  (%.1f MB/s)".format(details.bytes / 1e3 / details.readMs) else "")
        row("Decode", "${details.decodeMs} ms")

        val all = exif?.let(::allTags).orEmpty()
        if (all.isNotEmpty()) {
            section("All EXIF tags (${all.size})")
            all.forEach { (tag, value) -> row(tag, value) }
        }
    }

    private fun section(title: String) {
        table.addView(TextView(activity).apply {
            text = title.uppercase()
            letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(0xFF8A94A3.toInt())
            setPadding(0, if (table.childCount == 0) 0 else dp(12), 0, dp(3))
        })
    }

    /** Adds a label/value row and returns its value view; rows without a value are skipped to keep the panel short. */
    private fun row(label: String, value: String?): TextView? {
        if (value.isNullOrBlank()) return null
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(activity).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF8A94A3.toInt())
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT))
        val valueView = TextView(activity).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            fontFeatureSettings = "tnum"
        }
        row.addView(valueView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.setPadding(0, dp(1), 0, dp(1))
        table.addView(row)
        return valueView
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).roundToInt()

    private companion object {
        // Read by the platform ExifInterface but not exposed as constants there.
        const val TAG_LENS_MAKE = "LensMake"
        const val TAG_LENS_MODEL = "LensModel"
        const val TAG_PHOTOGRAPHIC_SENSITIVITY = "PhotographicSensitivity"

        /** Every TAG_* constant ExifInterface knows (found once by reflection), plus the hidden ones above. */
        val TAGS: List<String> by lazy {
            ExifInterface::class.java.fields
                .filter { it.name.startsWith("TAG_") && it.type == String::class.java }
                .mapNotNull { runCatching { it.get(null) as String }.getOrNull() }
                .plus(listOf(TAG_LENS_MAKE, TAG_LENS_MODEL, TAG_PHOTOGRAPHIC_SENSITIVITY))
                .distinct()
                .sorted()
        }

        /** Binary blobs that are useless (and huge) as text. */
        val SKIPPED = setOf("MakerNote", "UserComment", "JPEGInterchangeFormat", "JPEGInterchangeFormatLength", "StripOffsets", "StripByteCounts", "XMP")

        fun allTags(exif: ExifInterface): List<Pair<String, String>> =
            TAGS.filter { it !in SKIPPED }
                .mapNotNull { tag -> exif.getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() }?.let { tag to it.take(120) } }

        fun ExifInterface.text(tag: String) = getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() }

        fun ExifInterface.number(tag: String) = getAttributeDouble(tag, Double.NaN).takeUnless { it.isNaN() }

        fun shutter(seconds: Double) =
            if (seconds >= 0.5) "%.1f s".format(seconds).replace(".0 s", " s") else "1/${(1 / seconds).roundToInt()} s"

        fun exifDate(value: String?, offset: String?): String? {
            value ?: return null
            val date = runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(value) }.getOrNull() ?: return value
            return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(date) + (offset?.let { "  (UTC$it)" } ?: "")
        }

        fun orientationName(o: Int) = when (o) {
            ExifInterface.ORIENTATION_NORMAL -> "Normal"
            ExifInterface.ORIENTATION_ROTATE_90 -> "Rotated 90°"
            ExifInterface.ORIENTATION_ROTATE_180 -> "Rotated 180°"
            ExifInterface.ORIENTATION_ROTATE_270 -> "Rotated 270°"
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "Mirrored"
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> "Flipped"
            ExifInterface.ORIENTATION_TRANSPOSE -> "Transposed"
            ExifInterface.ORIENTATION_TRANSVERSE -> "Transversed"
            else -> null
        }

        fun programName(p: Int) = when (p) {
            1 -> "Manual"
            2 -> "Program auto"
            3 -> "Aperture priority"
            4 -> "Shutter priority"
            5 -> "Creative"
            6 -> "Action"
            7 -> "Portrait"
            8 -> "Landscape"
            else -> null
        }

        fun meteringName(m: Int) = when (m) {
            1 -> "Average"
            2 -> "Center-weighted"
            3 -> "Spot"
            4 -> "Multi-spot"
            5 -> "Pattern / matrix"
            6 -> "Partial"
            else -> null
        }
    }
}
