package com.streamflixreborn.streamflix.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class CloudSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    companion object {
        const val PROFILE_ID = "profile_id"
        const val USER_ID = "user_id"
    }

    override suspend fun doWork(): Result = try {
        val profileId = inputData.getString(PROFILE_ID) ?: return Result.success()
        val expectedUserId = inputData.getString(USER_ID) ?: return Result.success()
        if (!SupabaseProvider.isConfigured ||
            CloudSyncManager.currentUserId(profileId) != expectedUserId ||
            com.streamflixreborn.streamflix.utils.ProfileManager.activeProfileId != profileId
        ) {
            Result.success()
        } else {
            CloudSyncManager.syncNow(applicationContext, profileId)
            Result.success()
        }
    } catch (_: Throwable) {
        Result.retry()
    }
}
