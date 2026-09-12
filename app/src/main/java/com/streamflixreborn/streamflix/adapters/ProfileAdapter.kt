package com.streamflixreborn.streamflix.adapters

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.models.Profile
import com.streamflixreborn.streamflix.utils.ProfileManager

class ProfileAdapter(
    private val onProfileClick: (Profile) -> Unit,
    private val onProfileLongClick: (Profile) -> Unit,
    private val layoutResId: Int = R.layout.item_profile_mobile,
) : ListAdapter<Profile, ProfileAdapter.ProfileViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val root = LayoutInflater.from(parent.context).inflate(layoutResId, parent, false)
        return ProfileViewHolder(root)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ProfileViewHolder(item: View) : RecyclerView.ViewHolder(item) {
        private val ivProfileAvatar: ImageView = itemView.findViewById(R.id.iv_profile_avatar)
        private val tvProfileInitial: TextView = itemView.findViewById(R.id.tv_profile_initial)
        private val tvProfileName: TextView = itemView.findViewById(R.id.tv_profile_name)
        private val tvProfileCurrent: TextView? = itemView.findViewById(R.id.tv_profile_current)

        fun bind(profile: Profile) {
            itemView.isSelected = profile.id == ProfileManager.activeProfileId
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * itemView.resources.displayMetrics.density
                setColor(profile.avatarColor)
            }
            ivProfileAvatar.setImageDrawable(drawable)

            val initial = profile.name.firstOrNull()?.toString()?.uppercase() ?: "?"
            tvProfileInitial.text = initial
            tvProfileName.text = profile.name
            tvProfileCurrent?.apply {
                visibility = View.VISIBLE
                alpha = if (profile.id == ProfileManager.activeProfileId) 1f else 0f
                contentDescription = text
            }
            itemView.contentDescription = itemView.context.getString(
                R.string.profile_card_content_description,
                profile.name,
            )

            itemView.setOnClickListener { onProfileClick(profile) }
            itemView.setOnLongClickListener {
                onProfileLongClick(profile)
                true
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<Profile>() {
        override fun areItemsTheSame(oldItem: Profile, newItem: Profile): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Profile, newItem: Profile): Boolean =
            oldItem == newItem
    }
}
