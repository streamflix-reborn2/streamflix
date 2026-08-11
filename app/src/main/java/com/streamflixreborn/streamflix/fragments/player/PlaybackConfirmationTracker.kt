package com.streamflixreborn.streamflix.fragments.player

internal class PlaybackConfirmationTracker(
    private val thresholdMs: Long = 10_000L,
    private val monotonicNowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var playingSinceMs: Long? = null
    private var confirmed = false

    fun sample(isPlaying: Boolean): Boolean {
        if (confirmed) return false
        if (!isPlaying) {
            playingSinceMs = null
            return false
        }

        val nowMs = monotonicNowMs()
        val startedMs = playingSinceMs
        if (startedMs == null) {
            playingSinceMs = nowMs
            return false
        }
        if (nowMs - startedMs < thresholdMs) return false

        confirmed = true
        return true
    }

    fun positionDiscontinuity() {
        playingSinceMs = null
    }

    fun reset() {
        playingSinceMs = null
        confirmed = false
    }
}
