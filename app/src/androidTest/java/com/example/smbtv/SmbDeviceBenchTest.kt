package com.example.smbtv

import android.os.Bundle
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device SMB throughput benchmark. Run without uninstalling the app:
 *   adb shell am instrument -w -e smbHost <host> -e smbShare <share> -e smbUser <u> -e smbPass <p> \
 *       -e smbPath <path/in/share> com.example.smbtv.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SmbDeviceBenchTest {

    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun throughput() {
        val host = args.getString("smbHost")
        val share = args.getString("smbShare")
        val path = args.getString("smbPath")
        assumeTrue(host != null && share != null && path != null)
        val src = SmbSource(
            id = "bench", name = "bench", host = host!!,
            share = share!!,
            user = args.getString("smbUser", ""),
            password = args.getString("smbPass", ""),
        )
        val limit = 150_000_000L

        // Warm up the connection so login time isn't counted.
        Smb.list(src, "")

        // The player's real path: SmbDataSource with 64 KB reads from ExoPlayer.
        run {
            val ds = SmbDataSource.Factory { src }.createDataSource()
            ds.open(DataSpec.Builder().setUri(smbUri(src.id, path!!)).setLength(limit).build())
            val buf = ByteArray(64 * 1024)
            var total = 0L
            val t0 = System.nanoTime()
            while (true) {
                val n = ds.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                total += n
            }
            ds.close()
            report("SmbDataSource", total, System.nanoTime() - t0)
        }

        // Raw single-threaded reads at various sizes, to see what one request can do.
        for (size in listOf(256 * 1024, 1024 * 1024, 4 * 1024 * 1024)) {
            val f = Smb.openFile(src, path!!)
            val buf = ByteArray(size)
            var off = 0L
            val t0 = System.nanoTime()
            while (off < 60_000_000L) {
                val n = f.read(buf, off, 0, size)
                if (n <= 0) break
                off += n
            }
            f.close()
            report("raw ${size / 1024}K single", off, System.nanoTime() - t0)
        }
    }

    private fun report(label: String, bytes: Long, nanos: Long) {
        val secs = nanos / 1e9
        val line = "BENCH $label: ${bytes / 1_000_000} MB in ${"%.2f".format(secs)} s = ${"%.1f".format(bytes / 1e6 / secs)} MB/s"
        Log.i("SmbBench", line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", line + "\n") })
    }
}
