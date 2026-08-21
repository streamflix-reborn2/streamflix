package com.streamflixreborn.streamflix.fragments.player

import com.streamflixreborn.streamflix.models.Video

internal class PlaybackSourceRecovery {
    sealed interface Action {
        data object Ignore : Action
        data class Retry(val server: Video.Server) : Action
        data class TryNext(
            val failedServer: Video.Server,
            val nextServer: Video.Server,
        ) : Action
        data class Restore(val server: Video.Server) : Action
        data object Exhausted : Action
    }

    private var servers = emptyList<Video.Server>()
    private val failedServers = mutableSetOf<Video.Server>()
    private val playbackRetries = mutableSetOf<Video.Server>()
    private var lastWorkingServer: Video.Server? = null
    private var recoveryInProgress = false
    private var restoringLastWorkingServer = false

    fun beginServerDiscovery() {
        reset()
    }

    fun setServers(servers: List<Video.Server>) {
        this.servers = servers
    }

    fun select(server: Video.Server) {
        failedServers.clear()
        playbackRetries.remove(server)
        recoveryInProgress = false
        restoringLastWorkingServer = false
    }

    fun videoResolved() {
        recoveryInProgress = false
    }

    fun playbackStarted(server: Video.Server) {
        lastWorkingServer = server
        failedServers.clear()
        playbackRetries.remove(server)
        recoveryInProgress = false
        restoringLastWorkingServer = false
    }

    fun playbackFailed(server: Video.Server): Action {
        if (restoringLastWorkingServer) {
            restoringLastWorkingServer = false
            recoveryInProgress = false
            return Action.Exhausted
        }
        if (recoveryInProgress) return Action.Ignore

        recoveryInProgress = true
        if (playbackRetries.add(server)) return Action.Retry(server)

        return fallbackAfter(server)
    }

    fun videoLoadFailed(server: Video.Server): Action {
        if (restoringLastWorkingServer) {
            restoringLastWorkingServer = false
            recoveryInProgress = false
            return Action.Exhausted
        }

        recoveryInProgress = true
        return fallbackAfter(server)
    }

    fun markFailed(servers: Iterable<Video.Server>) {
        failedServers.addAll(servers)
    }

    fun reset() {
        servers = emptyList()
        failedServers.clear()
        playbackRetries.clear()
        lastWorkingServer = null
        recoveryInProgress = false
        restoringLastWorkingServer = false
    }

    private fun fallbackAfter(failedServer: Video.Server): Action {
        failedServers.add(failedServer)

        nextUnfailedServerAfter(failedServer)?.let { nextServer ->
            playbackRetries.remove(nextServer)
            return Action.TryNext(failedServer, nextServer)
        }

        val workingServer = lastWorkingServer
        if (
            workingServer != null &&
            workingServer != failedServer &&
            workingServer !in failedServers
        ) {
            restoringLastWorkingServer = true
            playbackRetries.remove(workingServer)
            return Action.Restore(workingServer)
        }

        recoveryInProgress = false
        return Action.Exhausted
    }

    private fun nextUnfailedServerAfter(server: Video.Server): Video.Server? {
        if (servers.isEmpty()) return null

        val currentIndex = servers.indexOfFirst { it === server }
            .takeIf { it >= 0 }
            ?: servers.indexOf(server)

        for (offset in 1..servers.size) {
            val index = if (currentIndex >= 0) {
                (currentIndex + offset) % servers.size
            } else {
                offset - 1
            }
            val candidate = servers[index]
            val isCurrent = candidate === server || candidate == server
            val isLastWorking = lastWorkingServer?.let {
                candidate === it || candidate == it
            } ?: false

            if (!isCurrent && !isLastWorking && candidate !in failedServers) {
                return candidate
            }
        }

        return null
    }
}
