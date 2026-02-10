package com.example.future_minds // CHECK YOUR PACKAGE NAME

import android.content.Context
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Filter
import android.widget.Filterable
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.Locale
import java.util.concurrent.Executors

class LocationSearch(
    private val context: Context,
    private val map: MapView,
    private val searchBar: AutoCompleteTextView,
    private val searchButton: ImageButton
) {

    private val geocoder = Geocoder(context, Locale.getDefault())
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentMarker: Marker? = null

    // We use our custom adapter here
    private lateinit var adapter: AutoSuggestAdapter

    init {
        setupAdapter()
        setupSearchButton()
        setupAutocompleteListener()
    }

    // 1. SETUP CUSTOM ADAPTER
    private fun setupAdapter() {
        adapter = AutoSuggestAdapter(context, android.R.layout.simple_dropdown_item_1line)
        searchBar.setAdapter(adapter)
    }

    private fun setupSearchButton() {
        searchButton.setOnClickListener {
            val query = searchBar.text.toString()
            performSearch(query)
        }
    }

    private fun setupAutocompleteListener() {
        searchBar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString()

                // Only search if we have enough text
                if (query.length >= 3) {
                    getSuggestions(query)
                }
            }
        })

        searchBar.setOnItemClickListener { parent, _, position, _ ->
            val selectedLocation = adapter.getItem(position) ?: ""
            performSearch(selectedLocation)
        }
    }

    private fun getSuggestions(query: String) {
        executor.execute {
            try {
                // Bias search to visible map
                val box = map.boundingBox
                val addresses = if (box != null) {
                    geocoder.getFromLocationName(query, 5,
                        box.latSouth, box.lonWest, box.latNorth, box.lonEast)
                } else {
                    geocoder.getFromLocationName(query, 5)
                }

                val suggestions = addresses?.map { address ->
                    val feature = address.featureName ?: ""
                    val locality = address.locality ?: ""
                    val country = address.countryName ?: ""
                    listOf(feature, locality, country).filter { it.isNotEmpty() }.joinToString(", ")
                } ?: emptyList()

                mainHandler.post {
                    // Update our custom adapter directly
                    adapter.setData(suggestions)
                    adapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun performSearch(query: String) {
        if (query.isEmpty()) return

        // Hide Keyboard
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(searchBar.windowToken, 0)
        searchBar.dismissDropDown()

        executor.execute {
            try {
                val results = geocoder.getFromLocationName(query, 1)
                if (!results.isNullOrEmpty()) {
                    val location = results[0]
                    val point = GeoPoint(location.latitude, location.longitude)
                    val title = location.featureName ?: query

                    mainHandler.post {
                        zoomToLocation(point, title)
                    }
                } else {
                    mainHandler.post {
                        Toast.makeText(context, "Location not found", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun zoomToLocation(point: GeoPoint, title: String) {
        map.controller.animateTo(point)
        map.controller.setZoom(18.0)

        currentMarker?.let { map.overlays.remove(it) }

        val marker = Marker(map)
        marker.position = point
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        marker.title = title
        marker.showInfoWindow()

        map.overlays.add(marker)
        map.invalidate()
        currentMarker = marker

        if (context is MainActivity) {
            context.calculateSafeRoute(point)
        }
    }

    // --- CUSTOM ADAPTER CLASS (The Secret Sauce) ---
    // This adapter tricks the AutoCompleteTextView into thinking
    // "Everything is fine, just show the data I give you."
    class AutoSuggestAdapter(context: Context, resource: Int) :
        ArrayAdapter<String>(context, resource), Filterable {

        private val mData = ArrayList<String>()

        fun setData(list: List<String>) {
            mData.clear()
            mData.addAll(list)
        }

        override fun getCount(): Int {
            return mData.size
        }

        override fun getItem(position: Int): String? {
            return mData[position]
        }

        // We override the Filter to do NOTHING
        // We handle the filtering ourselves in getSuggestions()
        override fun getFilter(): Filter {
            return object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    val results = FilterResults()
                    results.values = mData
                    results.count = mData.size
                    return results
                }

                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    if (results != null && results.count > 0) {
                        notifyDataSetChanged()
                    } else {
                        notifyDataSetInvalidated()
                    }
                }
            }
        }
    }
}