package com.streamflixreborn.streamflix.utils

/** Pure state used to ensure only media changes trigger an overlay and stale results are rejected. */
class ContentRatingOverlayPolicy {
    @Volatile
    var currentMediaKey: String? = null
        private set

    @Synchronized
    fun onMediaChanged(mediaKey: String): Boolean {
        if (mediaKey == currentMediaKey) return false
        currentMediaKey = mediaKey
        return true
    }

    /** Checks the key and publishes while holding the same lock used by [onMediaChanged]. */
    @Synchronized
    fun publishIfCurrent(mediaKey: String, publication: () -> Unit): Boolean {
        if (currentMediaKey != mediaKey) return false
        publication()
        return true
    }

    companion object {
        const val DISPLAY_DURATION_MS = 5_000L
        const val FADE_OUT_DURATION_MS = 350L
    }
}
