package com.streamflixreborn.streamflix.sync

import android.content.Context
import android.util.Log
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecordOrNull
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object CloudRealtimeSync {
    private const val TAG = "CloudRealtime"
    private const val TABLE = "user_media_state"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()

    private var activeUserId: String? = null
    private var channel: RealtimeChannel? = null
    private var collectorJob: Job? = null

    suspend fun start(context: Context, userId: String) {
        if (!SupabaseProvider.isConfigured) return
        val appContext = context.applicationContext

        lifecycleMutex.withLock {
            if (activeUserId == userId &&
                channel?.status?.value == RealtimeChannel.Status.SUBSCRIBED
            ) {
                return@withLock
            }

            stopLocked()

            val newChannel = SupabaseProvider.client.realtime.channel(
                "user-media-state-$userId",
            )
            val changes = newChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = TABLE
                filter("user_id", FilterOperator.EQ, userId)
            }

            val newCollector = changes
                .onEach { action ->
                    val state = when (action) {
                        is PostgresAction.Insert ->
                            action.decodeRecordOrNull<RemoteMediaState>()
                        is PostgresAction.Update ->
                            action.decodeRecordOrNull<RemoteMediaState>()
                        else -> null
                    }
                    if (state != null) {
                        CloudSyncManager.applyRealtimeState(appContext, state)
                    }
                }
                .catch { error ->
                    Log.w(TAG, "Realtime media synchronization stopped", error)
                    CloudSyncScheduler.enqueue(appContext)
                }
                .launchIn(scope)

            try {
                newChannel.subscribe(blockUntilSubscribed = true)
                activeUserId = userId
                channel = newChannel
                collectorJob = newCollector
                Log.i(TAG, "Listening for media changes")
            } catch (error: Throwable) {
                newCollector.cancel()
                runCatching {
                    SupabaseProvider.client.realtime.removeChannel(newChannel)
                }
                Log.w(TAG, "Could not start realtime media synchronization", error)
            }
        }
    }

    suspend fun stop() {
        lifecycleMutex.withLock {
            stopLocked()
        }
    }

    private suspend fun stopLocked() {
        collectorJob?.cancelAndJoin()
        collectorJob = null
        channel?.let { existingChannel ->
            runCatching {
                SupabaseProvider.client.realtime.removeChannel(existingChannel)
            }.onFailure { error ->
                Log.w(TAG, "Could not stop realtime media synchronization", error)
            }
        }
        channel = null
        activeUserId = null
    }
}
