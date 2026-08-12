package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.extractors.GenericPackedSourceExtractor
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
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object SeriesTurcasProvider : Provider {

    override val name = "Series Turcas"
    override val baseUrl = "https://tbg.seriesturcastv.to"
    override val logo = artworkUrl(
        "$baseUrl/wp-content/uploads/2021/04/favicon.png",
        "$baseUrl/home/"
    ).orEmpty()
    override val language = "es"

    private const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:152.0) Gecko/20100101 Firefox/152.0"
    private const val DOCUMENT_ACCEPT =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    private const val IMAGE_ACCEPT =
        "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"

    private interface SeriesTurcasService {
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    private data class CachedDocument(
        val document: Document,
        val timestamp: Long
    )

    private val detailPageCache = ConcurrentHashMap<String, CachedDocument>()

    private val service: SeriesTurcasService by lazy {
        val client = OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", "$baseUrl/")
                        .header("Accept", DOCUMENT_ACCEPT)
                        .header("Accept-Language", "en-US,en;q=0.5")
                        .header("Sec-Fetch-Dest", "document")
                        .header("Sec-Fetch-Mode", "navigate")
                        .header("Sec-Fetch-Site", "same-origin")
                        .header("Upgrade-Insecure-Requests", "1")
                        .build()
                )
            }
            .build()

        Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .addConverterFactory(JsoupConverterFactory.create())
            .client(client)
            .build()
            .create(SeriesTurcasService::class.java)
    }

    private val esprinahyExtractor = object : GenericPackedSourceExtractor() {
        override val name = "Esprinahy"
        override val mainUrl = "https://esprinahy.com"
        override val refererUrl = baseUrl
    }

    override suspend fun getHome(): List<Category> {
        val homeUrl = "$baseUrl/home/"
        val document = service.getPage(homeUrl)
        val categories = mutableListOf<Category>()

        val featured = document.select("#slider .swiper-wrapper > .item")
            .mapNotNull { parseFeaturedShow(it, homeUrl) }
        if (featured.isNotEmpty()) {
            categories += Category(Category.FEATURED, featured)
        }

        document.select("#body section.bl").forEach { section ->
            val title = section.selectFirst(".heading h2")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@forEach

            val items = if (
                title.contains("capitulo", ignoreCase = true) ||
                title.contains("episodio", ignoreCase = true)
            ) {
                parseLatestEpisodeShows(section.select(".filmlist > .item"), homeUrl)
            } else {
                section.select(".filmlist > .item").mapNotNull { parseShowCard(it, homeUrl) }
            }
            if (items.isNotEmpty()) {
                categories += Category(title, items)
            }
        }

        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return if (page <= 1) getSearchGenres() else emptyList()
        }

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) {
            "$baseUrl/?s=$encodedQuery"
        } else {
            "$baseUrl/page/$page/?s=$encodedQuery"
        }
        val document = try {
            service.getPage(url)
        } catch (exception: HttpException) {
            if (page > 1 && exception.code() == 404) return emptyList()
            throw exception
        }

        return document
            .select("#body .filmlist > .item")
            .mapNotNull { parseShowCard(it, url) }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> {
        if (page > 1) return emptyList()
        val url = "$baseUrl/series/"
        return service.getPage(url)
            .select("#body .filmlist > .item")
            .mapNotNull { parseShowCard(it, url) }
            .distinctBy { it.id }
    }

    override suspend fun getMovie(id: String): Movie {
        throw UnsupportedOperationException("Series Turcas is a series-only provider")
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = absoluteUrl(id)
        val document = getDetailPage(url)
        val info = document.selectFirst(".watch-extra section.info") ?: document
        val poster = artworkUrl(info.selectFirst(".poster img")?.imageSource(), url)
        val title = info.selectFirst("h1.title")?.text()?.trim().orEmpty()
        val meta = info.selectFirst(".meta.lg")
        val genres = info.select("a[href*='/generos/']").mapNotNull(::parseGenreLink).distinctBy { it.id }
        val directors = info.select(".mvici-right > div")
            .firstOrNull { it.text().trim().startsWith("Director:", ignoreCase = true) }
            ?.select("a[href]")
            ?.mapNotNull(::parsePeopleLink)
            .orEmpty()
        val cast = info.select(".casts a[href]").mapNotNull(::parsePeopleLink).distinctBy { it.id }
        val episodes = parseEpisodes(document, poster)
        val seasonTitle = document.selectFirst("#episodes .episod .glowi")?.text()?.trim()
            ?.takeIf { it.startsWith("Temporada", ignoreCase = true) }
            ?: "Temporada 1"
        val recommendations = document.select("section.bl").asSequence()
            .filter { it.selectFirst(".heading h2")?.text()?.contains("Más Series", ignoreCase = true) == true }
            .flatMap { it.select(".filmlist > .item").asSequence() }
            .mapNotNull { parseShowCard(it, url) }
            .filterNot { it.id == url }
            .distinctBy { it.id }
            .toList()

        return TvShow(
            id = url,
            title = title,
            overview = info.selectFirst(".desc")?.text()?.trim(),
            released = info.selectFirst("a[href*='/ano/']")?.text()?.trim(),
            runtime = parseRuntimeMinutes(meta?.select("span")?.firstOrNull {
                it.text().contains("min", ignoreCase = true)
            }?.text()),
            quality = meta?.selectFirst(".quality")?.text()?.trim(),
            rating = parseRating(meta?.selectFirst(".imdb")?.text()),
            poster = poster,
            banner = artworkUrl(document.selectFirst("#watch .play")?.backgroundImage(), url),
            genres = genres,
            directors = directors,
            cast = cast,
            seasons = listOf(
                Season(
                    id = url,
                    number = 1,
                    title = seasonTitle,
                    poster = poster,
                    episodes = episodes
                )
            ),
            recommendations = recommendations
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val url = absoluteUrl(seasonId)
        val document = getDetailPage(url)
        val poster = artworkUrl(document.selectFirst(".watch-extra .poster img")?.imageSource(), url)
        return parseEpisodes(document, poster)
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = pagedUrl(absoluteUrl(id), page)
        val document = service.getPage(url)
        val name = document.selectFirst("#body .heading h1")?.text()?.trim()
            ?.substringAfter(":", missingDelimiterValue = "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: id.trimEnd('/').substringAfterLast('/').replace('-', ' ')
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

        return Genre(
            id = id,
            name = name,
            shows = document.select("#body .filmlist > .item").mapNotNull { parseShowCard(it, url) }
        )
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val url = pagedUrl(absoluteUrl(id), page)
        val document = service.getPage(url)
        val name = document.selectFirst("#body .heading h1")?.text()?.trim()
            ?.substringAfter(":", missingDelimiterValue = "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: id.trimEnd('/').substringAfterLast('/').replace('-', ' ')
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

        return People(
            id = id,
            name = name,
            filmography = document.select("#body .filmlist > .item").mapNotNull { parseShowCard(it, url) }
        )
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val document = service.getPage(absoluteUrl(id))
        return document.select(".dltabsi .dl-contenti a[href]").mapIndexedNotNull { index, anchor ->
            val rawUrl = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
            if (!rawUrl.startsWith("http")) return@mapIndexedNotNull null
            val playableUrl = normalizeServerUrl(rawUrl)
            val host = playableUrl.toHttpUrlOrNull()?.host.orEmpty()
            val hostName = when {
                host.contains("vidmoly", ignoreCase = true) -> "VidMoly"
                host.contains("voe", ignoreCase = true) -> "VOE"
                host.contains("esprinahy", ignoreCase = true) -> "Esprinahy"
                else -> host.substringBefore('.').replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                }.ifBlank { "Server" }
            }

            Video.Server(
                id = playableUrl,
                name = "$hostName ${index + 1}",
                src = playableUrl
            )
        }.distinctBy { it.id }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return if (server.src.contains("esprinahy.com", ignoreCase = true)) {
            esprinahyExtractor.extract(server.src, server)
        } else {
            Extractor.extract(server.src, server)
        }
    }

    private suspend fun getSearchGenres(): List<Genre> {
        return service.getPage("$baseUrl/home/")
            .select("a[href*='/generos/']")
            .mapNotNull(::parseGenreLink)
            .distinctBy { it.id }
    }

    private suspend fun getDetailPage(url: String): Document {
        val now = System.currentTimeMillis()
        detailPageCache[url]
            ?.takeIf { now - it.timestamp < DETAIL_CACHE_DURATION_MS }
            ?.let { return it.document }

        return service.getPage(url).also { document ->
            detailPageCache[url] = CachedDocument(document, now)
        }
    }

    private fun parseFeaturedShow(element: Element, referer: String): TvShow? {
        val anchor = element.selectFirst("a.watchnow[href]") ?: return null
        val title = element.selectFirst("h3.title")?.text()?.trim() ?: return null
        val banner = artworkUrl(element.attr("data-src"), referer)
        return TvShow(
            id = absoluteUrl(anchor.attr("href")),
            title = title,
            overview = element.selectFirst(".desc")?.text()?.trim(),
            runtime = parseRuntimeMinutes(element.select(".meta span").firstOrNull {
                it.text().contains("min", ignoreCase = true)
            }?.text()),
            quality = element.selectFirst(".quality")?.text()?.trim(),
            rating = parseRating(element.selectFirst(".imdb")?.text()),
            poster = banner,
            banner = banner
        )
    }

    private fun parseShowCard(element: Element, referer: String): TvShow? {
        val anchor = element.selectFirst("a.poster[href], h3 a.title[href]") ?: return null
        val title = element.selectFirst("h3 a.title")?.text()?.trim()
            ?: anchor.attr("title").trim()
        if (title.isBlank() || title.contains(EPISODE_TITLE_REGEX)) return null

        return TvShow(
            id = absoluteUrl(anchor.attr("href")),
            title = title,
            quality = element.selectFirst(".quality")?.text()?.trim(),
            poster = artworkUrl(element.selectFirst("a.poster img")?.imageSource(), referer)
        )
    }

    private fun parseLatestEpisodeShows(
        elements: List<Element>,
        referer: String
    ): List<TvShow> {
        return elements.mapNotNull { resolveEpisodeShow(it, referer) }.distinctBy { it.id }
    }

    private fun resolveEpisodeShow(element: Element, referer: String): TvShow? {
        val anchor = element.selectFirst("a.poster[href], h3 a.title[href]") ?: return null
        val episodeUrl = absoluteUrl(anchor.attr("href"))
        val seriesUrl = episodeUrl.replace(EPISODE_URL_SUFFIX_REGEX, "/")
        if (seriesUrl == episodeUrl) return null
        val title = (element.selectFirst("h3 a.title")?.text()
            ?: anchor.attr("title"))
            .replace(EPISODE_TITLE_SUFFIX_REGEX, "")
            .trim()
        if (title.isBlank()) return null

        return TvShow(
            id = seriesUrl,
            title = title,
            quality = element.selectFirst(".quality")?.text()?.trim(),
            poster = artworkUrl(element.selectFirst("a.poster img")?.imageSource(), referer)
        )
    }

    private fun parseEpisodes(document: Document, poster: String?): List<Episode> {
        return document.select("#episodes a.episod[href]").mapNotNull { anchor ->
            val number = extractEpisodeNumber(anchor.ownText()) ?: extractEpisodeNumber(anchor.text())
                ?: return@mapNotNull null
            Episode(
                id = absoluteUrl(anchor.attr("href")),
                number = number,
                title = "Capítulo $number",
                poster = poster
            )
        }.distinctBy { it.id }.sortedBy { it.number }
    }

    private fun parseGenreLink(anchor: Element): Genre? {
        val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
        val name = anchor.text().trim().trimEnd(',')
        if (href.isBlank() || name.isBlank()) return null
        return Genre(id = absoluteUrl(href), name = name)
    }

    private fun parsePeopleLink(anchor: Element): People? {
        val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
        val name = anchor.text().trim().trimEnd(',')
        if (href.isBlank() || name.isBlank()) return null
        return People(id = absoluteUrl(href), name = name)
    }

    private fun normalizeServerUrl(url: String): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return url
        val path = httpUrl.encodedPath
        return when {
            httpUrl.host.contains("esprinahy", ignoreCase = true) && path.startsWith("/d/") ->
                httpUrl.newBuilder().encodedPath(path.replaceFirst("/d/", "/f/")).build().toString()

            httpUrl.host.contains("vidmoly", ignoreCase = true) && path.startsWith("/dl/") -> {
                val videoId = path.removePrefix("/dl/").trim('/')
                "https://vidmoly.to/embed-$videoId"
            }

            httpUrl.host.contains("voe", ignoreCase = true) && path.endsWith("/download") -> {
                val videoId = path.removeSuffix("/download").trim('/').substringAfterLast('/')
                httpUrl.newBuilder().encodedPath("/e/$videoId").query(null).build().toString()
            }

            else -> url
        }
    }

    private fun Element.backgroundImage(): String? {
        return STYLE_URL_REGEX.find(attr("style"))?.groupValues?.getOrNull(1)
    }

    private fun Element.imageSource(): String? {
        return listOf(
            attr("src"),
            attr("data-src"),
            attr("data-lazy-src"),
            attr("srcset").substringBefore(',').substringBefore(' ')
        ).firstOrNull { it.isNotBlank() }
    }

    private fun extractEpisodeNumber(value: String): Int? {
        return EPISODE_NUMBER_REGEX.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun parseRuntimeMinutes(value: String?): Int? {
        return Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
            .find(value.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun parseRating(value: String?): Double? {
        return Regex("""\d+(?:[.,]\d+)?""").find(value.orEmpty())?.value?.replace(',', '.')?.toDoubleOrNull()
    }

    private fun artworkUrl(url: String?, referer: String): String? {
        val clean = url?.trim().orEmpty()
        if (clean.isBlank()) return null
        val normalized = when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$baseUrl$clean"
            else -> clean
        }
        return ArtworkRequestHeaders.withHeaders(
            url = normalized,
            referer = referer,
            origin = baseUrl,
            userAgent = USER_AGENT,
            accept = IMAGE_ACCEPT
        )
    }

    private fun absoluteUrl(value: String): String {
        val clean = value.trim()
        return when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("/") -> "$baseUrl$clean"
            else -> "$baseUrl/$clean"
        }
    }

    private fun pagedUrl(url: String, page: Int): String {
        return if (page <= 1) url else "${url.trimEnd('/')}/page/$page/"
    }

    private val EPISODE_NUMBER_REGEX = Regex("""(?:cap[ií]tulo|eps?)\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val EPISODE_TITLE_REGEX = Regex("""(?:cap[ií]tulo|eps?)\s*\d+""", RegexOption.IGNORE_CASE)
    private val EPISODE_TITLE_SUFFIX_REGEX =
        Regex("""\s*[-–—]\s*cap[ií]tulo\s*\d+.*$""", RegexOption.IGNORE_CASE)
    private val EPISODE_URL_SUFFIX_REGEX =
        Regex("""-cap[ií]tulo-\d+/?$""", RegexOption.IGNORE_CASE)
    private val STYLE_URL_REGEX = Regex("""url\(["']?([^"')]+)""", RegexOption.IGNORE_CASE)
    private const val DETAIL_CACHE_DURATION_MS = 2 * 60 * 1000L
}
