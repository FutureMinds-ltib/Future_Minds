package com.example.future_minds

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
        val ivProfile: ImageView = view.findViewById(R.id.iv_item_rank_photo)
        val tvUsername: TextView = view.findViewById(R.id.tv_item_rank_name)
        val tvRank: TextView = view.findViewById(R.id.tv_item_rank_label)
        val tvTrust: TextView = view.findViewById(R.id.tv_item_rank_factor)
        val frame: View = view.findViewById(R.id.item_rank_frame)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_user_rank, parent, false)
        return UserViewHolder(view)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        val user = users[position]
        holder.tvUsername.text = user.username
        val rank = UserRank.fromTrustFactor(user.trustFactor)
        holder.tvRank.text = rank.getDisplayName(holder.itemView.context)
        holder.tvRank.setTextColor(rank.color)
        
        holder.tvTrust.text = holder.itemView.context.getString(R.string.trust_factor_label, user.trustFactor)

        // Apply rank frame
        val gd = GradientDrawable()
        gd.setColor(Color.TRANSPARENT)
        gd.setStroke(4, rank.color)
        gd.shape = GradientDrawable.OVAL
        holder.frame.background = gd

        if (user.profileImageUrl != null) {
            Glide.with(holder.itemView.context).load(user.profileImageUrl).circleCrop().into(holder.ivProfile)
        } else {
            holder.ivProfile.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }

    override fun getItemCount() = users.size
}
