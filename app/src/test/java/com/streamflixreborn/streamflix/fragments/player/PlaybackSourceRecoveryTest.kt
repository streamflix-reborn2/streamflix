package com.streamflixreborn.streamflix.fragments.player

import com.streamflixreborn.streamflix.models.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackSourceRecoveryTest {
    private val serverA = server("A")
    private val serverB = server("B")
    private val serverC = server("C")
    private val serverD = server("D")

    @Test
    fun `successful playback restores the one-retry allowance`() {
        val recovery = recoveryWith(serverA)

        assertEquals(
            PlaybackSourceRecovery.Action.Retry(serverA),
            recovery.playbackFailed(serverA),
        )

        recovery.videoResolved()
        recovery.playbackStarted(serverA)

        assertEquals(
            PlaybackSourceRecovery.Action.Retry(serverA),
            recovery.playbackFailed(serverA),
        )
    }

    @Test
    fun `fallback wraps around after a manually selected server`() {
        val recovery = recoveryWith(serverA, serverB, serverC, serverD)
        recovery.select(serverC)

        assertEquals(
            PlaybackSourceRecovery.Action.TryNext(serverC, serverD),
            failAfterRetry(recovery, serverC),
        )
        assertEquals(
            PlaybackSourceRecovery.Action.TryNext(serverD, serverA),
            failAfterRetry(recovery, serverD),
        )
        assertEquals(
            PlaybackSourceRecovery.Action.TryNext(serverA, serverB),
            failAfterRetry(recovery, serverA),
        )
    }

    @Test
    fun `failed servers are excluded from the current recovery cycle`() {
        val recovery = recoveryWith(serverA, serverB, serverC)

        assertEquals(
            PlaybackSourceRecovery.Action.TryNext(serverA, serverB),
            failAfterRetry(recovery, serverA),
        )
        assertEquals(
            PlaybackSourceRecovery.Action.TryNext(serverB, serverC),
            failAfterRetry(recovery, serverB),
        )
        assertSame(
            PlaybackSourceRecovery.Action.Exhausted,
            failAfterRetry(recovery, serverC),
        )
    }

    @Test
    fun `last working server is restored only after other servers fail`() {
        val recovery = recoveryWith(serverA, serverB, serverC)
        recovery.playbackStarted(serverA)
        recovery.select(serverB)

        assertEquals(
            PlaybackSourceRecovery.Action.TryNext(serverB, serverC),
            failAfterRetry(recovery, serverB),
        )
        assertEquals(
            PlaybackSourceRecovery.Action.Restore(serverA),
            failAfterRetry(recovery, serverC),
        )
    }

    @Test
    fun `failure of restored source exhausts recovery`() {
        val recovery = recoveryWith(serverA, serverB)
        recovery.playbackStarted(serverA)
        recovery.select(serverB)

        assertEquals(
            PlaybackSourceRecovery.Action.Restore(serverA),
            failAfterRetry(recovery, serverB),
        )
        assertSame(
            PlaybackSourceRecovery.Action.Exhausted,
            recovery.videoLoadFailed(serverA),
        )
    }

    @Test
    fun `duplicate playback errors are ignored while recovery is resolving`() {
        val recovery = recoveryWith(serverA)

        assertEquals(
            PlaybackSourceRecovery.Action.Retry(serverA),
            recovery.playbackFailed(serverA),
        )
        assertSame(
            PlaybackSourceRecovery.Action.Ignore,
            recovery.playbackFailed(serverA),
        )
    }

    private fun recoveryWith(vararg servers: Video.Server) = PlaybackSourceRecovery().apply {
        beginServerDiscovery()
        setServers(servers.toList())
    }

    private fun failAfterRetry(
        recovery: PlaybackSourceRecovery,
        server: Video.Server,
    ): PlaybackSourceRecovery.Action {
        recovery.videoResolved()
        assertEquals(
            PlaybackSourceRecovery.Action.Retry(server),
            recovery.playbackFailed(server),
        )
        recovery.videoResolved()
        return recovery.playbackFailed(server)
    }

    private fun server(name: String) = Video.Server(
        id = "https://example.com/$name",
        name = name,
    )
}
