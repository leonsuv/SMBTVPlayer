package com.example.smbtv

import java.text.DateFormat
import java.util.Date

/** "1:02:03" for times of an hour or more, otherwise "2:03". */
fun formatClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000L -> "%d MB".format(bytes / 1_000_000L)
    else -> "%d KB".format(bytes / 1_000L)
}

/** Locale-formatted date and time, or null for an unknown (zero) timestamp. */
fun formatDate(epochMs: Long): String? =
    if (epochMs <= 0) null else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

/** Offsets of JPEG start-of-image markers (FF D8 FF), e.g. the previews embedded in camera RAW files. */
fun jpegStarts(bytes: ByteArray, limit: Int = 64): List<Int> {
    val starts = ArrayList<Int>()
    var i = 0
    while (i + 2 < bytes.size && starts.size < limit) {
        if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD8.toByte() && bytes[i + 2] == 0xFF.toByte()) starts += i
        i++
    }
    return starts
}
