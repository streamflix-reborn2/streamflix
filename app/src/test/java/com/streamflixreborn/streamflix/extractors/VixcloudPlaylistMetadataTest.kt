package com.streamflixreborn.streamflix.extractors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VixcloudPlaylistMetadataTest {
    @Test
    fun `parses nested params without crossing object boundaries`() {
        val script = """
            window.video = { id: 42, filename: 'fixture' };
            window.masterPlaylist = {
                url: '/playlist/42?b=1',
                params: {
                    token: 'abc',
                    expires: '1234'
                }
            };
        """.trimIndent()

        val metadata = parseVixcloudPlaylistMetadata(script)
        assertEquals("42", metadata.videoId)
        assertEquals("abc", metadata.token)
        assertEquals("1234", metadata.expires)
        assertTrue(metadata.hasBParam)
    }

    @Test
    fun `supports direct master playlist params and quoted keys`() {
        val script = """
            window . video = { "id": "77", nested: { id: 99 } };
            window . masterPlaylist = {
                "token": "fresh",
                "expires": "5678"
            };
        """.trimIndent()

        val metadata = parseVixcloudPlaylistMetadata(script)
        assertEquals("77", metadata.videoId)
        assertEquals("fresh", metadata.token)
        assertEquals("5678", metadata.expires)
        assertFalse(metadata.hasBParam)
    }

    @Test
    fun `rejects unrelated token and expiry objects`() {
        val script = """
            window.video = { id: 42 };
            window.masterPlaylist = { params: { token: '', expires: '' } };
            const unrelated = { token: 'wrong', expires: '9999' };
        """.trimIndent()

        val error = runCatching { parseVixcloudPlaylistMetadata(script) }.exceptionOrNull()
        assertTrue(error is VixcloudMetadataException)
    }

    @Test
    fun `rejects missing video id even if nested id exists`() {
        val script = """
            window.video = { nested: { id: 99 } };
            window.masterPlaylist = { params: { token: 'abc', expires: '1234' } };
        """.trimIndent()

        val error = runCatching { parseVixcloudPlaylistMetadata(script) }.exceptionOrNull()
        assertTrue(error is VixcloudMetadataException)
        assertTrue(error?.message.orEmpty().contains("video id"))
    }
}
