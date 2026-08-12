package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer

/** Runtime subtitle timing shared by the player settings and text renderer. */
object SubtitleOffset {
    @Volatile
    var offsetMs: Long = 0
}

/**
 * Builds the normal Media3 renderers, but presents subtitle cues against an adjusted clock.
 * A positive offset delays captions and a negative offset advances them.
 */
@UnstableApi
class SubtitleOffsetRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        val textRenderer = TextRenderer(output, outputLooper)
        out.add(object : ForwardingRenderer(textRenderer) {
            private fun adjustedPositionUs(positionUs: Long): Long =
                (positionUs - SubtitleOffset.offsetMs * 1_000).coerceAtLeast(0)

            override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
                super.render(adjustedPositionUs(positionUs), elapsedRealtimeUs)
            }

            override fun enable(
                configuration: RendererConfiguration,
                formats: Array<out Format>,
                stream: SampleStream,
                positionUs: Long,
                joining: Boolean,
                mayRenderStartOfStream: Boolean,
                startPositionUs: Long,
                offsetUs: Long,
                mediaPeriodId: MediaSource.MediaPeriodId,
            ) {
                super.enable(
                    configuration,
                    formats,
                    stream,
                    adjustedPositionUs(positionUs),
                    joining,
                    mayRenderStartOfStream,
                    startPositionUs,
                    offsetUs,
                    mediaPeriodId,
                )
            }

            override fun resetPosition(positionUs: Long) {
                super.resetPosition(adjustedPositionUs(positionUs))
            }
        })
    }
}
