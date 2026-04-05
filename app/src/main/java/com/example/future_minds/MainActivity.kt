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
    private var isTestMode = false

    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (usersInEmergency.isNotEmpty()) {
                pulseAlpha += pulseDirection
                if (pulseAlpha <= 60 || pulseAlpha >= 220) pulseDirection *= -1
                
                usersInEmergency.keys.forEach { uid -> refreshMarkerIcon(uid) }
                map.invalidate()
            }
            // Lag reduction: 500ms instead of 100ms
            pulseHandler.postDelayed(this, 500)
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                setupLocationOverlay()
            } else {
                Toast.makeText(this, "Permisiunea de locație este necesară!", Toast.LENGTH_LONG).show()
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
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint(44.4268, 26.1025))

        val reportBtn = findViewById<ImageButton>(R.id.button_report)
        reportBtn?.setOnClickListener { 
            isReportMode = true 
            Toast.makeText(this, "Apasă lung pe hartă pentru a raporta!", Toast.LENGTH_SHORT).show()
        }

        val testBtn = findViewById<MaterialSwitch>(R.id.testare)
        testBtn?.setOnCheckedChangeListener { _, isChecked ->
            isTestMode = isChecked
            Toast.makeText(this, if (isTestMode) "Test Mode ON" else "Test Mode OFF", Toast.LENGTH_SHORT).show()
        }

        val sosBtn = findViewById<Button>(R.id.button_sos)
        sosBtn?.setOnClickListener { sendSosAlert() }

        val searchBar = findViewById<AutoCompleteTextView>(R.id.search_bar)
        val searchBtn = findViewById<ImageButton>(R.id.btn_search)
        locationSearch = LocationSearch(this, map, searchBar, searchBtn)
        routeManager = RouteManager(this, map)

        setupFavoriteChips()

        val mapEventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p?.let {
                    if(isReportMode){
                        showReportDialog(it)
                        isReportMode = false
                    } else if (isTestMode) {
                        locationSearch.zoomToLocation(it, "Pin")
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
                        Toast.makeText(this, "Calculăm traseu spre Casă...", Toast.LENGTH_SHORT).show()
                        calculateRouteToAddress(address)
                    } ?: Toast.makeText(this, "Adresa 'Home' nu este setată!", Toast.LENGTH_SHORT).show()
                }
                
                chipSchool?.setOnClickListener {
                    (favorites?.get("school") as? String)?.let { address ->
                        Toast.makeText(this, "Calculăm traseu spre Școală...", Toast.LENGTH_SHORT).show()
                        calculateRouteToAddress(address)
                    } ?: Toast.makeText(this, "Adresa 'School' nu este setată!", Toast.LENGTH_SHORT).show()
                }
                
                chipPark?.setOnClickListener {
                    (favorites?.get("park") as? String)?.let { address ->
                        Toast.makeText(this, "Calculăm traseu spre Parc...", Toast.LENGTH_SHORT).show()
                        calculateRouteToAddress(address)
                    } ?: Toast.makeText(this, "Adresa 'Park' nu este setată!", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this@MainActivity, "Adresa nu a fost găsită!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error finding address", e)
            }
        }
    }

    private fun openCityReport() {
        val url = "https://www.pmb.ro/interes-public/informatii/formuleaza-petitie"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    private fun reportBug() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = "mailto:safewayltib@gmail.com".toUri()
            putExtra(Intent.EXTRA_SUBJECT, "Raport Bug - Future Minds")
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Nu s-a găsit nicio aplicație de email!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun calculateSafeRoute(destination: GeoPoint) {
        if (!::locationOverlay.isInitialized) return
        locationOverlay.myLocation?.let {
            routeManager.getSafeRoute(it, destination, badZonesList)
        } ?: Toast.makeText(this, "Așteaptă GPS...", Toast.LENGTH_SHORT).show()
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
                    
                    badZonesList.add(mapOf("lat" to lat, "lon" to lon, "radius" to radius))
                    
                    val circle = Polygon()
                    circle.points = Polygon.pointsAsCircle(GeoPoint(lat, lon), radius)
                    circle.fillPaint.color = if (type == "danger") Color.argb(80, 255, 0, 0) else Color.argb(80, 255, 165, 0)
                    circle.outlinePaint.color = Color.TRANSPARENT
                    map.overlays.add(circle)
                    dangerZoneOverlays.add(circle)
                }
                map.invalidate()
            }
        }
    }

    private fun showReportDialog(point: GeoPoint) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.raportari, findViewById(android.R.id.content), false)
        
        val spinner = view.findViewById<Spinner>(R.id.spinner_issue_type)
        val seekBar = view.findViewById<SeekBar>(R.id.seekbar_radius)
        val tvRadius = view.findViewById<TextView>(R.id.tv_radius_label)
        
        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
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
                "reportedBy" to auth.currentUser?.uid
            )
            db.collection("danger_zones").add(report).addOnSuccessListener {
                Toast.makeText(this, "Raport trimis! Mulțumim.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.setContentView(view)
        dialog.show()
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
                        Toast.makeText(this, "Nu ai gardieni!", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, "SOS trimis gardienilor și locația activată pentru toți!", Toast.LENGTH_LONG).show()
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
            .setTitle("ALERTĂ SOS!")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ ->
                db.collection("sos_alerts").document(alertId).update("status", "read")
            }
            .setCancelable(false)
            .show()
    }

    private fun showSignOutConfirmation() {
        AlertDialog.Builder(this)
            .setMessage("Ieși din cont?")
            .setPositiveButton("Da") { _, _ -> signOut() }
            .setNegativeButton("Nu", null)
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

        db.collection("users").document(uid).addSnapshotListener { snapshot, _ ->
            if (snapshot != null && snapshot.exists()) {
                val profileUrl = snapshot.getString("profileImageUrl")
                val trust = snapshot.getLong("trustFactor")?.toInt() ?: 0
                val rank = UserRank.fromTrustFactor(trust)

                if (mainRankFrame != null) applyRankFrame(mainRankFrame, rank)
                if (!isFinishing && ivMainProfile != null) {
                    Glide.with(this).load(profileUrl ?: android.R.drawable.ic_menu_gallery).circleCrop().into(ivMainProfile)
                }
            }
        }
    }

    private fun applyRankFrame(view: View, rank: UserRank) {
        val gd = GradientDrawable()
        gd.setColor(Color.TRANSPARENT)
        gd.setStroke(8, rank.color)
        gd.shape = GradientDrawable.OVAL
        view.background = gd
    }

    private fun setupNavigationHeader(navView: NavigationView) {
        val header = navView.getHeaderView(0)
        val tvUser = header.findViewById<TextView>(R.id.tv_username)
        val tvRank = header.findViewById<TextView>(R.id.tv_nav_rank)
        val ivPhoto = header.findViewById<ImageView>(R.id.iv_user_photo)
        val rankFrame = header.findViewById<View>(R.id.nav_rank_frame)
        
        header.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        
        auth.currentUser?.let { user ->
            db.collection("users").document(user.uid).addSnapshotListener { doc, _ ->
                if (doc != null && doc.exists()) {
                    val trust = doc.getLong("trustFactor")?.toInt() ?: 0
                    val rank = UserRank.fromTrustFactor(trust)
                    tvUser.text = doc.getString("username")
                    tvRank.text = rank.displayName
                    tvRank.setTextColor(rank.color)
                    if (rankFrame != null) applyRankFrame(rankFrame, rank)
                    if (!isFinishing) Glide.with(this).load(doc.getString("profileImageUrl") ?: android.R.drawable.ic_menu_gallery).circleCrop().into(ivPhoto)
                }
            }
        }
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
            .setTitle("Cerere Gardian")
            .setMessage("$name vrea să îi fii gardian. Accepți?")
            .setPositiveButton("Accept") { _, _ -> updateConnectionStatus(connectionId, "accepted") }
            .setNegativeButton("Refuz") { _, _ -> updateConnectionStatus(connectionId, "rejected") }
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

    private fun refreshMarkerIcon(uid: String) {
        val marker = protectedMarkers[uid] ?: return
        val baseBitmap = userBitmaps[uid] ?: return
        val isEmergency = usersInEmergency.containsKey(uid)
        
        val size = 120
        val margin = 24
        val finalBitmap = createBitmap(size + margin, size + margin, Bitmap.Config.ARGB_8888)
        
        val centerX = (size + margin) / 2f
        val centerY = (size + margin) / 2f
        
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
