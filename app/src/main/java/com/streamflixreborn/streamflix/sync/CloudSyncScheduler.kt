package com.streamflixreborn.streamflix.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Data

object CloudSyncScheduler {
    fun enqueue(context: Context) {
        val profileId = com.streamflixreborn.streamflix.utils.ProfileManager.activeProfileId ?: return
        val userId = CloudSyncManager.currentUserId(profileId) ?: return
        enqueue(context, profileId, userId)
    }

    fun enqueue(context: Context, profileId: String, userId: String) {
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setInputData(Data.Builder()
                .putString(CloudSyncWorker.PROFILE_ID, profileId)
                .putString(CloudSyncWorker.USER_ID, userId)
                .build())
            .addTag("cloud-profile-$profileId")
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "cloud-user-state-$profileId-$userId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
