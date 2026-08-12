package com.streamflixreborn.streamflix.extractors

internal class VixcloudMetadataException(reason: String) :
    Exception("Vixcloud playlist metadata is invalid: $reason")

internal data class VixcloudPlaylistMetadata(
    val videoId: String,
    val token: String,
    val expires: String,
    val hasBParam: Boolean,
)

internal fun parseVixcloudPlaylistMetadata(scriptText: String): VixcloudPlaylistMetadata {
    val videoObject = extractAssignedJsObject(scriptText, "video")
        ?: throw VixcloudMetadataException("window.video is missing")
    val playlistObject = extractAssignedJsObject(scriptText, "masterPlaylist")
        ?: throw VixcloudMetadataException("window.masterPlaylist is missing")

    val videoId = findTopLevelJsStringOrNumber(videoObject, "id")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw VixcloudMetadataException("video id is missing")

    // Newer Vixcloud layouts nest these values under params, while older layouts place them
    // directly on masterPlaylist. Search the scoped playlist object only; never the whole script.
    val paramsObject = extractNamedObjectProperty(playlistObject, "params")
    val metadataScope = paramsObject ?: playlistObject
    val token = findTopLevelJsStringOrNumber(metadataScope, "token")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw VixcloudMetadataException("token is missing")
    val expires = findTopLevelJsStringOrNumber(metadataScope, "expires")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw VixcloudMetadataException("expiry is missing")

    val hasBParam = Regex("""(?:[?&])b=1(?:[&'\"\s]|$)""")
        .containsMatchIn(playlistObject)

    return VixcloudPlaylistMetadata(
        videoId = videoId,
        token = token,
        expires = expires,
        hasBParam = hasBParam,
    )
}

private fun findTopLevelJsStringOrNumber(objectBody: String, property: String): String? {
    val escaped = Regex.escape(property)
    val stringProperty = Regex(
        """^\s*(?:['\"]$escaped['\"]|$escaped)\s*:\s*(['\"])(.*?)\1\s*$""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )
    val numberProperty = Regex(
        """^\s*(?:['\"]$escaped['\"]|$escaped)\s*:\s*([0-9]+)\s*$""",
    )

    return splitTopLevelJsFields(objectBody)
        .asSequence()
        .mapNotNull { field ->
            stringProperty.matchEntire(field)?.groupValues?.get(2)
                ?: numberProperty.matchEntire(field)?.groupValues?.get(1)
        }
        .firstOrNull()
}

private fun extractNamedObjectProperty(objectBody: String, property: String): String? {
    val escaped = Regex.escape(property)
    val field = splitTopLevelJsFields(objectBody).firstOrNull {
        Regex("""^\s*(?:['\"]$escaped['\"]|$escaped)\s*:\s*\{""").containsMatchIn(it)
    } ?: return null
    val openingBrace = field.indexOf('{')
    return if (openingBrace >= 0) extractBalancedJsObject(field, openingBrace) else null
}

private fun extractAssignedJsObject(scriptText: String, property: String): String? {
    val assignment = Regex(
        """window\s*\.\s*${Regex.escape(property)}\s*=\s*\{""",
    ).find(scriptText) ?: return null
    val openingBrace = scriptText.indexOf('{', assignment.range.first)
    return if (openingBrace >= 0) extractBalancedJsObject(scriptText, openingBrace) else null
}

private fun extractBalancedJsObject(text: String, openingBrace: Int): String? {
    var depth = 0
    var quote: Char? = null
    var escaped = false
    var lineComment = false
    var blockComment = false
    var index = openingBrace

    while (index < text.length) {
        val char = text[index]
        val next = text.getOrNull(index + 1)

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
                if (depth == 0) return text.substring(openingBrace + 1, index)
                if (depth < 0) return null
            }
        }
        index++
    }
    return null
}

private fun splitTopLevelJsFields(objectBody: String): List<String> {
    val fields = mutableListOf<String>()
    var start = 0
    var braceDepth = 0
    var bracketDepth = 0
    var parenDepth = 0
    var quote: Char? = null
    var escaped = false
    var lineComment = false
    var blockComment = false
    var index = 0

    while (index < objectBody.length) {
        val char = objectBody[index]
        val next = objectBody.getOrNull(index + 1)

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
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == quote) quote = null
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
            '{' -> braceDepth++
            '}' -> braceDepth--
            '[' -> bracketDepth++
            ']' -> bracketDepth--
            '(' -> parenDepth++
            ')' -> parenDepth--
            ',' -> if (braceDepth == 0 && bracketDepth == 0 && parenDepth == 0) {
                fields += objectBody.substring(start, index)
                start = index + 1
            }
        }
        index++
    }
    fields += objectBody.substring(start)
    return fields
}
