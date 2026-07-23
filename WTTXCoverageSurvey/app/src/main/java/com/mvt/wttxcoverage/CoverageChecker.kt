package com.mvt.wttxcoverage

data class CoverageResult(
    val favorable: Boolean,
    val lat: Double,
    val lon: Double,
    val matchedSite: Site?,
    val nearestSite: Site?,
    val nearestDistanceMeters: Double?
)

object CoverageChecker {

    fun evaluate(lat: Double, lon: Double, sites: List<Site>): CoverageResult {
        var matched: Site? = null
        for (site in sites) {
            if (GeoUtils.pointInPolygon(lat, lon, site.polygon)) {
                matched = site
                break
            }
        }

        if (matched != null) {
            return CoverageResult(
                favorable = true,
                lat = lat,
                lon = lon,
                matchedSite = matched,
                nearestSite = matched,
                nearestDistanceMeters = 0.0
            )
        }

        var nearest: Site? = null
        var nearestDist = Double.MAX_VALUE
        for (site in sites) {
            val d = GeoUtils.distanceMeters(lat, lon, site.lat, site.lon)
            if (d < nearestDist) {
                nearestDist = d
                nearest = site
            }
        }

        return CoverageResult(
            favorable = false,
            lat = lat,
            lon = lon,
            matchedSite = null,
            nearestSite = nearest,
            nearestDistanceMeters = if (nearest != null) nearestDist else null
        )
    }
}
