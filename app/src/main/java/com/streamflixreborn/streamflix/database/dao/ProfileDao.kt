package com.streamflixreborn.streamflix.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.streamflixreborn.streamflix.models.Profile
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {

    @Query("SELECT * FROM profiles ORDER BY position ASC")
    fun getAllProfiles(): Flow<List<Profile>>

    @Query("SELECT * FROM profiles ORDER BY position ASC")
    suspend fun getAllProfilesList(): List<Profile>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getProfileById(id: String): Profile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: Profile)

    @Update
    suspend fun update(profile: Profile)

    @Delete
    suspend fun delete(profile: Profile)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun getProfileCount(): Int

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM profiles")
    suspend fun getNextPosition(): Int
}
