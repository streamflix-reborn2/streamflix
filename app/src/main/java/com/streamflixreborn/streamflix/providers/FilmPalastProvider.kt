package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
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
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.TmdbUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.OkHttpClient.Builder
import okhttp3.ResponseBody
import okhttp3.dnsoverhttps.DnsOverHttps
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Url
import java.io.File
import java.util.concurrent.TimeUnit
import com.streamflixreborn.streamflix.utils.UserPreferences

object FilmPalastProvider : Provider {

    private const val DEFAULT_BASE_URL = "https://filmpalast.to/"

    override val name = "Filmpalast"

    override val baseUrl: String
        get() = UserPreferences.resolveProviderBaseUrl(name, DEFAULT_BASE_URL)

    override val logo: String
        get() = "${baseUrl.trimEnd('/')}/themes/downloadarchive/images/logo.png"
    override val language = "de"

    @Volatile
    private var cachedService: FilmpalastService? = null

    @Volatile
    private var cachedServiceBaseUrl: String? = null

    private val service: FilmpalastService
        get() {
            val currentBaseUrl = baseUrl.trimEnd('/') + "/"
            val currentService = cachedService

            if (currentService != null && cachedServiceBaseUrl == currentBaseUrl) {
                return currentService
            }

            return synchronized(this) {
                val synchronizedService = cachedService

                if (
                    synchronizedService != null &&
                    cachedServiceBaseUrl == currentBaseUrl
                ) {
                    synchronizedService
                } else {
                    FilmpalastService.build(currentBaseUrl).also {
                        cachedService = it
                        cachedServiceBaseUrl = currentBaseUrl
                    }
                }
            }
        }

    private fun siteUrl(path: String): String =
        "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

    override suspend fun getHome(): List<Category> {
        val document = service.getHome()
        val featured = coroutineScope {
            document.select("div.headerslider ul#sliderDla li").map { li ->
                async {
                    val title = li.select("span.title.rb").text()
                    val href = li.select("a.moviSliderPlay").attr("href")
                    val id = href.substringAfterLast("/")
                    val posterSrc = li.select("a img").attr("src")
                    val fullPosterUrl = if (posterSrc.startsWith("/")) {
                        siteUrl(posterSrc)
                    } else {
                        posterSrc
                    }
                    val overview = li.select("div.moviedescription").text()

                    val releaseYearText = li.select("span.releasedate b").text()

                    val imdbRatingText =
                        li.select("span.views b").lastOrNull()?.text()?.split("/")?.get(1)?.trim()
                    val rating = imdbRatingText?.toDoubleOrNull() ?: 0.0

                    val tmdbMovie = TmdbUtils.getMovie(title, language = language)

                    Movie(
                        id = id,
                        title = title,
                        overview = overview,
                        released = releaseYearText,
                        rating = rating,
                        poster = fullPosterUrl,
                        banner = tmdbMovie?.banner
                    )
                }
            }.awaitAll()
        }


        val main_content = document.select("div#content article").map { article ->
            val href = article.selectFirst("h2 a")?.attr("href") ?: ""
            val title = article.selectFirst("h2 a")?.text() ?: ""
            val posterSrc = article.selectFirst("a img")?.attr("src") ?: ""

            val fullPosterUrl = if (posterSrc.startsWith("/")) {
                siteUrl(posterSrc)
            } else {
                posterSrc
            }

            val info = article.select("*").toInfo()

            Movie(
                id = href.substringAfterLast("/"),
                title = title,
                released = info.released,
                quality = info.quality,
                rating = info.rating ?: 0.0,
                poster = fullPosterUrl
            )
        }
        val tvShowsDocument = service.getTvShowsHome()
        val tvShows = tvShowsDocument.select("div#content article").map { article ->
            val href = article.selectFirst("h2 a")?.attr("href") ?: ""
            val title = article.selectFirst("h2 a")?.text() ?: ""
            val posterSrc = article.selectFirst("a img")?.attr("src") ?: ""

            val fullPosterUrl = if (posterSrc.startsWith("/")) {
                siteUrl(posterSrc)
            } else {
                posterSrc
            }

            val info = article.select("*").toInfo()
                
                TvShow(
                id = href.substringAfterLast("/"),
                    title = title,
                    released = info.released,
                    quality = info.quality,
                    rating = info.rating ?: 0.0,
                    poster = fullPosterUrl
                )
        }

        return listOf(
            Category(name = "Featured", list = featured),
            Category(name = "Filme", list = main_content),
            Category(name = "Serien", list = tvShows)
        )
    }


    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            val document = service.getHome()
            val genres = document.select("aside#sidebar section#genre ul li a").map { element ->
                val text = element.text()
                Genre(text, text)
            }
            return genres
        }
        var document = service.searchNoPage(query)

        if (page > 1){
            val paging = document.selectFirst("div#paging a.pageing.button-small.rb")
            if (paging != null){
                document = service.search(query, page)
            } else {
                return emptyList()
            }
        }

        val results = document.select("div#content article").map { article ->
            val href = article.selectFirst("h2 a")?.attr("href") ?: ""
            val title = article.selectFirst("h2 a")?.text() ?: ""
            val posterSrc = article.selectFirst("a img")?.attr("src") ?: ""

            val fullPosterUrl = if (posterSrc.startsWith("/")) {
                siteUrl(posterSrc)
            } else {
                posterSrc
            }

            val info = article.select("*").toInfo()

            // Determine if it's a movie or TV show based on season/episode info in title
            val isTvShow = title.matches(Regex(".*S\\d+E\\d+.*"))
            
            if (isTvShow) {
                TvShow(
                    id = href.substringAfterLast("/"),
                    title = title,
                    released = info.released,
                    quality = info.quality,
                    rating = info.rating ?: 0.0,
                    poster = fullPosterUrl
                )
            } else {
            Movie(
                id = href.substringAfterLast("/"),
                title = title,
                released = info.released,
                quality = info.quality,
                rating = info.rating ?: 0.0,
                poster = fullPosterUrl
            )
            }
        }.distinctBy { 
            when (it) {
                is Movie -> it.id
                is TvShow -> it.id
            }
        }

        return results

    }



    override suspend fun getMovie(id: String): Movie {
        val relativeId = siteUrl("stream/$id");
        val document = service.getMoviePage(relativeId)
        val title = document.selectFirst("h2")?.text() ?: ""
        val poster = document.selectFirst("img.cover2")?.attr("src")?.let {
            if (it.startsWith("http")) it else siteUrl(it)
        }
        val description = document.selectFirst("span[itemprop=description]")?.text()
        val rating = document.selectFirst("div#star-rate")?.attr("data-rating")?.toDoubleOrNull()
        val genres =
            document.select("ul#detail-content-list > li:has(p:matchesOwn(Kategorien, Genre)) a")
                .map { Genre(id = it.text().trim(), name = it.text().trim()) }
        val directors = document.select("ul#detail-content-list > li:has(p:matchesOwn(Regie)) a")
            .map { People(id = it.text().trim(), name = it.text().trim()) }
        val actors =
            document.select("ul#detail-content-list > li:has(p:matchesOwn(Schauspieler)) a")
                .map { People(id = it.text().trim(), name = it.text().trim()) }

        val tmdbMovie = TmdbUtils.getMovie(title, language = language)

        return Movie(
            id = id,
            title = title,
            poster = poster,
            banner = tmdbMovie?.banner,
            genres = tmdbMovie?.genres ?: genres,
            directors = directors,
            cast = actors.map { person ->
                val tmdbPerson = tmdbMovie?.cast?.find { it.name.equals(person.name, ignoreCase = true) }
                person.copy(image = tmdbPerson?.image)
            },
            rating = tmdbMovie?.rating ?: rating,
            overview = tmdbMovie?.overview ?: description,
            released = tmdbMovie?.released?.let { "${it.get(java.util.Calendar.YEAR)}" } ?: document.selectFirst("ul#detail-content-list > li:has(p:matchesOwn(Release)) a")?.text()?.trim(),
            runtime = tmdbMovie?.runtime,
            trailer = tmdbMovie?.trailer,
            imdbId = tmdbMovie?.imdbId
        )
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val relativeId = siteUrl("stream/$id");
        val document = service.getMoviePage(relativeId)
        val servers = mutableListOf<Video.Server>()

        val serverBlocks = document.select("ul.currentStreamLinks")
        val keywords = listOf("bigwarp", "vinovo")
        for (block in serverBlocks) {
            val name = block.selectFirst("li.hostBg p.hostName")?.text()?.trim() ?: "Unbekannt"
            var linkElement = block.selectFirst("a[href]")
            var url = linkElement?.attr("href")?.trim()
            if (linkElement == null){
                linkElement = block.selectFirst("a[data-player-url]")
                url = linkElement?.attr("data-player-url")?.trim();
            }


            if (!url.isNullOrEmpty()) {
                val displayName = if (keywords.none { name.lowercase().contains(it) }) {
                    name
                } else {
                    "$name (VLC Only)"
                }
                servers.add(
                    Video.Server(
                        id = name.split(" ")[0],
                        name = displayName,
                        src = url
                    )
                )
            }
        }

        return servers
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val response = service.getRedirectLink(server.src)
            .let { response -> response.raw() as okhttp3.Response }


        val videoUrl = response.request.url
        val link = when (server.name) {
            "VOE" -> {
                val baseUrl = "https://voe.sx"
                val path = videoUrl.encodedPath.trimStart('/')
                "$baseUrl/e/$path?"
            }

            else -> videoUrl.toString()
        }
        return Extractor.extract(link, server)
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val document = service.getMovies(page)
        val movies = document.select("div#content article").map { article ->
            val href = article.selectFirst("h2 a")?.attr("href") ?: ""
            val title = article.selectFirst("h2 a")?.text() ?: ""
            val posterSrc = article.selectFirst("a img")?.attr("src") ?: ""

            val fullPosterUrl = if (posterSrc.startsWith("/")) {
                siteUrl(posterSrc)
            } else {
                posterSrc
            }

            val info = article.select("*").toInfo()

            Movie(
                id = href.substringAfterLast("/"),
                title = title,
                released = info.released,
                quality = info.quality,
                rating = info.rating ?: 0.0,
                poster = fullPosterUrl
            )
        }
        return movies
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val document = service.getTvShows(page)
        val shows = document.select("div#content article").map { article ->
            val href = article.selectFirst("h2 a")?.attr("href") ?: ""
            val title = article.selectFirst("h2 a")?.text() ?: ""
            val posterSrc = article.selectFirst("a img")?.attr("src") ?: ""

            val fullPosterUrl = if (posterSrc.startsWith("/")) {
                siteUrl(posterSrc)
            } else {
                posterSrc
            }

            val info = article.select("*").toInfo()
                
                TvShow(
                id = href.substringAfterLast("/"),
                    title = title,
                    released = info.released,
                    quality = info.quality,
                    rating = info.rating ?: 0.0,
                    poster = fullPosterUrl
                )
            }
        return shows
    }

    override suspend fun getTvShow(id: String): TvShow {
        val relativeId = siteUrl("stream/$id")
        val document = service.getTvShow(relativeId)
        val title = document.selectFirst("h2")?.text() ?: ""
        val poster = document.selectFirst("img.cover2")?.attr("src")?.let {
            if (it.startsWith("http")) it else siteUrl(it)
        }
        val description = document.selectFirst("span[itemprop=description]")?.text()
        val rating = document.selectFirst("div#star-rate")?.attr("data-rating")?.toDoubleOrNull()
        val genres = document.select("ul#detail-content-list > li:has(p:matchesOwn(Kategorien, Genre)) a")
            .map { Genre(id = it.text().trim(), name = it.text().trim()) }
        val directors = document.select("ul#detail-content-list > li:has(p:matchesOwn(Regie)) a")
            .map { People(id = it.text().trim(), name = it.text().trim()) }
        val actors = document.select("ul#detail-content-list > li:has(p:matchesOwn(Schauspieler)) a")
            .map { People(id = it.text().trim(), name = it.text().trim()) }

        // Parse seasons and episodes
        val seasons = mutableListOf<Season>()
        
        val cleanedTitle = title.replace(Regex("""\s+S\d+E\d+.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+S\d+.*""", RegexOption.IGNORE_CASE), "")
            .trim()
            
        val tmdbTvShow = TmdbUtils.getTvShow(cleanedTitle, language = language)

        document.select("div#staffelWrapper div.staffelWrapperLoop").forEachIndexed { seasonIndex, seasonBlock ->
            val seasonNumber = seasonIndex + 1
            val episodes = mutableListOf<Episode>()
            
            seasonBlock.select("ul.staffelEpisodenList li a.getStaffelStream").forEachIndexed { episodeIndex, episodeLink ->
                val epTitle = episodeLink.ownText().trim()
                val epHref = episodeLink.attr("href")
                val fullEpHref = if (epHref.startsWith("//")) {
                    "https:$epHref"
                } else {
                    epHref
                }
                val epId = fullEpHref.substringAfterLast("/")
                
                episodes.add(
                    Episode(
                        id = epId,
                        number = episodeIndex + 1,
                        title = epTitle
                    )
                )
            }
            
            seasons.add(
                Season(
                    id = "${id}_$seasonNumber",
                    number = seasonNumber,
                    episodes = episodes,
                    poster = tmdbTvShow?.seasons?.find { it.number == seasonNumber }?.poster
                )
            )
        }

        return TvShow(
            id = id,
            title = title,
            poster = poster,
            banner = tmdbTvShow?.banner,
            genres = tmdbTvShow?.genres ?: genres,
            directors = directors,
            cast = actors.map { person ->
                val tmdbPerson = tmdbTvShow?.cast?.find { it.name.equals(person.name, ignoreCase = true) }
                person.copy(image = tmdbPerson?.image)
            },
            rating = tmdbTvShow?.rating ?: rating,
            overview = tmdbTvShow?.overview ?: description,
            released = tmdbTvShow?.released?.let { "${it.get(java.util.Calendar.YEAR)}" } ?: document.selectFirst("ul#detail-content-list > li:has(p:matchesOwn(Release)) a")?.text()?.trim(),
            runtime = tmdbTvShow?.runtime,
            trailer = tmdbTvShow?.trailer,
            imdbId = tmdbTvShow?.imdbId,
            seasons = seasons
        )
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val document = service.getGenre(id, page)

        val shows = document.select("div#content article").map { article ->
            val aTag = article.selectFirst("h2 a")
            val href = aTag?.attr("href").orEmpty()
            val title = aTag?.text().orEmpty()

            val posterSrc = article.selectFirst("a img")?.attr("src").orEmpty()
            val fullPosterUrl = if (posterSrc.startsWith("/")) {
                siteUrl(posterSrc)
            } else {
                posterSrc
            }

            val info = article.select("*").toInfo()

            Movie(
                id = href.substringAfterLast("/"),
                title = title,
                released = info.released,
                quality = info.quality,
                rating = info.rating ?: 0.0,
                poster = fullPosterUrl
            )
        }

        return Genre(
            id = id,
            name = id.replaceFirstChar { it.uppercase() },
            shows = shows
        )
    }


    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split("_")
        if (parts.size != 2) return emptyList()
        
        val showId = parts[0]
        val seasonNumber = parts[1].toIntOrNull() ?: return emptyList()
        
        val relativeId = siteUrl("stream/$showId")
        val document = service.getTvShow(relativeId)
        val title = document.selectFirst("h2")?.text() ?: ""

        val cleanedTitle = title.replace(Regex("""\s+S\d+E\d+.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+S\d+.*""", RegexOption.IGNORE_CASE), "")
            .trim()

        val tmdbTvShow = TmdbUtils.getTvShow(cleanedTitle, language = language)
        val tmdbEpisodes = tmdbTvShow?.let { TmdbUtils.getEpisodesBySeason(it.id, seasonNumber, language = language) } ?: emptyList()
        
        val episodes = mutableListOf<Episode>()
        
        document.select("div#staffelWrapper div.staffelWrapperLoop").forEachIndexed { index, seasonBlock ->
            if (index + 1 == seasonNumber) {
                seasonBlock.select("ul.staffelEpisodenList li a.getStaffelStream").forEachIndexed { episodeIndex, episodeLink ->
                    val epTitle = episodeLink.ownText().trim()
                    val epHref = episodeLink.attr("href")
                    val fullEpHref = if (epHref.startsWith("//")) {
                        "https:$epHref"
                    } else {
                        epHref
                    }
                    val epId = fullEpHref.substringAfterLast("/")
                    val epNumber = episodeIndex + 1

                    val tmdbEp = tmdbEpisodes.find { it.number == epNumber }
                    
                    episodes.add(
                        Episode(
                            id = epId,
                            number = epNumber,
                            title = tmdbEp?.title ?: epTitle,
                            poster = tmdbEp?.poster,
                            overview = tmdbEp?.overview
                        )
                    )
                }
            }
        }
        
        return episodes
    }
    override suspend fun getPeople(id: String, page: Int): People {
        val url = siteUrl("search/title/$id")
        val document = service.getPeoplePage(url)
        val name = document.selectFirst("h1")?.text() ?: ""
        val image = document.selectFirst("img.cover2")?.attr("src")?.let {
            if (it.startsWith("http")) it else siteUrl(it)
        }
        
        // Parse filmography (same structure as movies/series)
        val filmography = document.select("div#content article").map { article ->
            val href = article.selectFirst("h2 a")?.attr("href") ?: ""
            val title = article.selectFirst("h2 a")?.text() ?: ""
            val posterSrc = article.selectFirst("a img")?.attr("src") ?: ""

            val fullPosterUrl = if (posterSrc.startsWith("/")) {
                siteUrl(posterSrc)
            } else {
                posterSrc
            }

            val info = article.select("*").toInfo()

            // Determine if it's a movie or TV show based on season/episode info in title
            val isTvShow = title.matches(Regex(".*S\\d+E\\d+.*"))
            
            if (isTvShow) {
                TvShow(
                    id = href.substringAfterLast("/"),
                    title = title,
                    released = info.released,
                    quality = info.quality,
                    rating = info.rating ?: 0.0,
                    poster = fullPosterUrl
                )
            } else {
                Movie(
                    id = href.substringAfterLast("/"),
                    title = title,
                    released = info.released,
                    quality = info.quality,
                    rating = info.rating ?: 0.0,
                    poster = fullPosterUrl
                )
            }
        }
        
        return People(
            id = id,
            name = name,
            image = image,
            filmography = filmography
        )
    }


    private fun Elements.toInfo() =
        this.mapNotNull { it.text().trim().takeIf { it.isNotEmpty() } }.let { textList ->
            val starCount = this.select("img[src*=star_on]").size
            val rating = starCount / 10.0

            object {
                val rating =
                    rating.takeIf { starCount > 0 }

                val quality = textList.find { it in listOf("HD", "SD", "CAM", "TS", "HDRip") }

                val released = textList.find { it.matches(Regex("\\d{4}")) }

            }
        }


    interface FilmpalastService {

        companion object {
            private fun getOkHttpClient(): OkHttpClient {
                val appCache = Cache(File("cacheDir", "okhttpcache"), 10 * 1024 * 1024)
                val clientBuilder = Builder().cache(appCache).readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)

                val clientToReturn = clientBuilder.dns(DnsResolver.doh).build()
                return clientToReturn
            }

            fun build(baseUrl: String): FilmpalastService {
                val client = getOkHttpClient()
                val retrofit = Retrofit.Builder().baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create()).client(client).build()
                return retrofit.create(FilmpalastService::class.java)
            }
        }

        @GET("movies/new/page/1")
        suspend fun getHome(): Document

        @GET("serien/view/page/1")
        suspend fun getTvShowsHome(): Document

        @GET
        suspend fun getMoviePage(@Url url: String): Document

        @GET
        suspend fun getTvShow(@Url url: String): Document

        @GET("movies/new/page/{page}")
        suspend fun getMovies(@Path("page") page: Int): Document

        @GET("serien/view/page/{page}")
        suspend fun getTvShows(@Path("page") page: Int): Document

        @GET("search/title/{query}/{page}")
        suspend fun search(@Path("query") query: String,@Path("page") page: Int): Document
        @GET("search/title/{query}")
        suspend fun searchNoPage(@Path("query") query: String): Document

        @GET
        @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        suspend fun getRedirectLink(@Url url: String): Response<ResponseBody>

        @GET("search/genre/{genre}/{page}")
        suspend fun getGenre(@Path("genre") genre: String, @Path("page") page: Int): Document

        @GET
        suspend fun getPeoplePage(@Url url: String): Document

    }

}
