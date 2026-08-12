package com.streamflixreborn.streamflix.ui

import android.graphics.drawable.PictureDrawable
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.resource.SimpleResource
import com.bumptech.glide.load.resource.transcode.ResourceTranscoder
import com.caverock.androidsvg.SVG

/** Keeps SVG artwork vector-backed until Android draws it into the target view. */
class SvgDrawableTranscoder : ResourceTranscoder<SVG, PictureDrawable> {
    override fun transcode(
        toTranscode: Resource<SVG>,
        options: Options,
    ): Resource<PictureDrawable> {
        return SimpleResource(PictureDrawable(toTranscode.get().renderToPicture()))
    }
}
