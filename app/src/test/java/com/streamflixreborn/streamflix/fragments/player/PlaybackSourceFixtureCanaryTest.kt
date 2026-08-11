package com.streamflixreborn.streamflix.fragments.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSourceFixtureCanaryTest {
    private data class Server(val id: String, val name: String, val src: String)
    private data class Resolved(val source: String)

    @Test fun `provider resolver coordinator canary covers direct extractor malformed throw and fallback`() {
        val direct = Server("direct", "Direct", "https://cdn.example.test/direct.mp4")
        val directCoordinator = PlaybackSourceRecoveryCoordinator<Server> {
            PlaybackSourceRecoveryCoordinator.Identity(it.id, it.name, it.src)
        }
        val directRequest = directCoordinator.discover(listOf(direct)) as PlaybackSourceRecoveryCoordinator.Outcome.Resolve
        assertTrue(directCoordinator.resolved(directRequest.request.token, direct.src) is PlaybackSourceRecoveryCoordinator.Outcome.Success)

        val fixtures = listOf(
            Server("malformed", "Direct malformed", "direct:bad"),
            Server("throws", "Extractor throws", "extractor:throws"),
            Server("fallback", "Extractor fallback", "extractor:ok"),
        )
        val coordinator = PlaybackSourceRecoveryCoordinator<Server> {
            PlaybackSourceRecoveryCoordinator.Identity(it.id, it.name, it.src)
        }

        fun resolve(server: Server): Resolved = when (server.src) {
            "direct:bad" -> Resolved("not-a-url")
            "extractor:throws" -> throw IllegalStateException("fixture extractor failure")
            "extractor:ok" -> Resolved("https://cdn.example.test/fallback.m3u8")
            else -> Resolved(server.src)
        }

        var outcome: PlaybackSourceRecoveryCoordinator.Outcome<Server> = coordinator.discover(fixtures)
        val visited = mutableListOf<String>()
        while (outcome is PlaybackSourceRecoveryCoordinator.Outcome.Resolve) {
            val request = outcome.request
            visited += request.candidate.id
            outcome = try {
                coordinator.resolved(request.token, resolve(request.candidate).source)
            } catch (error: Exception) {
                coordinator.resolutionFailed(request.token, error)
            }
        }

        assertEquals(listOf("malformed", "throws", "fallback"), visited)
        assertTrue(outcome is PlaybackSourceRecoveryCoordinator.Outcome.Success)
        assertEquals("fallback", (outcome as PlaybackSourceRecoveryCoordinator.Outcome.Success).candidate.id)
    }
}
