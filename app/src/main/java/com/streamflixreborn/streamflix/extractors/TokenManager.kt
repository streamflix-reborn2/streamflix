package com.streamflixreborn.streamflix.extractors

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Coordinates the one rotating playback-token stream that can be consumed by the legacy
 * player interceptors. Starting a new session cancels the previous refresh loop, so a stale
 * movie or episode can no longer overwrite the token used by the currently selected video.
 */
object TokenManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var generation: Long = 0L
    private var refreshJob: Job? = null

    @Volatile
    var latestQuery: String? = null
        private set

    @Synchronized
    fun beginSession(initialQuery: String?): Long {
        generation += 1L
        refreshJob?.cancel()
        refreshJob = null
        latestQuery = initialQuery?.takeIf { it.isNotBlank() }
        return generation
    }

    @Synchronized
    fun updateQuery(session: Long, query: String?): Boolean {
        if (session != generation) return false
        val normalized = query?.takeIf { it.isNotBlank() } ?: return false
        latestQuery = normalized
        return true
    }

    @Synchronized
    fun launchRefresh(
        session: Long,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        if (session != generation) return
        refreshJob?.cancel()
        refreshJob = scope.launch(block = block)
    }

    @Synchronized
    fun isCurrent(session: Long): Boolean = session == generation

    @Synchronized
    fun cancelSession(session: Long) {
        if (session != generation) return
        refreshJob?.cancel()
        refreshJob = null
        latestQuery = null
    }

    internal fun resetForTests() {
        synchronized(this) {
            generation += 1L
            refreshJob?.cancel()
            refreshJob = null
            latestQuery = null
        }
    }
}

/**
 * Some token endpoints expose Unix seconds while others expose epoch milliseconds. Normalize
 * both forms before scheduling refreshes. Values below 10^10 are safely interpreted as seconds
 * for contemporary epoch timestamps.
 */
internal fun normalizeTokenExpiryMillis(rawExpiry: Long?): Long? {
    val value = rawExpiry ?: return null
    if (value <= 0L) return null
    return if (value < 10_000_000_000L) value * 1000L else value
}
