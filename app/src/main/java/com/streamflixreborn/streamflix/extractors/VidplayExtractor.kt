package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.StringConverterFactory
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Url
import java.lang.reflect.Type
import java.net.URLDecoder
import kotlin.experimental.xor

open class VidplayExtractor : Extractor() {

    override val name = "Vidplay"
    override val mainUrl = "https://vidplay.site"
    open val key = "https://raw.githubusercontent.com/Ciarands/vidsrc-keys/main/keys.json"

    override suspend fun extract(link: String): Video {
        val service = Service.build(mainUrl)

        val id = link.substringBefore("?").substringAfterLast("/")
        val keys = service.getKeys(key)

        require(keys.encrypt.size > 2 && keys.decrypt.size > 1) {
            "Vidplay key payload is incomplete"
        }

        val encId = encode(keys.encrypt[1], id)
        val h = encode(keys.encrypt[2], id)
        val mediaUrl = "${mainUrl}/mediainfo/${encId}?${link.substringAfter("?")}&autostart=true&ads=0&h=$h"
        val response = service.getSources(
            mediaUrl,
            referer = link,
        )

        val result = when (response) {
            is Sources -> response.result
            is Sources.Encrypted -> response.decrypt(keys.decrypt[1]).result
        }

        val source = result.sources
            ?.firstOrNull()
            ?.file
            ?.takeIf { it.isNotBlank() }
            ?: throw Exception("Can't retrieve source")

        val origin = link.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
        val playbackHeaders = buildMap {
            put("Referer", link)
            put("User-Agent", NetworkClient.USER_AGENT)
            origin?.let { put("Origin", it) }
        }

        return Video(
            source = source,
            subtitles = result.tracks
                ?.filter { it.kind == "captions" }
                ?.mapNotNull {
                    Video.Subtitle(
                        it.label ?: "Unknown",
                        it.file ?: return@mapNotNull null,
                    )
                }
                ?: emptyList(),
            headers = playbackHeaders,
        )
    }

    companion object {
        private fun encode(key: String, vId: String): String {
            val decodedId = decodeData(key, vId)
            return Base64.encode(decodedId, Base64.NO_WRAP)
                .toString(Charsets.UTF_8)
                .replace("/", "_")
                .replace("+", "-")
        }

        private fun decodeData(key: String, data: String): ByteArray {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            val s = ByteArray(256) { it.toByte() }
            var j = 0

            for (i in 0 until 256) {
                j = (j + s[i].toInt() + keyBytes[i % keyBytes.size].toInt()) and 0xff
                s[i] = s[j].also { s[j] = s[i] }
            }

            val decoded = ByteArray(data.length)
            var i = 0
            var k = 0

            for (index in decoded.indices) {
                i = (i + 1) and 0xff
                k = (k + s[i].toInt()) and 0xff
                s[i] = s[k].also { s[k] = s[i] }
                val t = (s[i].toInt() + s[k].toInt()) and 0xff
                decoded[index] = (data[index].code xor s[t].toInt()).toByte()
            }

            return decoded
        }
    }

    class Any(hostUrl: String) : VidplayExtractor() {
        override val mainUrl = hostUrl
    }

    class MyCloud : VidplayExtractor() {
        override val name = "MyCloud"
        override val mainUrl = "https://mcloud.bz"
    }

    class VidplayOnline : VidplayExtractor() {
        override val mainUrl = "https://vidplay.online"
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service {
                val client = NetworkClient.default.newBuilder().build()
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(StringConverterFactory.create())
                    .addConverterFactory(
                        GsonConverterFactory.create(
                            GsonBuilder()
                                .registerTypeAdapter(
                                    SourcesResponse::class.java,
                                    SourcesResponse.Deserializer(),
                                )
                                .create(),
                        ),
                    )
                    .build()

                return retrofit.create(Service::class.java)
            }
        }

        @GET
        @Headers(
            "Accept: application/json, text/javascript, */*; q=0.01",
            "X-Requested-With: XMLHttpRequest",
        )
        suspend fun getSources(
            @Url url: String,
            @Header("referer") referer: String,
        ): SourcesResponse

        @GET
        suspend fun getKeys(@Url url: String): Keys
    }

    sealed class SourcesResponse {
        class Deserializer : JsonDeserializer<SourcesResponse> {
            override fun deserialize(
                json: JsonElement?,
                typeOfT: Type?,
                context: JsonDeserializationContext?,
            ): SourcesResponse {
                val jsonObject = json?.asJsonObject ?: JsonObject()
                return when (jsonObject.get("result")?.isJsonObject ?: false) {
                    true -> Gson().fromJson(json, Sources::class.java)
                    false -> Gson().fromJson(json, Sources.Encrypted::class.java)
                }
            }
        }
    }

    data class Sources(
        val status: Int? = null,
        val result: Result,
    ) : SourcesResponse() {

        data class Encrypted(
            val status: Int? = null,
            val result: String,
        ) : SourcesResponse() {
            fun decrypt(key: String): Sources {
                fun decodeBase64UrlSafe(url: String): ByteArray {
                    val standardizedInput = url
                        .replace('_', '/')
                        .replace('-', '+')
                    return Base64.decode(standardizedInput, Base64.NO_WRAP)
                }

                fun decodeData(key: String, data: ByteArray): ByteArray {
                    val keyBytes = key.toByteArray(Charsets.UTF_8)
                    val s = ByteArray(256) { it.toByte() }
                    var j = 0

                    for (i in 0 until 256) {
                        j = (j + s[i].toInt() + keyBytes[i % keyBytes.size].toInt()) and 0xff
                        s[i] = s[j].also { s[j] = s[i] }
                    }

                    val decoded = ByteArray(data.size)
                    var i = 0
                    var k = 0

                    for (index in decoded.indices) {
                        i = (i + 1) and 0xff
                        k = (k + s[i].toInt()) and 0xff
                        s[i] = s[k].also { s[k] = s[i] }
                        val t = (s[i].toInt() + s[k].toInt()) and 0xff
                        decoded[index] = data[index] xor s[t]
                    }

                    return decoded
                }

                val encoded = decodeBase64UrlSafe(result)
                val decoded = decodeData(key, encoded)
                val resultJson = URLDecoder.decode(decoded.toString(Charsets.UTF_8), "utf-8")
                return Sources(
                    status = status,
                    result = Gson().fromJson(resultJson, Result::class.java),
                )
            }
        }

        data class Result(
            val sources: List<Sources>? = emptyList(),
            val tracks: List<Tracks>? = emptyList(),
        ) {
            data class Tracks(
                val file: String? = null,
                val label: String? = null,
                val kind: String? = null,
            )

            data class Sources(
                val file: String? = null,
            )
        }
    }

    data class Keys(
        val encrypt: List<String>,
        val decrypt: List<String>,
    )
}
