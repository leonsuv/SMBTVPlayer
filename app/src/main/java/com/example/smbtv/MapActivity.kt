package com.example.smbtv

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Path
import android.os.Bundle
import android.os.SystemClock
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.tan

/**
 * Full-screen OpenStreetMap view driven by the remote. Shows either one photo's position (from the
 * photo info panel) or, with [EXTRA_SOURCE] + [EXTRA_PATH], the time-ordered route of every geotagged
 * photo and video below a folder.
 */
class MapActivity : Activity() {

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_PATH = "path"
    }

    private lateinit var map: MapTileView
    private lateinit var header: TextView
    private val scope = MainScope()

    /** Once the user pans or zooms, the route no longer re-fits itself as points arrive. */
    private var userMoved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        map = MapTileView(this)

        val density = resources.displayMetrics.density
        fun label(text: String, size: Float, color: Int) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
            setPadding((24 * density).toInt(), (10 * density).toInt(), (24 * density).toInt(), (10 * density).toInt())
            setBackgroundColor(0xB3000000.toInt())
        }
        header = label("", 16f, Color.WHITE)
        val keys = label("◀ ▲ ▼ ▶  Move   ·   OK  Zoom in   ·   ⏯ / Menu  Zoom out   ·   Back  Close", 13f, 0xCCFFFFFF.toInt())
        val credit = label("© OpenStreetMap contributors", 11f, 0xCCFFFFFF.toInt())

        setContentView(FrameLayout(this).apply {
            addView(map)
            addView(header, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
            addView(keys, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
            addView(credit, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
        })

        val sourceId = intent.getStringExtra(EXTRA_SOURCE)
        if (sourceId == null) {
            val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
            val lon = intent.getDoubleExtra(EXTRA_LON, 0.0)
            map.pin = lat to lon
            map.centerOn(lat, lon, 15)
            header.text = listOfNotNull(intent.getStringExtra(EXTRA_TITLE), "%.6f, %.6f".format(Locale.US, lat, lon)).joinToString("\n")
        } else {
            showRoute(sourceId, intent.getStringExtra(EXTRA_PATH).orEmpty())
        }
    }

    private fun showRoute(sourceId: String, path: String) {
        val source = Store(this).sources().firstOrNull { it.id == sourceId } ?: return finish()
        val title = "Route  ·  " + (path.substringAfterLast('/').ifEmpty { source.name })
        header.text = "$title\nListing files…"
        map.centerOn(30.0, 10.0, 3)
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            val track = runCatching {
                GpsScanner(this@MapActivity, source).scan(path) { done, total, points ->
                    map.track = points
                    if (!userMoved) map.fit(points)
                    header.text = "$title\nScanning $done / $total files  ·  ${points.size} with GPS"
                }
            }.getOrElse {
                header.text = "$title\nCould not scan: ${it.message}"
                return@launch
            }
            val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
            header.text = if (track.isEmpty()) {
                "$title\nNo GPS data in these photos and videos"
            } else {
                val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)
                val first = dates.format(Date(track.first().time))
                val last = dates.format(Date(track.last().time))
                val photos = track.count { !it.isVideo }
                listOf(
                    title,
                    listOf(
                        if (first == last) first else "$first – $last",
                        "%,.0f km".format(trackKm(track)),
                        "$photos photos, ${track.size - photos} videos",
                        "scanned in %.1f s".format(seconds),
                    ).joinToString("  ·  "),
                ).joinToString("\n")
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = map.width / 4.0
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> map.pan(-step, 0.0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> map.pan(step, 0.0)
            KeyEvent.KEYCODE_DPAD_UP -> map.pan(0.0, -step)
            KeyEvent.KEYCODE_DPAD_DOWN -> map.pan(0.0, step)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_ZOOM_IN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> map.zoomBy(1)
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_ZOOM_OUT, KeyEvent.KEYCODE_MEDIA_REWIND -> map.zoomBy(-1)
            else -> return super.onKeyDown(keyCode, event)
        }
        userMoved = true
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        map.release()
        super.onDestroy()
    }
}

/**
 * Minimal slippy map: draws 256 px OpenStreetMap tiles around a centre point, fetching missing ones
 * in the background (memory LRU + disk cache), with an optional pin and an optional route.
 */
private class MapTileView(context: Context) : View(context) {

    private var zoom = 3

    /** Map centre in world pixels at [zoom]. */
    private var cx = 0.0
    private var cy = 0.0

    /** Single position to mark (photo info). */
    var pin: Pair<Double, Double>? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Time-ordered route to draw as a line with a dot per photo/video. */
    var track: List<GeoPoint> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val scale = resources.displayMetrics.density.coerceAtLeast(1f)
    private val tiles = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(2) // OSM asks clients to keep concurrent requests low.
    private val diskDir = File(context.cacheDir, "tiles")

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val placeholder = Paint().apply { color = 0xFF1A1F26.toInt() }
    private val pinFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    private val pinRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3 * scale
    }
    private val pinHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40E53935 }
    private val startFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF43A047.toInt() }
    private val trackLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E88E5.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4 * scale
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val trackShadow = Paint(trackLine).apply {
        color = 0x99000000.toInt()
        strokeWidth = 7 * scale
    }
    private val photoDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val videoDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt() }

    fun pan(dx: Double, dy: Double) {
        cx += dx / scale
        cy = (cy + dy / scale).coerceIn(0.0, worldSize(zoom))
        invalidate()
    }

    fun zoomBy(delta: Int) {
        val z = (zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (z == zoom) return
        val factor = Math.pow(2.0, (z - zoom).toDouble())
        cx *= factor
        cy *= factor
        zoom = z
        invalidate()
    }

    fun release() = pool.shutdownNow()

    fun centerOn(lat: Double, lon: Double, z: Int) {
        zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        cx = worldX(lon, zoom)
        cy = worldY(lat, zoom)
        invalidate()
    }

    /** Centres on [points] at the closest zoom that shows them all. */
    fun fit(points: List<GeoPoint>) {
        if (points.isEmpty()) return
        if (width == 0) {
            post { fit(points) }
            return
        }
        // Bounds at zoom 0 (one 256 px world); longitudes are taken as-is (no date-line handling).
        val xs = points.map { worldX(it.lon, 0) }
        val ys = points.map { worldY(it.lat, 0) }
        val spanX = (xs.max() - xs.min()).coerceAtLeast(1e-9)
        val spanY = (ys.max() - ys.min()).coerceAtLeast(1e-9)
        val fitX = log2(width * 0.8 / scale / spanX)
        val fitY = log2(height * 0.7 / scale / spanY)
        zoom = floor(minOf(fitX, fitY)).toInt().coerceIn(MIN_ZOOM, 16)
        val f = (1 shl zoom).toDouble()
        cx = (xs.min() + xs.max()) / 2 * f
        cy = (ys.min() + ys.max()) / 2 * f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF0E1116.toInt())
        val size = TILE * scale
        val n = 1 shl zoom
        // World pixel (in scaled screen units) at the top-left of the view.
        val left = cx * scale - width / 2.0
        val top = cy * scale - height / 2.0
        val firstX = floor(left / size).toInt()
        val firstY = floor(top / size).toInt()
        val lastX = floor((left + width) / size).toInt()
        val lastY = floor((top + height) / size).toInt()

        val dst = RectF()
        for (ty in firstY..lastY) {
            if (ty !in 0 until n) continue
            for (tx in firstX..lastX) {
                val sx = (tx * size - left).toFloat()
                val sy = (ty * size - top).toFloat()
                dst.set(sx, sy, sx + size.toFloat(), sy + size.toFloat())
                val wrappedX = Math.floorMod(tx, n)
                val bitmap = tile(zoom, wrappedX, ty)
                when {
                    bitmap != null -> canvas.drawBitmap(bitmap, null, dst, tilePaint)
                    // While a tile loads, stretch the matching quarter of its parent so zooming isn't a blank flash.
                    zoom > MIN_ZOOM && tiles.get(key(zoom - 1, wrappedX / 2, ty / 2)) != null -> {
                        val parent = tiles.get(key(zoom - 1, wrappedX / 2, ty / 2))
                        val half = TILE / 2
                        val src = Rect((wrappedX % 2) * half, (ty % 2) * half, (wrappedX % 2) * half + half, (ty % 2) * half + half)
                        canvas.drawBitmap(parent, src, dst, tilePaint)
                    }
                    else -> canvas.drawRect(dst, placeholder)
                }
            }
        }

        drawTrack(canvas)

        pin?.let { (lat, lon) ->
            val x = screenX(lon)
            val y = screenY(lat)
            canvas.drawCircle(x, y, 22 * scale, pinHalo)
            canvas.drawCircle(x, y, 9 * scale, pinFill)
            canvas.drawCircle(x, y, 9 * scale, pinRing)
        }
    }

    private fun drawTrack(canvas: Canvas) {
        val points = track
        if (points.isEmpty()) return
        val line = Path()
        points.forEachIndexed { i, p ->
            if (i == 0) line.moveTo(screenX(p.lon), screenY(p.lat)) else line.lineTo(screenX(p.lon), screenY(p.lat))
        }
        canvas.drawPath(line, trackShadow)
        canvas.drawPath(line, trackLine)
        for (p in points) {
            canvas.drawCircle(screenX(p.lon), screenY(p.lat), 4.5f * scale, if (p.isVideo) videoDot else photoDot)
        }
        // Start (green) and end (red) markers on top.
        val first = points.first()
        val last = points.last()
        canvas.drawCircle(screenX(last.lon), screenY(last.lat), 9 * scale, pinFill)
        canvas.drawCircle(screenX(last.lon), screenY(last.lat), 9 * scale, pinRing)
        canvas.drawCircle(screenX(first.lon), screenY(first.lat), 9 * scale, startFill)
        canvas.drawCircle(screenX(first.lon), screenY(first.lat), 9 * scale, pinRing)
    }

    /** Screen x of a longitude, using the copy of the world nearest the centre (the map wraps horizontally). */
    private fun screenX(lon: Double): Float {
        val world = worldSize(zoom)
        var px = worldX(lon, zoom)
        while (px - cx > world / 2) px -= world
        while (cx - px > world / 2) px += world
        return ((px - cx) * scale + width / 2.0).toFloat()
    }

    private fun screenY(lat: Double) = ((worldY(lat, zoom) - cy) * scale + height / 2.0).toFloat()

    /** Cached tile, or null after queueing a download (memory, then disk, then network). */
    private fun tile(z: Int, x: Int, y: Int): Bitmap? {
        val k = key(z, x, y)
        tiles.get(k)?.let { return it }
        if (k in failed || !pending.add(k)) return null
        pool.execute {
            val bitmap = runCatching { load(z, x, y) }.getOrNull()
            post {
                pending.remove(k)
                if (bitmap != null) tiles.put(k, bitmap) else failed.add(k)
                invalidate()
            }
        }
        return null
    }

    private fun load(z: Int, x: Int, y: Int): Bitmap? {
        val file = File(diskDir, "$z/$x/$y.png")
        if (file.exists()) BitmapFactory.decodeFile(file.path)?.let { return it }
        val conn = URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("User-Agent", OSM_USER_AGENT)
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            file.parentFile?.mkdirs()
            runCatching { file.writeBytes(bytes) }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TILE = 256
        const val MIN_ZOOM = 2
        const val MAX_ZOOM = 19

        fun key(z: Int, x: Int, y: Int) = "$z/$x/$y"
        fun worldSize(z: Int) = TILE.toDouble() * (1 shl z)
        fun worldX(lon: Double, z: Int) = (lon + 180) / 360 * worldSize(z)
        fun worldY(lat: Double, z: Int): Double {
            val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
            return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * worldSize(z)
        }
    }
}
