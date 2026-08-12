package com.streamflixreborn.streamflix.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class StreamingCommunityRecoveryPolicyTest {
    @Test
    fun `retries wrapped 404 and 410 source failures`() {
        assertTrue(
            isRecoverableStreamingCommunityError(
                IOException("Vixcloud playlist failed with HTTP 404"),
            ),
        )
        assertTrue(
            isRecoverableStreamingCommunityError(
                IOException("provider response status 410 Gone"),
            ),
        )
    }

    @Test
    fun `retries timeouts`() {
        assertTrue(isRecoverableStreamingCommunityError(SocketTimeoutException("timeout")))
    }

    @Test
    fun `does not retry parser or programming failures`() {
        assertFalse(
            isRecoverableStreamingCommunityError(
                IllegalArgumentException("malformed provider id"),
            ),
        )
        assertFalse(
            isRecoverableStreamingCommunityError(
                IOException("unexpected payload shape"),
            ),
        )
    }
}
