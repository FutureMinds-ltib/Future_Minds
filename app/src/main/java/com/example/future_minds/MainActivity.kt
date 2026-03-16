package com.example.future_minds

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.navigation.NavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
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

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                setupLocationOverlay()
            } else {
                Toast.makeText(this, "Location permission is required for navigation!", Toast.LENGTH_LONG).show()
            }
        }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        drawerLayout = findViewById(R.id.drawer_layout)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        val btnMenu = findViewById<ImageButton>(R.id.btn_menu)

        btnMenu.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        setupNavigationHeader(navView)
        migrateOldGuardianData() 
        listenForGuardianRequests()

        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_profile -> {
                    val intent = Intent(this, ProfileActivity::class.java)
                    startActivity(intent)
                }
                R.id.nav_guardian -> {
                    val intent = Intent(this, GuardianActivity::class.java)
                    startActivity(intent)
                }
                R.id.nav_protected -> {
                    val intent = Intent(this, ProtectedActivity::class.java)
                    startActivity(intent)
                }
                R.id.nav_logout -> {
                    showSignOutConfirmation()
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        findViewById<View>(R.id.btn_profile_container)?.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        var report_bool=false;
        val report_btn= findViewById<Button>(R.id.button_report)
        report_btn.setOnClickListener { report_bool=true }

        val route_btn= findViewById<Button>(R.id.button_route)
        route_btn.setOnClickListener {
            if(locationSearch.pntBool) {
                calculateSafeRoute(locationSearch.pnt)
            }else Toast.makeText(this, "You must first select a place", Toast.LENGTH_LONG).show()
        }

        val modTestare= findViewById<Switch>(R.id.testare)
        var testare_bool=false
        modTestare?.setOnCheckedChangeListener({ _ , isChecked ->
            if (isChecked) testare_bool=true else testare_bool=false
        })

        val mapController = map.controller
        mapController.setZoom(15.0)
        val startPoint = GeoPoint(44.420483, 26.061319)
        mapController.setCenter(startPoint)

        routeManager = RouteManager(this, map)
        val searchBar = findViewById<AutoCompleteTextView>(R.id.search_bar)
        val searchButton = findViewById<ImageButton>(R.id.btn_search)
        locationSearch = LocationSearch(this, map, searchBar, searchButton)

        val mapEventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p?.let {
                    if(testare_bool){
                        if(report_bool){
                            showReportDialog(it)
                            report_bool=false
                        }else{
                            locationSearch.zoomToLocation(it,"Pin")
                        }
                    }

                }
                return true
            }
        }
        map.overlays.add(MapEventsOverlay(mapEventsReceiver))

        listenForDangerZones()
        checkLocationPermission()
        listenForUserData()
    }

    private fun showSignOutConfirmation() {
        AlertDialog.Builder(this)
            .setMessage("Ești sigur că dorești să ieși din cont?")
            .setPositiveButton("Da") { _, _ ->
                signOut()
            }
            .setNegativeButton("Nu", null)
            .show()
    }

    private fun signOut() {
        auth.signOut()
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun listenForUserData() {
        val uid = auth.currentUser?.uid ?: return
        val ivMainProfile = findViewById<ImageView>(R.id.iv_main_profile)
        val mainRankFrame = findViewById<View>(R.id.main_rank_frame)

        db.collection("users").document(uid).addSnapshotListener { snapshot, _ ->
            if (snapshot != null && snapshot.exists()) {
                val profileUrl = snapshot.getString("profileImageUrl")
                val trustFactor = snapshot.getLong("trustFactor")?.toInt() ?: 0
                val rank = UserRank.fromTrustFactor(trustFactor)

                if (mainRankFrame != null) applyRankFrame(mainRankFrame, rank)
                if (!isFinishing && ivMainProfile != null) {
                    Glide.with(this)
                        .load(profileUrl ?: android.R.drawable.ic_menu_gallery)
                        .circleCrop()
                        .into(ivMainProfile)
                }
            }
        }
    }

    private fun applyRankFrame(view: View, rank: UserRank) {
        val gd = GradientDrawable()
        gd.setColor(Color.TRANSPARENT)
        gd.setStroke(6, rank.color)
        gd.shape = GradientDrawable.OVAL
        view.background = gd
    }

    private fun migrateOldGuardianData() {
        val currentUser = auth.currentUser ?: return
        db.collection("users").document(currentUser.uid).get().addOnSuccessListener { doc ->
            if (doc.contains("guardianUid")) {
                val gUid = doc.getString("guardianUid")
                val gName = doc.getString("guardianUsername")
                val status = doc.getString("guardianStatus")
                val myName = doc.getString("username") ?: "User"

                if (gUid != null) {
                    val connection = hashMapOf(
                        "protectedUid" to currentUser.uid,
                        "protectedUsername" to myName,
                        "guardianUid" to gUid,
                        "guardianUsername" to gName,
                        "status" to status
                    )
                    db.collection("connections").add(connection).addOnSuccessListener {
                        db.collection("users").document(currentUser.uid).update(
                            "guardianUid", FieldValue.delete(),
                            "guardianUsername", FieldValue.delete(),
                            "guardianStatus", FieldValue.delete()
                        )
                    }
                }
            }
        }
    }

    private fun listenForGuardianRequests() {
        val currentUser = auth.currentUser ?: return
        db.collection("connections")
            .whereEqualTo("guardianUid", currentUser.uid)
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snapshots, e ->
                if (e != null) return@addSnapshotListener
                for (doc in snapshots!!) {
                    val requesterUsername = doc.getString("protectedUsername") ?: "Cineva"
                    val connectionId = doc.id
                    showGuardianRequestDialog(requesterUsername, connectionId)
                }
            }
    }

    private fun showGuardianRequestDialog(username: String, connectionId: String) {
        AlertDialog.Builder(this)
            .setTitle("Cerere Gardian")
            .setMessage("$username dorește să îi fii gardian. Ești de acord?")
            .setPositiveButton("Accept") { _, _ -> updateConnectionStatus(connectionId, "accepted") }
            .setNegativeButton("Refuz") { _, _ -> updateConnectionStatus(connectionId, "rejected") }
            .setCancelable(false)
            .show()
    }

    private fun updateConnectionStatus(connectionId: String, newStatus: String) {
        db.collection("connections").document(connectionId)
            .update("status", newStatus)
            .addOnSuccessListener {
                Toast.makeText(this, "Status actualizat: $newStatus", Toast.LENGTH_SHORT).show()
            }
    }

    private fun setupNavigationHeader(navView: NavigationView) {
        val headerView = navView.getHeaderView(0)
        val tvUsername = headerView.findViewById<TextView>(R.id.tv_username)
        val tvNavRank = headerView.findViewById<TextView>(R.id.tv_nav_rank)
        val ivUserPhoto = headerView.findViewById<ImageView>(R.id.iv_user_photo)
        val navRankFrame = headerView.findViewById<View>(R.id.nav_rank_frame)
        
        headerView.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        
        val currentUser = auth.currentUser
        if (currentUser != null) {
            if (currentUser.isAnonymous) {
                tvUsername.text = "Guest User"
                tvNavRank.text = ""
            } else {
                db.collection("users").document(currentUser.uid).addSnapshotListener { document, _ ->
                    if (document != null && document.exists()) {
                        val username = document.getString("username")
                        val trustFactor = document.getLong("trustFactor")?.toInt() ?: 0
                        val profileUrl = document.getString("profileImageUrl")
                        val rank = UserRank.fromTrustFactor(trustFactor)

                        tvUsername.text = username ?: "No Username"
                        tvNavRank.text = rank.displayName
                        tvNavRank.setTextColor(rank.color)
                        applyRankFrame(navRankFrame, rank)

                        if (!isFinishing) {
                            Glide.with(this)
                                .load(profileUrl ?: android.R.drawable.ic_menu_gallery)
                                .circleCrop()
                                .into(ivUserPhoto)
                        }
                    } else {
                        tvUsername.text = currentUser.email
                    }
                }
            }
        }
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            setupLocationOverlay()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun setupLocationOverlay() {
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        locationOverlay.enableMyLocation()
        map.overlays.add(locationOverlay)
        map.invalidate()
    }

    private fun listenForDangerZones() {
        db.collection("reports").addSnapshotListener { snapshots, e ->
            if (e != null) {
                Log.w("Firestore", "Listen failed.", e)
                return@addSnapshotListener
            }
            if (snapshots != null) {
                dangerZoneOverlays.forEach { map.overlays.remove(it) }
                dangerZoneOverlays.clear()
                badZonesList.clear()
                for (document in snapshots) {
                    val lat = document.getDouble("lat") ?: 0.0
                    val lng = document.getDouble("lng") ?: 0.0
                    val radius = document.getDouble("radius") ?: 20.0
                    val type = document.getString("type") ?: "Unsafe Area"
                    val zoneData = mapOf("lat" to lat, "lng" to lng, "radius" to radius, "type" to type)
                    badZonesList.add(zoneData)
                    addDangerZoneToMap(GeoPoint(lat, lng), radius, type, document.id)
                }
                map.invalidate()
            }
        }
    }

    private fun showReportDialog(point: GeoPoint) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.raportari, null)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinner_issue_type)
        val seekBar = dialogView.findViewById<SeekBar>(R.id.seekbar_radius)
        val tvRadius = dialogView.findViewById<TextView>(R.id.tv_radius_label)
        var currentRadius = 20.0
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val radius = if (progress < 5) 5 else progress
                currentRadius = radius.toDouble()
                tvRadius.text = "Affected Area: ${radius}m"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        AlertDialog.Builder(this).setView(dialogView).setPositiveButton("Report") { dialog, _ ->
            val issueType = spinner.selectedItem.toString()
            lifecycleScope.launch {
                saveReportToDatabase(point, currentRadius, issueType)
            }
            dialog.dismiss()
        }.setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }.create().show()
    }

    private fun addDangerZoneToMap(center: GeoPoint, radius: Double, type: String, reportId: String) {
        val circle = Polygon()
        circle.points = Polygon.pointsAsCircle(center, radius)
        val color = when (type) {
            "Unsafe Area" -> Color.argb(100, 255, 0, 0)
            "Construction" -> Color.argb(100, 255, 165, 0)
            "Blocked Path" -> Color.argb(100, 0, 0, 0)
            else -> Color.argb(100, 255, 0, 0)
        }
        circle.fillColor = color
        circle.strokeColor = color
        circle.strokeWidth = 2.0f
        circle.title = "$type (${radius.toInt()}m)"
        
        circle.setOnClickListener { _, _, _ ->
            showConfirmDeleteDialog(reportId)
            true
        }

        map.overlays.add(circle)
        dangerZoneOverlays.add(circle)
    }

    private fun showConfirmDeleteDialog(reportId: String) {
        val currentUser = auth.currentUser
        if (currentUser == null || currentUser.isAnonymous) {
            Toast.makeText(this, "You must be logged in to vote on reports.", Toast.LENGTH_SHORT).show()
            return
        }

        db.collection("users").document(currentUser.uid).get().addOnSuccessListener { userDoc ->
            val userTrust = userDoc.getLong("trustFactor") ?: 0L
            val voteWeight = if (userTrust < 2) 1L else userTrust / 2

            AlertDialog.Builder(this)
                .setTitle("Confirm Report")
                .setMessage("Do you agree with this report?")
                .setPositiveButton("Keep") { _, _ ->
                    updateReportTrust(reportId, voteWeight)
                }
                .setNegativeButton("Remove") { _, _ ->
                    updateReportTrust(reportId, -voteWeight)
                }
                .show()
        }.addOnFailureListener {
            Toast.makeText(this, "Error fetching user data", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateReportTrust(reportId: String, weight: Long) {
        val currentUser = auth.currentUser ?: return
        val reportRef = db.collection("reports").document(reportId)

        reportRef.get().addOnSuccessListener { snapshot ->
            val voters = snapshot.get("voters") as? List<String> ?: listOf()
            if (voters.contains(currentUser.uid)) {
                Toast.makeText(this, "You have already voted on this report!", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }

            val creatorId = snapshot.getString("creatorId") ?: ""

            val updates = hashMapOf(
                "trust" to FieldValue.increment(weight),
                "voters" to FieldValue.arrayUnion(currentUser.uid)
            )

            reportRef.update(updates as Map<String, Any>)
                .addOnSuccessListener {
                    if (creatorId.isNotEmpty() && creatorId != currentUser.uid) {
                        db.collection("users").document(creatorId)
                            .update("trustFactor", FieldValue.increment(weight))
                    }

                    reportRef.get().addOnSuccessListener { updatedSnapshot ->
                        val currentTrust = updatedSnapshot.getLong("trust") ?: 0L
                        if (currentTrust <= 0) {
                            reportRef.delete()
                            Toast.makeText(this, "Report removed.", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Vote registered!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
        }
    }

    private suspend fun getTrust_suspend(): String {
        val currentUser = auth.currentUser ?: return "0"
        if (currentUser.isAnonymous) return "0"

        return try {
            val document = db.collection("users").document(currentUser.uid).get().await()
            val trustVal = document.getLong("trustFactor")?.toString()
                ?: document.getString("trustFactor")
                ?: "0"
            trustVal
        } catch (e: Exception) {
            "0"
        }
    }

    private suspend fun saveReportToDatabase(point: GeoPoint, radius: Double, type: String) {
        val trustStr = getTrust_suspend()
        val currentUser = auth.currentUser ?: return
        val trustValue = trustStr.toLongOrNull() ?: 0L

        val report = hashMapOf(
            "lat" to point.latitude,
            "lng" to point.longitude,
            "radius" to radius,
            "type" to type,
            "timestamp" to Date(),
            "trust" to trustValue,
            "creatorId" to currentUser.uid,
            "voters" to listOf(currentUser.uid)
        )
        db.collection("reports").add(report).addOnSuccessListener {
            Toast.makeText(this, "Report Saved!", Toast.LENGTH_SHORT).show()
            if (!currentUser.isAnonymous) {
                db.collection("users").document(currentUser.uid)
                    .update("trustFactor", FieldValue.increment(10))
            }
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Error saving: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun calculateSafeRoute(destination: GeoPoint) {
        val myLocation = locationOverlay.myLocation
        if (myLocation != null) {
            Toast.makeText(this, "Calculating Safe Route...", Toast.LENGTH_SHORT).show()
            routeManager.getSafeRoute(myLocation, destination, badZonesList)
        } else {
            Toast.makeText(this, "Waiting for GPS...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() { super.onResume() ; map.onResume() ; if (::locationOverlay.isInitialized) locationOverlay.enableMyLocation() }
    override fun onPause() { super.onPause() ; map.onPause() ; if (::locationOverlay.isInitialized) locationOverlay.disableMyLocation() }
}
