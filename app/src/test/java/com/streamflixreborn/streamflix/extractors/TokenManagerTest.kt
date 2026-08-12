package com.streamflixreborn.streamflix.extractors

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenManagerTest {
    @After
    fun tearDown() {
        TokenManager.resetForTests()
    }

    @Test
    fun `new playback session prevents stale refresh from overwriting current token`() {
        val oldSession = TokenManager.beginSession("token=old&expires=1")
        val currentSession = TokenManager.beginSession("token=current&expires=2")

        assertFalse(TokenManager.updateQuery(oldSession, "token=stale&expires=3"))
        assertEquals("token=current&expires=2", TokenManager.latestQuery)

        assertTrue(TokenManager.updateQuery(currentSession, "token=fresh&expires=4"))
        assertEquals("token=fresh&expires=4", TokenManager.latestQuery)
    }

    @Test
    fun `cancelling current session clears token but stale cancellation does not`() {
        val oldSession = TokenManager.beginSession("token=old")
        val currentSession = TokenManager.beginSession("token=current")

        TokenManager.cancelSession(oldSession)
        assertEquals("token=current", TokenManager.latestQuery)

        TokenManager.cancelSession(currentSession)
        assertNull(TokenManager.latestQuery)
    }

    @Test
    fun `normalizes second and millisecond epoch expiries`() {
        assertEquals(1_700_000_000_000L, normalizeTokenExpiryMillis(1_700_000_000L))
        assertEquals(1_700_000_000_000L, normalizeTokenExpiryMillis(1_700_000_000_000L))
        assertNull(normalizeTokenExpiryMillis(null))
        assertNull(normalizeTokenExpiryMillis(0L))
    }
}
