package com.streamflixreborn.streamflix.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val avatarColor: Int = 0xFF1E88E5.toInt(),
    val createdAt: Long = System.currentTimeMillis(),
    val position: Int = 0,
)
