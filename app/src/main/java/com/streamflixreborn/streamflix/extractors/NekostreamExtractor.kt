package com.streamflixreborn.streamflix.extractors

import android.net.Uri
import androidx.media3.common.MimeTypes
import com.google.gson.Gson
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class NekostreamExtractor : Extractor() {

    override val name = "Nekostream"
    override val mainUrl = "https://vidtube.site"
    override val aliasUrls = listOf(
        "https://megaplay.buzz",
        "https://vidwish.live",
    )

    private val client = OkHttpClient.Builder()
        .dns(DnsResolver.doh)
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun extract(link: String): Video {
        val streamPageUrl = link.substringBefore("?") + link.substringAfter("?", "?autostart=true").let {
            if (link.contains("?")) "?${link.substringAfter("?")}" else it
        }
        val pageUri = Uri.parse(streamPageUrl)
        val origin = "${pageUri.scheme}://${pageUri.host}"
        val pageBody = getText(
            url = streamPageUrl,
            referer = "https://anikototv.to/",
            origin = origin,
            accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )

        val fileId = Regex("""id=["']megaplay-player["'][^>]*data-id=["']([^"']+)""")
            .find(pageBody)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""data-id=["']([^"']+)["'][^>]*id=["']megaplay-player["']""")
                .find(pageBody)
                ?.groupValues
                ?.getOrNull(1)
            ?: throw Exception("Nekostream player file id not found")

        val streamType = Regex("""type:\s*['"]([^'"]+)""")
            .find(pageBody)
            ?.groupValues
            ?.getOrNull(1)

        fun sourcesUrl(endpoint: String) = buildString {
            append("$origin/stream/$endpoint?id=$fileId")
            streamType?.let { append("&type=${Uri.encode(it)}") }
            pageUri.getQueryParameter("s")?.let { append("&s=${Uri.encode(it)}") }
        }

        // MegaPlay's current player uses getSourcesNew, while older records
        // may still expose only the legacy response format.
        val sources = listOf("getSourcesNew", "getSources").asSequence()
            .mapNotNull { endpoint ->
                runCatching {
                    Gson().fromJson(
                        getText(
                            url = sourcesUrl(endpoint),
                            referer = streamPageUrl,
                            origin = origin,
                            accept = "application/json, text/javascript, */*; q=0.01",
                            requestedWith = true,
                        ),
                        SourcesResponse::class.java,
                    )
                }.getOrNull()
            }
            .firstOrNull { !it.sources?.file.isNullOrBlank() }
            ?: throw Exception("Nekostream source not found")
        val source = sources.sources?.file ?: throw Exception("Nekostream source not found")

        return Video(
            source = source,
            subtitles = sources.tracks.orEmpty()
                .filter { it.kind == null || it.kind == "captions" }
                .mapNotNull {
                    Video.Subtitle(
                        label = it.label?.ifBlank { null } ?: "Subtitle",
                        file = it.file ?: return@mapNotNull null,
                        default = it.default == true,
                    )
                },
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$origin/",
                "Origin" to origin,
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.9",
                "Connection" to "keep-alive",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "Sec-GPC" to "1",
            ),
            type = MimeTypes.APPLICATION_M3U8,
        )
    }

    private fun getText(
        url: String,
        referer: String,
        origin: String,
        accept: String,
        requestedWith: Boolean = false,
    ): String {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", referer)
            .header("Origin", origin)
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")

        if (requestedWith) {
            requestBuilder.header("X-Requested-With", "XMLHttpRequest")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Nekostream request failed ${response.code}: $url")
            }
            return response.body?.string().orEmpty()
        }
    }

    private data class SourcesResponse(
        val sources: Sources? = null,
        val tracks: List<Track>? = null,
    ) {
        data class Sources(
            val file: String? = null,
        )

        data class Track(
            val file: String? = null,
            val label: String? = null,
            val kind: String? = null,
            val default: Boolean? = null,
        )
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}