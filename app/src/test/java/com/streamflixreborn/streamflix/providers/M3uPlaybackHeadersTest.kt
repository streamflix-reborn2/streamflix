package com.streamflixreborn.streamflix.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class M3uPlaybackHeadersTest {
    @Test fun `playback identity round trips delimiter bearing fields`() {
        val identity = M3uPlaybackIdentity(
            url = "https://fixture.example/live.m3u8?token=a%7Cb",
            name = "News | International",
            logo = "https://fixture.example/logo%7Cwide.png",
            userAgent = "Fixture|UA",
            referrer = "https://fixture.example/embed%7Cchannel",
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

    @Test fun `oversized encoded identity is rejected before decoder allocation`() {
        var decoderCalled = false

        assertNull(
            decodeM3uPlaybackIdentityFromBase64(
                encoded = "A".repeat(MAX_M3U_PLAYBACK_ID_LENGTH + 1),
                decodeBase64 = {
                    decoderCalled = true
                    ByteArray(0)
                },
            ),
        )
        assertFalse(decoderCalled)
    }

    @Test fun `oversized decoded identity is rejected after bounded decode`() {
        var decoderCalled = false
        assertNull(
            decodeM3uPlaybackIdentityFromBase64(
                encoded = "AAAA",
                decodeBase64 = {
                    decoderCalled = true
                    ByteArray(MAX_M3U_PLAYBACK_PAYLOAD_LENGTH + 1)
                },
            ),
        )
        assertEquals(true, decoderCalled)
    }

    @Test fun `base64 identity parser rejects noncanonical representations`() {
        val identityBytes = encodeM3uPlaybackIdentity(validIdentity()).toByteArray()
        val canonical = Base64.getEncoder().encodeToString(identityBytes)
        val noncanonical = "$canonical\n"

        assertNull(
            decodeM3uPlaybackIdentityFromBase64(
                encoded = noncanonical,
                decodeBase64 = { Base64.getMimeDecoder().decode(it) },
                encodeBase64 = { Base64.getEncoder().encodeToString(it) },
            ),
        )
    }

    @Test fun `base64 identity parser rejects malformed identity instead of returning raw id`() {
        try {
            requireM3uPlaybackIdentityFromBase64(
                encoded = Base64.getEncoder().encodeToString("malformed".toByteArray()),
                decodeBase64 = { Base64.getDecoder().decode(it) },
            )
            fail("Expected malformed identity to be rejected")
        } catch (_: M3uPlaybackIdentityException) {
            // Controlled failure.
        }
    }

    @Test fun `base64 identity parser rejects malformed utf8`() {
        val malformed = byteArrayOf(
            'm'.code.toByte(), '3'.code.toByte(), 'u'.code.toByte(), '1'.code.toByte(), ';'.code.toByte(),
            '1'.code.toByte(), ':'.code.toByte(), 0xC3.toByte(),
        )
        assertNull(
            decodeM3uPlaybackIdentityFromBase64(
                encoded = Base64.getEncoder().encodeToString(malformed),
                decodeBase64 = { Base64.getDecoder().decode(it) },
            ),
        )
    }

    @Test fun `identity validation rejects unsafe urls and header controls`() {
        val invalid = listOf(
            validIdentity().copy(url = "file:///etc/passwd"),
            validIdentity().copy(url = "http://127.0.0.1/fixture.m3u8"),
            validIdentity().copy(url = "http://localhost./fixture.m3u8"),
            validIdentity().copy(url = "http://fixture.local./fixture.m3u8"),
            validIdentity().copy(url = "http://2130706433/fixture.m3u8"),
            validIdentity().copy(url = "http://0x7f000001/fixture.m3u8"),
            validIdentity().copy(url = "http://0177.0.0.1/fixture.m3u8"),
            validIdentity().copy(url = "http://192.168.1.20/fixture.m3u8"),
            validIdentity().copy(url = "http://169.254.1.20/fixture.m3u8"),
            validIdentity().copy(referrer = "file:///tmp/embed"),
            validIdentity().copy(userAgent = "Fixture-UA\r\nInjected: true"),
            validIdentity().copy(userAgent = "Fixture\tUA"),
            validIdentity().copy(userAgent = "Fixture\u0085UA"),
            validIdentity().copy(name = "broken\uD800name"),
            validIdentity().copy(name = "x".repeat(513)),
            validIdentity().copy(userAgent = "x".repeat(1025)),
            validIdentity().copy(referrer = "https://fixture.example/\u0000embed"),
        )

        invalid.forEach { identity ->
            assertNull("Expected invalid identity: $identity", validateM3uPlaybackIdentity(identity))
        }
    }

    @Test fun `encoder rejects identity larger than decoder limits`() {
        try {
            encodeM3uPlaybackIdentity(
                validIdentity().copy(name = "x".repeat(MAX_M3U_PLAYBACK_PAYLOAD_LENGTH)),
            )
            fail("Expected oversized identity to be rejected")
        } catch (_: M3uPlaybackIdentityException) {
            // Controlled failure.
        }
    }

    @Test fun `valid public playback identity passes validation`() {
        assertEquals(validIdentity(), validateM3uPlaybackIdentity(validIdentity()))
    }

    private fun validIdentity() = M3uPlaybackIdentity(
        url = "https://fixture.example/live.m3u8",
        name = "Fixture channel",
        logo = "https://fixture.example/logo.png",
        userAgent = "Fixture-UA",
        referrer = "https://fixture.example/embed",
    )

    @Test fun `invalid remote playlist row is filtered without dropping valid neighbors`() {
        val identities = listOf(
            validIdentity().copy(name = "First"),
            validIdentity().copy(name = "Broken", url = "file:///etc/passwd"),
            validIdentity().copy(name = "Last"),
        )

        assertEquals(
            listOf("First", "Last"),
            identities.filterValidM3uPlaybackIdentities { it }.map { it.name },
        )
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
