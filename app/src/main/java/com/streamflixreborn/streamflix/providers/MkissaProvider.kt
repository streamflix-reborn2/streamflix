package com.streamflixreborn.streamflix.providers

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.ArtworkRequestHeaders
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.HttpException
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.apply
import kotlin.jvm.java
import kotlin.text.clear
import kotlin.text.format

object MkissaProvider : Provider {

    private const val TAG = "MkissaProvider"
    private const val API_URL = "https://api.allanime.day/api"
    private const val CLOCK_URL = "https://allanime.day"
    private const val SEARCH_HASH = "a24c500a1b765c68ae1d8dd85174931f661c71369c89b92b88b75a725afc471c"
    private const val POPULAR_DAILY_HASH = "a0aca6827cc9a3ad7bc711da4d200a04adea8f1a7545dc418d5e92e74c3aad15"
    private const val POPULAR_HASH = "ac2c75884a11fca5707ce4ad10f2e3e2aae31e42af5e4d9c511a4a5e708e4c6d"
    private val DETAIL_HASH: String by lazy { sha256Hex(DETAIL_QUERY) }
    // Keep the persisted-query hash coupled to the query body.
    private val SOURCE_HASH: String by lazy { sha256Hex(SOURCE_QUERY) }
    private const val GENRE_HASH = "ff61a63ff776f334f80c1e6ad1aa49ef71eab831e235e5d6ec679eae5b83450f"
    private const val IMAGE_URL = "https://aln.youtube-anime.com"
    private const val DEFAULT_BUILD_ID = "45"
    private const val CRYPTO_PREFS = "mkissa_crypto"
    private const val CRYPTO_BUILD_KEY = "build_id"
    private const val CRYPTO_MASK_KEY = "mask"
    private const val CRYPTO_KEY_KEY = "key"
    private const val CRYPTO_API_URL_KEY = "api_url"
    private const val CRYPTO_BUCKET_MS = 5 * 60 * 1000L
    private const val CRYPTO_CONFIG_MAX_AGE_MS = 6 * 60 * 60 * 1000L
    private const val CRYPTO_EPOCH_MS = 7 * 24 * 60 * 60 * 1000L
    private const val CRYPTO_EPOCH_GRACE_MS = 24 * 60 * 60 * 1000L
    private const val CRYPTO_CONTENT_LANE = "k7"
    private const val HOME_ROW_LIMIT = 20
    private const val HOME_TAG_LIMIT = 20
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val translationTypes = listOf("sub", "dub", "raw")
    private val browseTranslationTypes = listOf("sub", "dub")
    @Volatile
    private var cryptoBootstrap: CryptoBootstrap? = null

    private val SHOW_FIELDS = """
        _id
        type
        englishName
        name
        nativeName
        nameOnlyString
        altNames
        slugTime
        description
        availableEpisodes
        episodeCount
        lastEpisodeInfo
        episodeDuration
        airedStart
        score
        thumbnail
        banner
        genres
        isAdult
    """.trimIndent()

    private val SEARCH_QUERY = """
        query(
          ${'$'}search: SearchInput
          ${'$'}limit: Int
          ${'$'}page: Int
          ${'$'}translationType: VaildTranslationTypeEnumType
          ${'$'}countryOrigin: VaildCountryOriginEnumType
          ${'$'}allowAdult: Boolean
        ) {
          shows(
            search: ${'$'}search
            limit: ${'$'}limit
            page: ${'$'}page
            translationType: ${'$'}translationType
            countryOrigin: ${'$'}countryOrigin
            allowAdult: ${'$'}allowAdult
          ) {
            pageInfo { total }
            edges { $SHOW_FIELDS }
          }
        }
    """.trimIndent()

    private val POPULAR_DAILY_QUERY = """
        query(
          ${'$'}type: VaildPopularTypeEnumType!
          ${'$'}size: Int!
          ${'$'}dateRange: Int
          ${'$'}page: Int
          ${'$'}allowAdult: Boolean
          ${'$'}allowUnknown: Boolean
        ) {
          queryPopular(
            type: ${'$'}type
            size: ${'$'}size
            dateRange: ${'$'}dateRange
            page: ${'$'}page
            allowAdult: ${'$'}allowAdult
            allowUnknown: ${'$'}allowUnknown
          ) {
            total
            recommendations {
              anyCard {
                $SHOW_FIELDS
                lastEpisodeDate
                lastChapterDate
                availableChapters
              }
            }
          }
        }
    """.trimIndent()

    private val TAG_QUERY = """
        query(${ '$' }search: ListForTagInput!) {
          queryListForTag(search: ${ '$' }search) {
            pageInfo { total }
            edges { $SHOW_FIELDS }
          }
        }
    """.trimIndent()

    private val TAGS_QUERY = """
        query(
          ${ '$' }page: Int
          ${ '$' }offset: Int
          ${ '$' }limit: Int
          ${ '$' }search: TagSearchInput
        ) {
          queryTags(
            page: ${ '$' }page
            offset: ${ '$' }offset
            limit: ${ '$' }limit
            search: ${ '$' }search
          ) {
            pageInfo { total }
            edges {
              _id
              name
              slug
              tagType
            }
          }
        }
    """.trimIndent()

    private val DETAIL_QUERY = """
        query(${ '$' }_id: String!) {
          show(_id: ${ '$' }_id) {
            $SHOW_FIELDS
            status
            averageScore
            rating
            airedEnd
            studios
            countryOfOrigin
            availableEpisodesDetail
            isAdult
            tags
          }
        }
    """.trimIndent()

    private val RANDOM_QUERY = """
        query(
          ${ '$' }format: String!
          ${ '$' }allowAdult: Boolean
        ) {
          queryRandomRecommendation(
            format: ${ '$' }format
            allowAdult: ${ '$' }allowAdult
          ) {
            $SHOW_FIELDS
          }
        }
    """.trimIndent()

    private val SOURCE_QUERY = """
        query(
          ${ '$' }showId: String!
          ${ '$' }translationType: VaildTranslationTypeEnumType!
          ${ '$' }episodeString: String!
        ) {
          episode(
            showId: ${ '$' }showId
            translationType: ${ '$' }translationType
            episodeString: ${ '$' }episodeString
          ) {
            episodeString
            uploadDate
            sourceUrls
            thumbnail
            notes
            show { $SHOW_FIELDS }
          }
        }
    """.trimIndent()

    override val name = "MKissa"
    override val baseUrl = "https://mkissa.to/anime"
    override val language = "en"
    override val logo = "https://mkissa.to/favicon-32x32.png"

    private val service = Retrofit.Builder()
        .baseUrl("https://mkissa.to/")
        .addConverterFactory(ScalarsConverterFactory.create())
        .client(
            OkHttpClient.Builder()
                .cache(Cache(File("cacheDir", "mkissa_okhttpcache"), 10 * 1024 * 1024))
                .readTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .dns(DnsResolver.doh)
                .build()
        )
        .build()
        .create(MkissaService::class.java)

    private val sourceResolverClient = OkHttpClient.Builder()
        .dns(DnsResolver.doh)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private interface MkissaService {
        @Headers(
            "Accept: application/json",
            "Origin: https://mkissa.to",
            "Referer: https://mkissa.to/",
            "User-Agent: Mozilla/5.0"
        )
        @GET
        suspend fun api(
            @Url apiUrl: String,
            @Query("variables") variables: String,
            @Query("extensions") extensions: String,
            @Header("x-build-id") buildId: String
        ): String

        @Headers(
            "Accept: application/json",
            "Content-Type: application/json",
            "Origin: https://mkissa.to",
            "Referer: https://mkissa.to/",
            "User-Agent: Mozilla/5.0"
        )
        @POST
        suspend fun apiPost(
            @Url apiUrl: String,
            @Body body: okhttp3.RequestBody,
            @Header("x-build-id") buildId: String
        ): String
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        fun category(name: String, block: suspend () -> List<AppAdapter.Item>) = async {
            Category(
                name = name,
                list = try {
                    block()
                } catch (_: Exception) {
                    emptyList()
                }
            )
        }

        val dynamicTags = try {
            homeTags()
        } catch (_: Exception) {
            fallbackHomeTags
        }

        val newSeries = category("New Series") {
            val now = java.util.Calendar.getInstance()
            searchShows(
                search = mapOf(
                    "season" to currentAnimeSeason(now.get(java.util.Calendar.MONTH) + 1),
                    "year" to now.get(java.util.Calendar.YEAR)
                ),
                limit = HOME_ROW_LIMIT,
                page = 1,
                countryOrigin = "JP"
            )
        }

        val categories = buildList {
            add(category("Latest Updates (Sub/Dub)") {
                searchShows(mapOf("sortBy" to "Recent"), limit = HOME_ROW_LIMIT, page = 1)
            })
            add(newSeries)
            add(category("Random") { randomShows(limit = HOME_ROW_LIMIT) })
            addAll(
                dynamicTags.map { tag ->
                    category(tag.name) {
                        tagShows(
                            slug = tag.slug,
                            name = tag.name,
                            tagType = tag.tagType,
                            limit = HOME_ROW_LIMIT,
                            page = 1
                        )
                    }
                }
            )
            add(category("Trending Activity") { popularByDateRange(dateRange = 1, page = 1, size = HOME_ROW_LIMIT) })
        }

        categories
            .map { it.await() }
            .filter { it.list.isNotEmpty() }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return genres
        return searchItems(mapOf("query" to query), limit = 26, page = page)
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return searchMovies(page = page)
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return searchShows(mapOf("sortBy" to "Popular", "types" to listOf("TV")), limit = 26, page = page)
    }

    override suspend fun getMovie(id: String): Movie {
        return showDetails(id.removePrefix("movie:")).toMovie()
    }

    override suspend fun getTvShow(id: String): TvShow {
        return showDetails(id.removePrefix("movie:"))
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split("|")
        val showId = parts.firstOrNull().orEmpty()
        val translation = parts.getOrNull(1) ?: "sub"
        val show = showDetails(showId)
        val count = show.seasons.firstOrNull { it.id == seasonId }?.episodes?.size ?: 0
        return buildEpisodes(showId, count, translation)
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val name = id.replace('_', ' ')
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        val shows = tagShows(slug = id, name = name, limit = 26, page = page)
        return Genre(id = id, name = name, shows = shows)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        throw Exception("People pages are not available in MKissa")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val parts = id.split("|")
        val showId = parts.firstOrNull()?.removePrefix("movie:").orEmpty()
        val episode = parts.getOrNull(1) ?: "1"
        val requestedTranslation = parts.getOrNull(2)

        val detail = showJson(showId)
        val available = detail.optJSONObject("availableEpisodes")
        return translationTypes
            .filter { translation ->
                requestedTranslation == null || requestedTranslation == translation
            }
            .filter { translation ->
                (available?.optInt(translation, 0) ?: if (translation == "sub") 1 else 0) > 0
            }
            .flatMap { translation ->
                val sources = getSourceEntries(showId = showId, episode = episode, translation = translation)
                sources.map { sourceObject ->
                    val sourceName = sourceObject.stringOrNull("sourceName") ?: "MKissa"
                    val sourceUrl = sourceObject.sourceUrl()
                    Video.Server(
                        id = sourceUrl,
                        name = "$sourceName ${translation.uppercase()}".trim(),
                        src = sourceUrl
                    )
                }
            }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val source = resolveSourceUrl(server.src.ifBlank { server.id })
            ?: throw Exception("Selected MKissa source could not be resolved")

        if (source.contains(".m3u8", ignoreCase = true) || source.contains(".mp4", ignoreCase = true)) {
            return Video(
                source = source,
                headers = directPlaybackHeaders()
            )
        }

        return Extractor.extract(source, server)
    }

    private suspend fun getSourceEntries(showId: String, episode: String, translation: String): List<JSONObject> {
        val response = api(
            variables = JSONObject()
                .put("showId", showId)
                .put("translationType", translation)
                .put("episodeString", episode),
            hash = SOURCE_HASH,
            fallbackQuery = SOURCE_QUERY
        )
        var data = response.optJSONObject("data") ?: JSONObject()
        if (data.has("tobeparsed")) {
            data = decryptTobeParsed(data.optString("tobeparsed"))
        }

        return sequenceOf(
            data.optJSONArray("sourceUrls"),
            data.optJSONObject("episode")?.optJSONArray("sourceUrls")
        )
            .filterNotNull()
            .flatMap { it.asSequence() }
            .mapNotNull { it as? JSONObject }
            .filter { it.sourceUrl().isNotBlank() }
//            .filterNot { it.isKnownDeadEmbedSource() }
            .toList()
    }

    private suspend fun popularShows(page: Int, size: Int): List<TvShow> {
        val variables = JSONObject()
            .put(
                "search",
                JSONObject()
                    .put("page", page)
                    .put("size", size)
                    .put("sortBy", "Popular")
                    .put("allowAdult", false)
                    .put("allowUnknown", false)
            )
        return parseShows(api(variables, POPULAR_HASH, SEARCH_QUERY))
    }

    private suspend fun popularByDateRange(dateRange: Int, page: Int, size: Int): List<TvShow> {
        val variables = JSONObject()
            .put("type", "anime")
            .put("size", size)
            .put("dateRange", dateRange)
            .put("page", page)
            .put("allowAdult", false)
            .put("allowUnknown", false)
        return parsePopular(api(variables, POPULAR_DAILY_HASH, POPULAR_DAILY_QUERY))
    }

    private suspend fun tagShows(
        slug: String,
        name: String,
        tagType: String? = null,
        limit: Int = HOME_ROW_LIMIT,
        page: Int = 1
    ): List<TvShow> {
        val search = JSONObject()
            .put("slug", slug)
            .put("format", "anime")
            .put("page", page)
            .put("limit", limit)
            .put("name", name)
            .put("allowAdult", false)
            .put("allowUnknown", false)
        if (!tagType.isNullOrBlank()) search.put("tagType", tagType.normalizedTagType())
        val variables = JSONObject().put("search", search)
        return parseShows(api(variables, GENRE_HASH, TAG_QUERY))
    }

    private suspend fun homeTags(): List<HomeTag> {
        val variables = JSONObject()
            .put("page", 1)
            .put("limit", HOME_TAG_LIMIT)
            .put(
                "search",
                JSONObject()
                    .put("format", "anime")
                    .put("sortBy", "Recommendation")
                    .put("allowAdult", false)
                    .put("allowUnknown", false)
            )
        val response = postQuery(TAGS_QUERY, variables)
        val edges = response.optJSONObject("data")
            ?.optJSONObject("queryTags")
            ?.optJSONArray("edges")
            ?: JSONArray()
        return edges.asSequence()
            .mapNotNull { it as? JSONObject }
            .mapNotNull { tag ->
                val slug = tag.stringOrNull("slug") ?: tag.stringOrNull("name")?.slugify() ?: return@mapNotNull null
                val name = tag.stringOrNull("name") ?: return@mapNotNull null
                if (slug == "movie-anime") return@mapNotNull null
                HomeTag(
                    slug = slug,
                    name = name,
                    tagType = tag.stringOrNull("tagType")?.normalizedTagType()
                )
            }
            .distinctBy { it.slug }
            .toList()
    }

    private suspend fun randomShows(limit: Int): List<TvShow> {
        val response = postQuery(
            RANDOM_QUERY,
            JSONObject()
                .put("format", "anime")
                .put("allowAdult", false)
        )
        val items = response.optJSONObject("data")
            ?.optJSONArray("queryRandomRecommendation")
            ?: JSONArray()
        return items.asSequence()
            .mapNotNull { it as? JSONObject }
            .mapNotNull { it.toTvShow(detailed = false) }
            .take(limit)
            .toList()
    }

    private suspend fun searchShows(
        search: Map<String, Any?>,
        limit: Int,
        page: Int,
        countryOrigin: String? = null,
        hash: String = SEARCH_HASH
    ): List<TvShow> {
        val shows = buildList {
            for (translation in browseTranslationTypes) {
                val variables = JSONObject()
                    .put("search", JSONObject(search))
                    .put("limit", limit)
                    .put("page", page)
                    .put("translationType", translation)
                    .put("allowAdult", false)
                if (countryOrigin != null) variables.put("countryOrigin", countryOrigin)
                addAll(parseShows(api(variables, hash, SEARCH_QUERY)))
            }
        }
        return shows
            .distinctBy { it.id }
            .take(limit)
    }

    private suspend fun searchItems(
        search: Map<String, Any?>,
        limit: Int,
        page: Int
    ): List<AppAdapter.Item> {
        val items = buildList {
            for (translation in browseTranslationTypes) {
                val variables = JSONObject()
                    .put("search", JSONObject(search))
                    .put("limit", limit)
                    .put("page", page)
                    .put("translationType", translation)
                    .put("allowAdult", false)
                addAll(parseSearchItems(api(variables, SEARCH_HASH, SEARCH_QUERY)))
            }
        }
        return items
            .distinctBy { item ->
                when (item) {
                    is Movie -> item.id
                    is TvShow -> item.id
                    else -> item.itemType
                }
            }
            .take(limit)
    }

    private suspend fun searchMovies(page: Int, limit: Int = 26): List<Movie> {
        val movies = buildList {
            for (translation in browseTranslationTypes) {
                val variables = JSONObject()
                    .put("search", JSONObject(mapOf("sortBy" to "Popular", "types" to listOf("Movie"))))
                    .put("limit", limit)
                    .put("page", page)
                    .put("translationType", translation)
                    .put("allowAdult", false)
                addAll(
                    showEdges(api(variables, SEARCH_HASH, SEARCH_QUERY))
                        .asSequence()
                        .mapNotNull { it as? JSONObject }
                        .mapNotNull { it.toMovieOrNull(forceMovie = true) }
                        .toList()
                )
            }
        }
        return movies
            .distinctBy { it.id }
            .take(limit)
    }

    private suspend fun showDetails(id: String): TvShow {
        val show = showJson(id)
        return show.toTvShow(detailed = true) ?: throw Exception("MKissa show is missing required metadata")
    }

    private suspend fun showJson(id: String): JSONObject {
        val show = api(JSONObject().put("_id", id), DETAIL_HASH, DETAIL_QUERY)
            .optJSONObject("data")
            ?.optJSONObject("show")
            ?: throw Exception("MKissa show not found")
        if (show.isAdultContent()) throw Exception("MKissa show not found")
        return show
    }

    private suspend fun api(variables: JSONObject, hash: String, fallbackQuery: String? = null): JSONObject {
        val protectedQuery = hash == SOURCE_HASH
        var lastError: Exception? = null
        repeat(if (protectedQuery) 2 else 1) { attempt ->
            try {
                val bootstrap = if (protectedQuery) {
                    getCryptoBootstrap(forceRefresh = attempt > 0)
                } else {
                    null
                }
                val extensions = JSONObject()
                    .put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", hash))
                if (protectedQuery) {
                    extensions
                        .put("k", CRYPTO_CONTENT_LANE)
                        .put("aaReq", createCryptoRequest(hash, bootstrap!!))
                }

                val response = try {
                    JSONObject(
                        service.api(
                            bootstrap?.apiUrl ?: API_URL,
                            variables.toString(),
                            extensions.toString(),
                            bootstrap?.buildId ?: DEFAULT_BUILD_ID
                        )
                    )
                } catch (error: HttpException) {
                    if (fallbackQuery == null) throw error
                    null
                }
                if (response != null && !response.shouldRetryWithQueryBody()) {
                    if (protectedQuery && response.hasCryptoError()) throw CryptoConfigRejectedException()
                    if (response.hasNoGraphQlData()) {
                        throw response.toGraphQlException()
                    }
                    return response
                }
                if (fallbackQuery == null) return response ?: JSONObject()

                val body = JSONObject()
                    .put("query", fallbackQuery)
                    .put("variables", variables)
                    .put("extensions", extensions)
                    .toString()
                    .toRequestBody(JSON_MEDIA_TYPE)
                val postResponse = JSONObject(
                    service.apiPost(
                        bootstrap?.apiUrl ?: API_URL,
                        body,
                        bootstrap?.buildId ?: DEFAULT_BUILD_ID
                    )
                )
                if (protectedQuery && postResponse.hasCryptoError()) throw CryptoConfigRejectedException()
                if (postResponse.hasNoGraphQlData()) {
                    throw postResponse.toGraphQlException()
                }
                return postResponse
            } catch (error: Exception) {
                lastError = error
                if (protectedQuery && attempt == 0 && error.shouldRefreshCryptoConfig()) {
                    invalidateCryptoBootstrap()
                    return@repeat
                }
                throw error
            }
        }
        throw lastError ?: Exception("MKissa API request failed")
    }

    private suspend fun postQuery(query: String, variables: JSONObject): JSONObject {
        val body = JSONObject()
            .put("query", query)
            .put("variables", variables)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        return JSONObject(service.apiPost(API_URL, body, DEFAULT_BUILD_ID))
    }

    private fun parseShows(response: JSONObject): List<TvShow> {
        return showEdges(response)
            .mapNotNull { it as? JSONObject }
            .filterNot { it.isAdultContent() }
            .mapNotNull { it.toTvShow(detailed = false) }
            .toList()
    }

    private fun parseSearchItems(response: JSONObject): List<AppAdapter.Item> {
        val items = showEdges(response)
            .mapNotNull { it as? JSONObject }
            .filterNot { it.isAdultContent() }
            .mapNotNull { show ->
                val title = show.displayTitleOrNull() ?: "?"
                val type = show.stringOrNull("type") ?: "?"
                val genres = (0 until (show.optJSONArray("genres")?.length() ?: 0))
                    .map { show.optJSONArray("genres")!!.optString(it) }
                    .joinToString(",")
                val isAdult = show.opt("isAdult")
                Log.d(TAG, "RAW show: _id=${show.stringOrNull("_id")}, title=$title, type=$type, genres=[$genres], isAdult=$isAdult")
                if (show.stringOrNull("type").equals("Movie", ignoreCase = true)) {
                    show.toMovieOrNull(forceMovie = true)
                } else {
                    show.toTvShow(detailed = false)
                }
            }
            .toList()
        Log.d(TAG, "parseSearchItems: ${items.size} items parsed from search")
        items.forEach { item ->
            when (item) {
                is Movie -> Log.d(TAG, "  Movie: ${item.title} (genres: ${item.genres.map { it.name }})")
                is TvShow -> Log.d(TAG, "  TvShow: ${item.title} (genres: ${item.genres.map { it.name }})")
                else -> Log.d(TAG, "  Other: ${item.itemType}")
            }
        }
        return items
    }

    private fun showEdges(response: JSONObject): Sequence<Any?> {
        val edges = response.optJSONObject("data")
            ?.optJSONObject("shows")
            ?.optJSONArray("edges")
            ?: response.optJSONObject("data")
                ?.optJSONObject("queryListForTag")
                ?.optJSONArray("edges")
            ?: JSONArray()
        return edges.asSequence()
    }

    private fun JSONObject.shouldRetryWithQueryBody(): Boolean {
        val errors = optJSONArray("errors") ?: return false
        return errors.asSequence()
            .mapNotNull { it as? JSONObject }
            .any { error ->
                error.optString("message").contains("PersistedQueryNotFound", ignoreCase = true) ||
                        error.optString("message").contains("PersistedQueryNotSupported", ignoreCase = true) ||
                        error.optJSONObject("extensions")
                            ?.optString("code")
                            ?.contains("PERSISTED_QUERY", ignoreCase = true) == true
            }
    }

    private fun JSONObject.hasCryptoError(): Boolean {
        val errors = optJSONArray("errors") ?: return false
        return errors.asSequence()
            .mapNotNull { it as? JSONObject }
            .any { error ->
                val message = error.optString("message")
                val code = error.optJSONObject("extensions")?.optString("code").orEmpty()
                sequenceOf(message, code).any { value ->
                    value.contains("AA_CRYPTO_", ignoreCase = true) ||
                            value.contains("BUILD_MISMATCH", ignoreCase = true) ||
                            value.contains("INVALID_BUILD", ignoreCase = true) ||
                            value.contains("STALE_BUILD", ignoreCase = true) ||
                            value.contains("x-build-id", ignoreCase = true)
                }
            }
    }

    private fun JSONObject.hasNoGraphQlData(): Boolean {
        return !has("data") || isNull("data")
    }

    private fun JSONObject.toGraphQlException(): Exception {
        val messages = optJSONArray("errors")
            ?.asSequence()
            ?.mapNotNull { it as? JSONObject }
            ?.mapNotNull { it.stringOrNull("message") }
            ?.distinct()
            ?.joinToString("; ")
            .orEmpty()
        return Exception(
            if (messages.isBlank()) "MKissa API returned no data" else "MKissa API error: $messages"
        )
    }

    private fun Exception.shouldRefreshCryptoConfig(): Boolean {
        return this is IOException ||
                this is HttpException ||
                this is org.json.JSONException ||
                this is CryptoConfigRejectedException
    }

    private fun createCryptoRequest(queryHash: String, bootstrap: CryptoBootstrap): String {
        val timestamp = System.currentTimeMillis() / CRYPTO_BUCKET_MS * CRYPTO_BUCKET_MS
        val iv = MessageDigest.getInstance("SHA-256")
            .digest(
                "${bootstrap.epoch}:${bootstrap.buildId}:$queryHash:$timestamp:$CRYPTO_CONTENT_LANE"
                    .toByteArray(Charsets.UTF_8)
            )
            .copyOfRange(0, 12)
        val payload = """{"v":1,"ts":$timestamp,"epoch":${bootstrap.epoch},"buildId":"${bootstrap.buildId}","qh":"$queryHash","k":"$CRYPTO_CONTENT_LANE"}"""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(bootstrap.key, "AES"),
            GCMParameterSpec(128, iv)
        )
        val encrypted = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(byteArrayOf(1) + iv + encrypted, Base64.NO_WRAP)
    }

    @Synchronized
    private fun getCryptoBootstrap(forceRefresh: Boolean): CryptoBootstrap {
        val now = System.currentTimeMillis()
        val cached = cryptoBootstrap
            ?.takeIf { !forceRefresh && it.isFresh(now) }
            ?: loadStoredCryptoConfig()
                ?.takeIf { !forceRefresh && it.isFresh(now) }
        if (cached != null) {
            cryptoBootstrap = cached
            return cached
        }

        return fetchCryptoBootstrap().also {
            cryptoBootstrap = it
            saveStoredCryptoConfig(it)
        }
    }

    private fun invalidateCryptoBootstrap() {
        cryptoBootstrap = null
        runCatching {
            StreamFlixApp.instance
                .getSharedPreferences(CRYPTO_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }
    }

    private fun loadStoredCryptoConfig(): CryptoBootstrap? {
        return runCatching {
            val prefs = StreamFlixApp.instance
                .getSharedPreferences(CRYPTO_PREFS, android.content.Context.MODE_PRIVATE)
            val buildId = prefs.getString(CRYPTO_BUILD_KEY, null)?.takeIf { it.isNotBlank() }
                ?: return null
            val mask = prefs.getString(CRYPTO_MASK_KEY, null)?.hexBytes() ?: return null
            val key = prefs.getString(CRYPTO_KEY_KEY, null)?.hexBytes() ?: return null
            val apiUrl = prefs.getString(CRYPTO_API_URL_KEY, null)?.normalizedApiUrl() ?: return null
            if (mask.isEmpty() || key.size != 32) return null
            CryptoBootstrap(
                epoch = prefs.getLong("epoch", -1L),
                switchAt = prefs.getLong("switch_at", 0L),
                fetchedAt = prefs.getLong("fetched_at", 0L),
                buildId = buildId,
                mask = mask,
                key = key,
                apiUrl = apiUrl
            )
        }.getOrNull()
    }

    private fun saveStoredCryptoConfig(config: CryptoBootstrap) {
        runCatching {
            StreamFlixApp.instance
                .getSharedPreferences(CRYPTO_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(CRYPTO_BUILD_KEY, config.buildId)
                .putString(CRYPTO_MASK_KEY, config.mask.toHex())
                .putString(CRYPTO_KEY_KEY, config.key.toHex())
                .putString(CRYPTO_API_URL_KEY, config.apiUrl)
                .putLong("epoch", config.epoch)
                .putLong("switch_at", config.switchAt)
                .putLong("fetched_at", config.fetchedAt)
                .apply()
        }
    }

    private fun fetchCryptoBootstrap(): CryptoBootstrap {
        val request = Request.Builder()
            .url("https://mkissa.to/anime")
            .header("Accept", "text/html,application/xhtml+xml")
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val html = sourceResolverClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("MKissa crypto bootstrap returned HTTP ${response.code}")
            response.body?.string()
        } ?: throw Exception("MKissa crypto bootstrap was empty")
        val bundleCrypto = fetchBundleCryptoConfig(html)
            ?: throw Exception("MKissa crypto bundle config was not found")
        val json = parseEmbeddedCryptoBootstrap(html)
            ?: fetchRemoteCryptoBootstrap(bundleCrypto)
            ?: throw Exception("MKissa crypto bootstrap was not found")
        val epoch = json.optLong("epoch", -1L)
        val switchAt = json.optLong("switchAt", 0L)
        val partB = json.optString("partB")
        if (epoch < 0 || switchAt <= 0 || partB.isBlank()) {
            throw Exception("MKissa crypto bootstrap is invalid")
        }
        val mask = bundleCrypto.mask
        val buildId = bundleCrypto.buildId
        val encodedKey = Base64.decode(partB, Base64.DEFAULT)
        if (encodedKey.size < 32 || mask.isEmpty()) throw Exception("MKissa crypto key is invalid")
        val key = ByteArray(32) { index ->
            (encodedKey[index].toInt() xor mask[index % mask.size].toInt()).toByte()
        }
        return CryptoBootstrap(
            epoch = epoch,
            switchAt = switchAt,
            fetchedAt = System.currentTimeMillis(),
            buildId = buildId,
            mask = mask,
            key = key,
            apiUrl = bundleCrypto.apiUrl
        )
    }

    private fun parseEmbeddedCryptoBootstrap(html: String): JSONObject? {
        val markerIndex = html.indexOf("window.__aaCrypto")
        val valueStart = if (markerIndex >= 0) html.indexOf('=', markerIndex) else -1
        val valueEnd = if (valueStart >= 0) html.indexOf(';', valueStart + 1) else -1
        if (valueStart < 0 || valueEnd < 0) return null
        return runCatching {
            JSONObject(html.substring(valueStart + 1, valueEnd).trim())
        }.getOrNull()
    }

    private fun fetchRemoteCryptoBootstrap(bundleCrypto: BundleCryptoConfig): JSONObject? {
        val endpoint = bundleCrypto.apiUrl.toHttpUrlOrNull()
            ?.newBuilder()
            ?.encodedPath("/client-crypto/v1/bootstrap")
            ?.query(null)
            ?.addQueryParameter("buildId", bundleCrypto.buildId)
            ?.addQueryParameter("k", CRYPTO_CONTENT_LANE)
            ?.build()
            ?: return null
        val now = System.currentTimeMillis()
        val currentEpoch = now / CRYPTO_EPOCH_MS
        val firstEpoch = if (now - currentEpoch * CRYPTO_EPOCH_MS < CRYPTO_EPOCH_GRACE_MS) {
            currentEpoch - 1
        } else {
            currentEpoch
        }
        return sequenceOf(firstEpoch, currentEpoch)
            .distinct()
            .mapNotNull { epoch ->
                runCatching {
                    val bootToken = createCryptoBootToken(bundleCrypto, epoch)
                    sourceResolverClient.newCall(
                        Request.Builder()
                            .url(endpoint)
                            .header("Accept", "application/json")
                            .header("User-Agent", "Mozilla/5.0")
                            .header("Origin", "https://mkissa.to")
                            .header("Referer", "https://mkissa.to/anime")
                            .header("x-build-id", bundleCrypto.buildId)
                            .header("x-aa-boot", bootToken)
                            .build()
                    ).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        response.body?.string()?.let(::JSONObject)
                    }
                }.getOrNull()
            }
            .firstOrNull { json ->
                json.optString("partB").isNotBlank() &&
                        json.optString("k", CRYPTO_CONTENT_LANE) == CRYPTO_CONTENT_LANE
            }
    }

    private fun createCryptoBootToken(bundleCrypto: BundleCryptoConfig, epoch: Long): String {
        val key = hmacSha256(bundleCrypto.mask, "aa-boot:${bundleCrypto.buildId}")
        val payload = "${bundleCrypto.buildId}:mkissa:mkissa.to:$epoch:$CRYPTO_CONTENT_LANE"
        return hmacSha256(key, payload).toHex()
    }

    private fun hmacSha256(key: ByteArray, value: String): ByteArray {
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value.toByteArray(Charsets.UTF_8))
        }
    }

    private fun sha256Hex(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .toHex()

    private fun fetchBundleCryptoConfig(html: String): BundleCryptoConfig? {
        // SvelteKit/Vite changes the asset host and may emit either absolute or
        // relative script URLs. Do not couple discovery to today's CDN path.
        val pageUrl = "https://mkissa.to/anime".toHttpUrlOrNull() ?: return null
        // The entry can be emitted as a script attribute or as an import()
        // in streamed HTML, so look for the URL token rather than its wrapper.
        val entryUrl = Regex("""(?:https?://|/|\.\.?/)[^\"'\s]*?/entry/app\.[^\"'\s]+\.js""")
            .find(html)
            ?.value
            ?.let { pageUrl.resolve(it)?.toString() }
            ?: return null
        val app = runCatching {
            sourceResolverClient.newCall(
                Request.Builder()
                    .url(entryUrl)
                    .header("Accept", "application/javascript")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull() ?: return null

        // The crypto code can live in a chunks/ file or in a route node. Both
        // are referenced by the entry bundle, and either directory can change
        // independently between deployments.
        val scriptUrls = Regex("""(?:https?:[^\"'\s]+|\.\.?/)(?:[^\"'\s]+/)?(?:chunks|nodes)/[^\"'\s]+\.js""")
            .findAll(app)
            .mapNotNull { entryUrl.toHttpUrlOrNull()?.resolve(it.value)?.toString() }
            .distinct()
            .toList()
        val scripts = (sequenceOf(app) + scriptUrls.asSequence().mapNotNull { url ->
            runCatching {
                sourceResolverClient.newCall(
                    Request.Builder()
                        .url(url)
                        .header("Accept", "application/javascript")
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                ).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.string()
                }
            }.getOrNull()
        }).toList()

        // SvelteKit can split the request code, the obfuscation table, and the
        // API constants into separate chunks. Parse each chunk first, then a
        // combined view so those pieces can still be discovered together.
        return (scripts.asSequence() + sequenceOf(scripts.joinToString("\n")))
            .filter { it.contains("aaReq") }
            .mapNotNull(::parseBundleCryptoConfig)
            .firstOrNull()
    }

    private fun parseBundleCryptoConfig(script: String): BundleCryptoConfig? {
        // Older bundles used stable names (`const bd=...`, `fr=...`). Newer minified
        // bundles rename both variables but retain the two adjacent guarded literals.
        val apiUrl = Regex("""https://[A-Za-z0-9.-]+(?::\d+)?/api/?""")
            .find(script)
            ?.value
            ?.normalizedApiUrl()
            ?: return null
        val legacyMask = Regex("""const\s+bd\s*=\s*[\"']([0-9a-fA-F]{64})[\"']""")
            .find(script)
            ?.groupValues
            ?.getOrNull(1)
        val legacyBuildId = Regex("""\bfr\s*=.{0,160}?[\"'](\d+)[\"']""")
            .find(script)
            ?.groupValues
            ?.getOrNull(1)
        if (legacyMask != null && legacyBuildId != null) {
            return BundleCryptoConfig(
                buildId = legacyBuildId,
                mask = legacyMask.hexBytes() ?: return null,
                apiUrl = apiUrl
            )
        }

        // Current bundles guard the mask but assign the adjacent build ID
        // directly: `const x=...?'<mask>':'',y='<build>';`.
        val guardedMaskWithDirectBuild = Regex(
            """[\"']([0-9a-fA-F]{64})[\"']\s*:\s*[\"']{2}\s*,\s*[A-Za-z_$][\w$]*\s*=\s*[\"'](\d+)[\"']\s*[,;]"""
        ).find(script)
        if (guardedMaskWithDirectBuild != null) {
            return BundleCryptoConfig(
                buildId = guardedMaskWithDirectBuild.groupValues[2],
                mask = guardedMaskWithDirectBuild.groupValues[1].hexBytes() ?: return null,
                apiUrl = apiUrl
            )
        }

        val guardedLiterals = Regex(
            """[\"']([0-9a-fA-F]{64})[\"']\s*:\s*[\"']{2}\s*,\s*[A-Za-z_$][\w$]*\s*=.{0,160}?[\"'](\d+)[\"']\s*:\s*[\"']{2}"""
        ).find(script)
        if (guardedLiterals != null) {
            return BundleCryptoConfig(
                buildId = guardedLiterals.groupValues[2],
                mask = guardedLiterals.groupValues[1].hexBytes() ?: return null,
                apiUrl = apiUrl
            )
        }

        return parseObfuscatedBundleCryptoConfig(script, apiUrl)
    }

    private fun parseObfuscatedBundleCryptoConfig(
        script: String,
        apiUrl: String
    ): BundleCryptoConfig? {
        // Recent bundles split the build id and mask fragments into separate
        // declarations (`const fm=... ?"105":""; const zd=[...];`). Older
        // bundles kept them together, so discover both independently.
        val buildId = Regex(
            """\?[\"'](\d+)[\"']\s*:\s*[\"']{2}"""
        ).find(script)?.groupValues?.getOrNull(1) ?: return null
        val callRegex = Regex("""([A-Za-z_$][\w$]*)\(([^)]*)\)""")
        val encodedParts = Regex(
            """(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*\[([^]]+)]"""
        ).findAll(script)
            .map { it.groupValues[1] to it.groupValues[2] }
            .map { body ->
                body.second.splitTopLevel(',').map { expression ->
                    callRegex.findAll(expression).map { call ->
                        ObfuscatedCall(
                            name = call.groupValues[1],
                            arguments = call.groupValues[2]
                                .split(',')
                                .mapNotNull { it.trim().toIntOrNull() }
                        )
                    }.toList()
                }
            }
            // The combined bundle contains unrelated minified arrays with the
            // same shape. The crypto array is the one whose lookup functions
            // resolve through the crypto string-table root.
            .filter { parts -> parts.size == 4 && parts.all { it.size == 2 } }
            .firstOrNull { parts ->
                parts.flatten().map { it.name }.distinct().all { name ->
                    parseObfuscatedLookup(script, name)?.let { lookup ->
                        parseObfuscatedRootAdjustment(script, lookup.rootName) != null
                    } == true
                }
            }
            ?: return null
        if (encodedParts.size != 4 || encodedParts.any { it.size != 2 }) return null

        val stringTable = extractObfuscatedStringTable(script) ?: return null
        val lookups = encodedParts.flatten().map { it.name }.distinct().associateWith { name ->
            parseObfuscatedLookup(script, name) ?: return null
        }
        val rootName = lookups.values.map { it.rootName }.distinct().singleOrNull() ?: return null
        val rootAdjustment = parseObfuscatedRootAdjustment(script, rootName) ?: return null

        val encodedMask = stringTable.indices.asSequence().mapNotNull { rotation ->
            decodeObfuscatedMask(
                encodedParts = encodedParts,
                lookups = lookups,
                rootAdjustment = rootAdjustment,
                stringTable = stringTable,
                rotation = rotation
            )
        }.distinctBy { it.contentHashCode() }.singleOrNull() ?: return null

        val buildSeed = ByteArray(32) { index ->
            val buildByte = buildId[index % buildId.length].code
            (buildByte xor ((index * 17 + 31) and 0xff)).toByte()
        }
        val mask = ByteArray(32) { index ->
            val group = index / 8
            val offset = index % 8
            (encodedMask[index].toInt() xor
                    buildSeed[index].toInt() xor
                    ((group * 41 + offset * 7) and 0xff)).toByte()
        }
        return BundleCryptoConfig(buildId = buildId, mask = mask, apiUrl = apiUrl)
    }

    private fun decodeObfuscatedMask(
        encodedParts: List<List<ObfuscatedCall>>,
        lookups: Map<String, ObfuscatedLookup>,
        rootAdjustment: Int,
        stringTable: List<String>,
        rotation: Int
    ): ByteArray? {
        val result = ByteArray(32)
        encodedParts.forEachIndexed { partIndex, calls ->
            val part = buildString {
                calls.forEach { call ->
                    val lookup = lookups[call.name] ?: return null
                    val variables = lookup.parameters.zip(call.arguments).toMap()
                    val input = IntegerExpression(lookup.argumentExpression, variables).parse()
                        ?: return null
                    val rawIndex = input - rootAdjustment + rotation
                    val index = (rawIndex % stringTable.size + stringTable.size) % stringTable.size
                    append(stringTable[index])
                }
            }
            if (!part.matches(Regex("""[A-Za-z0-9+/]{11}="""))) return null
            val bytes = runCatching { Base64.decode(part, Base64.DEFAULT) }
                .getOrNull()
                ?.takeIf { it.size == 8 }
                ?: return null
            bytes.copyInto(result, destinationOffset = partIndex * bytes.size)
        }
        return result
    }

    private fun parseObfuscatedLookup(script: String, name: String): ObfuscatedLookup? {
        val matches = Regex(
            """function\s+${Regex.escape(name)}\(([^)]*)\)\{return\s+([A-Za-z_$][\w$]*)\(([^)]*)\)\}"""
        ).findAll(script).map { match ->
            ObfuscatedLookup(
                parameters = match.groupValues[1].split(',').map(String::trim),
                rootName = match.groupValues[2],
                argumentExpression = match.groupValues[3]
            )
        }
        return matches.firstOrNull { parseObfuscatedRootAdjustment(script, it.rootName) != null }
            ?: matches.firstOrNull()
    }

    private fun parseObfuscatedRootAdjustment(script: String, name: String): Int? {
        val match = Regex(
            """function\s+${Regex.escape(name)}\(([A-Za-z_$][\w$]*)(?:,[^)]*)?\)\{return\s+\1=\1-\(([^)]*)\),"""
        ).find(script) ?: return null
        return IntegerExpression(match.groupValues[2]).parse()
    }

    private fun extractObfuscatedStringTable(script: String): List<String>? {
        val marker = listOf("\"aa-boo\"", "'aa-boo'")
            .map { script.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
            ?: return null
        val functionStart = script.lastIndexOf("function ", marker)
        val arrayStart = script.indexOf("=[", functionStart).takeIf { it in 0 until marker }
            ?.plus(1)
            ?: return null
        val arrayEnd = script.findClosingBracket(arrayStart) ?: return null
        return script.substring(arrayStart + 1, arrayEnd).parseJavaScriptStrings()
    }

    private fun String.findClosingBracket(start: Int): Int? {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in start until length) {
            val char = this[index]
            if (quote != null) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == quote) quote = null
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                '[' -> depth++
                ']' -> if (--depth == 0) return index
            }
        }
        return null
    }

    private fun String.parseJavaScriptStrings(): List<String> {
        val values = mutableListOf<String>()
        var index = 0
        while (index < length) {
            val quote = this[index]
            if (quote != '\'' && quote != '"') {
                index++
                continue
            }
            index++
            val value = StringBuilder()
            while (index < length && this[index] != quote) {
                if (this[index] != '\\') {
                    value.append(this[index++])
                    continue
                }
                index++
                if (index >= length) break
                when (val escaped = this[index++]) {
                    'n' -> value.append('\n')
                    'r' -> value.append('\r')
                    't' -> value.append('\t')
                    'x', 'u' -> {
                        val count = if (escaped == 'x') 2 else 4
                        val end = (index + count).coerceAtMost(length)
                        val decoded = substring(index, end).toIntOrNull(16)
                        if (decoded != null && end - index == count) {
                            value.append(decoded.toChar())
                            index = end
                        }
                    }
                    else -> value.append(escaped)
                }
            }
            if (index < length && this[index] == quote) index++
            values += value.toString()
        }
        return values
    }

    private fun String.splitTopLevel(separator: Char): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        forEachIndexed { index, char ->
            when (char) {
                '(' -> depth++
                ')' -> depth--
                separator -> if (depth == 0) {
                    result += substring(start, index)
                    start = index + 1
                }
            }
        }
        result += substring(start)
        return result
    }

    private fun parsePopular(response: JSONObject): List<TvShow> {
        val recommendations = response.optJSONObject("data")
            ?.optJSONObject("queryPopular")
            ?.optJSONArray("recommendations")
            ?: JSONArray()
        return recommendations.asSequence()
            .mapNotNull { (it as? JSONObject)?.optJSONObject("anyCard") }
            .mapNotNull { it.toTvShow(detailed = false) }
            .toList()
    }

    private fun JSONObject.toTvShow(detailed: Boolean): TvShow? {
        if (isAdultContent()) return null
        val rawId = stringOrNull("_id") ?: return null
        val isMovie = stringOrNull("type").equals("Movie", ignoreCase = true)
        val id = if (isMovie) "movie:$rawId" else rawId
        val title = displayTitleOrNull() ?: return null
        val overview = stringOrNull("description")?.let { Jsoup.parse(it).text() }
        // Browse results are compared by RecyclerView's DiffUtil. Keeping episode
        // graphs on those cards lets TvShow.episodeToWatch attach TvShow/Season
        // back-references, which makes model equality recurse indefinitely.
        // Episode metadata is only needed by the detail response.
        val availableEpisodes = if (detailed) {
            availableEpisodeTranslation(isMovie = isMovie)
        } else {
            null
        }
        val runtime = stringOrNull("episodeDuration")?.toLongOrNull()?.let { (it / 60000L).toInt() }

        return TvShow(
            id = id,
            title = title,
            overview = overview,
            released = dateString(optJSONObject("airedStart")),
            runtime = runtime,
            rating = optDoubleOrNull("score"),
            poster = imageUrl(stringOrNull("thumbnail")),
            banner = imageUrl(stringOrNull("banner")),
            genres = optJSONArray("genres")?.asSequence()
                ?.mapNotNull { it as? String }
                ?.map { Genre(id = it.lowercase().replace(" ", "_"), name = it) }
                ?.toList()
                ?: emptyList(),
            seasons = if (availableEpisodes != null) {
                listOf(
                    Season(
                        id = "$rawId|${availableEpisodes.translation}",
                        number = 1,
                        title = "Episodes",
                        episodes = buildEpisodes(rawId, availableEpisodes.count, availableEpisodes.translation)
                    )
                )
            } else {
                emptyList()
            }
        )
    }

    private fun JSONObject.availableEpisodeTranslation(isMovie: Boolean): AvailableEpisodes? {
        return translationTypes
            .firstNotNullOfOrNull { translation ->
                val count = availableEpisodeCount(translation = translation, isMovie = isMovie)
                if (count > 0) AvailableEpisodes(translation = translation, count = count) else null
            }
    }

    private fun JSONObject.availableEpisodeCount(translation: String, isMovie: Boolean): Int {
        val available = optJSONObject("availableEpisodes")
        if (available != null && available.has(translation) && !available.isNull(translation)) {
            return available.optInt(translation, 0).coerceAtLeast(0)
        }

        optJSONObject("availableEpisodesDetail")
            ?.optJSONArray(translation)
            ?.let { return it.length().coerceAtLeast(0) }

        return stringOrNull("episodeCount")?.toIntOrNull()?.coerceAtLeast(0)
            ?: optJSONObject("lastEpisodeInfo")
                ?.optJSONObject(translation)
                ?.optString("episodeString")
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
            ?: if (isMovie) 1 else 0
    }

    private fun JSONObject.toMovieOrNull(forceMovie: Boolean = false): Movie? {
        if (isAdultContent()) return null
        val rawId = stringOrNull("_id") ?: return null
        val isMovie = stringOrNull("type").equals("Movie", ignoreCase = true)
        if (!forceMovie && !isMovie) return null
        val title = displayTitleOrNull() ?: return null
        val overview = stringOrNull("description")?.let { Jsoup.parse(it).text() }
        val runtime = stringOrNull("episodeDuration")?.toLongOrNull()?.let { (it / 60000L).toInt() }
        return Movie(
            id = "movie:$rawId",
            title = title,
            overview = overview,
            released = dateString(optJSONObject("airedStart")),
            runtime = runtime,
            rating = optDoubleOrNull("score"),
            poster = imageUrl(stringOrNull("thumbnail")),
            banner = imageUrl(stringOrNull("banner")),
            genres = optJSONArray("genres")?.asSequence()
                ?.mapNotNull { it as? String }
                ?.map { Genre(id = it.lowercase().replace(" ", "_"), name = it) }
                ?.toList()
                ?: emptyList()
        )
    }

    private fun JSONObject.displayTitleOrNull(): String? {
        return stringOrNull("englishName")
            ?: stringOrNull("name")
            ?: stringOrNull("nativeName")
            ?: firstStringOrNull("altNames")
            ?: stringOrNull("nameOnlyString")?.humanizeSlug()
            ?: stringOrNull("slugTime")?.humanizeSlug()
    }

    private fun TvShow.toMovie(): Movie {
        return Movie(
            id = id,
            title = title,
            overview = overview,
            released = released?.let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(it.time) },
            runtime = runtime,
            rating = rating,
            poster = poster,
            banner = banner,
            genres = genres
        )
    }

    private fun buildEpisodes(showId: String, count: Int, translation: String): List<Episode> {
        if (count <= 0) return emptyList()
        return (1..count).map { number ->
            Episode(
                id = listOf(showId, number.toString(), translation).joinToString("|"),
                number = number,
                title = "Episode $number"
            )
        }
    }

    private fun imageUrl(value: String?): String? {
        val image = value?.takeIf { it.isNotBlank() } ?: return null
        val url = when {
            image.contains("/_tbs/") || image.contains("_tbs/") -> image
                .removePrefix("https://wp.youtube-anime.com/")
                .removePrefix("https://aln.youtube-anime.com/")
                .removePrefix("/")
                .substringBefore("?")
                .let { "$IMAGE_URL/$it?w=250" }
            image.startsWith("http") -> image
            image.startsWith("//") -> "https:$image"
            image.startsWith("images") -> "$IMAGE_URL/$image?w=250"
            else -> "$IMAGE_URL/images/$image?w=250"
        }
        return if (url.contains("youtube-anime.com")) {
            ArtworkRequestHeaders.withHeaders(
                url = url,
                referer = baseUrl,
                origin = "https://mkissa.to",
                userAgent = "Mozilla/5.0",
                accept = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
            )
        } else {
            url
        }
    }

    private fun dateString(date: JSONObject?): String? {
        val year = date?.optInt("year", 0)?.takeIf { it > 0 } ?: return null
        val month = date.optInt("month", 1).coerceIn(1, 12)
        val day = date.optInt("date", 1).coerceIn(1, 31)
        return "%04d-%02d-%02d".format(year, month, day)
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        return if (has(key) && !isNull(key)) optDouble(key) else null
    }

    private fun JSONObject.isAdultContent(): Boolean {
        if (!has("isAdult") || isNull("isAdult")) return false
        return when (val value = opt("isAdult")) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            else -> false
        }
    }

    private fun JSONObject.sourceUrl(): String {
        return stringOrNull("sourceUrl")
            ?: stringOrNull("url")
            ?: stringOrNull("source")
            ?: ""
    }

    private fun JSONObject.isKnownDeadEmbedSource(): Boolean {
        val source = sourceUrl().lowercase()
        return source.contains("streamsb.net") ||
                source.contains("streamlare.com")
    }

    private suspend fun resolveSourceUrl(value: String): String? {
        if (value.isBlank()) return null

        val decoded = decodePackedSourceUrl(value)
        val normalized = when {
            decoded.startsWith("//") -> "https:$decoded"
            decoded.startsWith("http", ignoreCase = true) -> decoded
            decoded.startsWith("/apivtwo/", ignoreCase = true) -> resolveAllanimeClockSource(decoded)
            else -> decoded.takeIf { it.isNotBlank() }
        }

        return normalized?.takeIf { it.isNotBlank() }
    }

    private fun decodePackedSourceUrl(value: String): String {
        if (!value.startsWith("--")) return value

        val bytes = runCatching {
            value.removePrefix("--")
                .chunked(2)
                .map { pair -> pair.toInt(16).xor(56).toByte() }
                .toByteArray()
        }.getOrNull() ?: return value

        return bytes.toString(Charsets.UTF_8).trim()
    }

    private suspend fun resolveAllanimeClockSource(path: String): String? {
        val normalizedPath = when {
            path.contains("/apivtwo/clock.json", ignoreCase = true) -> path
            path.contains("/apivtwo/clock?", ignoreCase = true) ->
                path.replace("/apivtwo/clock?", "/apivtwo/clock.json?", ignoreCase = true)
            else -> return null
        }
        val requestUrl = "$CLOCK_URL$normalizedPath" +
                if (normalizedPath.contains("referer=", ignoreCase = true)) "" else "&referer="
        val request = Request.Builder()
            .url(requestUrl)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", "https://allanime.to")
            .header("Referer", "https://allanime.to/")
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val body = sourceResolverClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        } ?: return null
        return findClockLinks(body).firstOrNull()
    }

    private fun findClockLinks(body: String): Sequence<String> = sequence {
        val root: Any = runCatching { JSONObject(body) }.getOrNull()
            ?: runCatching { JSONArray(body) }.getOrNull()
            ?: return@sequence
        yieldAll(findClockLinks(root))
    }

    private fun findClockLinks(value: Any): Sequence<String> = sequence {
        when (value) {
            is JSONObject -> value.keys().forEach { key ->
                val child = value.opt(key)
                if (key.equals("link", true) || key.equals("url", true) ||
                    key.equals("sourceUrl", true) || key.equals("file", true) ||
                    key.equals("hls", true) || key.equals("mp4", true)
                ) {
                    val link = child as? String
                    if (!link.isNullOrBlank() && link.startsWith("http", true)) yield(link)
                }
                if (child is JSONObject || child is JSONArray) yieldAll(findClockLinks(child))
            }
            is JSONArray -> for (index in 0 until value.length()) {
                val child = value.opt(index)
                if (child is JSONObject || child is JSONArray) yieldAll(findClockLinks(child))
                else if (child is String && child.startsWith("http", true)) yield(child)
            }
        }
    }

    /*
        val normalizedPath = path.replace("/apivtwo/clock?", "/apivtwo/clock.json?")
        val request = Request.Builder()
            .url("$CLOCK_URL$normalizedPath")
            .header("Accept", "application/json")
            .header("Origin", CLOCK_URL)
            .header("Referer", "$CLOCK_URL/player.html")
            .header("User-Agent", "Mozilla/5.0")
            .build()

        val body = sourceResolverClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        } ?: return null

        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val links = json.optJSONArray("links") ?: return null

        for (i in 0 until links.length()) {
            val linkObject = links.optJSONObject(i) ?: continue
            val link = linkObject.stringOrNull("link")
                ?: linkObject.stringOrNull("url")
                ?: linkObject.stringOrNull("sourceUrl")
                ?: linkObject.stringOrNull("file")
            if (!link.isNullOrBlank()) return link
        }

        return null*/

    private fun directPlaybackHeaders(): Map<String, String> {
        return mapOf(
            "Accept" to "*/*",
            "Origin" to CLOCK_URL,
            "Referer" to "$CLOCK_URL/",
            "User-Agent" to "Mozilla/5.0"
        )
    }

    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key)
            .trim()
            .takeUnless {
                it.isBlank() ||
                        it.equals("null", ignoreCase = true) ||
                        it.equals("undefined", ignoreCase = true)
            }
    }

    private fun JSONObject.firstStringOrNull(key: String): String? {
        val values = optJSONArray(key) ?: return null
        return values.asSequence()
            .mapNotNull { it as? String }
            .map { it.trim() }
            .firstOrNull {
                it.isNotBlank() &&
                        !it.equals("null", ignoreCase = true) &&
                        !it.equals("undefined", ignoreCase = true)
            }
    }

    private fun decryptTobeParsed(value: String): JSONObject {
        val bytes = Base64.decode(value, Base64.DEFAULT)
        if (bytes.isEmpty()) throw Exception("Empty MKissa encrypted payload")
        val version = bytes[0].toInt()
        if (version != 1) throw Exception("Unsupported MKissa encryption version: $version")

        val iv = bytes.copyOfRange(1, 13)
        val cipherText = bytes.copyOfRange(13, bytes.size)
        val rotatingKey = cryptoBootstrap?.key ?: fetchCryptoBootstrap().also {
            cryptoBootstrap = it
            saveStoredCryptoConfig(it)
        }.key
        val legacyKey = MessageDigest.getInstance("SHA-256")
            .digest("Xot36i3lK3:v$version".toByteArray(Charsets.UTF_8))
        val decrypted = sequenceOf(rotatingKey, legacyKey)
            .mapNotNull { key ->
                runCatching {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                    cipher.doFinal(cipherText)
                }.getOrNull()
            }
            .firstOrNull()
            ?: throw Exception("MKissa encrypted payload could not be decrypted")
        return JSONObject(String(decrypted, Charsets.UTF_8))
    }

    private fun JSONArray.asSequence(): Sequence<Any?> = sequence {
        for (i in 0 until length()) yield(opt(i))
    }

    private fun String.hexBytes(): ByteArray? {
        if (length % 2 != 0 || isBlank()) return null
        return runCatching {
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }.getOrNull()
    }

    private fun String.normalizedApiUrl(): String? {
        val url = toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.encodedPath.trimEnd('/') != "/api") return null
        return url.newBuilder()
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun currentAnimeSeason(month: Int): String {
        return when (month) {
            1, 2, 3 -> "Winter"
            4, 5, 6 -> "Spring"
            7, 8, 9 -> "Summer"
            else -> "Fall"
        }
    }

    private fun String.normalizedTagType(): String {
        return when (this) {
            "genre", "tag" -> "generic"
            "all" -> ""
            else -> this
        }
    }

    private fun String.slugify(): String {
        return lowercase()
            .replace("'", "")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private fun String.humanizeSlug(): String {
        return replace('_', ' ')
            .replace('-', ' ')
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase() else char.toString()
                }
            }
            .takeIf { it.isNotBlank() }
            ?: this
    }

    private data class HomeTag(
        val slug: String,
        val name: String,
        val tagType: String? = null
    )

    private data class AvailableEpisodes(
        val translation: String,
        val count: Int
    )

    private data class CryptoBootstrap(
        val epoch: Long,
        val switchAt: Long,
        val fetchedAt: Long,
        val buildId: String,
        val mask: ByteArray,
        val key: ByteArray,
        val apiUrl: String
    ) {
        fun isFresh(now: Long): Boolean {
            val age = now - fetchedAt
            return now < switchAt && age in 0 until CRYPTO_CONFIG_MAX_AGE_MS
        }
    }

    private data class BundleCryptoConfig(
        val buildId: String,
        val mask: ByteArray,
        val apiUrl: String
    )

    private data class ObfuscatedCall(
        val name: String,
        val arguments: List<Int>
    )

    private data class ObfuscatedLookup(
        val parameters: List<String>,
        val rootName: String,
        val argumentExpression: String
    )

    private class IntegerExpression(
        private val value: String,
        private val variables: Map<String, Int> = emptyMap()
    ) {
        private var position = 0

        fun parse(): Int? = runCatching {
            val result = parseSum()
            skipWhitespace()
            check(position == value.length)
            result
        }.getOrNull()

        private fun parseSum(): Int {
            var result = parseProduct()
            while (true) {
                skipWhitespace()
                result = when (peek()) {
                    '+' -> {
                        position++
                        result + parseProduct()
                    }
                    '-' -> {
                        position++
                        result - parseProduct()
                    }
                    else -> return result
                }
            }
        }

        private fun parseProduct(): Int {
            var result = parseFactor()
            while (true) {
                skipWhitespace()
                if (peek() != '*') return result
                position++
                result *= parseFactor()
            }
        }

        private fun parseFactor(): Int {
            skipWhitespace()
            return when (peek()) {
                '+' -> {
                    position++
                    parseFactor()
                }
                '-' -> {
                    position++
                    -parseFactor()
                }
                '(' -> {
                    position++
                    val result = parseSum()
                    skipWhitespace()
                    check(peek() == ')')
                    position++
                    result
                }
                else -> parseNumberOrVariable()
            }
        }

        private fun parseNumberOrVariable(): Int {
            val start = position
            while (peek()?.let { it.isLetterOrDigit() || it == '_' || it == '$' } == true) {
                position++
            }
            check(position > start)
            val token = value.substring(start, position)
            return token.toIntOrNull() ?: variables.getValue(token)
        }

        private fun skipWhitespace() {
            while (peek()?.isWhitespace() == true) position++
        }

        private fun peek(): Char? = value.getOrNull(position)
    }

    private class CryptoConfigRejectedException : Exception("MKissa rejected the crypto configuration")

    private val fallbackHomeTags = listOf(
        "Isekai",
        "Boys' Love",
        "Female Harem",
        "Yuri",
        "Reincarnation",
        "Male Protagonist",
        "Overpowered Protagonist",
        "Yandere",
        "Gyaru",
        "Cultivation",
        "Female Protagonist",
        "Full Color",
        "Magic",
        "Anti-Hero",
        "School",
        "POV",
        "Post-Apocalyptic",
        "Succubus",
        "Primarily Adult Cast",
        "Gender Bending"
    ).map { HomeTag(slug = it.slugify(), name = it) }

    private val genres = listOf(
        "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Isekai", "Magic", "Mystery",
        "Romance", "School", "Sci-Fi", "Seinen", "Shoujo", "Shounen", "Slice of Life",
        "Sports", "Super Power", "Supernatural", "Thriller"
    ).map { Genre(id = it.lowercase().replace(" ", "_"), name = it) }
}
