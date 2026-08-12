package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Url
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class VixcloudExtractor(
    private val preferredLanguage: String? = null,
    private var customReferer: String? = null,
) : Extractor() {

    override val name = "vixcloud"
    override val mainUrl = "https://vixcloud.co/"

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

        private val client = NetworkClient.default.newBuilder()
            .readTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()

        private val retrofitCache = ConcurrentHashMap<String, VixcloudExtractorService>()

        private fun getService(baseUrl: String): VixcloudExtractorService {
            retrofitCache[baseUrl]?.let { return it }
            val created = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(JsoupConverterFactory.create())
                .client(client)
                .build()
                .create(VixcloudExtractorService::class.java)
            return retrofitCache.putIfAbsent(baseUrl, created) ?: created
        }
    }

    private class VixcloudHttpException(
        val statusCode: Int,
        stage: String,
    ) : IOException("Vixcloud $stage failed with HTTP $statusCode")

    override suspend fun extract(link: String): Video {
        var currentLink = link
        var lastError: Exception? = null

        repeat(2) { attempt ->
            try {
                return extractOnce(currentLink)
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0 && isRecoverableSourceError(e)) {
                    Log.w(
                        "VixcloudDebug",
                        "Stale/protected Vixcloud source detected (${e.message}); refreshing StreamingCommunity iframe",
                    )
                    val refreshed = runCatching { refreshLinkFromReferer() }.getOrNull()
                    if (!refreshed.isNullOrBlank()) {
                        currentLink = refreshed
                        return@repeat
                    }
                }
                throw e
            }
        }

        throw lastError ?: IOException("Vixcloud extraction failed")
    }

    private suspend fun extractOnce(link: String): Video {
        Log.d("VixcloudDebug", "Extracting language=$preferredLanguage")

        val uri = link.toHttpUrlOrNull() ?: throw IOException("Invalid Vixcloud link")
        val currentMainUrl = "${uri.scheme}://${uri.host}/"
        val sourceReferer = customReferer ?: currentMainUrl
        val service = getService(currentMainUrl)
        val requestPath = buildRequestPath(uri)

        val source = try {
            service.getSource(requestPath, referer = sourceReferer)
        } catch (e: HttpException) {
            throw VixcloudHttpException(e.code(), "embed")
        }

        val scriptText = source.body()
            .select("script")
            .asSequence()
            .map { it.data() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
            ?: throw VixcloudMetadataException("embed page contains no inline scripts")

        val metadata = parseVixcloudPlaylistMetadata(scriptText)
        val finalUrl = buildPlaylistUrl(uri, metadata)
        val finalHeaders = buildPlaybackHeaders(
            playlistUrl = finalUrl,
            sourcePageUrl = link,
            currentMainUrl = currentMainUrl,
        )

        val videoSource = if (preferredLanguage.isNullOrBlank()) {
            finalUrl
        } else {
            fetchAndPatchManifest(finalUrl, finalHeaders, preferredLanguage)
        }

        return Video(
            source = videoSource,
            subtitles = emptyList(),
            type = MimeTypes.APPLICATION_M3U8,
            headers = finalHeaders,
        )
    }

    private fun buildRequestPath(uri: HttpUrl): String = buildString {
        append(uri.encodedPath)
        uri.encodedQuery?.let {
            append('?')
            append(it)
        }
    }

    private fun buildPlaylistUrl(
        sourceUrl: HttpUrl,
        metadata: VixcloudPlaylistMetadata,
    ): String {
        val builder = "${sourceUrl.scheme}://${sourceUrl.host}/playlist/${metadata.videoId}"
            .toHttpUrlOrNull()
            ?.newBuilder()
            ?: throw IOException("Invalid Vixcloud playlist URL")

        builder.addQueryParameter("token", metadata.token)
        builder.addQueryParameter("expires", metadata.expires)
        if (metadata.hasBParam) builder.addQueryParameter("b", "1")
        if (sourceUrl.queryParameterNames.contains("canPlayFHD")) {
            builder.addQueryParameter("h", "1")
        }
        preferredLanguage?.takeIf { it.isNotBlank() }?.let {
            builder.addQueryParameter("language", it)
        }
        return builder.build().toString()
    }

    private fun buildPlaybackHeaders(
        playlistUrl: String,
        sourcePageUrl: String,
        currentMainUrl: String,
    ): Map<String, String> {
        val playlistHttpUrl = playlistUrl.toHttpUrlOrNull()
        return linkedMapOf<String, String>().apply {
            put("Referer", sourcePageUrl)
            put("Origin", currentMainUrl.trimEnd('/'))
            put("User-Agent", USER_AGENT)

            preferredLanguage?.takeIf { it.isNotBlank() }?.let { lang ->
                put("Accept-Language", languageHeader(lang))
            }

            if (playlistHttpUrl != null) {
                val cookies = NetworkClient.cookieJar.loadForRequest(playlistHttpUrl)
                    .associate { it.name to it.value }
                    .toMutableMap()
                preferredLanguage?.takeIf { it.isNotBlank() }?.let { lang ->
                    cookies["language"] = lang
                }
                if (cookies.isNotEmpty()) {
                    put("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }
            }
        }
    }

    private fun fetchAndPatchManifest(
        playlistUrl: String,
        headers: Map<String, String>,
        language: String,
    ): String {
        val request = Request.Builder()
            .url(playlistUrl)
            .apply { headers.forEach { (key, value) -> header(key, value) } }
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw VixcloudHttpException(response.code, "playlist")
            }

            val playlistContent = response.body?.string()
                ?.takeIf { it.isNotBlank() }
                ?: throw IOException("Vixcloud playlist response was empty")
            if (!playlistContent.contains("#EXTM3U")) {
                throw IOException("Vixcloud playlist response is not HLS")
            }

            val baseUri = response.request.url
            val patched = patchManifest(
                playlistContent = playlistContent,
                baseUri = baseUri,
                language = language,
            )
            val base64Manifest = Base64.encodeToString(
                patched.toByteArray(),
                Base64.NO_WRAP,
            )
            "data:application/vnd.apple.mpegurl;base64,$base64Manifest"
        }
    }

    private fun patchManifest(
        playlistContent: String,
        baseUri: HttpUrl,
        language: String,
    ): String {
        val altLanguage = when (language.lowercase()) {
            "en" -> "eng"
            "it" -> "ita"
            else -> language.lowercase()
        }
        val uriRegex = """URI=["']([^"']+)["']""".toRegex()

        return playlistContent.lineSequence().joinToString("\n") { line ->
            var patched = when {
                line.startsWith("#") -> uriRegex.replace(line) { match ->
                    val relative = match.groupValues[1]
                    if (
                        relative.startsWith("http", ignoreCase = true) ||
                        relative.startsWith("data:", ignoreCase = true)
                    ) {
                        match.value
                    } else {
                        "URI=\"${baseUri.resolve(relative) ?: relative}\""
                    }
                }
                line.isNotBlank() -> baseUri.resolve(line)?.toString() ?: line
                else -> line
            }

            if (patched.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")) {
                patched = patched
                    .replace(Regex("DEFAULT=YES", RegexOption.IGNORE_CASE), "DEFAULT=NO")
                    .replace(Regex("AUTOSELECT=YES", RegexOption.IGNORE_CASE), "AUTOSELECT=NO")

                if (matchesTrackLanguage(patched, language, altLanguage)) {
                    patched = patched
                        .replace("DEFAULT=NO", "DEFAULT=YES")
                        .replace("AUTOSELECT=NO", "AUTOSELECT=YES")
                }
            } else if (patched.startsWith("#EXT-X-MEDIA:TYPE=SUBTITLES")) {
                val isForced = patched.contains("forced", ignoreCase = true) ||
                    patched.contains("FORCED=YES", ignoreCase = true)
                patched = patched
                    .replace(Regex("DEFAULT=YES", RegexOption.IGNORE_CASE), "DEFAULT=NO")
                    .replace(Regex("AUTOSELECT=YES", RegexOption.IGNORE_CASE), "AUTOSELECT=NO")

                if (isForced && matchesTrackLanguage(patched, language, altLanguage)) {
                    patched = patched
                        .replace("DEFAULT=NO", "DEFAULT=YES")
                        .replace("AUTOSELECT=NO", "AUTOSELECT=YES")
                }
            }
            patched
        }
    }

    private fun matchesTrackLanguage(
        line: String,
        language: String,
        altLanguage: String,
    ): Boolean {
        val normalized = language.lowercase()
        return line.contains("LANGUAGE=\"$normalized\"", ignoreCase = true) ||
            line.contains("NAME=\"$normalized\"", ignoreCase = true) ||
            line.contains(altLanguage, ignoreCase = true) ||
            (normalized == "it" && line.contains("Italian", ignoreCase = true)) ||
            (normalized == "en" && line.contains("English", ignoreCase = true))
    }

    private fun isRecoverableSourceError(error: Exception): Boolean {
        if (error is VixcloudMetadataException) return true

        val code = when (error) {
            is VixcloudHttpException -> error.statusCode
            is HttpException -> error.code()
            else -> null
        }
        if (code in setOf(401, 403, 404, 408, 410, 425, 429, 500, 502, 503, 504)) {
            return true
        }

        // Protection/challenge pages are sometimes returned as HTTP 200. If the manifest is empty
        // or not HLS, refresh the authoritative StreamingCommunity iframe once before giving up.
        if (error is IOException) {
            val message = error.message.orEmpty()
            return message.contains("playlist response", ignoreCase = true) ||
                message.contains("embed page contains no inline scripts", ignoreCase = true)
        }
        return false
    }

    private fun refreshLinkFromReferer(): String? {
        val referer = customReferer?.takeIf { it.isNotBlank() } ?: return null
        val refererUrl = referer.toHttpUrlOrNull() ?: return null
        val siteRoot = "${refererUrl.scheme}://${refererUrl.host}/"
        val request = Request.Builder()
            .url(referer)
            .header("Referer", siteRoot)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", languageHeader(preferredLanguage))
            .apply {
                preferredLanguage?.takeIf { it.isNotBlank() }?.let {
                    header("Cookie", "language=$it")
                }
            }
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw VixcloudHttpException(response.code, "StreamingCommunity iframe refresh")
            }
            val html = response.body?.string().orEmpty()
            val src = Jsoup.parse(html, response.request.url.toString())
                .selectFirst("iframe[src]")
                ?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?: return@use null

            when {
                src.startsWith("//") -> "${response.request.url.scheme}:$src"
                src.startsWith("http", ignoreCase = true) -> src
                else -> response.request.url.resolve(src)?.toString() ?: src
            }
        }
    }

    private fun languageHeader(language: String?): String = when (language?.lowercase()) {
        "en" -> "en-US,en;q=0.9"
        "it" -> "it-IT,it;q=0.9,en-US;q=0.7,en;q=0.6"
        else -> "en-US,en;q=0.9"
    }

    private interface VixcloudExtractorService {
        @GET
        @Headers(
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
            "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        )
        suspend fun getSource(
            @Url url: String,
            @Header("Referer") referer: String,
        ): Document
    }
}
