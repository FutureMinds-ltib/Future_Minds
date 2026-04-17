package com.example.future_minds

import android.content.Context
import android.graphics.DashPathEffect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.graphics.toColorInt
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.io.IOException
import java.util.Locale
import kotlin.math.*

class RouteManager(private val context: Context, private val map: MapView) {

    private val client = OkHttpClient()
    private val apiKey = "eyJvcmciOiI1YjNjZTM1OTc4NTExMTAwMDFjZjYyNDgiLCJpZCI6IjFhYzIyMDdmMDlhMjRjZmM4YmYwZjVhMDIxNzg2OWJmIiwiaCI6Im11cm11cjY0In0="
    
    var isBusModeActive: Boolean = false
    
    private var safeRouteOverlay: Polyline? = null
    private var fastRouteOverlay: Polyline? = null

    fun getSafeRoute(start: GeoPoint, end: GeoPoint, badZones: List<Map<String, Any>>) {
        // Clear previous routes
        clearRoutes()

        val distanceKm = calculateDistance(start, end)
        if (distanceKm > 100) {
            showToast("Destination too far: ${String.format(Locale.US, "%.1f", distanceKm)} km")
            return
        }

        // 1. Fetch the Fast (Risky) Route
        fetchRoute(start, end, emptyList(), isSafeAttempt = false)

        // 2. Fetch the Safe Route (only if there are zones to avoid)
        if (badZones.isNotEmpty()) {
            fetchRoute(start, end, badZones, isSafeAttempt = true)
        }
    }

    private fun clearRoutes() {
        safeRouteOverlay?.let { map.overlays.remove(it) }
        fastRouteOverlay?.let { map.overlays.remove(it) }
        safeRouteOverlay = null
        fastRouteOverlay = null
        map.invalidate()
    }

    private fun fetchRoute(start: GeoPoint, end: GeoPoint, badZones: List<Map<String, Any>>, isSafeAttempt: Boolean) {
        val jsonBody = JSONObject()
        val coordinates = JSONArray().apply {
            put(JSONArray().put(start.longitude).put(start.latitude))
            put(JSONArray().put(end.longitude).put(end.latitude))
        }
        jsonBody.put("coordinates", coordinates)

        if (isSafeAttempt && badZones.isNotEmpty()) {
            val options = JSONObject()
            val avoidPolygons = JSONObject().apply {
                put("type", "MultiPolygon")
                val multiPolygonCoordinates = JSONArray()
                for (zone in badZones) {
                    val lat = (zone["lat"] as? Number)?.toDouble() ?: continue
                    val lng = (zone["lon"] as? Number)?.toDouble() ?: continue
                    val radius = (zone["radius"] as? Number)?.toDouble() ?: 20.0
                    val latDeg = radius / 111320.0
                    val lngDeg = radius / (111320.0 * cos(Math.toRadians(lat)))
                    val ring = JSONArray()
                    for (i in 0..8) {
                        val angle = Math.toRadians(i * 45.0)
                        ring.put(JSONArray().put(lng + lngDeg * cos(angle)).put(lat + latDeg * sin(angle)))
                    }
                    multiPolygonCoordinates.put(JSONArray().put(ring))
                }
                put("coordinates", multiPolygonCoordinates)
            }
            options.put("avoid_polygons", avoidPolygons)
            jsonBody.put("options", options)
        }

        val profile = if (isBusModeActive) "driving-car" else "foot-walking"
        val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://api.openrouteservice.org/v2/directions/$profile/geojson")
            .post(body).addHeader("Authorization", apiKey).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Network error: Check your internet connection.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    if (responseData != null) {
                        Handler(Looper.getMainLooper()).post {
                            processAndDrawRoute(responseData,isSafeAttempt)
                        }
                    }
                } else {
                    val errorCode = response.code
                    val errorBody = response.body?.string()
                    android.util.Log.e("RouteManager", "API Error $errorCode: $errorBody")

                    // Handle specific API limits and errors safely on the Main Thread
                    Handler(Looper.getMainLooper()).post {
                        var specificErrorMessage: String? = null
                        if (errorBody != null) {
                            try {
                                val jsonError = org.json.JSONObject(errorBody)
                                val errorObj = jsonError.optJSONObject("error")
                                if (errorObj != null) {
                                    val internalCode = errorObj.optInt("code")
                                    val internalMessage = errorObj.optString("message", "")

                                    // 2. Catch our specific profile bug
                                    if (internalCode == 2003 && internalMessage.contains("profile")) {
                                        specificErrorMessage = "The routing server is temporarily down. Please try again later."
                                    }
                                }
                            } catch (e: Exception) {
                                // If it's not valid JSON, just ignore and fall back to HTTP codes
                                e.printStackTrace()
                            }
                        }
                        if (specificErrorMessage != null) {
                            Toast.makeText(context, specificErrorMessage, Toast.LENGTH_LONG).show()
                        } else {
                            when (errorCode) {
                                429 -> Toast.makeText(
                                    context,
                                    "Server is busy (Too many requests). Try again in a minute!",
                                    Toast.LENGTH_LONG
                                ).show()

                                400 -> Toast.makeText(
                                    context,
                                    "Route too long or impossible to calculate with current danger zones.",
                                    Toast.LENGTH_LONG
                                ).show()

                                404 -> Toast.makeText(
                                    context,
                                    "Could not find a valid walking route to that exact location.",
                                    Toast.LENGTH_LONG
                                ).show()

                                else -> Toast.makeText(
                                    context,
                                    "Routing failed (Error $errorCode).",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        })
    }

    private fun processAndDrawRoute(jsonString: String, isSafe: Boolean) {
        try {
            val feature = JSONObject(jsonString).getJSONArray("features").getJSONObject(0)
            val geometry = feature.getJSONObject("geometry")
            val coords = geometry.getJSONArray("coordinates")
            val properties = feature.getJSONObject("properties").getJSONObject("summary")
            
            val duration = properties.getDouble("duration") // in seconds

            val points = ArrayList<GeoPoint>()
            for (i in 0 until coords.length()) {
                val p = coords.getJSONArray(i)
                points.add(GeoPoint(p.getDouble(1), p.getDouble(0)))
            }

            val polyline = Polyline(map)
            polyline.setPoints(points)
            
            if (isSafe) {
                polyline.outlinePaint.color = "#4CAF50".toColorInt() // Green for Safe
                polyline.outlinePaint.strokeWidth = 14f
                polyline.title = "Safe Route: ${formatTime(duration)}"
                safeRouteOverlay = polyline
            } else {
                polyline.outlinePaint.color = "#F44336".toColorInt() // Red for Fast/Risky
                polyline.outlinePaint.strokeWidth = 10f
                // Make the risky route dashed
                polyline.outlinePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)
                polyline.title = "Fastest Route: ${formatTime(duration)}"
                fastRouteOverlay = polyline
            }

            map.overlays.add(polyline)
            map.invalidate()

            // Optional: Comparison message
            checkIfBothRoutesReady()

        } catch (e: Exception) { Log.e("RouteManager", "Error parsing route", e) }
    }

    private fun checkIfBothRoutesReady() {
        if (safeRouteOverlay != null && fastRouteOverlay != null) {
            val safeTime = parseDuration(safeRouteOverlay?.title ?: "")
            val fastTime = parseDuration(fastRouteOverlay?.title ?: "")
            
            if (safeTime > fastTime * 1.5) {
                showToast("Note: Safe route is significantly longer. Fast route is shown in dashed red.")
            }
        }
    }

    private fun formatTime(seconds: Double): String {
        val minutes = (seconds / 60).toInt()
        return if (minutes < 1) "< 1 min" else "$minutes min"
    }

    private fun parseDuration(title: String): Int {
        return title.filter { it.isDigit() }.toIntOrNull() ?: 0
    }

    private fun calculateDistance(p1: GeoPoint, p2: GeoPoint): Double {
        val earthRadius = 6371.0
        val dLat = Math.toRadians(p2.latitude - p1.latitude)
        val dLon = Math.toRadians(p2.longitude - p1.longitude)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(p1.latitude)) * cos(Math.toRadians(p2.latitude)) * sin(dLon / 2).pow(2)
        return earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }
}
