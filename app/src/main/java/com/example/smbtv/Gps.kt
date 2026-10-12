package com.example.smbtv

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import com.hierynomus.smbj.share.File as SmbFile

/** One geotagged photo or video; [time] is epoch ms (UTC where the file says so). */
data class GeoPoint(val path: String, val lat: Double, val lon: Double, val time: Long, val isVideo: Boolean)

/** Great-circle distance in km along the points in order. */
fun trackKm(points: List<GeoPoint>): Double = points.zipWithNext { a, b ->
    val r = 6371.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
    2 * r * atan2(sqrt(h), sqrt(1 - h))
}.sum()

/**
 * Collects the GPS track of every photo and video below a folder.
 *
 * Built for speed over SMB: only metadata is read (JPEG/TIFF headers via a 64 KB block cache, video
 * and HEIC boxes via MediaMetadataRetriever on the same cache), files are scanned [PARALLEL] at a
 * time, and results are cached on disk keyed by path, size and modification time.
 */
class GpsScanner(context: Context, private val source: SmbSource) {

    private val cache = GpsCache.get(context)

    /**
     * Scans [root] recursively. [onProgress] runs on the main thread, throttled, with files done so
     * far, the total, and the points found so far (time-sorted). Returns the final time-sorted track.
     */
    suspend fun scan(root: String, onProgress: (done: Int, total: Int, points: List<GeoPoint>) -> Unit): List<GeoPoint> =
        coroutineScope {
            val files = withContext(Dispatchers.IO) { listMedia(root) }
            val found = ArrayList<GeoPoint>()
            val done = AtomicInteger()
            var lastReport = 0L

            fun report(force: Boolean) {
                val now = SystemClock.elapsedRealtime()
                if (!force && now - lastReport < 250) return
                lastReport = now
                onProgress(done.get(), files.size, synchronized(found) { found.sortedWith(ORDER) })
            }

            report(force = true)
            val gate = Semaphore(PARALLEL)
            files.map { (path, entry) ->
                async(Dispatchers.IO) {
                    val key = "${source.host}/${source.share}/$path|${entry.size}|${entry.modified}"
                    val point = if (cache.has(key)) {
                        cache.get(key)
                    } else {
                        gate.withPermit {
                            ensureActive()
                            val p = runCatching { read(path, entry) }.getOrNull()
                            cache.put(key, p)
                            p
                        }
                    }
                    if (point != null) synchronized(found) { found += point }
                    done.incrementAndGet()
                    withContext(Dispatchers.Main) { report(force = false) }
                }
            }.awaitAll()
            cache.flush()
            val track = found.sortedWith(ORDER)
            onProgress(files.size, files.size, track)
            track
        }

    /** Every photo and video below [dir], with its path relative to the share root. */
    private fun listMedia(dir: String): List<Pair<String, SmbEntry>> =
        Smb.list(source, dir).flatMap { e ->
            val path = if (dir.isEmpty()) e.name else "$dir/${e.name}"
            when {
                e.isDir -> listMedia(path)
                e.isMedia -> listOf(path to e)
                else -> emptyList()
            }
        }

    private fun read(path: String, entry: SmbEntry): GeoPoint? {
        val file = Smb.openFile(source, path)
        try {
            val data = SmbRandomAccess(file, entry.size)
            val ext = path.substringAfterLast('.').lowercase()
            val fix = when {
                entry.isVideo || ext in setOf("heic", "heif") -> Metadata.fromRetriever(data, entry.isVideo)
                else -> Metadata.fromImage(data)
            } ?: return null
            // Files without a capture time fall back to their modification time so they still sort sensibly.
            return GeoPoint(path, fix.lat, fix.lon, fix.time.takeIf { it > 0 } ?: entry.modified, entry.isVideo)
        } finally {
            runCatching { file.close() }
        }
    }

    private companion object {
        const val PARALLEL = 8
        val ORDER = compareBy<GeoPoint> { it.time }.thenBy { it.path }
    }
}

/**
 * Random access to an SMB file through 64 KB blocks, so metadata parsers that hop around a file
 * cost one round trip per block instead of one per tiny read.
 */
class SmbRandomAccess(private val file: SmbFile, private val length: Long) : MediaDataSource() {

    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>) = size > 32
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= length || position < 0) return -1
        var done = 0
        while (done < size && position + done < length) {
            val at = position + done
            val index = at / BLOCK
            val block = blocks.getOrPut(index) { load(index) }
            val inBlock = (at - index * BLOCK).toInt()
            if (inBlock >= block.size) break
            val n = min(size - done, block.size - inBlock)
            System.arraycopy(block, inBlock, buffer, offset + done, n)
            done += n
        }
        return if (done == 0) -1 else done
    }

    /** Exactly [count] bytes at [position], or null past the end of the file. */
    fun bytes(position: Long, count: Int): ByteArray? {
        val out = ByteArray(count)
        var got = 0
        while (got < count) {
            val n = readAt(position + got, out, got, count - got)
            if (n <= 0) return null
            got += n
        }
        return out
    }

    private fun load(index: Long): ByteArray {
        val start = index * BLOCK
        val len = min(BLOCK.toLong(), length - start).toInt()
        val block = ByteArray(len)
        var got = 0
        while (got < len) {
            val n = file.read(block, start + got, got, len - got)
            if (n <= 0) break
            got += n
        }
        return if (got == len) block else block.copyOf(got)
    }

    override fun getSize() = length

    override fun close() = Unit

    private companion object {
        const val BLOCK = 64 * 1024
    }
}

/** Minimal readers for the GPS position and capture time of photos and videos. */
object Metadata {

    class Fix(val lat: Double, val lon: Double, val time: Long)

    /** MP4/MOV location + creation date, or the EXIF block of a HEIC, via the platform parser. */
    fun fromRetriever(data: SmbRandomAccess, isVideo: Boolean): Fix? {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(data)
            if (isVideo) {
                val (lat, lon) = parseIso6709(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)) ?: return null
                return Fix(lat, lon, parseVideoDate(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)))
            }
            val offset = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_EXIF_OFFSET)?.toLongOrNull() ?: return null
            return exifPayload(data, offset)
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** JPEG (APP1), TIFF-based RAW (DNG, CR2, NEF, ARW, ORF, RW2, PEF…) and Fuji RAF. */
    fun fromImage(data: SmbRandomAccess): Fix? {
        val head = data.bytes(0, 16) ?: return null
        return when {
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> fromJpeg(data, 0)
            (head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte()) ||
                (head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte()) -> Tiff(data, 0).fix()
            String(head, 0, 8, Charsets.US_ASCII) == "FUJIFILM" -> {
                val jpeg = data.bytes(84, 4)?.let { Tiff.u32(it, 0, bigEndian = true) } ?: return null
                fromJpeg(data, jpeg)
            }
            else -> null
        }
    }

    /** Walks JPEG segments from [start] to the Exif APP1 block. */
    fun fromJpeg(data: SmbRandomAccess, start: Long): Fix? {
        var pos = start + 2
        repeat(64) {
            val seg = data.bytes(pos, 10) ?: return null
            if (seg[0] != 0xFF.toByte()) return null
            val marker = seg[1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) return null // image data / end: no Exif before it
            val len = ((seg[2].toInt() and 0xFF) shl 8) or (seg[3].toInt() and 0xFF)
            if (marker == 0xE1 && String(seg, 4, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000") {
                return Tiff(data, pos + 10).fix()
            }
            pos += 2 + len
        }
        return null
    }

    /** HEIF Exif items may start with a 4-byte header offset and/or the "Exif\0\0" marker. */
    private fun exifPayload(data: SmbRandomAccess, offset: Long): Fix? {
        val head = data.bytes(offset, 10) ?: return null
        val tiff = when {
            String(head, 0, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000" -> offset + 6
            String(head, 4, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000" -> offset + 10
            else -> offset
        }
        return Tiff(data, tiff).fix()
    }

    /** "+47.6603+009.1758+400.000/" → (47.6603, 9.1758). */
    fun parseIso6709(value: String?): Pair<Double, Double>? {
        val m = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(value ?: return null) ?: return null
        val lat = m.groupValues[1].toDouble()
        val lon = m.groupValues[2].toDouble()
        if (lat == 0.0 && lon == 0.0) return null
        return lat to lon
    }

    /** MediaMetadataRetriever dates look like "20240812T140322.000Z" (UTC). */
    fun parseVideoDate(value: String?): Long {
        value ?: return 0L
        for (pattern in listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss")) {
            val f = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val t = runCatching { f.parse(value)?.time }.getOrNull() ?: continue
            // 1904-01-01 is QuickTime's "unset".
            return if (t > 0) t else 0L
        }
        return 0L
    }

    /** Reads just the IFD entries needed for GPS and capture time from a TIFF/EXIF block at [base]. */
    private class Tiff(private val data: SmbRandomAccess, private val base: Long) {
        private var big = false

        fun fix(): Fix? {
            val header = data.bytes(base, 8) ?: return null
            big = header[0] == 'M'.code.toByte()
            val ifd0 = entries(u32(header, 4, big)) ?: return null

            val exif = ifd0[TAG_EXIF_IFD]?.let { entries(it.value) }
            var gps = ifd0[TAG_GPS_IFD]?.let { entries(it.value) }
            // Panasonic RW2 keeps the camera EXIF inside an embedded JPEG.
            if (gps == null) ifd0[TAG_RW2_JPEG]?.let { return fromJpeg(data, base + it.value) }
            gps ?: return null

            val lat = coordinate(gps[1], gps[2]) ?: return null
            val lon = coordinate(gps[3], gps[4]) ?: return null
            if (lat == 0.0 && lon == 0.0) return null

            val gpsTime = run {
                val date = gps[0x1D]?.let(::ascii) ?: return@run 0L
                val hms = gps[7]?.let { rationals(it, 3) } ?: return@run 0L
                parse("$date ${hms[0].toInt()}:${hms[1].toInt()}:${hms[2].toInt()}", "yyyy:MM:dd H:m:s", "UTC")
            }
            val time = gpsTime.takeIf { it > 0 } ?: run {
                val original = exif?.get(TAG_DATETIME_ORIGINAL)?.let(::ascii) ?: ifd0[TAG_DATETIME]?.let(::ascii) ?: return@run 0L
                val offset = exif?.get(TAG_OFFSET_TIME_ORIGINAL)?.let(::ascii)
                parse(original, "yyyy:MM:dd HH:mm:ss", offset?.let { "GMT$it" })
            }
            return Fix(lat, lon, time)
        }

        private class Entry(val type: Int, val count: Long, val value: Long, val at: Long)

        private fun entries(offset: Long): Map<Int, Entry>? {
            val countBytes = data.bytes(base + offset, 2) ?: return null
            val count = u16(countBytes, 0)
            if (count == 0 || count > 1000) return null
            val raw = data.bytes(base + offset + 2, count * 12) ?: return null
            return (0 until count).associate { i ->
                val p = i * 12
                u16(raw, p) to Entry(u16(raw, p + 2), u32(raw, p + 4, big), u32(raw, p + 8, big), base + offset + 2 + p + 8)
            }
        }

        private fun ascii(e: Entry): String? {
            val n = e.count.toInt().coerceIn(0, 64)
            val bytes = (if (n <= 4) data.bytes(e.at, n) else data.bytes(base + e.value, n)) ?: return null
            return String(bytes, Charsets.US_ASCII).trimEnd('\u0000', ' ').ifEmpty { null }
        }

        private fun rationals(e: Entry, n: Int): DoubleArray? {
            if (e.type != 5 || e.count < n) return null
            val raw = data.bytes(base + e.value, n * 8) ?: return null
            return DoubleArray(n) { i ->
                val num = u32(raw, i * 8, big).toDouble()
                val den = u32(raw, i * 8 + 4, big).toDouble()
                if (den == 0.0) 0.0 else num / den
            }
        }

        private fun coordinate(ref: Entry?, value: Entry?): Double? {
            val dms = value?.let { rationals(it, 3) } ?: return null
            val deg = dms[0] + dms[1] / 60 + dms[2] / 3600
            val r = ref?.let(::ascii)
            return if (r == "S" || r == "W") -deg else deg
        }

        private fun u16(b: ByteArray, at: Int) =
            if (big) ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
            else ((b[at + 1].toInt() and 0xFF) shl 8) or (b[at].toInt() and 0xFF)

        companion object {
            const val TAG_EXIF_IFD = 0x8769
            const val TAG_GPS_IFD = 0x8825
            const val TAG_RW2_JPEG = 0x002E
            const val TAG_DATETIME = 0x0132
            const val TAG_DATETIME_ORIGINAL = 0x9003
            const val TAG_OFFSET_TIME_ORIGINAL = 0x9011

            fun u32(b: ByteArray, at: Int, bigEndian: Boolean): Long =
                if (bigEndian) {
                    ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
                        ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
                } else {
                    ((b[at + 3].toLong() and 0xFF) shl 24) or ((b[at + 2].toLong() and 0xFF) shl 16) or
                        ((b[at + 1].toLong() and 0xFF) shl 8) or (b[at].toLong() and 0xFF)
                }

            /** Parses an EXIF date; without a zone the device's local time zone is assumed. */
            fun parse(value: String, pattern: String, zone: String?): Long {
                val f = SimpleDateFormat(pattern, Locale.US)
                if (zone != null) f.timeZone = TimeZone.getTimeZone(zone)
                return runCatching { f.parse(value)?.time }.getOrNull() ?: 0L
            }
        }
    }
}

/** On-disk cache of scan results ("none" for files without GPS), so reopening a route is instant. */
class GpsCache private constructor(private val file: File) {

    private val entries = HashMap<String, GeoPoint?>()
    private val unsaved = StringBuilder()

    init {
        runCatching {
            file.forEachLine { line ->
                val p = line.split('\t')
                when {
                    p.size == 2 && p[1] == "none" -> entries[p[0]] = null
                    p.size == 6 -> entries[p[0]] = GeoPoint(p[1], p[2].toDouble(), p[3].toDouble(), p[4].toLong(), p[5] == "1")
                }
            }
        }
    }

    @Synchronized
    fun has(key: String) = key in entries

    @Synchronized
    fun get(key: String) = entries[key]

    @Synchronized
    fun put(key: String, point: GeoPoint?) {
        entries[key] = point
        unsaved.append(key).append('\t')
        if (point == null) {
            unsaved.append("none")
        } else {
            unsaved.append(point.path).append('\t').append(point.lat).append('\t').append(point.lon).append('\t')
                .append(point.time).append('\t').append(if (point.isVideo) "1" else "0")
        }
        unsaved.append('\n')
        if (unsaved.length > 16_384) flush()
    }

    @Synchronized
    fun flush() {
        if (unsaved.isEmpty()) return
        runCatching { file.appendText(unsaved.toString()) }
        unsaved.clear()
    }

    companion object {
        @Volatile
        private var instance: GpsCache? = null

        fun get(context: Context): GpsCache =
            instance ?: synchronized(this) {
                instance ?: GpsCache(File(context.applicationContext.filesDir, "gps_cache.tsv")).also { instance = it }
            }
    }
}
