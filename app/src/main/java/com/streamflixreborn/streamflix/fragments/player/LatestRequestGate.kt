package com.streamflixreborn.streamflix.fragments.player

internal class LatestRequestGate {
    private var generation = 0L

    @Synchronized
    fun begin(): Long = ++generation

    @Synchronized
    fun isCurrent(requestGeneration: Long): Boolean = requestGeneration == generation
}
