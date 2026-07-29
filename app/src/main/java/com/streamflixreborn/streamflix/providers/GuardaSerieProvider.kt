package com.streamflixreborn.streamflix.providers

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TmdbUtils
import com.streamflixreborn.streamflix.utils.WebViewResolver
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import retrofit2.http.Headers
import retrofit2.http.Query
import retrofit2.http.Path
import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object GuardaSerieProvider : Provider {

    override val name = "GuardaSerie"
    override val baseUrl = "https://guardoserie.study"
    override val logo: String = "$baseUrl/wp-content/uploads/2026/06/Guardoserie1.png"
    override val language = "it"

    private const val USER_AGENT = "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val TAG = "GuardaSerieBypass"

    private var webViewResolver: WebViewResolver? = null
    private val providerMutex = Mutex()

    fun init(context: Context) {
        webViewResolver = WebViewResolver(context)
    }

    private fun getResolver(): WebViewResolver {
        return webViewResolver ?: WebViewResolver(StreamFlixApp.instance).also {
            webViewResolver = it
        }
    }

    private interface GuardaSerieService {
        companion object {
            fun build(baseUrl: String): GuardaSerieService {
                val clientBuilder = OkHttpClient.Builder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .cookieJar(NetworkClient.cookieJar)

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(clientBuilder.dns(DnsResolver.doh).build())
                    .build()
                    .create(GuardaSerieService::class.java)
            }
        }

        @Headers(USER_AGENT)
        @GET(".")
        suspend fun getHome(): Document

        @Headers(USER_AGENT)
        @GET
        suspend fun getPage(@Url url: String): Document

        @Headers(USER_AGENT)
        @GET("{path}page/{page}/")
        suspend fun getPage(@Path(value = "path", encoded = true) path: String, @Path("page") page: Int): Document

        @Headers(USER_AGENT)
        @GET("serie/")
        suspend fun getSerie(): Document

        @Headers(USER_AGENT)
        @GET("serie/page/{page}/")
        suspend fun getSerie(@Path("page") page: Int): Document

        @Headers(USER_AGENT)
        @GET("turche/")
        suspend fun getTurche(): Document

        @Headers(USER_AGENT)
        @GET("guarda-film-streaming-ita/")
        suspend fun getMovies(): Document

        @Headers(USER_AGENT)
        @GET("guarda-film-streaming-ita/page/{page}/")
        suspend fun getMovies(@Path("page") page: Int): Document

        @Headers(USER_AGENT)
        @GET(".")
        suspend fun search(@Query(value = "s", encoded = true) query: String): Document

        @Headers(USER_AGENT)
        @GET("page/{page}/")
        suspend fun search(@Path("page") page: Int, @Query(value = "s", encoded = true) query: String): Document
    }

    private val service = GuardaSerieService.build(baseUrl)

    private class CloudflareChallengeException(val url: String) : Exception("Cloudflare challenge detected for $url")

    private fun requiresClearance(html: String): Boolean {
        return html.contains("Just a moment...", ignoreCase = true) ||
               html.contains("cf-browser-verification", ignoreCase = true) ||
               html.contains("Checking your browser", ignoreCase = true)
    }



    private suspend fun getDocument(url: String): Document {
        try {
            val doc = service.getPage(url)
            val html = doc.outerHtml()
            if (requiresClearance(html)) {
                throw CloudflareChallengeException(url)
            }
            return doc
        } catch (e: Exception) {
            val httpException = e as? retrofit2.HttpException
            val isChallengeException = e is CloudflareChallengeException

            // 403 with Cloudflare challenge in body -> open WebView
            val isCloudflareChallengeOn403 = if (httpException?.code() == 403) {
                val errorBody = httpException.response()?.errorBody()?.string().orEmpty()
                requiresClearance(errorBody)
            } else false

            // Open WebView only if a true Cloudflare challenge is detected
            if (!isChallengeException && !isCloudflareChallengeOn403) {
                throw e
            }


            Log.d(TAG, "Using WebView bypass for $url (Cloudflare challenge detected)")
            val result = providerMutex.withLock {
                getResolver().getResult(
                    url = url,
                    headers = mapOf("User-Agent" to NetworkClient.USER_AGENT),
                    completion = { currentUrl, htmlText, cookies ->
                        val isChallenge = requiresClearance(htmlText)
                        val hasContent = htmlText.contains("movies-list") || htmlText.contains("mvic-desc") || htmlText.contains("player2")
                        !isChallenge && hasContent
                    }
                )
            }
            CookieManager.getInstance().flush()

            val parsedDoc = Jsoup.parse(result.html, url).apply { setBaseUri(baseUrl) }
            if (requiresClearance(parsedDoc.outerHtml())) {
                throw CloudflareChallengeException(url)
            }
            return parsedDoc
        }
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val categories = mutableListOf<Category>()
        
        val docSerieDeferred = async { getDocument("$baseUrl/serie/") }
        val docTurcheDeferred = async { getDocument("$baseUrl/turche/") }
        
        val docSerie = docSerieDeferred.await()
        val itemsSerie = docSerie.select("div.movies-list.movies-list-full div.ml-item").mapNotNull { el: Element ->
            parseGridItem(el)
        }
        if (itemsSerie.isNotEmpty()) {
            categories.add(Category(name = "Serie", list = itemsSerie))
        }
        
        val docTurche = docTurcheDeferred.await()
        val itemsTurche = docTurche.select("div.movies-list.movies-list-full div.ml-item").mapNotNull { el: Element ->
            parseGridItem(el)
        }
        if (itemsTurche.isNotEmpty()) {
            categories.add(Category(name = "Serie Turche", list = itemsTurche))
        }
        
        categories
    }

    private fun parseGridItem(el: Element): AppAdapter.Item? {
        val link = el.selectFirst("a.ml-mask[href]") ?: return null
        val href = link.attr("href").trim()
        if (href.isBlank()) return null

        val title = el.selectFirst("span.mli-info h2")?.text()?.trim() ?: return null
        val img = el.selectFirst("img.lazy.thumb.mli-thumb")
        val poster = img?.attr("data-original")?.takeIf { it.isNotBlank() } ?: ""

        return if (href.contains("/serie/")) {
            TvShow(
                id = href,
                title = title,
                poster = poster
            )
        } else {
            Movie(
                id = href,
                title = title,
                poster = poster
            )
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            val doc = getDocument(baseUrl)
            return doc.select("li.menu-item:has(> a:matchesOwn(^Genere$)) ul.sub-menu li a[href]")
                .mapNotNull { a: Element ->
                    val href = a.attr("href").trim()
                    val text = a.text().trim()
                    if (href.isBlank() || text.isBlank()) return@mapNotNull null
                    Genre(id = href, name = text)
                }
        }

        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = if (page > 1) "$baseUrl/page/$page/?s=$encoded" else "$baseUrl/?s=$encoded"
        if (page > 1) {
            val firstDoc = getDocument("$baseUrl/?s=$encoded")
            val hasPager = firstDoc.selectFirst("div#pagination ul.pagination li:not(.active) a[href]") != null
            if (!hasPager) return emptyList()
        }

        val doc = getDocument(searchUrl)

        return doc.select("div.movies-list.movies-list-full div.ml-item").mapNotNull { el: Element ->
            parseGridItem(el)
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val url = if (page > 1) "$baseUrl/guarda-film-streaming-ita/page/$page/" else "$baseUrl/guarda-film-streaming-ita/"
        val doc = getDocument(url)

        return doc.select("div.movies-list.movies-list-full div.ml-item")
            .mapNotNull { el: Element -> parseGridItem(el) as? Movie }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val url = if (page > 1) "$baseUrl/serie/page/$page/" else "$baseUrl/serie/"
        val doc = getDocument(url)
        
        return doc.select("div.movies-list.movies-list-full div.ml-item")
            .mapNotNull { el: Element -> parseGridItem(el) as? TvShow }
    }

    override suspend fun getMovie(id: String): Movie {
        val doc = getDocument(id)

        val title = doc.selectFirst("div.mvic-desc h3")?.text()?.trim() ?: ""

        val tmdbMovie = TmdbUtils.getMovie(title, language = language)

        val poster = tmdbMovie?.poster ?: doc.selectFirst("div.thumb.mvic-thumb img")?.attr("data-src") ?: ""

        return Movie(
            id = id,
            title = title,
            poster = poster,
            released = tmdbMovie?.released?.let { "${it.get(java.util.Calendar.YEAR)}" } ?: doc.selectFirst("div.mvici-right a[rel='tag']")?.text()?.trim(),
            runtime = tmdbMovie?.runtime ?: doc.selectFirst("div.mvici-right p:has(strong:matchesOwn((?i)^Duration:)) span")?.text()?.substringBefore(" min")?.trim()?.toIntOrNull(),
            rating = tmdbMovie?.rating ?: doc.selectFirst("div.mvici-right div.imdb_r span.imdb-r")?.text()?.toDoubleOrNull(),
            genres = tmdbMovie?.genres ?: doc.select("div.mvici-left a[rel='category tag']").map { Genre(it.text().trim(), it.text().trim()) },
            cast = doc.select("div.mvici-left p:has(strong:matchesOwn((?i)^attori:)) span a[href]").map { el ->
                val href = el.attr("href").trim()
                val name = el.text().trim()
                val tmdbPerson = tmdbMovie?.cast?.find { it.name.equals(name, ignoreCase = true) }
                People(id = href, name = name, image = tmdbPerson?.image)
            },
            overview = tmdbMovie?.overview ?: doc.selectFirst("p.f-desc")?.text()?.trim() ?: "",
            trailer = tmdbMovie?.trailer ?: runCatching {
                val scripts = doc.select("script")
                scripts.firstOrNull { it.html().contains("iframe-trailer") && it.html().contains("youtube.com/embed/") }
                    ?.let { script ->
                        val scriptContent = script.html()
                        val embedUrl = scriptContent.substringAfter("'src', '")
                            .substringBefore("');")
                        embedUrl.replace("/embed/", "/watch?v=")
                            .let { if (it.startsWith("//")) "https:$it" else it }
                    }
            }.getOrNull(),
            banner = tmdbMovie?.banner,
            imdbId = tmdbMovie?.imdbId
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val doc = getDocument(id)
        
        val title = doc.selectFirst("div.mvic-desc h3")?.text()?.trim() ?: ""
        
        val tmdbTvShow = TmdbUtils.getTvShow(title, language = language)

        val poster = tmdbTvShow?.poster ?: doc.selectFirst("div.thumb.mvic-thumb img")?.attr("data-src") ?: ""

        val seriesDescRaw = doc.selectFirst("p.f-desc")?.text()?.trim()
        val overview = tmdbTvShow?.overview ?: seriesDescRaw?.takeUnless { text ->
            val l = text.lowercase()
            l.contains("streaming community ita su guardaserie") && l.contains("guardare serie")
        }

        val seasons = doc.select("div#seasons div.tvseason").mapNotNull { seasonEl: Element ->
            val content = seasonEl.selectFirst("div.les-content")
            if (content?.selectFirst("a.ep-404") != null) return@mapNotNull null
            val titleText = seasonEl.selectFirst("div.les-title strong")?.text()?.trim() ?: return@mapNotNull null
            val seasonMatch = Regex("Stagione\\s+(\\d+)", RegexOption.IGNORE_CASE).find(titleText)
            val seasonNumber = seasonMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            
            Season(
                id = "$id#s$seasonNumber",
                number = seasonNumber,
                poster = tmdbTvShow?.seasons?.find { it.number == seasonNumber }?.poster
            )
        }

        return TvShow(
            id = id,
            title = title,
            poster = poster,
            released = tmdbTvShow?.released?.let { "${it.get(java.util.Calendar.YEAR)}" } ?: doc.selectFirst("div.mvici-right a[rel='tag']")?.text()?.trim(),
            runtime = tmdbTvShow?.runtime ?: doc.selectFirst("div.mvici-right p:has(strong:matchesOwn((?i)^Duration:)) span")?.text()?.substringBefore(" min")?.trim()?.toIntOrNull(),
            rating = tmdbTvShow?.rating ?: doc.selectFirst("div.mvici-right div.imdb_r span.imdb-r")?.text()?.toDoubleOrNull(),
            overview = overview,
            genres = tmdbTvShow?.genres ?: doc.select("div.mvici-left a[rel='category tag']").map { Genre(it.text().trim(), it.text().trim()) },
            cast = doc.select("div.mvici-left p:has(strong:matchesOwn((?i)^attori:)) span a[href]").map { el ->
                val href = el.attr("href").trim()
                val name = el.text().trim()
                val tmdbPerson = tmdbTvShow?.cast?.find { it.name.equals(name, ignoreCase = true) }
                People(id = href, name = name, image = tmdbPerson?.image)
            },
            seasons = seasons,
            banner = tmdbTvShow?.banner,
            imdbId = tmdbTvShow?.imdbId,
            trailer = tmdbTvShow?.trailer
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val showId = seasonId.substringBefore("#s")
        val seasonNum = seasonId.substringAfter("#s").toIntOrNull() ?: return emptyList()

        val doc = getDocument(showId)
        val tmdbTvShow = TmdbUtils.getTvShow(cleanTitle(doc.selectFirst("div.mvic-desc h3")?.text() ?: ""), language = language)
        val tmdbEpisodes = if (tmdbTvShow != null) TmdbUtils.getEpisodesBySeason(tmdbTvShow.id, seasonNum, language = language) else emptyList()

        val seasonEl = doc.select("div#seasons div.tvseason").firstOrNull { el ->
            val titleText = el.selectFirst("div.les-title strong")?.text()?.trim() ?: ""
            Regex("Stagione\\s+$seasonNum", RegexOption.IGNORE_CASE).containsMatchIn(titleText)
        } ?: return emptyList()

        val contentEl = seasonEl.selectFirst("div.les-content") ?: return emptyList()
        if (contentEl.selectFirst("a.ep-404") != null) return emptyList()

        return contentEl.select("a[href]").mapNotNull { a: Element ->
            val href = a.attr("href").trim()
            val text = a.text().trim()
            if (href.isBlank() || text.isBlank()) return@mapNotNull null

            val epMatch = Regex("Episodio\\s+(\\d+)", RegexOption.IGNORE_CASE).find(text)
            val epNumber = epMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            
            val tmdbEp = tmdbEpisodes.find { it.number == epNumber }

            Episode(
                id = href,
                number = epNumber,
                title = tmdbEp?.title ?: text,
                poster = tmdbEp?.poster,
                overview = tmdbEp?.overview
            )
        }
    }

    private fun cleanTitle(title: String): String {
        return title.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "").trim()
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        return try {
            val url = if (page <= 1) id else "${id.trimEnd('/')}/page/$page/"
            val doc = getDocument(url)
            val name = ""
            val shows = doc.select("div.movies-list.movies-list-full div.ml-item").mapNotNull { el: Element ->
                parseGridItem(el) as? Show
            }
            Genre(id = id, name = name, shows = shows)
        } catch (_: Exception) {
            Genre(id = id, name = "", shows = emptyList())
        }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val doc = getDocument(id)

        val name = ""

        if (page > 1) {
            return People(
                id = id,
                name = name,
                filmography = emptyList()
            )
        }

        val filmography = doc.select("div.movies-list.movies-list-full div.ml-item")
            .mapNotNull { el: Element -> parseGridItem(el) as? Show }

        return People(
            id = id,
            name = name,
            filmography = filmography
        )
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val doc = getDocument(id)

        // Same logic for both Movies and Episodes as they share the HTML structure
        return doc.select("div#player2 div[id^=tab]").mapIndexedNotNull { index, tabDiv ->
            val iframe = tabDiv.selectFirst("div.movieplay iframe")
            val iframeSrc = iframe?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: iframe?.attr("src")?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null

            val finalUrl = iframeSrc.trim()

            try {
                val hostName = finalUrl.toHttpUrl().host
                    .replaceFirst("www.", "")
                    .substringBefore(".")
                    .replaceFirstChar { char ->
                        if (char.isLowerCase()) char.titlecase() else char.toString()
                    }
                
                val serverName = "Server ${index + 1} - $hostName"

                Video.Server(
                    id = finalUrl,
                    name = serverName,
                    src = finalUrl
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.src, server)
    }
}
