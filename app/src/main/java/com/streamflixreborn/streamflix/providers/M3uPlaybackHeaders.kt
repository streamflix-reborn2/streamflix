package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.Video

internal fun m3uPlaybackHeaders(
    userAgent: String?,
    referrer: String?,
): Map<String, String>? = buildMap {
    userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
    referrer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
}.ifEmpty { null }

internal fun m3uPlaybackVideo(
    source: String,
    userAgent: String?,
    referrer: String?,
): Video = Video(
    source = source,
    subtitles = emptyList(),
    headers = m3uPlaybackHeaders(userAgent, referrer),
)
