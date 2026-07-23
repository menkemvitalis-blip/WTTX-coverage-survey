package com.mvt.wttxcoverage

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var inputField: EditText
    private lateinit var resultCard: LinearLayout
    private lateinit var resultTitle: TextView
    private lateinit var resultDetail: TextView

    private var sites: List<Site> = emptyList()
    private var queryMarker: Marker? = null

    private val locationPermissionRequest = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        // osmdroid needs its config initialized before any MapView is inflated
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        inputField = findViewById(R.id.inputCoordinates)
        resultCard = findViewById(R.id.resultCard)
        resultTitle = findViewById(R.id.resultTitle)
        resultDetail = findViewById(R.id.resultDetail)

        setupMap()
        loadCoverageOnMap()

        findViewById<LinearLayout>(R.id.btnCheck).setOnClickListener { handleCheck() }
        findViewById<LinearLayout>(R.id.btnLocate).setOnClickListener { useMyLocation() }

        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                handleCheck()
                true
            } else {
                false
            }
        }

        handleIncomingLink(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingLink(intent)
    }

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(11.5)
        // Center roughly over Douala/Yaoundé coverage area; refined once sites load.
        map.controller.setCenter(GeoPoint(4.05, 9.75))
    }

    private fun loadCoverageOnMap() {
        sites = CoverageRepository.loadSites(this)
        if (sites.isEmpty()) return

        for (site in sites) {
            val ring = site.polygon.map { GeoPoint(it[1], it[0]) } // lat, lon
            val polygon = Polygon(map).apply {
                points = ring
                fillColor = ContextCompat.getColor(this@MainActivity, R.color.polygon_fill)
                strokeColor = ContextCompat.getColor(this@MainActivity, R.color.polygon_stroke)
                strokeWidth = 2.5f
                title = site.name
            }
            map.overlays.add(polygon)

            val marker = Marker(map).apply {
                position = GeoPoint(site.lat, site.lon)
                icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_marker_site)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = site.name
                snippet = "${site.city} · ${site.cluster} · ${site.status}"
            }
            map.overlays.add(marker)
        }

        // Center on the average of all sites for a sensible initial view.
        val avgLat = sites.map { it.lat }.average()
        val avgLon = sites.map { it.lon }.average()
        map.controller.setCenter(GeoPoint(avgLat, avgLon))
        map.invalidate()
    }

    private fun handleCheck() {
        val raw = inputField.text.toString().trim()
        if (raw.isEmpty()) {
            Toast.makeText(this, "Enter coordinates or a map link first", Toast.LENGTH_SHORT).show()
            return
        }
        processInput(raw)
    }

    /** Shared by the "Check Coverage" button and by links the app was opened with. */
    private fun processInput(raw: String) {
        if (GeoUtils.looksLikeShortLink(raw)) {
            CoroutineScope(Dispatchers.Main).launch {
                val resolved = GeoUtils.resolveShortLink(raw)
                val coords = GeoUtils.parseCoordinates(resolved)
                if (coords == null) {
                    Toast.makeText(this@MainActivity, "Couldn't read coordinates from that link", Toast.LENGTH_SHORT).show()
                } else {
                    runCheck(coords.first, coords.second)
                }
            }
        } else {
            val coords = GeoUtils.parseCoordinates(raw)
            if (coords == null) {
                Toast.makeText(this, "Couldn't understand that input. Try: 4.0512, 9.7679", Toast.LENGTH_LONG).show()
            } else {
                runCheck(coords.first, coords.second)
            }
        }
    }

    /** Handles the app being opened via a geo: URI or a Google Maps link ("Open with…"). */
    private fun handleIncomingLink(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        val raw = uri.toString()
        inputField.setText(raw)
        processInput(raw)
    }

    private fun runCheck(lat: Double, lon: Double) {
        val result = CoverageChecker.evaluate(lat, lon, sites)
        showResult(result)
        placeQueryMarker(lat, lon, result.favorable)
        map.controller.animateTo(GeoPoint(lat, lon))
        map.controller.setZoom(15.5)
    }

    private fun showResult(result: CoverageResult) {
        resultCard.visibility = View.VISIBLE
        if (result.favorable) {
            resultCard.setBackgroundResource(R.drawable.bg_result_favorable)
            resultTitle.text = "✔ FAVORABLE — inside coverage"
            val site = result.matchedSite
            resultDetail.text = if (site != null) {
                "Covered by ${site.name} · ${site.city} · ${site.cluster} (${site.status})"
            } else {
                "This position falls within a WTTX 1km coverage zone."
            }
        } else {
            resultCard.setBackgroundResource(R.drawable.bg_result_unfavorable)
            resultTitle.text = "✘ NOT FAVORABLE — outside coverage"
            val nearest = result.nearestSite
            val dist = result.nearestDistanceMeters
            resultDetail.text = if (nearest != null && dist != null) {
                String.format(
                    Locale.US,
                    "Outside all coverage zones. Nearest site: %s (%s) — %.0f m away",
                    nearest.name, nearest.city, dist
                )
            } else {
                "Outside all known WTTX coverage zones."
            }
        }
    }

    private fun placeQueryMarker(lat: Double, lon: Double, favorable: Boolean) {
        queryMarker?.let { map.overlays.remove(it) }
        val marker = Marker(map).apply {
            position = GeoPoint(lat, lon)
            icon = ContextCompat.getDrawable(
                this@MainActivity,
                if (favorable) R.drawable.ic_pin_favorable else R.drawable.ic_pin_unfavorable
            )
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = if (favorable) "Surveyed point — Favorable" else "Surveyed point — Not favorable"
        }
        map.overlays.add(marker)
        queryMarker = marker
        map.invalidate()
    }

    private fun useMyLocation() {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                locationPermissionRequest
            )
            return
        }

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        var best: android.location.Location? = null
        for (provider in providers) {
            @Suppress("MissingPermission")
            val loc = lm.getLastKnownLocation(provider) ?: continue
            if (best == null || loc.accuracy < best.accuracy) {
                best = loc
            }
        }

        if (best == null) {
            Toast.makeText(this, "No recent GPS fix available — move to an open area and retry", Toast.LENGTH_LONG).show()
            return
        }

        inputField.setText(String.format(Locale.US, "%.6f, %.6f", best.latitude, best.longitude))
        runCheck(best.latitude, best.longitude)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locationPermissionRequest && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            useMyLocation()
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }
}
