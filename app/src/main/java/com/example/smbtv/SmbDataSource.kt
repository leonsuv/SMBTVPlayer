package com.example.smbtv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.smbj.share.File as SmbFile
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Streams SMB files straight into ExoPlayer with random access, so seeking
 * works without downloading the file first.
 *
 * SMB reads are latency-bound: each request is a network round trip and the
 * server caps the size of a single read. To keep the pipe full, a refill
 * fetches [READ_AHEAD_BYTES] as [PARALLEL_READS] chunks issued concurrently.
 */
class SmbDataSource(private val lookup: (String) -> SmbSource?) : BaseDataSource(/* isNetwork = */ true) {

    private val cache = ByteArray(READ_AHEAD_BYTES)
    private var cacheStart = -1L
    private var cacheLen = 0

    private var file: SmbFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val u = dataSpec.uri
        val src = lookup(u.authority.orEmpty()) ?: throw IOException("Unknown SMB source")
        val path = u.pathSegments.joinToString("/")

        val f = try {
            Smb.openFile(src, path)
        } catch (e: Exception) {
            throw if (e is IOException) e else IOException(e)
        }

        file = f
        uri = u
        cacheStart = -1L
        cacheLen = 0

        val size = f.fileInformation.standardInformation.endOfFile
        SmbStats.fileSizes[u.toString()] = size
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
        remaining = remaining.coerceAtLeast(0L)

        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        val f = file ?: return C.RESULT_END_OF_INPUT

        if (position < cacheStart || position >= cacheStart + cacheLen) {
            val n = try {
                refill(f, position)
            } catch (e: Exception) {
                throw if (e is IOException) e else IOException(e)
            }
            if (n <= 0) return C.RESULT_END_OF_INPUT
            cacheStart = position
            cacheLen = n
        }

        val available = cacheStart + cacheLen - position
        val n = min(min(length.toLong(), available), remaining).toInt()
        System.arraycopy(cache, (position - cacheStart).toInt(), buffer, offset, n)
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    /**
     * Fills [cache] with the file bytes starting at [start]. Returns the length of the
     * contiguous valid prefix (less than the window only at end of file).
     */
    private fun refill(f: SmbFile, start: Long): Int {
        val chunk = READ_AHEAD_BYTES / PARALLEL_READS
        val futures = (0 until PARALLEL_READS).map { i ->
            val at = i * chunk
            IO_POOL.submit(Callable { readFully(f, start + at, at, chunk) })
        }
        // Wait for every chunk before returning so no task writes into the cache later.
        val results = futures.map { runCatching { it.get() } }
        results.firstOrNull { it.isFailure }?.let { throw (it.exceptionOrNull() as? ExecutionException)?.cause ?: it.exceptionOrNull()!! }

        var valid = 0
        for (r in results) {
            val got = r.getOrThrow()
            valid += got
            if (got < chunk) break
        }
        SmbStats.bytesRead.addAndGet(valid.toLong())
        return valid
    }

    /** Reads up to [len] bytes at [fileOffset] into cache[dstOffset...], looping over short reads. */
    private fun readFully(f: SmbFile, fileOffset: Long, dstOffset: Int, len: Int): Int {
        var got = 0
        while (got < len) {
            val n = f.read(cache, fileOffset + got, dstOffset + got, len - got)
            if (n <= 0) break
            got += n
        }
        return got
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        file?.let { runCatching { it.close() } }
        file = null
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }

    class Factory(private val lookup: (String) -> SmbSource?) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(lookup)
    }

    private companion object {
        const val READ_AHEAD_BYTES = 2 * 1024 * 1024
        const val PARALLEL_READS = 8

        /** Shared across all open files so concurrent playback doesn't spawn threads per file. */
        val IO_POOL: ExecutorService = Executors.newFixedThreadPool(PARALLEL_READS) { r ->
            Thread(r, "smb-read").apply { isDaemon = true }
        }
    }
}

/** Process-wide counters for the stats overlay. */
object SmbStats {
    /** Bytes fetched from SMB servers since process start. */
    val bytesRead = AtomicLong()

    /** File size per opened smb:// URI, for estimating average bitrate. */
    val fileSizes = ConcurrentHashMap<String, Long>()
}
