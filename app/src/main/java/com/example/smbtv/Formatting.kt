package com.example.smbtv

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
