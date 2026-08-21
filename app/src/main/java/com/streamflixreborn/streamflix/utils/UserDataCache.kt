package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.WatchItem
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.sync.CloudSyncHooks
import com.streamflixreborn.streamflix.ui.UserDataNotifier
import com.streamflixreborn.streamflix.utils.ProfileManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object UserDataCache {

    private val gson = Gson()
    private val memoryCache = ConcurrentHashMap<String, UserData>()

    data class UserData(
        val favoritesMovies: List<CachedMovie> = emptyList(),
        val favoritesTvShows: List<CachedTvShow> = emptyList(),
        val continueWatchingMovies: List<CachedMovie> = emptyList(),
        val continueWatchingEpisodes: List<CachedEpisode> = emptyList(),
    )

    private fun UserData.normalized(): UserData = copy(
        favoritesMovies = favoritesMovies.sortedByDescending { it.favoritedAtMillis ?: 0L },
        favoritesTvShows = favoritesTvShows.sortedByDescending { it.favoritedAtMillis ?: 0L },
        continueWatchingMovies = continueWatchingMovies.sortedByDescending { it.lastEngagementTimeUtcMillis ?: 0L },
        continueWatchingEpisodes = continueWatchingEpisodes.sortedByDescending { it.lastEngagementTimeUtcMillis ?: 0L },
    )

    // -------------------------
    // CACHE FILE
    // -------------------------

    private fun cacheKey(provider: Provider): String {
        val baseUrlKey = provider.baseUrl.trim().trimEnd('/')
        val profileId = ProfileManager.activeProfileId ?: "default"
        return listOf(profileId, provider.name, baseUrlKey)
            .filter { it.isNotEmpty() }
            .joinToString("__")
    }

    /**
     * Moves cache files written before profiles existed into the first default
     * profile. The old cache key was provider + base URL; the new key adds the
     * profile ID. Keeping this migration here also makes it safe for callers
     * that read the cache before the next database refresh.
     */
    fun migrateLegacyCacheToDefaultProfile(context: Context, providers: Iterable<Provider>) {
        if (ProfileManager.activeProfileId != "default") return

        providers.forEach { provider ->
            val baseUrlKey = provider.baseUrl.trim().trimEnd('/')
            val legacyKey = listOf(provider.name, baseUrlKey)
                .filter { it.isNotEmpty() }
                .joinToString("__")
            val legacyFile = cacheFile(context, legacyKey)
            val profileFile = cacheFile(context, cacheKey(provider))

            if (legacyFile.exists() && !profileFile.exists()) {
                runCatching {
                    profileFile.parentFile?.mkdirs()
                    legacyFile.copyTo(profileFile, overwrite = false)
                    Log.i("UserDataCache", "Migrated legacy cache for ${provider.name} to default profile")
                }.onFailure { error ->
                    Log.w("UserDataCache", "Could not migrate legacy cache for ${provider.name}", error)
                }
            }
        }
    }

    private fun cacheFile(context: Context, cacheKey: String): File {
        val safeName = cacheKey.replace(Regex("[^a-zA-Z0-9._-]+"), "_")
        val file = File(context.filesDir, "user-data-cache/$safeName.json")
        Log.d("CACHE_PATH", file.absolutePath)
        return file
    }

    // -------------------------
    // READ / WRITE
    // -------------------------

    fun read(context: Context, provider: Provider): UserData? {
        val key = cacheKey(provider)

        memoryCache[key]?.let { return it }

        val file = cacheFile(context, key)
        if (!file.exists()) return null

        return runCatching {
            gson.fromJson(file.readText(), UserData::class.java).normalized().also {
                memoryCache[key] = it
            }
        }.getOrNull()
    }

    fun write(context: Context, provider: Provider, newData: UserData) {
        val key = cacheKey(provider)
        val normalizedData = newData.normalized()
        val oldData = memoryCache[key]

        // ✅ prevent spam
        if (oldData == normalizedData) return

        memoryCache[key] = normalizedData

        runCatching {
            cacheFile(context, key).apply {
                parentFile?.mkdirs()
                writeText(gson.toJson(normalizedData))
            }
        }

        UserDataNotifier.notifyChanged()
    }

    fun clear(context: Context, provider: Provider) {
        val key = cacheKey(provider)
        memoryCache.remove(key)
        cacheFile(context, key).delete()
    }

    fun clearAll(context: Context) {
        memoryCache.clear()
        val cacheDir = File(context.filesDir, "user-data-cache")
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
        }
    }

    // -------------------------
    // WRITE HELPERS (FIXED)
    // -------------------------

    fun writeMovies(context: Context, provider: Provider, movies: List<Movie>) {
        val current = read(context, provider) ?: UserData()
        val moviesById = movies.associateBy { it.id }

        val newData = current.copy(
            favoritesMovies = current.favoritesMovies.mapNotNull { cached ->
                moviesById[cached.id]?.takeIf { it.isFavorite }?.toCached()
            } + movies.filter { it.isFavorite && current.favoritesMovies.none { cached -> cached.id == it.id } }
                .map { it.toCached() },
            continueWatchingMovies = current.continueWatchingMovies.mapNotNull { cached ->
                moviesById[cached.id]?.takeIf { it.watchHistory != null }?.toCached()
            } + movies.filter { it.watchHistory != null && current.continueWatchingMovies.none { cached -> cached.id == it.id } }
                .map { it.toCached() }
        )

        write(context, provider, newData)
    }

    fun writeTvShows(context: Context, provider: Provider, tvShows: List<TvShow>) {
        val current = read(context, provider) ?: UserData()
        val tvShowsById = tvShows.associateBy { it.id }

        val newData = current.copy(
            favoritesTvShows = current.favoritesTvShows.mapNotNull { cached ->
                tvShowsById[cached.id]?.takeIf { it.isFavorite }?.toCached()
            } + tvShows.filter { it.isFavorite && current.favoritesTvShows.none { cached -> cached.id == it.id } }
                .map { it.toCached() }
        )

        write(context, provider, newData)
    }

    fun writeEpisodes(context: Context, provider: Provider, episodes: List<Episode>) {
        val current = read(context, provider) ?: UserData()
        val episodesById = episodes.associateBy { it.id }

        val newData = current.copy(
            continueWatchingEpisodes = current.continueWatchingEpisodes.mapNotNull { cached ->
                episodesById[cached.id]?.takeIf { it.watchHistory != null }?.toCached()
            } + episodes.filter { it.watchHistory != null && current.continueWatchingEpisodes.none { cached -> cached.id == it.id } }
                .map { it.toCached() }
        )

        write(context, provider, newData)
    }

    // -------------------------
    // MOVIES
    // -------------------------

    fun removeMovieFromContinueWatching(context: Context, provider: Provider, id: String) {
        val current = read(context, provider) ?: UserData()

        runCatching {
            val db = AppDatabase.getInstance(context)
            db.movieDao().getById(id)?.let { movie ->
                movie.watchHistory = null
                movie.isWatched = false
                movie.watchedDate = null
                db.movieDao().update(movie)
            }
        }

        write(context, provider, current.copy(
            continueWatchingMovies = current.continueWatchingMovies.filter { it.id != id }
        ))
        CloudSyncHooks.movie(context, provider, id)
        UserDataNotifier.notifyChanged()
    }

    fun addMovieToContinueWatching(context: Context, provider: Provider, movie: Movie) {
        val current = read(context, provider) ?: UserData()

        write(context, provider, current.copy(
            continueWatchingMovies = (current.continueWatchingMovies + movie.toCached())
                .distinctBy { it.id }
        ))
        CloudSyncHooks.movie(context, provider, movie)
        UserDataNotifier.notifyChanged()
    }

    fun removeMovieFromFavorites(context: Context, provider: Provider, id: String) {
        val current = read(context, provider) ?: UserData()

        write(context, provider, current.copy(
            favoritesMovies = current.favoritesMovies.filter { it.id != id }
        ))
        UserDataNotifier.notifyChanged()
        CloudSyncHooks.movie(context, provider, id)
    }

    fun addMovieToFavorites(context: Context, provider: Provider, movie: Movie) {
        val current = read(context, provider) ?: UserData()
        val favoritedMovie = movie.copy().apply {
            isFavorite = true
            favoritedAtMillis = favoritedAtMillis ?: System.currentTimeMillis()
        }

        write(context, provider, current.copy(
            favoritesMovies = (current.favoritesMovies + favoritedMovie.toCached())
                .distinctBy { it.id }
        ))
        UserDataNotifier.notifyChanged()
        CloudSyncHooks.movie(context, provider, favoritedMovie)
    }

    // -------------------------
    // EPISODES
    // -------------------------

    fun removeEpisodeFromContinueWatching(context: Context, provider: Provider, id: String) {
        val current = read(context, provider) ?: UserData()

        write(context, provider, current.copy(
            continueWatchingEpisodes = current.continueWatchingEpisodes.filter { it.id != id }
        ))
        UserDataNotifier.notifyChanged()
        CloudSyncHooks.episode(context, provider, id)
    }

    fun addEpisodeToContinueWatching(context: Context, provider: Provider, episode: Episode) {
        val current = read(context, provider) ?: UserData()

        write(context, provider, current.copy(
            continueWatchingEpisodes = (current.continueWatchingEpisodes + episode.toCached())
                .distinctBy { it.id }
        ))
        UserDataNotifier.notifyChanged()
        CloudSyncHooks.episode(context, provider, episode)
    }

    // -------------------------
    // TV SHOWS
    // -------------------------

    fun removeTvShowFromFavorites(context: Context, provider: Provider, id: String) {
        val current = read(context, provider) ?: UserData()

        write(context, provider, current.copy(
            favoritesTvShows = current.favoritesTvShows.filter { it.id != id }
        ))
        UserDataNotifier.notifyChanged()
        CloudSyncHooks.tvShow(context, provider, id)
    }

    fun addTvShowToFavorites(context: Context, provider: Provider, tvShow: TvShow) {
        val current = read(context, provider) ?: UserData()
        val favoritedTvShow = tvShow.copy().apply {
            isFavorite = true
            favoritedAtMillis = favoritedAtMillis ?: System.currentTimeMillis()
        }

        write(context, provider, current.copy(
            favoritesTvShows = (current.favoritesTvShows + favoritedTvShow.toCached())
                .distinctBy { it.id }
        ))
        UserDataNotifier.notifyChanged()
        CloudSyncHooks.tvShow(context, provider, favoritedTvShow)
    }

    // -------------------------
    // CACHE SYNC (Keep cache & DB in sync)
    // -------------------------

    fun syncMovieToCache(context: Context, provider: Provider, movie: Movie) {
        val current = read(context, provider) ?: UserData()
        
        val updatedContinueWatching = if (movie.watchHistory != null) {
            (current.continueWatchingMovies.filter { it.id != movie.id } + movie.toCached())
                .distinctBy { it.id }
        } else {
            current.continueWatchingMovies.filter { it.id != movie.id }
        }
        
        val updatedFavorites = if (movie.isFavorite) {
            (current.favoritesMovies.filter { it.id != movie.id } + movie.toCached().copy(
                favoritedAtMillis = movie.favoritedAtMillis
                    ?: current.favoritesMovies.firstOrNull { it.id == movie.id }?.favoritedAtMillis
                    ?: System.currentTimeMillis()
            ))
                .distinctBy { it.id }
        } else {
            current.favoritesMovies.filter { it.id != movie.id }
        }
        
        write(context, provider, current.copy(
            continueWatchingMovies = updatedContinueWatching,
            favoritesMovies = updatedFavorites
        ))
        UserDataNotifier.notifyChanged()
        CloudSyncHooks.movie(context, provider, movie)
    }

    fun syncEpisodeToCache(context: Context, provider: Provider, episode: Episode) {
        val current = read(context, provider) ?: UserData()
        
        val updatedContinueWatching = if (episode.watchHistory != null) {
            (current.continueWatchingEpisodes.filter { it.id != episode.id } + episode.toCached())
                .distinctBy { it.id }
        } else {
            current.continueWatchingEpisodes.filter { it.id != episode.id }
        }
        
        write(context, provider, current.copy(
            continueWatchingEpisodes = updatedContinueWatching
        ))
        UserDataNotifier.notifyChanged()
        CloudSyncHooks.episode(context, provider, episode)
    }

    fun syncTvShowToCache(context: Context, provider: Provider, tvShow: TvShow) {
        val current = read(context, provider) ?: UserData()

        val updatedFavorites = if (tvShow.isFavorite) {
            (current.favoritesTvShows.filter { it.id != tvShow.id } + tvShow.toCached().copy(
                favoritedAtMillis = tvShow.favoritedAtMillis
                    ?: current.favoritesTvShows.firstOrNull { it.id == tvShow.id }?.favoritedAtMillis
                    ?: System.currentTimeMillis()
            ))
                .distinctBy { it.id }
        } else {
            current.favoritesTvShows.filter { it.id != tvShow.id }
        }

        write(context, provider, current.copy(
            favoritesTvShows = updatedFavorites
        ))
        UserDataNotifier.notifyChanged()
        CloudSyncHooks.tvShow(context, provider, tvShow)
    }





    data class CachedMovie(
        val id: String,
        val title: String,
        val overview: String? = null,
        val released: String? = null,
        val runtime: Int? = null,
        val trailer: String? = null,
        val quality: String? = null,
        val rating: Double? = null,
        val poster: String? = null,
        val banner: String? = null,
        val isFavorite: Boolean = false,
        val isWatched: Boolean = false,
        val favoritedAtMillis: Long? = null,
        val lastEngagementTimeUtcMillis: Long? = null,
        val lastPlaybackPositionMillis: Long? = null,
        val durationMillis: Long? = null,
    )

    data class CachedTvShow(
        val id: String,
        val title: String,
        val overview: String? = null,
        val released: String? = null,
        val runtime: Int? = null,
        val trailer: String? = null,
        val quality: String? = null,
        val rating: Double? = null,
        val poster: String? = null,
        val banner: String? = null,
        val isFavorite: Boolean = false,
        val favoritedAtMillis: Long? = null,
    )

    data class CachedEpisode(
        val id: String,
        val number: Int,
        val title: String? = null,
        val released: String? = null,
        val poster: String? = null,
        val overview: String? = null,
        val isWatched: Boolean = false,
        val lastEngagementTimeUtcMillis: Long? = null,
        val lastPlaybackPositionMillis: Long? = null,
        val durationMillis: Long? = null,
        val tvShowId: String? = null,
        val tvShowTitle: String? = null,
        val tvShowPoster: String? = null,
        val tvShowBanner: String? = null,
        val seasonId: String? = null,
        val seasonNumber: Int? = null,
        val seasonTitle: String? = null,
        val seasonPoster: String? = null,
    )


    fun CachedMovie.toMovie() = Movie(
        id = id,
        title = title,
        overview = overview,
        released = released,
        runtime = runtime,
        trailer = trailer,
        quality = quality,
        rating = rating,
        poster = poster,
        banner = banner,
    ).apply {
        isFavorite = this@toMovie.isFavorite
        favoritedAtMillis = this@toMovie.favoritedAtMillis
        isWatched = this@toMovie.isWatched
        if (this@toMovie.lastEngagementTimeUtcMillis != null) {
            watchHistory = WatchItem.WatchHistory(
                lastEngagementTimeUtcMillis = this@toMovie.lastEngagementTimeUtcMillis,
                lastPlaybackPositionMillis = this@toMovie.lastPlaybackPositionMillis ?: 0,
                durationMillis = this@toMovie.durationMillis ?: 0
            )
        }
    }

    fun CachedTvShow.toTvShow() = TvShow(
        id = id,
        title = title,
        overview = overview,
        released = released,
        runtime = runtime,
        trailer = trailer,
        quality = quality,
        rating = rating,
        poster = poster,
        banner = banner,
    ).apply {
        isFavorite = this@toTvShow.isFavorite
        favoritedAtMillis = this@toTvShow.favoritedAtMillis
    }

    fun CachedEpisode.toEpisode() = Episode(
        id = id,
        number = number,
        title = title,
        released = released,
        poster = poster,
        overview = overview,
    ).apply {
        isWatched = this@toEpisode.isWatched
        if (this@toEpisode.lastEngagementTimeUtcMillis != null) {
            watchHistory = WatchItem.WatchHistory(
                lastEngagementTimeUtcMillis = this@toEpisode.lastEngagementTimeUtcMillis,
                lastPlaybackPositionMillis = this@toEpisode.lastPlaybackPositionMillis ?: 0,
                durationMillis = this@toEpisode.durationMillis ?: 0
            )
        }
        tvShow = this@toEpisode.tvShowId?.let {
            TvShow(
                id = it,
                title = this@toEpisode.tvShowTitle.orEmpty(),
                poster = this@toEpisode.tvShowPoster,
                banner = this@toEpisode.tvShowBanner,
            )
        }
        season = this@toEpisode.seasonId?.let {
            Season(
                id = it,
                number = this@toEpisode.seasonNumber ?: 0,
                title = this@toEpisode.seasonTitle.orEmpty(),
                poster = this@toEpisode.seasonPoster,
            )
        }
    }
    fun Movie.toCached() = UserDataCache.CachedMovie(
        id = id,
        title = title,
        overview = overview,
        released = released?.format("yyyy-MM-dd"),
        runtime = runtime,
        trailer = trailer,
        quality = quality,
        rating = rating,
        poster = poster,
        banner = banner,
        isFavorite = isFavorite,
        isWatched = isWatched,
        favoritedAtMillis = favoritedAtMillis,
        lastEngagementTimeUtcMillis = watchHistory?.lastEngagementTimeUtcMillis,
        lastPlaybackPositionMillis = watchHistory?.lastPlaybackPositionMillis,
        durationMillis = watchHistory?.durationMillis
    )
    fun TvShow.toCached() = UserDataCache.CachedTvShow(
        id = id,
        title = title,
        overview = overview,
        released = released?.format("yyyy-MM-dd"),
        runtime = runtime,
        trailer = trailer,
        quality = quality,
        rating = rating,
        poster = poster,
        banner = banner,
        isFavorite = isFavorite,
        favoritedAtMillis = favoritedAtMillis,
    )
    fun Episode.toCached() = UserDataCache.CachedEpisode(
        id = id,
        number = number,
        title = title,
        released = released?.format("yyyy-MM-dd"),
        poster = poster,
        overview = overview,
        isWatched = isWatched,
        lastEngagementTimeUtcMillis = watchHistory?.lastEngagementTimeUtcMillis,
        lastPlaybackPositionMillis = watchHistory?.lastPlaybackPositionMillis,
        durationMillis = watchHistory?.durationMillis,

        tvShowId = tvShow?.id,
        tvShowTitle = tvShow?.title,
        tvShowPoster = tvShow?.poster,
        tvShowBanner = tvShow?.banner,

        seasonId = season?.id,
        seasonNumber = season?.number,
        seasonTitle = season?.title,
        seasonPoster = season?.poster,
    )
}
