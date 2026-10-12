package com.example.smbtv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Runs against a real SMB server. Set these env vars to enable (otherwise tests are skipped):
 *   SMB_TEST_PORT  - port the server listens on, e.g. 4450
 *   SMB_TEST_ROOT  - local directory exported as share "media"
 *   SMB_TEST_USER / SMB_TEST_PASS - credentials for the share
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmbIntegrationTest {

    private val port = System.getenv("SMB_TEST_PORT")?.toIntOrNull()
    private val root = System.getenv("SMB_TEST_ROOT")?.let(::File)
    private val user = System.getenv("SMB_TEST_USER").orEmpty()
    private val pass = System.getenv("SMB_TEST_PASS").orEmpty()

    private lateinit var source: SmbSource
    private lateinit var store: Store

    @Before
    fun setUp() {
        assumeTrue("SMB_TEST_PORT/SMB_TEST_ROOT not set", port != null && root != null)
        source = SmbSource(name = "test", host = "127.0.0.1", port = port!!, share = "media", user = user, password = pass)
        store = Store(RuntimeEnvironment.getApplication())
        store.addSource(source)
    }

    @Test
    fun wrongPasswordIsRejected() {
        val bad = source.copy(id = "bad-pw", password = pass + "-wrong")
        try {
            Smb.list(bad, "")
            fail("expected authentication failure")
        } catch (expected: Exception) {
            // ok
        }
    }

    @Test
    fun listsFoldersAndFilesOfAShare() {
        val entries = Smb.list(source, "Movies").associateBy { it.name }

        assertTrue(entries.getValue("Sub").isDir)
        assertTrue(entries.getValue("big.mkv").let { !it.isDir && it.isVideo })
        assertEquals(File(root, "Movies/big.mkv").length(), entries.getValue("big.mkv").size)
        assertTrue(entries.containsKey("readme.txt"))
    }

    @Test
    fun listingMissingFolderThrows() {
        try {
            Smb.list(source, "DoesNotExist")
            fail("expected an exception")
        } catch (expected: Exception) {
            // ok
        }
    }

    @Test
    fun streamsWholeFileIdenticallyToDisk() {
        val bytes = readAll(DataSpec(smbUri(source.id, "Movies/big.mkv")))
        assertArrayEquals(sha256(File(root, "Movies/big.mkv").readBytes()), sha256(bytes))
    }

    @Test
    fun seekingReturnsTheCorrectTail() {
        val local = File(root, "Movies/big.mkv").readBytes()
        val offset = 12_345L
        val spec = DataSpec.Builder().setUri(smbUri(source.id, "Movies/big.mkv")).setPosition(offset).build()

        assertArrayEquals(local.copyOfRange(offset.toInt(), local.size), readAll(spec))
    }

    @Test
    fun boundedRangeReturnsExactlyThatSlice() {
        val local = File(root, "Movies/big.mkv").readBytes()
        val spec = DataSpec.Builder()
            .setUri(smbUri(source.id, "Movies/big.mkv"))
            .setPosition(1_000_000L)
            .setLength(4_096L)
            .build()

        assertArrayEquals(local.copyOfRange(1_000_000, 1_004_096), readAll(spec))
    }

    @Test
    fun randomSeeksAcrossReadAheadBoundariesStayCorrect() {
        val local = File(root, "Movies/big.mkv").readBytes()
        // Positions straddle the 2 MB read-ahead window to exercise cache refills.
        for (offset in listOf(0L, 2_097_151L, 2_097_152L, 5_000_000L, local.size - 1L)) {
            val spec = DataSpec.Builder().setUri(smbUri(source.id, "Movies/big.mkv")).setPosition(offset).setLength(64L).build()
            val expectedLen = minOf(64, local.size - offset.toInt())
            assertArrayEquals(
                "offset $offset",
                local.copyOfRange(offset.toInt(), offset.toInt() + expectedLen),
                readAll(spec),
            )
        }
    }

    @Test
    fun openingUnknownSourceFailsWithIOException() {
        val dataSource = SmbDataSource.Factory { null }.createDataSource()
        try {
            dataSource.open(DataSpec(smbUri("no-such-source", "Movies/big.mkv")))
            fail("expected IOException")
        } catch (expected: IOException) {
            // ok
        }
    }

    @Test
    fun storeRoundTripsSourcesAndResumePositions() {
        val extra = SmbSource(name = "nas", host = "10.0.0.2", share = "Media", user = "u", password = "p", domain = "D")
        store.addSource(extra)
        assertEquals(extra, store.sources().single { it.id == extra.id })

        store.removeSource(extra)
        assertEquals(listOf(source), store.sources())

        val uri = smbUri(source.id, "Movies/big.mkv").toString()
        store.savePosition(uri, 90_000L)
        assertEquals(90_000L, store.position(uri))
        store.savePosition(uri, 2_000L)
        assertEquals(0L, store.position(uri))
    }

    private fun readAll(spec: DataSpec): ByteArray {
        val ds: DataSource = SmbDataSource.Factory { if (it == source.id) source else null }.createDataSource()
        ds.open(spec)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = ds.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
            }
        } finally {
            ds.close()
        }
        return out.toByteArray()
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
}
