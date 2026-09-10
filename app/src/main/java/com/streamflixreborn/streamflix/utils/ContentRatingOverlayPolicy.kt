package com.streamflixreborn.streamflix.utils

/** Pure state used to ensure only media changes trigger an overlay and stale results are rejected. */
class ContentRatingOverlayPolicy {
    var currentMediaKey: String? = null
        private set

    fun onMediaChanged(mediaKey: String): Boolean {
        if (mediaKey == currentMediaKey) return false
        currentMediaKey = mediaKey
        return true
    }

    fun accepts(mediaKey: String): Boolean = currentMediaKey == mediaKey

    companion object {
        const val DISPLAY_DURATION_MS = 5_000L
        const val FADE_OUT_DURATION_MS = 350L
    }
}
