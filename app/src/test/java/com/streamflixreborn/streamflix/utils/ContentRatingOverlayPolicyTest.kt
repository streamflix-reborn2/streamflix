package com.streamflixreborn.streamflix.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ContentRatingOverlayPolicyTest {
    @Test fun `overlay duration is five seconds`() {
        assertEquals(5_000L, ContentRatingOverlayPolicy.DISPLAY_DURATION_MS)
    }

    @Test fun `same media events such as pause and resume do not retrigger`() {
        val policy = ContentRatingOverlayPolicy()
        assertTrue(policy.onMediaChanged("movie:A"))
        assertFalse(policy.onMediaChanged("movie:A"))
    }

    @Test fun `new episode restarts and old asynchronous result is rejected`() {
        val policy = ContentRatingOverlayPolicy()
        assertTrue(policy.onMediaChanged("episode:A"))
        assertTrue(policy.onMediaChanged("episode:B"))
        assertFalse(policy.accepts("episode:A"))
        assertTrue(policy.accepts("episode:B"))
    }
}
