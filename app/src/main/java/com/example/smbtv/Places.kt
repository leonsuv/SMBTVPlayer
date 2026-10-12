package com.example.smbtv

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** HTTP User-Agent for OpenStreetMap services, whose usage policy requires an identifying one. */
const val OSM_USER_AGENT = "SMBTVPlayer/1.1 (Android TV photo viewer)"

/** Turns photo GPS coordinates into "City, State, Country". */
object Places {

    private val cache = HashMap<String, String?>()

    /** The place for these coordinates in the device language, or null if it can't be found. */
    suspend fun lookup(context: Context, lat: Double, lon: Double): String? {
        // ~100 m grid: photos from one spot share a lookup.
        val key = "%.3f,%.3f".format(Locale.US, lat, lon)
        synchronized(cache) { if (key in cache) return cache[key] }
        val place = withContext(Dispatchers.IO) {
            runCatching { fromGeocoder(context, lat, lon) }.getOrNull()
                ?: runCatching { fromNominatim(lat, lon) }.getOrNull()
        }
        synchronized(cache) { cache[key] = place }
        return place
    }

    @Suppress("DEPRECATION") // The listener variant needs API 33; this runs on a background thread anyway.
    private fun fromGeocoder(context: Context, lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        val a = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return null
        return join(a.locality ?: a.subAdminArea, a.adminArea, a.countryName)
    }

    /** OpenStreetMap's reverse geocoder, for TV boxes without a platform geocoder. */
    private fun fromNominatim(lat: Double, lon: Double): String? {
        val url = URL(
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=10" +
                "&lat=$lat&lon=$lon&accept-language=${Locale.getDefault().toLanguageTag()}"
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("User-Agent", OSM_USER_AGENT)
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            if (conn.responseCode != 200) return null
            val address = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONObject("address") ?: return null
            fun field(vararg names: String) = names.firstNotNullOfOrNull { address.optString(it).takeIf(String::isNotEmpty) }
            return join(
                field("city", "town", "village", "municipality", "county"),
                field("state", "region", "province"),
                field("country"),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun join(vararg parts: String?) =
        parts.filterNotNull().filter { it.isNotBlank() }.distinct().joinToString(", ").ifEmpty { null }
}
