package com.streamflixreborn.streamflix.fragments.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class PlaybackRequestSessionGateTest {
    @Test fun `new discovery invalidates prior discovery and active resolution`() {
        val gate = PlaybackRequestSessionGate()
        val oldDiscovery = gate.beginDiscovery()
        val oldResolution = gate.beginResolution()
        val newDiscovery = gate.beginDiscovery()

        assertFalse(gate.runIfCurrent(oldDiscovery) {})
        assertFalse(gate.runIfCurrent(oldResolution) {})
        assertTrue(gate.runIfCurrent(newDiscovery) {})
    }

    @Test fun `late old discovery completion is rejected after newer episode starts`() {
        val gate = PlaybackRequestSessionGate()
        val oldDiscovery = gate.beginDiscovery()
        val releaseOldProvider = CountDownLatch(1)
        val stalePublished = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val staleCompletion = executor.submit(java.util.concurrent.Callable {
                releaseOldProvider.await(5, TimeUnit.SECONDS)
                gate.runIfCurrent(oldDiscovery) { stalePublished.set(true) }
            })

            val newDiscovery = gate.beginDiscovery()
            releaseOldProvider.countDown()
            assertFalse(staleCompletion.get(5, TimeUnit.SECONDS))
            assertFalse(stalePublished.get())
            assertTrue(gate.runIfCurrent(newDiscovery) {})
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `late resolver completion is rejected after reload discovery starts`() {
        val gate = PlaybackRequestSessionGate()
        gate.beginDiscovery()
        val oldResolution = gate.beginResolution()
        val releaseOldResolver = CountDownLatch(1)
        val stalePublished = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val staleCompletion = executor.submit(java.util.concurrent.Callable {
                releaseOldResolver.await(5, TimeUnit.SECONDS)
                gate.runIfCurrent(oldResolution) { stalePublished.set(true) }
            })

            gate.beginDiscovery()
            releaseOldResolver.countDown()
            assertFalse(staleCompletion.get(5, TimeUnit.SECONDS))
            assertFalse(stalePublished.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `discovery publication is atomic with starting a newer discovery`() {
        val gate = PlaybackRequestSessionGate()
        val request = gate.beginDiscovery()
        assertPublicationBlocksNewBegin(
            publish = { action -> gate.runIfCurrent(request, action) },
            beginNewRequest = gate::beginDiscovery,
        )
    }

    @Test fun `resolution publication is atomic with starting a newer resolution`() {
        val gate = PlaybackRequestSessionGate()
        val request = gate.beginResolution()
        assertPublicationBlocksNewBegin(
            publish = { action -> gate.runIfCurrent(request, action) },
            beginNewRequest = gate::beginResolution,
        )
    }

    private fun assertPublicationBlocksNewBegin(
        publish: ((() -> Unit)) -> Boolean,
        beginNewRequest: () -> Any,
    ) {
        val actionEntered = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val beginAttempted = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val publication = executor.submit(java.util.concurrent.Callable {
                publish {
                    events += "publication-entered"
                    actionEntered.countDown()
                    assertTrue(releaseAction.await(5, TimeUnit.SECONDS))
                    events += "publication-exited"
                }
            })
            assertTrue(actionEntered.await(5, TimeUnit.SECONDS))

            val newerBegin = executor.submit(java.util.concurrent.Callable {
                events += "begin-attempted"
                beginAttempted.countDown()
                beginNewRequest()
                events += "begin-returned"
            })
            assertTrue(beginAttempted.await(5, TimeUnit.SECONDS))
            try {
                newerBegin.get(150, TimeUnit.MILLISECONDS)
                throw AssertionError("A newer request began while publication was still in progress")
            } catch (_: TimeoutException) {
                // Expected: begin uses the same lock held for the publication action.
            }

            releaseAction.countDown()
            assertTrue(publication.get(5, TimeUnit.SECONDS))
            newerBegin.get(5, TimeUnit.SECONDS)
            assertEquals(
                listOf("publication-entered", "begin-attempted", "publication-exited", "begin-returned"),
                events,
            )
        } finally {
            releaseAction.countDown()
            executor.shutdownNow()
        }
    }
}
