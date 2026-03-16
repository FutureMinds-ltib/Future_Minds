package com.example.future_minds

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private lateinit var tvUsername: TextView
    private lateinit var tvRank: TextView
    private lateinit var tvTrustFactor: TextView
    private lateinit var rankFrame: View
    private lateinit var rvCommunity: RecyclerView

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val imageUri = result.data?.data
            if (imageUri != null) {
                uploadProfileImage(imageUri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        storage = FirebaseStorage.getInstance()

        ivProfileLarge = findViewById(R.id.iv_profile_large)
        tvUsername = findViewById(R.id.tv_profile_username)
        tvRank = findViewById(R.id.tv_profile_rank)
        tvTrustFactor = findViewById(R.id.tv_trust_factor)
        rankFrame = findViewById(R.id.rank_frame)
        rvCommunity = findViewById(R.id.rv_community_ranks)

        findViewById<View>(R.id.btn_profile_back).setOnClickListener { finish() }

        findViewById<View>(R.id.btn_change_photo).setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            pickImageLauncher.launch(intent)
        }

        findViewById<View>(R.id.btn_remove_photo).setOnClickListener {
            removeProfilePhoto()
        }

        loadUserData()
        setupCommunityRanking()
    }

    private fun loadUserData() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).addSnapshotListener { snapshot, _ ->
            if (isFinishing || isDestroyed) return@addSnapshotListener
            if (snapshot != null && snapshot.exists()) {
                val username = snapshot.getString("username") ?: "User"
                val profileUrl = snapshot.getString("profileImageUrl")
                val trustFactor = snapshot.getLong("trustFactor")?.toInt() ?: 0
                val rank = UserRank.fromTrustFactor(trustFactor)

                tvUsername.text = username
                tvRank.text = "Rank: ${rank.displayName}"
                tvRank.setTextColor(rank.color)
                tvTrustFactor.text = "Trust Factor: $trustFactor"
                applyRankFrame(rankFrame, rank)

                Glide.with(this)
                    .load(profileUrl ?: android.R.drawable.ic_menu_gallery)
                    .circleCrop()
                    .into(ivProfileLarge)
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

    private fun uploadProfileImage(uri: Uri) {
        val uid = auth.currentUser?.uid ?: return
        val ref = storage.reference.child("profile_images/$uid.jpg")
        
        Toast.makeText(this, "Se încarcă imaginea...", Toast.LENGTH_SHORT).show()
        
        ref.putFile(uri).addOnSuccessListener {
            ref.downloadUrl.addOnSuccessListener { downloadUri ->
                db.collection("users").document(uid).update("profileImageUrl", downloadUri.toString())
                    .addOnSuccessListener {
                        Toast.makeText(this, "Imagine actualizată!", Toast.LENGTH_SHORT).show()
                    }
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Eroare la încărcare: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeProfilePhoto() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).update("profileImageUrl", null)
            .addOnSuccessListener {
                Toast.makeText(this, "Imagine eliminată!", Toast.LENGTH_SHORT).show()
            }
    }

    private fun setupCommunityRanking() {
        rvCommunity.layoutManager = LinearLayoutManager(this)
        db.collection("users")
            .orderBy("trustFactor", Query.Direction.DESCENDING)
            .limit(10)
            .get()
            .addOnSuccessListener { snapshots ->
                if (isFinishing || isDestroyed) return@addOnSuccessListener
                val users = snapshots.documents.map { it.data ?: emptyMap<String, Any>() }
                rvCommunity.adapter = CommunityAdapter(users)
            }
    }

    private inner class CommunityAdapter(private val users: List<Map<String, Any>>) : 
        RecyclerView.Adapter<CommunityAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_item_rank_name)
            val tvFactor: TextView = view.findViewById(R.id.tv_item_rank_factor)
            val ivPhoto: ImageView = view.findViewById(R.id.iv_item_rank_photo)
            val frame: View = view.findViewById(R.id.item_rank_frame)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_user_rank, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val user = users[position]
            val name = user["username"] as? String ?: "User"
            val factor = (user["trustFactor"] as? Long)?.toInt() ?: 0
            val photoUrl = user["profileImageUrl"] as? String
            val rank = UserRank.fromTrustFactor(factor)

            holder.tvName.text = "${position + 1}. $name"
            holder.tvFactor.text = "TF: $factor"
            holder.tvName.setTextColor(rank.color)
            
            val gd = GradientDrawable()
            gd.setColor(Color.TRANSPARENT)
            gd.setStroke(4, rank.color)
            gd.shape = GradientDrawable.OVAL
            holder.frame.background = gd

            Glide.with(holder.itemView.context)
                .load(photoUrl ?: android.R.drawable.ic_menu_gallery)
                .circleCrop()
                .into(holder.ivPhoto)
        }

        override fun getItemCount() = users.size
    }
}
