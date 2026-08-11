package com.streamflixreborn.streamflix.fragments.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPlaybackViewReplayTest {
    @Test fun `finished video state replays the latest discovered candidates to an unseeded new view`() {
        val replay = TvPlaybackViewReplay<String>()
        replay.record(listOf("a", "b"))
        val view = replay.beginView()

        assertEquals(
            listOf("a", "b"),
            replay.candidatesForNewView(view, discoveryStateIsReplayable = false),
        )
    }

    @Test fun `same view server observation suppresses posted replay race`() {
        val replay = TvPlaybackViewReplay<String>()
        replay.record(listOf("a"))
        val view = replay.beginView()

        assertTrue(replay.markObserved(view))
        assertNull(replay.candidatesForNewView(view, discoveryStateIsReplayable = false))
    }

    @Test fun `active discovery state is allowed to replay itself`() {
        val replay = TvPlaybackViewReplay<String>()
        replay.record(listOf("a"))
        val view = replay.beginView()

        assertNull(replay.candidatesForNewView(view, discoveryStateIsReplayable = true))
    }

    @Test fun `stale view cannot suppress or trigger replay for replacement view`() {
        val replay = TvPlaybackViewReplay<String>()
        replay.record(listOf("a"))
        val oldView = replay.beginView()
        val replacementView = replay.beginView()

        assertFalse(replay.markObserved(oldView))
        assertNull(replay.candidatesForNewView(oldView, discoveryStateIsReplayable = false))
        assertEquals(
            listOf("a"),
            replay.candidatesForNewView(replacementView, discoveryStateIsReplayable = false),
        )
    }
}
