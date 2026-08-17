package com.streamflixreborn.streamflix.fragments.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlaybackSourceRecoveryCoordinatorTest {
    private data class Source(val id: String, val name: String, val src: String, val payload: String = "")

    private fun source(id: String, name: String = id, src: String = "https://example.test/$id") =
        Source(id, name, src)

    private fun coordinator() = PlaybackSourceRecoveryCoordinator<Source> {
        PlaybackSourceRecoveryCoordinator.Identity(it.id, it.name, it.src)
    }

    @Test fun `no sources is typed`() {
        assertEquals(PlaybackSourceRecoveryCoordinator.Outcome.NoSources, coordinator().discover(emptyList()))
    }

    @Test fun `discovery failure is typed`() {
        val error = IllegalStateException("fixture discovery")
        assertEquals(
            PlaybackSourceRecoveryCoordinator.Outcome.DiscoveryFailure(error),
            coordinator().discoveryFailed(error),
        )
    }

    @Test fun `resolution failure advances`() {
        val c = coordinator()
        val first = c.discover(listOf(source("a"), source("b"))) as PlaybackSourceRecoveryCoordinator.Outcome.Resolve
        val result = c.resolutionFailed(first.request.token, IllegalStateException("extractor"))
        assertEquals("b", (result as PlaybackSourceRecoveryCoordinator.Outcome.Resolve<Source>).request.candidate.id)
        assertTrue(result.trigger is PlaybackSourceRecoveryCoordinator.Failure.ResolutionFailure)
    }

    @Test fun `blank and malformed resolved sources advance as invalid`() {
        listOf("", "not a uri").forEach { invalid ->
            val c = coordinator()
            val first = c.discover(listOf(source("a"), source("b"))) as PlaybackSourceRecoveryCoordinator.Outcome.Resolve
            val result = c.resolved(first.request.token, invalid)
            assertEquals("b", (result as PlaybackSourceRecoveryCoordinator.Outcome.Resolve<Source>).request.candidate.id)
            assertTrue(result.trigger is PlaybackSourceRecoveryCoordinator.Failure.InvalidSource)
        }
    }

    @Test fun `parseable provider source schemes reach the player layer`() {
        listOf(
            "content://media/external/video/1",
            "file:///storage/emulated/0/video.mp4",
            "ftp://example.test/video.mp4",
        ).forEach { source ->
            assertTrue(source, PlaybackSourceRecoveryCoordinator.isValidSource(source))
        }
    }

    @Test fun `player failure retries once then advances`() {
        val c = coordinator()
        val initial = c.discover(listOf(source("a"), source("b"))) as PlaybackSourceRecoveryCoordinator.Outcome.Resolve
        c.resolved(initial.request.token, initial.request.candidate.src)
        val retry = c.playerFailed(IllegalStateException("decoder")) as PlaybackSourceRecoveryCoordinator.Outcome.Retry<Source>
        c.resolved(retry.request.token, retry.request.candidate.src)
        val next = c.playerFailed(IllegalStateException("decoder again")) as PlaybackSourceRecoveryCoordinator.Outcome.Resolve<Source>
        assertEquals("b", next.request.candidate.id)
        assertTrue(next.trigger is PlaybackSourceRecoveryCoordinator.Failure.PlayerFailure)
    }

    @Test fun `ready alone does not reset retry budget`() {
        val c = coordinator()
        val initial = c.discover(listOf(source("a"), source("b"))) as PlaybackSourceRecoveryCoordinator.Outcome.Resolve
        c.resolved(initial.request.token, initial.request.candidate.src)
        val retry = c.playerFailed(Exception("first")) as PlaybackSourceRecoveryCoordinator.Outcome.Retry<Source>
        c.resolved(retry.request.token, retry.request.candidate.src)
        c.ready()

        val next = c.playerFailed(Exception("fails immediately after ready")) as PlaybackSourceRecoveryCoordinator.Outcome.Resolve<Source>
        assertEquals("b", next.request.candidate.id)
    }

    @Test fun `confirmed working playback restores retry budget`() {
        val c = coordinator()
        val initial = c.discover(listOf(source("a"))) as PlaybackSourceRecoveryCoordinator.Outcome.Resolve
        c.resolved(initial.request.token, initial.request.candidate.src)
        val retry = c.playerFailed(Exception("first")) as PlaybackSourceRecoveryCoordinator.Outcome.Retry<Source>
        c.resolved(retry.request.token, retry.request.candidate.src)
        c.ready()

        c.confirmedWorking()

        assertTrue(c.playerFailed(Exception("later independent failure")) is PlaybackSourceRecoveryCoordinator.Outcome.Retry)
    }

    @Test fun `advance wraps around and skips failed candidates`() {
        val c = coordinator()
        val start = c.discover(listOf(source("a"), source("b"), source("c")), preferredIndex = 2) as PlaybackSourceRecoveryCoordinator.Outcome.Resolve
        assertEquals("c", start.request.candidate.id)
        val wrapped = c.resolutionFailed(start.request.token, Exception("c")) as PlaybackSourceRecoveryCoordinator.Outcome.Resolve<Source>
        assertEquals("a", wrapped.request.candidate.id)
        val next = c.resolutionFailed(wrapped.request.token, Exception("a")) as PlaybackSourceRecoveryCoordinator.Outcome.Resolve<Source>
        assertEquals("b", next.request.candidate.id)
    }

    @Test fun `manual selection uses discovery index when identities are exact duplicates`() {
        val first = source("same", "Mirror", "https://same.test/video").copy(payload = "first")
        val second = source("same", "Mirror", "https://same.test/video").copy(payload = "second")
        val c = coordinator()
        val automatic = c.discover(listOf(first, second)) as PlaybackSourceRecoveryCoordinator.Outcome.Resolve
        val manual = c.select(1) as PlaybackSourceRecoveryCoordinator.Outcome.Resolve<Source>
        assertEquals(second, manual.request.candidate)
        assertEquals(PlaybackSourceRecoveryCoordinator.Outcome.Cancelled, c.resolved(automatic.request.token, first.src))
    }

    @Test fun `concurrent duplicate player errors produce only one retry`() {
        val c = coordinator()
        val request = c.discover(listOf(source("a"))) as PlaybackSourceRecoveryCoordinator.Outcome.Resolve
        c.resolved(request.request.token, request.request.candidate.src)

        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(
            mutableListOf<PlaybackSourceRecoveryCoordinator.Outcome<Source>>()
        )
        val workers = Executors.newFixedThreadPool(2)
        repeat(2) { index ->
            workers.submit {
                start.await()
                results += c.playerFailed(Exception("concurrent-$index"))
            }
        }
        start.countDown()
        workers.shutdown()

        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(1, results.count { it is PlaybackSourceRecoveryCoordinator.Outcome.Retry })
        assertEquals(1, results.count { it == PlaybackSourceRecoveryCoordinator.Outcome.Cancelled })
    }

    @Test fun `failed alternatives restore the confirmed working source once`() {
        val c = coordinator()
        val a = source("a")
        val b = source("b")
        val initial = c.discover(listOf(a, b)) as PlaybackSourceRecoveryCoordinator.Outcome.Resolve
        c.resolved(initial.request.token, a.src)
        c.ready()
        val manual = c.select(1) as PlaybackSourceRecoveryCoordinator.Outcome.Resolve<Source>
        val restore = c.resolutionFailed(manual.request.token, Exception("b")) as PlaybackSourceRecoveryCoordinator.Outcome.Restore<Source>
        assertEquals(a, restore.request.candidate)
    }

    @Test fun `restoration failure exhausts`() {
        val c = coordinator()
        val a = source("a")
        val b = source("b")
        val initial = c.discover(listOf(a, b)) as PlaybackSourceRecoveryCoordinator.Outcome.Resolve
        c.resolved(initial.request.token, a.src)
        c.ready()
        val manual = c.select(1) as PlaybackSourceRecoveryCoordinator.Outcome.Resolve<Source>
        val restore = c.resolutionFailed(manual.request.token, Exception("b")) as PlaybackSourceRecoveryCoordinator.Outcome.Restore<Source>
        val exhausted = c.resolutionFailed(restore.request.token, Exception("restore"))
        assertTrue(exhausted is PlaybackSourceRecoveryCoordinator.Outcome.Exhausted)
        assertTrue((exhausted as PlaybackSourceRecoveryCoordinator.Outcome.Exhausted).lastFailure is PlaybackSourceRecoveryCoordinator.Failure.ResolutionFailure)
    }

    @Test fun `terminal exhaustion is stable and cannot loop`() {
        val c = coordinator()
        val only = c.discover(listOf(source("a"))) as PlaybackSourceRecoveryCoordinator.Outcome.Resolve
        val exhausted = c.resolutionFailed(only.request.token, Exception("bad"))
        assertTrue(exhausted is PlaybackSourceRecoveryCoordinator.Outcome.Exhausted)
        repeat(20) {
            assertEquals(PlaybackSourceRecoveryCoordinator.Outcome.Cancelled, c.resolutionFailed(only.request.token, Exception("stale")))
        }
    }

    @Test fun `reset invalidates an in-flight resolution from the previous episode`() {
        val c = coordinator()
        val first = source("a")
        val second = source("b")
        val resolving = c.discover(listOf(first, second)) as PlaybackSourceRecoveryCoordinator.Outcome.Resolve

        c.reset()

        assertTrue(c.resolved(resolving.request.token, first.src) is PlaybackSourceRecoveryCoordinator.Outcome.Cancelled)
    }
}
