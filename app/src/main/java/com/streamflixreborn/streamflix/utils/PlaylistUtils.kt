package com.streamflixreborn.streamflix.utils

import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class PlaylistUtils(private val client: OkHttpClient) {

    fun extractFromHls(
        playlistUrl: String,
        referer: String? = null,
        requestHeaders: Map<String, String>? = null,
    ): List<Video> {
        val headers = buildMap {
            requestHeaders
                ?.filterValues { it.isNotBlank() }
                ?.forEach { (key, value) -> put(key, value) }
            referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        }.ifEmpty { null }

        val masterPlaylist = try {
            val request = Request.Builder().url(playlistUrl)
            headers?.forEach { (key, value) -> request.header(key, value) }
            client.newCall(request.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use null
                }
                response.body?.string()
            }
        } catch (_: Exception) {
            null
        }

        if (masterPlaylist.isNullOrBlank()) {
            return listOf(playableHlsVideo(playlistUrl, headers))
        }

        if (!masterPlaylist.contains("#EXT-X-STREAM-INF")) {
            return listOf(playableHlsVideo(playlistUrl, headers))
        }

        val masterUrl = playlistUrl.toHttpUrl()
        val subtitles = SUBTITLE_REGEX.findAll(masterPlaylist).mapNotNull { match ->
            resolveHlsUrl(masterUrl, match.groupValues[2])?.let { url ->
                Video.Subtitle(
                    label = match.groupValues[1],
                    file = url,
                )
            }
        }.toList()

        return masterPlaylist
            .substringAfter("#EXT-X-STREAM-INF:")
            .split("#EXT-X-STREAM-INF:")
            .mapNotNull { variant ->
                val uriLine = variant
                    .lineSequence()
                    .drop(1)
                    .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    ?.trim()
                    ?: return@mapNotNull null
                val url = resolveHlsUrl(masterUrl, uriLine) ?: return@mapNotNull null
                val height = RESOLUTION_REGEX.find(variant)?.groupValues?.get(1)?.toIntOrNull()
                val bandwidth = BANDWIDTH_REGEX.find(variant)?.groupValues?.get(1)?.toLongOrNull()

                HlsVariant(
                    video = Video(
                        source = url,
                        subtitles = subtitles,
                        type = MimeTypes.APPLICATION_M3U8,
                        headers = headers,
                    ),
                    height = height,
                    bandwidth = bandwidth,
                )
            }
            .sortedWith(
                compareByDescending<HlsVariant> { it.height ?: -1 }
                    .thenByDescending { it.bandwidth ?: -1L },
            )
            .map { it.video }
    }

    private fun playableHlsVideo(
        source: String,
        headers: Map<String, String>?,
    ) = Video(
        source = source,
        subtitles = emptyList(),
        type = MimeTypes.APPLICATION_M3U8,
        headers = headers,
    )

    private fun resolveHlsUrl(masterUrl: HttpUrl, value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("//")) {
            return "${masterUrl.scheme}:$trimmed"
        }
        return masterUrl.resolve(trimmed)?.toString()
    }

    private data class HlsVariant(
        val video: Video,
        val height: Int?,
        val bandwidth: Long?,
    )

    companion object {
        private val SUBTITLE_REGEX by lazy {
            Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES.*?NAME="(.*?)".*?URI="(.*?)"""")
        }
        private val RESOLUTION_REGEX by lazy { Regex("""RESOLUTION=\d{2,5}x(\d{2,5})""") }
        private val BANDWIDTH_REGEX by lazy { Regex("""BANDWIDTH=(\d+)""") }
    }
}
