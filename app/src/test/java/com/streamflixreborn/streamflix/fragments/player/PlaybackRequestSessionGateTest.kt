package com.streamflixreborn.streamflix.fragments.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
}
