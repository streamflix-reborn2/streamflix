package com.streamflixreborn.streamflix.extractors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VixSrcPlaylistMetadataTest {
    @Test fun `rejects embed script without video metadata`() {
        val error = runCatching {
            parseVixSrcPlaylistMetadata(
                scriptText = "<html>challenge page</html>",
                language = "en",
            )
        }.exceptionOrNull()

        assertTrue(error is VixSrcMetadataException)
    }

    @Test fun `rejects missing playlist token`() {
        val script = """
            window.video = { id: '42', filename: 'fixture' };
            window.masterPlaylist = { 'token': '', 'expires': '1234' };
        """.trimIndent()

        val error = runCatching { parseVixSrcPlaylistMetadata(script, "en") }.exceptionOrNull()
        assertTrue(error is VixSrcMetadataException)
        assertTrue(error?.message.orEmpty().contains("token"))
    }

    @Test fun `rejects missing playlist expiry`() {
        val script = """
            window.video = { id: '42' };
            window.masterPlaylist = { token: 'abc', expires: '' };
        """.trimIndent()

        val error = runCatching { parseVixSrcPlaylistMetadata(script, "en") }.exceptionOrNull()
        assertTrue(error is VixSrcMetadataException)
        assertTrue(error?.message.orEmpty().contains("expiry"))
    }

    @Test fun `rejects missing video id`() {
        val script = """
            window.video = { filename: 'fixture' };
            window.masterPlaylist = { token: 'abc', expires: '1234' };
        """.trimIndent()

        val error = runCatching { parseVixSrcPlaylistMetadata(script, "en") }.exceptionOrNull()
        assertTrue(error is VixSrcMetadataException)
        assertTrue(error?.message.orEmpty().contains("video id"))
    }

    @Test fun `rejects blank language`() {
        val script = """
            window.video = { id: '42' };
            window.masterPlaylist = { token: 'abc', expires: '1234' };
        """.trimIndent()

        val error = runCatching { parseVixSrcPlaylistMetadata(script, "  ") }.exceptionOrNull()
        assertTrue(error is VixSrcMetadataException)
        assertTrue(error?.message.orEmpty().contains("language"))
    }

    @Test fun `rejects metadata from unrelated objects`() {
        val script = """
            window.video = { filename: 'fixture' };
            const unrelated = { id: '99', 'token': 'abc', 'expires': '1234' };
        """.trimIndent()

        val error = runCatching { parseVixSrcPlaylistMetadata(script, "en") }.exceptionOrNull()
        assertTrue(error is VixSrcMetadataException)
    }

    @Test fun `supports double quotes whitespace and nested objects`() {
        val script = """
            window . video = {
                "id" : "42",
                nested: { braces: "{kept inside string}" },
                filename: "fixture"
            };
            window . masterPlaylist = {
                "token" : "abc",
                options: { nested: true },
                "expires" : "1234"
            };
        """.trimIndent()

        assertEquals(
            "https://vixsrc.to/playlist/42?token=abc&expires=1234&lang=en",
            parseVixSrcPlaylistMetadata(script, " en ").playlistUrl,
        )
    }

    @Test fun `ignores comments and braces while scanning assigned objects`() {
        val script = """
            window.video = {
                id: '42',
                text: '} not the end',
                /* } not the end either */
                nested: { ok: true }
            };
            // window.video = { id: 'wrong' };
            window.masterPlaylist = { token: 'abc', expires: '1234' };
        """.trimIndent()

        assertEquals(
            "https://vixsrc.to/playlist/42?token=abc&expires=1234&lang=en",
            parseVixSrcPlaylistMetadata(script, "en").playlistUrl,
        )
    }

    @Test fun `does not leak b flag from unrelated playlist`() {
        val script = """
            window.video = { id: '42' };
            window.masterPlaylist = { token: 'abc', expires: '1234' };
            const unrelated = { url: '/playlist/99?b=1' };
        """.trimIndent()

        val url = parseVixSrcPlaylistMetadata(script, "en").playlistUrl
        assertFalse(url.contains("b=1"))
    }

    @Test fun `builds playlist URL only from matching complete metadata`() {
        val script = """
            window.video = { id: '42', filename: 'fixture' };
            window.masterPlaylist = { 'token': 'abc', 'expires': '1234' };
            const config = { url: '/playlist/42?foo=x&b=1' };
            window   . canPlayFHD = true;
        """.trimIndent()

        assertEquals(
            "https://vixsrc.to/playlist/42?token=abc&expires=1234&b=1&h=1&lang=en",
            parseVixSrcPlaylistMetadata(script, "en").playlistUrl,
        )
    }
}
