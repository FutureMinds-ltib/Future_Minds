package com.example.future_minds
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import android.graphics.Color
import android.widget.Toast
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polygon
import android.app.AlertDialog
import android.view.LayoutInflater
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import android.location.Geocoder
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var locationSearch: LocationSearch
    private lateinit var routeManager: RouteManager
    private var badZonesList = mutableListOf<Map<String, Any>>()
    private lateinit var locationOverlay: MyLocationNewOverlay
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Permission granted: Turn on the blue dot
                setupLocationOverlay()
            } else {
                // Permission denied: Show a message
                Toast.makeText(this, "Location permission is required for navigation!", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1. IMPORTANT: Initialize OSMDroid Configuration
        // This handles caching and User-Agent to prevent getting banned by OSM servers
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))

        setContentView(R.layout.activity_main)

        // 2. Setup the MapView
        map = findViewById(R.id.map)

        // Use MAPNIK (The standard OSM look)
        map.setTileSource(TileSourceFactory.MAPNIK)

        // Enable pinch-to-zoom and standard controls
        map.setMultiTouchControls(true)

        // 3. Set the Starting Point
        val mapController = map.controller
        mapController.setZoom(15.0)

        val startPoint = GeoPoint(44.420483, 26.061319)
        mapController.setCenter(startPoint)

        routeManager = RouteManager(this, map)



        val mapEventsReceiver = object : MapEventsReceiver {

            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                // Close info windows or deselect items if needed
                return false
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                // "p" contains the latitude and longitude of the touch
                p?.let { point ->
                    showReportDialog(point)
                }
                return true
            }
        }

        // 2. Add the Receiver to the Map as an Overlay
        val eventsOverlay = MapEventsOverlay(mapEventsReceiver)
        map.overlays.add(eventsOverlay)


        val searchBar = findViewById<AutoCompleteTextView>(R.id.search_bar)
        val searchButton = findViewById<ImageButton>(R.id.btn_search)

        locationSearch = LocationSearch(this, map, searchBar, searchButton)

        listenForDangerZones()
        checkLocationPermission()
        routeManager = RouteManager(this, map)




    }






    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // We already have permission!
            setupLocationOverlay()
        } else {
            // We need to ask for it
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }



    private fun setupLocationOverlay() {
        // 4. Create the "Blue Dot" Overlay
        // GpsMyLocationProvider uses the phone's GPS automatically
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)

        // Enable it
        locationOverlay.enableMyLocation()

        // Optional: Follow the user (move map as they walk)
        // locationOverlay.enableFollowLocation()

        // Add to map
        map.overlays.add(locationOverlay)

        // Refresh map
        map.invalidate()
    }



    private fun listenForDangerZones() {
        val db = FirebaseFirestore.getInstance()
        db.collection("reports").addSnapshotListener { snapshots, e ->
            if (snapshots != null) {
                badZonesList.clear() // Clear old list

                for (document in snapshots) {
                    val lat = document.getDouble("lat") ?: 0.0
                    val lng = document.getDouble("lng") ?: 0.0
                    val radius = document.getDouble("radius") ?: 20.0

                    // Add to our list for the routing engine
                    val zoneData = mapOf(
                        "lat" to lat,
                        "lng" to lng,
                        "radius" to radius
                    )
                    badZonesList.add(zoneData)

                    // Draw on map (keep your existing visual code here)
                    addDangerZoneToMap(GeoPoint(lat, lng), radius, "Danger")
                }
            }
        }
    }




    val mapEventsReceiver = object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
            return false
        }

        override fun longPressHelper(p: GeoPoint?): Boolean {
            p?.let { point ->
                // Instead of drawing immediately, show the dialog
                showReportDialog(point)
            }
            return true
        }
    }

    // 2. The Function to Show the Popup
    private fun showReportDialog(point: GeoPoint) {
        // Inflate the custom layout
        val dialogView = LayoutInflater.from(this).inflate(R.layout.raportari, null)

        val spinner = dialogView.findViewById<Spinner>(R.id.spinner_issue_type)
        val seekBar = dialogView.findViewById<SeekBar>(R.id.seekbar_radius)
        val tvRadius = dialogView.findViewById<TextView>(R.id.tv_radius_label)

        // Handle Slider Changes
        var currentRadius = 20.0 // Default
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Ensure minimum radius of 5m so it's visible
                val radius = if (progress < 5) 5 else progress
                currentRadius = radius.toDouble()
                tvRadius.text = "Affected Area: ${radius}m"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Build the Dialog
        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Report") { dialog, _ ->
                val issueType = spinner.selectedItem.toString()

                // Now we have everything we need: Location, Type, and Size
                addDangerZoneToMap(point, currentRadius, issueType)
                saveReportToDatabase(point, currentRadius, issueType)

                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    // 3. Updated Map Drawing Function
    private fun addDangerZoneToMap(center: GeoPoint, radius: Double, type: String) {
        val circle = Polygon()

        // Create the circle with the USER SELECTED radius
        circle.points = Polygon.pointsAsCircle(center, radius)

        // Optional: Color code based on the problem type!
        val color = when (type) {
            "Unsafe Area" -> Color.argb(100, 255, 0, 0)   // Red
            "Construction" -> Color.argb(100, 255, 165, 0) // Orange
            "Blocked Path" -> Color.argb(100, 0, 0, 0)     // Black
            else -> Color.argb(100, 255, 0, 0)
        }
        circle.fillColor = color
        circle.strokeColor = color // Or make the border solid
        circle.strokeWidth = 2.0f
        circle.title = "$type (${radius.toInt()}m)" // Visible on tap

        map.overlays.add(circle)
        map.invalidate()
    }

    // 4. Updated Database Function
    private fun saveReportToDatabase(point: GeoPoint, radius: Double, type: String) {
        val db = FirebaseFirestore.getInstance()
        // This is where you send the structured data to Firebase
        val report = hashMapOf(
            "lat" to point.latitude,
            "lng" to point.longitude,
            "radius" to radius,
            "type" to type,
            "timestamp" to Date() // Adds the current time
        )
        db.collection("reports")
            .add(report)
            .addOnSuccessListener { documentReference ->
                Log.d("Firestore", "DocumentSnapshot added with ID: ${documentReference.id}")
                Toast.makeText(this, "Report Saved to Cloud!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Log.w("Firestore", "Error adding document", e)
                Toast.makeText(this, "Error saving: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }



    private fun searchLocation(query: String) {
        if (query.isEmpty()) return

        // Geocoding needs to run on a background thread to not freeze the app
        Thread {
            try {
                val geocoder = Geocoder(this, Locale.getDefault())

                // Get top 1 result
                // Note: In newer Android versions, this requires a listener,
                // but this synchronous method works fine for simple use cases.
                val results = geocoder.getFromLocationName(query, 4)

                if (results != null && results.isNotEmpty()) {
                    val location = results[0]
                    val geoPoint = GeoPoint(location.latitude, location.longitude)

                    // Update UI on the main thread
                    runOnUiThread {
                        // Move map to the location
                        map.controller.animateTo(geoPoint)
                        map.controller.setZoom(18.0)

                        // Optional: Show a marker
                        addMarker(geoPoint, query)

                        Toast.makeText(this, "Found: ${location.featureName ?: query}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Search error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
    private fun addMarker(point: GeoPoint, title: String) {
        val marker = org.osmdroid.views.overlay.Marker(map)
        marker.position = point
        marker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
        marker.title = title
        map.overlays.add(marker)
        map.invalidate()
    }



    fun calculateSafeRoute(destination: GeoPoint) {
        // 1. Find my current location (Blue Dot)
        val myLocationOverlay = map.overlays.firstOrNull { it is MyLocationNewOverlay } as? MyLocationNewOverlay
        val myLocation = myLocationOverlay?.myLocation

        if (myLocation != null) {
            Toast.makeText(this, "Calculating Safe Route...", Toast.LENGTH_SHORT).show()

            // 2. Ask RouteManager to do the math
            routeManager.getSafeRoute(myLocation, destination, badZonesList)
        } else {
            Toast.makeText(this, "Waiting for GPS... Try again in a moment.", Toast.LENGTH_SHORT).show()
        }
    }


    // 4. Handle Lifecycle to prevent battery drain
    override fun onResume() {
        super.onResume()
        map.onResume()
        if (::locationOverlay.isInitialized) {
            locationOverlay.enableMyLocation()
        }
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        if (::locationOverlay.isInitialized) {
            locationOverlay.disableMyLocation()
        }
    }
}