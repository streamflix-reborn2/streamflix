package com.streamflixreborn.streamflix.extractors

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class VixSrcMetadataException : Exception("VixSrc playlist metadata is missing")

internal data class VixSrcPlaylistMetadata(
    val playlistUrl: String,
)

internal fun parseVixSrcPlaylistMetadata(
    scriptText: String,
    language: String,
): VixSrcPlaylistMetadata {
    val videoId = Regex(
        """window\.video\s*=\s*\{[\s\S]*?\bid\s*:\s*['\"]([^'\"]+)['\"]""",
    ).find(scriptText)?.groupValues?.get(1)?.trim().orEmpty()
    val token = Regex(
        """['\"]token['\"]\s*:\s*['\"]([^'\"]+)['\"]""",
    ).find(scriptText)?.groupValues?.get(1)?.trim().orEmpty()
    val expires = Regex(
        """['\"]expires['\"]\s*:\s*['\"]([^'\"]+)['\"]""",
    ).find(scriptText)?.groupValues?.get(1)?.trim().orEmpty()

    if (videoId.isBlank() || token.isBlank() || expires.isBlank()) {
        throw VixSrcMetadataException()
    }

    val url = "https://vixsrc.to/playlist/$videoId".toHttpUrlOrNull()?.newBuilder()
        ?: throw VixSrcMetadataException()
    url.addQueryParameter("token", token)
    url.addQueryParameter("expires", expires)
    if (Regex("""[?&]b=1(?:[&'\"\s]|$)""").containsMatchIn(scriptText)) {
        url.addQueryParameter("b", "1")
    }
    if (scriptText.contains("window.canPlayFHD = true")) {
        url.addQueryParameter("h", "1")
    }
    url.addQueryParameter("lang", language)

    return VixSrcPlaylistMetadata(playlistUrl = url.build().toString())
}
