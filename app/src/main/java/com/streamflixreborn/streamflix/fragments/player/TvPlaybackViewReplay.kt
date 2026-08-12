package com.streamflixreborn.streamflix.fragments.player

internal class TvPlaybackViewReplay<T> {
    private var latestCandidates = emptyList<T>()
    private var currentViewToken = 0L
    private var observedViewToken: Long? = null
    private var replayRequestedViewToken: Long? = null

    @Synchronized
    fun record(candidates: List<T>) {
        latestCandidates = candidates.toList()
    }

    @Synchronized
    fun beginView(): Long {
        currentViewToken += 1L
        observedViewToken = null
        replayRequestedViewToken = null
        return currentViewToken
    }

    @Synchronized
    fun markObserved(viewToken: Long): Boolean {
        if (viewToken != currentViewToken) return false
        observedViewToken = viewToken
        return true
    }

    @Synchronized
    fun markPlaybackAccepted(viewToken: Long): Boolean = markObserved(viewToken)

    @Synchronized
    fun candidatesForNewView(
        viewToken: Long,
        discoveryStateIsReplayable: Boolean,
    ): List<T>? {
        if (
            viewToken != currentViewToken ||
            observedViewToken == viewToken ||
            replayRequestedViewToken == viewToken ||
            discoveryStateIsReplayable ||
            latestCandidates.isEmpty()
        ) return null

        replayRequestedViewToken = viewToken
        return latestCandidates.toList()
    }
}

internal class TvPlaybackReplayCallback<T : Any>(
    private val viewToken: Long,
    private val root: T,
) {
    fun runIfActive(activeRoot: T?, replay: (Long) -> Unit): Boolean {
        if (root !== activeRoot) return false
        replay(viewToken)
        return true
    }
}
