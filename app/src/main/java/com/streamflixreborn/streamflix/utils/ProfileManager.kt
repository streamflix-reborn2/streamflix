package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.database.ProfileDatabase
import com.streamflixreborn.streamflix.database.dao.ProfileDao
import com.streamflixreborn.streamflix.models.Profile
import com.streamflixreborn.streamflix.providers.Provider
import kotlinx.coroutines.flow.Flow

object ProfileManager {

    private const val TAG = "ProfileManager"
    private const val GLOBAL_PREFS_NAME = "${BuildConfig.APPLICATION_ID}.profile_global"
    private const val KEY_ACTIVE_PROFILE_ID = "ACTIVE_PROFILE_ID"
    private const val KEY_PROFILE_COLOR_MIGRATED = "PROFILE_COLOR_MIGRATED"
    private const val DEFAULT_PROFILE_ID = "default"
    private val profileColors = intArrayOf(
        0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFE53935.toInt(),
        0xFFFB8C00.toInt(), 0xFF8E24AA.toInt(), 0xFF00ACC1.toInt(),
        0xFFD81B60.toInt(), 0xFF3949AB.toInt(), 0xFF6D4C41.toInt(),
        0xFF546E7A.toInt(),
        0xFF00897B.toInt(), 0xFF7CB342.toInt(), 0xFFC0CA33.toInt(),
        0xFFFDD835.toInt(), 0xFFFFB300.toInt(), 0xFFF4511E.toInt(),
        0xFF5E35B1.toInt(), 0xFF039BE5.toInt(), 0xFFEC407A.toInt(),
        0xFFAD1457.toInt(),
    )

    val profileColorOptions: List<Int> get() = profileColors.toList()

    private lateinit var appContext: Context
    private var profileDao: ProfileDao? = null
    private var _activeProfile: Profile? = null

    val activeProfile: Profile? get() = _activeProfile
    val activeProfileId: String? get() = _activeProfile?.id

    private val globalPrefs: SharedPreferences?
        get() = if (::appContext.isInitialized)
            appContext.getSharedPreferences(GLOBAL_PREFS_NAME, Context.MODE_PRIVATE) else null

    init {
        UserPreferences._profileManagerReady = true
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext

        val db = ProfileDatabase.getInstance(appContext)
        profileDao = db.profileDao()

        val storedId = globalPrefs?.getString(KEY_ACTIVE_PROFILE_ID, null)

        if (storedId == null) {
            createDefaultProfile()
        } else {
            val profile = runCatching {
                kotlinx.coroutines.runBlocking {
                    profileDao?.getProfileById(storedId)
                }
            }.getOrNull()

            if (profile == null) {
                Log.w(TAG, "Stored profile $storedId not found, creating default")
                createDefaultProfile()
            } else {
                _activeProfile = profile
            }
        }

        migrateLegacyProfileColors()
        applyActiveProfilePrefs()
        Log.i(TAG, "Initialized. Active profile: ${_activeProfile?.name} (${_activeProfile?.id})")
    }

    private fun createDefaultProfile() {
        val defaultProfile = Profile(
            id = DEFAULT_PROFILE_ID,
            name = appContext.getString(com.streamflixreborn.streamflix.R.string.profile_default_name),
            position = 0,
        )
        kotlinx.coroutines.runBlocking {
            profileDao?.insert(defaultProfile)
        }

        _activeProfile = defaultProfile
        globalPrefs?.edit()?.putString(KEY_ACTIVE_PROFILE_ID, defaultProfile.id)?.apply()

        migrateLegacyPrefs()
        migrateLegacyDatabasesToDefaultProfile()
        UserDataCache.migrateLegacyCacheToDefaultProfile(appContext, Provider.providers.keys)
        Log.i(TAG, "Created default profile: ${defaultProfile.name}")
    }

    private fun migrateLegacyPrefs() {
        val legacyPrefs = appContext.getSharedPreferences(
            "${BuildConfig.APPLICATION_ID}.preferences",
            Context.MODE_PRIVATE,
        )

        val profilePrefs = getProfilePrefs(DEFAULT_PROFILE_ID)
        profilePrefs.edit().apply {
            legacyPrefs.all.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        putStringSet(key, value as Set<String>)
                    }
                    else -> {}
                }
            }
            commit()
        }

        Log.i(TAG, "Migrated ${legacyPrefs.all.size} legacy preferences to profile: $DEFAULT_PROFILE_ID")
    }

    private fun migrateLegacyDatabasesToDefaultProfile() {
        val currentProviderName = UserPreferences.currentProvider?.name
        val expectedLegacyDbNames = (Provider.providers.keys.map { it.name } + listOfNotNull(currentProviderName))
            .map { AppDatabase.legacyDatabaseNameFor(it) }
            .toSet()

        val legacyDbNames = appContext.databaseList()
            .filter { name ->
                name in expectedLegacyDbNames || (name.startsWith("tmdb_") && name.endsWith(".db"))
            }

        var migratedCount = 0
        legacyDbNames.forEach { legacyDbName ->
            val providerPart = legacyDbName.removeSuffix(".db")
            val defaultDbName = "${AppDatabase.sanitizeDatabasePart(DEFAULT_PROFILE_ID)}_$providerPart.db"
            val legacyDb = appContext.getDatabasePath(legacyDbName)
            val defaultDb = appContext.getDatabasePath(defaultDbName)

            if (!legacyDb.exists()) return@forEach
            if (defaultDb.exists()) return@forEach

            runCatching {
                listOf("", "-wal", "-shm").forEach { suffix ->
                    val source = appContext.getDatabasePath("$legacyDbName$suffix")
                    if (!source.exists()) return@forEach

                    val destination = appContext.getDatabasePath("$defaultDbName$suffix")
                    if (destination.exists()) return@forEach
                    destination.parentFile?.mkdirs()
                    source.copyTo(destination, overwrite = false)
                }
            }.onSuccess {
                migratedCount++
                Log.i(TAG, "Migrated legacy database $legacyDbName to default profile database $defaultDbName")
            }.onFailure { error ->
                Log.e(TAG, "Failed to migrate legacy database $legacyDbName", error)
            }
        }

        Log.i(TAG, "Migrated $migratedCount legacy databases to profile: $DEFAULT_PROFILE_ID")
    }

    private fun migrateLegacyProfileColors() {
        if (globalPrefs?.getBoolean(KEY_PROFILE_COLOR_MIGRATED, false) == true) return

        val profiles = runCatching {
            kotlinx.coroutines.runBlocking {
                profileDao?.getAllProfilesList().orEmpty()
            }
        }.getOrDefault(emptyList())

        var migratedCount = 0
        profiles.forEach { profile ->
            if (profile.avatarColor == profileColors.first()) {
                val migratedColor = profileColors[profile.position.coerceAtLeast(0) % profileColors.size]
                if (migratedColor != profile.avatarColor) {
                    runCatching {
                        kotlinx.coroutines.runBlocking {
                            profileDao?.update(profile.copy(avatarColor = migratedColor))
                        }
                    }.onSuccess {
                        if (profile.id == _activeProfile?.id) {
                            _activeProfile = profile.copy(avatarColor = migratedColor)
                        }
                        migratedCount++
                    }.onFailure { error ->
                        Log.w(TAG, "Failed to migrate color for profile ${profile.id}", error)
                    }
                }
            }
        }

        globalPrefs?.edit()?.putBoolean(KEY_PROFILE_COLOR_MIGRATED, true)?.apply()
        Log.i(TAG, "Migrated $migratedCount legacy profile colors")
    }

    suspend fun switchToProfile(profileId: String, preserveProvider: Boolean = true) {
        val profile = profileDao?.getProfileById(profileId)
        if (profile == null) {
            Log.e(TAG, "Cannot switch to non-existent profile: $profileId")
            return
        }

        val currentProviderName = if (preserveProvider) UserPreferences.getCurrentProviderName() else null

        AppDatabase.resetInstance()
        UserDataCache.clearAll(appContext)

        _activeProfile = profile
        globalPrefs?.edit()?.putString(KEY_ACTIVE_PROFILE_ID, profileId)?.apply()

        applyActiveProfilePrefs()

        if (preserveProvider && currentProviderName != UserPreferences.getCurrentProviderName()) {
            UserPreferences.setCurrentProviderName(currentProviderName)
        }
        // A profile switch changes the database and cached user data even
        // when the selected provider remains the same. Refresh all active
        // provider screens, especially Home, in that case as well.
        ProviderChangeNotifier.notifyProviderChanged()
        Log.i(TAG, "Switched to profile: ${profile.name} (${profile.id})")
    }

    private fun applyActiveProfilePrefs() {
        val profileId = _activeProfile?.id ?: return
        val profilePrefs = getProfilePrefs(profileId)
        UserPreferences.profilePrefs = profilePrefs
        UserPreferences.profileId = profileId
    }

    fun getProfilePrefs(profileId: String): SharedPreferences {
        return appContext.getSharedPreferences(
            "${BuildConfig.APPLICATION_ID}.preferences_${profileId}",
            Context.MODE_PRIVATE,
        )
    }

    fun getAllProfilesFlow(): Flow<List<Profile>>? = profileDao?.getAllProfiles()

    suspend fun getAllProfiles(): List<Profile> = profileDao?.getAllProfilesList() ?: emptyList()

    suspend fun getProfileById(id: String): Profile? = profileDao?.getProfileById(id)

    suspend fun createProfile(name: String): Profile? {
        val pos = profileDao?.getNextPosition() ?: return null
        val profile = Profile(
            name = name.trim().take(30),
            avatarColor = profileColors[pos % profileColors.size],
            position = pos,
        )
        profileDao?.insert(profile)
        Log.i(TAG, "Created profile: ${profile.name} (${profile.id})")
        return profile
    }

    suspend fun renameProfile(id: String, newName: String): Boolean {
        val profile = profileDao?.getProfileById(id) ?: return false
        val updated = profile.copy(name = newName.trim().take(30))
        profileDao?.update(updated)
        if (id == _activeProfile?.id) {
            _activeProfile = updated
        }
        Log.i(TAG, "Renamed profile $id to: $newName")
        return true
    }

    suspend fun setProfileColor(id: String, color: Int): Boolean {
        if (color !in profileColors) return false
        val profile = profileDao?.getProfileById(id) ?: return false
        val updated = profile.copy(avatarColor = color)
        profileDao?.update(updated)
        if (id == _activeProfile?.id) {
            _activeProfile = updated
        }
        Log.i(TAG, "Changed profile color for $id")
        return true
    }

    suspend fun deleteProfile(id: String): Boolean {
        val allProfiles = profileDao?.getAllProfilesList() ?: return false
        if (allProfiles.size <= 1) return false

        val profile = profileDao?.getProfileById(id) ?: return false
        profileDao?.delete(profile)

        val profilePrefs = getProfilePrefs(id)
        profilePrefs.edit().clear().commit()

        if (id == _activeProfile?.id) {
            val next = allProfiles.firstOrNull { it.id != id }
            if (next != null) switchToProfile(next.id)
        }

        Log.i(TAG, "Deleted profile: $id")
        return true
    }

    suspend fun getProfileCount(): Int = profileDao?.getProfileCount() ?: 1
}
