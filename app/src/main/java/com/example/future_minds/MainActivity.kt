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

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView

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
        listenForDangerZones()

    }






    private fun listenForDangerZones() {
        val db = FirebaseFirestore.getInstance()

        // "reports" must match the collection name you used in saveReportToDatabase
        db.collection("reports")
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.w("Firestore", "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshots != null) {
                    // Optional: Clear old overlays here if you want to avoid duplicates
                    // map.overlays.clear()
                    // (But be careful, this removes the map events receiver too!
                    //  For V1, let's just draw on top.)

                    for (document in snapshots) {
                        // 1. Get the data from the cloud
                        val lat = document.getDouble("lat") ?: 0.0
                        val lng = document.getDouble("lng") ?: 0.0
                        val radius = document.getDouble("radius") ?: 20.0
                        val type = document.getString("type") ?: "General Hazard"

                        // 2. Create the GeoPoint
                        val point = GeoPoint(lat, lng)

                        // 3. Draw it on the map!
                        addDangerZoneToMap(point, radius, type)
                    }

                    // Refresh map to show new circles
                    map.invalidate()
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




    // 4. Handle Lifecycle to prevent battery drain
    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }
}