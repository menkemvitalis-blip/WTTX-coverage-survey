package com.mvt.wttxcoverage

import android.content.Context
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loads the bundled WTTX 1km coverage polygons (pre-converted from the
 * original Google Earth KML export) out of assets/coverage.json.
 */
object CoverageRepository {

    private var cache: List<Site>? = null

    fun loadSites(context: Context): List<Site> {
        cache?.let { return it }

        val json = context.assets.open("coverage.json").use { stream ->
            BufferedReader(InputStreamReader(stream)).readText()
        }

        val array = JSONArray(json)
        val sites = ArrayList<Site>(array.length())

        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val ringArray = o.getJSONArray("polygon")
            val ring = ArrayList<DoubleArray>(ringArray.length())
            for (j in 0 until ringArray.length()) {
                val pair = ringArray.getJSONArray(j)
                ring.add(doubleArrayOf(pair.getDouble(0), pair.getDouble(1))) // lon, lat
            }
            sites.add(
                Site(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    sn = o.optString("sn"),
                    lat = o.optDouble("lat"),
                    lon = o.optDouble("lon"),
                    status = o.optString("status"),
                    city = o.optString("city"),
                    cluster = o.optString("cluster"),
                    type = o.optString("type"),
                    polygon = ring
                )
            )
        }

        cache = sites
        return sites
    }
}
