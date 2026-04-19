package com.example.future_minds

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class FavoritesActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var etHome: EditText
    private lateinit var etSchool: EditText
    private lateinit var etPark: EditText
    private lateinit var btnSave: Button
    private lateinit var btnBack: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        etHome = findViewById(R.id.et_fav_home)
        etSchool = findViewById(R.id.et_fav_school)
        etPark = findViewById(R.id.et_fav_park)
        btnSave = findViewById(R.id.btn_save_favorites)
        btnBack = findViewById(R.id.btn_fav_back)

        loadFavorites()

        btnSave.setOnClickListener {
            saveFavorites()
        }

        btnBack.setOnClickListener {
            finish()
        }
    }

    private fun loadFavorites() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get().addOnSuccessListener { document ->
            if (document != null && document.exists()) {
                val favorites = document.get("favorites") as? Map<*, *>
                etHome.setText(favorites?.get("home")?.toString() ?: "")
                etSchool.setText(favorites?.get("school")?.toString() ?: "")
                etPark.setText(favorites?.get("park")?.toString() ?: "")
            }
        }
    }

    private fun saveFavorites() {
        val uid = auth.currentUser?.uid ?: return
        val favorites = hashMapOf(
            "home" to etHome.text.toString().trim(),
            "school" to etSchool.text.toString().trim(),
            "park" to etPark.text.toString().trim()
        )

        db.collection("users").document(uid).update("favorites", favorites)
            .addOnSuccessListener {
                Toast.makeText(this, getString(R.string.favorites_saved_success), Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, getString(R.string.error_saving_favorites) + ": ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
}
