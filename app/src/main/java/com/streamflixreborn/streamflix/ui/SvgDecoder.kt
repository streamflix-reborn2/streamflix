package com.streamflixreborn.streamflix.ui

import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.resource.SimpleResource
import com.bumptech.glide.request.target.Target
import com.caverock.androidsvg.SVG
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.ceil

/** Parses SVG streams while leaving rendering to the PictureDrawable transcoder. */
class SvgDecoder : ResourceDecoder<InputStream, SVG> {

    override fun handles(source: InputStream, options: Options): Boolean {
        if (!source.markSupported()) return false

        source.mark(SVG_HEADER_LIMIT)
        return try {
            val header = ByteArray(SVG_HEADER_LIMIT)
            val bytesRead = source.read(header)
            bytesRead > 0 && String(header, 0, bytesRead, StandardCharsets.UTF_8)
                .lowercase(Locale.ROOT)
                .contains("<svg")
        } finally {
            source.reset()
        }
    }

    override fun decode(
        source: InputStream,
        width: Int,
        height: Int,
        options: Options,
    ): Resource<SVG> {
        val svg = try {
            SVG.getFromInputStream(source)
        } catch (error: Exception) {
            throw IOException("Unable to parse SVG image", error)
        }

        val viewBox = svg.documentViewBox
        svg.documentWidth = resolveDimension(width, svg.documentWidth, viewBox?.width()).toFloat()
        svg.documentHeight = resolveDimension(height, svg.documentHeight, viewBox?.height()).toFloat()
        return SimpleResource(svg)
    }

    private fun resolveDimension(requested: Int, document: Float, viewBox: Float?): Int {
        val intrinsic = document.takeIf { it.isFinite() && it > 0f }
            ?: viewBox?.takeIf { it.isFinite() && it > 0f }
        val resolved = when {
            requested > 0 && requested != Target.SIZE_ORIGINAL -> requested
            intrinsic != null -> ceil(intrinsic).toInt()
            else -> DEFAULT_SVG_SIZE
        }
        return resolved.coerceIn(1, MAX_SVG_SIZE)
    }

    private companion object {
        const val SVG_HEADER_LIMIT = 8 * 1024
        const val DEFAULT_SVG_SIZE = 512
        const val MAX_SVG_SIZE = 2_048
    }
}
