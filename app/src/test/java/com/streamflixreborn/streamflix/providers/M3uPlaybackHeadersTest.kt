package com.streamflixreborn.streamflix.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uPlaybackHeadersTest {
    @Test fun `preserves user agent and referrer required by playlist`() {
        assertEquals(
            mapOf(
                "User-Agent" to "Fixture-UA",
                "Referer" to "https://fixture.example/embed",
            ),
            m3uPlaybackHeaders(
                userAgent = "Fixture-UA",
                referrer = "https://fixture.example/embed",
            ),
        )
    }

    @Test fun `ignores blank playlist metadata`() {
        assertNull(m3uPlaybackHeaders(userAgent = "  ", referrer = ""))
    }

    @Test fun `preserves whichever required header exists`() {
        assertEquals(
            mapOf("Referer" to "https://fixture.example/"),
            m3uPlaybackHeaders(userAgent = null, referrer = "https://fixture.example/"),
        )
    }

    @Test fun `creates a playable video with playlist request headers`() {
        val video = m3uPlaybackVideo(
            source = "https://fixture.example/live.m3u8",
            userAgent = "Fixture-UA",
            referrer = "https://fixture.example/embed",
        )

        assertEquals("https://fixture.example/live.m3u8", video.source)
        assertEquals(
            mapOf(
                "User-Agent" to "Fixture-UA",
                "Referer" to "https://fixture.example/embed",
            ),
            video.headers,
        )
    }
}
