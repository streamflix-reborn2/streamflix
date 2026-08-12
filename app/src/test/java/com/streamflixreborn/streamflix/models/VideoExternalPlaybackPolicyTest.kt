package com.streamflixreborn.streamflix.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoExternalPlaybackPolicyTest {
    @Test fun `restricted playback cannot cross into an external player`() {
        val video = Video(
            source = "https://fixture.example/live.m3u8",
            restrictToPublicNetwork = true,
        )

        assertFalse(video.canUseExternalPlayer())
    }

    @Test fun `ordinary playback remains eligible for an external player`() {
        val video = Video(source = "https://fixture.example/video.mp4")

        assertTrue(video.canUseExternalPlayer())
    }
}
