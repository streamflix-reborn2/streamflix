package com.streamflixreborn.streamflix.providers

import android.util.Base64
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
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url

object JKAnimeProvider : Provider {

    override val name = "JKAnime"
    override val baseUrl = "https://jkanime.net"
    override val language = "es"
    override val logo = "https://cdn.jkdesa.com/assets3/css/img/jkanimenet.png"

    private const val EPISODES_PER_PAGE = 16

    private val service = Retrofit.Builder()
        .baseUrl("$baseUrl/")
        .addConverterFactory(JsoupConverterFactory.create())
        .client(NetworkClient.default)
        .build()
        .create(JKAnimeService::class.java)

    private val sessionMutex = Mutex()

    @Volatile
    private var csrfToken: String? = null

    private interface JKAnimeService {
        @GET
        suspend fun getPage(@Url url: String): Document

        @FormUrlEncoded
        @POST
        @Headers(
            "X-Requested-With: XMLHttpRequest",
            "Accept: application/json, text/javascript, */*; q=0.01",
        )
        suspend fun post(
            @Url url: String,
            @Field("_token") token: String,
            @Field("q") query: String? = null,
        ): Response<ResponseBody>
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val home = async { runCatching { getPage(baseUrl) }.getOrNull() }
        val series = async { runCatching { getTvShows(1) }.getOrDefault(emptyList()) }
        val movies = async { runCatching { getMovies(1) }.getOrDefault(emptyList()) }

        buildList {
            val homeDocument = home.await()
            homeDocument?.let(::parseFeatured)?.takeIf { it.isNotEmpty() }?.let {
                add(Category(Category.FEATURED, it))
            }
            homeDocument?.let(::parseLatestEpisodes)?.takeIf { it.isNotEmpty() }?.let {
                add(Category("Últimos episodios", it))
            }
            series.await().take(20).takeIf { it.isNotEmpty() }?.let {
                add(Category("Series recientes", it))
            }
            movies.await().take(20).takeIf { it.isNotEmpty() }?.let {
                add(Category("Películas recientes", it))
            }
            homeDocument?.let(::parseTopAnime)?.takeIf { it.isNotEmpty() }?.let {
                add(Category("Top animes", it))
            }
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return DIRECTORY_CATEGORIES + GENRES
        if (page > 1) return emptyList()

        val response = postJson("$baseUrl/ajax_search", query)
        val results = JSONArray(response)
        return buildList {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val slug = item.optString("slug").trim()
                val title = item.optString("title").trim()
                if (slug.isBlank() || title.isBlank()) continue

                val poster = normalizePosterUrl(
                    item.optString("image").takeIf { it.isNotBlank() }
                        ?: item.optString("thumbnail").takeIf { it.isNotBlank() }
                )
                if (item.optString("type").equals("Pelicula", ignoreCase = true)) {
                    add(Movie(id = slug, title = title, poster = poster))
                } else {
                    add(TvShow(id = slug, title = title, poster = poster))
                }
            }
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return parseDirectory(getDirectoryPage(page, type = "peliculas"))
            .filterIsInstance<Movie>()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return parseDirectory(getDirectoryPage(page, type = "animes"))
            .filterIsInstance<TvShow>()
    }

    override suspend fun getMovie(id: String): Movie {
        val slug = normalizeSlug(id)
        val document = getPage(animeUrl(slug))
        val details = parseDetails(document, slug)
        return Movie(
            id = slug,
            title = details.title,
            overview = details.overview,
            runtime = details.runtime,
            quality = details.quality,
            poster = details.poster,
            banner = details.poster,
            genres = details.genres,
            recommendations = details.recommendations,
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val slug = normalizeSlug(id)
        val document = getPage(animeUrl(slug))
        val details = parseDetails(document, slug)
        val episodePage = getEpisodePage(details.numericId, 1)
        val pageCount = episodePage.optInt("last_page", 1).coerceAtLeast(1)
        val episodeCount = episodePage.optInt("total", episodePage.optJSONArray("data")?.length() ?: 0)
        val seasons = (1..pageCount).map { page ->
            val first = ((page - 1) * EPISODES_PER_PAGE) + 1
            val last = minOf(page * EPISODES_PER_PAGE, episodeCount).coerceAtLeast(first)
            Season(
                id = "${details.numericId}|$slug|$page",
                number = page,
                title = if (pageCount == 1) "Episodios" else "Episodios $first - $last",
                poster = details.poster,
            )
        }

        return TvShow(
            id = slug,
            title = details.title,
            overview = details.overview,
            runtime = details.runtime,
            quality = details.quality,
            poster = details.poster,
            banner = details.poster,
            seasons = seasons,
            genres = details.genres,
            recommendations = details.recommendations,
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split('|')
        require(parts.size == 3) { "Invalid JKAnime season id: $seasonId" }
        val animeId = parts[0]
        val slug = parts[1]
        val page = parts[2].toIntOrNull() ?: 1
        return parseEpisodes(getEpisodePage(animeId, page), slug)
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val directoryFilter = DIRECTORY_FILTER_REGEX.matchEntire(id)
        val filterKind = directoryFilter?.groupValues?.getOrNull(1)
        val filterValue = directoryFilter?.groupValues?.getOrNull(2)
        val document = when (filterKind) {
            "type" -> getDirectoryPage(page, type = filterValue)
            "category" -> getDirectoryPage(page, category = filterValue)
            else -> getDirectoryPage(page, genre = id)
        }
        return Genre(
            id = id,
            name = DIRECTORY_CATEGORIES.firstOrNull { it.id == id }?.name
                ?: GENRES.firstOrNull { it.id == id }?.name
                ?: id.replace('-', ' ').replaceFirstChar { it.uppercase() },
            shows = parseDirectory(document).mapNotNull { it as? Show },
        )
    }

    override suspend fun getPeople(id: String, page: Int): People {
        throw UnsupportedOperationException("JKAnime does not expose people pages")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val episodeUrl = if (EPISODE_URL_REGEX.containsMatchIn(id)) {
            if (id.startsWith("http", ignoreCase = true)) id else "$baseUrl/${id.trimStart('/')}"
        } else {
            val slug = normalizeSlug(id)
            val details = parseDetails(getPage(animeUrl(slug)), slug)
            val firstEpisode = parseEpisodes(getEpisodePage(details.numericId, 1), slug).firstOrNull()
            firstEpisode?.id ?: "$baseUrl/$slug/1/"
        }

        val document = getPage(episodeUrl)
        val servers = linkedMapOf<String, Video.Server>()

        document.select("script").forEach { script ->
            val source = script.data().ifBlank { script.html() }

            VIDEO_IFRAME_REGEX.findAll(source).forEach { match ->
                val index = match.groupValues[1]
                val url = decodeHtml(match.groupValues[2])
                if (url.startsWith("http")) {
                    servers.putIfAbsent(url, Video.Server(id = url, name = "JKPlayer $index", src = url))
                }
            }

            val serverJson = extractJsonArray(source, "var servers") ?: return@forEach
            val array = runCatching { JSONArray(serverJson) }.getOrNull() ?: return@forEach
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("server").trim()
                if (SUPPORTED_SERVER_NAMES.none { it.equals(name, ignoreCase = true) }) continue

                val url = decodeBase64(item.optString("remote")) ?: continue
                servers.putIfAbsent(url, Video.Server(id = url, name = name, src = url))
            }
        }

        return servers.values.sortedBy { it.name.startsWith("JKPlayer") }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.id, server)
    }

    private suspend fun getPage(url: String): Document {
        return service.getPage(url).also(::updateCsrfToken)
    }

    private suspend fun getDirectoryPage(
        page: Int,
        type: String? = null,
        genre: String? = null,
        category: String? = null,
    ): Document {
        val url = "$baseUrl/directorio".toHttpUrl().newBuilder()
            .apply {
                type?.let { addQueryParameter("tipo", it) }
                genre?.let { addQueryParameter("genero", it) }
                category?.let { addQueryParameter("categoria", it) }
                addQueryParameter("p", page.coerceAtLeast(1).toString())
            }
            .build()
            .toString()
        return getPage(url)
    }

    private suspend fun getEpisodePage(animeId: String, page: Int): JSONObject {
        val response = postJson("$baseUrl/ajax/episodes/$animeId/${page.coerceAtLeast(1)}")
        return JSONObject(response)
    }

    private suspend fun postJson(url: String, query: String? = null): String {
        var token = ensureCsrfToken()
        var response = service.post(url, token, query)
        if (response.code() == 419) {
            token = refreshCsrfToken()
            response = service.post(url, token, query)
        }
        if (!response.isSuccessful) {
            throw IllegalStateException("JKAnime request failed (${response.code()}) for $url")
        }
        return response.body()?.string().orEmpty()
    }

    private suspend fun ensureCsrfToken(): String {
        csrfToken?.takeIf { it.isNotBlank() }?.let { return it }
        return sessionMutex.withLock {
            csrfToken?.takeIf { it.isNotBlank() } ?: refreshCsrfTokenUnlocked()
        }
    }

    private suspend fun refreshCsrfToken(): String = sessionMutex.withLock {
        refreshCsrfTokenUnlocked()
    }

    private suspend fun refreshCsrfTokenUnlocked(): String {
        val document = service.getPage(baseUrl)
        updateCsrfToken(document)
        return csrfToken ?: throw IllegalStateException("JKAnime CSRF token was not found")
    }

    private fun updateCsrfToken(document: Document) {
        document.selectFirst("meta[name=csrf-token]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { csrfToken = it }
    }

    private fun parseDirectory(document: Document): List<AppAdapter.Item> {
        val json = document.select("script")
            .firstNotNullOfOrNull { script ->
                extractJsonObject(script.data().ifBlank { script.html() }, "var animes")
            }
            ?: return emptyList()
        val data = runCatching { JSONObject(json).optJSONArray("data") }.getOrNull()
            ?: return emptyList()

        return buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val slug = item.optString("slug").trim()
                val title = item.optString("title").trim()
                if (slug.isBlank() || title.isBlank()) continue

                val poster = item.optString("image").takeIf { it.isNotBlank() }
                val overview = item.optString("synopsis").takeIf {
                    it.isNotBlank() && !it.equals("falta sinopsis", ignoreCase = true)
                }
                if (item.optString("type").equals("Movie", ignoreCase = true) ||
                    item.optString("tipo").equals("Pelicula", ignoreCase = true)
                ) {
                    add(Movie(id = slug, title = title, overview = overview, poster = poster))
                } else {
                    add(TvShow(id = slug, title = title, overview = overview, poster = poster))
                }
            }
        }
    }

    private fun parseFeatured(document: Document): List<Show> {
        return document.select("section.hero .hero__slider .hero__items").mapNotNull { item ->
            val detailsLink = item.select("a.slider-show[href]")
                .firstOrNull { !EPISODE_URL_REGEX.containsMatchIn(it.attr("href")) }
                ?: item.selectFirst("a.slider-show[href]")
                ?: return@mapNotNull null
            val slug = normalizeSlug(detailsLink.attr("href")).replace(Regex("/\\d+$"), "")
            val title = item.selectFirst(".hero__text h2")?.text()?.trim().orEmpty()
            if (slug.isBlank() || title.isBlank()) return@mapNotNull null

            val overview = item.selectFirst(".hero__text p")?.text()?.trim()?.takeIf { it.isNotBlank() }
            val banner = item.attr("data-setbg").takeIf { it.isNotBlank() }
            val type = item.selectFirst(".hero__text .ainfo span")?.text().orEmpty()
            if (type.contains("Pelicula", ignoreCase = true)) {
                Movie(id = slug, title = title, overview = overview, banner = banner)
            } else {
                TvShow(id = slug, title = title, overview = overview, banner = banner)
            }
        }.distinctBy(::showKey)
    }

    private fun parseLatestEpisodes(document: Document): List<TvShow> {
        return document.select(".dir1 .card").mapNotNull { card ->
            val link = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            val slug = normalizeSlug(href).replace(Regex("/\\d+$"), "")
            val title = card.selectFirst(".card-title")?.text()?.trim().orEmpty()
            if (slug.isBlank() || title.isBlank()) return@mapNotNull null

            val image = card.selectFirst("img")
            TvShow(
                id = slug,
                title = title,
                poster = image?.attr("data-animepic")?.takeIf { it.isNotBlank() }
                    ?: image?.attr("src")?.takeIf { it.isNotBlank() },
            )
        }.distinctBy { it.id }.take(24)
    }

    private fun parseTopAnime(document: Document): List<TvShow> {
        return document.select(".toplist .card").mapNotNull { card ->
            val link = card.selectFirst("a[href]") ?: return@mapNotNull null
            val slug = normalizeSlug(link.attr("href"))
            val title = card.selectFirst(".card-title")?.text()?.trim().orEmpty()
            if (slug.isBlank() || title.isBlank()) return@mapNotNull null

            TvShow(
                id = slug,
                title = title,
                overview = card.selectFirst(".card-synopsis")?.text()?.trim()?.takeIf { it.isNotBlank() },
                poster = card.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() },
            )
        }.distinctBy { it.id }
    }

    private fun parseDetails(document: Document, slug: String): AnimeDetails {
        val info = document.selectFirst(".anime_info")
        val metadata = document.selectFirst(".anime_data")
        val numericId = document.selectFirst(".guardar_anime[data-anime], #guardar-anime[data-anime]")
            ?.attr("data-anime")
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("JKAnime numeric id was not found for $slug")

        val genres = metadata?.select("li")
            ?.firstOrNull { it.selectFirst("span")?.text()?.trim()?.startsWith("Generos", true) == true }
            ?.select("a[href]")
            ?.map { link -> Genre(normalizeSlug(link.attr("href")).substringAfter("genero/"), link.text().trim()) }
            .orEmpty()

        val runtime = metadataValue(metadata, "Duracion")
            ?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
        val quality = metadataValue(metadata, "Calidad")
        val recommendations = document.select(".rec_bar .card, .recommendations .card")
            .mapNotNull(::parseRecommendation)
            .distinctBy { show ->
                when (show) {
                    is Movie -> "movie:${show.id}"
                    is TvShow -> "tv:${show.id}"
                }
            }

        return AnimeDetails(
            numericId = numericId,
            title = info?.selectFirst("h3")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: document.title().substringBefore(" - anime").trim(),
            overview = info?.selectFirst("p.scroll")?.text()?.trim()?.takeIf { it.isNotBlank() },
            poster = document.selectFirst(".anime_pic img, .movpic img")
                ?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[property='og:image'], meta[name='twitter:image']")
                    ?.attr("content")
                    ?.takeIf { it.isNotBlank() },
            runtime = runtime,
            quality = quality,
            genres = genres,
            recommendations = recommendations,
        )
    }

    private fun metadataValue(metadata: Element?, label: String): String? {
        val item = metadata?.select("li")?.firstOrNull {
            it.selectFirst("span")?.text()?.trim()?.startsWith(label, ignoreCase = true) == true
        } ?: return null
        val clone = item.clone()
        clone.select("span").remove()
        return clone.text().trim().takeIf { it.isNotBlank() }
    }

    private fun parseRecommendation(card: Element): Show? {
        val link = card.selectFirst("a[href]") ?: return null
        val slug = normalizeSlug(link.attr("href"))
        val title = card.selectFirst(".card-title, h5")?.text()?.trim().orEmpty()
        if (slug.isBlank() || title.isBlank()) return null
        val poster = card.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
        val type = card.selectFirst(".badge")?.text().orEmpty()
        return if (type.contains("Pelicula", ignoreCase = true)) {
            Movie(id = slug, title = title, poster = poster)
        } else {
            TvShow(id = slug, title = title, poster = poster)
        }
    }

    private fun parseEpisodes(response: JSONObject, slug: String): List<Episode> {
        val data = response.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val number = item.optInt("number", 0)
                if (number <= 0) continue
                val image = item.optString("image").takeIf { it.isNotBlank() }
                add(
                    Episode(
                        id = "$baseUrl/$slug/$number/",
                        number = number,
                        title = item.optString("title").takeIf { it.isNotBlank() }
                            ?: "Episodio $number",
                        released = item.optString("timestamp").substringBefore(' ').takeIf { it.isNotBlank() },
                        poster = image?.let { "https://cdn.jkdesa.com/assets/images/animes/video/image/$it" },
                    ),
                )
            }
        }
    }

    private fun extractJsonObject(source: String, marker: String): String? {
        return extractBalancedJson(source, marker, '{', '}')
    }

    private fun extractJsonArray(source: String, marker: String): String? {
        return extractBalancedJson(source, marker, '[', ']')
    }

    private fun extractBalancedJson(
        source: String,
        marker: String,
        opening: Char,
        closing: Char,
    ): String? {
        val markerIndex = source.indexOf(marker)
        if (markerIndex < 0) return null
        val start = source.indexOf(opening, markerIndex + marker.length)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until source.length) {
            val char = source[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                opening -> depth++
                closing -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun decodeHtml(value: String): String {
        return value.replace("&amp;", "&").replace("\\/", "/")
    }

    private fun decodeBase64(value: String): String? {
        if (value.isBlank()) return null
        return runCatching {
            String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8).trim()
        }.getOrNull()?.takeIf { it.startsWith("http") }
    }

    private fun normalizeSlug(value: String): String {
        return value.trim()
            .substringAfter("jkanime.net/", value.trim())
            .substringBefore('?')
            .trim('/')
    }

    private fun normalizePosterUrl(value: String?): String? {
        val image = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            image.contains("/animes/thumbnail/", ignoreCase = true) ->
                image.replace("/animes/thumbnail/", "/animes/image/", ignoreCase = true)
            image.startsWith("http", ignoreCase = true) -> image
            !image.contains('/') -> "https://cdn.jkdesa.com/assets/images/animes/image/$image"
            image.startsWith("/") -> "$baseUrl$image"
            else -> "$baseUrl/$image"
        }
    }

    private fun animeUrl(slug: String) = "$baseUrl/${normalizeSlug(slug)}/"

    private fun showKey(show: Show): String = when (show) {
        is Movie -> "movie:${show.id}"
        is TvShow -> "tv:${show.id}"
    }

    private data class AnimeDetails(
        val numericId: String,
        val title: String,
        val overview: String?,
        val poster: String?,
        val runtime: Int?,
        val quality: String?,
        val genres: List<Genre>,
        val recommendations: List<Show>,
    )

    private val EPISODE_URL_REGEX = Regex("/\\d+/?(?:\\?.*)?$")
    private val DIRECTORY_FILTER_REGEX = Regex("directory:(type|category):(.+)")
    private val VIDEO_IFRAME_REGEX = Regex(
        """video\[(\d+)]\s*=\s*['\"].*?src=[\"']([^\"']+)[\"']""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val SUPPORTED_SERVER_NAMES = setOf(
        "Streamwish",
        "VOE",
        "Vidhide",
        "Filemoon",
        "Mixdrop",
        "Mp4upload",
        "Streamtape",
        "Doodstream",
    )

    private val DIRECTORY_CATEGORIES = listOf(
        Genre("directory:type:animes", "Animes"),
        Genre("directory:type:peliculas", "Películas"),
        Genre("directory:type:especiales", "Especiales"),
        Genre("directory:type:ovas", "OVAs"),
        Genre("directory:type:onas", "ONAs"),
        Genre("directory:category:donghua", "Donghua"),
        Genre("directory:category:latino", "Categoria Español latino"),
    )

    private val GENRES = listOf(
        Genre("accion", "Acción"),
        Genre("aventura", "Aventura"),
        Genre("comedia", "Comedia"),
        Genre("drama", "Drama"),
        Genre("fantasia", "Fantasía"),
        Genre("misterio", "Misterio"),
        Genre("romance", "Romance"),
        Genre("sci-fi", "Ciencia ficción"),
        Genre("shoujo", "Shoujo"),
        Genre("shounen", "Shounen"),
        Genre("seinen", "Seinen"),
        Genre("sobrenatural", "Sobrenatural"),
        Genre("deportes", "Deportes"),
        Genre("terror", "Terror"),
        Genre("latino", "Género Español latino"),
        Genre("isekai", "Isekai"),
    )
}
