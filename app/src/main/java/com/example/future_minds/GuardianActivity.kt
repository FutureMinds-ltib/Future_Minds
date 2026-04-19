package com.example.future_minds

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class GuardianActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var llGuardiansList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guardian)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        llGuardiansList = findViewById(R.id.ll_guardians_list)
        val etUsername = findViewById<EditText>(R.id.et_guardian_username)
        val etPhone = findViewById<EditText>(R.id.et_guardian_phone)
        val btnSave = findViewById<Button>(R.id.btn_save_guardian)
        // Fixed ClassCastException: btn_back_from_guardian is a TextView in XML
        val btnBack = findViewById<View>(R.id.btn_back_from_guardian)

        loadMyGuardians()

        btnSave.setOnClickListener {
            val gUsername = etUsername.text.toString().trim()
            val gPhone = etPhone.text.toString().trim()

            if (gUsername.isNotEmpty() && gPhone.isNotEmpty()) {
                // PAS 1: Verificăm dacă utilizatorul curent (EU) are numărul verificat
                checkMyVerificationStatus { isMeVerified ->
                    if (isMeVerified) {
                        // PAS 2: Dacă eu sunt OK, verificăm și gardianul
                        checkGuardianExists(gUsername, gPhone)
                    } else {
                        Toast.makeText(this, getString(R.string.phone_not_confirmed_msg), Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Toast.makeText(this, getString(R.string.fill_all_fields), Toast.LENGTH_SHORT).show()
            }
        }

        btnBack.setOnClickListener { finish() }
    }

    private fun checkMyVerificationStatus(callback: (Boolean) -> Unit) {
        val currentUser = auth.currentUser ?: return
        db.collection("users").document(currentUser.uid).get()
            .addOnSuccessListener { doc ->
                val verified = doc.getBoolean("phoneVerified") ?: false
                callback(verified)
            }
            .addOnFailureListener {
                callback(false)
            }
    }

    private fun loadMyGuardians() {
        val currentUser = auth.currentUser ?: return
        
        db.collection("connections")
            .whereEqualTo("protectedUid", currentUser.uid)
            .addSnapshotListener { snapshots, e ->
                if (e != null) return@addSnapshotListener
                
                llGuardiansList.removeAllViews()
                
                for (doc in snapshots!!) {
                    val gName = doc.getString("guardianUsername") ?: "Unknown"
                    val status = doc.getString("status") ?: "pending"
                    val shareLocation = doc.getBoolean("shareLocation") ?: false
                    val connectionId = doc.id
                    val guardianUid = doc.getString("guardianUid") ?: ""
                    
                    addGuardianToUI(gName, status, shareLocation, connectionId, guardianUid)
                }
            }
    }

    private fun addGuardianToUI(name: String, status: String, shareLocation: Boolean, connectionId: String, guardianUid: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_user_connection, llGuardiansList, false)
        val ivProfile = view.findViewById<ImageView>(R.id.iv_connection_profile)
        view.findViewById<TextView>(R.id.tv_item_username).text = name
        view.findViewById<TextView>(R.id.tv_item_status).text = getString(R.string.status_format, status)
        
        @SuppressLint("UseSwitchCompatOrMaterialCode")
        val swShare = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_share_location)
        if (status == "accepted") {
            swShare.visibility = View.VISIBLE
            swShare.isChecked = shareLocation
            swShare.setOnCheckedChangeListener { _, isChecked ->
                db.collection("connections").document(connectionId).update("shareLocation", isChecked)
            }
        } else {
            swShare.visibility = View.GONE
        }

        db.collection("users").document(guardianUid).get().addOnSuccessListener { doc ->
            val url = doc.getString("profileImageUrl")
            if (!isFinishing && url != null) {
                Glide.with(this).load(url).circleCrop().into(ivProfile)
            }
        }
        
        view.findViewById<ImageButton>(R.id.btn_remove_connection).setOnClickListener {
            db.collection("connections").document(connectionId).delete()
                .addOnSuccessListener { Toast.makeText(this, getString(R.string.guardian_removed), Toast.LENGTH_SHORT).show() }
        }
        
        llGuardiansList.addView(view)
    }

    private fun checkGuardianExists(username: String, phone: String) {
        db.collection("users")
            .whereEqualTo("username", username)
            .get()
            .addOnSuccessListener { documents ->
                if (!documents.isEmpty) {
                    val guardianDoc = documents.documents[0]
                    val guardianUid = guardianDoc.id
                    val actualPhone = guardianDoc.getString("phone")
                    val isPhoneVerified = guardianDoc.getBoolean("phoneVerified") ?: false

                    if (actualPhone == phone) {
                        if (isPhoneVerified) {
                            sendGuardianRequest(guardianUid, username)
                        } else {
                            Toast.makeText(this, getString(R.string.guardian_no_phone_verified, username), Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(this, getString(R.string.phone_mismatch), Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, getString(R.string.username_not_found), Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun sendGuardianRequest(guardianUid: String, guardianName: String) {
        val currentUser = auth.currentUser ?: return
        
        db.collection("connections")
            .whereEqualTo("protectedUid", currentUser.uid)
            .whereEqualTo("guardianUid", guardianUid)
            .get()
            .addOnSuccessListener { docs ->
                if (docs.isEmpty) {
                    val connection = hashMapOf(
                        "protectedUid" to currentUser.uid,
                        "protectedUsername" to "User",
                        "guardianUid" to guardianUid,
                        "guardianUsername" to guardianName,
                        "status" to "pending",
                        "shareLocation" to false
                    )
                    
                    db.collection("users").document(currentUser.uid).get().addOnSuccessListener { myDoc ->
                        connection["protectedUsername"] = myDoc.getString("username") ?: getString(R.string.guest_user)
                        db.collection("connections").add(connection)
                            .addOnSuccessListener {
                                Toast.makeText(this, getString(R.string.request_sent), Toast.LENGTH_SHORT).show()
                            }
                    }
                } else {
                    Toast.makeText(this, getString(R.string.already_connected), Toast.LENGTH_SHORT).show()
                }
            }
    }
}
