package com.streamflixreborn.streamflix.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ContentRatingOverlayPolicyTest {
    @Test fun `overlay duration is five seconds`() {
        assertEquals(5_000L, ContentRatingOverlayPolicy.DISPLAY_DURATION_MS)
    }

    @Test fun `same media events such as pause and resume do not retrigger`() {
        val policy = ContentRatingOverlayPolicy()
        assertTrue(policy.onMediaChanged("movie:A"))
        assertFalse(policy.onMediaChanged("movie:A"))
    }

    @Test fun `new episode restarts and old asynchronous result is rejected`() {
        val policy = ContentRatingOverlayPolicy()
        assertTrue(policy.onMediaChanged("episode:A"))
        assertTrue(policy.onMediaChanged("episode:B"))
        assertFalse(policy.publishIfCurrent("episode:A") { error("stale result published") })
        var published = false
        assertTrue(policy.publishIfCurrent("episode:B") { published = true })
        assertTrue(published)
    }

    @Test fun `media change cannot interleave with validated publication`() {
        val policy = ContentRatingOverlayPolicy()
        policy.onMediaChanged("episode:A")
        val publicationStarted = CountDownLatch(1)
        val allowPublicationToFinish = CountDownLatch(1)
        val mediaChangeFinished = CountDownLatch(1)
        val publisher = Thread {
            policy.publishIfCurrent("episode:A") {
                publicationStarted.countDown()
                allowPublicationToFinish.await(1, TimeUnit.SECONDS)
            }
        }
        val changer = Thread {
            publicationStarted.await(1, TimeUnit.SECONDS)
            policy.onMediaChanged("episode:B")
            mediaChangeFinished.countDown()
        }
        publisher.start()
        changer.start()
        assertTrue(publicationStarted.await(1, TimeUnit.SECONDS))
        assertFalse(mediaChangeFinished.await(50, TimeUnit.MILLISECONDS))
        allowPublicationToFinish.countDown()
        publisher.join()
        changer.join()
        assertTrue(mediaChangeFinished.await(1, TimeUnit.SECONDS))
        assertFalse(policy.publishIfCurrent("episode:A") { error("stale result published") })
    }
}
