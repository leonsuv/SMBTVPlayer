package com.example.smbtv

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.util.concurrent.TimeUnit

val VIDEO_EXTENSIONS = setOf(
    "mkv", "mp4", "m4v", "mov", "avi", "ts", "m2ts", "mpg", "mpeg", "wmv", "webm", "flv", "3gp"
)

/** Camera RAW formats; shown via the full-size JPEG preview the camera embeds in the file. */
val RAW_EXTENSIONS = setOf(
    "dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf", "rw2", "rwl",
    "pef", "srw", "x3f", "3fr", "iiq", "erf", "kdc", "mrw", "mef", "mos",
)

val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif") + RAW_EXTENSIONS

fun isVideoName(name: String) = name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

fun isImageName(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

/** A directory entry; [created] and [modified] are epoch milliseconds. */
class SmbEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val created: Long = 0L,
    val modified: Long = 0L,
) {
    val isVideo: Boolean get() = !isDir && isVideoName(name)
    val isImage: Boolean get() = !isDir && isImageName(name)
    val isMedia: Boolean get() = isVideo || isImage
}

/**
 * Keeps one authenticated connection per share and reuses it for listing and reading.
 * Paths use "/" everywhere in the app and are converted to SMB's "\" here.
 */
object Smb {
    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(10, TimeUnit.SECONDS)
            .withSoTimeout(15, TimeUnit.SECONDS)
            .withSecurityProvider(FastSecurityProvider())
            .build()
    )

    private class Link(val connection: Connection, val share: DiskShare)

    private val links = HashMap<String, Link>()

    @Synchronized
    private fun share(src: SmbSource, reconnect: Boolean): DiskShare {
        links[src.id]?.let { if (!reconnect && it.connection.isConnected) return it.share }
        links.remove(src.id)?.let { runCatching { it.connection.close() } }

        val conn = client.connect(src.host, src.port)
        val auth = if (src.user.isEmpty()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(src.user, src.password.toCharArray(), src.domain)
        }
        val share = conn.authenticate(auth).connectShare(src.share) as DiskShare
        links[src.id] = Link(conn, share)
        return share
    }

    /** Runs [block] on a live share, reconnecting once if the cached connection went stale. */
    private fun <T> withShare(src: SmbSource, block: (DiskShare) -> T): T =
        try {
            block(share(src, reconnect = false))
        } catch (e: Exception) {
            block(share(src, reconnect = true))
        }

    fun list(src: SmbSource, dir: String): List<SmbEntry> = withShare(src) { share ->
        share.list(dir.toSmbPath())
            .asSequence()
            .filter { !it.fileName.startsWith('.') && !it.fileName.startsWith('$') }
            .map {
                SmbEntry(
                    name = it.fileName,
                    isDir = EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                    size = it.endOfFile,
                    created = it.creationTime.toEpochMillis(),
                    modified = it.lastWriteTime.toEpochMillis(),
                )
            }
            .toList()
    }

    fun openFile(src: SmbSource, path: String): SmbFile = withShare(src) { share ->
        share.openFile(
            path.toSmbPath(),
            setOf(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
    }

    private fun String.toSmbPath() = replace('/', '\\')
}
