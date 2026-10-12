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

/** smb://<sourceId>/<path>; the source id is resolved back to credentials by [SmbDataSource]. */
fun smbUri(sourceId: String, path: String): Uri =
    Uri.Builder().scheme("smb").authority(sourceId)
        .apply { path.split('/').filter { it.isNotEmpty() }.forEach { appendPath(it) } }
        .build()

/** Small SharedPreferences-backed store for shares and resume positions. */
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

    fun removeSource(source: SmbSource) = saveSources(sources() - source)

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
    }
}
