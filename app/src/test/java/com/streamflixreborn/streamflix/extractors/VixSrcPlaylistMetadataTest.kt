package com.streamflixreborn.streamflix.extractors

import org.junit.Assert.assertEquals
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

        val error = runCatching {
            parseVixSrcPlaylistMetadata(script, "en")
        }.exceptionOrNull()

        assertTrue(error is VixSrcMetadataException)
    }

    @Test fun `builds playlist URL only from complete metadata`() {
        val script = """
            window.video = { id: '42', filename: 'fixture' };
            window.masterPlaylist = { 'token': 'abc', 'expires': '1234' };
            const config = { url: '/playlist/42?b=1' };
            window.canPlayFHD = true;
        """.trimIndent()

        assertEquals(
            "https://vixsrc.to/playlist/42?token=abc&expires=1234&b=1&h=1&lang=en",
            parseVixSrcPlaylistMetadata(script, "en").playlistUrl,
        )
    }
}
