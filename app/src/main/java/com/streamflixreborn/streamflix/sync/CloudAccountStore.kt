package com.streamflixreborn.streamflix.sync

import android.content.Context

object CloudAccountStore {
    private const val PREFS = "cloud_account_state"
    private const val ACTIVE_USER = "active_user_id"
    private const val ACTIVE_EMAIL = "active_user_email"
    private const val LEGACY_OWNER = "legacy_owner_id"
    private const val MIGRATED = "profile_scope_migrated"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(base: String, profileId: String) = "${base}_$profileId"

    private fun migrateLegacy(context: Context) {
        val preferences = prefs(context)
        if (preferences.getBoolean(MIGRATED, false)) return
        preferences.edit().apply {
            listOf(ACTIVE_USER, ACTIVE_EMAIL, LEGACY_OWNER).forEach { base ->
                preferences.getString(base, null)?.let { putString(key(base, "default"), it) }
                remove(base)
            }
            putBoolean(MIGRATED, true)
        }.apply()
    }

    fun activeUserId(context: Context, profileId: String): String? {
        migrateLegacy(context)
        return prefs(context).getString(key(ACTIVE_USER, profileId), null)
    }

    fun activeUserEmail(context: Context, profileId: String): String? {
        migrateLegacy(context)
        return prefs(context).getString(key(ACTIVE_EMAIL, profileId), null)
    }

    fun setActiveAccount(context: Context, profileId: String, userId: String?, email: String?) {
        migrateLegacy(context)
        prefs(context).edit().apply {
            if (userId == null) remove(key(ACTIVE_USER, profileId)) else putString(key(ACTIVE_USER, profileId), userId)
            if (email == null) remove(key(ACTIVE_EMAIL, profileId)) else putString(key(ACTIVE_EMAIL, profileId), email)
        }.apply()
    }

    fun legacyOwnerId(context: Context, profileId: String): String? {
        migrateLegacy(context)
        return prefs(context).getString(key(LEGACY_OWNER, profileId), null)
    }

    fun claimLegacyData(context: Context, profileId: String, userId: String) {
        migrateLegacy(context)
        prefs(context).edit().putString(key(LEGACY_OWNER, profileId), userId).apply()
    }

    fun profileIdForUser(context: Context, userId: String): String? {
        migrateLegacy(context)
        return prefs(context).all.entries.firstOrNull { (key, value) ->
            (key.startsWith("${ACTIVE_USER}_") || key.startsWith("${LEGACY_OWNER}_")) && value == userId
        }?.let { (key, _) ->
            when {
                key.startsWith("${ACTIVE_USER}_") -> key.removePrefix("${ACTIVE_USER}_")
                else -> key.removePrefix("${LEGACY_OWNER}_")
            }
        }
    }

    fun clearProfile(context: Context, profileId: String) {
        migrateLegacy(context)
        prefs(context).edit().apply {
            remove(key(ACTIVE_USER, profileId))
            remove(key(ACTIVE_EMAIL, profileId))
            remove(key(LEGACY_OWNER, profileId))
        }.apply()
    }
}
