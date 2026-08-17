package com.streamflixreborn.streamflix.fragments.player

import java.net.URI

/** Pure, serialized policy for choosing and recovering playback sources. */
class PlaybackSourceRecoveryCoordinator<T>(
    private val identity: (T) -> Identity,
) {
    data class Identity(val id: String, val name: String, val src: String)
    data class Request<T>(val token: Long, val candidate: T, val identity: Identity)

    sealed interface Failure {
        data object NoSources : Failure
        data class DiscoveryFailure(val cause: Throwable) : Failure
        data class ResolutionFailure(val identity: Identity, val cause: Throwable) : Failure
        data class InvalidSource(val identity: Identity, val source: String) : Failure
        data class PlayerFailure(val identity: Identity, val cause: Throwable) : Failure
    }

    sealed interface Outcome<out T> {
        data object NoSources : Outcome<Nothing>
        data class DiscoveryFailure(val cause: Throwable) : Outcome<Nothing>
        data class Resolve<T>(val request: Request<T>, val trigger: Failure? = null) : Outcome<T>
        data class Retry<T>(val request: Request<T>, val failure: Failure.PlayerFailure) : Outcome<T>
        data class Restore<T>(val request: Request<T>, val trigger: Failure) : Outcome<T>
        data class Success<T>(val candidate: T, val identity: Identity, val source: String) : Outcome<T>
        data class Exhausted(val lastFailure: Failure) : Outcome<Nothing>
        data object Cancelled : Outcome<Nothing>
    }

    private enum class Phase { IDLE, RESOLVING, PLAYING, EXHAUSTED }

    private var candidates = emptyList<T>()
    private var activeIndex = -1
    private var activeToken = 0L
    private var phase = Phase.IDLE
    private val failed = linkedSetOf<Int>()
    private val retried = linkedSetOf<Int>()
    private var lastWorkingIndex: Int? = null
    private var restorationAttempted = false
    private var restoring = false

    @Synchronized
    fun reset() {
        cancelActive()
        candidates = emptyList()
        failed.clear()
        retried.clear()
        activeIndex = -1
        lastWorkingIndex = null
        restorationAttempted = false
        restoring = false
        phase = Phase.IDLE
    }

    @Synchronized
    fun discover(candidates: List<T>, preferredIndex: Int = 0): Outcome<T> {
        this.candidates = candidates.toList()
        failed.clear()
        retried.clear()
        lastWorkingIndex = null
        restorationAttempted = false
        restoring = false
        if (candidates.isEmpty()) {
            phase = Phase.EXHAUSTED
            return Outcome.NoSources
        }
        activeIndex = preferredIndex.coerceIn(candidates.indices)
        return resolve(activeIndex)
    }

    @Synchronized
    fun discoveryFailed(cause: Throwable): Outcome<T> {
        cancelActive()
        phase = Phase.EXHAUSTED
        return Outcome.DiscoveryFailure(cause)
    }

    @Synchronized
    fun select(index: Int): Outcome<T> {
        if (index !in candidates.indices) return Outcome.Cancelled
        activeToken++ // invalidates an in-flight automatic completion
        activeIndex = index
        failed.remove(index)
        retried.remove(index)
        restoring = false
        restorationAttempted = false
        return resolve(index)
    }

    @Synchronized
    fun resolved(token: Long, source: String): Outcome<T> {
        if (!accepts(token)) return Outcome.Cancelled
        val currentIdentity = identity(candidates[activeIndex])
        if (!isValidSource(source)) {
            val failure = Failure.InvalidSource(currentIdentity, source)
            failed += activeIndex
            return advance(failure)
        }
        phase = Phase.PLAYING
        return Outcome.Success(candidates[activeIndex], currentIdentity, source)
    }

    @Synchronized
    fun resolutionFailed(token: Long, cause: Throwable): Outcome<T> {
        if (!accepts(token)) return Outcome.Cancelled
        val currentIdentity = identity(candidates[activeIndex])
        val failure = Failure.ResolutionFailure(currentIdentity, cause)
        failed += activeIndex
        return advance(failure)
    }

    @Synchronized
    fun playerFailed(cause: Throwable): Outcome<T> {
        if (phase != Phase.PLAYING || activeIndex !in candidates.indices) return Outcome.Cancelled
        val current = candidates[activeIndex]
        val currentIdentity = identity(current)
        val failure = Failure.PlayerFailure(currentIdentity, cause)
        if (retried.add(activeIndex)) {
            phase = Phase.RESOLVING
            return Outcome.Retry(newRequest(current), failure)
        }
        failed += activeIndex
        return advance(failure)
    }

    @Synchronized
    fun ready(): Outcome<T> {
        if (phase != Phase.PLAYING || activeIndex !in candidates.indices) return Outcome.Cancelled
        val current = candidates[activeIndex]
        val currentIdentity = identity(current)
        lastWorkingIndex = activeIndex
        restorationAttempted = false
        restoring = false
        return Outcome.Success(current, currentIdentity, "")
    }

    @Synchronized
    fun confirmedWorking(): Outcome<T> {
        if (phase != Phase.PLAYING || activeIndex !in candidates.indices) return Outcome.Cancelled
        val current = candidates[activeIndex]
        val currentIdentity = identity(current)
        lastWorkingIndex = activeIndex
        failed.clear()
        retried.remove(activeIndex)
        restorationAttempted = false
        restoring = false
        return Outcome.Success(current, currentIdentity, "")
    }

    private fun advance(trigger: Failure): Outcome<T> {
        if (restoring) return exhaust(trigger)
        for (offset in 1..candidates.size) {
            val index = (activeIndex + offset).mod(candidates.size)
            if (index !in failed && index != lastWorkingIndex) {
                activeIndex = index
                return Outcome.Resolve(newRequest(candidates[index]), trigger)
            }
        }
        val workingIndex = lastWorkingIndex ?: -1
        if (!restorationAttempted && workingIndex in candidates.indices && workingIndex !in failed) {
            restorationAttempted = true
            restoring = true
            activeIndex = workingIndex
            return Outcome.Restore(newRequest(candidates[workingIndex]), trigger)
        }
        return exhaust(trigger)
    }

    private fun resolve(index: Int): Outcome.Resolve<T> {
        phase = Phase.RESOLVING
        return Outcome.Resolve(newRequest(candidates[index]))
    }

    private fun newRequest(candidate: T): Request<T> {
        phase = Phase.RESOLVING
        activeToken++
        return Request(activeToken, candidate, identity(candidate))
    }

    private fun accepts(token: Long) = phase == Phase.RESOLVING && token == activeToken

    private fun exhaust(failure: Failure): Outcome.Exhausted {
        cancelActive()
        phase = Phase.EXHAUSTED
        return Outcome.Exhausted(failure)
    }

    private fun cancelActive() {
        activeToken++
        activeIndex = -1
    }

    companion object {
        fun isValidSource(source: String): Boolean = runCatching {
            if (source.isBlank()) return false
            val uri = URI(source)
            when (uri.scheme?.lowercase()) {
                null, "" -> false
                "http", "https" -> !uri.host.isNullOrBlank()
                "data" -> uri.rawSchemeSpecificPart?.contains(',') == true
                else -> !uri.rawSchemeSpecificPart.isNullOrBlank()
            }
        }.getOrDefault(false)
    }
}
