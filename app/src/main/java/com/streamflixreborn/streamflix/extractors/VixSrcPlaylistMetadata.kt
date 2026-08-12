package com.streamflixreborn.streamflix.extractors

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class VixSrcMetadataException(
    reason: String,
) : Exception("VixSrc playlist metadata is invalid: $reason")

internal data class VixSrcPlaylistMetadata(
    val playlistUrl: String,
)

internal fun parseVixSrcPlaylistMetadata(
    scriptText: String,
    language: String,
): VixSrcPlaylistMetadata {
    val videoObject = extractAssignedObject(scriptText, "video")
        ?: throw VixSrcMetadataException("window.video is missing")
    val playlistObject = extractAssignedObject(scriptText, "masterPlaylist")
        ?: throw VixSrcMetadataException("window.masterPlaylist is missing")

    val videoId = findStringProperty(videoObject, "id")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw VixSrcMetadataException("video id is missing")
    val token = findStringProperty(playlistObject, "token")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw VixSrcMetadataException("playlist token is missing")
    val expires = findStringProperty(playlistObject, "expires")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw VixSrcMetadataException("playlist expiry is missing")
    val normalizedLanguage = language.trim()
        .takeIf { it.isNotEmpty() }
        ?: throw VixSrcMetadataException("language is missing")

    val url = "https://vixsrc.to/playlist/$videoId".toHttpUrlOrNull()?.newBuilder()
        ?: throw VixSrcMetadataException("video id cannot form a playlist URL")
    url.addQueryParameter("token", token)
    url.addQueryParameter("expires", expires)

    if (hasMatchingPlaylistFlag(scriptText, videoId, "b", "1")) {
        url.addQueryParameter("b", "1")
    }
    if (Regex("""window\s*\.\s*canPlayFHD\s*=\s*true\b""").containsMatchIn(scriptText)) {
        url.addQueryParameter("h", "1")
    }
    url.addQueryParameter("lang", normalizedLanguage)

    return VixSrcPlaylistMetadata(playlistUrl = url.build().toString())
}

private fun findStringProperty(objectBody: String, property: String): String? {
    val escaped = Regex.escape(property)
    return Regex(
        """(?:^|[,\s])(?:['\"]$escaped['\"]|$escaped)\s*:\s*(['\"])(.*?)\1""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    ).find(objectBody)?.groupValues?.get(2)
}

private fun hasMatchingPlaylistFlag(
    scriptText: String,
    videoId: String,
    key: String,
    expectedValue: String,
): Boolean {
    val escapedId = Regex.escape(videoId)
    val playlistUrl = Regex(
        """['\"]([^'\"]*/playlist/$escapedId\?[^'\"]*)['\"]""",
    ).findAll(scriptText)

    return playlistUrl.any { match ->
        match.groupValues[1]
            .substringAfter('?', "")
            .split('&')
            .any { parameter ->
                val parts = parameter.split('=', limit = 2)
                parts.size == 2 && parts[0] == key && parts[1] == expectedValue
            }
    }
}

private fun extractAssignedObject(scriptText: String, property: String): String? {
    val assignment = Regex(
        """window\s*\.\s*${Regex.escape(property)}\s*=\s*\{""",
    ).find(scriptText) ?: return null
    val openingBrace = scriptText.indexOf('{', assignment.range.first)
    if (openingBrace < 0) return null

    var depth = 0
    var quote: Char? = null
    var escaped = false
    var lineComment = false
    var blockComment = false
    var index = openingBrace

    while (index < scriptText.length) {
        val char = scriptText[index]
        val next = scriptText.getOrNull(index + 1)

        if (lineComment) {
            if (char == '\n' || char == '\r') lineComment = false
            index++
            continue
        }
        if (blockComment) {
            if (char == '*' && next == '/') {
                blockComment = false
                index += 2
            } else {
                index++
            }
            continue
        }

        if (quote != null) {
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == quote) {
                quote = null
            }
            index++
            continue
        }

        if (char == '/' && next == '/') {
            lineComment = true
            index += 2
            continue
        }
        if (char == '/' && next == '*') {
            blockComment = true
            index += 2
            continue
        }
        if (char == '\'' || char == '"' || char == '`') {
            quote = char
            index++
            continue
        }

        when (char) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) {
                    return scriptText.substring(openingBrace + 1, index)
                }
                if (depth < 0) return null
            }
        }
        index++
    }

    return null
}
