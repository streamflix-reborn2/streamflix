package com.streamflixreborn.streamflix.database.dao

import android.util.Log
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.streamflixreborn.streamflix.models.Movie
import kotlinx.coroutines.flow.Flow
import androidx.room.Transaction
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format

@Dao
interface MovieDao {

    @Query("SELECT * FROM movies")
    fun getAll(): List<Movie>

    @Query("SELECT * FROM movies WHERE id = :id")
    fun getById(id: String): Movie?

    @Query("SELECT * FROM movies WHERE id = :id")
    fun getByIdAsFlow(id: String): Flow<Movie?>

    @Query("SELECT * FROM movies WHERE id IN (:ids)")
    fun getByIds(ids: List<String>): Flow<List<Movie>>

    @Query("SELECT * FROM movies WHERE isFavorite = 1 ORDER BY favoritedAtMillis DESC")
    fun getFavorites(): Flow<List<Movie>>

    @Query("SELECT * FROM movies WHERE isFavorite = 1 OR poster IS NULL OR poster = '' OR banner IS NULL OR banner = ''")
    suspend fun getArtworkRepairCandidates(): List<Movie>

    @Query("SELECT * FROM movies WHERE lastEngagementTimeUtcMillis IS NOT NULL ORDER BY lastEngagementTimeUtcMillis DESC")
    fun getWatchingMovies(): Flow<List<Movie>>

    @Query("SELECT * FROM movies WHERE lastPlayedAtMillis IS NOT NULL ORDER BY lastPlayedAtMillis DESC LIMIT 10")
    fun getRecentlyWatched(): Flow<List<Movie>>

    @Query("UPDATE movies SET lastPlayedAtMillis = :playedAtMillis WHERE id = :id")
    fun markRecentlyWatched(id: String, playedAtMillis: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(movie: Movie)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(movies: List<Movie>)

    @Update
    fun update(movie: Movie)

    @Query("DELETE FROM movies")
    fun deleteAll()

    @Query("""
        UPDATE movies SET
            isFavorite = 0,
            favoritedAtMillis = NULL,
            isWatched = 0,
            watchedDate = NULL,
            lastEngagementTimeUtcMillis = NULL,
            lastPlaybackPositionMillis = NULL,
            durationMillis = NULL
    """)
    fun clearUserState()

    @Transaction
    fun save(movie: Movie) {
        val provider = UserPreferences.currentProvider?.name ?: "Unknown"
        val existing = getById(movie.id)
        if (existing != null) {
            val merged = movie.merge(existing)
            update(merged)
            Log.d("DatabaseVerify", "[$provider] REAL-TIME UPDATE Movie: ${merged.title} (Fav: ${merged.isFavorite}, Watched: ${merged.isWatched})")
        } else {
            insert(movie)
            Log.d("DatabaseVerify", "[$provider] REAL-TIME INSERT Movie: ${movie.title} (Fav: ${movie.isFavorite})")
        }
    }

    @Transaction
    fun setFavoriteWithLog(id: String, favorite: Boolean) {
        val provider = UserPreferences.currentProvider?.name ?: "Unknown"
        setFavorite(id, favorite, if (favorite) System.currentTimeMillis() else null)
        Log.d("DatabaseVerify", "[$provider] REAL-TIME Favorite Toggled: ID $id -> $favorite")
    }

    @Transaction
    fun upsertFavorite(movie: Movie, favorite: Boolean) {
        val existing = getById(movie.id)
        if (existing != null) {
            val updated = existing.copy(
                title = movie.title.ifBlank { existing.title },
                overview = movie.overview ?: existing.overview,
                released = movie.released?.format("yyyy-MM-dd") ?: existing.released?.format("yyyy-MM-dd"),
                runtime = movie.runtime ?: existing.runtime,
                trailer = movie.trailer ?: existing.trailer,
                quality = movie.quality ?: existing.quality,
                rating = movie.rating ?: existing.rating,
                poster = movie.poster ?: existing.poster,
                banner = movie.banner ?: existing.banner,
                imdbId = movie.imdbId ?: existing.imdbId,
                genres = if (movie.genres.isNotEmpty()) movie.genres else existing.genres,
                directors = if (movie.directors.isNotEmpty()) movie.directors else existing.directors,
                cast = if (movie.cast.isNotEmpty()) movie.cast else existing.cast,
                recommendations = if (movie.recommendations.isNotEmpty()) movie.recommendations else existing.recommendations,
                isFavorite = favorite,
            )
            updated.favoritedAtMillis = if (favorite) System.currentTimeMillis() else null
            updated.isWatched = existing.isWatched
            updated.watchedDate = existing.watchedDate
            updated.watchHistory = existing.watchHistory
            updated.lastPlayedAtMillis = existing.lastPlayedAtMillis
            update(updated)
        } else {
            movie.isFavorite = favorite
            movie.favoritedAtMillis = if (favorite) System.currentTimeMillis() else null
            insert(movie)
        }
    }

    @Query("UPDATE movies SET isFavorite = :favorite, favoritedAtMillis = :favoritedAtMillis WHERE id = :id")
    fun setFavorite(id: String, favorite: Boolean, favoritedAtMillis: Long?)

}
