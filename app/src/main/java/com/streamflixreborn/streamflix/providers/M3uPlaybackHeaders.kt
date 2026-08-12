package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.Video
import java.net.InetAddress
import java.net.URI
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Response

internal data class M3uPlaybackIdentity(
    val provider: String? = null,
    val url: String,
    val name: String,
    val logo: String?,
    val userAgent: String?,
    val referrer: String?,
)

private const val M3U_PLAYBACK_IDENTITY_PREFIX_V1 = "m3u1;"
private const val M3U_PLAYBACK_IDENTITY_PREFIX_V2 = "m3u2;"
internal const val MAX_M3U_PLAYBACK_ID_LENGTH = 32 * 1024
internal const val MAX_M3U_PLAYBACK_PAYLOAD_LENGTH = 24 * 1024
private const val MAX_M3U_URL_BYTES = 8 * 1024
private const val MAX_M3U_NAME_BYTES = 512
private const val MAX_M3U_LOGO_BYTES = 8 * 1024
private const val MAX_M3U_USER_AGENT_BYTES = 1024
private const val MAX_M3U_REFERRER_BYTES = 8 * 1024
private const val MAX_M3U_PROVIDER_BYTES = 128
private val CANONICAL_BASE64 = Regex("""[A-Za-z0-9+/]*={0,2}""")

internal class M3uPlaybackIdentityException : IOException("Malformed M3U playback identity")

internal fun encodeM3uPlaybackIdentity(identity: M3uPlaybackIdentity): String {
    val validated = validateM3uPlaybackIdentity(identity) ?: throw M3uPlaybackIdentityException()
    if (validated.provider.isNullOrBlank()) throw M3uPlaybackIdentityException()
    val payload = buildString {
        append(M3U_PLAYBACK_IDENTITY_PREFIX_V2)
        listOf(
            validated.provider,
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

internal fun isPublicHttpUrl(value: String): Boolean {
    if (value.any { it.code < 0x20 || it.code == 0x7f }) return false
    val uri = try {
        URI(value)
    } catch (_: Exception) {
        return false
    }
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
    if (!uri.isAbsolute || uri.userInfo != null || uri.host.isNullOrBlank()) return false
    if (uri.port != -1 && uri.port !in 1..65535) return false

    val host = uri.host.lowercase().removePrefix("[").removeSuffix("]").removeSuffix(".")
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
    val numericDottedHost = host.contains('.') && host.all { it.isDigit() || it == '.' }
    val dottedIpv4 = host.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}"""))
    if (numericDottedHost && !dottedIpv4) return false
    if (dottedIpv4 && host.split('.').any { it.length > 1 && it.startsWith('0') }) return false
    val isIpLiteral =
        host.contains(':') ||
        host.all(Char::isDigit) ||
        host.matches(Regex("""0[xX][0-9a-fA-F]+""")) ||
        dottedIpv4
    if (!isIpLiteral) {
        if (host.length > 253) return false
        return host.split('.').all { label ->
            label.matches(Regex("""[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"""))
        }
    }

    val address = try {
        InetAddress.getByName(host)
    } catch (_: Exception) {
        return false
    }
    return isGloballyRoutableAddress(address)
}

private fun ipv4Value(bytes: ByteArray, offset: Int = 0): Long =
    ((bytes[offset].toLong() and 0xff) shl 24) or
        ((bytes[offset + 1].toLong() and 0xff) shl 16) or
        ((bytes[offset + 2].toLong() and 0xff) shl 8) or
        (bytes[offset + 3].toLong() and 0xff)

private fun ipv4InCidr(value: Long, base: Long, prefix: Int): Boolean {
    val mask = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
    return (value and mask) == (base and mask)
}

private val SPECIAL_USE_IPV4 = listOf(
    0x00000000L to 8,
    0x0a000000L to 8,
    0x64400000L to 10,
    0x7f000000L to 8,
    0xa9fe0000L to 16,
    0xac100000L to 12,
    0xc0000000L to 24,
    0xc0000200L to 24,
    0xc01fc400L to 24,
    0xc034c100L to 24,
    0xc0586300L to 24,
    0xc0af3000L to 24,
    0xc0a80000L to 16,
    0xc6120000L to 15,
    0xc6336400L to 24,
    0xcb007100L to 24,
    0xe0000000L to 4,
    0xf0000000L to 4,
)

internal fun isGloballyRoutableAddress(value: String): Boolean = try {
    isGloballyRoutableAddress(InetAddress.getByName(value))
} catch (_: Exception) {
    false
}

internal fun isGloballyRoutableAddress(address: InetAddress): Boolean {
    if (
        address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isMulticastAddress
    ) return false

    val bytes = address.address
    if (bytes.size == 4) {
        val value = ipv4Value(bytes)
        return SPECIAL_USE_IPV4.none { (base, prefix) -> ipv4InCidr(value, base, prefix) }
    }
    if (bytes.size != 16) return false

    val isMappedIpv4 = bytes.take(10).all { it == 0.toByte() } &&
        bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
    if (isMappedIpv4) {
        val value = ipv4Value(bytes, 12)
        return SPECIAL_USE_IPV4.none { (base, prefix) -> ipv4InCidr(value, base, prefix) }
    }

    // Globally routable IPv6 unicast currently lives in 2000::/3.
    if ((bytes[0].toInt() and 0xe0) != 0x20) return false
    // Reject protocol assignments, documentation, transition, and experimental ranges.
    val b0 = bytes[0].toInt() and 0xff
    val b1 = bytes[1].toInt() and 0xff
    val b2 = bytes[2].toInt() and 0xff
    val b3 = bytes[3].toInt() and 0xff
    if (b0 == 0x20 && b1 == 0x01 && b2 <= 0x01) return false // 2001::/23 protocol assignments
    if (b0 == 0x20 && b1 == 0x02) return false // 2002::/16 (6to4)
    if (b0 == 0x20 && b1 == 0x01 && b2 == 0x0d && b3 == 0xb8) return false // documentation
    if (b0 == 0x3f && (b1 and 0xf0) == 0xf0) return false // 3fff::/20 documentation
    return true
}

internal class PublicPlaybackDns(
    private val resolver: (String) -> List<InetAddress>,
) : Dns {
    constructor(delegate: Dns) : this(delegate::lookup)

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = resolver(hostname)
        if (addresses.isEmpty() || addresses.any { !isGloballyRoutableAddress(it) }) {
            throw M3uPlaybackIdentityException()
        }
        return addresses
    }
}

internal fun resolvePublicPlaybackRedirect(baseUrl: String, location: String): String? {
    val base = try {
        URI(baseUrl)
    } catch (_: Exception) {
        return null
    }
    val resolved = try {
        base.resolve(location).toString()
    } catch (_: Exception) {
        return null
    }
    return resolved.takeIf(::isPublicHttpUrl)
}

internal object PublicPlaybackNetworkInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isPublicHttpUrl(request.url.toString())) throw M3uPlaybackIdentityException()

        val response = chain.proceed(request)
        val location = response.header("Location")
        if (
            response.isRedirect &&
            location != null &&
            resolvePublicPlaybackRedirect(request.url.toString(), location) == null
        ) {
            response.close()
            throw M3uPlaybackIdentityException()
        }
        return response
    }
}

private fun hasUnsafeText(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            char.code < 0x20 || char.code in 0x7f..0x9f -> return true
            char.isHighSurrogate() -> {
                if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return true
                index += 2
                continue
            }
            char.isLowSurrogate() -> return true
        }
        index++
    }
    return false
}

private fun validField(value: String, maxBytes: Int): Boolean =
    !hasUnsafeText(value) && value.toByteArray(Charsets.UTF_8).size <= maxBytes

internal fun validateM3uPlaybackIdentity(identity: M3uPlaybackIdentity): M3uPlaybackIdentity? {
    if (identity.provider?.let { it.isBlank() || !validField(it, MAX_M3U_PROVIDER_BYTES) } == true) return null
    if (!validField(identity.url, MAX_M3U_URL_BYTES) || !isPublicHttpUrl(identity.url)) return null
    if (identity.name.isBlank() || !validField(identity.name, MAX_M3U_NAME_BYTES)) return null
    if (identity.logo?.let { !validField(it, MAX_M3U_LOGO_BYTES) } == true) return null
    if (identity.logo?.takeIf { it.isNotBlank() }?.let(::isPublicHttpUrl) == false) return null
    if (identity.userAgent?.let { !validField(it, MAX_M3U_USER_AGENT_BYTES) } == true) return null
    if (identity.referrer?.let { !validField(it, MAX_M3U_REFERRER_BYTES) } == true) return null
    if (identity.referrer?.takeIf { it.isNotBlank() }?.let(::isPublicHttpUrl) == false) return null
    return identity
}

internal fun decodeM3uPlaybackIdentity(
    payload: String,
    legacyFieldCount: Int = 5,
): M3uPlaybackIdentity? {
    if (
        !payload.startsWith(M3U_PLAYBACK_IDENTITY_PREFIX_V1) &&
        !payload.startsWith(M3U_PLAYBACK_IDENTITY_PREFIX_V2)
    ) {
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

    val isV2 = payload.startsWith(M3U_PLAYBACK_IDENTITY_PREFIX_V2)
    val prefix = if (isV2) M3U_PLAYBACK_IDENTITY_PREFIX_V2 else M3U_PLAYBACK_IDENTITY_PREFIX_V1
    val fieldCount = if (isV2) 6 else 5
    var position = prefix.length
    val fields = ArrayList<String?>(fieldCount)
    repeat(fieldCount) {
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
            provider = if (isV2) fields[0] ?: return null else null,
            url = fields[if (isV2) 1 else 0] ?: return null,
            name = fields[if (isV2) 2 else 1] ?: return null,
            logo = fields[if (isV2) 3 else 2],
            userAgent = fields[if (isV2) 4 else 3],
            referrer = fields[if (isV2) 5 else 4],
        ),
    )
}

internal fun decodeM3uPlaybackIdentityFromBase64(
    encoded: String,
    legacyFieldCount: Int = 5,
    expectedProvider: String? = null,
    decodeBase64: (String) -> ByteArray,
    encodeBase64: ((ByteArray) -> String)? = null,
): M3uPlaybackIdentity? {
    if (
        encoded.length > MAX_M3U_PLAYBACK_ID_LENGTH ||
        encoded.length % 4 != 0 ||
        !CANONICAL_BASE64.matches(encoded)
    ) return null
    val decodedBytes = try {
        decodeBase64(encoded)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val canonicalEncoding = encodeBase64?.invoke(decodedBytes)
    if (canonicalEncoding != null && canonicalEncoding != encoded) return null
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
    val identity = decodeM3uPlaybackIdentity(
        payload = decodedText,
        legacyFieldCount = legacyFieldCount,
    )
    if (expectedProvider != null && identity?.provider != expectedProvider) return null
    return identity
}

internal fun requireM3uPlaybackIdentityFromBase64(
    encoded: String,
    legacyFieldCount: Int = 5,
    expectedProvider: String? = null,
    decodeBase64: (String) -> ByteArray,
    encodeBase64: ((ByteArray) -> String)? = null,
): M3uPlaybackIdentity = decodeM3uPlaybackIdentityFromBase64(
    encoded = encoded,
    legacyFieldCount = legacyFieldCount,
    expectedProvider = expectedProvider,
    decodeBase64 = decodeBase64,
    encodeBase64 = encodeBase64,
) ?: throw M3uPlaybackIdentityException()

internal fun isValidM3uPlaybackIdentity(
    url: String,
    name: String,
    logo: String?,
    userAgent: String?,
    referrer: String?,
): Boolean = validateM3uPlaybackIdentity(
    M3uPlaybackIdentity(
        url = url,
        name = name,
        logo = logo,
        userAgent = userAgent,
        referrer = referrer,
    ),
) != null

internal fun <T> Iterable<T>.filterValidM3uPlaybackIdentities(
    identityOf: (T) -> M3uPlaybackIdentity,
): List<T> = filter { validateM3uPlaybackIdentity(identityOf(it)) != null }

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
    restrictToPublicNetwork = true,
)
