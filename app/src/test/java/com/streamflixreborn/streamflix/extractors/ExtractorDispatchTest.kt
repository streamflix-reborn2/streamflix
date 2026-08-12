package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtractorDispatchTest {
    private class FixtureExtractor(
        override val name: String,
        override val mainUrl: String,
        override val aliasUrls: List<String> = emptyList(),
        override val rotatingDomain: List<Regex> = emptyList(),
    ) : Extractor() {
        var receivedServer: Video.Server? = null
        var receivedLink: String? = null

        override suspend fun extract(link: String): Video {
            error("server-aware overload should be used")
        }

        override suspend fun extract(link: String, server: Video.Server?): Video {
            receivedLink = link
            receivedServer = server
            return Video(source = "https://fixture.example/video.m3u8")
        }
    }

    @Test fun `dispatcher selects extractor and preserves server context`() = runBlocking {
        val server = Video.Server(
            id = "fixture-id",
            name = "Fixture server",
            src = "https://fixture.example/embed",
        )
        val extractor = FixtureExtractor(
            name = "Fixture",
            mainUrl = "https://fixture.example",
        )

        val video = dispatchExtraction(
            link = "https://fixture.example/embed",
            server = server,
            candidates = listOf(extractor),
        )

        assertEquals("https://fixture.example/embed", extractor.receivedLink)
        assertEquals("fixture-id", extractor.receivedServer?.id)
        assertEquals("https://fixture.example/video.m3u8", video.source)
    }

    @Test fun `selection supports aliases without losing context`() = runBlocking {
        val server = Video.Server(id = "alias-id", name = "Alias server")
        val extractor = FixtureExtractor(
            name = "Fixture",
            mainUrl = "https://fixture.example",
            aliasUrls = listOf("https://alias.example"),
        )

        dispatchExtraction(
            link = "https://alias.example/embed/42",
            server = server,
            candidates = listOf(extractor),
        )

        assertEquals("alias-id", extractor.receivedServer?.id)
    }

    @Test fun `selection can fall back to server name`() = runBlocking {
        val server = Video.Server(id = "named-id", name = "Fixture mirror")
        val extractor = FixtureExtractor(
            name = "Fixture",
            mainUrl = "https://unrelated.example",
        )

        dispatchExtraction(
            link = "https://unknown.example/embed/42",
            server = server,
            candidates = listOf(extractor),
        )

        assertEquals("named-id", extractor.receivedServer?.id)
    }

    @Test fun `selection does not invent extractor without a match`() {
        val extractor = FixtureExtractor(
            name = "Fixture",
            mainUrl = "https://fixture.example",
        )

        assertNull(
            selectExtractor(
                link = "https://unknown.example/embed",
                server = null,
                candidates = listOf(extractor),
            ),
        )
    }
}
