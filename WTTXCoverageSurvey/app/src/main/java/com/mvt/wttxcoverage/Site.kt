package com.mvt.wttxcoverage

/**
 * A single WTTX BTS site and its ~1km coverage polygon.
 * [polygon] is a closed ring of (lon, lat) pairs, matching KML coordinate order.
 */
data class Site(
    val id: String,
    val name: String,
    val sn: String,
    val lat: Double,
    val lon: Double,
    val status: String,
    val city: String,
    val cluster: String,
    val type: String,
    val polygon: List<DoubleArray> // each element = [lon, lat]
)
