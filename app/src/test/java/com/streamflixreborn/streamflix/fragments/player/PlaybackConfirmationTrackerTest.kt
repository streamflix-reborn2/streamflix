package com.streamflixreborn.streamflix.fragments.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackConfirmationTrackerTest {
    @Test fun `playback is confirmed only after continuous monotonic playing time`() {
        var nowMs = 1_000L
        val tracker = PlaybackConfirmationTracker(
            thresholdMs = 10_000L,
            monotonicNowMs = { nowMs },
        )

        assertFalse(tracker.sample(isPlaying = true))
        nowMs = 10_999L
        assertFalse(tracker.sample(isPlaying = true))
        nowMs = 11_000L
        assertTrue(tracker.sample(isPlaying = true))
        assertFalse(tracker.sample(isPlaying = true))
    }

    @Test fun `pause and position discontinuity require a fresh continuous interval`() {
        var nowMs = 0L
        val tracker = PlaybackConfirmationTracker(
            thresholdMs = 10_000L,
            monotonicNowMs = { nowMs },
        )

        assertFalse(tracker.sample(isPlaying = true))
        nowMs = 9_000L
        assertFalse(tracker.sample(isPlaying = false))
        nowMs = 60_000L // A large seek/timeline jump must be irrelevant to confirmation.
        assertFalse(tracker.sample(isPlaying = true))
        nowMs = 69_999L
        assertFalse(tracker.sample(isPlaying = true))

        tracker.positionDiscontinuity()
        nowMs = 70_000L
        assertFalse(tracker.sample(isPlaying = true))
        nowMs = 79_999L
        assertFalse(tracker.sample(isPlaying = true))
        nowMs = 80_000L
        assertTrue(tracker.sample(isPlaying = true))
    }
}
