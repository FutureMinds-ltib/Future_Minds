package com.example.future_minds // Update package name!

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.io.IOException

class RouteManager(private val context: Context, private val map: MapView) {

    private val client = OkHttpClient()
    // TODO: PASTE YOUR API KEY HERE
    private val apiKey = "eyJvcmciOiI1YjNjZTM1OTc4NTExMTAwMDFjZjYyNDgiLCJpZCI6IjFhYzIyMDdmMDlhMjRjZmM4YmYwZjVhMDIxNzg2OWJmIiwiaCI6Im11cm11cjY0In0="

    private var currentRouteOverlay: Polyline? = null

    // The main function to calculate the path
    fun getSafeRoute(start: GeoPoint, end: GeoPoint, badZones: List<Map<String, Any>>) {

        // 1. Prepare the JSON Body
        val jsonBody = JSONObject()

        // Coordinates: ORS expects [Longitude, Latitude]
        val coordinates = JSONArray()
        coordinates.put(JSONArray().put(start.longitude).put(start.latitude))
        coordinates.put(JSONArray().put(end.longitude).put(end.latitude))
        jsonBody.put("coordinates", coordinates)

        // 2. Add Avoidance Zones (THE FIXED PART)
        if (badZones.isNotEmpty()) {
            val options = JSONObject()
            val avoidPolygons = JSONObject()

            // REQUIRED: Tell ORS this is a "MultiPolygon"
            avoidPolygons.put("type", "MultiPolygon")

            val multiPolygonCoordinates = JSONArray()

            for (zone in badZones) {
                // Safely get data
                val lat = (zone["lat"] as? Number)?.toDouble() ?: continue
                val lng = (zone["lng"] as? Number)?.toDouble() ?: continue

                // Radius: 0.0003 degrees is roughly 30 meters
                val r = 0.0003

                // A single Polygon in GeoJSON is an array of rings (usually just one ring)
                val polygonRing = JSONArray()

                // Top Right
                polygonRing.put(JSONArray().put(lng + r).put(lat + r))
                // Bottom Right
                polygonRing.put(JSONArray().put(lng + r).put(lat - r))
                // Bottom Left
                polygonRing.put(JSONArray().put(lng - r).put(lat - r))
                // Top Left
                polygonRing.put(JSONArray().put(lng - r).put(lat + r))
                // Close the loop (Must be same as first point)
                polygonRing.put(JSONArray().put(lng + r).put(lat + r))

                // Wrap the ring in a container (because polygons can have holes)
                val polygonContainer = JSONArray()
                polygonContainer.put(polygonRing)

                // Add to the list of polygons
                multiPolygonCoordinates.put(polygonContainer)
            }

            // REQUIRED: The key must be "coordinates", not "polygons"
            avoidPolygons.put("coordinates", multiPolygonCoordinates)

            options.put("avoid_polygons", avoidPolygons)
            jsonBody.put("options", options)
        }

        // 3. Send the Request
        // Log the JSON so we can debug if it happens again
        android.util.Log.d("RouteManager", "Sending JSON: $jsonBody")

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonBody.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url("https://api.openrouteservice.org/v2/directions/foot-walking/geojson")
            .post(body)
            .addHeader("Authorization", apiKey) // Make sure API Key is set!
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                showToast("Failed to connect: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    if (responseData != null) {
                        Handler(Looper.getMainLooper()).post {
                            drawRouteOnMap(responseData)
                        }
                    }
                } else {
                    // Log the actual error message from the server
                    val errorBody = response.body?.string()
                    android.util.Log.e("RouteManager", "Server Error: $errorBody")
                    showToast("Error ${response.code}: Check Logcat for details")
                }
            }
        })
    }

    private fun drawRouteOnMap(jsonString: String) {
        try {
            // Remove old route
            if (currentRouteOverlay != null) {
                map.overlays.remove(currentRouteOverlay)
            }

            val json = JSONObject(jsonString)
            val features = json.getJSONArray("features")
            val geometry = features.getJSONObject(0).getJSONObject("geometry")
            val coords = geometry.getJSONArray("coordinates")

            val routePoints = ArrayList<GeoPoint>()

            for (i in 0 until coords.length()) {
                val point = coords.getJSONArray(i)
                // GeoJSON is [Lon, Lat], OSMDroid needs [Lat, Lon]
                val lon = point.getDouble(0)
                val lat = point.getDouble(1)
                routePoints.add(GeoPoint(lat, lon))
            }

            // Create the line
            currentRouteOverlay = Polyline()
            currentRouteOverlay?.setPoints(routePoints)
            currentRouteOverlay?.outlinePaint?.color = Color.BLUE
            currentRouteOverlay?.outlinePaint?.strokeWidth = 15f // Thick line

            map.overlays.add(currentRouteOverlay)
            map.invalidate()

            showToast("Safe Route Calculated!")

        } catch (e: Exception) {
            e.printStackTrace()
            showToast("Error parsing route")
        }
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}