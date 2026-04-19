package com.example.future_minds

import android.content.Context
import android.graphics.Color
import android.graphics.DashPathEffect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.graphics.toColorInt
import androidx.navigation.activity
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    private var safeRouteOverlay: Polyline? = null
    private var fastRouteOverlay: Polyline? = null
    private var busRouteOverlay: Polyline? = null
    public var isBusModeActive = false



    fun getSafeRoute(start: GeoPoint, end: GeoPoint, badZones: List<Map<String, Any>>) {
        // Clear previous routes
        clearRoutes()

        val distanceKm = calculateDistance(start, end)
        if (distanceKm > 100) {
            showToast(context.getString(R.string.destination_too_far, String.format(Locale.US, "%.1f", distanceKm)))
            return
        }

        if (isBusModeActive) {
            // In Bus mode, we only make one call
            fetchOTPBusRoute(start, end, badZones)
        } else {
            // 1. Fetch the Fast (Risky) Route
            fetchRoute(start, end, emptyList(), isSafeAttempt = false)

            // 2. Fetch the Safe Route (only if there are zones to avoid)
            if (badZones.isNotEmpty()) {
                fetchRoute(start, end, badZones, isSafeAttempt = true)
            }
        }
    }

    fun clearRoutes() {
        // Safety check to ensure we are on the Main UI Thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { clearRoutes() }
            return
        }

        try {
            safeRouteOverlay = null
            fastRouteOverlay = null
            busRouteOverlay = null // Added this line

// IMPROVED CLEARING: Loop through and keep the Destination pin
            val overlaysToRemove = mutableListOf<org.osmdroid.views.overlay.Overlay>()
            for (overlay in map.overlays) {
                if (overlay is Polyline) {
                    overlaysToRemove.add(overlay)
                }
                if (overlay is org.osmdroid.views.overlay.Marker) {
                    // Only remove markers that are identified as bus stops
                    // This ensures the Destination pin is never removed
                    if (overlay.id == "BUS_STOP") {
                        overlaysToRemove.add(overlay)
                    }
                }
            }
            map.overlays.removeAll(overlaysToRemove)

            map.invalidate() // Redraw the map
        } catch (e: Exception) {
            Log.e("RouteManager", "Error clearing routes safely: ${e.message}")
        }
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
                    // FIX: Changed "lng" to "lon" to match your data structure
                    val lon = (zone["lon"] as? Number)?.toDouble() ?: continue
                    val radius = (zone["radius"] as? Number)?.toDouble() ?: 20.0

                    val latDeg = radius / 111320.0
                    val lngDeg = radius / (111320.0 * cos(Math.toRadians(lat)))
                    val ring = JSONArray()

                    // FIX: Loop 0..8 (9 points) ensures the polygon is closed for the API
                    for (i in 0..8) {
                        val angle = Math.toRadians(i * 45.0)
                        ring.put(JSONArray()
                            .put(lon + lngDeg * cos(angle))
                            .put(lat + latDeg * sin(angle))
                        )
                    }
                    multiPolygonCoordinates.put(JSONArray().put(ring))
                }
                put("coordinates", multiPolygonCoordinates)
            }
            options.put("avoid_polygons", avoidPolygons)
            jsonBody.put("options", options)
        }

        val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://api.openrouteservice.org/v2/directions/foot-walking/geojson")
            .post(body).addHeader("Authorization", apiKey).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Handler(Looper.getMainLooper()).post { showToast(context.getString(R.string.network_error)) }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseData = response.body?.string()
                if (response.isSuccessful && responseData != null) {
                    Handler(Looper.getMainLooper()).post {
                        processAndDrawRoute(responseData, isSafeAttempt)
                    }
                }
            }
        })
    }
    private fun fetchOTPBusRoute(start: GeoPoint, destination: GeoPoint, badZones: List<Map<String, Any>>) {
        val sdfDate = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
        val sdfTime = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())

        val otpUrl = "http://tawanda-sequestral-tasha.ngrok-free.dev/otp/routers/default/plan"
        val urlBuilder = otpUrl.toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("fromPlace", "${start.latitude},${start.longitude}")
            .addQueryParameter("toPlace", "${destination.latitude},${destination.longitude}")
            .addQueryParameter("mode", "TRANSIT,WALK")
            .addQueryParameter("date", sdfDate)
            .addQueryParameter("time", sdfTime)
            .addQueryParameter("maxWalkDistance", "2000")
            .addQueryParameter("arriveBy", "false")
            .addQueryParameter("walkReluctance", "2000")
            .addQueryParameter("waitReluctance", "0.01")

        val request = Request.Builder().url(urlBuilder.build()).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { Log.e("OTP", "Request Failed", e) }
            override fun onResponse(call: Call, response: Response) {
                val responseData = response.body?.string()
                if (responseData != null) {
                    Handler(Looper.getMainLooper()).post {
                        processAndDrawBusRoute(responseData, start, destination, badZones)
                    }
                }
            }
        })
    }

    private fun createStationIcon(): android.graphics.drawable.BitmapDrawable {
        val size = 40
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        // Draw Black Outline
        paint.color = Color.BLACK
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        // Draw White Inner Circle
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - 3, paint)

        return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
    }
    private fun processAndDrawBusRoute(otpResponse: String, start: GeoPoint, end: GeoPoint, badZones: List<Map<String, Any>>) {
        try {
            val json = JSONObject(otpResponse)
            val plan = json.optJSONObject("plan") ?: return
            val itineraries = plan.optJSONArray("itineraries") ?: return
            if (itineraries.length() == 0) return

            val itinerary = itineraries.getJSONObject(0)
            val legs = itinerary.getJSONArray("legs")

            var hasTransit = false
            for (i in 0 until legs.length()) {
                if (legs.getJSONObject(i).getString("mode") != "WALK") {
                    hasTransit = true
                    break
                }
            }

            if (!hasTransit) {
                showToast(context.getString(R.string.no_bus_available))
                // Clear current overlays
                //clearRoutes()

                // Trigger the standard ORS walking logic (Fast and Safe)
                fetchRoute(start, end, emptyList(), isSafeAttempt = false)
                if (badZones.isNotEmpty()) {
                    fetchRoute(start, end, badZones, isSafeAttempt = true)
                }
                return // Exit this function so we don't draw the OTP walk leg
            }

            clearRoutes()
            val stationIcon = createStationIcon()

            for (i in 0 until legs.length()) {
                val leg = legs.getJSONObject(i)
                val mode = leg.getString("mode")
                val points = decodePolyline(leg.getJSONObject("legGeometry").getString("points"))
                if (points.isEmpty()) continue

                val polyline = Polyline(map)
                polyline.setPoints(points)

                if (mode == "WALK") {
                    polyline.outlinePaint.color = Color.GRAY
                    polyline.outlinePaint.strokeWidth = 12f
                    polyline.title = context.getString(R.string.walk_label)
                } else {
                    val busName = leg.optString("routeShortName", context.getString(R.string.bus_fallback))
                    val headsign = leg.optString("headsign", "")

                    polyline.outlinePaint.color = Color.BLUE
                    polyline.outlinePaint.strokeWidth = 14f
                    polyline.title = "$busName"

                    // --- NEW: DRAW ALL STOPS IN THIS LEG ---
                    val allStopsInLeg = mutableListOf<JSONObject>()

                    // 1. Add Departure Stop
                    allStopsInLeg.add(leg.getJSONObject("from"))

                    // 2. Add Intermediate Stops (all the stops the bus hits in between)
                    val intermediateStops = leg.optJSONArray("intermediateStops")
                    if (intermediateStops != null) {
                        for (j in 0 until intermediateStops.length()) {
                            allStopsInLeg.add(intermediateStops.getJSONObject(j))
                        }
                    }

                    // 3. Add Arrival Stop
                    allStopsInLeg.add(leg.getJSONObject("to"))

                    // Now create markers for every stop collected
                    for (stopJson in allStopsInLeg) {
                        val stopMarker = org.osmdroid.views.overlay.Marker(map)
                        stopMarker.id = "BUS_STOP" // Identification for clearRoutes
                        stopMarker.position = GeoPoint(stopJson.getDouble("lat"), stopJson.getDouble("lon"))
                        stopMarker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
                        stopMarker.icon = stationIcon
                        stopMarker.title = context.getString(R.string.bus_stop_title_format, stopJson.optString("name", context.getString(R.string.bus_stop_fallback)))
                        stopMarker.infoWindow = null // Keep it simple, info is on the line
                        map.overlays.add(stopMarker)
                    }
                }

                // Show InfoWindow on line click
                polyline.setOnClickListener { poly, mapView, eventPos ->
                    poly.showInfoWindow()
                    true
                }

                map.overlays.add(polyline)
            }

            map.invalidate()
        } catch (e: Exception) {
            Log.e("RouteManager", "Error drawing bus route: ${e.message}")
        }
    }

    private fun convertOtpToGeoJson(otpResponse: String): String {
        try {
            val otpJson = JSONObject(otpResponse)
            if (otpJson.has("error")) return ""

            val plan = otpJson.optJSONObject("plan") ?: return ""
            val itineraries = plan.optJSONArray("itineraries") ?: return ""
            if (itineraries.length() == 0) return ""

            val itinerary = itineraries.getJSONObject(0)
            val duration = itinerary.getDouble("duration")
            val legs = itinerary.getJSONArray("legs")

            var hasTransit = false
            val allCoordinates = JSONArray()

            for (i in 0 until legs.length()) {
                val leg = legs.getJSONObject(i)
                if (leg.getString("mode") != "WALK") hasTransit = true

                // FIX: You MUST decode the polyline string into GeoPoints
                val encodedPoints = leg.getJSONObject("legGeometry").getString("points")
                val decodedPoints = decodePolyline(encodedPoints)

                // Add decoded points to our GeoJSON array
                for (point in decodedPoints) {
                    val coord = JSONArray()
                    coord.put(point.longitude)
                    coord.put(point.latitude)
                    allCoordinates.put(coord)
                }
            }

            if (!hasTransit) {
                showToast(context.getString(R.string.bus_not_available_walking))
            }

            // Build the GeoJSON structure that processAndDrawRoute expects
            val feature = JSONObject()
            val geometry = JSONObject()
            geometry.put("type", "LineString")
            geometry.put("coordinates", allCoordinates)

            val properties = JSONObject()
            val summary = JSONObject()
            summary.put("duration", duration)
            properties.put("summary", summary)

            feature.put("type", "Feature")
            feature.put("geometry", geometry)
            feature.put("properties", properties)

            val featureCollection = JSONObject()
            featureCollection.put("type", "FeatureCollection")
            featureCollection.put("features", JSONArray().put(feature))

            return featureCollection.toString()
        } catch (e: Exception) {
            Log.e("RouteManager", "OTP Conversion Error: ${e.message}")
            return ""
        }
    }

    // Helper to decode Google Polyline (common in OTP responses)
    private fun decodePolyline(encoded: String): List<GeoPoint> {
        val poly = ArrayList<GeoPoint>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0
        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng
            poly.add(GeoPoint(lat.toDouble() / 1E5, lng.toDouble() / 1E5))
        }
        return poly
    }

    private fun processAndDrawRoute(jsonString: String, isSafe: Boolean) {
        try {
            if (jsonString.isEmpty()) return

            val feature = JSONObject(jsonString).getJSONArray("features").getJSONObject(0)
            val geometry = feature.getJSONObject("geometry")
            val coords = geometry.getJSONArray("coordinates")
            val properties = feature.getJSONObject("properties").getJSONObject("summary")
            val duration = properties.getDouble("duration")

            val points = ArrayList<GeoPoint>()
            for (i in 0 until coords.length()) {
                val p = coords.getJSONArray(i)
                points.add(GeoPoint(p.getDouble(1), p.getDouble(0)))
            }

            val polyline = Polyline(map)
            polyline.setPoints(points)

            if (isSafe) {
                safeRouteOverlay?.let { map.overlays.remove(it) }
                polyline.outlinePaint.color = "#4CAF50".toColorInt()
                polyline.outlinePaint.strokeWidth = 14f
                polyline.title = context.getString(R.string.safe_route_label, formatTime(duration))
                safeRouteOverlay = polyline
            } else {
                fastRouteOverlay?.let { map.overlays.remove(it) }
                polyline.outlinePaint.color = "#F44336".toColorInt()
                polyline.outlinePaint.strokeWidth = 10f
                polyline.outlinePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)
                polyline.title = context.getString(R.string.fast_route_label, formatTime(duration))
                fastRouteOverlay = polyline
            }


            map.overlays.add(polyline)
            map.invalidate()

            if (!isBusModeActive) {
                checkIfBothRoutesReady()
            }
        } catch (e: Exception) {
            Log.e("RouteManager", "Error drawing route", e)
        }
    }

    private fun checkIfBothRoutesReady() {
        if (safeRouteOverlay != null && fastRouteOverlay != null) {
            val safeTime = parseDuration(safeRouteOverlay?.title ?: "")
            val fastTime = parseDuration(fastRouteOverlay?.title ?: "")

            if (safeTime > fastTime * 1.5) {
                showToast(context.getString(R.string.safe_route_longer_hint))
            }
        }
    }

    private fun formatTime(seconds: Double): String {
        val minutes = (seconds / 60).toInt()
        return if (minutes < 1) context.getString(R.string.less_than_one_minute) else context.getString(R.string.minutes_format, minutes)
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