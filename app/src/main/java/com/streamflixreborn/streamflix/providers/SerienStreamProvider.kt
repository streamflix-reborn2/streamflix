package com.streamflixreborn.streamflix.providers

import android.annotation.SuppressLint
import android.net.Uri
import android.content.Context
import android.util.Base64
import android.util.Log
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.SerienStreamDatabase
import com.streamflixreborn.streamflix.database.dao.TvShowDao
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TmdbUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


object SerienStreamProvider : Provider {

    private const val DEFAULT_DOMAIN = "serienstream.to"

    override val baseUrl: String
        get() = currentBaseUrl()
    @SuppressLint("StaticFieldLeak")
    override val name = Base64.decode(
        "U2VyaWVuU3RyZWFt", Base64.NO_WRAP
    ).toString(Charsets.UTF_8)
    override val logo
        get() = "${currentBaseUrl()}assets/logos/logo.svg"
    override val language = "de"
    @Volatile
    private var service: SerienStreamService? = null
    @Volatile
    private var serviceBaseUrl: String? = null


    private var tvShowDao: TvShowDao? = null
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        if (tvShowDao == null) {
            tvShowDao = SerienStreamDatabase.getInstance(context).tvShowDao()

            this.appContext = context.applicationContext

        }
    }


    private fun getDao(): TvShowDao {
        return tvShowDao ?: throw IllegalStateException("SerienStreamProvider not initialized")
    }

    fun reloadService() {
        service = null
        serviceBaseUrl = null
    }

    private fun currentDomain(): String {
        return UserPreferences.serienstreamDomain.trim().ifBlank { DEFAULT_DOMAIN }
    }

    private fun currentBaseUrl(): String {
        val domain = currentDomain()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
        return "https://$domain/"
    }

    private fun getService(): SerienStreamService {
        val currentBase = currentBaseUrl()
        val cached = service
        if (cached != null && serviceBaseUrl == currentBase) {
            return cached
        }

        synchronized(this) {
            val synced = service
            if (synced != null && serviceBaseUrl == currentBase) {
                return synced
            }
            return SerienStreamService.build(currentBase).also {
                service = it
                serviceBaseUrl = currentBase
            }
        }
    }


    private fun getTvShowIdFromLink(link: String): String {
        return link.pathSegments().firstOrNull().orEmpty()
    }

    private fun getSeasonIdFromLink(link: String): String {
        val segments = link.pathSegments()
        val justTvShowId = segments.getOrNull(0).orEmpty()
        val justTvShowSeason = segments.getOrNull(1).orEmpty()
        return listOf(justTvShowId, justTvShowSeason).filter { it.isNotBlank() }.joinToString("/")
    }

    private fun getEpisodeIdFromLink(link: String): String {
        return link.pathSegments().take(3).joinToString("/")
    }

    override suspend fun getHome(): List<Category> {
        val document = getService().getHome()
        val categories = mutableListOf<Category>()
        categories.add(
            Category(name = Category.FEATURED,
                list = document.select(".home-hero-slide").map {
                    TvShow(
                        id = getTvShowIdFromLink(it.selectFirst("a.home-hero-cta")?.attr("href") ?: ""),
                        title = it.selectFirst("h2.home-hero-title")?.text() ?: "",
                        banner = normalizeImageUrl(
                            it.select("picture.home-hero-bg img")
                                .flatMap { img -> img.attr("srcset").split(",") }
                                .find { url -> url.contains("hero-2x-desktop") }
                                ?.trim()?.split(" ")?.firstOrNull()

                                ?: it.select("picture.home-hero-bg source[type='image/webp']")
                                    .flatMap { s -> s.attr("srcset").split(",") }
                                    .find { url -> url.contains("hero-2x-desktop") }
                                    ?.trim()?.split(" ")?.firstOrNull()

                                ?: it.select("picture.home-hero-bg source[type='image/avif']")
                                    .flatMap { s -> s.attr("srcset").split(",") }
                                    .find { url -> url.contains("hero-2x-desktop") }
                                    ?.trim()?.split(" ")?.firstOrNull()
                        )

                    )
                })
        )
        categories.add(
            Category(name = "Angesagt",
                list = document.select(".trending-widget .swiper-slide").map {
                    TvShow(
                        id = getTvShowIdFromLink(it.selectFirst("h3.trend-title a")?.attr("href") ?: ""),
                        title = it.selectFirst("h3.trend-title a")?.text()?.trim() ?: "",
                        poster = normalizeImageUrl(it.extractPoster()))
                })
        )
        categories.add(
            Category(name = "Neu auf S.to",
                list = document.select("section.continue-widget.new-shows-slider .swiper-slide").map {
                    TvShow(
                        id = getTvShowIdFromLink(
                            it.selectFirst("a.continue-cover, h3.continue-title a")?.attr("href") ?: ""
                        ),
                        title = it.selectFirst("h3.continue-title a")?.text()?.trim() ?: "",
                        poster = normalizeImageUrl(it.extractPoster()))
                })
        )
        document.select("#discover-blocks .col").forEach { column ->
            val categoryName = column.selectFirst("h4")?.text()?.trim() ?: ""
            if (categoryName.isNotEmpty()) {
                categories.add(
                    Category(name = categoryName,
                        list = column.select("li").map {
                            TvShow(
                                id = getTvShowIdFromLink(it.selectFirst("a")?.attr("href") ?: ""),
                                title = it.selectFirst("span.h6")?.text()?.trim() ?: "",
                                poster = normalizeImageUrl(it.extractPoster()))
                        })
                )
            }
        }
        categories.add(
            Category(name = "Derzeit beliebte Serien",
                list = document.select("div.carousel:contains(Derzeit beliebt) div.coverListItem").map {
                    TvShow(
                        id = getTvShowIdFromLink(it.selectFirst("a")?.attr("href") ?: ""),
                        title = it.selectFirst("a h3")?.text() ?: "",
                        poster = normalizeImageUrl(it.extractPoster())
                    )
                })
        )
        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            val document = getService().getSeriesListWithCategories()
            return document
                .select("div[data-group='genres'] .list-inline-item a")
                .map {
                    Genre(
                        id = it.attr("href").substringAfterLast("/"),
                        name = it.text().trim()
                    )
                }
        }
        val document = getService().search(query, page)
        return document
            .select("div.search-results-list div.card.cover-card")
            .mapNotNull { card ->
                val link = card.selectFirst("a[href^=/serie/]")?.attr("href")
                    ?: return@mapNotNull null

                TvShow(
                    id = getTvShowIdFromLink(link),
                    title = card.selectFirst("h6.show-title")?.text().orEmpty(),
                    poster = normalizeImageUrl(card.extractPoster())
                )
            }
            .distinctBy { it.id }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        throw Exception("Keine Filme verfügbar")
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val document = getService().getAllTvShows(page)
        return document
            .select("div.search-results-list div.card.cover-card")
            .mapNotNull { card ->
                val link = card.selectFirst("a[href^=/serie/]")?.attr("href")
                    ?: return@mapNotNull null
                TvShow(
                    id = getTvShowIdFromLink(link),
                    title = card.selectFirst("h6.show-title")?.text().orEmpty(),
                    poster = normalizeImageUrl(card.extractPoster())
                )
            }
            .distinctBy { it.id }
    }

    override suspend fun getMovie(id: String): Movie {
        throw Exception("Keine Filme verfügbar")
    }

    override suspend fun getTvShow(id: String): TvShow {
        val document = getService().getTvShow(id)
        val title = document.selectFirst("h1")?.text()?.trim() ?: ""
        
        val tmdbTvShow = TmdbUtils.getTvShow(title, language = language)
        
        val localRating = if (tmdbTvShow?.rating == null) {
            val imdbTitleUrl = document.selectFirst("a[href*='imdb.com']")?.attr("href") ?: ""
            val imdbDocument = if (imdbTitleUrl.isNotEmpty()) try { getService().getCustomUrl(imdbTitleUrl) } catch (e: Exception) { null } else null
            imdbDocument?.selectFirst("div[data-testid='hero-rating-bar__aggregate-rating__score'] span")
                ?.text()?.toDoubleOrNull() ?: document.selectFirst(".text-white-50:contains(Bewertungen)")?.text()?.split(" ")?.firstOrNull()?.toDoubleOrNull() ?: 0.0
        } else {
            0.0
        }
        
        val localCast = document.select(".series-group:contains(Besetzung) a").map {
            val actorName = it.text()
            val tmdbPerson = tmdbTvShow?.cast?.find { person -> person.name.equals(actorName, ignoreCase = true) }
            People(
                id = getTvShowIdFromLink(it.attr("href")),
                name = actorName,
                image = tmdbPerson?.image
            )
        }
        
        return TvShow(id = id,
            title = title,
            overview = tmdbTvShow?.overview ?: document.selectFirst("span.description-text")?.text() ?: document.selectFirst("div.series-description p")?.text(),
            released = tmdbTvShow?.released?.let { "${it.get(java.util.Calendar.YEAR)}" } 
                ?: document.selectFirst("a.small.text-muted")?.text() ?: "",
            rating = tmdbTvShow?.rating ?: localRating,
            runtime = tmdbTvShow?.runtime,
            directors = document.select(".series-group:contains(Regisseur) a").map {
                People(
                    id = getTvShowIdFromLink(it.attr("href")),
                    name = it.text()
                )
            },
            cast = localCast,
            genres = tmdbTvShow?.genres ?: document.select(".series-group:contains(Genre) a").map {
                Genre(
                    id = it.text().lowercase(Locale.getDefault()),
                    name = it.text()
                )
            },
            trailer = tmdbTvShow?.trailer ?: document.selectFirst("div[itemprop='trailer'] a")?.attr("href") ?: "",
            poster = tmdbTvShow?.poster
                ?: normalizeImageUrl(document.extractShowPoster()),
            banner = tmdbTvShow?.banner
                ?: normalizeImageUrl(document.extractShowBanner()),
            seasons = document.select("#season-nav ul li a").map {
                val seasonText = it.text().trim()
                val seasonNumber = seasonText.toIntOrNull() ?: 0
                Season(
                    id = getSeasonIdFromLink(it.attr("href")),
                    number = seasonNumber,
                    title = if (seasonText == "Filme") "Filme" else "Staffel $seasonNumber",
                    poster = tmdbTvShow?.seasons?.find { s -> s.number == seasonNumber }?.poster
                )
            },
            imdbId = tmdbTvShow?.imdbId
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val linkWithSplitData = seasonId.split("/")
        val showName = linkWithSplitData[0]
        val seasonNumberStr = linkWithSplitData[1]
        val seasonNumber = Regex("""\d+""").find(seasonNumberStr)!!.value.toInt()

        val document = getService().getTvShowEpisodes(showName, seasonNumberStr)
        
        // Get show title for TMDB lookup
        val title = (document.selectFirst("h1")?.text()?.trim() ?: "").split(" Staffel").firstOrNull()?.trim() ?: ""
        
        val tmdbTvShow = TmdbUtils.getTvShow(title, language = language)
        val tmdbEpisodes = tmdbTvShow?.let { 
            TmdbUtils.getEpisodesBySeason(it.id, seasonNumber, language = language) 
        } ?: emptyList()
        
        return document.select("tr.episode-row").map {
            val episodeNumber = it.selectFirst(".episode-number-cell")?.text()?.trim()?.toIntOrNull() ?: 0
            val tmdbEp = tmdbEpisodes.find { ep -> ep.number == episodeNumber }
            
            val episodeLink = it.attr("onclick")
                .substringAfter("window.location='")
                .substringBefore("'")

            Episode(
                id = getEpisodeIdFromLink(episodeLink),
                number = episodeNumber,
                title = tmdbEp?.title ?: it.selectFirst(".episode-title-ger")?.text() 
                    ?: it.selectFirst(".episode-title-eng")?.text() 
                    ?: "Episode $episodeNumber",
                poster = tmdbEp?.poster,
                overview = tmdbEp?.overview
            )
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {

        try {
            val shows = mutableListOf<TvShow>()
            val document = getService().getGenre(id, page)
            document.select("div.row.g-3 > div").map {
                shows.add(
                    TvShow(
                        id = it.selectFirst("a")?.attr("href")
                            ?.let { it1 -> getTvShowIdFromLink(it1) } ?: "",
                        title = it.selectFirst("h6")?.text()?.trim() ?: "",
                        poster =normalizeImageUrl(it.extractPoster()))
                    )
            }
            return Genre(id = id, name = id.replaceFirstChar { it.uppercase() }, shows = shows)
        } catch (e: Exception) {
            Log.e("SerienStreamProvider", "Error fetching genre $id page $page", e)
            return Genre(id = id, name = id, shows = emptyList())
        }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        if (page > 1) return People(id, "")
        val document = getService().getPeople(id)
        return People(id = id,
            name = document.selectFirst("h1 strong")?.text() ?: "",
            filmography = document.select("div.row.g-3 > div").map {
                TvShow(
                    id = it.selectFirst("a")?.attr("href")?.let { it1 -> getTvShowIdFromLink(it1) } ?: "",
                    title = it.selectFirst("h6 a")?.text() ?: "",
                    poster = it.selectFirst("img")?.let { img -> img.attr("data-src").takeIf { it.isNotEmpty() } ?: img.attr("src") }
                )
            })
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val servers = mutableListOf<Video.Server>()
        val linkWithSplitData = id.split("/")
        val showName = linkWithSplitData[0]
        val seasonNumber = linkWithSplitData[1]
        val episodeNumber = linkWithSplitData[2]
        val document = getService().getTvShowEpisodeServers(showName, seasonNumber, episodeNumber)

        val elements = document.select("button.link-box")
        for (element in elements) {
            val serverName = element.attr("data-provider-name")
            val language = element.attr("data-language-label")
            val href = element.attr("data-play-url")
            
            if (href.isEmpty()) continue

            try {
                val redirectUrl = currentBaseUrl() + href.removePrefix("/")

                val serverAfterRedirect = try {
                    getService().getRedirectLink(redirectUrl)
                } catch (exception: Exception) {
                    val unsafeOkHttpClient = SerienStreamService.buildUnsafe(currentBaseUrl())
                    unsafeOkHttpClient.getRedirectLink(redirectUrl)
                }
                val videoUrl = (serverAfterRedirect.raw() as okhttp3.Response).request.url
                val videoUrlString = videoUrl.toString()
                
                servers.add(
                    Video.Server(
                        id = videoUrlString,
                        name = "$serverName ($language)"
                    )
                )
            } catch (e: Exception) {
                Log.e("SerienStreamProvider", "Failed to process server '$serverName' with URL '$href'")
            }
        }
        return servers

    }

    override suspend fun getVideo(server: Video.Server): Video {
        val link = server.id
        return Extractor.extract(link)
    }

    interface SerienStreamService {

        companion object {
            private fun OkHttpClient.Builder.applyBrowserHeaders(): OkHttpClient.Builder {
                return addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", NetworkClient.USER_AGENT)
                        .header(
                            "Accept",
                            "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                        )
                        .header("Accept-Language", "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7")
                        .build()
                    chain.proceed(request)
                }.cookieJar(NetworkClient.cookieJar)
            }

            private fun getOkHttpClient(): OkHttpClient {
                val appCache = Cache(File("cacheDir", "okhttpcache"), 10 * 1024 * 1024)
                val clientBuilder = OkHttpClient.Builder()
                    .cache(appCache)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)

                return clientBuilder
                    .applyBrowserHeaders()
                    .dns(DnsResolver.doh)
                    .build()
            }

            private fun getUnsafeOkHttpClient(): OkHttpClient {
                try {
                    val trustAllCerts = arrayOf<TrustManager>(
                        object : X509TrustManager {
                            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                        }
                    )
                    val sslContext = SSLContext.getInstance("SSL")
                    sslContext.init(null, trustAllCerts, SecureRandom())
                    val sslSocketFactory = sslContext.socketFactory

                    val appCache = Cache(File("cacheDir", "okhttpcache"), 10 * 1024 * 1024)
                    val clientBuilder = OkHttpClient.Builder()
                        .cache(appCache)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                        .hostnameVerifier { _, _ -> true }

                    return clientBuilder
                        .applyBrowserHeaders()
                        .dns(DnsResolver.doh)
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .build()
                } catch (e: Exception) {
                    throw RuntimeException(e)
                }
            }

            fun build(baseUrl: String): SerienStreamService {
                val client = getOkHttpClient()
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()
                return retrofit.create(SerienStreamService::class.java)
            }

            fun buildUnsafe(baseUrl: String): SerienStreamService {
                val client = getUnsafeOkHttpClient()
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()
                return retrofit.create(SerienStreamService::class.java)
            }
        }


        @GET(".")
        suspend fun getHome(): Document

        @GET("suche?tab=genres")
        suspend fun getSeriesListWithCategories(): Document

        @GET("serien-alphabet")
        suspend fun getSeriesListAlphabet(): Document

        @GET("suche")
        suspend fun search(
            @Query("term") keyword: String,
            @Query("page") page: Int,
            @Query("tab") tab: String = "shows"
        ): Document
        @GET("suche")
        suspend fun getAllTvShows( @Query("page") page: Int,
                                   @Query("tab") tab: String = "shows"): Document

        @GET("genre/{genreName}")
        suspend fun getGenre(
            @Path("genreName") genreName: String, @Query("page") page: Int
        ): Document

        @GET("{peopleId}")
        suspend fun getPeople(@Path("peopleId", encoded = true) peopleId: String): Document

        @GET("serie/{tvShowName}")
        suspend fun getTvShow(@Path("tvShowName") tvShowName: String): Document

        @GET("serie/{tvShowName}/{seasonNumber}")
        suspend fun getTvShowEpisodes(
            @Path("tvShowName") showName: String, @Path("seasonNumber") seasonNumber: String
        ): Document

        @GET("serie/{tvShowName}/{seasonNumber}/{episodeNumber}")
        suspend fun getTvShowEpisodeServers(
            @Path("tvShowName") tvShowName: String,
            @Path("seasonNumber") seasonNumber: String,
            @Path("episodeNumber") episodeNumber: String
        ): Document

        @GET
        @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        suspend fun getCustomUrl(@Url url: String): Document

        @GET
        @Headers(
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
            "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language: en-US,en;q=0.5",
            "Connection: keep-alive"
        )
        suspend fun getRedirectLink(@Url url: String): Response<ResponseBody>
    }

    fun Element.extractPoster(): String {
        posterCandidate("img[data-src]", "data-src")?.let { return it }
        posterCandidate("img[data-srcset]", "data-srcset")?.let { return it }
        select("source[data-srcset]")
            .firstNotNullOfOrNull { source -> source.attr("data-srcset").firstJpegSrcsetEntry() }
            ?.takeIf(::isUsablePosterUrl)
            ?.let { return it }
        posterCandidate("img[srcset]", "srcset")?.let { return it }
        posterCandidate("img[src]", "src")?.let { return it }
        return ""
    }

    fun Element.extractBanner(): String {
        bannerCandidate("img[data-src]", "data-src")?.let { return it }
        bannerCandidate("img[data-srcset]", "data-srcset")?.let { return it }
        select("source[data-srcset]")
            .firstNotNullOfOrNull { source -> source.attr("data-srcset").firstBackdropJpegSrcsetEntry() }
            ?.takeIf(::isUsableBannerUrl)
            ?.let { return it }
        bannerCandidate("img[srcset]", "srcset")?.let { return it }
        bannerCandidate("img[src]", "src")?.let { return it }
        return ""
    }

    fun Document.extractShowPoster(): String {
        selectFirst(".show-cover-mobile picture")
            ?.extractPoster()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        select("div.col-3.col-md-3.col-lg-2 picture")
            .firstNotNullOfOrNull { it.extractPoster().takeIf(String::isNotBlank) }
            ?.let { return it }
        return extractPoster()
    }

    fun Document.extractShowBanner(): String {
        selectFirst(".backdrop-picture picture")
            ?.extractBanner()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return extractBanner()
    }

    private fun Element.posterCandidate(selector: String, attribute: String): String? {
        return selectFirst(selector)
            ?.attr(attribute)
            ?.firstSrcsetEntry()
            ?.takeIf(::isUsablePosterUrl)
    }

    private fun Element.bannerCandidate(selector: String, attribute: String): String? {
        return select(selector)
            .firstNotNullOfOrNull { it.attr(attribute).firstBackdropJpegSrcsetEntry() }
            ?.takeIf(::isUsableBannerUrl)
    }

    private fun String.firstSrcsetEntry(): String? {
        val entries = srcsetEntries()
        return entries.firstOrNull(::looksLikeJpegUrl)
            ?: entries.firstOrNull()
    }

    private fun String.firstJpegSrcsetEntry(): String? {
        val entries = srcsetEntries()
        return entries.firstOrNull(::looksLikeJpegUrl) ?: entries.firstOrNull()
    }

    private fun String.firstBackdropJpegSrcsetEntry(): String? {
        val entries = srcsetEntries().filter(::isUsableBannerUrl)
        return entries.firstOrNull(::looksLikeJpegUrl) ?: entries.firstOrNull()
    }

    private fun String.srcsetEntries(): List<String> {
        return split(",")
            .mapNotNull { entry ->
                entry.trim()
                    .substringBefore(" ")
                    .takeIf { it.isNotBlank() }
            }
    }

    private fun looksLikeJpegUrl(url: String): Boolean {
        val normalized = url.lowercase()
        return normalized.endsWith(".jpg") ||
            normalized.endsWith(".jpeg") ||
            normalized.contains("format=jpg") ||
            normalized.contains("format=jpeg")
    }

    private fun isUsablePosterUrl(url: String): Boolean {
        return url.isNotBlank() &&
            !url.startsWith("data:", ignoreCase = true) &&
            !url.startsWith("javascript:", ignoreCase = true) &&
            url != "#" &&
            !url.contains("/backdrop/", ignoreCase = true) &&
            !url.contains("/assets/", ignoreCase = true) &&
            !url.contains("/logos/", ignoreCase = true) &&
            !url.contains("placeholder", ignoreCase = true) &&
            !url.contains("default", ignoreCase = true) &&
            !url.substringBefore("?").endsWith(".svg", ignoreCase = true)
    }

    private fun isUsableBannerUrl(url: String): Boolean {
        return url.isNotBlank() &&
            !url.startsWith("data:", ignoreCase = true) &&
            !url.startsWith("javascript:", ignoreCase = true) &&
            url != "#" &&
            url.contains("/backdrop/", ignoreCase = true) &&
            !url.contains("/assets/", ignoreCase = true) &&
            !url.contains("/logos/", ignoreCase = true) &&
            !url.substringBefore("?").endsWith(".svg", ignoreCase = true)
    }

    fun normalizeImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("http")) url
        else currentBaseUrl().trimEnd('/') + "/" + url.removePrefix("/")
    }

    private fun String.pathSegments(): List<String> {
        val uri = runCatching {
            when {
                startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true) -> Uri.parse(this)
                else -> Uri.parse("https://$this")
            }
        }.getOrNull() ?: return emptyList()
        return uri.pathSegments
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "serie" }
    }


}
