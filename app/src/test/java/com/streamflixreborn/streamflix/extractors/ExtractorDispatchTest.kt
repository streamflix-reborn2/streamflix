package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtractorDispatchTest {
    @Test fun `dispatcher preserves server context for selected extractor`() = runBlocking {
        val server = Video.Server(
            id = "fixture-id",
            name = "Fixture server",
            src = "https://fixture.example/embed",
        )

        val video = dispatchExtraction(
            link = "https://fixture.example/embed",
            server = server,
            extract = { link, receivedServer ->
                assertEquals("https://fixture.example/embed", link)
                assertEquals("fixture-id", receivedServer?.id)
                Video(source = "https://fixture.example/video.m3u8")
            },
        )

        assertEquals("https://fixture.example/video.m3u8", video.source)
    }
}
