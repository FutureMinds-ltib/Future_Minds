package com.example.future_minds

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage

class ProfileActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var storage: FirebaseStorage

    private lateinit var ivProfileLarge: ImageView
    private lateinit var rankFrame: View
    private lateinit var tvUsername: TextView
    private lateinit var tvRank: TextView
    private lateinit var tvTrustFactor: TextView
    private lateinit var rvCommunity: RecyclerView

    private var selectedImageUri: Uri? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedImageUri = result.data?.data
            selectedImageUri?.let { uploadProfilePicture(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        storage = FirebaseStorage.getInstance()

        ivProfileLarge = findViewById(R.id.iv_profile_large)
        rankFrame = findViewById(R.id.rank_frame)
        tvUsername = findViewById(R.id.tv_profile_username)
        tvRank = findViewById(R.id.tv_profile_rank)
        tvTrustFactor = findViewById(R.id.tv_trust_factor)
        rvCommunity = findViewById(R.id.rv_community_ranks)

        findViewById<ImageButton>(R.id.btn_profile_back).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btn_change_photo).setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK)
            intent.type = "image/*"
            pickImageLauncher.launch(intent)
        }

        findViewById<Button>(R.id.btn_remove_photo).setOnClickListener {
            removeProfilePicture()
        }

        loadUserData()
        loadCommunityRanking()
    }

    private fun loadUserData() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).addSnapshotListener { snapshot, _ ->
            if (snapshot != null && snapshot.exists()) {
                val username = snapshot.getString("username") ?: "User"
                val trustFactor = snapshot.getLong("trustFactor")?.toInt() ?: 0
                val profileUrl = snapshot.getString("profileImageUrl")

                tvUsername.text = username
                tvTrustFactor.text = "Trust Factor: $trustFactor"
                
                val rank = UserRank.fromTrustFactor(trustFactor)
                tvRank.text = "Rank: ${rank.displayName}"
                tvRank.setTextColor(rank.color)
                
                applyRankFrame(rankFrame, rank)

                if (!isFinishing) {
                    Glide.with(this)
                        .load(profileUrl ?: android.R.drawable.ic_menu_gallery)
                        .circleCrop()
                        .into(ivProfileLarge)
                }
            }
        }
    }

    private fun applyRankFrame(view: View, rank: UserRank) {
        val strokeWidth = 8
        val gd = GradientDrawable()
        gd.setColor(android.graphics.Color.TRANSPARENT)
        gd.setStroke(strokeWidth, rank.color)
        gd.shape = GradientDrawable.OVAL
        
        if (rank == UserRank.SCOLAR_PATRON) {
            gd.setStroke(12, rank.color, 10f, 5f)
        }
        
        view.background = gd
    }

    private fun uploadProfilePicture(uri: Uri) {
        val uid = auth.currentUser?.uid ?: return
        val ref = storage.reference.child("profile_pictures/$uid.jpg")

        ref.putFile(uri).addOnSuccessListener {
            ref.downloadUrl.addOnSuccessListener { downloadUri ->
                db.collection("users").document(uid)
                    .update("profileImageUrl", downloadUri.toString())
                    .addOnSuccessListener {
                        Toast.makeText(this, "Profile picture updated!", Toast.LENGTH_SHORT).show()
                    }
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Upload failed: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeProfilePicture() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid)
            .update("profileImageUrl", null)
            .addOnSuccessListener {
                Toast.makeText(this, "Profile picture removed!", Toast.LENGTH_SHORT).show()
            }
    }

    private fun loadCommunityRanking() {
        db.collection("users")
            .orderBy("trustFactor", Query.Direction.DESCENDING)
            .limit(10)
            .get()
            .addOnSuccessListener { documents ->
                val userList = mutableListOf<UserProfile>()
                for (doc in documents) {
                    val user = doc.toObject(UserProfile::class.java).copy(uid = doc.id)
                    userList.add(user)
                }
                rvCommunity.layoutManager = LinearLayoutManager(this)
                rvCommunity.adapter = UserAdapter(userList)
            }
    }
}