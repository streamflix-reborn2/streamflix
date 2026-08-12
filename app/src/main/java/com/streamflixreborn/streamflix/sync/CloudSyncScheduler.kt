package com.streamflixreborn.streamflix.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

object CloudSyncScheduler {
    fun enqueue(context: Context) {
        val userId = CloudSyncManager.currentUserId() ?: return
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "cloud-user-state-$userId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
