package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.extractors.FrembedExtractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import java.util.concurrent.TimeUnit
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.Response
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.UserPreferences
import okhttp3.ResponseBody
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.Header
import retrofit2.http.Query
import retrofit2.http.Url
import kotlin.collections.map

object FrembedProvider : Provider, ProviderPortalUrl, ProviderConfigUrl {
    override val name = "Frembed"

    override val defaultPortalUrl: String = "https://audin213.com/"

    override val portalUrl: String = defaultPortalUrl
        get() {
            val cachePortalURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL)
            return cachePortalURL.ifEmpty { field }
        }

    override val defaultBaseUrl: String = "https://frembed.casa/"
    override val baseUrl: String = defaultBaseUrl
        get() {
            val cacheURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            return cacheURL.ifEmpty { field }
        }

    override val logo: String
        get() {
            val cacheLogo = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_LOGO)
            return cacheLogo.ifEmpty { baseUrl + "favicon-32x32.png" }
        }
    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private lateinit var service: Service
    private var serviceInitialized = false
    private val initializationMutex = Mutex()

    private val genres: HashMap<String, String> = hashMapOf("28" to "Action",
        "12" to "Adventure",
        "16" to "Animation",
        "35" to "Comedy",
        "80" to "Crime",
        "99" to "Documentary",
        "19" to "Drama",
        "10751" to "Family",
        "14" to "Fantasy",
        "36" to "History",
        "27" to "Horror",
        "10402" to "Music",
        "9648" to "Mystery",
        "10749" to "Romance",
        "878" to "Sci-Fi",
        "10770" to "TV Movie",
        "53" to "Thriller",
        "10752" to "War",
        "37" to "Western"
    )

    data class FrembedCastItem(
        val id: Int,
        val name: String,
        val profile_path: String?
    )

    fun FrembedCastItem.toPeople(): People =
        People( id = id.toString(),
            name = name,
            image = profile_path)

    data class FrembedSimilarItem(
        val tmdb: Int,
        val title: String,
        val poster_path: String?
    )

    fun List<FrembedSimilarItem>.toListShow(movie: Boolean = false, tvshow: Boolean = false): List<Show> =
        if (movie == false && tvshow)
            this.map { it ->
                TvShow( id = it.tmdb.toString(),
                    title = it.title,
                    poster = it.poster_path?.w500
                )
            }
        else
            this.map { it ->
                Movie( id = it.tmdb.toString(),
                    title = it.title,
                    poster = it.poster_path?.w500
                )
            }

    fun List<FrembedSeasonResponse>.toListSeason(id:String, posters: List<String>) : List<Season> =
        this.mapIndexed { idx, it ->
            val poster = if (posters.isNotEmpty()) {
                posters[idx % posters.size].w500
            } else {
                null
            }
            Season( id="$id/"+it.sa, number = it.sa, title = "Saison "+it.sa, poster = poster?.w500 )
        }

    data class FrembedShowItem(
        val director: String?,
        val genres: String?,
        val imdb: String?,
        val tmdb: Int?,
        val overview: String?,
        val overview_fr: String?,
        val rating: Double?,
        val title: String?,
        val title_fr: String?,
        val trailer: String?,
        val year: String?,
        val poster: String?,
        val backdrops: List<String>?,
        val cast: List<FrembedCastItem>?
    )

    data class FrembedShortCutItem(
        val tmdb: String?,
        val id: Int?,
        val imdb: String?,
        val title: String?,
        val title_fr: String?,
        val name: String?,
        val director: String,
        val cast: List<FrembedCastItem>,
        val poster: String?,
        val poster_path: String?,
        val version: String?,
        val year: String?,
        val release_date: String?,
        val first_air_date: String?,
        val rating: Double?,
        var sa: Int?,
        var overview: String?,
        var overview_fr: String?,
        var trailer: String?,
        var media_type: String?,
    )

    data class FrembedListEpItem(
        val epi: Int,
        val id: Int,
        val title: String?
    )

    fun FrembedShortCutItem.toShow(movie: Boolean = false, tvshow: Boolean = false): Show =
        if ((sa != null && (media_type == null || media_type != "movie") && movie == false) || tvshow)
            TvShow(
                id = (tmdb?:id).toString(),
                title = buildString {
                    append(title_fr?:title?:name?:"TvShow")
                    if (sa != null) {
                        append(" - S${sa}")
                    } },
                poster = (poster?.w500)?:poster_path,
                banner = poster?.original,
                rating = rating
            )
        else {
            Movie(
                id = (tmdb?:id).toString(),
                title = title_fr?:title?:name?:"Movie",
                poster = (poster?.w500)?:poster_path,
                banner = poster?.original,
                rating = rating
            )
        }

    fun List<FrembedShortCutItem>.toCategorie(name: String): Category =
        Category(
            name = name,
            list = this.map { it.toShow() }
        )

    data class FrembedSeasonResponse(
        val episodes: List<FrembedListEpItem>,
        val sa: Int
    )

    data class FrembedMoviesResponse(
        val movies: List<FrembedShortCutItem>
    )

    data class FrembedTvShowsResponse(
        val series: List<FrembedShortCutItem>
    )

    data class FrembedSearchResponse(
        val movies: List<FrembedShortCutItem>,
        val tvShows: List<FrembedShortCutItem>
    )

    data class FrembedActorItem(
        val name: String,
        val birthday: String?,
        val deathday: String?,
        val profile_path: String?,
        val place_of_birth: String?,
        val biography: String?,
        val known_for_department: String?,
        val known_for: List<FrembedShortCutItem>?
    )

    data class FrembedSearchActorsResponse(
        val actor: FrembedActorItem,
        val movies: List<FrembedShortCutItem>
    )

    override suspend fun getHome(): List<Category> {
        initializeService()

        val categories = mutableListOf<Category>()

        try {
            val ranking = service.getApiPublic("ranking")
            categories.add(ranking.toCategorie(Category.FEATURED))
            val latest = service.getApiPublic("latest")
            categories.add(latest.toCategorie("Nouveaux films"))
            val updated = service.getApiPublic("updated")
            categories.add(updated.toCategorie("Films mis à jour"))
            val mostViewed = service.getApiView("most-viewed")
            categories.add(mostViewed.toCategorie("Meilleurs films"))
            val latestAdded = service.getApiView("latest-added-seasons")
            categories.add(latestAdded.toCategorie("Nouvelles séries"))
            val mostViewedSeasons = service.getApiView("most-viewed-seasons")
            categories.add(mostViewedSeasons.toCategorie("Meilleures séries"))

        } catch (e: Exception) { }

        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page == 1) {
            if (query.isEmpty()) {
                return genres.map { (id, label) ->
                    Genre(
                        id = id,
                        name = label
                    )
                }
            }
            initializeService()
        }

        val result = service.getApiSearch(page, query)

        return result.movies.map { it.toShow(true) as Movie } + result.tvShows.map { it.toShow(tvshow=true) as TvShow }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        if (page == 1) initializeService()
        return service.getMovies(page).movies.map { it.toShow() as Movie }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        if (page == 1) initializeService()
        return service.getTvShows(page).series.map { it.toShow() as TvShow }
    }

    override suspend fun getMovie(id: String): Movie {
        initializeService()

        var recommendations : List<Show> = try {
            service.getApiSimilar(id=id).toListShow(movie = true)
        } catch (e: Exception) {
            emptyList()
        }

        return service.getMovie(id).let { movie ->
            Movie(
                id = movie.tmdb.toString(),
                title = movie.title_fr?:movie.title?:"Movie",
                overview = movie.overview_fr?:movie.overview,
                released = movie.year,
                trailer = movie.trailer?.let { "https://www.youtube.com/watch?v=${movie.trailer}" },
                rating = movie.rating,
                poster = movie.poster?.w500,
                banner = movie.backdrops?.randomOrNull()?.original,
                imdbId = movie.imdb,
                genres = movie.genres?.split(", ")?.map { genre ->
                    Genre(
                        genre,
                        genre,
                    )
                } ?: emptyList(),
                directors = movie.director?.split(", ")?.map { director ->
                                People(
                                    id = director,
                                    name = director
                                )
                            } ?: listOf(),
                cast = movie.cast?.map { cast ->
                    People(
                        id = cast.id.toString(),
                        name = cast.name,
                        image = cast.profile_path?.original
                    ) } ?: listOf(),
                recommendations = recommendations
            )
        }
    }

    override suspend fun getTvShow(id: String): TvShow {
        initializeService()

        var recommendations : List<Show> = try {
            service.getApiSimilar(type="tv-show", id).toListShow(tvshow = true)
        } catch (e: Exception) {
            emptyList()
        }

        val tvshowp = service.getTvShow(id)

        var seasons : List<Season> = try {
            service.getApiListEp(id).toListSeason(id=id, posters = tvshowp.backdrops ?: emptyList())
        } catch (e: Exception) {
            emptyList()
        }

        return tvshowp.let { tvshow ->
                TvShow(
                    id = tvshow.tmdb.toString(),
                    title = tvshow.title_fr ?: tvshow.title ?: "TvShow",
                    overview = tvshow.overview_fr ?: tvshow.overview,
                    released = tvshow.year,
                    trailer = tvshow.trailer?.let { "https://www.youtube.com/watch?v=${tvshow.trailer}" },
                    rating = tvshow.rating,
                    poster = tvshow.poster?.w500,
                    banner = tvshow.backdrops?.randomOrNull()?.original,

                imdbId = tvshow.imdb,
                genres = tvshow.genres?.split(", ")?.map { genre ->
                    Genre(
                        genre,
                        genre,
                    )
                } ?: emptyList(),
                directors = tvshow.director?.split(", ")?.map { director ->
                    People(
                        id = director,
                        name = director
                    )
                } ?: listOf(),
                cast = tvshow.cast?.map { cast ->
                    People(
                        id = cast.id.toString(),
                        name = cast.name,
                        image = cast.profile_path?.original
                    ) } ?: listOf(),
                recommendations = recommendations,
                    seasons = seasons,

                    )
        }
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val (tvShowId, seasonNumber) = seasonId.split("/")
        val episodes : List<Episode> = try {
            service.getApiListEp(tvShowId).firstOrNull { it.sa.toString() == seasonNumber }.let { season ->
                season?.episodes?.map { ep ->
                    Episode(id = ep.id.toString(),
                        number = ep.epi,
                        title = ep.title ?: ("Episode "+ep.epi)
                    )
                } ?: listOf()
            }
        } catch (e: Exception) {
            listOf()
        }

        return episodes
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        initializeService()

        val result = service.getMovies(page, genre=id)

        val genre = Genre(id=id,
                          name=genres.getOrDefault(id, 0).toString(),
                          shows = result.movies.map { it.toShow(true) as Movie })
        return genre
    }

    override suspend fun getPeople(id: String, page: Int): People {
        if (page > 1) return People(id=id,name=name)
        initializeService()

        val result = service.getApiSearchActor(id = id)
        val actor = result.actor
        return People(
                id = id, name = actor.name,
                birthday = actor.birthday,
                deathday = actor.deathday,
                image = actor.profile_path,
                biography = actor.biography,
                filmography = result.movies.map { item -> item.toShow() }
            )

    }

    override suspend fun getVideo(server: Video.Server): Video {
        return when {
            server.video != null -> server.video!!
            else -> Extractor.extract(server.src)
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return FrembedExtractor(baseUrl).servers(videoType)
    }

    /**
     * Initializes the service with the current domain URL.
     * This function is necessary because the provider's domain frequently changes.
     * We fetch the latest URL from a dedicated website that tracks these changes.
     */
    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            if (forceRefresh || UserPreferences.getProviderCache(this,UserPreferences.PROVIDER_AUTOUPDATE) != "false") {
                val addressService = Service.buildAddressFetcher()
                try {
                    val document = addressService.getPortalHome()

                    val newUrl = document.selectFirst("a")
                        ?.attr("href")
                        ?.trim()
                    if (!newUrl.isNullOrEmpty()) {
                        Log.d("FrembedProvider", "Fetched newUrl from portal: $newUrl")
                        val raw = addressService.loadPageRaw(newUrl)

                        if (raw.isSuccessful) {
                            val resolvedUrl = raw.raw().request.url.toString()
                            Log.d("FrembedProvider", "Portal resolved to: $resolvedUrl")

                            UserPreferences.setProviderCache(
                                this,
                                UserPreferences.PROVIDER_URL,
                                resolvedUrl
                            )
                            UserPreferences.setProviderCache(
                                this,
                                UserPreferences.PROVIDER_LOGO,
                                resolvedUrl + "/favicon-32x32.png"
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w("FrembedProvider", "Portal fetch failed: ${e.message}. Trying redirect fallback resolution.")
                    try {
                        val currentOrFallback = if (baseUrl.isNotEmpty()) baseUrl else defaultBaseUrl
                        Log.d("FrembedProvider", "Trying fallback redirect resolution for: $currentOrFallback")
                        val raw = addressService.loadPageRaw(currentOrFallback)
                        if (raw.isSuccessful) {
                            val finalUrl = raw.raw().request.url.toString()
                            Log.d("FrembedProvider", "Fallback resolved to finalUrl: $finalUrl")
                            if (finalUrl != baseUrl) {
                                Log.i("FrembedProvider", "Updating cached URL from $baseUrl to $finalUrl")
                                UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_URL, finalUrl)
                                UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_LOGO, finalUrl.removeSuffix("/") + "/favicon-32x32.png")
                            }
                        }
                    } catch (ex: Exception) {
                        Log.w("FrembedProvider", "Fallback redirect resolution for current baseUrl ($baseUrl) failed: ${ex.message}. Trying defaultBaseUrl.")
                        try {
                            if (baseUrl != defaultBaseUrl) {
                                Log.d("FrembedProvider", "Trying fallback redirect resolution for defaultBaseUrl: $defaultBaseUrl")
                                val raw = addressService.loadPageRaw(defaultBaseUrl)
                                if (raw.isSuccessful) {
                                    val finalUrl = raw.raw().request.url.toString()
                                    Log.i("FrembedProvider", "Resolved defaultBaseUrl redirect to finalUrl: $finalUrl. Saving to cache.")
                                    UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_URL, finalUrl)
                                    UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_LOGO, finalUrl.removeSuffix("/") + "/favicon-32x32.png")
                                }
                            }
                        } catch (ex2: Exception) {
                            Log.e("FrembedProvider", "All redirection fallbacks failed: ${ex2.message}")
                        }
                    }
                }
            }
            service = Service.build(baseUrl)

            serviceInitialized = true
        }
        return baseUrl
    }

    private suspend fun initializeService() {
        initializationMutex.withLock {
            if (serviceInitialized) return
            onChangeUrl()
        }
    }

    fun rebuildService() {
        serviceInitialized = false
    }

    private interface Service {
        companion object {
            private val client = OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .dns(DnsResolver.doh)
                .build()

            fun buildAddressFetcher(): Service {
                val addressRetrofit = Retrofit.Builder()
                    .baseUrl(portalUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)

                    .build()

                return addressRetrofit.create(Service::class.java)
            }

            fun build(baseUrl: String): Service {
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(ScalarsConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()

                return retrofit.create(Service::class.java)
            }
        }

        @GET("{url}")
        suspend fun loadPage(
            @Path("url") url: String,
            @Header("user-agent") user_agent: String = "Mozilla"
        ): Response<String>

        @GET(".")
        suspend fun getPortalHome(
            @Header("user-agent") user_agent: String = "Mozilla"
        ): Document

        @GET
        suspend fun loadPageRaw(
            @Url url: String
        ): Response<ResponseBody>

        @GET("api/public/movies/{section}")
        suspend fun getApiPublic(
            @Path("section") section: String,
            @Header("user-agent") user_agent: String = "Mozilla"
        ): List<FrembedShortCutItem>

        @GET("api/views/{section}")
        suspend fun getApiView(
            @Path("section") section: String,
            @Header("user-agent") user_agent: String = "Mozilla"
        ): List<FrembedShortCutItem>

        @GET("api/public/movies")
        suspend fun getMovies(
            @Query("page") page: Int = 1,
            @Query("pageSize") pageSize: Int = 16,
            @Query("orderBy") orderBy: String = "date_creation",
            @Query("orderDirection") orderDirection: String = "desc",
            @Query("searchQuery") searchQuery: String = "",
            @Query("searchBy") searchBy: String = "title",
            @Query("version") version: String = "",
            @Query("genre") genre: String = "",
            @Header("user-agent") user_agent: String = "Mozilla"
        ): FrembedMoviesResponse

        @GET("api/public/tv-show")
        suspend fun getTvShows(
            @Query("page") page: Int = 1,
            @Query("pageSize") pageSize: Int = 16,
            @Query("orderBy") orderBy: String = "date_creation",
            @Query("orderDirection") orderDirection: String = "desc",
            @Query("searchQuery") searchQuery: String = "",
            @Query("searchBy") searchBy: String = "title",
            @Query("version") version: String = "",
            @Query("genre") genre: String = "",
            @Header("user-agent") user_agent: String = "Mozilla"
        ): FrembedTvShowsResponse

        @GET("api/public/search")
        suspend fun getApiSearch(
            @Query("page") page: Int = 1,
            @Query("query") query: String,
            @Header("user-agent") user_agent: String = "Mozilla"
        ): FrembedSearchResponse

        @GET("api/public/actor/{id}")
        suspend fun getApiSearchActor(
            @Path("id") id: String,
            @Header("user-agent") user_agent: String = "Mozilla"
        ): FrembedSearchActorsResponse

        @GET("api/public/movies/{id}")
        suspend fun getMovie(
            @Path("id") id: String,
        ): FrembedShowItem

        @GET("api/public/tv-show/{id}")
        suspend fun getTvShow(
            @Path("id") id: String,
        ): FrembedShowItem

        @GET("api/public/{type}/similar/{id}")
        suspend fun getApiSimilar(
            @Path("type") type: String = "movies",
            @Path("id") id: String,
            @Header("user-agent") user_agent: String = "Mozilla"
        ): List<FrembedSimilarItem>

        @GET("api/public/tv-show/{id}/listep")
        suspend fun getApiListEp(
            @Path("id") id: String,
            @Header("user-agent") user_agent: String = "Mozilla"
        ): List<FrembedSeasonResponse>
    }
}