package com.streamflixreborn.streamflix.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.streamflixreborn.streamflix.database.dao.ProfileDao
import com.streamflixreborn.streamflix.models.Profile

@Database(
    entities = [Profile::class],
    version = 1,
    exportSchema = false,
)
abstract class ProfileDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao

    companion object {
        @Volatile
        private var INSTANCE: ProfileDatabase? = null

        fun getInstance(context: Context): ProfileDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context = context.applicationContext,
                    klass = ProfileDatabase::class.java,
                    name = "profiles.db",
                ).build().also { INSTANCE = it }
            }
        }
    }
}
