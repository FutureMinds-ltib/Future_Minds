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
            searchBar.isEnabled = false
            searchButton.isEnabled = false
            val warning = "Device location search is not available."
            searchBar.hint = warning
            Toast.makeText(context, warning, Toast.LENGTH_LONG).show()
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

        executor.execute {
            try {
                // Folosim versiunea mai generală a Geocoder-ului pentru a găsi mai bine POI-urile (mall-uri, parcuri etc.)
                val addresses = geocoder!!.getFromLocationName(query, 8)

                val suggestions = addresses?.mapNotNull { address ->
                    val feature = address.featureName
                    val street = address.thoroughfare
                    val locality = address.locality ?: address.adminArea
                    
                    var bestName: String? = null
                    
                    // Prioritatea 1: Verificăm dacă query-ul utilizatorului apare în vreuna din liniile de adresă
                    // Geocoder-ul pune deseori numele locației (ex: "AFI Cotroceni") în prima linie, dar nu și în featureName.
                    for (i in 0..address.maxAddressLineIndex) {
                        val line = address.getAddressLine(i) ?: continue
                        if (line.contains(query, ignoreCase = true)) {
                            // Luăm doar partea relevantă de dinainte de virgulă
                            bestName = line.split(",")[0].trim()
                            break
                        }
                    }
                    
                    // Prioritatea 2: Dacă nu am găsit meci cu query-ul, verificăm featureName (dacă nu e doar stradă/număr)
                    if (bestName == null) {
                        if (feature != null && feature.toIntOrNull() == null && feature != street) {
                            bestName = feature
                        }
                    }
                    
                    // Prioritatea 3: Fallback la prima parte a adresei
                    if (bestName == null) {
                        bestName = address.getAddressLine(0)?.split(",")?.get(0)?.trim()
                    }

                    if (bestName == null) return@mapNotNull null
                    
                    // Adăugăm orașul pentru context, dacă nu e deja în nume
                    if (locality != null && !bestName.contains(locality, ignoreCase = true)) {
                        "$bestName, $locality"
                    } else {
                        bestName
                    }
                }?.distinct() ?: emptyList()

                mainHandler.post {
                    adapter.setData(suggestions)
                    adapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                Log.e("LocationSearch", "Suggestions error", e)
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
                    // Folosim aceeași logică de nume scurt și pentru titlul markerului
                    val title = query.capitalize()
                    mainHandler.post { zoomToLocation(point, title) }
                } else {
                    mainHandler.post { Toast.makeText(context, "Location not found.", Toast.LENGTH_LONG).show() }
                }
            } catch (e: Exception) {
                Log.e("LocationSearch", "Search error", e)
            }
        }
    }

    fun zoomToLocation(point: GeoPoint, title: String) {
        pnt = point
        pntBool = true
        map.controller.animateTo(point)
        map.controller.setZoom(17.0)
        currentMarker?.let { map.overlays.remove(it) }
        currentMarker = Marker(map).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            this.title = title
            showInfoWindow()
        }
        map.overlays.add(1, currentMarker)
        map.invalidate()
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
