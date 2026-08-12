package com.streamflixreborn.streamflix.providers

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.StreamFlixApp
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.net.URLDecoder
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import okhttp3.Request

object FilmyOnlineCcProvider : Provider {

    override val name = "FilmyOnline"
    override val baseUrl = "https://filmyonline.cc"
    override val logo = "$baseUrl/favicon/icon-144x144.png?v=1703232212"
    override val language = "pl"

    private var webViewResolver: WebViewResolver? = null
    private val providerMutex = Mutex()
    private const val TAG = "FilmyOnlineBypass"
    private const val MAX_API_CLEARANCE_RETRIES = 2
    private var bootstrapCsrfToken: String? = null

    private val service = FilmyOnlineCcService.build()

    fun init(context: Context) {
        webViewResolver = WebViewResolver(context)
    }

    private fun getResolver(): WebViewResolver {
        return webViewResolver ?: WebViewResolver(StreamFlixApp.instance).also {
            webViewResolver = it
        }
    }

    private suspend fun fetchApiJson(url: String, referer: String = "$baseUrl/"): JSONObject = withContext(Dispatchers.IO) {
        var clearanceRetries = 0

        while (clearanceRetries <= MAX_API_CLEARANCE_RETRIES) {
            val request = buildBrowserApiRequest(url, referer)

            var shouldRefreshClearance = false
            NetworkClient.default.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    return@withContext JSONObject(body)
                }

                if (response.code == 403 && clearanceRetries < MAX_API_CLEARANCE_RETRIES) {
                    clearanceRetries++
                    shouldRefreshClearance = true
                } else {
                    throw Exception("FilmyOnline API request failed: ${response.code}")
                }
            }

            if (shouldRefreshClearance) {
                providerMutex.withLock {
                    getResolver().get(
                        baseUrl,
                        completion = { _, html, cookies -> isBrowserSessionReady(html, cookies) }
                    )
                }
                if (!promoteClearanceCookies(baseUrl)) {
                    throw Exception("FilmyOnline clearance cookie was not established")
                }
            }
        }

        throw Exception("FilmyOnline API request failed after refreshing clearance")
    }

    private suspend fun getDocument(url: String): Document {
        return try {
            val document = service.getDocument(url)
            val html = document.outerHtml()
            if (requiresClearance(html)) {
                throw Exception("FilmyOnline Cloudflare challenge detected")
            }
            document
        } catch (_: Exception) {
            Log.d(TAG, "Using WebView bypass for $url")
            val html = providerMutex.withLock {
                getResolver().get(
                    url,
                    completion = { _, pageHtml, cookies -> isBrowserSessionReady(pageHtml, cookies) }
                )
            }
            if (!promoteClearanceCookies(url)) {
                throw Exception("FilmyOnline clearance cookie was not established")
            }

            delay(500)
            runCatching {
                val refreshed = service.getDocument(url)
                if (!requiresClearance(refreshed.outerHtml())) {
                    return refreshed
                }
            }

            Jsoup.parse(html).apply { setBaseUri(baseUrl) }
        }
    }

    override suspend fun getHome(): List<Category> {
        val bootstrapCategories = runCatching {
            val root = getBootstrapRoot(getDocument(baseUrl))
            cacheBootstrapCsrfToken(root)
            extractHomeCategories(root)
        }.getOrDefault(emptyList())

        if (bootstrapCategories.isNotEmpty()) return bootstrapCategories

        Log.d(TAG, "Bootstrap home categories were empty")
        return emptyList()
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return listOf(
                Genre(id = "/movies", name = "Filmy"),
                Genre(id = "/series", name = "Seriale")
            )
        }

        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val root = fetchApiJson(
            "$baseUrl/api/v1/search/$encodedQuery?loader=searchPage",
            referer = "$baseUrl/search/$encodedQuery"
        )

        return root.optJSONArray("results")
            .orEmptyJsonArray()
            .toTitleItems()
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val root = getBootstrapRoot(getDocument(baseUrl))
        cacheBootstrapCsrfToken(root)
        return bootstrapTitleItems(root) { channel ->
            channel.optJSONObject("config")?.optString("contentModel") == "movie"
        }.filterIsInstance<Movie>()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val root = getBootstrapRoot(getDocument(baseUrl))
        cacheBootstrapCsrfToken(root)
        return bootstrapTitleItems(root) { channel ->
            channel.optJSONObject("config")?.optString("contentModel") == "series"
        }.filterIsInstance<TvShow>()
    }

    override suspend fun getMovie(id: String): Movie {
        val parsed = parseEncodedId(id)
        val title = fetchApiJson("$baseUrl/api/v1/titles/${parsed.titleId}?loader=titlePage")
            .optJSONObject("title")
            ?: throw Exception("Unable to load FilmyOnline movie")

        return (toItem(title) as? Movie)?.copy(
            id = buildEncodedId(
                type = "movie",
                titleId = parsed.titleId,
                primaryVideoId = parsed.primaryVideoId ?: title.firstPlayableVideoId(),
                titleSlug = parsed.titleSlug ?: slugifyTitle(title.optString("name"))
            )
        ) ?: throw Exception("Unable to build FilmyOnline movie")
    }

    override suspend fun getTvShow(id: String): TvShow {
        val parsed = parseEncodedId(id)
        val titlePage = fetchApiJson("$baseUrl/api/v1/titles/${parsed.titleId}?loader=titlePage")
        val title = titlePage.optJSONObject("title")
            ?: throw Exception("Missing title data")
        val titleSlug = parsed.titleSlug ?: slugifyTitle(title.optString("name")).orEmpty()

        val seasons = titlePage.optJSONObject("seasons")
            ?.optJSONArray("data")
            ?.toSeasonObjects()
            .orEmpty()
            .sortedBy { it.optInt("number") }
            .let { seasonObjects ->
                if (seasonObjects.isEmpty()) {
                    emptyList()
                } else {
                    coroutineScope {
                        seasonObjects.map { seasonObject ->
                            async {
                                val seasonNumber = seasonObject.optInt("number").takeIf { it > 0 } ?: return@async null
                                Season(
                                    id = buildSeasonId(parsed.titleId, titleSlug, seasonNumber),
                                    number = seasonNumber,
                                    title = "Sezon $seasonNumber",
                                    poster = seasonObject.optString("poster").takeIf { it.isNotBlank() }
                                        ?: title.optString("poster").takeIf { it.isNotBlank() },
                                    episodes = getEpisodesBySeason(buildSeasonId(parsed.titleId, titleSlug, seasonNumber))
                                )
                            }
                        }.awaitAll().filterNotNull().sortedBy { it.number }
                    }
                }
            }

        return (toItem(title) as? TvShow)?.copy(
            id = buildEncodedId(
                type = "tv",
                titleId = parsed.titleId,
                primaryVideoId = parsed.primaryVideoId,
                titleSlug = parsed.titleSlug ?: slugifyTitle(title.optString("name"))
            ),
            seasons = seasons,
        ) ?: throw Exception("Unable to build FilmyOnline show")
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split("|")
        if (parts.size < 3) return emptyList()
        val titleId = parts[0].toIntOrNull() ?: return emptyList()
        val seasonNumber = parts.getOrNull(2)?.toIntOrNull() ?: return emptyList()
        val seasonPage = fetchApiJson("$baseUrl/api/v1/titles/$titleId/seasons/$seasonNumber?loader=seasonPage")
        val title = seasonPage.optJSONObject("title") ?: return emptyList()
        return seasonPage.optJSONObject("episodes")
            ?.optJSONArray("data")
            ?.toEpisodeItems(title.optString("poster").takeIf { it.isNotBlank() })
            .orEmpty()
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val normalized = when (id) {
            "/series" -> "Seriale"
            else -> "Filmy"
        }
        val root = getBootstrapRoot(getDocument(baseUrl))
        cacheBootstrapCsrfToken(root)
        val contentModel = when (id) {
            "/series" -> "series"
            else -> "movie"
        }
        return Genre(
            id = id,
            name = normalized,
            shows = bootstrapTitleItems(root) { channel ->
                channel.optJSONObject("config")?.optString("contentModel") == contentModel
            }.filterIsInstance<Show>()
        )
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(
            id = id,
            name = id,
            filmography = emptyList()
        )
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val watchId = when (videoType) {
            is Video.Type.Movie -> parseEncodedId(id).primaryVideoId
            is Video.Type.Episode -> id.toIntOrNull()
        } ?: return emptyList()

        val watchPage = fetchApiJson("$baseUrl/api/v1/watch/$watchId")
        val collected = linkedMapOf<String, Video.Server>()
        val videos = watchPage.optJSONArray("videos").orEmptyJsonArray()
        for (index in 0 until videos.length()) {
            val video = videos.optJSONObject(index) ?: continue
            val source = video.optString("src").ifBlank { continue }
            val serverName = buildString {
                append(video.optString("quality").ifBlank { "default" }.uppercase())
            val lang = video.optString("language").takeIf { it.isNotBlank() }
                if (lang != null) append(" [$lang]")
            }
            collected.putIfAbsent(source, Video.Server(id = source, name = serverName, src = source))
        }
        if (collected.isEmpty()) {
            watchPage.optJSONObject("video")?.optString("src")?.takeIf { it.isNotBlank() }?.let { source ->
                collected[source] = Video.Server(id = source, name = "default", src = source)
            }
        }
        return collected.values.toList()
    }

    private fun parsedMovieId(id: String): Int? {
        return parseEncodedId(id).primaryVideoId
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.id, server)
    }

    private suspend fun getChannelItems(url: String): List<AppAdapter.Item> {
        val root = getBootstrapRoot(getDocument(url))
        return root.optJSONObject("loaders")
            ?.optJSONObject("channelPage")
            ?.optJSONObject("channel")
            ?.optJSONObject("content")
            ?.optJSONArray("data")
            ?.toTitleItems()
            .orEmpty()
    }

    private fun getBootstrapRoot(document: Document): JSONObject {
        val html = document.outerHtml()
        val marker = "window.bootstrapData ="
        val markerIndex = html.indexOf(marker)
        if (markerIndex == -1) {
            throw Exception("Unable to find FilmyOnline bootstrap data")
        }

        val startIndex = html.indexOf('{', markerIndex + marker.length)
        if (startIndex == -1) {
            throw Exception("Unable to find FilmyOnline bootstrap JSON start")
        }

        var depth = 0
        var inString = false
        var escaped = false

        for (index in startIndex until html.length) {
            val char = html[index]

            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
                continue
            }

            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return JSONObject(html.substring(startIndex, index + 1)).also { cacheBootstrapCsrfToken(it) }
                    }
                }
            }
        }

        throw Exception("Unable to extract FilmyOnline bootstrap JSON")
    }

    private fun extractHomeCategories(root: JSONObject): List<Category> {
        val channelData = root.optJSONObject("loaders")
            ?.optJSONObject("channelPage")
            ?.optJSONObject("channel")
            ?.optJSONObject("content")
            ?.optJSONArray("data")
            ?: root.optJSONObject("channel")
                ?.optJSONObject("content")
                ?.optJSONArray("data")

        return channelData
            ?.toJsonObjectList()
            ?.mapNotNull { channel ->
                val items = channel.optJSONObject("content")
                    ?.optJSONArray("data")
                    ?.toTitleItems()
                    .orEmpty()
                    .take(20)

                if (items.isEmpty()) null else Category(
                    name = channel.optString("name").ifBlank { "FilmyOnline" },
                    list = items
                )
            }
            .orEmpty()
    }

    private suspend fun promoteClearanceCookies(sourceUrl: String, timeoutMs: Long = 5000): Boolean = withContext(Dispatchers.IO) {
        val cookieManager = CookieManager.getInstance()
        val deadline = System.currentTimeMillis() + timeoutMs
        val targets = listOf(
            sourceUrl,
            "$sourceUrl/",
            baseUrl,
            "$baseUrl/"
        ).distinct()

        while (System.currentTimeMillis() <= deadline) {
            val cookieHeader = targets.firstNotNullOfOrNull { candidate ->
                cookieManager.getCookie(candidate)?.takeIf { it.contains("cf_clearance=") }
            }
            if (!cookieHeader.isNullOrBlank() &&
                cookieHeader.contains("XSRF-TOKEN=", ignoreCase = true) &&
                cookieHeader.contains("filmy_i_seriale_online_za_darmo_session=", ignoreCase = true)
            ) {
                cookieHeader.split(";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { cookie ->
                        val rootCookie = if (cookie.contains("Path=", ignoreCase = true)) {
                            cookie
                        } else {
                            "$cookie; Path=/"
                        }
                        targets.forEach { target ->
                            cookieManager.setCookie(target, rootCookie)
                        }
                    }
                cookieManager.flush()
                FilmyOnlineCfClearanceStore.update(cookieHeader)
                return@withContext true
            }
            delay(200)
        }

        FilmyOnlineCfClearanceStore.update(null)
        false
    }

    private fun JSONArray.toTitleItems(): List<AppAdapter.Item> {
        return (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.let(::toItem)
        }.distinctBy(::itemKey)
    }

    private fun toItem(title: JSONObject): AppAdapter.Item? {
        if (title.optString("model_type") != "title") return null

        val titleId = title.optInt("id").takeIf { it > 0 } ?: return null
        val primaryVideo = title.optJSONObject("primary_video")
        val encodedId = buildEncodedId(
            type = if (title.optBoolean("is_series")) "tv" else "movie",
            titleId = titleId,
            primaryVideoId = primaryVideo?.optInt("id")?.takeIf { it > 0 },
            titleSlug = slugifyTitle(title.optString("name"))
        )

        val commonTitle = title.optString("name")
        val commonOverview = title.optString("description").takeIf { it.isNotBlank() }
        val commonReleased = title.optString("release_date").takeIf { it.isNotBlank() }
            ?: title.optString("year").takeIf { it.isNotBlank() }
        val commonPoster = title.optString("poster").takeIf { it.isNotBlank() }
        val commonBanner = title.optString("backdrop").takeIf { it.isNotBlank() }
        val commonRating = title.optDouble("rating").takeIf { it > 0.0 }
        val commonRuntime = title.optInt("runtime").takeIf { it > 0 }

        return if (title.optBoolean("is_series")) {
            TvShow(
                id = encodedId,
                title = commonTitle,
                overview = commonOverview,
                released = commonReleased,
                runtime = commonRuntime,
                rating = commonRating,
                poster = commonPoster,
                banner = commonBanner
            )
        } else {
            Movie(
                id = encodedId,
                title = commonTitle,
                overview = commonOverview,
                released = commonReleased,
                runtime = commonRuntime,
                rating = commonRating,
                poster = commonPoster,
                banner = commonBanner
            )
        }
    }

    private fun JSONArray.toGenres(): List<Genre> {
        return (0 until length()).mapNotNull { index ->
            val genre = optJSONObject(index) ?: return@mapNotNull null
            Genre(
                id = genre.optString("name").ifBlank { return@mapNotNull null },
                name = genre.optString("display_name").ifBlank { genre.optString("name") }
            )
        }
    }

    private fun JSONArray?.toRecommendationItems(): List<Show> {
        if (this == null) return emptyList()
        val mapped = mutableListOf<Show>()
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val title = item.optJSONObject("title") ?: continue
            val recommendation = toItem(title)
            when (recommendation) {
                is Movie -> mapped += recommendation
                is TvShow -> mapped += recommendation
            }
        }
        return mapped.distinctBy(::itemKey)
    }

    private fun collectTitleObjects(root: JSONObject): List<JSONObject> {
        val results = mutableListOf<JSONObject>()

        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    if (value.optString("model_type") == "title") {
                        results += value
                    }
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        walk(value.opt(keys.next()))
                    }
                }

                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        walk(value.opt(index))
                    }
                }
            }
        }

        walk(root)
        return results
    }

    private fun collectChannelObjects(root: JSONObject): List<JSONObject> {
        val results = mutableListOf<JSONObject>()

        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val type = value.optString("type")
                    val modelType = value.optString("model_type")
                    val hasTitleData = value.optJSONObject("content")
                        ?.optJSONArray("data")
                        ?.let { data ->
                            (0 until data.length()).any { index ->
                                data.optJSONObject(index)?.optString("model_type") == "title"
                            }
                        } == true

                    if ((type == "channel" || modelType == "channel") && hasTitleData) {
                        results += value
                    }

                    val keys = value.keys()
                    while (keys.hasNext()) {
                        walk(value.opt(keys.next()))
                    }
                }

                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        walk(value.opt(index))
                    }
                }
            }
        }

        walk(root)
        return results.distinctBy { it.optInt("id").toString() + ":" + it.optString("name") }
    }

    private fun JSONArray.toJsonObjectList(): List<JSONObject> {
        return (0 until length()).mapNotNull { index -> optJSONObject(index) }
    }

    private fun JSONArray?.orEmptyJsonArray(): JSONArray {
        return this ?: JSONArray()
    }

    private fun JSONArray.toSeasonObjects(): List<JSONObject> {
        return (0 until length()).mapNotNull { index -> optJSONObject(index) }
    }

    private fun JSONArray.toEpisodeItems(fallbackPoster: String?): List<Episode> {
        return (0 until length()).mapNotNull { index ->
            val episode = optJSONObject(index) ?: return@mapNotNull null
            val primaryVideoId = episode.optJSONObject("primary_video")
                ?.optInt("id")
                ?.takeIf { it > 0 }
                ?: return@mapNotNull null
            val episodeNumber = episode.optInt("episode_number").takeIf { it > 0 } ?: return@mapNotNull null

            Episode(
                id = primaryVideoId.toString(),
                number = episodeNumber,
                title = episode.optString("name").ifBlank { "Odcinek $episodeNumber" },
                released = episode.optString("release_date").takeIf { it.isNotBlank() },
                poster = episode.optString("poster").takeIf { it.isNotBlank() } ?: fallbackPoster,
                overview = episode.optString("description").takeIf { it.isNotBlank() }
            )
        }
    }

    private fun JSONObject.firstPlayableVideoId(): Int? {
        optJSONObject("primary_video")
            ?.optInt("id")
            ?.takeIf { it > 0 }
            ?.let { return it }

        val videos = optJSONArray("videos").orEmptyJsonArray()
        for (index in 0 until videos.length()) {
            val video = videos.optJSONObject(index) ?: continue
            if (video.optString("category").equals("full", ignoreCase = true) ||
                video.optString("type").equals("full", ignoreCase = true)
            ) {
                val id = video.optInt("id").takeIf { it > 0 }
                if (id != null) return id
            }
        }

        return videos.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
    }

    private fun itemKey(item: AppAdapter.Item): String {
        return when (item) {
            is Movie -> "movie:${item.id}"
            is TvShow -> "tv:${item.id}"
            is Genre -> "genre:${item.id}"
            else -> item.toString()
        }
    }

    private fun buildEncodedId(type: String, titleId: Int, primaryVideoId: Int?, titleSlug: String?): String {
        return listOf(
            type,
            titleId.toString(),
            primaryVideoId?.toString().orEmpty(),
            titleSlug.orEmpty()
        ).joinToString("|")
    }

    private fun cacheBootstrapCsrfToken(root: JSONObject) {
        val token = root.optString("csrf_token").takeIf { it.isNotBlank() } ?: return
        bootstrapCsrfToken = token
    }

    private fun buildBrowserApiRequest(url: String, referer: String = "$baseUrl/"): Request {
        val cookieHeader = currentBrowserCookieHeader()
        val xsrfToken = resolveXsrfToken(cookieHeader)

        return Request.Builder()
            .url(url)
            .header("Referer", referer)
            .header("Accept", "application/json")
            .header("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
            .apply {
                if (!cookieHeader.isNullOrBlank()) {
                    header("Cookie", cookieHeader)
                }
                if (!xsrfToken.isNullOrBlank()) {
                    header("X-XSRF-TOKEN", xsrfToken)
                }
            }
            .build()
    }

    private fun currentBrowserCookieHeader(): String? {
        val stored = FilmyOnlineCfClearanceStore.cookieHeader()
        if (!stored.isNullOrBlank()) return stored

        val cookieManager = CookieManager.getInstance()
        val targets = listOf(
            baseUrl,
            "$baseUrl/",
            "$baseUrl/api/v1/",
        )
        return targets.firstNotNullOfOrNull { candidate ->
            cookieManager.getCookie(candidate)?.takeIf { it.isNotBlank() }
        }
    }

    private fun resolveXsrfToken(cookieHeader: String?): String? {
        val fromCookies = cookieHeader
            ?.split(";")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("XSRF-TOKEN=", ignoreCase = true) }
            ?.substringAfter("=", "")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { URLDecoder.decode(it, Charsets.UTF_8.name()) }.getOrDefault(it) }

        return fromCookies ?: bootstrapCsrfToken
    }

    private fun isBrowserSessionReady(html: String, cookies: String): Boolean {
        return html.contains("window.bootstrapData =") &&
            cookies.contains("cf_clearance=", ignoreCase = true) &&
            cookies.contains("XSRF-TOKEN=", ignoreCase = true) &&
            cookies.contains("filmy_i_seriale_online_za_darmo_session=", ignoreCase = true)
    }

    private fun bootstrapChannelObjects(root: JSONObject): List<JSONObject> {
        return root.optJSONObject("loaders")
            ?.optJSONObject("channelPage")
            ?.optJSONObject("channel")
            ?.optJSONObject("content")
            ?.optJSONArray("data")
            .orEmptyJsonArray()
            .toJsonObjectList()
    }

    private fun bootstrapTitleItems(root: JSONObject, channelPredicate: (JSONObject) -> Boolean = { true }): List<AppAdapter.Item> {
        return bootstrapChannelObjects(root)
            .filter(channelPredicate)
            .flatMap { channel ->
                channel.optJSONObject("content")
                    ?.optJSONArray("data")
                    .orEmptyJsonArray()
                    .toTitleItems()
            }
            .distinctBy(::itemKey)
    }

    private suspend fun fetchSeasonEpisodes(
        titleId: Int,
        titleSlug: String,
        seasonNumber: Int,
        title: JSONObject
    ): List<Episode> {
        val root = getBootstrapRoot(getDocument(buildSeasonUrl(titleId, titleSlug, seasonNumber)))
        val seasonPage = root.optJSONObject("loaders")?.optJSONObject("seasonPage") ?: return emptyList()
        val seasonPoster = seasonPage.optJSONObject("season")?.optString("poster")
        val fallbackPoster = seasonPoster?.takeIf { it.isNotBlank() }
            ?: title.optString("poster").takeIf { it.isNotBlank() }

        return seasonPage.optJSONObject("episodes")
            ?.optJSONArray("data")
            ?.toEpisodeItems(fallbackPoster)
            .orEmpty()
            .sortedBy { it.number }
    }

    private fun buildSeasonId(titleId: Int, titleSlug: String, seasonNumber: Int): String {
        return listOf(titleId.toString(), titleSlug, seasonNumber.toString()).joinToString("|")
    }

    private fun buildTitleUrl(titleId: Int, titleSlug: String): String {
        return "$baseUrl/titles/$titleId/$titleSlug"
    }

    private fun buildSeasonUrl(titleId: Int, titleSlug: String, seasonNumber: Int): String {
        return "${buildTitleUrl(titleId, titleSlug)}/season/$seasonNumber"
    }

    private suspend fun resolveTitleSlug(parsed: ParsedId): String? {
        parsed.titleSlug?.let { return it }

        val watchId = parsed.primaryVideoId ?: return null
        val root = getBootstrapRoot(getDocument("$baseUrl/watch/$watchId"))
        val watchTitle = root.optJSONObject("loaders")
            ?.optJSONObject("watchPage")
            ?.optJSONObject("title")
            ?.optString("name")
            ?.takeIf { it.isNotBlank() }
            ?: root.optJSONObject("loaders")
                ?.optJSONObject("watchPage")
                ?.optJSONObject("video")
                ?.optJSONObject("title")
                ?.optString("name")
                ?.takeIf { it.isNotBlank() }

        return slugifyTitle(watchTitle)
    }

    private fun slugifyTitle(value: String?): String? {
        if (value.isNullOrBlank()) return null

        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

        return normalized.takeIf { it.isNotBlank() }
    }

    private fun requiresClearance(html: String): Boolean {
        return html.contains("cf-browser-verification", ignoreCase = true) ||
            html.contains("Checking your browser", ignoreCase = true) ||
            html.contains("Just a moment...", ignoreCase = true) ||
            html.contains("cloudflare", ignoreCase = true) && !html.contains("window.bootstrapData =")
    }

    private data class ParsedId(
        val type: String,
        val titleId: Int,
        val primaryVideoId: Int?,
        val titleSlug: String?
    )

    private fun parseEncodedId(id: String): ParsedId {
        val parts = id.split("|")
        return ParsedId(
            type = parts.getOrNull(0).orEmpty(),
            titleId = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            primaryVideoId = parts.getOrNull(2)?.toIntOrNull(),
            titleSlug = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
        )
    }

    private interface FilmyOnlineCcService {
        @GET
        suspend fun getDocument(@Url url: String): Document

        @GET("search")
        suspend fun search(@Query("q") query: String): Document

        companion object {
            fun build(): FilmyOnlineCcService {
                return Retrofit.Builder()
                    .baseUrl("$baseUrl/")
                    .client(NetworkClient.default.newBuilder()
                        .readTimeout(30, TimeUnit.SECONDS)
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .addInterceptor { chain ->
                            val request = chain.request()
                            val cookieHeader = FilmyOnlineCfClearanceStore.cookieHeader()
                            if (cookieHeader.isNullOrBlank() || request.header("Cookie") != null) {
                                chain.proceed(request)
                            } else {
                                chain.proceed(
                                    request.newBuilder()
                                        .header("Cookie", cookieHeader)
                                        .build()
                                )
                            }
                        }
                        .build())
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()
                    .create(FilmyOnlineCcService::class.java)
            }
        }
    }
}

private object FilmyOnlineCfClearanceStore {
    @Volatile
    private var cookieHeader: String? = null

    fun update(cookieHeader: String?) {
        this.cookieHeader = cookieHeader?.trim()?.takeIf { it.isNotBlank() }
    }

    fun cookieHeader(): String? = cookieHeader
}
