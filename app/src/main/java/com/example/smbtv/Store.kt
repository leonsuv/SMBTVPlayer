package com.example.smbtv

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SmbSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 445,
    val share: String,
    val domain: String = "",
    val user: String = "",
    val password: String = "",
)

/** A folder pinned to the start screen; [path] is relative to the share root. */
data class Favorite(
    val id: String = UUID.randomUUID().toString(),
    val sourceId: String,
    val path: String,
    val name: String,
)

enum class SortKey(val label: String) {
    NAME("Name"),
    CREATED("Date created"),
    MODIFIED("Date modified"),
}

/** How the browser orders a folder; folders always stay above files. */
data class Sort(val key: SortKey = SortKey.NAME, val descending: Boolean = false) {
    val label: String get() = "${key.label} ${if (descending) "↓" else "↑"}"

    fun comparator(): Comparator<SmbEntry> {
        val byKey: Comparator<SmbEntry> = when (key) {
            SortKey.NAME -> compareBy { it.name.lowercase() }
            SortKey.CREATED -> compareBy<SmbEntry> { it.created }.thenBy { it.name.lowercase() }
            SortKey.MODIFIED -> compareBy<SmbEntry> { it.modified }.thenBy { it.name.lowercase() }
        }
        return compareByDescending<SmbEntry> { it.isDir }.then(if (descending) byKey.reversed() else byKey)
    }
}

/** smb://<sourceId>/<path>; the source id is resolved back to credentials by [SmbDataSource]. */
fun smbUri(sourceId: String, path: String): Uri =
    Uri.Builder().scheme("smb").authority(sourceId)
        .apply { path.split('/').filter { it.isNotEmpty() }.forEach { appendPath(it) } }
        .build()

/** Small SharedPreferences-backed store for shares, favorites and resume positions. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("smb_tv", Context.MODE_PRIVATE)

    fun sources(): List<SmbSource> {
        val arr = JSONArray(prefs.getString(KEY_SOURCES, "[]"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            SmbSource(
                id = o.getString("id"),
                name = o.getString("name"),
                host = o.getString("host"),
                port = o.optInt("port", 445),
                share = o.getString("share"),
                domain = o.optString("domain"),
                user = o.optString("user"),
                password = o.optString("password"),
            )
        }
    }

    fun addSource(source: SmbSource) = saveSources(sources() + source)

    /** Replaces the saved share with the same id. */
    fun updateSource(source: SmbSource) = saveSources(sources().map { if (it.id == source.id) source else it })

    /** Removes the share and every favorite that points into it. */
    fun removeSource(id: String) {
        saveSources(sources().filterNot { it.id == id })
        saveFavorites(favorites().filterNot { it.sourceId == id })
    }

    /** Moves the share [delta] places up (negative) or down; returns false at either end. */
    fun moveSource(id: String, delta: Int): Boolean =
        sources().moved({ it.id == id }, delta)?.also(::saveSources) != null

    private fun saveSources(list: List<SmbSource>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("host", s.host)
                    .put("port", s.port)
                    .put("share", s.share)
                    .put("domain", s.domain)
                    .put("user", s.user)
                    .put("password", s.password)
            )
        }
        prefs.edit().putString(KEY_SOURCES, arr.toString()).apply()
    }

    fun favorites(): List<Favorite> {
        val arr = JSONArray(prefs.getString(KEY_FAVORITES, "[]"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Favorite(
                id = o.getString("id"),
                sourceId = o.getString("sourceId"),
                path = o.getString("path"),
                name = o.getString("name"),
            )
        }
    }

    fun favorite(sourceId: String, path: String): Favorite? =
        favorites().firstOrNull { it.sourceId == sourceId && it.path == path }

    fun addFavorite(favorite: Favorite) = saveFavorites(favorites() + favorite)

    fun renameFavorite(id: String, name: String) =
        saveFavorites(favorites().map { if (it.id == id) it.copy(name = name) else it })

    fun removeFavorite(id: String) = saveFavorites(favorites().filterNot { it.id == id })

    /** Moves the favorite [delta] places up (negative) or down; returns false at either end. */
    fun moveFavorite(id: String, delta: Int): Boolean =
        favorites().moved({ it.id == id }, delta)?.also(::saveFavorites) != null

    private fun saveFavorites(list: List<Favorite>) {
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("sourceId", f.sourceId)
                    .put("path", f.path)
                    .put("name", f.name)
            )
        }
        prefs.edit().putString(KEY_FAVORITES, arr.toString()).apply()
    }

    fun sort(): Sort = Sort(
        key = runCatching { SortKey.valueOf(prefs.getString(KEY_SORT_KEY, null)!!) }.getOrDefault(SortKey.NAME),
        descending = prefs.getBoolean(KEY_SORT_DESC, false),
    )

    fun saveSort(sort: Sort) =
        prefs.edit().putString(KEY_SORT_KEY, sort.key.name).putBoolean(KEY_SORT_DESC, sort.descending).apply()

    /** Resume position in ms for a file URI, or 0. */
    fun position(uri: String): Long = prefs.getLong("pos:$uri", 0L)

    /** Saves the resume position; near-zero positions clear the entry. */
    fun savePosition(uri: String, positionMs: Long) {
        prefs.edit().apply {
            if (positionMs > 5_000) putLong("pos:$uri", positionMs) else remove("pos:$uri")
        }.apply()
    }

    private companion object {
        const val KEY_SOURCES = "sources"
        const val KEY_FAVORITES = "favorites"
        const val KEY_SORT_KEY = "sort_key"
        const val KEY_SORT_DESC = "sort_desc"
    }
}

/** This list with the first [match] moved by [delta], or null if it is missing or would leave the list. */
internal fun <T> List<T>.moved(match: (T) -> Boolean, delta: Int): List<T>? {
    val from = indexOfFirst(match)
    val to = from + delta
    if (from < 0 || to !in indices) return null
    return toMutableList().apply { add(to, removeAt(from)) }
}
