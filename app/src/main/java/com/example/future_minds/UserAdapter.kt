package com.example.future_minds

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

data class UserProfile(
    val uid: String = "",
    val username: String = "",
    val trustFactor: Int = 0,
    val profileImageUrl: String? = null
)

class UserAdapter(private val users: List<UserProfile>) : RecyclerView.Adapter<UserAdapter.UserViewHolder>() {

    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivProfile: ImageView = view.findViewById(R.id.iv_item_profile)
        val tvUsername: TextView = view.findViewById(R.id.tv_item_username)
        val tvRank: TextView = view.findViewById(R.id.tv_item_rank)
        val tvTrust: TextView = view.findViewById(R.id.tv_item_trust)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_user_rank, parent, false)
        return UserViewHolder(view)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        val user = users[position]
        holder.tvUsername.text = user.username
        val rank = UserRank.fromTrustFactor(user.trustFactor)
        holder.tvRank.text = rank.displayName
        holder.tvRank.setTextColor(rank.color)
        holder.tvTrust.text = "TF: ${user.trustFactor}"

        if (user.profileImageUrl != null) {
            Glide.with(holder.itemView.context).load(user.profileImageUrl).circleCrop().into(holder.ivProfile)
        } else {
            holder.ivProfile.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }

    override fun getItemCount() = users.size
}