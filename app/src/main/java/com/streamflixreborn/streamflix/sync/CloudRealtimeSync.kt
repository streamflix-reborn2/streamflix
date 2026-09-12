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
    private var activeProfileId: String? = null
    private var activeClient: io.github.jan.supabase.SupabaseClient? = null
    private var channel: RealtimeChannel? = null
    private var collectorJob: Job? = null

    fun isOwnedBy(profileId: String): Boolean = activeProfileId == profileId

    suspend fun start(context: Context, profileId: String, userId: String) {
        if (!SupabaseProvider.isConfigured) return
        val appContext = context.applicationContext

        lifecycleMutex.withLock {
            if (activeProfileId == profileId && activeUserId == userId &&
                channel?.status?.value == RealtimeChannel.Status.SUBSCRIBED
            ) {
                return@withLock
            }

            stopLocked()

            val client = SupabaseProvider.clientFor(appContext, profileId)
            val newChannel = client.realtime.channel(
                "user-media-state-$profileId-$userId",
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
                        CloudSyncManager.applyRealtimeState(appContext, profileId, state)
                    }
                }
                .catch { error ->
                    Log.w(TAG, "Realtime media synchronization stopped", error)
                    CloudSyncScheduler.enqueue(appContext)
                }
                .launchIn(scope)

            try {
                newChannel.subscribe(blockUntilSubscribed = true)
                activeProfileId = profileId
                activeUserId = userId
                activeClient = client
                channel = newChannel
                collectorJob = newCollector
                Log.i(TAG, "Listening for media changes")
            } catch (error: Throwable) {
                newCollector.cancel()
                runCatching {
                    client.realtime.removeChannel(newChannel)
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
        val client = activeClient
        channel?.let { existingChannel ->
            runCatching {
                client?.realtime?.removeChannel(existingChannel)
            }.onFailure { error ->
                Log.w(TAG, "Could not stop realtime media synchronization", error)
            }
        }
        channel = null
        activeClient = null
        activeProfileId = null
        activeUserId = null
    }
}
