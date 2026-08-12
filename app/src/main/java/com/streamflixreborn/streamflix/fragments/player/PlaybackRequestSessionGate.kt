package com.streamflixreborn.streamflix.fragments.player

/**
 * Atomically orders server discovery and TV video resolution work.
 * Starting a discovery invalidates both older discovery completions and every
 * resolution belonging to the previous content/session.
 */
internal class PlaybackRequestSessionGate {
    class Discovery internal constructor(internal val generation: Long)
    class Resolution internal constructor(internal val generation: Long)

    private var discoveryGeneration = 0L
    private var resolutionGeneration = 0L

    @Synchronized
    fun beginDiscovery(): Discovery {
        discoveryGeneration++
        resolutionGeneration++
        return Discovery(discoveryGeneration)
    }

    @Synchronized
    fun beginResolution(): Resolution = Resolution(++resolutionGeneration)

    @Synchronized
    fun runIfCurrent(request: Discovery, action: () -> Unit): Boolean {
        if (request.generation != discoveryGeneration) return false
        action()
        return true
    }

    @Synchronized
    fun runIfCurrent(request: Resolution, action: () -> Unit): Boolean {
        if (request.generation != resolutionGeneration) return false
        action()
        return true
    }
}
