package com.streamflixreborn.streamflix.fragments.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TvPlaybackRecoveryInstrumentedCanaryTest {
    private data class Candidate(val id: String, val url: String)

    @Test
    fun playerErrorRetriesOnceThenFailsOverAndActuallyPlaysFixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val fixtureBytes = instrumentation.context.assets
            .open("playback_recovery_canary.mp4")
            .use { it.readBytes() }
        val server = object : NanoHTTPD(0) {
            override fun serve(session: IHTTPSession): Response {
                if (session.uri != "/fixture.mp4") {
                    return newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        MIME_PLAINTEXT,
                        "intentional recovery canary failure",
                    )
                }
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "video/mp4",
                    ByteArrayInputStream(fixtureBytes),
                    fixtureBytes.size.toLong(),
                ).apply {
                    addHeader("Accept-Ranges", "bytes")
                    addHeader("Cache-Control", "no-store")
                }
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

        val bad = Candidate("bad", "http://127.0.0.1:${server.listeningPort}/missing.mp4")
        val good = Candidate("good", "http://127.0.0.1:${server.listeningPort}/fixture.mp4")
        val coordinator = PlaybackSourceRecoveryCoordinator<Candidate> {
            PlaybackSourceRecoveryCoordinator.Identity(it.id, it.id, it.url)
        }
        val loads = Collections.synchronizedList(mutableListOf<String>())
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val progressCheckStarted = AtomicBoolean(false)
        val playerRef = AtomicReference<ExoPlayer?>()

        fun failCanary(cause: Throwable) {
            failure.compareAndSet(null, cause)
            completed.countDown()
        }

        lateinit var handleOutcome: (PlaybackSourceRecoveryCoordinator.Outcome<Candidate>) -> Unit
        handleOutcome = { outcome ->
            when (outcome) {
                is PlaybackSourceRecoveryCoordinator.Outcome.Resolve -> {
                    loads += outcome.request.candidate.id
                    val resolved = coordinator.resolved(
                        outcome.request.token,
                        outcome.request.candidate.url,
                    )
                    handleOutcome(resolved)
                }
                is PlaybackSourceRecoveryCoordinator.Outcome.Retry -> {
                    loads += outcome.request.candidate.id
                    val resolved = coordinator.resolved(
                        outcome.request.token,
                        outcome.request.candidate.url,
                    )
                    handleOutcome(resolved)
                }
                is PlaybackSourceRecoveryCoordinator.Outcome.Restore -> {
                    failCanary(AssertionError("Unexpected restoration during canary: $outcome"))
                }
                is PlaybackSourceRecoveryCoordinator.Outcome.Success -> {
                    val player = checkNotNull(playerRef.get())
                    player.stop()
                    player.setMediaItem(MediaItem.fromUri(outcome.source))
                    player.prepare()
                    player.play()
                }
                is PlaybackSourceRecoveryCoordinator.Outcome.Exhausted -> {
                    failCanary(AssertionError("Recovery exhausted before fixture playback", outcome.lastFailure.causeOrNull()))
                }
                is PlaybackSourceRecoveryCoordinator.Outcome.DiscoveryFailure -> failCanary(outcome.cause)
                PlaybackSourceRecoveryCoordinator.Outcome.NoSources -> failCanary(AssertionError("No sources"))
                PlaybackSourceRecoveryCoordinator.Outcome.Cancelled -> Unit
            }
        }

        try {
            instrumentation.runOnMainSync {
                val player = ExoPlayer.Builder(targetContext).build()
                playerRef.set(player)
                player.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        handleOutcome(coordinator.playerFailed(error))
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState != Player.STATE_READY || loads.lastOrNull() != good.id) return
                        coordinator.ready()
                        if (!progressCheckStarted.compareAndSet(false, true)) return
                        val handler = Handler(Looper.getMainLooper())
                        handler.post(object : Runnable {
                            override fun run() {
                                val current = playerRef.get() ?: return
                                if (current.currentPosition >= 500L && current.isPlaying) {
                                    coordinator.confirmedWorking()
                                    completed.countDown()
                                } else if (completed.count > 0L) {
                                    handler.postDelayed(this, 100L)
                                }
                            }
                        })
                    }
                })
                handleOutcome(coordinator.discover(listOf(bad, good)))
            }

            assertTrue("TV playback canary timed out", completed.await(30, TimeUnit.SECONDS))
            assertNull("TV playback canary failed", failure.get())
            assertEquals(listOf("bad", "bad", "good"), loads.toList())
        } finally {
            instrumentation.runOnMainSync {
                playerRef.getAndSet(null)?.release()
            }
            server.stop()
        }
    }

    private fun PlaybackSourceRecoveryCoordinator.Failure.causeOrNull(): Throwable? = when (this) {
        is PlaybackSourceRecoveryCoordinator.Failure.DiscoveryFailure -> cause
        is PlaybackSourceRecoveryCoordinator.Failure.PlayerFailure -> cause
        is PlaybackSourceRecoveryCoordinator.Failure.ResolutionFailure -> cause
        is PlaybackSourceRecoveryCoordinator.Failure.InvalidSource,
        PlaybackSourceRecoveryCoordinator.Failure.NoSources -> null
    }
}
