package com.streamflixreborn.streamflix.fragments.player

/** A half-open playback range in which an episode intro can be skipped. */
data class IntroRange(
    val startMs: Long,
    val endMs: Long,
) {
    init {
        require(startMs >= 0) { "Intro start must not be negative" }
        require(endMs > startMs) { "Intro end must be after intro start" }
    }

    fun contains(positionMs: Long): Boolean = positionMs >= startMs && positionMs < endMs

    fun isAvailable(positionMs: Long, durationMs: Long): Boolean =
        durationMs >= endMs && contains(positionMs)

    /** The absolute seek target; this deliberately does not depend on playback position. */
    val seekDestinationMs: Long get() = endMs

    companion object {
        const val DEFAULT_INTRO_START_MS = 3_000L
        const val DEFAULT_INTRO_END_MS = 88_000L

        /** Used until episode-specific intro metadata is available. */
        val DEFAULT = IntroRange(DEFAULT_INTRO_START_MS, DEFAULT_INTRO_END_MS)
    }
}
