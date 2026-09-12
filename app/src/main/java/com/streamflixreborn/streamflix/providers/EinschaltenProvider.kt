package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.extractors.DoodLaExtractor
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.TmdbUtils
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.Query
import okhttp3.ResponseBody
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import com.streamflixreborn.streamflix.utils.UserPreferences

object EinschaltenProvider : Provider {

    override val name = "Einschalten"

    private const val DEFAULT_BASE_URL = "https://einschalten.in"

    override val baseUrl: String
        get() = UserPreferences.resolveProviderBaseUrl(name, DEFAULT_BASE_URL)
    override val logo = "https://images2.imgbox.com/74/12/NBWU0dNi_o.png"
    override val language = "de"

    private const val USER_AGENT = "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private interface EinschaltenService {
        companion object {
            fun build(baseUrl: String): EinschaltenService {
                val clientBuilder = OkHttpClient.Builder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(clientBuilder.dns(DnsResolver.doh).build())
                    .build()
                    .create(EinschaltenService::class.java)
            }

        }

        @Headers(USER_AGENT, "Content-Type: application/json")
        @GET("api/movies/{id}")
        suspend fun getMovie(@Path("id") id: String): ResponseBody

        @Headers(USER_AGENT, "Content-Type: application/json")
        @GET("api/movies/{id}/watch")
        suspend fun getWatch(@Path("id") id: String): ResponseBody

        @Headers(USER_AGENT, "Content-Type: application/json")
        @GET("api/genres")
        suspend fun getGenres(): ResponseBody

        @Headers(USER_AGENT, "Content-Type: application/json")
        @POST("api/search")
        suspend fun search(@Body body: RequestBody): ResponseBody

        @Headers(USER_AGENT, "Content-Type: application/json")
        @GET("api/movies")
        suspend fun getMovies(
            @Query("genreId") genreId: Int?,
            @Query("pageNumber") page: Int,
            @Query("order") order: String = "new"
        ): ResponseBody
    }

    @Volatile
    private var cachedService: EinschaltenService? = null

    @Volatile
    private var cachedServiceBaseUrl: String? = null

    private val service: EinschaltenService
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
                    EinschaltenService.build(currentBaseUrl).also {
                        cachedService = it
                        cachedServiceBaseUrl = currentBaseUrl
                    }
                }
            }
        }

    private suspend fun getPosterUrl(movieId: String, posterPath: String): String {
        if (posterPath.isNotBlank()) {
            return "$baseUrl/api/image/poster$posterPath"
        }
        
        return try {
            val tmdbId = movieId.toIntOrNull()
            if (tmdbId != null) {
                val movie = TmdbUtils.getMovieById(tmdbId, language = language)
                movie?.poster ?: ""
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }
    
    private suspend fun getTmdbMovie(movieId: String): Movie? {
        return try {
            val tmdbId = movieId.toIntOrNull()
            if (tmdbId != null) {
                TmdbUtils.getMovieById(tmdbId, language = language)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun parseMoviesFromJsonArray(moviesArray: JSONArray): List<Movie> {
        val movies = mutableListOf<Movie>()
        (0 until moviesArray.length()).forEach { i ->
            val movieObj = moviesArray.optJSONObject(i) ?: return@forEach
            val movieId = movieObj.optInt("id", 0).toString()
            val title = movieObj.optString("title", "")
            val posterPath = movieObj.optString("posterPath", "")
            
            if (movieId.isNotBlank() && title.isNotBlank()) {
                val poster = getPosterUrl(movieId, posterPath)
                movies.add(
                    Movie(
                        id = movieId,
                        title = title,
                        poster = poster
                    )
                )
            }
        }
        return movies
    }

    override suspend fun getHome(): List<Category> {
        val categories = mutableListOf<Category>()

        try {
            // Neue Filme
            val neueResponse = service.getMovies(genreId = null, page = 1, order = "new")
            val neueJson = JSONObject(neueResponse.string())
            val neueArray = neueJson.optJSONArray("data") ?: JSONArray()
            val neueMovies = parseMoviesFromJsonArray(neueArray)
            if (neueMovies.isNotEmpty()) {
                categories.add(Category(name = "Neue Filme", list = neueMovies))
            }

            // Zuletzt hinzugefügte Filme
            val addedResponse = service.getMovies(genreId = null, page = 1, order = "added")
            val addedJson = JSONObject(addedResponse.string())
            val addedArray = addedJson.optJSONArray("data") ?: JSONArray()
            val addedMovies = parseMoviesFromJsonArray(addedArray)
            if (addedMovies.isNotEmpty()) {
                categories.add(Category(name = "Zuletzt hinzugefügte Filme", list = addedMovies))
            }
        } catch (e: Exception) {
        }

        return categories
    }


    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            
            try {
                val genresResponse = service.getGenres()
                val genresBody = genresResponse.string()
                val genresJson = JSONArray(genresBody)
                
                return (0 until genresJson.length()).mapNotNull { i ->
                    val genreObj = genresJson.optJSONObject(i) ?: return@mapNotNull null
                    val genreId = genreObj.optInt("id", 0)
                    val genreName = genreObj.optString("name", "")
                    
                    if (genreId > 0 && genreName.isNotBlank()) {
                        Genre(id = genreId.toString(), name = genreName)
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                return emptyList()
            }
        }
        
        try {
            val searchBody = JSONObject().apply {
                put("query", query)
                put("pageSize", 32)
                put("pageNumber", page)
            }
            
            val requestBody = searchBody.toString()
                .toRequestBody("application/json".toMediaType())
            
            val searchResponse = service.search(requestBody)
            val searchBodyString = searchResponse.string()
            val jsonResponse = JSONObject(searchBodyString)
            val moviesArray = jsonResponse.optJSONArray("data") ?: JSONArray()
            
            return parseMoviesFromJsonArray(moviesArray)
        } catch (e: Exception) {
            return emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        try {
            val moviesResponse = service.getMovies(
                genreId = null,
                page = page,
                order = "new"
            )
            val moviesBodyString = moviesResponse.string()
            val jsonResponse = JSONObject(moviesBodyString)
            val moviesArray = jsonResponse.optJSONArray("data") ?: JSONArray()
            
            return parseMoviesFromJsonArray(moviesArray)
        } catch (e: Exception) {
            return emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return emptyList() // Einschalten is movies only
    }

    override suspend fun getMovie(id: String): Movie {
        val responseBody = service.getMovie(id)
        val body = responseBody.string()
        val json = JSONObject(body)

        val title = json.optString("title", "")
        val overview = json.optString("overview", "")
        val posterPath = json.optString("posterPath", "")
        val releaseDate = json.optString("releaseDate", "").takeIf { it.isNotBlank() }
        val voteAverage = json.optDouble("voteAverage", 0.0).takeIf { it > 0 }
        val runtime = json.optInt("runtime", 0).takeIf { it > 0 }
        val genresArray = json.optJSONArray("genres")

        val tmdbMovie = getTmdbMovie(id)

        val poster = when {
            posterPath.isNotBlank() -> "$baseUrl/api/image/poster$posterPath"
            else -> tmdbMovie?.poster ?: ""
        }

        val genres = if (genresArray != null && genresArray.length() > 0) {
            (0 until genresArray.length()).mapNotNull { i ->
                val genreObj = genresArray.optJSONObject(i) ?: return@mapNotNull null
                Genre(
                    id = genreObj.optInt("id", 0).toString(),
                    name = genreObj.optString("name", "")
                )
            }
        } else {
            tmdbMovie?.genres ?: emptyList()
        }

        return Movie(
            id = id,
            title = title,
            overview = overview.ifBlank { tmdbMovie?.overview ?: "" },
            released = releaseDate ?: tmdbMovie?.released?.let { "${it.get(java.util.Calendar.YEAR)}-${it.get(java.util.Calendar.MONTH) + 1}-${it.get(java.util.Calendar.DAY_OF_MONTH)}" },
            runtime = runtime ?: tmdbMovie?.runtime,
            rating = voteAverage ?: tmdbMovie?.rating,
            poster = poster,
            banner = tmdbMovie?.banner,
            genres = genres,
            cast = tmdbMovie?.cast ?: emptyList(),
            trailer = tmdbMovie?.trailer,
            recommendations = tmdbMovie?.recommendations ?: emptyList()
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        throw Exception("TV shows not supported")
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        throw Exception("TV shows not supported")
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val genreId = id.toIntOrNull() ?: return Genre(id = id, name = "", shows = emptyList())
        
        val genreName = try {
            val genresResponse = service.getGenres()
            val genresBody = genresResponse.string()
            val genresJson = JSONArray(genresBody)
            
            (0 until genresJson.length()).firstNotNullOfOrNull { i ->
                val genreObj = genresJson.optJSONObject(i)
                if (genreObj?.optInt("id", 0) == genreId) {
                    genreObj.optString("name", "")
                } else {
                    null
                }
            } ?: ""
        } catch (e: Exception) {
            ""
        }
        
        try {
            val moviesResponse = service.getMovies(
                genreId = genreId,
                page = page,
                order = "new"
            )
            val moviesBodyString = moviesResponse.string()
            val jsonResponse = JSONObject(moviesBodyString)
            val moviesArray = jsonResponse.optJSONArray("data") ?: JSONArray()
            val movies = parseMoviesFromJsonArray(moviesArray)
            
            return Genre(id = id, name = genreName, shows = movies)
        } catch (e: Exception) {
            return Genre(id = id, name = genreName, shows = emptyList())
        }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = "", filmography = emptyList())
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return when (videoType) {
            is Video.Type.Movie -> {
                val responseBody = service.getWatch(id)
                val body = responseBody.string()
                val json = JSONObject(body)
                val streamUrl = json.optString("streamUrl", "").trim()

                if (streamUrl.isBlank()) {
                    return emptyList()
                }

                listOf(
                    Video.Server(
                        id = streamUrl,
                        name = "DoodStream",
                        src = streamUrl
                    )
                )
            }
            is Video.Type.Episode -> {
                emptyList()
            }
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return DoodLaExtractor().extract(server.src)
    }
}
