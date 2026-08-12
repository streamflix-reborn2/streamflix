package com.streamflixreborn.streamflix.providers

import com.google.gson.annotations.SerializedName
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
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

object FanpelisProvider : Provider {
    private const val URL = "https://fanpelis.to/"
    private const val API_URL = "https://fanpelis.to/api/rest/"

    override val baseUrl = URL
    override val name = "Fanpelis"
    override val logo = "https://fanpelis.to/wp-content/uploads/2025/02/cropped-play-button-icon-trendy-flat-260nw-752745979-e1738708582632-192x192.webp"
    override val language = "es"

    private val service = Service.build()

    override suspend fun getHome(): List<Category> {
        val movies = service.listing(1, "movies", 16).data?.posts.orEmpty()
        val shows = service.listing(1, "tvshows", 16).data?.posts.orEmpty()
        return listOf(
            Category(Category.FEATURED, movies.map(::toMovie)),
            Category("Últimas películas", movies.map(::toMovie)),
            Category("Últimas series", shows.map(::toTvShow)),
        ).filter { it.list.isNotEmpty() }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        return service.search(query, page, "movies,tvshows,animes", 16).data?.posts.orEmpty().mapNotNull { item ->
            when (item.type) {
                "movies" -> toMovie(item)
                "tvshows", "animes" -> toTvShow(item)
                else -> null
            }
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> =
        service.listing(page, "movies", 16).data?.posts.orEmpty().map(::toMovie)

    override suspend fun getTvShows(page: Int): List<TvShow> =
        service.listing(page, "tvshows", 16).data?.posts.orEmpty().map(::toTvShow)

    override suspend fun getMovie(id: String): Movie =
        service.single(id, "movies").data?.let(::toMovie) ?: Movie(id = id)

    override suspend fun getTvShow(id: String): TvShow {
        val item = service.single(id, "tvshows").data ?: return TvShow(id = id)
        return toTvShow(item).withEpisodes(item.id)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split('|', limit = 2)
        val showId = parts.firstOrNull()?.toIntOrNull() ?: return emptyList()
        val seasonNumber = parts.getOrNull(1)?.toIntOrNull() ?: return emptyList()
        return service.episodes(showId).data.orEmpty()
            .filter { it.seasonNumber == seasonNumber }
            .sortedBy { it.episodeNumber }
            .map(::toEpisode)
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val response = service.taxonomy("genres", id, page)
        return Genre(id = id, name = id, shows = response.data?.posts.orEmpty().mapNotNull(::toShow))
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val response = service.taxonomy("cast", id, page)
        return People(id = id, name = id, filmography = response.data?.posts.orEmpty().mapNotNull(::toShow))
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val postId = when (videoType) {
            is Video.Type.Movie -> id.toIntOrNull() ?: service.single(id, "movies").data?.id
            is Video.Type.Episode -> id.toIntOrNull()
        } ?: return emptyList()

        return service.player(postId).data?.embeds.orEmpty().mapIndexed { index, embed ->
            Video.Server(
                id = embed.url,
                name = embed.url.substringAfter("//").substringBefore('/').ifBlank { "Server ${index + 1}" },
                src = embed.url,
            )
        }.filter { it.src.isNotBlank() }.distinctBy { it.src }
    }

    override suspend fun getVideo(server: Video.Server): Video =
        Extractor.extract(server.src.ifBlank { server.id }, server)

    private suspend fun TvShow.withEpisodes(postId: Int): TvShow {
        val episodes = service.episodes(postId).data.orEmpty()
        val seasons = episodes.groupBy { it.seasonNumber }.toSortedMap().map { (number, seasonEpisodes) ->
            Season(
                id = "$postId|$number",
                number = number,
                episodes = seasonEpisodes.sortedBy { it.episodeNumber }.map(::toEpisode),
            )
        }
        return TvShow(
            id = id,
            title = title,
            overview = overview,
            released = released?.let { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(it.time) },
            runtime = runtime,
            trailer = trailer,
            quality = quality,
            rating = rating,
            poster = poster,
            banner = banner,
            seasons = seasons,
            genres = genres,
            cast = cast,
        )
    }

    private fun toShow(item: FanItem): Show? = when (item.type) {
        "movies" -> toMovie(item)
        "tvshows", "animes" -> toTvShow(item)
        else -> null
    }

    private fun toMovie(item: FanItem) = Movie(
        id = item.slug,
        title = item.title,
        overview = item.overview,
        released = item.releaseDate,
        runtime = item.runtime.toDoubleOrNull()?.toInt(),
        trailer = item.trailer.takeIf(String::isNotBlank)?.let { "https://www.youtube.com/watch?v=$it" },
        quality = item.quality.firstOrNull()?.toString(),
        rating = item.rating.toDoubleOrNull(),
        poster = image(item.images?.poster),
        banner = image(item.images?.backdrop),
        genres = item.genres.map { Genre(id = it.toString(), name = it.toString()) },
    )

    private fun toTvShow(item: FanItem) = TvShow(
        id = item.slug,
        title = item.title,
        overview = item.overview,
        released = item.releaseDate,
        runtime = item.runtime.toDoubleOrNull()?.toInt(),
        trailer = item.trailer.takeIf(String::isNotBlank)?.let { "https://www.youtube.com/watch?v=$it" },
        quality = item.quality.firstOrNull()?.toString(),
        rating = item.rating.toDoubleOrNull(),
        poster = image(item.images?.poster),
        banner = image(item.images?.backdrop),
        genres = item.genres.map { Genre(id = it.toString(), name = it.toString()) },
    )

    private fun toEpisode(item: FanEpisode) = Episode(
        id = item.id.toString(),
        number = item.episodeNumber,
        title = item.title,
        overview = item.overview,
        poster = image(item.stillPath),
    )

    private fun image(path: String?): String? = path?.takeIf(String::isNotBlank)?.let {
        "$URL/wp-content/uploads${if (it.startsWith('/')) it else "/$it"}"
    }

    private data class ApiResponse<T>(val error: Boolean = false, val data: T? = null)
    private data class ListingData(val posts: List<FanItem> = emptyList())
    private data class PlayerData(val embeds: List<Embed> = emptyList())
    private data class Images(val poster: String? = null, val backdrop: String? = null)
    private data class Embed(val url: String = "", val lang: String? = null, val quality: String? = null)

    private data class FanItem(
        @SerializedName("_id") val id: Int = 0,
        val title: String = "",
        val overview: String? = null,
        val slug: String = "",
        val images: Images? = null,
        val trailer: String = "",
        val rating: String = "",
        val genres: List<Int> = emptyList(),
        val quality: List<Int> = emptyList(),
        val type: String = "",
        @SerializedName("release_date") val releaseDate: String? = null,
        val runtime: String = "",
    )

    private data class FanEpisode(
        @SerializedName("_id") val id: Int = 0,
        val title: String = "",
        val overview: String? = null,
        @SerializedName("still_path") val stillPath: String? = null,
        @SerializedName("season_number") val seasonNumber: Int = 0,
        @SerializedName("episode_number") val episodeNumber: Int = 0,
    )

    private interface Service {
        companion object {
            fun build(): Service = Retrofit.Builder()
                .baseUrl(API_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .client(
                    OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .dns(DnsResolver.doh)
                        .build(),
                )
                .build()
                .create(Service::class.java)
        }

        @GET("listing")
        suspend fun listing(@Query("page") page: Int, @Query("post_type") postType: String, @Query("posts_per_page") postsPerPage: Int): ApiResponse<ListingData>

        @GET("search")
        suspend fun search(@Query("query") query: String, @Query("page") page: Int, @Query("post_type") postType: String, @Query("posts_per_page") postsPerPage: Int): ApiResponse<ListingData>

        @GET("single")
        suspend fun single(@Query("post_name") slug: String, @Query("post_type") type: String): ApiResponse<FanItem>

        @GET("listing")
        suspend fun taxonomy(@Query("tax") tax: String, @Query("term") term: String, @Query("page") page: Int, @Query("post_type") postType: String = "movies,tvshows,animes", @Query("posts_per_page") postsPerPage: Int = 16): ApiResponse<ListingData>

        @GET("episodes")
        suspend fun episodes(@Query("post_id") postId: Int): ApiResponse<List<FanEpisode>>

        @GET("player")
        suspend fun player(@Query("post_id") postId: Int, @Query("_any") any: Int = 1): ApiResponse<PlayerData>
    }
}
