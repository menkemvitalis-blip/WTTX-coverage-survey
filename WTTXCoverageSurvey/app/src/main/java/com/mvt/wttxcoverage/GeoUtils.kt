package com.mvt.wttxcoverage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {

    private val DECIMAL_PAIR = Regex("(-?\\d{1,3}\\.\\d{3,})\\s*,\\s*(-?\\d{1,3}\\.\\d{3,})")
    private val AT_PATTERN = Regex("@(-?\\d{1,3}\\.\\d{3,}),(-?\\d{1,3}\\.\\d{3,})")
    private val QUERY_LL = Regex("[?&](?:q|ll|query)=(-?\\d{1,3}\\.\\d{3,}),(-?\\d{1,3}\\.\\d{3,})")
    private val GEO_URI = Regex("geo:(-?\\d{1,3}\\.\\d{3,}),(-?\\d{1,3}\\.\\d{3,})")

    /**
     * Extracts a (lat, lon) pair from free text: a raw "lat, lon" pair,
     * a geo: URI, or a Google Maps style link containing @lat,lon or ?q=lat,lon.
     * Short links (maps.app.goo.gl, goo.gl) are NOT resolved here -- use
     * [resolveShortLink] first if the text looks like a shortened URL.
     */
    fun parseCoordinates(rawInput: String): Pair<Double, Double>? {
        val text = rawInput.trim()
        if (text.isEmpty()) return null

        GEO_URI.find(text)?.let {
            return toLatLon(it.groupValues[1], it.groupValues[2])
        }
        AT_PATTERN.find(text)?.let {
            return toLatLon(it.groupValues[1], it.groupValues[2])
        }
        QUERY_LL.find(text)?.let {
            return toLatLon(it.groupValues[1], it.groupValues[2])
        }
        // Plain "lat, lon" typed by the user (only if it isn't a URL with other numbers)
        if (!text.contains("http")) {
            DECIMAL_PAIR.find(text)?.let {
                return toLatLon(it.groupValues[1], it.groupValues[2])
            }
        } else {
            // Last resort: first decimal pair found anywhere in the URL
            DECIMAL_PAIR.find(text)?.let {
                return toLatLon(it.groupValues[1], it.groupValues[2])
            }
        }
        return null
    }

    private fun toLatLon(a: String, b: String): Pair<Double, Double>? {
        val lat = a.toDoubleOrNull() ?: return null
        val lon = b.toDoubleOrNull() ?: return null
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null
        return lat to lon
    }

    fun looksLikeShortLink(text: String): Boolean {
        return text.contains("goo.gl") || text.contains("maps.app")
    }

    /**
     * Follows HTTP redirects for shortened map links (e.g. maps.app.goo.gl/xxx)
     * to reveal the full URL containing coordinates. Runs on IO dispatcher.
     */
    suspend fun resolveShortLink(shortUrl: String, maxHops: Int = 5): String = withContext(Dispatchers.IO) {
        var current = shortUrl
        repeat(maxHops) {
            try {
                val conn = URL(current).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (code in 300..399 && !location.isNullOrEmpty()) {
                    current = location
                } else {
                    return@withContext current
                }
            } catch (e: Exception) {
                return@withContext current
            }
        }
        current
    }

    /**
     * Standard ray-casting point-in-polygon test.
     * [ring] is a list of [lon, lat] pairs (matching KML/GeoJSON order).
     */
    fun pointInPolygon(lat: Double, lon: Double, ring: List<DoubleArray>): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val xi = ring[i][0]; val yi = ring[i][1]
            val xj = ring[j][0]; val yj = ring[j][1]
            val intersects = ((yi > lat) != (yj > lat)) &&
                (lon < (xj - xi) * (lat - yi) / (yj - yi + 1e-12) + xi)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    /** Distance in meters between two lat/lon points (haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
