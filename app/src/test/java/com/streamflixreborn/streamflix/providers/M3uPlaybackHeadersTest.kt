package com.streamflixreborn.streamflix.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uPlaybackHeadersTest {
    @Test fun `playback identity round trips delimiter bearing fields`() {
        val identity = M3uPlaybackIdentity(
            url = "https://fixture.example/live.m3u8?token=a|b",
            name = "News | International",
            logo = "https://fixture.example/logo|wide.png",
            userAgent = "Fixture|UA",
            referrer = "https://fixture.example/embed|channel",
        )

        assertEquals(
            identity,
            decodeM3uPlaybackIdentity(encodeM3uPlaybackIdentity(identity)),
        )
    }

    @Test fun `playback identity decoder preserves legacy payloads`() {
        assertEquals(
            M3uPlaybackIdentity(
                url = "https://fixture.example/live.m3u8",
                name = "Fixture channel",
                logo = "https://fixture.example/logo.png",
                userAgent = "Fixture-UA",
                referrer = "https://fixture.example/embed",
            ),
            decodeM3uPlaybackIdentity(
                "https://fixture.example/live.m3u8|Fixture channel|https://fixture.example/logo.png|Fixture-UA|https://fixture.example/embed",
            ),
        )
    }

    @Test fun `playback identity decoder rejects ambiguous legacy payloads`() {
        assertNull(
            decodeM3uPlaybackIdentity(
                payload = "https://fixture.example/live.m3u8|News | International|logo|Fixture-UA|referer",
                legacyFieldCount = 5,
            ),
        )
        assertNull(
            decodeM3uPlaybackIdentity(
                payload = "https://fixture.example/live.m3u8|News | International|logo|Fixture-UA",
                legacyFieldCount = 4,
            ),
        )
    }

    @Test fun `playback identity decoder rejects overflowing lengths`() {
        assertNull(decodeM3uPlaybackIdentity("m3u1;2147483647:x"))
    }

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
