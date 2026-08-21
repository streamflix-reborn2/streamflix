package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import android.util.Base64
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import retrofit2.http.Body
import retrofit2.http.POST
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object AnimeSaturnProvider : Provider {
    override val name = "AnimeSaturn"
    override val baseUrl = "https://www.animesaturn.net"

    override val logo = "https://www.animesaturn.net/assets/img/saturn.png"
    override val language = "it"

    private const val USER_AGENT = "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private interface AnimeSaturnService {
        companion object {
            fun build(baseUrl: String): AnimeSaturnService {
                val clientBuilder = OkHttpClient.Builder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)

                val client = clientBuilder.dns(DnsResolver.doh).build()

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                    .create(AnimeSaturnService::class.java)
            }
        }

        @Headers(USER_AGENT)
        @GET(".")
        suspend fun getHome(): Document

        @Headers(USER_AGENT)
        @GET("anime/{id}")
        suspend fun getAnime(@Path("id") id: String): Document

        @Headers(USER_AGENT)
        @GET
        suspend fun getEpisodeByUrl(@Url url: String): ResponseBody

        /** Fetches an `/embed/<id>` page; a Referer is required by the site. */
        @Headers(USER_AGENT)
        @GET
        suspend fun getEmbedPage(@Url url: String, @Header("Referer") referer: String): ResponseBody

        /** Fetches an `/embed/<id>/playlist` XHR response; needs Referer + XHR headers. */
        @Headers(
            USER_AGENT,
            "Accept: application/json, text/plain, */*",
            "X-Requested-With: XMLHttpRequest"
        )
        @GET
        suspend fun getPlaylist(@Url url: String, @Header("Referer") referer: String): ResponseBody

        @Headers(USER_AGENT)
        @GET("genres")
        suspend fun getGenres(): Document

        // /filter?key=<query>&categories=<id>
        @Headers(USER_AGENT)
        @GET("filter")
        suspend fun getFilter(
            @Query("key") key: String? = null,
            @Query("categories") categories: String? = null
        ): Document

        // /filter/<page>?key=<query>&categories=<id>
        @Headers(USER_AGENT)
        @GET("filter/{page}")
        suspend fun getFilterPage(
            @Path("page") page: Int,
            @Query("key") key: String? = null,
            @Query("categories") categories: String? = null
        ): Document
    }

    private interface KitsuService {
        @POST("graphql")
        suspend fun getEpisodes(@Body body: okhttp3.RequestBody): okhttp3.ResponseBody
    }

    private val service = AnimeSaturnService.build(baseUrl)

    private val kitsuService by lazy {
        val client = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl("https://kitsu.io/api/")
            .client(client)
            .build()
            .create(KitsuService::class.java)
    }

    private data class EpisodeExtra(
        val thumbnail: String?,
        val title: String?
    )

    private suspend fun fetchEpisodeThumbnails(anilistId: Int): Map<Int, EpisodeExtra> {
        return try {
            val query = """
                query {
                  lookupMapping(externalId: $anilistId, externalSite: ANILIST_ANIME) {
                    __typename
                    ... on Anime {
                      id
                      episodes(first: 2000) {
                        nodes {
                          number
                          titles { canonical }
                          thumbnail {
                            original {
                              url
                            }
                          }
                        }
                      }
                    }
                  }
                }
            """.trimIndent()

            val requestBody = JSONObject().apply {
                put("query", query)
            }.toString().toRequestBody("application/json".toMediaType())

            val response = kitsuService.getEpisodes(requestBody)
            val responseString = response.string()
            val jsonResponse = JSONObject(responseString)

            val episodes = jsonResponse
                .optJSONObject("data")
                ?.optJSONObject("lookupMapping")
                ?.optJSONObject("episodes")
                ?.optJSONArray("nodes")
                ?: return emptyMap()

            val extras = mutableMapOf<Int, EpisodeExtra>()
            for (i in 0 until episodes.length()) {
                try {
                    val episode = episodes.optJSONObject(i) ?: continue
                    val number = episode.optInt("number", 0)
                    val thumbnail = episode
                        .optJSONObject("thumbnail")
                        ?.optJSONObject("original")
                        ?.optString("url", "")
                        ?: ""
                    val canonicalTitle = episode
                        .optJSONObject("titles")
                        ?.optString("canonical", "")
                        ?: ""

                    if (number > 0) {
                        extras[number] = EpisodeExtra(
                            thumbnail = thumbnail.takeIf { it.isNotEmpty() },
                            title = canonicalTitle.takeIf { it.isNotEmpty() }
                        )
                    }
                } catch (e: Exception) {
                    continue
                }
            }

            extras
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // -----------------------
    // HELPERS
    // -----------------------

    /** `/anime/<slug>` and `/episode/<slug>/ep-N` both identify a show by `<slug>`. */
    private fun slugFromHref(href: String): String? {
        return Regex("/(?:anime|episode)/([^/?#]+)").find(href)?.groupValues?.get(1)
    }

    /**
     * Episode links on the detail page point at `/episode/<slug>/ep-N`, which is
     * only an interstitial landing page. The actual player is `/anime/<slug>/ep-N`,
     * so episode ids are always stored/fetched in that form.
     */
    private fun toPlayerPath(href: String): String {
        return href.replaceFirst(Regex("^(?:https?://[^/]+)?/episode/"), "/anime/")
    }

    /** Reads the value next to a label in the detail page's `.ag-meta` box. */
    private fun metaValue(document: Document, label: String): String {
        val container = document.selectFirst(".ag-meta") ?: return ""
        val row = container.children().firstOrNull { row ->
            row.children().firstOrNull()?.text()?.trim()?.equals(label, ignoreCase = true) == true
        }
        return row?.children()?.lastOrNull()?.text()?.trim() ?: ""
    }

    // -----------------------
    // SATURN EMBED PROTOCOL
    //
    // The player page hands Alpine a JSON blob (`x-data="watchPage({...})"`)
    // describing the episode's servers. Each internal server points at an
    // `/embed/<id>` page whose real source is fetched from
    // `/embed/<id>/playlist` and returned base64-encoded, XOR'd against the
    // same token that authorises the request.
    // -----------------------

    /** Pulls the `x-data="watchPage({...})"` blob out of a player page. */
    private fun parseWatchPageData(html: String): JSONObject? {
        val document = Jsoup.parse(html)

        val raw = document.select("[x-data]")
            .map { it.attr("x-data") }
            .firstOrNull { it.trimStart().startsWith("watchPage(") }
            ?: return null

        val start = raw.indexOf("(")
        val end = raw.lastIndexOf(")")
        if (start == -1 || end <= start) return null

        return try {
            JSONObject(raw.substring(start + 1, end))
        } catch (e: Exception) {
            null
        }
    }

    /** True for the internal `play.<site>/embed/<id>` links this extractor handles. */
    private fun isSaturnEmbed(link: String?): Boolean {
        if (link.isNullOrEmpty()) return false
        return try {
            Regex("/embed/\\d+").containsMatchIn(link.toHttpUrl().encodedPath)
        } catch (e: Exception) {
            false
        }
    }

    /** base64 → XOR against the repeating token, matching the embed page's `dec()`. */
    private fun decodeEmbedPayload(payload: String?, key: String): String {
        if (payload.isNullOrEmpty()) return ""

        val k = key.ifEmpty { "as" }
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        val out = ByteArray(bytes.size)

        for (i in bytes.indices) {
            out[i] = (bytes[i].toInt() xor k[i % k.length].code).toByte()
        }

        return String(out, Charsets.ISO_8859_1)
    }

    /**
     * Prefers the `window.__E={i,k,e}` blob on the embed page (authoritative,
     * and survives the query string being reshaped), falling back to the
     * embed URL's own path/query.
     */
    private suspend fun readEmbedParams(embedUrl: String, referer: String): Triple<String, String, String> {
        val url = embedUrl.toHttpUrl()
        val fallbackId = Regex("/embed/(\\d+)").find(url.encodedPath)?.groupValues?.get(1) ?: ""
        val fallbackToken = url.queryParameter("token") ?: ""
        val fallbackExpires = url.queryParameter("expires") ?: ""

        try {
            val html = service.getEmbedPage(embedUrl, referer).string()
            val match = Regex(
                "window\\.__E\\s*=\\s*\\{\\s*i\\s*:\\s*(\\d+)\\s*,\\s*k\\s*:\\s*\"([^\"]+)\"\\s*,\\s*e\\s*:\\s*(\\d+)"
            ).find(html)

            if (match != null) {
                return Triple(match.groupValues[1], match.groupValues[2], match.groupValues[3])
            }
        } catch (e: Exception) {
            // fall through to the URL-derived params
        }

        if (fallbackId.isEmpty() || fallbackToken.isEmpty()) {
            throw Exception("Saturn embed: could not read embed parameters")
        }

        return Triple(fallbackId, fallbackToken, fallbackExpires)
    }

    /**
     * Resolves an internal Saturn embed link to a playable source. The result
     * is usually a progressive MP4 rather than HLS.
     */
    private suspend fun resolveSaturnEmbed(embedUrl: String?): String {
        val trimmed = embedUrl?.trim().orEmpty()
        if (trimmed.isEmpty()) throw Exception("Saturn embed: missing server source")
        if (!isSaturnEmbed(trimmed)) {
            throw Exception("Saturn embed: not an internal embed link (${trimmed.take(80)})")
        }

        val (embedId, token, expires) = readEmbedParams(trimmed, "$baseUrl/")

        val embedHttpUrl = trimmed.toHttpUrl()
        val defaultPort = HttpUrl.defaultPort(embedHttpUrl.scheme)
        val origin = "${embedHttpUrl.scheme}://${embedHttpUrl.host}" +
                if (embedHttpUrl.port != defaultPort) ":${embedHttpUrl.port}" else ""

        val playlistUrl = "$origin/embed/${URLEncoder.encode(embedId, "UTF-8")}/playlist" +
                "?token=${URLEncoder.encode(token, "UTF-8")}&expires=${URLEncoder.encode(expires, "UTF-8")}"

        val playlistBody = service.getPlaylist(playlistUrl, trimmed).string()
        val payload = JSONObject(playlistBody).optString("d", "")

        val source = decodeEmbedPayload(payload, token)
        if (source.isEmpty()) throw Exception("Saturn embed: empty video source")
        if (source.startsWith("youtube/")) {
            throw Exception("Saturn embed: source is a YouTube embed, not a playable stream")
        }

        return source
    }

    private fun dedupeById(shows: List<TvShow>): List<TvShow> {
        val seen = mutableSetOf<String>()
        return shows.filter { seen.add(it.id) }
    }

    private suspend fun filterPage(page: Int, key: String? = null, categories: String? = null): Document {
        return if (page > 1) service.getFilterPage(page, key, categories) else service.getFilter(key, categories)
    }

    private suspend fun getGenreList(): List<Genre> {
        val document = service.getGenres()
        return document.select("a.genre-card").mapNotNull { a ->
            val id = Regex("categories=(\\d+)").find(a.attr("href"))?.groupValues?.get(1)
            val name = a.selectFirst(".genre-card__name")?.text()?.trim()
            if (id.isNullOrEmpty() || name.isNullOrEmpty()) null else Genre(id = id, name = name)
        }
    }

    // -----------------------
    // HOME
    // -----------------------

    override suspend fun getHome(): List<Category> {
        return try {
            val document = service.getHome()

            val categories = mutableListOf<Category>()

            val featuredAnimes = document.select(".hero-slide").mapNotNull { parseFeaturedAnime(it) }
            if (featuredAnimes.isNotEmpty()) {
                categories.add(Category(Category.FEATURED, featuredAnimes))
            }

            // Every rail on the home page is a <section> with an `h2.section-title`
            // heading and a set of `a.ac` cards.
            document.select("section").forEach { section ->
                val title = section.selectFirst("h2.section-title")?.text()?.trim() ?: ""
                if (title.isEmpty()) return@forEach

                val animes = section.select("a.ac").mapNotNull { parseAnimeCard(it) }

                if (animes.isNotEmpty()) {
                    categories.add(Category(title, dedupeById(animes)))
                }
            }

            categories
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseFeaturedAnime(element: Element): TvShow? {
        return try {
            val href = element.selectFirst("a.hero-btn-info")?.attr("href") ?: return null
            val animeId = slugFromHref(href) ?: return null

            val title = element.selectFirst(".hero-title")?.text()?.trim() ?: ""
            val banner = element.selectFirst("img.hero-slide__bg")?.attr("src") ?: ""

            TvShow(
                id = animeId,
                title = title,
                banner = banner
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses the `a.ac` anime card used by every grid/rail on the site (home,
     * search results, related titles).
     */
    private fun parseAnimeCard(element: Element): TvShow? {
        return try {
            val href = element.attr("href")
            val animeId = slugFromHref(href) ?: return null

            val poster = element.selectFirst(".ac__poster img")?.attr("src") ?: ""
            val title = element.selectFirst(".ac__title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: element.selectFirst(".ac__poster img")?.attr("alt")?.trim()
                ?: ""

            TvShow(
                id = animeId,
                title = title,
                poster = poster
            )
        } catch (e: Exception) {
            null
        }
    }

    // -----------------------
    // SEARCH / GENRES
    // -----------------------

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        return try {
            if (query.isBlank()) {
                if (page > 1) return emptyList()

                return getGenreList()
            }

            val document = filterPage(page, key = query.trim())

            document.select("a.ac").mapNotNull { parseAnimeCard(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val name = try {
            getGenreList().find { it.id == id }?.name ?: id
        } catch (e: Exception) {
            id
        }

        return try {
            val document = filterPage(page, categories = id)
            val shows = document.select("a.ac").mapNotNull { parseAnimeCard(it) }
            Genre(id = id, name = name, shows = shows)
        } catch (e: Exception) {
            Genre(id = id, name = name, shows = emptyList())
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return emptyList() // AnimeSaturn is TV shows only
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val document = filterPage(page)
            document.select("a.ac").mapNotNull { parseAnimeCard(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // -----------------------
    // DETAILS
    // -----------------------

    override suspend fun getTvShow(id: String): TvShow {
        return try {
            val document = service.getAnime(id)

            val title = document.selectFirst(".ag-head h1")?.text()?.trim() ?: ""
            val poster = document.selectFirst(".ag-poster img")?.attr("src") ?: ""
            val banner = document.selectFirst("img.anime-hero__bg")?.attr("src") ?: ""

            val ratingText = document.selectFirst("#anime-score")?.text()?.trim() ?: ""
            val rating = ratingText.toDoubleOrNull()

            // "04 Aprile 2026" — only the year is meaningful downstream.
            val releasedText = metaValue(document, "Data di uscita")
            val released = releasedText.split(Regex("\\s+")).firstOrNull { it.matches(Regex("\\d{4}")) } ?: ""

            // "23 min"
            val runtime = metaValue(document, "Durata").trim().takeWhile { it.isDigit() }.toIntOrNull()

            val overview = document.selectFirst(".ag-story .story-clip")?.text()?.trim() ?: ""

            val genres = document.select(".ag-genres a.chip").mapNotNull { a ->
                val genreName = a.text().trim()
                if (genreName.isEmpty()) return@mapNotNull null
                val genreId = Regex("categories=(\\d+)").find(a.attr("href"))?.groupValues?.get(1) ?: genreName
                Genre(id = genreId, name = genreName)
            }

            // Long series split their episode list into range tabs (1–50, 51–100, …);
            // each becomes a "season" so the client can page through them.
            val seasons = parseSeasons(document, id)

            val recommendations = document.select(".ag-related a.ac").mapNotNull { parseAnimeCard(it) }

            TvShow(
                id = id,
                title = title,
                poster = poster,
                banner = banner,
                overview = overview,
                rating = rating,
                released = released,
                runtime = runtime,
                seasons = seasons,
                genres = genres,
                recommendations = recommendations
            )
        } catch (e: Exception) {
            TvShow(id = id, title = "", poster = "")
        }
    }

    override suspend fun getMovie(id: String): Movie {
        throw Exception("Movies not supported")
    }

    /**
     * Range tab labels use an en dash ("1–50"); normalised to a plain hyphen so
     * a season id stays `<slug>-<from>-<to>` and round-trips through
     * `getEpisodesBySeason`.
     */
    private fun parseRangeLabels(document: Document): List<String> {
        return document.select(".ep-range-tab").map {
            it.text().trim().replace("–", "-").replace("—", "-").replace(Regex("\\s+"), "")
        }
    }

    private fun parseSeasons(document: Document, animeId: String): List<Season> {
        val ranges = parseRangeLabels(document)
        if (ranges.isEmpty()) {
            return listOf(Season(id = animeId, number = 0, title = "Episodi"))
        }
        return ranges.map { range -> Season(id = "$animeId-$range", number = 0, title = range) }
    }

    // -----------------------
    // EPISODES
    // -----------------------

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        return try {
            val animeId: String
            val targetRange: String?

            val rangePattern = Regex("-(\\d+-\\d+)$")
            val match = rangePattern.find(seasonId)

            if (match != null) {
                targetRange = match.groupValues[1]
                animeId = seasonId.substringBeforeLast("-$targetRange")
            } else {
                animeId = seasonId
                targetRange = null
            }

            val document = service.getAnime(animeId)

            val anilistLink = document.selectFirst("a[href*='anilist.co/anime/']")
            val anilistHref = anilistLink?.attr("href")

            val anilistId = anilistHref
                ?.substringAfter("/anime/", "")
                ?.trimEnd('/')
                ?.takeWhile { it.isDigit() }
                ?.toIntOrNull()

            val extrasByNumber = if (anilistId != null) {
                fetchEpisodeThumbnails(anilistId)
            } else {
                emptyMap()
            }

            fun parseTile(tile: Element, index: Int): Episode {
                val href = tile.attr("href")
                val rawText = tile.text().trim()

                // "/anime/<slug>/ep-7" — decimals ("ep-7.5") keep the site's own label.
                val rawToken = href.substringAfterLast("/ep-")
                val isDecimal = rawToken.contains(".")
                val episodeNumber = rawToken.substringBefore(".").toIntOrNull() ?: (index + 1)

                val extras = extrasByNumber[episodeNumber]
                val displayTitle = if (isDecimal) rawText else (extras?.title ?: rawText)
                val thumbnail = extras?.thumbnail

                return Episode(
                    id = toPlayerPath(href),
                    number = episodeNumber,
                    title = displayTitle,
                    poster = thumbnail
                )
            }

            val ranges = parseRangeLabels(document)

            val tiles: List<Element> = if (ranges.isNotEmpty()) {
                // Every range's tiles are already in the HTML, hidden behind an
                // Alpine `x-show="active === <index>"` panel.
                val rangeIndex = if (targetRange != null) ranges.indexOf(targetRange) else -1

                if (rangeIndex >= 0) {
                    document.select("[x-show=\"active === $rangeIndex\"]").select("a.ep-tile")
                } else {
                    document.select("a.ep-tile")
                }
            } else {
                document.select("a.ep-tile")
            }

            tiles.mapIndexed { i, tile -> parseTile(tile, i) }.sortedBy { it.number }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // -----------------------
    // SERVERS
    // -----------------------

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            // `id` is the player page path, e.g. "/anime/<slug>/ep-1"; tolerate an
            // `/episode/...` link being passed in directly.
            val playerPath = toPlayerPath(id)
            val html = service.getEpisodeByUrl(playerPath).string()
            val data = parseWatchPageData(html)

            val serversArray = data?.optJSONArray("servers")
            val resolved = mutableListOf<Video.Server>()

            if (serversArray != null) {
                for (i in 0 until serversArray.length()) {
                    val serverObj = serversArray.optJSONObject(i) ?: continue
                    val link = serverObj.optString("link", "").takeIf { it.isNotEmpty() }
                    if (!isSaturnEmbed(link)) continue

                    val slug = serverObj.optString("slug", "").takeIf { it.isNotEmpty() }
                    val serverId = slug ?: serverObj.opt("id")?.toString().orEmpty()
                    val serverName = serverObj.optString("name", "").takeIf { it.isNotEmpty() } ?: "AnimeSaturn"

                    resolved.add(
                        Video.Server(
                            id = serverId,
                            name = serverName,
                            src = link!!
                        )
                    )
                }
            }

            if (resolved.isNotEmpty()) return resolved

            // Some episodes only carry the pre-selected source.
            val initialVideoUrl = data?.optString("initialVideoUrl", "")?.takeIf { it.isNotEmpty() }
            if (isSaturnEmbed(initialVideoUrl)) {
                return listOf(
                    Video.Server(
                        id = "serverstock",
                        name = "AnimeSaturn",
                        src = initialVideoUrl!!
                    )
                )
            }

            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = "Person $id") // TODO: Implement people functionality
    }

    /** Server links point at a saturncdn `/embed/<id>` page — see resolveSaturnEmbed. */
    override suspend fun getVideo(server: Video.Server): Video {
        val source = resolveSaturnEmbed(server.src)
        return Video(
            source = source
        )
    }

}