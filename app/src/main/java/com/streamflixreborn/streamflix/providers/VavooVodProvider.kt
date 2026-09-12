package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

object VavooConfig {
    const val PREF_KEY = "Vavoo"
    const val DEFAULT_BASE_URL = "https://vavoo.to/"

    fun baseUrl(): String =
        UserPreferences.vavooDomain.trimEnd('/') + "/"
}

class VavooVodProvider private constructor(
    override val language: String,
    private val region: String,
    override val name: String,
) : Provider {

    companion object {
        val DE = VavooVodProvider("de", "DE", "Vavoo VOD")
        val IT = VavooVodProvider("it", "IT", "Vavoo VOD IT")
        val FR = VavooVodProvider("fr", "FR", "Vavoo VOD FR")
        val ES = VavooVodProvider("es", "ES", "Vavoo VOD ES")
        val PL = VavooVodProvider("pl", "PL", "Vavoo VOD PL")
        val EN = VavooVodProvider("en", "GB", "Vavoo VOD EN")
    }

    // VAVOO_VOD_MEDIAHUB_SOURCE_SCHEMA_V6
    private val TAG = "VavooVodProvider-$language"
    private val tmdb = TmdbProvider(language)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    override val baseUrl: String get() = VavooConfig.baseUrl()
    override val logo: String get() = "${baseUrl}assets/favicon-Djqjt9PL.ico"

    override suspend fun getHome(): List<Category> =
        tmdb.getHome().map { category ->
            category.copy(list = category.list.map(::markItem))
        }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> =
        tmdb.search(query, page).map(::markItem)

    override suspend fun getMovies(page: Int): List<Movie> =
        tmdb.getMovies(page).map(::markMovie)

    override suspend fun getTvShows(page: Int): List<TvShow> =
        tmdb.getTvShows(page).map(::markTvShow)

    override suspend fun getMovie(id: String): Movie =
        markMovie(tmdb.getMovie(id))

    override suspend fun getTvShow(id: String): TvShow =
        markTvShow(tmdb.getTvShow(id))

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> =
        tmdb.getEpisodesBySeason(seasonId)

    override suspend fun getGenre(id: String, page: Int): Genre {
        val genre = tmdb.getGenre(id, page)
        return genre.copy(
            shows = genre.shows.map { show ->
                when (show) {
                    is Movie -> markMovie(show)
                    is TvShow -> markTvShow(show)
                }
            }
        )
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val person = tmdb.getPeople(id, page)
        return person.copy(
            filmography = person.filmography.map { show ->
                when (show) {
                    is Movie -> markMovie(show)
                    is TvShow -> markTvShow(show)
                }
            }
        )
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> =
        withContext(Dispatchers.IO) {
            requestSources(videoType).mapIndexed { index, url ->
                Video.Server(
                    id = "vavoo-vod-$index",
                    name = "Vavoo • ${hostName(url)}",
                    src = url
                )
            }
        }

    override suspend fun getVideo(server: Video.Server): Video {
        server.video?.let { return it }
        return Extractor.extract(server.src)
    }

    /**
     * Vavoo's mediahubmx-source endpoint is a MediaHubMX "source" action.
     * It does NOT accept a Stremio-style "id" property.
     *
     * The official SourceRequest schema requires the item metadata:
     * language + region + type + ids + name.
     * Series requests additionally carry episode metadata.
     */
    private fun buildSourcePayload(videoType: Video.Type): JSONObject =
        JSONObject().apply {
            put("language", language)
            put("region", region)
            put("clientVersion", "3.0.2")

            when (videoType) {
                is Video.Type.Movie -> {
                    put("type", "movie")
                    put(
                        "ids",
                        JSONObject().apply {
                            put("tmdb_id", videoType.id)
                            videoType.imdbId
                                ?.takeIf { it.isNotBlank() }
                                ?.let { put("imdb_id", it) }
                        }
                    )
                    put("name", videoType.title)
                    if (videoType.releaseDate.isNotBlank()) {
                        put("releaseDate", videoType.releaseDate)
                    }
                }

                is Video.Type.Episode -> {
                    put("type", "series")
                    put(
                        "ids",
                        JSONObject().apply {
                            put("tmdb_id", videoType.tvShow.id)
                            videoType.tvShow.imdbId
                                ?.takeIf { it.isNotBlank() }
                                ?.let { put("imdb_id", it) }
                        }
                    )
                    put("name", videoType.tvShow.title)
                    videoType.tvShow.releaseDate
                        ?.takeIf { it.isNotBlank() }
                        ?.let { put("releaseDate", it) }

                    put(
                        "episode",
                        JSONObject().apply {
                            put(
                                "ids",
                                JSONObject().apply {
                                    // Android's TMDB episode model carries its own TMDB id here.
                                    if (videoType.id.isNotBlank()) {
                                        put("tmdb_id", videoType.id)
                                    }
                                }
                            )
                            put(
                                "name",
                                videoType.title?.takeIf { it.isNotBlank() }
                                    ?: "Episode ${videoType.number}"
                            )
                            put("season", videoType.season.number)
                            put("episode", videoType.number)
                        }
                    )
                }
            }
        }

    private fun requestSources(videoType: Video.Type): List<String> {
        val referer = if (videoType is Video.Type.Movie) {
            "${baseUrl}movies"
        } else {
            "${baseUrl}series"
        }

        val payload = buildSourcePayload(videoType)

        return runCatching {
            Log.d(TAG, "source request type=${payload.optString("type")} ids=${payload.optJSONObject("ids")}")

            val request = Request.Builder()
                .url("${baseUrl}mediahubmx-source.json")
                .post(
                    payload.toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                )
                .header("User-Agent", "MediaHubMX/2")
                .header("Accept", "application/json")
                .header("Accept-Language", language)
                .header("Origin", baseUrl.trimEnd('/'))
                .header("Referer", referer)
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                Log.d(TAG, "source HTTP ${response.code}, bytes=${raw.length}")

                if (!response.isSuccessful) {
                    Log.w(TAG, "Vavoo VOD source -> HTTP ${response.code}: ${raw.take(500)}")
                    emptyList()
                } else {
                    extractUrls(raw)
                }
            }
        }.getOrElse { error ->
            Log.w(TAG, "Vavoo VOD source failed", error)
            emptyList()
        }
    }

    private fun extractUrls(raw: String): List<String> {
        val urls = linkedSetOf<String>()
        val targetLanguage = language.lowercase().substringBefore('-')

        fun normalizeLanguage(value: String): String =
            value.trim()
                .lowercase()
                .substringBefore('-')
                .substringBefore('_')

        fun explicitLanguages(value: JSONObject): Set<String> {
            val result = linkedSetOf<String>()

            value.optJSONArray("languages")?.let { languages ->
                for (index in 0 until languages.length()) {
                    languages.optString(index)
                        .takeIf { it.isNotBlank() }
                        ?.let { result += normalizeLanguage(it) }
                }
            }

            listOf("language", "lang").forEach { key ->
                value.optString(key)
                    .takeIf { it.isNotBlank() }
                    ?.let { result += normalizeLanguage(it) }
            }

            return result
        }

        fun walk(
            value: Any?,
            inheritedLanguageAllowed: Boolean = true,
        ) {
            when (value) {
                is JSONObject -> {
                    val languages = explicitLanguages(value)

                    val languageAllowed =
                        if (languages.isNotEmpty()) {
                            targetLanguage in languages
                        } else {
                            inheritedLanguageAllowed
                        }

                    if (!languageAllowed) return

                    listOf("url", "src", "link", "stream", "file").forEach { key ->
                        value.optString(key)
                            .takeIf { it.startsWith("http", ignoreCase = true) }
                            ?.let(urls::add)
                    }

                    value.keys().forEach { key ->
                        walk(value.opt(key), languageAllowed)
                    }
                }

                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        walk(value.opt(index), inheritedLanguageAllowed)
                    }
                }

                is String -> {
                    if (
                        inheritedLanguageAllowed &&
                        value.startsWith("http", ignoreCase = true)
                    ) {
                        urls += value
                    }
                }
            }
        }

        runCatching { walk(JSONArray(raw)) }
            .recoverCatching { walk(JSONObject(raw)) }

        return urls.filterNot { url ->
            val lower = url.lowercase()
            lower.contains("image.tmdb.org") ||
                lower.contains("themoviedb.org")
        }
    }

    private fun hostName(url: String): String =
        runCatching {
            URI(url).host.orEmpty()
                .removePrefix("www.")
                .substringBefore('.')
                .replaceFirstChar { it.uppercase() }
        }.getOrDefault("Stream")

    private fun markItem(item: AppAdapter.Item): AppAdapter.Item = when (item) {
        is Movie -> markMovie(item)
        is TvShow -> markTvShow(item)
        else -> item
    }

    private fun markMovie(movie: Movie): Movie =
        movie.copy().apply { providerName = name }

    private fun markTvShow(show: TvShow): TvShow =
        show.copy().apply { providerName = name }
}
