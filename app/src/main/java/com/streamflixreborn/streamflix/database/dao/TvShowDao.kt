package com.streamflixreborn.streamflix.database.dao

import android.util.Log
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.streamflixreborn.streamflix.models.TvShow
import kotlinx.coroutines.flow.Flow
import androidx.room.Transaction
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format

@Dao
interface TvShowDao {

    @Query("SELECT * FROM tv_shows")
    fun getAllForBackup(): List<TvShow>

    @Query("SELECT * FROM tv_shows WHERE id = :id")
    fun getById(id: String): TvShow?

    @Query("SELECT * FROM tv_shows WHERE id = :id")
    fun getByIdAsFlow(id: String): Flow<TvShow?>

    @Query("SELECT * FROM tv_shows WHERE id IN (:ids)")
    fun getByIds(ids: List<String>): Flow<List<TvShow>>

    @Query("SELECT * FROM tv_shows WHERE isFavorite = 1 ORDER BY favoritedAtMillis DESC")
    fun getFavorites(): Flow<List<TvShow>>

    @Query("SELECT * FROM tv_shows WHERE isFavorite = 1 OR poster IS NULL OR poster = '' OR banner IS NULL OR banner = ''")
    suspend fun getArtworkRepairCandidates(): List<TvShow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(tvShow: TvShow)

    @Update(onConflict = OnConflictStrategy.REPLACE)
    fun update(tvShow: TvShow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(tvShows: List<TvShow>)

    @Query("SELECT * FROM tv_shows")
    fun getAll(): Flow<List<TvShow>>

    @Query("SELECT * FROM tv_shows WHERE lastPlayedAtMillis IS NOT NULL ORDER BY lastPlayedAtMillis DESC LIMIT 10")
    fun getRecentlyWatched(): Flow<List<TvShow>>

    @Query("UPDATE tv_shows SET lastPlayedAtMillis = :playedAtMillis, lastPlayedEpisodeId = :episodeId WHERE id = :id")
    fun markRecentlyWatched(id: String, episodeId: String, playedAtMillis: Long): Int

    @Query("SELECT * FROM tv_shows WHERE poster IS NULL or poster = ''")
    suspend fun getAllWithNullPoster(): List<TvShow>

    @Query("SELECT id FROM tv_shows")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM tv_shows WHERE LOWER(title) LIKE '%' || :query || '%' LIMIT :limit OFFSET :offset")
    suspend fun searchTvShows(query: String, limit: Int, offset: Int): List<TvShow>

    @Query("DELETE FROM tv_shows")
    fun deleteAll()

    @Query("""
        UPDATE tv_shows SET
            isFavorite = 0,
            favoritedAtMillis = NULL,
            isWatching = 1
    """)
    fun clearUserState()

    @Transaction
    fun save(tvShow: TvShow) {
        val provider = UserPreferences.currentProvider?.name ?: "Unknown"
        val existing = getById(tvShow.id)
        if (existing != null) {
            val merged = tvShow.merge(existing)
            update(merged)
            Log.d("DatabaseVerify", "[$provider] REAL-TIME UPDATE TV Show: ${merged.title} (Fav: ${merged.isFavorite}, Watching: ${merged.isWatching})")
        } else {
            insert(tvShow)
            Log.d("DatabaseVerify", "[$provider] REAL-TIME INSERT TV Show: ${tvShow.title} (Fav: ${tvShow.isFavorite})")
        }
    }

    @Transaction
    fun setFavoriteWithLog(id: String, favorite: Boolean) {
        val provider = UserPreferences.currentProvider?.name ?: "Unknown"
        setFavorite(id, favorite, if (favorite) System.currentTimeMillis() else null)
        Log.d("DatabaseVerify", "[$provider] REAL-TIME Favorite Toggled: ID $id -> $favorite")
    }

    @Transaction
    fun upsertFavorite(tvShow: TvShow, favorite: Boolean) {
        val existing = getById(tvShow.id)
        if (existing != null) {
            val updated = existing.copy(
                title = tvShow.title.ifBlank { existing.title },
                overview = tvShow.overview ?: existing.overview,
                released = tvShow.released?.format("yyyy-MM-dd") ?: existing.released?.format("yyyy-MM-dd"),
                runtime = tvShow.runtime ?: existing.runtime,
                trailer = tvShow.trailer ?: existing.trailer,
                quality = tvShow.quality ?: existing.quality,
                rating = tvShow.rating ?: existing.rating,
                poster = tvShow.poster ?: existing.poster,
                banner = tvShow.banner ?: existing.banner,
                imdbId = tvShow.imdbId ?: existing.imdbId,
                seasons = if (tvShow.seasons.isNotEmpty()) tvShow.seasons else existing.seasons,
                genres = if (tvShow.genres.isNotEmpty()) tvShow.genres else existing.genres,
                directors = if (tvShow.directors.isNotEmpty()) tvShow.directors else existing.directors,
                cast = if (tvShow.cast.isNotEmpty()) tvShow.cast else existing.cast,
                recommendations = if (tvShow.recommendations.isNotEmpty()) tvShow.recommendations else existing.recommendations,
                isFavorite = favorite,
            )
            updated.favoritedAtMillis = if (favorite) System.currentTimeMillis() else null
            updated.isWatching = existing.isWatching
            updated.lastPlayedAtMillis = existing.lastPlayedAtMillis
            updated.lastPlayedEpisodeId = existing.lastPlayedEpisodeId
            update(updated)
        } else {
            tvShow.isFavorite = favorite
            tvShow.favoritedAtMillis = if (favorite) System.currentTimeMillis() else null
            insert(tvShow)
        }
    }

    @Query("UPDATE tv_shows SET isFavorite = :favorite, favoritedAtMillis = :favoritedAtMillis WHERE id = :id")
    fun setFavorite(id: String, favorite: Boolean, favoritedAtMillis: Long?)
}
