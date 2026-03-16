package com.example.future_minds

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
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
        val btnBack = findViewById<Button>(R.id.btn_back_from_guardian)

        loadMyGuardians()

        btnSave.setOnClickListener {
            val gUsername = etUsername.text.toString().trim()
            val gPhone = etPhone.text.toString().trim()

            if (gUsername.isNotEmpty() && gPhone.isNotEmpty()) {
                checkGuardianExists(gUsername, gPhone)
            } else {
                Toast.makeText(this, "Completează toate câmpurile!", Toast.LENGTH_SHORT).show()
            }
        }

        btnBack.setOnClickListener { finish() }
    }

    private fun loadMyGuardians() {
        val currentUser = auth.currentUser ?: return
        
        db.collection("connections")
            .whereEqualTo("protectedUid", currentUser.uid)
            .addSnapshotListener(this) { snapshots, e ->
                if (e != null) return@addSnapshotListener
                if (isFinishing || isDestroyed) return@addSnapshotListener
                
                llGuardiansList.removeAllViews()
                
                for (doc in snapshots!!) {
                    val gName = doc.getString("guardianUsername") ?: "Unknown"
                    val status = doc.getString("status") ?: "pending"
                    val connectionId = doc.id
                    
                    addGuardianToUI(gName, status, connectionId)
                }
            }
    }

    private fun addGuardianToUI(name: String, status: String, connectionId: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_user_connection, llGuardiansList, false)
        view.findViewById<TextView>(R.id.tv_item_username).text = name
        view.findViewById<TextView>(R.id.tv_item_status).text = "Status: $status"
        
        view.findViewById<ImageButton>(R.id.btn_remove_connection).setOnClickListener {
            db.collection("connections").document(connectionId).delete()
                .addOnSuccessListener { 
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, "Gardian eliminat", Toast.LENGTH_SHORT).show()
                    }
                }
        }
        
        llGuardiansList.addView(view)
    }

    private fun checkGuardianExists(username: String, phone: String) {
        db.collection("users")
            .whereEqualTo("username", username)
            .get()
            .addOnSuccessListener { documents ->
                if (isFinishing || isDestroyed) return@addOnSuccessListener
                
                if (!documents.isEmpty) {
                    val guardianDoc = documents.documents[0]
                    val guardianUid = guardianDoc.id
                    val actualPhone = guardianDoc.getString("phone")

                    if (actualPhone == phone) {
                        sendGuardianRequest(guardianUid, username)
                    } else {
                        Toast.makeText(this, "Numărul de telefon nu corespunde!", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, "Username-ul nu a fost găsit!", Toast.LENGTH_LONG).show()
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
                if (isFinishing || isDestroyed) return@addOnSuccessListener
                
                if (docs.isEmpty) {
                    val connection = hashMapOf(
                        "protectedUid" to currentUser.uid,
                        "protectedUsername" to "User",
                        "guardianUid" to guardianUid,
                        "guardianUsername" to guardianName,
                        "status" to "pending"
                    )
                    
                    db.collection("users").document(currentUser.uid).get().addOnSuccessListener { myDoc ->
                        if (isFinishing || isDestroyed) return@addOnSuccessListener
                        connection["protectedUsername"] = myDoc.getString("username") ?: "User"
                        db.collection("connections").add(connection)
                            .addOnSuccessListener {
                                if (!isFinishing && !isDestroyed) {
                                    Toast.makeText(this, "Cerere trimisă!", Toast.LENGTH_SHORT).show()
                                }
                            }
                    }
                } else {
                    Toast.makeText(this, "Există deja o conexiune!", Toast.LENGTH_SHORT).show()
                }
            }
    }
}
