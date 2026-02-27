package com.example.future_minds

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Point
import android.location.Geocoder
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.LayoutInflater
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.internal.wait
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var locationSearch: LocationSearch
    private lateinit var routeManager: RouteManager
    private var badZonesList = mutableListOf<Map<String, Any>>()
    private var dangerZoneOverlays = mutableListOf<Polygon>()
    private var locationOverlay: MyLocationNewOverlay ?= null

    private val locationPermissionRequest = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions.getOrDefault(android.Manifest.permission.ACCESS_FINE_LOCATION, false) -> {
                // Permission granted! Turn on the GPS.
                setupUserLocation()
            }
            else -> {
                android.widget.Toast.makeText(this, "Location permission is required to center the map.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.setBuiltInZoomControls(false)

        var report_bool=false;
        val report_btn= findViewById<Button>(R.id.button_report)
        report_btn.setOnClickListener { report_bool=true }

        val route_btn= findViewById<Button>(R.id.button_route)
        route_btn.setOnClickListener {
            if(locationSearch.pntBool) {
                calculateSafeRoute(locationSearch.pnt)
            }else Toast.makeText(this, "You must first select a place", Toast.LENGTH_LONG).show()
        }

        val modTestare= findViewById<Switch>(R.id.testare)
        var testare_bool=true
        modTestare?.setOnCheckedChangeListener({ _ , isChecked ->
            if (!isChecked) {
                testare_bool = true
                map.setBuiltInZoomControls(false)
            }
            else {
                testare_bool = false
                map.setBuiltInZoomControls(true)
            }
        })




        val mapController = map.controller
        mapController.setZoom(15.0)

        routeManager = RouteManager(this, map)
        val searchBar = findViewById<AutoCompleteTextView>(R.id.search_bar)
        val searchButton = findViewById<ImageButton>(R.id.btn_search)
        locationSearch = LocationSearch(this, map, searchBar, searchButton)

        // Setup Map Events (Long Press for reporting)
        val mapEventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p?.let {
                    if(testare_bool){
                        if(report_bool){
                            showReportDialog(it)
                            report_bool=false
                        }else{
                            locationSearch.zoomToLocation(it,"Pin")
                        }
                    }

                }
                return true
            }
        }
        map.overlays.add(MapEventsOverlay(mapEventsReceiver))




        listenForDangerZones()
        locationPermissionRequest.launch(arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }

    private fun setupUserLocation() {
        // 1. Create the GPS tracker
        val locationProvider = GpsMyLocationProvider(this)
        locationOverlay = MyLocationNewOverlay(locationProvider, map)

        // 2. Turn it on and add it to the map
        locationOverlay?.enableMyLocation()
        map.overlays.add(locationOverlay)

        // 3. Wait for the very first GPS signal, then instantly zoom the map to it
        locationOverlay?.runOnFirstFix {
            runOnUiThread {
                map.controller.setZoom(18.0) // 18.0 is a great street-level zoom
                map.controller.animateTo(locationOverlay!!.myLocation)
            }
        }
    }

    private fun listenForDangerZones() {

        val db = FirebaseFirestore.getInstance()
        db.collection("reports").addSnapshotListener { snapshots, e ->
            if (e != null) {
                Log.w("Firestore", "Listen failed.", e)
                return@addSnapshotListener
            }

            if (snapshots != null) {
                // Remove existing overlays
                dangerZoneOverlays.forEach { map.overlays.remove(it) }
                dangerZoneOverlays.clear()
                badZonesList.clear()

                for (document in snapshots) {
                    val lat = document.getDouble("lat") ?: 0.0
                    val lng = document.getDouble("lng") ?: 0.0
                    val radius = document.getDouble("radius") ?: 20.0
                    val type = document.getString("type") ?: "Unsafe Area"

                    val zoneData = mapOf(
                        "lat" to lat,
                        "lng" to lng,
                        "radius" to radius,
                        "type" to type
                    )
                    badZonesList.add(zoneData)
                    addDangerZoneToMap(GeoPoint(lat, lng), radius, type)
                }
                map.invalidate()
            }
        }
    }

    private fun showReportDialog(point: GeoPoint) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.raportari, null)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinner_issue_type)
        val seekBar = dialogView.findViewById<SeekBar>(R.id.seekbar_radius)
        val tvRadius = dialogView.findViewById<TextView>(R.id.tv_radius_label)

        var currentRadius = 20.0
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val radius = if (progress < 5) 5 else progress
                currentRadius = radius.toDouble()
                tvRadius.text = "Affected Area: ${radius}m"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Report") { dialog, _ ->
                val issueType = spinner.selectedItem.toString()
                saveReportToDatabase(point, currentRadius, issueType)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    private fun addDangerZoneToMap(center: GeoPoint, radius: Double, type: String) {
        val circle = Polygon()
        circle.points = Polygon.pointsAsCircle(center, radius)

        val color = when (type) {
            "Unsafe Area" -> Color.argb(100, 255, 0, 0)
            "Construction" -> Color.argb(100, 255, 165, 0)
            "Blocked Path" -> Color.argb(100, 0, 0, 0)
            else -> Color.argb(100, 255, 0, 0)
        }
        circle.fillColor = color
        circle.strokeColor = color
        circle.strokeWidth = 2.0f
        circle.title = "$type (${radius.toInt()}m)"

        map.overlays.add(circle)
        dangerZoneOverlays.add(circle)
    }

    private fun saveReportToDatabase(point: GeoPoint, radius: Double, type: String) {
        val db = FirebaseFirestore.getInstance()
        val report = hashMapOf(
            "lat" to point.latitude,
            "lng" to point.longitude,
            "radius" to radius,
            "type" to type,
            "timestamp" to Date()
        )
        db.collection("reports")
            .add(report)
            .addOnSuccessListener {
                Toast.makeText(this, "Report Saved to Cloud!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error saving: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    fun calculateSafeRoute(destination: GeoPoint) {
        val myLocation = locationOverlay?.myLocation
        if (myLocation != null) {
            Toast.makeText(this, "Calculating Safe Route...", Toast.LENGTH_SHORT).show()
            routeManager.getSafeRoute(myLocation, destination, badZonesList)
        } else {
            Toast.makeText(this, "Waiting for GPS...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        locationOverlay?.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        locationOverlay?.disableMyLocation()
    }
}
