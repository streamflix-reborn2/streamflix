package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.Video

internal data class M3uPlaybackIdentity(
    val url: String,
    val name: String,
    val logo: String?,
    val userAgent: String?,
    val referrer: String?,
)

private const val M3U_PLAYBACK_IDENTITY_PREFIX = "m3u1;"

internal fun encodeM3uPlaybackIdentity(identity: M3uPlaybackIdentity): String = buildString {
    append(M3U_PLAYBACK_IDENTITY_PREFIX)
    listOf(
        identity.url,
        identity.name,
        identity.logo,
        identity.userAgent,
        identity.referrer,
    ).forEach { value ->
        if (value == null) {
            append("-1:")
        } else {
            append(value.length).append(':').append(value)
        }
    }
}

internal fun decodeM3uPlaybackIdentity(
    payload: String,
    legacyFieldCount: Int = 5,
): M3uPlaybackIdentity? {
    if (!payload.startsWith(M3U_PLAYBACK_IDENTITY_PREFIX)) {
        val fields = payload.split("|")
        if (fields.size != legacyFieldCount || legacyFieldCount !in 4..5) return null
        return M3uPlaybackIdentity(
            url = fields[0],
            name = fields[1],
            logo = fields.getOrNull(2)?.ifEmpty { null },
            userAgent = fields.getOrNull(3)?.ifEmpty { null },
            referrer = fields.getOrNull(4)?.ifEmpty { null },
        )
    }

    var position = M3U_PLAYBACK_IDENTITY_PREFIX.length
    val fields = ArrayList<String?>(5)
    repeat(5) {
        val separator = payload.indexOf(':', position)
        if (separator < 0) return null
        val length = payload.substring(position, separator).toIntOrNull() ?: return null
        position = separator + 1
        if (length == -1) {
            fields.add(null)
        } else {
            if (length < 0 || position + length > payload.length) return null
            fields.add(payload.substring(position, position + length))
            position += length
        }
    }
    if (position != payload.length) return null

    return M3uPlaybackIdentity(
        url = fields[0] ?: return null,
        name = fields[1] ?: return null,
        logo = fields[2],
        userAgent = fields[3],
        referrer = fields[4],
    )
}

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
