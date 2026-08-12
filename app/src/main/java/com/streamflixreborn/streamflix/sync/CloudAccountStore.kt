package com.streamflixreborn.streamflix.sync

import android.content.Context

object CloudAccountStore {
    private const val PREFS = "cloud_account_state"
    private const val ACTIVE_USER = "active_user_id"
    private const val LEGACY_OWNER = "legacy_owner_id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun activeUserId(context: Context): String? =
        prefs(context).getString(ACTIVE_USER, null)

    fun setActiveUserId(context: Context, userId: String?) {
        prefs(context).edit().apply {
            if (userId == null) remove(ACTIVE_USER) else putString(ACTIVE_USER, userId)
        }.apply()
    }

    fun legacyOwnerId(context: Context): String? =
        prefs(context).getString(LEGACY_OWNER, null)

    fun claimLegacyData(context: Context, userId: String) {
        prefs(context).edit().putString(LEGACY_OWNER, userId).apply()
    }
}
