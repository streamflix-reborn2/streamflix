package com.streamflixreborn.streamflix.sync

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

object CloudMutationStore {
    private const val PREFS = "cloud_sync_queue"
    private const val QUEUE = "pending_media_states"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(RemoteMediaState.serializer())

    @Synchronized
    fun enqueue(context: Context, state: RemoteMediaState) {
        val current = read(context).associateByTo(linkedMapOf()) { it.queueKey }
        current[state.queueKey] = state
        write(context, current.values.toList())
    }

    @Synchronized
    fun pendingForUser(context: Context, userId: String): List<RemoteMediaState> =
        read(context).filter { it.userId == userId }

    @Synchronized
    fun acknowledge(context: Context, uploaded: List<RemoteMediaState>) {
        if (uploaded.isEmpty()) return
        val uploadedVersions = uploaded.associate { it.queueKey to it.clientUpdatedAtMillis }
        val remaining = read(context).filter { state ->
            val uploadedVersion = uploadedVersions[state.queueKey]
            uploadedVersion == null || state.clientUpdatedAtMillis > uploadedVersion
        }
        write(context, remaining)
    }

    private fun read(context: Context): List<RemoteMediaState> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(QUEUE, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private fun write(context: Context, states: List<RemoteMediaState>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(QUEUE, json.encodeToString(serializer, states))
            .apply()
    }
}
