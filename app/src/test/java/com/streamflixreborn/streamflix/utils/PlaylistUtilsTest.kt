package com.streamflixreborn.streamflix.utils

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaylistUtilsTest {
    private lateinit var server: MockWebServer
    private lateinit var playlistUtils: PlaylistUtils

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        playlistUtils = PlaylistUtils(OkHttpClient())
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `sends all request headers when loading master playlist`() {
        server.enqueue(
            MockResponse().setBody(
                """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080
                high/index.m3u8
                """.trimIndent(),
            ),
        )

        val videos = playlistUtils.extractFromHls(
            playlistUrl = server.url("/master.m3u8").toString(),
            requestHeaders = mapOf(
                "User-Agent" to "Fixture-UA",
                "Origin" to "https://origin.example",
            ),
            referer = "https://ref.example/embed",
        )

        val request = server.takeRequest()
        assertEquals("Fixture-UA", request.getHeader("User-Agent"))
        assertEquals("https://origin.example", request.getHeader("Origin"))
        assertEquals("https://ref.example/embed", request.getHeader("Referer"))
        assertEquals(1, videos.size)
        assertEquals("Fixture-UA", videos.single().headers?.get("User-Agent"))
        assertEquals("https://origin.example", videos.single().headers?.get("Origin"))
        assertEquals("https://ref.example/embed", videos.single().headers?.get("Referer"))
    }

    @Test fun `preserves headers when master request fails`() {
        server.enqueue(MockResponse().setResponseCode(403))
        val source = server.url("/protected.m3u8").toString()

        val video = playlistUtils.extractFromHls(
            playlistUrl = source,
            requestHeaders = mapOf("Authorization" to "Bearer fixture"),
        ).single()

        assertEquals(source, video.source)
        assertEquals("Bearer fixture", video.headers?.get("Authorization"))
    }

    @Test fun `resolves parent relative variant and subtitle urls`() {
        server.enqueue(
            MockResponse().setBody(
                """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",URI="../subs/en.vtt"
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
                ../video/720/index.m3u8
                """.trimIndent(),
            ),
        )

        val video = playlistUtils.extractFromHls(
            server.url("/catalog/master/index.m3u8").toString(),
        ).single()

        assertTrue(video.source.endsWith("/catalog/video/720/index.m3u8"))
        assertTrue(video.subtitles.single().file.endsWith("/catalog/subs/en.vtt"))
    }

    @Test fun `sorts variants by parsed resolution then bandwidth`() {
        server.enqueue(
            MockResponse().setBody(
                """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=700000,RESOLUTION=854x480
                contains-1080-in-name.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080
                best.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720
                middle.m3u8
                """.trimIndent(),
            ),
        )

        val videos = playlistUtils.extractFromHls(server.url("/master.m3u8").toString())

        assertEquals(3, videos.size)
        assertTrue(videos[0].source.endsWith("/best.m3u8"))
        assertTrue(videos[1].source.endsWith("/middle.m3u8"))
        assertTrue(videos[2].source.endsWith("/contains-1080-in-name.m3u8"))
    }
}
