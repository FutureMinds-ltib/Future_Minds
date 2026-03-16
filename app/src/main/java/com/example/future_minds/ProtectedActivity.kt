package com.example.future_minds

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ProtectedActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var llProtectedList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_protected)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        llProtectedList = findViewById(R.id.ll_protected_list)
        val btnBack = findViewById<Button>(R.id.btn_back_from_protected)

        loadProtectedUsers()

        btnBack.setOnClickListener { finish() }
    }

    private fun loadProtectedUsers() {
        val currentUser = auth.currentUser ?: return
        
        // Căutăm conexiunile unde eu sunt gardianul și sunt acceptate
        db.collection("connections")
            .whereEqualTo("guardianUid", currentUser.uid)
            .whereEqualTo("status", "accepted")
            .addSnapshotListener { snapshots, e ->
                if (e != null) return@addSnapshotListener
                
                llProtectedList.removeAllViews()
                
                for (doc in snapshots!!) {
                    val pName = doc.getString("protectedUsername") ?: "Unknown"
                    val connectionId = doc.id
                    
                    addProtectedToUI(pName, connectionId)
                }
            }
    }

    private fun addProtectedToUI(name: String, connectionId: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_user_connection, llProtectedList, false)
        view.findViewById<TextView>(R.id.tv_item_username).text = name
        view.findViewById<TextView>(R.id.tv_item_status).text = "Ești gardianul lui"
        
        view.findViewById<ImageButton>(R.id.btn_remove_connection).setOnClickListener {
            db.collection("connections").document(connectionId).delete()
                .addOnSuccessListener { Toast.makeText(this, "Nu mai ești gardianul lui $name", Toast.LENGTH_SHORT).show() }
        }
        
        llProtectedList.addView(view)
    }
}
