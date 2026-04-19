package com.example.future_minds

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.navigation.NavigationView
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var locationSearch: LocationSearch 
    private lateinit var routeManager: RouteManager
    private var badZonesList = mutableListOf<Map<String, Any>>()
    private var dangerZoneOverlays = mutableListOf<Polygon>()
    private lateinit var locationOverlay: MyLocationNewOverlay
    
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private var protectedMarkers = mutableMapOf<String, Marker>()
    private var connectionsListener: ListenerRegistration? = null
    private val userLocationListeners = mutableMapOf<String, ListenerRegistration>()
    
    private val usersInEmergency = mutableMapOf<String, Long>()
    private val userProfileUrls = mutableMapOf<String, String?>()
    private val userBitmaps = mutableMapOf<String, Bitmap>()
    private val pulseHandler = Handler(Looper.getMainLooper())
    private var pulseAlpha = 0
    private var pulseDirection = 20

    private var isReportMode = false

    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (usersInEmergency.isNotEmpty()) {
                pulseAlpha += pulseDirection
                if (pulseAlpha <= 60 || pulseAlpha >= 220) pulseDirection *= -1
                
                // Optimized: Only refresh if needed, and map.invalidate() once
                usersInEmergency.keys.forEach { uid -> refreshMarkerIcon(uid) }
                map.postInvalidate()
            }
            pulseHandler.postDelayed(this, 300) 
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                setupLocationOverlay()
            } else {
                Toast.makeText(this, getString(R.string.location_permission_required), Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val sharedPrefs = getSharedPreferences("${packageName}_preferences", MODE_PRIVATE)
        Configuration.getInstance().load(this, sharedPrefs)
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        if (auth.currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        drawerLayout = findViewById(R.id.drawer_layout)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        val btnMenu = findViewById<ImageButton>(R.id.btn_menu)

        btnMenu?.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        setupNavigationHeader(navView)
        migrateOldGuardianData() 
        listenForGuardianRequests()
        listenForSosAlerts()
        listenForProtectedUsersLocations()

        navView?.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_profile -> startActivity(Intent(this, ProfileActivity::class.java))
                R.id.nav_personal_data -> startActivity(Intent(this, PersonalDataActivity::class.java))
                R.id.nav_guardian -> startActivity(Intent(this, GuardianActivity::class.java))
                R.id.nav_protected -> startActivity(Intent(this, ProtectedActivity::class.java))
                R.id.nav_manage_favs -> startActivity(Intent(this, FavoritesActivity::class.java))
                R.id.nav_report_bug -> reportBug()
                R.id.nav_report_city -> openCityReport()
                R.id.nav_logout -> showSignOutConfirmation()
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        findViewById<View>(R.id.btn_profile_container)?.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        // Map Initialization
        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.setBuiltInZoomControls(false)
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint(44.4268, 26.1025))

        val reportBtn = findViewById<ImageButton>(R.id.button_report)
        reportBtn?.setOnClickListener { 
            isReportMode = true 
            Toast.makeText(this, getString(R.string.long_press_report_hint), Toast.LENGTH_SHORT).show()
        }

        val sosBtn = findViewById<Button>(R.id.button_sos)
        sosBtn?.setOnClickListener { sendSosAlert() }

        val searchBar = findViewById<AutoCompleteTextView>(R.id.search_bar)
        val searchBtn = findViewById<ImageButton>(R.id.btn_search)
        locationSearch = LocationSearch(this, map, searchBar, searchBtn)
        routeManager = RouteManager(this, map)

        val routeBtn = findViewById<ImageButton>(R.id.button_route)
        routeBtn?.setOnClickListener {
            if(!locationSearch.pntBool) {
                Toast.makeText(this, getString(R.string.no_location_selected), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            calculateSafeRoute(locationSearch.pnt)
        }

        setupTransportToggle()

        setupFavoriteChips()

        val mapEventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p?.let {
                    if(isReportMode){
                        showReportDialog(it)
                        isReportMode = false
                    } else {
                        locationSearch.zoomToLocation(it, getString(R.string.no_location_selected))
                    }
                }
                return true
            }
        }
        map.overlays.add(MapEventsOverlay(mapEventsReceiver))

        listenForDangerZones()
        checkLocationPermission()
        listenForUserData()
        
        pulseHandler.post(pulseRunnable)
    }

    private fun setupTransportToggle() {
        val btnWalking = findViewById<View>(R.id.btn_toggle_walking)
        val btnBus = findViewById<View>(R.id.btn_toggle_bus)
        
        val ivWalking = findViewById<ImageView>(R.id.iv_toggle_walking)
        val tvWalking = findViewById<TextView>(R.id.tv_toggle_walking)
        val ivBus = findViewById<ImageView>(R.id.iv_toggle_bus)
        val tvBus = findViewById<TextView>(R.id.tv_toggle_bus)

        val textDark = ContextCompat.getColor(this, R.color.text_dark)
        val white = ContextCompat.getColor(this, R.color.white)

        fun updateUI(isBus: Boolean) {
            routeManager.isBusModeActive = isBus
            
            if (isBus) {
                btnBus.setBackgroundResource(R.drawable.bg_primary_button)
                ivBus.setColorFilter(white)
                tvBus.setTextColor(white)

                btnWalking.setBackgroundResource(android.R.color.transparent)
                ivWalking.setColorFilter(textDark)
                tvWalking.setTextColor(textDark)
            } else {
                btnWalking.setBackgroundResource(R.drawable.bg_primary_button)
                ivWalking.setColorFilter(white)
                tvWalking.setTextColor(white)

                btnBus.setBackgroundResource(android.R.color.transparent)
                ivBus.setColorFilter(textDark)
                tvBus.setTextColor(textDark)
            }
        }

        btnWalking.setOnClickListener { updateUI(false) }
        btnBus.setOnClickListener { updateUI(true) }
        
        // Set initial state
        updateUI(false)
    }

    private fun setupFavoriteChips() {
        val chipHome = findViewById<Chip>(R.id.chip_home)
        val chipSchool = findViewById<Chip>(R.id.chip_work) 
        val chipPark = findViewById<Chip>(R.id.chip_school) 

        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).addSnapshotListener { snapshot, _ ->
            if (snapshot != null && snapshot.exists()) {
                val favorites = snapshot.get("favorites") as? Map<*, *>
                
                chipHome?.setOnClickListener {
                    (favorites?.get("home") as? String)?.let { address ->
                        Toast.makeText(this, getString(R.string.calculating_route_home), Toast.LENGTH_SHORT).show()
                        calculateRouteToAddress(address)
                    } ?: Toast.makeText(this, getString(R.string.home_not_set), Toast.LENGTH_SHORT).show()
                }
                
                chipSchool?.setOnClickListener {
                    (favorites?.get("school") as? String)?.let { address ->
                        Toast.makeText(this, getString(R.string.calculating_route_school), Toast.LENGTH_SHORT).show()
                        calculateRouteToAddress(address)
                    } ?: Toast.makeText(this, getString(R.string.school_not_set), Toast.LENGTH_SHORT).show()
                }
                
                chipPark?.setOnClickListener {
                    (favorites?.get("park") as? String)?.let { address ->
                        Toast.makeText(this, getString(R.string.calculating_route_park), Toast.LENGTH_SHORT).show()
                        calculateRouteToAddress(address)
                    } ?: Toast.makeText(this, getString(R.string.park_not_set), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun calculateRouteToAddress(address: String) {
        val geocoder = android.location.Geocoder(this, Locale.getDefault())
        lifecycleScope.launch {
            try {
                val results = geocoder.getFromLocationName(address, 1)
                if (!results.isNullOrEmpty()) {
                    val location = results[0]
                    val destination = GeoPoint(location.latitude, location.longitude)
                    locationSearch.zoomToLocation(destination, address)
                    calculateSafeRoute(destination)
                } else {
                    Toast.makeText(this@MainActivity, getString(R.string.address_not_found), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error finding address", e)
            }
        }
    }

    private fun openCityReport() {
        val url = "https://www.pmb.ro/interes-public/informatii/formuleaza-petitie"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_open_browser), Toast.LENGTH_LONG).show()
        }
    }

    private fun reportBug() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = "mailto:safewayltib@gmail.com".toUri()
            putExtra(Intent.EXTRA_SUBJECT, "Raport Bug - Future Minds")
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.no_email_app_found), Toast.LENGTH_SHORT).show()
        }
    }

    private fun calculateSafeRoute(destination: GeoPoint) {
        if (!::locationOverlay.isInitialized) return
        locationOverlay.myLocation?.let {
            routeManager.getSafeRoute(it, destination, badZonesList)
        } ?: Toast.makeText(this, getString(R.string.waiting_for_gps), Toast.LENGTH_SHORT).show()
    }

    private fun setupLocationOverlay() {
        if (!::map.isInitialized) return
        
        locationOverlay = object : MyLocationNewOverlay(GpsMyLocationProvider(this), map) {
            override fun onLocationChanged(location: android.location.Location?, source: IMyLocationProvider?) {
                super.onLocationChanged(location, source)
                location?.let { updateMyLocationInFirestore(it.latitude, it.longitude) }
            }
        }

        val size = 60
        val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.applyCanvas {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.WHITE
            drawCircle(size / 2f, size / 2f, size / 2f, paint)
            paint.color = Color.parseColor("#1A73E8") 
            drawCircle(size / 2f, size / 2f, (size / 2f) - 6, paint)
        }
        
        locationOverlay.setPersonIcon(bitmap)
        locationOverlay.setDirectionIcon(bitmap)
        @Suppress("DEPRECATION")
        locationOverlay.setPersonHotspot(size / 2f, size / 2f)
        
        locationOverlay.enableMyLocation()
        map.overlays.add(locationOverlay)
    }

    private fun updateMyLocationInFirestore(lat: Double, lon: Double) {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).update(
            "latitude", lat,
            "longitude", lon,
            "lastSeen", FieldValue.serverTimestamp()
        )
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            setupLocationOverlay()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun listenForDangerZones() {
        db.collection("danger_zones").addSnapshotListener { snapshot, _ ->
            if (snapshot != null) {
                badZonesList.clear()
                dangerZoneOverlays.forEach { map.overlays.remove(it) }
                dangerZoneOverlays.clear()
                
                for (doc in snapshot.documents) {
                    val lat = doc.getDouble("lat") ?: 0.0
                    val lon = doc.getDouble("lon") ?: 0.0
                    val radius = doc.getDouble("radius") ?: 100.0
                    val type = doc.getString("type") ?: "warning"
                    val docId = doc.id

                    badZonesList.add(mapOf("lat" to lat, "lon" to lon, "radius" to radius))
                    
                    val circle = Polygon()
                    circle.points = Polygon.pointsAsCircle(GeoPoint(lat, lon), radius)
                    circle.fillPaint.color = if (type == "danger") Color.argb(80, 255, 0, 0) else Color.argb(80, 255, 165, 0)
                    circle.outlinePaint.color = Color.TRANSPARENT
                    circle.setOnClickListener { polygon, mapView, eventPos ->
                        showDeleteZoneDialog(docId, type)
                        true // consume the event
                    }
                    map.overlays.add(circle)
                    dangerZoneOverlays.add(circle)
                }
                map.invalidate()
            }
        }
    }
    private fun showDeleteZoneDialog(documentId: String, type: String) {
        val uid = auth.currentUser?.uid ?: return

        db.collection("users").document(uid).get().addOnSuccessListener { userSnapshot ->
            val myTrust = userSnapshot?.getLong("trustFactor")?.toInt() ?: 0

            // Fetch the zone details
            db.collection("danger_zones").document(documentId).get().addOnSuccessListener { zoneSnapshot ->
                if (!zoneSnapshot.exists()) return@addOnSuccessListener

                val votedBy = zoneSnapshot.get("votedBy") as? List<String> ?: listOf()
                val votedAgainst = zoneSnapshot.get("votedAgainst") as? List<String> ?: listOf()

                // --- 1. PREVENT DOUBLE VOTING ---
                if (votedBy.contains(uid)) {
                    Toast.makeText(this, getString(R.string.already_voted), Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                if (votedAgainst.contains(uid)) {
                    Toast.makeText(this, getString(R.string.already_voted), Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.manage_zone))
                    .setMessage(getString(R.string.manage_zone_desc))
                    .setPositiveButton(getString(R.string.remove_confirm)) { _, _ ->
                        handleVote(documentId, uid, myTrust, isConfirming = false)
                    }
                    .setNegativeButton(getString(R.string.confirm)) { _, _ ->
                        handleVote(documentId, uid, myTrust, isConfirming = true)
                    }
                    .setNeutralButton(getString(R.string.cancel), null)
                    .show()
            }
        }
    }

    private fun handleVote(documentId: String, voterUid: String, voterTrust: Int, isConfirming: Boolean) {
        val zoneRef = db.collection("danger_zones").document(documentId)

        zoneRef.get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) return@addOnSuccessListener

            var currentTrust = snapshot.getLong("trust")?.toInt() ?: 0
            val creatorUid = snapshot.getString("reportedBy") ?: ""
            val votedBy = snapshot.get("votedBy") as? MutableList<String> ?: mutableListOf()
            val votedAgainst = snapshot.get("votedAgainst") as? MutableList<String> ?: mutableListOf()

            // Add current user to the list of voters

            if (isConfirming) {
                votedBy.forEach { uid -> updateUserTrust(uid, +20) }
                votedAgainst.forEach { uid -> updateUserTrust(uid, -20) }
                votedBy.add(voterUid)
                // INCREASE trust of the zone
                currentTrust += (voterTrust / 2).coerceAtLeast(1)
                zoneRef.update("trust", currentTrust, "votedBy", votedBy, "votedAgainst", votedAgainst).addOnSuccessListener {
                    Toast.makeText(this, getString(R.string.zone_confirmed), Toast.LENGTH_SHORT).show()
                }
            } else {
                // DECREASE trust of the zone
                currentTrust -= (voterTrust / 2).coerceAtLeast(1)
                votedBy.forEach { uid -> updateUserTrust(uid, -20) }
                votedAgainst.forEach { uid -> updateUserTrust(uid, +20) }

                votedAgainst.add(voterUid)

                if (currentTrust <= 0) {
                    zoneRef.delete().addOnSuccessListener {
                        Toast.makeText(this, getString(R.string.zone_removed), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    zoneRef.update("trust", currentTrust, "votedBy", votedBy, "votedAgainst", votedAgainst).addOnSuccessListener {
                        Toast.makeText(this, getString(R.string.zone_trust_decreased), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Helper to update trust factor of a specific user
    private fun updateUserTrust(userUid: String, amount: Int) {
        if (userUid.isEmpty()) return
        val userRef = db.collection("users").document(userUid)
        db.runTransaction { transaction ->
            val snapshot = transaction.get(userRef)
            val oldTrust = snapshot.getLong("trustFactor") ?: 0
            transaction.update(userRef, "trustFactor", oldTrust + amount)
        }.addOnFailureListener {
            // Handle error (e.g. user doesn't exist)
        }
    }

    private fun showReportDialog(point: GeoPoint) {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get().addOnSuccessListener { snapshot ->
            val reporters:MutableList<String> = mutableListOf()
            reporters.add(uid)
            val trust = snapshot?.getLong("trustFactor")?.toInt() ?: 0
            val dialog = BottomSheetDialog(this)
            val view = layoutInflater.inflate(
                R.layout.raportari,
                findViewById(android.R.id.content),
                false
            )

            val spinner = view.findViewById<Spinner>(R.id.spinner_issue_type)
            val seekBar = view.findViewById<SeekBar>(R.id.seekbar_radius)
            val tvRadius = view.findViewById<TextView>(R.id.tv_radius_label)

            seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    tvRadius?.text = getString(R.string.affected_area_format, progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            val btnSubmit = Button(this).apply { text = getString(R.string.submit_report) }
            (view as? LinearLayout)?.addView(btnSubmit)

            btnSubmit.setOnClickListener {
                val type = spinner?.selectedItem?.toString() ?: "warning"
                val radius = seekBar?.progress?.toDouble() ?: 20.0

                val report = hashMapOf(
                    "lat" to point.latitude,
                    "lon" to point.longitude,
                    "description" to type,
                    "type" to if (type.contains("pericol", ignoreCase = true) || type.contains("danger", ignoreCase = true)) "danger" else "warning",
                    "radius" to radius,
                    "timestamp" to FieldValue.serverTimestamp(),
                    "votedBy" to reporters,
                    "trust" to trust
                )
                db.collection("danger_zones").add(report).addOnSuccessListener {
                    Toast.makeText(this, getString(R.string.report_sent_thanks), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }

            dialog.setContentView(view)
            dialog.show()
        }
    }

    private fun sendSosAlert() {
        val currentUser = auth.currentUser ?: return
        db.collection("users").document(currentUser.uid).get().addOnSuccessListener { userDoc ->
            val username = userDoc.getString("username") ?: "Cineva"
            db.collection("connections")
                .whereEqualTo("protectedUid", currentUser.uid)
                .whereEqualTo("status", "accepted")
                .get()
                .addOnSuccessListener { connections ->
                    if (connections.isEmpty) {
                        Toast.makeText(this, getString(R.string.no_guardians_error), Toast.LENGTH_SHORT).show()
                        return@addOnSuccessListener
                    }
                    
                    for (doc in connections) {
                        db.collection("connections").document(doc.id).update("shareLocation", true)
                        
                        val alert = hashMapOf(
                            "guardianUid" to doc.getString("guardianUid"),
                            "protectedUid" to currentUser.uid,
                            "protectedUsername" to username,
                            "message" to "$username are nevoie de ajutor!",
                            "timestamp" to FieldValue.serverTimestamp(),
                            "status" to "new"
                        )
                        db.collection("sos_alerts").add(alert)
                    }
                    Toast.makeText(this, getString(R.string.sos_sent_msg), Toast.LENGTH_LONG).show()
                    locationOverlay.myLocation?.let { updateMyLocationInFirestore(it.latitude, it.longitude) }
                }
        }
    }

    private fun listenForSosAlerts() {
        val currentUser = auth.currentUser ?: return
        db.collection("sos_alerts")
            .whereEqualTo("guardianUid", currentUser.uid)
            .whereEqualTo("status", "new")
            .addSnapshotListener { snapshots, _ ->
                snapshots?.documentChanges?.forEach { doc ->
                    if (doc.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val msg = doc.document.getString("message") ?: "Alertă SOS!"
                        val pUid = doc.document.getString("protectedUid") ?: ""
                        if (pUid.isNotEmpty()) {
                            usersInEmergency[pUid] = System.currentTimeMillis() + 3600000
                        }
                        showSosDialog(msg, doc.document.id)
                    }
                }
            }
    }

    private fun showSosDialog(message: String, alertId: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sos))
            .setMessage(message)
            .setPositiveButton("OK") { _, _ ->
                db.collection("sos_alerts").document(alertId).update("status", "read")
            }
            .setCancelable(false)
            .show()
    }

    private fun showSignOutConfirmation() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.logout_confirm_msg))
            .setPositiveButton(getString(R.string.yes)) { _, _ -> signOut() }
            .setNegativeButton(getString(R.string.no), null)
            .show()
    }

    private fun signOut() {
        auth.signOut()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun listenForUserData() {
        val uid = auth.currentUser?.uid ?: return
        val ivMainProfile = findViewById<ImageView>(R.id.iv_main_profile)
        val mainRankFrame = findViewById<View>(R.id.main_rank_frame)
        
        // Navigation Header Views
        val navView = findViewById<NavigationView>(R.id.nav_view)
        val header = navView?.getHeaderView(0)
        val tvNavUser = header?.findViewById<TextView>(R.id.tv_username)
        val tvNavRank = header?.findViewById<TextView>(R.id.tv_nav_rank)
        val ivNavPhoto = header?.findViewById<ImageView>(R.id.iv_user_photo)
        val navRankFrame = header?.findViewById<View>(R.id.nav_rank_frame)

        val tvHeaderName = findViewById<TextView>(R.id.tv_header_name)
        val tvHeaderRank = findViewById<TextView>(R.id.tv_header_rank)

        db.collection("users").document(uid).addSnapshotListener { snapshot, _ ->
            if (snapshot != null && snapshot.exists()) {
                val username = snapshot.getString("username")
                val profileUrl = snapshot.getString("profileImageUrl")
                val trust = snapshot.getLong("trustFactor")?.toInt() ?: 0
                val rank = UserRank.fromTrustFactor(trust)

                // Update Main UI
                tvHeaderName?.text = username ?: getString(R.string.guest_user)
                tvHeaderRank?.text = rank.getDisplayName(this@MainActivity)
                tvHeaderRank?.setBackgroundColor(rank.color)

                if (mainRankFrame != null) applyRankFrame(mainRankFrame, rank)
                if (!isFinishing && ivMainProfile != null) {
                    Glide.with(this).load(profileUrl ?: android.R.drawable.ic_menu_gallery).circleCrop().into(ivMainProfile)
                }

                // Update Nav Header
                tvNavUser?.text = username ?: getString(R.string.guest_user)
                tvNavRank?.text = rank.getDisplayName(this@MainActivity)
                tvNavRank?.setTextColor(rank.color)
                if (navRankFrame != null) applyRankFrame(navRankFrame, rank)
                if (!isFinishing && ivNavPhoto != null) {
                    Glide.with(this).load(profileUrl ?: android.R.drawable.ic_menu_gallery).circleCrop().into(ivNavPhoto)
                }
            }
        }
    }

    private fun setupNavigationHeader(navView: NavigationView) {
        val header = navView.getHeaderView(0)
        header.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        // Listener logic moved to listenForUserData for performance
    }

    private fun applyRankFrame(view: View, rank: UserRank) {
        val gd = GradientDrawable()
        gd.setColor(Color.TRANSPARENT)
        gd.setStroke(8, rank.color)
        gd.shape = GradientDrawable.OVAL
        view.background = gd
    }

    private fun migrateOldGuardianData() {
        val user = auth.currentUser ?: return
        db.collection("users").document(user.uid).get().addOnSuccessListener { doc ->
            val oldGuardian = doc.getString("guardian")
            if (!oldGuardian.isNullOrEmpty()) {
                db.collection("users").whereEqualTo("username", oldGuardian).get().addOnSuccessListener { guards ->
                    if (!guards.isEmpty) {
                        val gUid = guards.documents[0].id
                        val connection = hashMapOf(
                            "protectedUid" to user.uid,
                            "protectedUsername" to (doc.getString("username") ?: "User"),
                            "guardianUid" to gUid,
                            "guardianUsername" to oldGuardian,
                            "status" to "accepted",
                            "shareLocation" to true
                        )
                        db.collection("connections").add(connection)
                        db.collection("users").document(user.uid).update("guardian", null)
                    }
                }
            }
        }
    }

    private fun listenForGuardianRequests() {
        val user = auth.currentUser ?: return
        db.collection("connections")
            .whereEqualTo("guardianUid", user.uid)
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snapshots, _ ->
                snapshots?.documentChanges?.forEach { doc ->
                    if (doc.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val pName = doc.document.getString("protectedUsername") ?: "Cineva"
                        showRequestDialog(pName, doc.document.id)
                    }
                }
            }
    }

    private fun showRequestDialog(name: String, connectionId: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.guardian_request_title))
            .setMessage(getString(R.string.guardian_request_desc, name))
            .setPositiveButton(getString(R.string.accept)) { _, _ -> updateConnectionStatus(connectionId, "accepted") }
            .setNegativeButton(getString(R.string.decline)) { _, _ -> updateConnectionStatus(connectionId, "rejected") }
            .show()
    }

    private fun updateConnectionStatus(id: String, status: String) {
        db.collection("connections").document(id).update("status", status)
    }

    private fun listenForProtectedUsersLocations() {
        val currentUser = auth.currentUser ?: return
        connectionsListener = db.collection("connections")
            .whereEqualTo("guardianUid", currentUser.uid)
            .whereEqualTo("status", "accepted")
            .whereEqualTo("shareLocation", true)
            .addSnapshotListener { snapshots, _ ->
                if (snapshots == null) return@addSnapshotListener
                
                val currentUids = snapshots.documents.mapNotNull { it.getString("protectedUid") }
                
                userLocationListeners.keys.filter { it !in currentUids }.forEach { uid ->
                    userLocationListeners[uid]?.remove()
                    userLocationListeners.remove(uid)
                    protectedMarkers[uid]?.let { map.overlays.remove(it) }
                    protectedMarkers.remove(uid)
                }

                for (doc in snapshots.documents) {
                    val pUid = doc.getString("protectedUid") ?: continue
                    if (!userLocationListeners.containsKey(pUid)) {
                        startListeningForUserLocation(pUid)
                    }
                }
                map.invalidate()
            }
    }

    private fun startListeningForUserLocation(uid: String) {
        val listener = db.collection("users").document(uid).addSnapshotListener { doc, _ ->
            if (doc != null && doc.exists()) {
                val lat = doc.getDouble("latitude")
                val lon = doc.getDouble("longitude")
                val profileUrl = doc.getString("profileImageUrl")
                if (lat != null && lon != null) {
                    updateProtectedUserMarker(uid, GeoPoint(lat, lon), profileUrl)
                }
            }
        }
        userLocationListeners[uid] = listener
    }

    private fun updateProtectedUserMarker(uid: String, point: GeoPoint, profileUrl: String?) {
        val marker = protectedMarkers.getOrPut(uid) {
            Marker(map).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                map.overlays.add(this)
            }
        }
        marker.position = point
        userProfileUrls[uid] = profileUrl
        
        if (!userBitmaps.containsKey(uid)) {
            loadMarkerIcon(uid, profileUrl)
        } else {
            refreshMarkerIcon(uid)
        }
        map.invalidate()
    }

    private fun loadMarkerIcon(uid: String, url: String?) {
        Glide.with(this)
            .asBitmap()
            .load(url ?: android.R.drawable.ic_menu_gallery)
            .circleCrop()
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    userBitmaps[uid] = resource.scale(120, 120, false)
                    refreshMarkerIcon(uid)
                }
                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    private val iconCache = mutableMapOf<String, Bitmap>()

    private fun refreshMarkerIcon(uid: String) {
        val marker = protectedMarkers[uid] ?: return
        val baseBitmap = userBitmaps[uid] ?: return
        val isEmergency = usersInEmergency.containsKey(uid)
        
        val size = 120
        val margin = 24
        val totalSize = size + margin
        
        // Use a cached bitmap if not in emergency to avoid allocations
        if (!isEmergency) {
            val cached = iconCache[uid]
            if (cached != null) {
                marker.icon = cached.toDrawable(resources)
                return
            }
        }

        val finalBitmap = createBitmap(totalSize, totalSize, Bitmap.Config.ARGB_8888)
        val centerX = totalSize / 2f
        val centerY = totalSize / 2f
        
        finalBitmap.applyCanvas {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            if (isEmergency) {
                paint.color = Color.RED
                paint.alpha = pulseAlpha
                drawCircle(centerX, centerY, (size / 2f) + (margin / 2f), paint)
                paint.alpha = (pulseAlpha * 0.7).toInt()
                drawCircle(centerX, centerY, (size / 2f) + 6, paint)
            } else {
                paint.color = Color.WHITE
                drawCircle(centerX, centerY, (size / 2f) + 4, paint)
            }
            drawBitmap(baseBitmap, (margin / 2).toFloat(), (margin / 2).toFloat(), null)
        }
        
        if (!isEmergency) iconCache[uid] = finalBitmap
        marker.icon = finalBitmap.toDrawable(resources)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        if (::locationOverlay.isInitialized) locationOverlay.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        if (::locationOverlay.isInitialized) locationOverlay.disableMyLocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        pulseHandler.removeCallbacks(pulseRunnable)
        connectionsListener?.remove()
        userLocationListeners.values.forEach { it.remove() }
    }
}
