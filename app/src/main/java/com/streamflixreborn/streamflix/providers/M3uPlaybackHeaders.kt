package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.Video
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal data class M3uPlaybackIdentity(
    val url: String,
    val name: String,
    val logo: String?,
    val userAgent: String?,
    val referrer: String?,
)

private const val M3U_PLAYBACK_IDENTITY_PREFIX = "m3u1;"
internal const val MAX_M3U_PLAYBACK_ID_LENGTH = 32 * 1024
internal const val MAX_M3U_PLAYBACK_PAYLOAD_LENGTH = 24 * 1024

internal class M3uPlaybackIdentityException : Exception("Malformed M3U playback identity")

internal fun encodeM3uPlaybackIdentity(identity: M3uPlaybackIdentity): String {
    val validated = validateM3uPlaybackIdentity(identity) ?: throw M3uPlaybackIdentityException()
    val payload = buildString {
        append(M3U_PLAYBACK_IDENTITY_PREFIX)
        listOf(
            validated.url,
            validated.name,
            validated.logo,
            validated.userAgent,
            validated.referrer,
        ).forEach { value ->
            if (value == null) {
                append("-1:")
            } else {
                append(value.length).append(':').append(value)
            }
        }
    }
    if (payload.toByteArray(Charsets.UTF_8).size > MAX_M3U_PLAYBACK_PAYLOAD_LENGTH) {
        throw M3uPlaybackIdentityException()
    }
    return payload
}

private fun isPublicHttpUrl(value: String): Boolean {
    if (value.any { it.code < 0x20 || it.code == 0x7f }) return false
    val uri = try {
        URI(value)
    } catch (_: Exception) {
        return false
    }
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
    if (!uri.isAbsolute || uri.userInfo != null || uri.host.isNullOrBlank()) return false

    val host = uri.host.lowercase()
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
    val isIpLiteral =
        host.contains(':') ||
        host.all(Char::isDigit) ||
        host.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}"""))
    if (!isIpLiteral) return true

    val address = try {
        InetAddress.getByName(host)
    } catch (_: Exception) {
        return false
    }
    if (
        address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isMulticastAddress
    ) return false

    val bytes = address.address
    if (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) return false
    if (bytes.size == 4 && (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 64) return false
    return true
}

private fun hasHeaderControls(value: String): Boolean =
    value.any { it.code < 0x20 || it.code == 0x7f }

internal fun validateM3uPlaybackIdentity(identity: M3uPlaybackIdentity): M3uPlaybackIdentity? {
    if (!isPublicHttpUrl(identity.url)) return null
    if (identity.name.isBlank()) return null
    if (identity.logo?.takeIf { it.isNotBlank() }?.let(::isPublicHttpUrl) == false) return null
    if (identity.userAgent?.let(::hasHeaderControls) == true) return null
    if (identity.referrer?.takeIf { it.isNotBlank() }?.let(::isPublicHttpUrl) == false) return null
    return identity
}

internal fun decodeM3uPlaybackIdentity(
    payload: String,
    legacyFieldCount: Int = 5,
): M3uPlaybackIdentity? {
    if (!payload.startsWith(M3U_PLAYBACK_IDENTITY_PREFIX)) {
        val fields = payload.split("|")
        if (fields.size != legacyFieldCount || legacyFieldCount !in 4..5) return null
        return validateM3uPlaybackIdentity(
            M3uPlaybackIdentity(
                url = fields[0],
                name = fields[1],
                logo = fields.getOrNull(2)?.ifEmpty { null },
                userAgent = fields.getOrNull(3)?.ifEmpty { null },
                referrer = fields.getOrNull(4)?.ifEmpty { null },
            ),
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
            if (length < 0 || length > payload.length - position) return null
            fields.add(payload.substring(position, position + length))
            position += length
        }
    }
    if (position != payload.length) return null

    return validateM3uPlaybackIdentity(
        M3uPlaybackIdentity(
            url = fields[0] ?: return null,
            name = fields[1] ?: return null,
            logo = fields[2],
            userAgent = fields[3],
            referrer = fields[4],
        ),
    )
}

internal fun decodeM3uPlaybackIdentityFromBase64(
    encoded: String,
    legacyFieldCount: Int = 5,
    decodeBase64: (String) -> ByteArray,
): M3uPlaybackIdentity? {
    if (encoded.length > MAX_M3U_PLAYBACK_ID_LENGTH) return null
    val decodedBytes = try {
        decodeBase64(encoded)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (decodedBytes.size > MAX_M3U_PLAYBACK_PAYLOAD_LENGTH) return null
    val decodedText = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(decodedBytes))
            .toString()
    } catch (_: Exception) {
        return null
    }
    return decodeM3uPlaybackIdentity(
        payload = decodedText,
        legacyFieldCount = legacyFieldCount,
    )
}

internal fun requireM3uPlaybackIdentityFromBase64(
    encoded: String,
    legacyFieldCount: Int = 5,
    decodeBase64: (String) -> ByteArray,
): M3uPlaybackIdentity = decodeM3uPlaybackIdentityFromBase64(
    encoded = encoded,
    legacyFieldCount = legacyFieldCount,
    decodeBase64 = decodeBase64,
) ?: throw M3uPlaybackIdentityException()

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
