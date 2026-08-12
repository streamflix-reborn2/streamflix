package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import android.util.Log
import androidx.media3.common.MimeTypes
import com.google.gson.annotations.SerializedName
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.Request
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url
import java.io.IOException
import java.util.concurrent.TimeUnit

class VixSrcExtractor : Extractor() {

    override val name = "VixSrc"
    override val mainUrl = "https://vixsrc.to"

    fun server(videoType: Video.Type): Video.Server {
        return Video.Server(
            id = name,
            name = name,
            src = when (videoType) {
                is Video.Type.Episode -> "$mainUrl/api/tv/${videoType.tvShow.id}/${videoType.season.number}/${videoType.number}"
                is Video.Type.Movie -> "$mainUrl/api/movie/${videoType.id}"
            },
        )
    }

    override suspend fun extract(link: String): Video {
        val service = VixSrcExtractorService.build(mainUrl)
        val providerLang = UserPreferences.currentProvider?.language ?: "en"

        var apiPath = link.substringAfter(mainUrl).trimStart('/')
        if (!apiPath.startsWith("api/")) {
            apiPath = "api/$apiPath"
        }
        if (!apiPath.contains("lang=")) {
            val separator = if (apiPath.contains("?")) "&" else "?"
            apiPath += "${separator}lang=$providerLang"
        }

        Log.i("VixSrcDebug", "Calling API: $mainUrl/$apiPath")
        val apiResponse = service.getSourceApi(apiPath)
        var currentEmbedPath = apiResponse.src.trimStart('/')
            .takeIf { it.isNotBlank() }
            ?: throw IOException("VixSrc API returned an empty embed path")

        Log.i("VixSrcDebug", "Embed path from API: $currentEmbedPath")
        val source = try {
            service.getSource(currentEmbedPath)
        } catch (e: Exception) {
            val isGone = (e as? retrofit2.HttpException)?.code() == 410 ||
                e.message?.contains("410") == true
            if (!isGone) throw e

            Log.w("VixSrcDebug", "410 Gone detected; refreshing embed path")
            val retryApiResponse = service.getSourceApi(apiPath)
            currentEmbedPath = retryApiResponse.src.trimStart('/')
                .takeIf { it.isNotBlank() }
                ?: throw IOException("VixSrc retry returned an empty embed path")
            service.getSource(currentEmbedPath)
        }

        val scriptText = source.body()
            .select("script")
            .map { it.data() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
            ?: throw VixSrcMetadataException("embed page contains no inline player script")

        val playlistMetadata = parseVixSrcPlaylistMetadata(scriptText, providerLang)
        val finalUrl = playlistMetadata.playlistUrl
        val finalHeaders = linkedMapOf(
            "Referer" to "$mainUrl/$currentEmbedPath",
            "User-Agent" to VIXSRC_USER_AGENT,
        )

        val client = NetworkClient.default.newBuilder()
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(finalUrl)
            .apply { finalHeaders.forEach { (key, value) -> header(key, value) } }
            .build()

        val videoSource = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(
                        "VixSrc playlist validation failed with HTTP ${response.code}",
                    )
                }

                val playlistContent = response.body?.string()
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IOException("VixSrc playlist response was empty")

                if (!playlistContent.contains("#EXTM3U")) {
                    throw IOException("VixSrc playlist response is not HLS")
                }

                val langCode = providerLang
                val baseUri = response.request.url
                val finalLines = mutableListOf<String>()
                val uriRegex = """URI=["']([^"']+)["']""".toRegex()

                for (line in playlistContent.lines()) {
                    var patchedLine = line

                    if (line.startsWith("#")) {
                        patchedLine = uriRegex.replace(line) { matchResult ->
                            val relative = matchResult.groupValues[1]
                            if (relative.startsWith("http") || relative.startsWith("data:")) {
                                matchResult.value
                            } else {
                                "URI=\"${baseUri.resolve(relative) ?: relative}\""
                            }
                        }
                    } else if (line.isNotBlank()) {
                        patchedLine = baseUri.resolve(line)?.toString() ?: line
                    }

                    if (patchedLine.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")) {
                        patchedLine = patchedLine
                            .replace(Regex("DEFAULT=YES", RegexOption.IGNORE_CASE), "DEFAULT=NO")
                            .replace(Regex("AUTOSELECT=YES", RegexOption.IGNORE_CASE), "AUTOSELECT=NO")

                        val isTargetAudio =
                            patchedLine.contains("LANGUAGE=\"$langCode\"", ignoreCase = true) ||
                                patchedLine.contains("NAME=\"$langCode\"", ignoreCase = true) ||
                                (langCode == "it" && (
                                    patchedLine.contains("Italian", true) ||
                                        patchedLine.contains("ita", true)
                                    )) ||
                                (langCode == "es" && (
                                    patchedLine.contains("Spanish", true) ||
                                        patchedLine.contains("Español", true) ||
                                        patchedLine.contains("Castellano", true) ||
                                        patchedLine.contains("spa", true)
                                    )) ||
                                (langCode == "en" && (
                                    patchedLine.contains("English", true) ||
                                        patchedLine.contains("eng", true)
                                    ))

                        if (isTargetAudio) {
                            patchedLine = patchedLine
                                .replace("DEFAULT=NO", "DEFAULT=YES")
                                .replace("AUTOSELECT=NO", "AUTOSELECT=YES")
                        }
                    } else if (patchedLine.startsWith("#EXT-X-MEDIA:TYPE=SUBTITLES")) {
                        val trackName = patchedLine
                            .substringAfter("NAME=\"", "Unknown")
                            .substringBefore("\"")
                        val trackLang = patchedLine
                            .substringAfter("LANGUAGE=\"", "")
                            .substringBefore("\"")

                        patchedLine = patchedLine
                            .replace(Regex("DEFAULT=YES", RegexOption.IGNORE_CASE), "DEFAULT=NO")
                            .replace(Regex("AUTOSELECT=YES", RegexOption.IGNORE_CASE), "AUTOSELECT=NO")

                        val isForced = trackName.contains("forced", ignoreCase = true) ||
                            trackLang.contains("forced", ignoreCase = true) ||
                            patchedLine.contains("FORCED=YES", ignoreCase = true)
                        val isRightLanguage =
                            trackLang.contains(langCode, ignoreCase = true) ||
                                trackName.contains(langCode, ignoreCase = true) ||
                                (langCode == "es" && (
                                    trackName.contains("Spanish", true) ||
                                        trackName.contains("Español", true) ||
                                        trackName.contains("Castellano", true) ||
                                        trackLang.contains("spa", true)
                                    )) ||
                                (langCode == "it" && (
                                    trackName.contains("Italian", true) ||
                                        trackLang.contains("ita", true)
                                    )) ||
                                (langCode == "en" && (
                                    trackName.contains("English", true) ||
                                        trackLang.contains("eng", true)
                                    ))

                        if (isForced && isRightLanguage) {
                            patchedLine = patchedLine
                                .replace("DEFAULT=NO", "DEFAULT=YES")
                                .replace("AUTOSELECT=NO", "AUTOSELECT=YES")
                        }
                    }

                    finalLines.add(patchedLine)
                }

                val patchedManifest = finalLines.joinToString("\n")
                if (!patchedManifest.contains("#EXTM3U")) {
                    throw IOException("VixSrc patched manifest is invalid")
                }

                val base64Manifest = Base64.encodeToString(
                    patchedManifest.toByteArray(),
                    Base64.NO_WRAP,
                )
                "data:application/vnd.apple.mpegurl;base64,$base64Manifest"
            }
        } catch (e: Exception) {
            Log.e("VixSrcDebug", "Playlist validation/patching failed", e)
            throw e
        }

        return Video(
            source = videoSource,
            subtitles = emptyList(),
            type = MimeTypes.APPLICATION_M3U8,
            headers = finalHeaders,
        )
    }

    companion object {
        private const val VIXSRC_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private interface VixSrcExtractorService {
        companion object {
            fun build(baseUrl: String): VixSrcExtractorService {
                val client = NetworkClient.default.newBuilder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("Referer", baseUrl)
                            .build()
                        chain.proceed(request)
                    }
                    .build()
                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(VixSrcExtractorService::class.java)
            }
        }

        @GET
        @Headers(
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept: application/json, text/plain, */*",
            "X-Requested-With: XMLHttpRequest",
        )
        suspend fun getSourceApi(@Url url: String): VixSrcApiResponse

        @GET
        @Headers(
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language: it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "X-Requested-With: XMLHttpRequest",
        )
        suspend fun getSource(@Url url: String): Document

        data class VixSrcApiResponse(val src: String)

        data class WindowVideo(
            @SerializedName("id") val id: Int,
            @SerializedName("filename") val filename: String,
        )

        data class WindowParams(
            @SerializedName("token") val token: String?,
            @SerializedName("expires") val expires: String?,
        )
    }
}
