package com.streamflixreborn.streamflix.extractors

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url

class OkruExtractor : Extractor() {

    override val name = "Okru"
    override val mainUrl = "https://ok.ru"

    private val service = Service.build(mainUrl)

    override suspend fun extract(link: String): Video {
        val document = service.get(link)
        val videoString = document.selectFirst("[data-module=OKVideo][data-options]")
            ?.attr("data-options")
            ?: document.selectFirst("[data-options]")?.attr("data-options")

        if (videoString.isNullOrBlank()) {
            if (document.text().contains("copyright", ignoreCase = true) ||
                document.text().contains("авторск", ignoreCase = true)
            ) {
                throw Exception("El vídeo de Ok.ru está bloqueado por derechos de autor")
            }
            throw Exception("Ok.ru no proporcionó metadatos de vídeo")
        }

        val videos = parseVideos(videoString)
        if (videos.isEmpty()) {
            throw Exception("Ok.ru no proporcionó vídeos válidos en sus metadatos")
        }

        val bestVideoUrl = videos.maxBy { qualityRank(it.first) }.second
        return Video(
            source = bestVideoUrl,
            headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to USER_AGENT
            )
        )
    }

    private fun parseVideos(rawOptions: String): List<Pair<String, String>> {
        val root = runCatching { JSONObject(rawOptions) }.getOrNull() ?: return emptyList()
        val roots = buildList {
            add(root)
            val metadata = root.optJSONObject("flashvars")?.opt("metadata")
                ?: root.opt("metadata")
            if (metadata is String) {
                runCatching { JSONObject(metadata) }.getOrNull()?.let(::add)
            }
        }

        return roots
            .flatMap { findVideoArrays(it) }
            .flatMap { array ->
                (0 until array.length()).mapNotNull { index ->
                    val video = array.optJSONObject(index) ?: return@mapNotNull null
                    val url = video.optString("url")
                        .replace("\\u0026", "&")
                        .replace("\\/", "/")
                        .let { if (it.startsWith("//")) "https:$it" else it }
                    if (!url.startsWith("http", ignoreCase = true)) return@mapNotNull null
                    fixQuality(video.optString("name")) to url
                }
            }
            .distinctBy { it.second }
    }

    private fun findVideoArrays(value: Any): List<JSONArray> {
        val result = mutableListOf<JSONArray>()
        when (value) {
            is JSONObject -> value.keys().forEach { key ->
                val child = value.opt(key)
                if (key.equals("videos", ignoreCase = true) && child is JSONArray) result += child
                if (child is JSONObject || child is JSONArray) result += findVideoArrays(child)
            }
            is JSONArray -> for (index in 0 until value.length()) {
                val child = value.opt(index)
                if (child is JSONObject || child is JSONArray) result += findVideoArrays(child)
            }
        }
        return result
    }

    private fun fixQuality(quality: String): String {
        return when (quality) {
            "ultra" -> "2160p"
            "quad" -> "1440p"
            "full" -> "1080p"
            "hd" -> "720p"
            "sd" -> "480p"
            "low" -> "360p"
            "lowest" -> "240p"
            "mobile" -> "144p"
            else -> quality
        }
    }

    private fun qualityRank(quality: String): Int {
        return quality.removeSuffix("p").toIntOrNull() ?: 0
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service {
                val client = OkHttpClient.Builder()
                    .followRedirects(true)
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("User-Agent", USER_AGENT)
                                .build()
                        )
                    }
                    .build()

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                    .create(Service::class.java)
            }
        }

        @GET
        suspend fun get(@Url url: String): Document
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36"
    }
}
