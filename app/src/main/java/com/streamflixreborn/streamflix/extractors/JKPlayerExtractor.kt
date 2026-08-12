package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

class JKPlayerExtractor : Extractor() {

    override val name = "JKPlayer"
    override val mainUrl = "https://jkanime.net/jkplayer/"

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(link)
            .header("User-Agent", NetworkClient.USER_AGENT)
            .header("Referer", "$JKANIME_URL/")
            .build()

        val html = NetworkClient.default.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("JKPlayer request failed (${response.code})")
            }
            response.body?.string().orEmpty()
        }

        val encodedUrl = ATOB_URL_REGEX.find(html)?.groupValues?.getOrNull(1)
        val source = encodedUrl?.let(::decodeBase64Url)
            ?: RAW_HLS_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: throw IllegalStateException("JKPlayer HLS source was not found")

        Video(
            source = source.replace("\\/", "/"),
            headers = mapOf(
                "Origin" to JKANIME_URL,
                "Referer" to "$JKANIME_URL/",
                "User-Agent" to NetworkClient.USER_AGENT,
            ),
            type = MimeTypes.APPLICATION_M3U8,
        )
    }

    private fun decodeBase64Url(value: String): String? {
        return runCatching {
            String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8).trim()
        }.getOrNull()?.takeIf { it.startsWith("http") }
    }

    private companion object {
        const val JKANIME_URL = "https://jkanime.net"

        val ATOB_URL_REGEX = Regex(
            """url\s*:\s*atob\(\s*['\"]([^'\"]+)['\"]\s*\)""",
            RegexOption.IGNORE_CASE,
        )
        val RAW_HLS_REGEX = Regex(
            """(?:url\s*:\s*|loadSource\(\s*)['\"](https?://[^'\"]+\.m3u8[^'\"]*)['\"]""",
            RegexOption.IGNORE_CASE,
        )
    }
}
