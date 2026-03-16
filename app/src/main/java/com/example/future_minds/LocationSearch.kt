package com.example.future_minds

import android.content.Context
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Filter
import android.widget.Filterable
import android.widget.ImageButton
import android.widget.Toast
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

class LocationSearch(
    private val context: Context,
    private val map: MapView,
    private val searchBar: AutoCompleteTextView,
    private val searchButton: ImageButton
) {

    private var geocoder: Geocoder? = null
    lateinit var pnt: GeoPoint
    public  var pntBool: Boolean=false
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentMarker: Marker? = null
    private lateinit var adapter: AutoSuggestAdapter

    init {
        if (Geocoder.isPresent()) {
            geocoder = Geocoder(context, Locale.getDefault())
            setupAdapter()
            setupSearchButton()
            setupAutocompleteListener()
        } else {
            // If geocoder is not present, disable the search bar and inform the user.
            searchBar.isEnabled = false
            searchButton.isEnabled = false
            val warning = "Device location search is not available."
            searchBar.hint = warning
            Toast.makeText(context, warning, Toast.LENGTH_LONG).show()
            Log.e("LocationSearch", "Geocoder is not present on this device.")
        }
    }

    private fun setupAdapter() {
        adapter = AutoSuggestAdapter(context, android.R.layout.simple_dropdown_item_1line)
        searchBar.setAdapter(adapter)
    }

    private fun setupSearchButton() {
        searchButton.setOnClickListener {
            performSearch(searchBar.text.toString())
        }
    }

    private fun setupAutocompleteListener() {
        searchBar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s != null && s.length >= 3) getSuggestions(s.toString())
            }
        })

        searchBar.setOnItemClickListener { _, _, position, _ ->
            performSearch(adapter.getItem(position) ?: "")
        }
    }

    private fun getSuggestions(query: String) {
        geocoder ?: return

        val mapCenter = map.mapCenter as GeoPoint
        val threshold = 0.1 // 10 - 11 km

        executor.execute {
            try {
                // Simplified the call to be more reliable.
                val addresses = geocoder!!.getFromLocationName(
                    query, 5,
                    mapCenter.latitude - threshold,
                    mapCenter.longitude - threshold,
                    mapCenter.latitude + threshold,
                    mapCenter.longitude + threshold
                )

                val suggestions = addresses?.mapNotNull { address ->
                    // Build a readable address line
                    val addressLine =
                        (0..address.maxAddressLineIndex).joinToString(separator = ", ") { i ->
                            address.getAddressLine(i)
                        }
                    if (addressLine.isNotEmpty()) addressLine else null
                } ?: emptyList()

                mainHandler.post {
                    adapter.setData(suggestions)
                    adapter.notifyDataSetChanged()
                }
            } catch (e: IOException) {
                Log.w("LocationSearch", "Suggestion search failed: Check network. Query: $query", e)
            } catch (e: Exception) {
                Log.e("LocationSearch", "Suggestion search failed unexpectedly. Query: $query", e)
            }
        }
    }

    private fun performSearch(query: String) {
        if (query.isEmpty() || geocoder == null) return

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchBar.windowToken, 0)
        searchBar.dismissDropDown()

        mainHandler.post { Toast.makeText(context, "Searching...", Toast.LENGTH_SHORT).show() }

        executor.execute {
            try {
                val results = geocoder!!.getFromLocationName(query, 1)

                if (!results.isNullOrEmpty()) {
                    val location = results[0]
                    val point = GeoPoint(location.latitude, location.longitude)
                    val title = location.featureName ?: query
                    mainHandler.post { zoomToLocation(point, title) }
                } else {
                    mainHandler.post { Toast.makeText(context, "Location not found.", Toast.LENGTH_LONG).show() }
                }
            } catch (e: IOException) {
                 mainHandler.post { Toast.makeText(context, "Search failed: Please check network connection.", Toast.LENGTH_LONG).show() }
                 Log.e("LocationSearch", "Search failed (Network). Query: $query", e)
            } catch (e: Exception) {
                mainHandler.post { Toast.makeText(context, "An error occurred during search.", Toast.LENGTH_LONG).show() }
                Log.e("LocationSearch", "Search failed (Unknown). Query: $query", e)
            }
        }
    }

    fun zoomToLocation(point: GeoPoint, title: String) {
        pnt=point
        pntBool=true
        map.controller.animateTo(point)
        map.controller.setZoom(17.0)
        currentMarker?.let { map.overlays.remove(it) }
        currentMarker = Marker(map).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            this.title = title
            showInfoWindow()
        }
        map.overlays.add(1,currentMarker)
        map.invalidate()

//        if (context is MainActivity) {
//            context.calculateSafeRoute(point)
//        }
    }

    class AutoSuggestAdapter(context: Context, resource: Int) : ArrayAdapter<String>(context, resource), Filterable {
        private val mData = ArrayList<String>()
        fun setData(list: List<String>) { mData.clear(); mData.addAll(list) }
        override fun getCount(): Int = mData.size
        override fun getItem(position: Int): String? = mData[position]
        override fun getFilter(): Filter = object : Filter() {
            override fun performFiltering(c: CharSequence?): FilterResults = FilterResults().apply { values = mData; count = mData.size }
            override fun publishResults(c: CharSequence?, r: FilterResults?) { if (r?.count ?: 0 > 0) notifyDataSetChanged() else notifyDataSetInvalidated() }
        }
    }
}
