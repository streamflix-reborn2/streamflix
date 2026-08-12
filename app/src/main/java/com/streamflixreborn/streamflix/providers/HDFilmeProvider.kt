package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.TmdbUtils
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import retrofit2.Retrofit
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url
import retrofit2.http.Path

object HDFilmeProvider : Provider {

    override val name: String = "HDFilme"
    override val baseUrl: String = "https://hdfilme.win"
    override val logo: String = "$baseUrl/templates/hdfilme/images/apple-touch-icon.png"
    override val language: String = "de"

    private const val USER_AGENT = "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private interface HDFilmeService {
        companion object {
            fun build(baseUrl: String): HDFilmeService {
                val clientBuilder = OkHttpClient.Builder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)

                val client = clientBuilder
                    .addInterceptor(RedirectInterceptor())
                    .dns(DnsResolver.doh)
                    .build()

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                    .create(HDFilmeService::class.java)
            }

            private class RedirectInterceptor : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    var request = chain.request()
                    var response = chain.proceed(request)

                    while (response.isRedirect) {
                        val location = response.header("Location") ?: break
                        val newUrl = request.url.resolve(location) ?: break

                        request = request.newBuilder()
                            .url(newUrl)
                            .method(request.method, request.body)
                            .build()

                        response.close()
                        response = chain.proceed(request)
                    }
                    return response
                }
            }
        }

        @Headers(USER_AGENT)
        @GET(".")
        suspend fun getHome(): Document

        @Headers(USER_AGENT)
        @GET("filme1/")
        suspend fun getMovies(): Document

        @Headers(USER_AGENT)
        @GET("filme1/page/{page}/")
        suspend fun getMovies(@Path("page") page: Int): Document

        @Headers(USER_AGENT)
        @GET("serien/")
        suspend fun getTvShows(): Document

        @Headers(USER_AGENT)
        @GET("serien/page/{page}/")
        suspend fun getTvShows(@Path("page") page: Int): Document

        @Headers(USER_AGENT)
        @GET
        suspend fun getPage(@Url url: String): Document

        @Headers(USER_AGENT)
        @GET
        suspend fun getRawPage(@Url url: String): ResponseBody

        @Headers(USER_AGENT)
        @GET("{path}page/{page}/")
        suspend fun getPaged(
            @Path(value = "path", encoded = true) path: String,
            @Path("page") page: Int
        ): Document

    }

    private val service = HDFilmeService.build(baseUrl)
    private data class SitemapEntry(val url: String, val searchableSlug: String)

    @Volatile private var sitemapEntries: List<SitemapEntry>? = null
    private val sitemapMutex = Mutex()

    override suspend fun getHome(): List<Category> {
        val doc = service.getHome()
        val categories = mutableListOf<Category>()

        val sliderItems = coroutineScope {
            doc.select("ul.glide__slides li.glide__slide").map { el ->
                async { parseSliderItem(el) }
            }.awaitAll().filterNotNull()
        }

        if (sliderItems.isNotEmpty()) {
            categories.add(Category(name = Category.FEATURED, list = sliderItems))
        }

        val listingDiv = doc.selectFirst("div.listing.grid[id=dle-content]")
        if (listingDiv != null) {
            val items = listingDiv.select("div.item.relative.mt-3").mapNotNull { parseGridItem(it) }
            if (items.isNotEmpty()) {
                categories.add(Category(name = "Filme", list = items))
            }
        }

        val latestMoviesSection = doc.selectFirst("section.sidebar-section:has(h3:containsOwn(neueste Filme eingefügt))")
        val latestMoviesItems = latestMoviesSection?.select("div.listing > a")?.mapNotNull { parseSidebarItemAsMovie(it) } ?: emptyList()
        if (latestMoviesItems.isNotEmpty()) {
            categories.add(Category(name = "Neueste Filme Eingefügt", list = latestMoviesItems))
        }

        val latestSeriesSection = doc.selectFirst("section.sidebar-section:has(h3:containsOwn(neueste Serie eingefügt))")
        val latestSeriesItems = latestSeriesSection?.select("div.listing > a")?.mapNotNull { parseSidebarItemAsTvShow(it) } ?: emptyList()
        if (latestSeriesItems.isNotEmpty()) {
            categories.add(Category(name = "Neueste Serie Eingefügt", list = latestSeriesItems))
        }

        return categories
    }

    private suspend fun parseSliderItem(el: Element): Movie? {
        val title = el.selectFirst("h3.title")?.text()?.trim() ?: return null

        val linkElement = el.selectFirst("div.actions a.watchnow") ?: return null
        val href = linkElement.attr("href").trim()

        val bannerUrl = el.selectFirst("img")?.attr("data-src") ?: ""
        val banner = normalizeUrl(bannerUrl)

        val tmdbMovie = TmdbUtils.getMovie(title, language = language)

        return Movie(
            id = href,
            title = title,
            banner = banner,
            rating = tmdbMovie?.rating
        )
    }

    private fun parseGridItem(el: Element): Movie? {
        val titleElement = el.selectFirst("h3.line-clamp-2.text-sm.mt-1.font-light.leading-snug") ?: return null
        val title = titleElement.text().trim()

        val linkElement = el.selectFirst("a.block.relative[href]") ?: return null
        val href = linkElement.attr("href").trim()

        val posterUrl = el.selectFirst("img")?.attr("data-src") ?: ""
        val poster = normalizeUrl(posterUrl)

        val quality = el.selectFirst("span.absolute")?.text()?.trim()

        return Movie(
            id = href,
            title = title,
            poster = poster,
            quality = quality
        )
    }

    private fun parseGridItemAsTvShow(el: Element): TvShow? {
        val titleElement = el.selectFirst("h3.line-clamp-2.text-sm.mt-1.font-light.leading-snug") ?: return null
        val title = titleElement.text().trim()

        val linkElement = el.selectFirst("a.block.relative[href]") ?: return null
        val href = linkElement.attr("href").trim()

        val posterUrl = el.selectFirst("img")?.attr("data-src") ?: ""
        val poster = normalizeUrl(posterUrl)

        val quality = el.selectFirst("span.absolute")?.text()?.trim()

        return TvShow(
            id = href,
            title = title,
            poster = poster,
            quality = quality
        )
    }

    private fun parseSidebarItemAsMovie(el: Element): Movie? {
        val href = el.attr("href").trim()
        if (href.isBlank()) return null

        val title = el.selectFirst("figcaption.hidden")?.text()?.trim()
            ?: el.selectFirst("h4.movie-title")?.text()?.trim()
            ?: return null

        val posterUrl = el.selectFirst("img")?.attr("data-src") ?: ""
        val poster = normalizeUrl(posterUrl)

        return Movie(
            id = href,
            title = title,
            poster = poster
        )
    }

    private fun parseSidebarItemAsTvShow(el: Element): TvShow? {
        val href = el.attr("href").trim()
        if (href.isBlank()) return null

        val title = el.selectFirst("figcaption.hidden")?.text()?.trim()
            ?: el.selectFirst("h4.movie-title")?.text()?.trim()
            ?: return null

        val posterUrl = el.selectFirst("img")?.attr("data-src") ?: ""
        val poster = normalizeUrl(posterUrl)

        return TvShow(
            id = href,
            title = title,
            poster = poster
        )
    }

    private fun normalizeUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return baseUrl + (if (url.startsWith("/")) url else "/$url")
    }

    private fun normalizeExternalUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") -> url
        else -> url
    }

    private fun extractImdbId(doc: Document): String? {
        val scripts = doc.select("script").joinToString("\n") { it.data() }
        return Regex("var\\s+imdb\\s*=\\s*['\"](tt\\d+)['\"]")
            .find(scripts)?.groupValues?.get(1)
    }

    private suspend fun getSerialDocument(doc: Document): Document? {
        val imdbId = extractImdbId(doc) ?: return null
        return runCatching {
            service.getPage("https://meinecloud.click/serial/${imdbId.removePrefix("tt")}")
        }.getOrNull()
    }

    private fun serialSeasonNumber(season: Element, serialDoc: Document): Int? {
        val seasonId = season.attr("data-season")
        val tabNumber = serialDoc.selectFirst("._stab[data-season='$seasonId']")?.text()
            ?.let { Regex("S(\\d+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return tabNumber ?: season.selectFirst("._ep[data-label]")?.attr("data-label")
            ?.let { Regex("S(\\d+)\\s*E\\d+", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() }
    }

    private fun parseSerialEpisodes(
        showUrl: String,
        seasonNumber: Int,
        serialDoc: Document,
        tmdbEpisodes: List<Episode> = emptyList()
    ): List<Episode> {
        val season = serialDoc.select("._season-eps").firstOrNull {
            serialSeasonNumber(it, serialDoc) == seasonNumber
        } ?: return emptyList()

        return season.select("._ep").mapNotNull { element ->
            val episodeNumber = element.selectFirst("._ep-n")?.text()?.trim()?.toIntOrNull()
                ?: Regex("S\\d+\\s*E(\\d+)", RegexOption.IGNORE_CASE)
                    .find(element.attr("data-label"))?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val siteTitle = element.selectFirst("._ep-t")?.text()?.trim()?.takeIf { it.isNotBlank() }
            val tmdbEpisode = tmdbEpisodes.find { it.number == episodeNumber }

            Episode(
                id = "$showUrl#s${seasonNumber}e$episodeNumber",
                number = episodeNumber,
                title = tmdbEpisode?.title ?: siteTitle ?: "Episode $episodeNumber",
                poster = tmdbEpisode?.poster,
                overview = tmdbEpisode?.overview
            )
        }.distinctBy { it.number }.sortedBy { it.number }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()

            val doc = service.getHome()

            val genreContainer = doc.selectFirst("div.dropdown-hover:has(span:containsOwn(Genre))")
            val genreLinks = genreContainer?.select("div.dropdown-content a[href]") ?: emptyList()

            return genreLinks.mapNotNull { a ->
                val href = a.attr("href").trim()
                val text = a.text().trim()
                if (href.isBlank() || text.isBlank()) return@mapNotNull null

                Genre(
                    id = href,
                    name = text
                )
            }
        }

        // The site's DLE search currently dies in sfilter/filter.php. Its
        // generated sitemap remains current and contains the complete index.
        return searchSitemap(query, page)
    }

    private suspend fun searchSitemap(query: String, page: Int): List<AppAdapter.Item> {
        return try {
            val entries = getSitemapEntries()

            val queryTokens = searchTokens(query)
            if (queryTokens.isEmpty()) return emptyList()
            val pageSize = 12
            val matchingUrls = entries.asSequence()
                .filter { entry ->
                    queryTokens.all { queryToken -> entry.searchableSlug.contains(queryToken) }
                }
                .drop((page - 1).coerceAtLeast(0) * pageSize)
                .take(pageSize)
                .map { it.url }
                .toList()

            val detailSemaphore = Semaphore(4)
            coroutineScope {
                matchingUrls.map { url ->
                    async {
                        detailSemaphore.withPermit {
                            runCatching { parseSearchResult(url, service.getPage(url)) }.getOrNull()
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        } catch (e: Exception) {
            searchCataloguePages(query, page)
        }
    }

    private suspend fun getSitemapEntries(): List<SitemapEntry> {
        sitemapEntries?.let { return it }

        sitemapMutex.lock()
        return try {
            sitemapEntries ?: coroutineScope {
                listOf("news_pages.xml", "news_pages2.xml")
                    .map { sitemap -> async { parseSitemap(service.getRawPage("$baseUrl/$sitemap")) } }
                    .awaitAll()
                    .flatten()
                    .distinctBy { it.url }
                    .also { sitemapEntries = it }
            }
        } finally {
            sitemapMutex.unlock()
        }
    }

    private fun parseSitemap(body: ResponseBody): List<SitemapEntry> = body.use { responseBody ->
        val parser = android.util.Xml.newPullParser().apply {
            setInput(responseBody.charStream())
        }
        val entries = ArrayList<SitemapEntry>()
        var event = parser.eventType

        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "loc") {
                val url = parser.nextText().trim()
                if (url.isNotBlank()) {
                    val encodedSlug = url.substringAfterLast('/')
                    val slug = runCatching {
                        URLDecoder.decode(encodedSlug, StandardCharsets.UTF_8.name())
                    }.getOrDefault(encodedSlug)
                    entries.add(SitemapEntry(url = url, searchableSlug = slug.lowercase()))
                }
            }
            event = parser.next()
        }
        entries
    }

    private fun searchTokens(value: String): List<String> = value
        .lowercase()
        .replace(Regex("^\\d+-"), "")
        .replace(Regex("-stream(?:ing)?-stream\\.html$"), "")
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotBlank() }

    private fun parseSearchResult(url: String, doc: Document): AppAdapter.Item? {
        val title = doc.selectFirst("h1.font-bold")?.text()?.trim()
            ?.replace(Regex("\\s*hdfilme\\s*$", RegexOption.IGNORE_CASE), "")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" Stream")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = normalizeUrl(doc.selectFirst("figure.inline-block img")?.attr("data-src").orEmpty())
        return if (isTvShowDocument(doc)) {
            TvShow(id = url, title = title, poster = poster)
        } else {
            Movie(id = url, title = title, poster = poster)
        }
    }

    private fun isTvShowDocument(doc: Document): Boolean =
        doc.selectFirst("a[href*='themoviedb.org/tv/'], .info a[href$='/serien/'], #serial_iframe") != null

    private suspend fun searchCataloguePages(query: String, page: Int): List<AppAdapter.Item> =
        coroutineScope {
            val pages = (1..3).map { cataloguePage ->
                async {
                    listOf(
                        service.getMovies(cataloguePage) to false,
                        service.getTvShows(cataloguePage) to true
                    ).flatMap { (document, isTvShow) ->
                        document.select("div.listing.grid[id=dle-content] div.item.relative.mt-3")
                            .filter { element ->
                                element.selectFirst("h3.line-clamp-2")?.text()?.contains(query, ignoreCase = true) == true
                            }
                            .mapNotNull { element ->
                                if (isTvShow) {
                                    parseGridItemAsTvShow(element)
                                } else {
                                    parseGridItem(element)
                                }
                            }
                    }
                }
            }
            pages.awaitAll().flatten().distinctBy { item ->
                when (item) {
                    is Movie -> item.id
                    is TvShow -> item.id
                    else -> item.toString()
                }
            }
        }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val doc = if (page > 1) service.getMovies(page) else service.getMovies()

            coroutineScope {
                val detailSemaphore = Semaphore(4)
                doc.select("div.listing.grid[id=dle-content] div.item.relative.mt-3").map { element ->
                    async {
                        detailSemaphore.withPermit {
                            val href = element.selectFirst("a.block.relative[href]")
                                ?.attr("href")?.trim()?.takeIf { it.isNotBlank() }
                                ?: return@withPermit null
                            val itemDoc = runCatching { service.getPage(href) }.getOrNull()
                                ?: return@withPermit null

                            if (isTvShowDocument(itemDoc)) null else parseGridItem(element)
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val doc = if (page > 1) service.getTvShows(page) else service.getTvShows()

            coroutineScope {
                val detailSemaphore = Semaphore(4)
                doc.select("div.listing.grid[id=dle-content] div.item.relative.mt-3").map { element ->
                    async {
                        detailSemaphore.withPermit {
                            val href = element.selectFirst("a.block.relative[href]")
                                ?.attr("href")?.trim()?.takeIf { it.isNotBlank() }
                                ?: return@withPermit null
                            val itemDoc = runCatching { service.getPage(href) }.getOrNull()
                                ?: return@withPermit null

                            if (isTvShowDocument(itemDoc)) parseGridItemAsTvShow(element) else null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val doc = service.getPage(id)

        val titleRaw = doc.selectFirst("h1.font-bold")?.text()?.trim() ?: ""
        val title = titleRaw.replace(Regex("\\s*hdfilme\\s*$", RegexOption.IGNORE_CASE), "").trim()

        val tmdbMovie = TmdbUtils.getMovie(title, language = language)
        val poster = normalizeUrl(doc.selectFirst("figure.inline-block img")?.attr("data-src") ?: "")

        val overviewDiv = doc.selectFirst("div.font-extralight.prose.max-w-none")
        val overview = overviewDiv?.select("p")?.firstOrNull()?.let { p ->
            val fullText = p.text().trim()
            val refIndex = fullText.indexOf("Referenzen von")
            if (refIndex > 0) {
                fullText.substring(0, refIndex).trim()
            } else {
                fullText
            }
        }

        val trailer = doc.selectFirst("iframe[src*='youtube.com/embed']")?.attr("src")?.let { src ->
            src.replace("/embed/", "/watch?v=").replace("?autoplay=1", "")
        }

        val metadataDiv = doc.selectFirst("div.border-b.border-gray-700.font-extralight")

        val genres = metadataDiv?.let { div ->
            val firstSpan = div.selectFirst("span")
            firstSpan?.select("a")?.mapNotNull { a ->
                val genreName = a.text().trim()
                if (genreName.isNotBlank()) {
                    Genre(id = genreName, name = genreName)
                } else null
            }
        } ?: emptyList()

        val year = metadataDiv?.select("span")?.firstOrNull { span ->
            span.text().trim().matches(Regex("^\\d{4}$"))
        }?.text()?.trim()

        val duration = metadataDiv?.select("span")?.firstOrNull { span ->
            span.text().trim().contains("min")
        }?.text()?.trim()?.replace("min", "")?.trim()?.toIntOrNull()

        val quality = metadataDiv?.children()?.filter { it.tagName() == "span" && !it.hasClass("divider") }
            ?.lastOrNull()?.text()?.trim()?.let { text ->
                if (text.matches(Regex("^\\d{4}$")) || text.contains("min", ignoreCase = true)) null else text
            }


        val rating = metadataDiv?.selectFirst("p.imdb-badge span.imdb-rate")?.text()?.trim()?.toDoubleOrNull()

        val cast = doc.select("ul.space-y-1 li:has(span:containsOwn(Schauspieler:)) a[href*='/xfsearch/actors/']")
            .mapNotNull { a ->
                val actorName = a.text().trim()
                val actorUrl = a.attr("href").trim()
                if (actorName.isNotBlank() && actorName != "N/A") {
                    val tmdbPerson = tmdbMovie?.cast?.find { it.name.equals(actorName, ignoreCase = true) }
                    People(id = actorUrl, name = actorName, image = tmdbPerson?.image)
                } else null
            }

        return Movie(
            id = id,
            title = title,
            poster = poster,
            banner = tmdbMovie?.banner,
            trailer = trailer,
            rating = tmdbMovie?.rating ?: rating,
            overview = tmdbMovie?.overview ?: overview,
            released = tmdbMovie?.released?.let { "${it.get(java.util.Calendar.YEAR)}" } ?: year,
            runtime = tmdbMovie?.runtime ?: duration,
            quality = quality,
            genres = tmdbMovie?.genres ?: genres,
            cast = cast,
            imdbId = tmdbMovie?.imdbId
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val doc = service.getPage(id)

        val titleRaw = doc.selectFirst("h1.font-bold")?.text()?.trim() ?: ""
        val title = titleRaw.replace(Regex("\\s*hdfilme\\s*$", RegexOption.IGNORE_CASE), "").trim()

        val tmdbTitle = title.replace(Regex("(?i)\\s*\\((Season|Staffel)s?\\s*\\d+-\\d+\\)"), "").trim()
        val tmdbTvShow = TmdbUtils.getTvShow(tmdbTitle, language = language)
        val poster = normalizeUrl(doc.selectFirst("figure.inline-block img")?.attr("data-src") ?: "")

        val overviewDiv = doc.selectFirst("div.font-extralight.prose.max-w-none")
        val overview = overviewDiv?.select("p")?.firstOrNull()?.let { p ->
            val fullText = p.text().trim()
            val refIndex = fullText.indexOf("Referenzen von")
            if (refIndex > 0) {
                fullText.substring(0, refIndex).trim()
            } else {
                fullText
            }
        }

        val trailer = doc.selectFirst("iframe[src*='youtube.com/embed']")?.attr("src")?.let { src ->
            src.replace("/embed/", "/watch?v=").replace("?autoplay=1", "")
        }

        val metadataDiv = doc.selectFirst("div.border-b.border-gray-700.font-extralight")

        val genres = metadataDiv?.let { div ->
            val firstSpan = div.selectFirst("span")
            firstSpan?.select("a")?.mapNotNull { a ->
                val genreName = a.text().trim()
                if (genreName.isNotBlank()) {
                    Genre(id = genreName, name = genreName)
                } else null
            }
        } ?: emptyList()

        val year = metadataDiv?.select("span")?.firstOrNull { span ->
            span.text().trim().matches(Regex("^\\d{4}$"))
        }?.text()?.trim()

        val duration = metadataDiv?.select("span")?.firstOrNull { span ->
            span.text().trim().contains("min")
        }?.text()?.trim()?.replace("min", "")?.trim()?.toIntOrNull()

        val quality = metadataDiv?.children()?.filter { it.tagName() == "span" && !it.hasClass("divider") }
            ?.lastOrNull()?.text()?.trim()?.let { text ->
                if (text.matches(Regex("^\\d{4}$")) || text.contains("min", ignoreCase = true)) null else text
            }

        val rating = metadataDiv?.selectFirst("p.imdb-badge span.imdb-rate")?.text()?.trim()?.toDoubleOrNull()

        val seasons = mutableListOf<Season>()
        val serialDoc = getSerialDocument(doc)
        serialDoc?.select("._season-eps")?.forEach { serialSeason ->
            val seasonNumber = serialSeasonNumber(serialSeason, serialDoc) ?: return@forEach
            val episodes = parseSerialEpisodes(id, seasonNumber, serialDoc)
            if (episodes.isNotEmpty()) {
                seasons.add(
                    Season(
                        id = "$id#season-$seasonNumber",
                        number = seasonNumber,
                        poster = tmdbTvShow?.seasons?.find { it.number == seasonNumber }?.poster,
                        episodes = episodes
                    )
                )
            }
        }

        if (seasons.isEmpty()) doc.select("div#se-accordion div.su-spoiler").forEach { spoiler ->
            val seasonTitle = spoiler.selectFirst("div.su-spoiler-title")?.text()?.trim() ?: return@forEach
            val seasonNumberMatch = Regex("Staffel\\s+(\\d+)").find(seasonTitle)
            val seasonNumber = seasonNumberMatch?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach

            val episodes = mutableListOf<Episode>()
            val content = spoiler.selectFirst("div.su-spoiler-content")?.html() ?: ""

            if (content.isNotBlank()) {
                val episodeRegex = Regex("""(\d+)x(\d+)\s+Episode\s+\d+""")
                val lines = content.split("<br>")

                lines.forEach { line ->
                    val match = episodeRegex.find(line) ?: return@forEach
                    val epNumber = match.groupValues[2].toIntOrNull() ?: return@forEach

                    episodes.add(
                        Episode(
                            id = "$id#s${seasonNumber}e$epNumber",
                            number = epNumber,
                            title = "Episode $epNumber",
                            poster = null
                        )
                    )
                }
            }

            if (episodes.isNotEmpty()) {
                seasons.add(
                    Season(
                        id = "$id#season-$seasonNumber",
                        number = seasonNumber,
                        poster = tmdbTvShow?.seasons?.find { it.number == seasonNumber }?.poster,
                        episodes = episodes.distinctBy { it.number }.sortedBy { it.number }
                    )
                )
            }
        }

        val cast = doc.select("ul.space-y-1 li:has(span:containsOwn(Schauspieler:)) a[href*='/xfsearch/actors/']")
            .mapNotNull { a ->
                val actorName = a.text().trim()
                val actorUrl = a.attr("href").trim()
                if (actorName.isNotBlank() && actorName != "N/A") {
                    val tmdbPerson = tmdbTvShow?.cast?.find { it.name.equals(actorName, ignoreCase = true) }
                    People(id = actorUrl, name = actorName, image = tmdbPerson?.image)
                } else null
            }

        return TvShow(
            id = id,
            title = title,
            poster = poster,
            banner = tmdbTvShow?.banner,
            trailer = tmdbTvShow?.trailer ?: trailer,
            rating = tmdbTvShow?.rating ?: rating,
            overview = tmdbTvShow?.overview ?: overview,
            released = tmdbTvShow?.released?.let { "${it.get(java.util.Calendar.YEAR)}" } ?: year,
            runtime = tmdbTvShow?.runtime ?: duration,
            quality = quality,
            genres = tmdbTvShow?.genres ?: genres,
            cast = cast,
            seasons = seasons,
            imdbId = tmdbTvShow?.imdbId
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val showUrl = seasonId.substringBefore("#")
        val seasonNumber = seasonId.substringAfter("#season-").toIntOrNull() ?: return emptyValue()

        val doc = service.getPage(showUrl)
        val titleRaw = doc.selectFirst("h1.font-bold")?.text()?.trim() ?: ""
        val title = titleRaw.replace(Regex("\\s*hdfilme\\s*$", RegexOption.IGNORE_CASE), "").trim()

        val tmdbTitle = title.replace(Regex("(?i)\\s*\\((Season|Staffel)s?\\s*\\d+-\\d+\\)"), "").trim()
        val tmdbTvShow = TmdbUtils.getTvShow(tmdbTitle, language = language)
        val tmdbEpisodes = tmdbTvShow?.let { TmdbUtils.getEpisodesBySeason(it.id, seasonNumber, language = language) } ?: emptyList()

        getSerialDocument(doc)?.let { serialDoc ->
            val serialEpisodes = parseSerialEpisodes(showUrl, seasonNumber, serialDoc, tmdbEpisodes)
            if (serialEpisodes.isNotEmpty()) return serialEpisodes
        }

        val episodes = mutableListOf<Episode>()

        doc.select("div#se-accordion div.su-spoiler").forEach { spoiler ->
            val seasonTitle = spoiler.selectFirst("div.su-spoiler-title")?.text()?.trim() ?: return@forEach
            val seasonNumberMatch = Regex("Staffel\\s+(\\d+)").find(seasonTitle)
            val currentSeasonNum = seasonNumberMatch?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach

            if (currentSeasonNum == seasonNumber) {
                val content = spoiler.selectFirst("div.su-spoiler-content")?.html() ?: return@forEach

                val episodeRegex = Regex("""(\d+)x(\d+)\s+Episode\s+\d+""")
                val lines = content.split("<br>")

                lines.forEach { line ->
                    val match = episodeRegex.find(line) ?: return@forEach
                    val epNumber = match.groupValues[2].toIntOrNull() ?: return@forEach

                    val tmdbEp = tmdbEpisodes.find { it.number == epNumber }

                    episodes.add(
                        Episode(
                            id = "$showUrl#s${seasonNumber}e$epNumber",
                            number = epNumber,
                            title = tmdbEp?.title ?: "Episode $epNumber",
                            poster = tmdbEp?.poster,
                            overview = tmdbEp?.overview
                        )
                    )
                }
            }
        }

        return episodes.distinctBy { it.number }.sortedBy { it.number }
    }

    private fun <T> emptyValue(): List<T> = emptyList()

    override suspend fun getGenre(id: String, page: Int): Genre {
        return try {
            val doc = if (page <= 1) {
                service.getPage(id)
            } else {
                val path = id.substringAfter(baseUrl).trimStart('/')
                service.getPaged(path, page)
            }
            val title = doc.selectFirst("h1")?.text()?.trim() ?: ""

            val elements = doc.select("div.listing.grid[id=dle-content] div.item.relative.mt-3")

            val shows = when {
                id.contains("/serien/", ignoreCase = true) -> {
                    elements.mapNotNull { el -> parseGridItemAsTvShow(el) }
                }
                else -> {
                    coroutineScope {
                        elements.map { el ->
                            async {
                                val href = el.selectFirst("a.block.relative[href]")?.attr("href")?.trim()
                                    ?: return@async null

                                try {
                                    val itemDoc = service.getPage(href)
                                    val hasSeasons = itemDoc.select("div#se-accordion").isNotEmpty()

                                    if (hasSeasons) {
                                        parseGridItemAsTvShow(el)
                                    } else {
                                        parseGridItem(el)
                                    }
                                } catch (e: Exception) {
                                    parseGridItem(el)
                                }
                            }
                        }.awaitAll().filterNotNull()
                    }
                }
            }

            Genre(
                id = id,
                name = title,
                shows = shows
            )
        } catch (e: Exception) {
            Genre(id = id, name = "")
        }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val doc = service.getPage(id)

        val name = doc.selectFirst("h1")?.text()?.trim() ?: ""

        if (page > 1) {
            return People(
                id = id,
                name = name,
                filmography = emptyList()
            )
        }

        val elements = doc.select("div.listing.grid[id=dle-content] div.item.relative.mt-3")

        val filmography = coroutineScope {
            elements.map { el ->
                async {
                    val href = el.selectFirst("a.block.relative[href]")?.attr("href")?.trim()
                        ?: return@async null

                    try {
                        val itemDoc = service.getPage(href)
                        val hasSeasons = itemDoc.select("div#se-accordion").isNotEmpty()

                        if (hasSeasons) {
                            parseGridItemAsTvShow(el)
                        } else {
                            parseGridItem(el)
                        }
                    } catch (e: Exception) {
                        parseGridItem(el)
                    }
                }
            }.awaitAll().filterNotNull()
        }

        return People(
            id = id,
            name = name,
            filmography = filmography
        )
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val doc = service.getPage(id)

        if (videoType is Video.Type.Episode) {
            val showUrl = id.substringBefore('#')
            val episodePart = id.substringAfter('#', "")
            val seasonNum = episodePart.substringAfter('s').substringBefore('e').toIntOrNull() ?: return emptyList()
            val epNum = episodePart.substringAfter('e').toIntOrNull() ?: return emptyList()

            val showDoc = doc
            getSerialDocument(showDoc)?.let { serialDoc ->
                val season = serialDoc.select("._season-eps").firstOrNull {
                    serialSeasonNumber(it, serialDoc) == seasonNum
                }
                val episode = season?.select("._ep")?.firstOrNull { element ->
                    element.selectFirst("._ep-n")?.text()?.trim()?.toIntOrNull() == epNum
                }
                val streamUrl = episode?.attr("data-link")?.trim().orEmpty()
                if (streamUrl.isNotBlank()) {
                    val normalized = normalizeExternalUrl(streamUrl)
                    val serverName = runCatching {
                        normalized.toHttpUrl().host.substringBefore('.').replaceFirstChar { it.uppercase() }
                    }.getOrDefault("Server")
                    return listOf(Video.Server(id = normalized, name = serverName, src = normalized))
                }
            }
            val servers = mutableListOf<Video.Server>()

            showDoc.select("div#se-accordion div.su-spoiler").forEach { spoiler ->
                val seasonTitle = spoiler.selectFirst("div.su-spoiler-title")?.text()?.trim() ?: return@forEach
                val seasonNumberMatch = Regex("Staffel\\s+(\\d+)").find(seasonTitle)
                val currentSeasonNum = seasonNumberMatch?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach

                if (currentSeasonNum == seasonNum) {
                    val content = spoiler.selectFirst("div.su-spoiler-content") ?: return@forEach

                    val episodeRegex = Regex("""${seasonNum}x${epNum}\s+Episode\s+\d+""")
                    val lines = content.html().split("<br>")

                    lines.forEach { line ->
                        if (episodeRegex.find(line) != null) {
                            val linkElements = Jsoup.parse(line).select("a[href]")
                            linkElements.forEach { link ->
                                val serverName = link.text().trim()
                                val serverUrl = link.attr("href").trim()

                                if (!serverUrl.contains("/engine/player.php") &&
                                    !serverName.contains("Player HD", ignoreCase = true) &&
                                    !serverName.contains("4K", ignoreCase = true) &&
                                    serverUrl.isNotBlank()) {

                                    val normalized = when {
                                        serverUrl.startsWith("http") -> serverUrl
                                        serverUrl.startsWith("//") -> "https:$serverUrl"
                                        else -> serverUrl
                                    }

                                    servers.add(
                                        Video.Server(
                                            id = normalized,
                                            name = serverName.ifBlank { "Server" },
                                            src = normalized
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            return servers.distinctBy { it.src }
        }

        val iframeSrc = doc.selectFirst("iframe[src*='meinecloud.click']")?.attr("src")
            ?: throw Exception("Embed iframe not found")

        val embedUrl = normalizeUrl(iframeSrc)
        val embedDoc = service.getPage(embedUrl)

        return embedDoc.select("ul._player-mirrors li[data-link]")
            .filterNot { li ->
                li.hasClass("fullhd") || li.text().contains("4K Server", ignoreCase = true)
            }
            .mapNotNull { li ->
                val dataLink = li.attr("data-link").trim()
                if (dataLink.isBlank()) return@mapNotNull null

                val normalized = when {
                    dataLink.startsWith("//") -> "https:$dataLink"
                    dataLink.startsWith("http") -> dataLink
                    else -> "https://$dataLink"
                }

                val nameText = li.ownText().ifBlank { li.text() }.trim()
                val name = nameText.ifBlank { "Server" }

                Video.Server(id = normalized, name = name, src = normalized)
            }
            .filter { it.src.isNotBlank() }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.src)
    }
}
