package com.streamflixreborn.streamflix.providers

import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.StreamFlixApp
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
import com.streamflixreborn.streamflix.utils.WebViewResolver
import com.streamflixreborn.streamflix.utils.ArtworkRequestHeaders
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.HttpException
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object ZaluknijProvider : Provider {

    override val name = "Zaluknij"
    override val baseUrl = "https://zaluknij.cc"
    override val logo: String
        get() = artworkUrl("$baseUrl/public/dist/images/favicon.png") ?: "$baseUrl/public/dist/images/favicon.png"
    override val language = "pl"

    private const val TAG = "ZaluknijProvider"

    private var webViewResolver: WebViewResolver? = null
    private val providerMutex = Mutex()

    private interface Service {
        @GET
        suspend fun getDocument(@Url url: String): Document
    }

    private val service = Retrofit.Builder()
        .baseUrl("$baseUrl/")
        .client(
            NetworkClient.default.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request()
                    val cookieHeader = clearanceCookieHeader(request.url.toString())
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
                .build()
        )
        .addConverterFactory(JsoupConverterFactory.create())
        .build()
        .create(Service::class.java)

    fun init(context: Context) {
        webViewResolver = WebViewResolver(context)
    }

    private fun getResolver(): WebViewResolver {
        return webViewResolver ?: WebViewResolver(StreamFlixApp.instance).also {
            webViewResolver = it
        }
    }

    override suspend fun getHome(): List<Category> {
        val document = getDocument(baseUrl)
        val categories = mutableListOf<Category>()

        document.select("h3.section-title").forEach { header ->
            val title = header.text().trim().orEmpty()
            val content = nextContentBlock(header) ?: return@forEach
            val items = parseHomeItems(header, content).take(20)
            if (items.isNotEmpty()) {
                categories.add(Category(title, items))
            }
        }

        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return listOf(
                Genre(id = "/filmy-online/", name = "Filmy"),
                Genre(id = "/seriale-online/index", name = "Seriale"),
            )
        }

        val url = buildString {
            append("$baseUrl/wyszukiwarka?phrase=${encodeQuery(query)}")
            if (page > 1) {
                append("&page=$page")
            }
        }

        return parseSearchResults(getDocument(url)).distinctBy(::itemKey)
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val url = if (page <= 1) {
            "$baseUrl/filmy-online/"
        } else {
            "$baseUrl/filmy-online/?page=$page"
        }

        return parseTiles(getDocument(url))
            .filterIsInstance<Movie>()
            .distinctBy { it.id }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val url = if (page <= 1) {
            "$baseUrl/seriale-online/index"
        } else {
            "$baseUrl/seriale-online/index?url=seriale-online%2Findex&page=$page"
        }

        return parseTiles(getDocument(url))
            .filterIsInstance<TvShow>()
            .distinctBy { it.id }
    }

    override suspend fun getMovie(id: String): Movie {
        val url = toAbsoluteUrl(id)
        return parseMovie(getDocument(url), url)
    }

    override suspend fun getTvShow(id: String): TvShow {
        val initialUrl = toAbsoluteUrl(id)
        var document = getDocument(initialUrl)
        val detailUrl = document.selectFirst("#single-poster a[href*=\"/serial-online/\"]")
            ?.attr("href")
            ?.takeIf { !it.contains("/odcinek-") }
            ?.let(::toAbsoluteUrl)
            ?: initialUrl

        if (detailUrl != initialUrl) {
            document = getDocument(detailUrl)
        }

        return parseTvShow(document, detailUrl)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val showUrl = seasonId.substringBefore("|").takeIf { it.isNotBlank() } ?: return emptyList()
        val seasonNumber = seasonId.substringAfter("|").toIntOrNull() ?: return emptyList()
        return parseTvShow(getDocument(toAbsoluteUrl(showUrl)), toAbsoluteUrl(showUrl))
            .seasons
            .firstOrNull { it.number == seasonNumber }
            ?.episodes
            .orEmpty()
            .sortedBy { it.number }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        return when {
            id.startsWith("/filmy-online") -> Genre(
                id = id,
                name = "Filmy",
                shows = getMovies(page).map { it as Show },
            )

            id.startsWith("/seriale-online") -> Genre(
                id = id,
                name = "Seriale",
                shows = getTvShows(page).map { it as Show },
            )

            else -> {
                val url = when {
                    id.startsWith("http") -> id
                    id.startsWith("/") -> "$baseUrl$id"
                    else -> "$baseUrl/$id"
                }
                val finalUrl = if (page > 1 && !url.contains("?")) "$url?page=$page" else url
                val document = getDocument(finalUrl)
                Genre(
                    id = id,
                    name = document.selectFirst(".section-header .headline-gradient")?.text()?.trim()
                        ?: document.title().substringBefore(" - ").trim().ifBlank { id },
                    shows = parseTiles(document).filterIsInstance<Show>(),
                )
            }
        }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(
            id = id,
            name = id,
            filmography = emptyList(),
        )
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val document = getDocument(toAbsoluteUrl(id))
        return document.select("div#link-list tbody tr").mapNotNull { row ->
            val link = row.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            if (href.isBlank()) return@mapNotNull null

            val cells = row.select("td")
            val host = link.selectFirst("img")?.attr("alt")
                .blankToNull()
                ?: link.text().trim()
            val version = cells.getOrNull(2)?.text()?.trim().orEmpty()
            val quality = cells.getOrNull(3)?.text()?.trim().orEmpty()
            val serverName = buildString {
                append(host.ifBlank { "Server" })
                if (version.isNotBlank()) append(" [$version]")
                if (quality.isNotBlank()) append(" [$quality]")
            }

            Video.Server(
                id = href,
                name = serverName,
                src = decodeIframeSrc(link.attr("data-iframe")).ifBlank { href },
            )
        }.distinctBy { it.id }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.src.ifBlank { server.id }, server)
    }

    private fun parseTiles(container: Element): List<AppAdapter.Item> {
        return container.select("div.tile > a[href]").mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (href.isBlank()) return@mapNotNull null

            val image = anchor.selectFirst("img")
            val title = image?.attr("alt")
                .blankToNull()
                ?: anchor.attr("title").blankToNull()
                ?: anchor.selectFirst(".info-bar .title")?.ownText().blankToNull()
                ?: anchor.text().trim()

            val poster = artworkUrl(
                image?.attr("src").blankToNull()
                    ?: image?.attr("data-src").blankToNull(),
                referer = baseUrl
            )
            val year = anchor.selectFirst(".year")?.text()?.extractYear()

            when {
                href.contains("/film/") -> Movie(
                    id = href,
                    title = title,
                    released = year,
                    poster = poster,
                    banner = poster,
                )

                href.contains("/serial-online/") -> TvShow(
                    id = href,
                    title = title,
                    poster = poster,
                    banner = poster,
                )

                else -> null
            }
        }.distinctBy(::itemKey)
    }

    private fun parseHomeItems(header: Element, container: Element): List<AppAdapter.Item> {
        return when {
            header.text().contains("Ostatnio dodane odcinki", ignoreCase = true) ||
                container.classNames().any { it.contains("episode", ignoreCase = true) } -> {
                parseHomeEpisodes(container)
            }

            else -> parseHomeMovies(container)
        }
    }

    private fun parseHomeMovies(container: Element): List<AppAdapter.Item> {
        return container.select("a[href]").mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (!href.contains("/film/")) return@mapNotNull null

            val image = anchor.selectFirst("img")
            val poster = artworkUrl(
                image?.attr("src").blankToNull()
                    ?: image?.attr("data-src").blankToNull(),
                referer = baseUrl
            )
            val title = image?.attr("alt")
                .blankToNull()
                ?: anchor.attr("title").blankToNull()
                ?: anchor.selectFirst(".title")?.text()?.trim().blankToNull()
                ?: anchor.text().trim()
            val year = anchor.selectFirst(".year")?.text()?.extractYear()

            Movie(
                id = href,
                title = title,
                released = year,
                poster = poster,
                banner = poster,
            )
        }.distinctBy(::itemKey)
    }

    private fun parseSearchResults(document: Document): List<AppAdapter.Item> {
        return document.select("#advanced-search a.item[href]").mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (href.isBlank()) return@mapNotNull null

            val image = anchor.selectFirst("img")
            val poster = artworkUrl(
                image?.attr("src").blankToNull()
                    ?: image?.attr("data-src").blankToNull(),
                referer = baseUrl
            )
            val title = image?.attr("alt")
                .blankToNull()
                ?: anchor.attr("title").blankToNull()
                ?: anchor.selectFirst(".title")?.text()?.trim().blankToNull()
                ?: anchor.text().trim()
            val overview = anchor.selectFirst(".description")?.text()?.trim().blankToNull()
            val year = anchor.attr("title").extractYear()

            when {
                href.contains("/film/") -> Movie(
                    id = href,
                    title = title,
                    overview = overview,
                    released = year,
                    poster = poster,
                    banner = poster,
                )

                href.contains("/serial-online/") -> TvShow(
                    id = href,
                    title = title,
                    overview = overview,
                    poster = poster,
                    banner = poster,
                )

                else -> null
            }
        }.distinctBy(::itemKey)
    }

    private fun parseHomeEpisodes(container: Element): List<AppAdapter.Item> {
        return container.select("a.list-group-item[href*=\"/serial-online/\"]").mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (href.isBlank()) return@mapNotNull null

            val epCode = anchor.selectFirst(".ep-code")?.text().orEmpty()
            val episodeNumber = epCode.extractEpisodeNumber() ?: href.extractEpisodeNumberFromUrl()
                ?: return@mapNotNull null
            val seasonNumber = epCode.extractSeasonNumber() ?: 1
            val showId = href.substringBeforeLast("/").substringBeforeLast("/")
            val title = anchor.selectFirst(".ep-title")?.text()?.trim()
                ?: anchor.attr("title").blankToNull()
                ?: anchor.text().trim()
            val badge = anchor.selectFirst(".badge")?.text()?.trim()
            val poster = anchor.selectFirst("img")?.let { image ->
                artworkUrl(
                    image.attr("src").blankToNull()
                        ?: image.attr("data-src").blankToNull(),
                    referer = baseUrl
                )
            }

            TvShow(
                id = href,
                title = title,
                overview = badge,
                poster = poster,
                banner = poster,
                seasons = listOf(
                    Season(
                        id = showId,
                        number = seasonNumber,
                        title = "Sezon $seasonNumber",
                        poster = poster,
                        episodes = listOf(
                            Episode(
                                id = href,
                                number = episodeNumber,
                                title = title,
                                overview = badge,
                                poster = poster,
                            )
                        )
                    )
                ),
            )
        }.distinctBy(::itemKey)
    }

    private fun parseMovie(document: Document, id: String): Movie {
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore(" - ").trim()
        val poster = artworkUrl(
            document.selectFirst("meta[property=og:image]")?.attr("content").blankToNull()
                ?: document.selectFirst("#single-poster img")?.attr("src").blankToNull(),
            referer = id
        )
        val description = document.selectFirst("p.description")?.text()?.trim()
        val overview = description
            ?.substringAfter(" - ")
            ?.trim()
            ?.ifBlank { description }

        return Movie(
            id = id,
            title = title,
            overview = overview,
            released = title.extractYear(),
            poster = poster,
            banner = poster,
        )
    }

    private fun parseTvShow(document: Document, id: String): TvShow {
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("h2")?.text()?.trim()
            ?: document.title().substringBefore(" - ").trim()
        val poster = artworkUrl(
            document.selectFirst("meta[property=og:image]")?.attr("content").blankToNull()
                ?: document.selectFirst("#single-poster img")?.attr("src").blankToNull(),
            referer = id
        )
        val overview = document.selectFirst("p.description")?.text()?.trim()?.ifBlank { null }
        val seasons = document.select("ul#episode-list > li").mapNotNull { seasonElement ->
            parseSeason(seasonElement, id, poster)
        }.sortedBy { it.number }

        return TvShow(
            id = id,
            title = title,
            overview = overview,
            poster = poster,
            banner = poster,
            seasons = seasons,
        )
    }

    private fun parseSeason(seasonElement: Element, showUrl: String, poster: String?): Season? {
        val seasonLabel = seasonElement.selectFirst("> span")?.text()?.trim().orEmpty()
        val seasonNumber = seasonLabel.extractSeasonNumber() ?: return null
        val episodes = seasonElement.select("ul > li > a[href*=\"/odcinek-\"]").mapNotNull { anchor ->
            parseEpisode(anchor, poster)
        }.sortedBy { it.number }

        return Season(
            id = "$showUrl|$seasonNumber",
            number = seasonNumber,
            title = seasonLabel.ifBlank { "Sezon $seasonNumber" },
            poster = poster,
            episodes = episodes,
        )
    }

    private fun parseEpisode(anchor: Element, poster: String?): Episode? {
        val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
        if (href.isBlank()) return null

        val episodeNumber = anchor.text().extractEpisodeNumber() ?: return null
        val title = anchor.text().substringAfter("] ").trim().ifBlank { "Odcinek $episodeNumber" }

        return Episode(
            id = href,
            number = episodeNumber,
            title = title,
            poster = poster,
        )
    }

    private fun decodeIframeSrc(encoded: String): String {
        if (encoded.isBlank()) return ""

        return try {
            val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8).trim()
            JSONObject(decoded).optString("src").ifBlank {
                if (decoded.startsWith("http")) decoded else ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun nextContentBlock(header: Element): Element? {
        var sibling = header.nextElementSibling()
        while (sibling != null) {
            if (sibling.hasClass("row") || sibling.hasClass("list-group")) return sibling
            sibling = sibling.nextElementSibling()
        }
        return null
    }

    private fun itemKey(item: AppAdapter.Item): String {
        return when (item) {
            is Movie -> "movie:${item.id}"
            is TvShow -> "tv:${item.id}"
            is Genre -> "genre:${item.id}"
            else -> item.toString()
        }
    }

    private fun String.extractYear(): String? {
        return Regex("""\b(19|20)\d{2}\b""").find(this)?.value
    }

    private fun String.extractSeasonNumber(): Int? {
        return Regex("""\b(?:Sezon|S)\s*0*(\d+)\b""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun String.extractEpisodeNumber(): Int? {
        return Regex("""\b(?:Odcinek|E)\s*0*(\d+)\b""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""e0*(\d+)\b""", RegexOption.IGNORE_CASE)
                .find(this)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun String.extractEpisodeNumberFromUrl(): Int? {
        return Regex("""/odcinek-(\d+)""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun String?.blankToNull(): String? {
        return this?.trim()?.takeIf { it.isNotBlank() }
    }

    private suspend fun getDocument(url: String): Document {
        return try {
            val document = service.getDocument(url)
            val html = document.outerHtml()
            if (requiresClearance(html)) {
                throw IllegalStateException("Cloudflare clearance required")
            }
            document
        } catch (e: HttpException) {
            if (e.code() != 403 && e.code() != 503) {
                throw e
            }
            Log.d(TAG, "Resolving clearance with WebView for $url: HTTP ${e.code()}")
            val html = providerMutex.withLock { getResolver().get(url) }
            promoteClearanceCookies(url)
            org.jsoup.Jsoup.parse(html).apply { setBaseUri(url) }
        } catch (e: Exception) {
            if (!looksLikeClearanceFailure(e)) {
                throw e
            }
            Log.d(TAG, "Resolving clearance with WebView for $url: ${e.message}")
            val html = providerMutex.withLock { getResolver().get(url) }
            promoteClearanceCookies(url)
            org.jsoup.Jsoup.parse(html).apply { setBaseUri(url) }
        }
    }

    private fun looksLikeClearanceFailure(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("Cloudflare clearance required", ignoreCase = true) ||
            message.contains("Just a moment", ignoreCase = true) ||
            message.contains("Checking your browser", ignoreCase = true) ||
            message.contains("cf-browser-verification", ignoreCase = true)
    }

    private fun requiresClearance(html: String): Boolean {
        return html.contains("cf-browser-verification", ignoreCase = true) ||
            html.contains("Checking your browser", ignoreCase = true) ||
            html.contains("Just a moment...", ignoreCase = true)
    }

    private fun promoteClearanceCookies(sourceUrl: String) {
        val cookieManager = CookieManager.getInstance()
        val cookieHeader = listOf(
            sourceUrl,
            baseUrl,
            "$baseUrl/"
        ).firstNotNullOfOrNull { candidate ->
            cookieManager.getCookie(candidate)?.takeIf { it.isNotBlank() }
        }.orEmpty()

        if (cookieHeader.isBlank()) {
            return
        }

        cookieHeader.split(";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { cookie ->
                val rootCookie = if (cookie.contains("Path=", ignoreCase = true)) cookie else "$cookie; Path=/"
                listOf(sourceUrl, baseUrl, "$baseUrl/").distinct().forEach { target ->
                    cookieManager.setCookie(target, rootCookie)
                }
            }

        cookieManager.flush()
    }

    private fun clearanceCookieHeader(requestUrl: String): String? {
        val cookieManager = CookieManager.getInstance()
        return listOf(
            requestUrl,
            baseUrl,
            "$baseUrl/"
        ).firstNotNullOfOrNull { candidate ->
            cookieManager.getCookie(candidate)?.takeIf { it.isNotBlank() }
        }
    }

    private fun artworkUrl(url: String?, referer: String = baseUrl): String? {
        val image = url?.trim().orEmpty()
        if (image.isBlank()) return null

        return ArtworkRequestHeaders.withHeaders(
            url = image,
            referer = referer,
            userAgent = NetworkClient.USER_AGENT,
            cookie = clearanceCookieHeader(referer),
        )
    }

    private fun encodeQuery(query: String): String {
        return URLEncoder.encode(query, Charsets.UTF_8.name())
    }

    private fun toAbsoluteUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("/") -> "$baseUrl$url"
            else -> "$baseUrl/$url"
        }
    }
}
