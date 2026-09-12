package com.streamflixreborn.streamflix.backup

import android.content.Context
import android.util.Log
import androidx.room.Transaction
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.database.dao.EpisodeDao
import com.streamflixreborn.streamflix.database.dao.MovieDao
import com.streamflixreborn.streamflix.database.dao.TvShowDao
import com.streamflixreborn.streamflix.database.dao.SeasonDao
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.WatchItem
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.ProfileManager
import com.streamflixreborn.streamflix.utils.UserDataCache
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Calendar
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ProviderBackupContext(
    val name: String,
    val movieDao: MovieDao,
    val tvShowDao: TvShowDao,
    val episodeDao: EpisodeDao,
    val seasonDao: SeasonDao,
    val provider: Provider
)

data class ExportMetadata(
    val profileId: String?,
    val profileName: String?,
)

class BackupRestoreManager(
    private val context: Context,
    initialProviders: List<ProviderBackupContext>
) {
    private val TAG = "BackupVerify"
    private val providerDefinitions = initialProviders.map { it.provider }.distinctBy { it.name }
    private var providers: List<ProviderBackupContext> = initialProviders
    private var boundProfileId: String? = ProfileManager.activeProfileId

    /**
     * DAO instances are tied to the database opened for a specific profile.
     * Settings can remain alive while the user switches profiles, so refresh
     * these bindings before every backup operation instead of reusing DAOs
     * from the profile that was active when Settings was opened.
     */
    private fun ensureActiveProfileBindings(force: Boolean = false) {
        val activeProfileId = ProfileManager.activeProfileId
        if (!force && activeProfileId == boundProfileId) return

        providers = providerDefinitions.mapNotNull { provider ->
            runCatching {
                val db = AppDatabase.getInstanceForProvider(provider.name, context, activeProfileId)
                ProviderBackupContext(
                    name = provider.name,
                    movieDao = db.movieDao(),
                    tvShowDao = db.tvShowDao(),
                    episodeDao = db.episodeDao(),
                    seasonDao = db.seasonDao(),
                    provider = provider,
                )
            }.onFailure { error ->
                Log.w(TAG, "Skipping ${provider.name} while rebinding profile $activeProfileId", error)
            }.getOrNull()
        }
        boundProfileId = activeProfileId
        Log.d(TAG, "Backup databases bound to profile $activeProfileId")
    }

    suspend fun refreshCachesFromDatabase(): Boolean {
        return try {
            ensureActiveProfileBindings()
            providers.forEach { buildCacheForProvider(it) }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Error refreshing caches from database", t)
            false
        }
    }

    fun exportDatabaseZip(): ByteArray? {
        return try {
            ensureActiveProfileBindings()
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                providers.forEach { providerCtx ->
                    addDatabaseFilesToZip(zip, providerCtx.name)
                }
            }
            output.toByteArray()
        } catch (t: Throwable) {
            Log.e(TAG, "Error exporting database zip", t)
            null
        }
    }

    suspend fun importDatabaseZip(zipBytes: ByteArray): Boolean {
        return try {
            AppDatabase.resetInstance()
            restoreDatabaseZip(ByteArrayInputStream(zipBytes))
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Error importing database zip", t)
            false
        }
    }

    fun exportUserData(): String? {
        return try {
            ensureActiveProfileBindings()
            val root = JSONObject()
            root.put("version", 5)
            root.put("exportedAt", System.currentTimeMillis())

            val activeProfile = ProfileManager.activeProfile
            if (activeProfile != null) {
                root.put("profileId", activeProfile.id)
                root.put("profileName", activeProfile.name)
            }

            val providersArray = JSONArray()
            for (p in providers) {
                val moviesToExport = p.movieDao.getAll()
                    .filter { it.isWatched || it.watchedDate != null || it.watchHistory != null || it.isFavorite || it.lastPlayedAtMillis != null }
                val tvShowsToExport = p.tvShowDao.getAllForBackup()
                    .filter { it.isWatching || it.isFavorite || it.lastPlayedAtMillis != null }
                val episodesToExport = p.episodeDao.getAllForBackup()
                    .filter { it.isWatched || it.watchedDate != null || it.watchHistory != null }

                if (moviesToExport.isEmpty() && tvShowsToExport.isEmpty() && episodesToExport.isEmpty()) {
                    continue
                }

                val providerObj = JSONObject()
                providerObj.put("name", p.name)

                val moviesArray = JSONArray()
                moviesToExport.forEach { movie ->
                    val obj = JSONObject().apply {
                        put("id", movie.id)
                        put("title", movie.title)
                        put("poster", movie.poster)
                        put("banner", movie.banner)
                        put("isFavorite", movie.isFavorite)
                        put("favoritedAtMillis", movie.favoritedAtMillis ?: JSONObject.NULL)
                        put("isWatched", movie.isWatched)
                        put("watchedDate", movie.watchedDate?.timeInMillis ?: JSONObject.NULL)
                        put("watchHistory", movie.watchHistory?.toJson() ?: JSONObject.NULL)
                        put("lastPlayedAtMillis", movie.lastPlayedAtMillis ?: JSONObject.NULL)
                    }
                    moviesArray.put(obj)
                    Log.d(TAG, "EXPORT: [${p.name}] Movie: ${movie.title} (Fav: ${movie.isFavorite})")
                }
                providerObj.put("movies", moviesArray)

                val tvShowsArray = JSONArray()
                tvShowsToExport.forEach { show ->
                    val obj = JSONObject().apply {
                        put("id", show.id)
                        put("title", show.title)
                        put("poster", show.poster)
                        put("banner", show.banner)
                        put("isFavorite", show.isFavorite)
                        put("favoritedAtMillis", show.favoritedAtMillis ?: JSONObject.NULL)
                        put("isWatching", show.isWatching)
                        put("lastPlayedAtMillis", show.lastPlayedAtMillis ?: JSONObject.NULL)
                        put("lastPlayedEpisodeId", show.lastPlayedEpisodeId ?: JSONObject.NULL)
                    }
                    tvShowsArray.put(obj)
                    Log.d(TAG, "EXPORT: [${p.name}] TV Show: ${show.title} (Fav: ${show.isFavorite})")
                }
                providerObj.put("tvShows", tvShowsArray)

                val seasonsArray = JSONArray()
                p.seasonDao.getAllForBackup()
                    .forEach { season ->
                        val obj = JSONObject().apply {
                            put("id", season.id)
                            put("number", season.number)
                            put("title", season.title)
                            put("poster", season.poster)
                            put("tvShowId", season.tvShow?.id)
                        }
                        seasonsArray.put(obj)
                    }
                providerObj.put("seasons", seasonsArray)

                val episodesArray = JSONArray()
                episodesToExport.forEach { ep ->
                    val obj = JSONObject().apply {
                        put("id", ep.id)
                        put("number", ep.number)
                        put("title", ep.title)
                        put("poster", ep.poster)
                        put("tvShowId", ep.tvShow?.id)
                        put("seasonId", ep.season?.id)
                        put("isWatched", ep.isWatched)
                        put("watchedDate", ep.watchedDate?.timeInMillis ?: JSONObject.NULL)
                        put("watchHistory", ep.watchHistory?.toJson() ?: JSONObject.NULL)
                    }
                    episodesArray.put(obj)
                    Log.d(TAG, "EXPORT: [${p.name}] Episode: ${ep.title} (Watched: ${ep.isWatched})")
                }
                providerObj.put("episodes", episodesArray)

                providersArray.put(providerObj)
            }

            root.put("providers", providersArray)
            Log.d(TAG, "Export successful for version 5. Total providers exported: ${providersArray.length()}")
            root.toString()
        } catch (t: Throwable) {
            Log.e(TAG, "Error during exportUserData", t)
            null
        }
    }

    fun parseExportMetadata(json: String): ExportMetadata? {
        return try {
            val obj = JSONObject(json)
            ExportMetadata(
                profileId = obj.optString("profileId", null)?.takeIf { it.isNotEmpty() && it != "null" },
                profileName = obj.optString("profileName", null)?.takeIf { it.isNotEmpty() && it != "null" },
            )
        } catch (t: Throwable) {
            null
        }
    }

    @Transaction
    suspend fun importUserData(json: String): Boolean {
        return try {
            ensureActiveProfileBindings(force = true)
            Log.d(TAG, "Import target profile: ${ProfileManager.activeProfileId}")
            val obj = JSONObject(json)
            val providersArray = obj.optJSONArray("providers") ?: return false
            val backupVersion = obj.optInt("version", 1)

            Log.d(TAG, "Starting import from version $backupVersion for ${providersArray.length()} providers")

            for (i in 0 until providersArray.length()) {
                val providerObj = providersArray.optJSONObject(i) ?: continue
                val providerName = providerObj.optString("name") ?: continue
                val providerCtx = providers.find { it.name == providerName } ?: run {
                    Log.w(TAG, "Provider '$providerName' not found in current providers. Skipping...")
                    continue
                }

                providerObj.optJSONArray("seasons")?.let { arr ->
                    val seasonsToSave = mutableListOf<Season>()
                    for (j in 0 until arr.length()) {
                        val s = arr.optJSONObject(j) ?: continue
                        val season = Season(
                            id = s.optString("id", ""),
                            number = s.optInt("number", 0)
                        ).apply {
                            title = s.optStringOrNull("title")
                            poster = s.optStringOrNull("poster")
                            s.optStringOrNull("tvShowId")?.let { tvId -> tvShow = TvShow(tvId, "") }
                        }
                        seasonsToSave.add(season)
                    }
                    if (seasonsToSave.isNotEmpty()) {
                        providerCtx.seasonDao.saveAll(seasonsToSave)
                        Log.d(TAG, "IMPORT: Imported ${seasonsToSave.size} seasons for provider $providerName")
                    }
                }

                providerObj.optJSONArray("tvShows")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        val s = arr.optJSONObject(j) ?: continue
                        val isFavorite = s.optBoolean("isFavorite", false)
                        val favoritedAtMillis = s.optLongOrNull("favoritedAtMillis")
                        val isWatching = s.optBoolean("isWatching", false) // Corretto il default a false
                        val lastPlayedAtMillis = s.optLongOrNull("lastPlayedAtMillis")
                        val lastPlayedEpisodeId = s.optStringOrNull("lastPlayedEpisodeId")

                        val tvShow = TvShow(
                            id = s.optString("id", ""),
                            title = s.optString("title", "")
                        ).apply {
                            poster = s.optStringOrNull("poster")
                            banner = s.optStringOrNull("banner")
                            this.isFavorite = isFavorite
                            this.favoritedAtMillis = favoritedAtMillis
                            this.isWatching = isWatching
                            this.lastPlayedAtMillis = lastPlayedAtMillis
                            this.lastPlayedEpisodeId = lastPlayedEpisodeId
                        }
                        providerCtx.tvShowDao.save(tvShow)
                        Log.d(TAG, "IMPORT: [${providerName}] TV Show: ${tvShow.title}. Favorites: $isFavorite, Watching: $isWatching")
                    }
                }

                providerObj.optJSONArray("movies")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        val m = arr.optJSONObject(j) ?: continue
                        val isFavorite = m.optBoolean("isFavorite", false)
                        val favoritedAtMillis = m.optLongOrNull("favoritedAtMillis")
                        val isWatched = m.optBoolean("isWatched", false)
                        val watchedDate = m.optLongOrNull("watchedDate")?.toCalendar()
                        val watchHistory = m.optJSONObject("watchHistory")?.toWatchHistory()
                        val lastPlayedAtMillis = m.optLongOrNull("lastPlayedAtMillis")

                        val movie = Movie(
                            id = m.optString("id", ""),
                            title = m.optString("title", "")
                        ).apply {
                            poster = m.optStringOrNull("poster")
                            banner = m.optStringOrNull("banner")
                            this.isFavorite = isFavorite
                            this.favoritedAtMillis = favoritedAtMillis
                            this.isWatched = isWatched
                            this.watchedDate = watchedDate
                            this.watchHistory = watchHistory
                            this.lastPlayedAtMillis = lastPlayedAtMillis
                        }
                        providerCtx.movieDao.save(movie)
                        Log.d(TAG, "IMPORT: [${providerName}] Movie: ${movie.title}. Favorites: $isFavorite, Watched: $isWatched, History: ${watchHistory != null}")
                    }
                }

                providerObj.optJSONArray("episodes")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        val e = arr.optJSONObject(j) ?: continue
                        val isWatched = e.optBoolean("isWatched", false)
                        val watchedDate = e.optLongOrNull("watchedDate")?.toCalendar()
                        val watchHistory = e.optJSONObject("watchHistory")?.toWatchHistory()

                        val ep = Episode(id = e.optString("id", "")).apply {
                            number = e.optInt("number", 0)
                            title = e.optStringOrNull("title")
                            poster = e.optStringOrNull("poster")
                            e.optStringOrNull("tvShowId")?.let { tvId -> tvShow = TvShow(tvId, "") }
                            e.optStringOrNull("seasonId")?.let { sId -> season = Season(sId, 0) }
                            this.isWatched = isWatched
                            this.watchedDate = watchedDate
                            this.watchHistory = watchHistory
                        }
                        providerCtx.episodeDao.save(ep)
                        Log.d(TAG, "IMPORT: [${providerName}] Episode: ${ep.title}. Watched: $isWatched, History: ${watchHistory != null}")
                    }
                }

                buildCacheForProvider(providerCtx)
            }

            Log.d(TAG, "Import completed successfully")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Error during importUserData", t)
            false
        }
    }

    private suspend fun buildCacheForProvider(providerCtx: ProviderBackupContext) {
        try {
            val movies = providerCtx.movieDao.getFavorites().first()
            val tvShows = providerCtx.tvShowDao.getFavorites().first()
            val watchingMovies = providerCtx.movieDao.getWatchingMovies().first()
            val watchingEpisodes = providerCtx.episodeDao.getWatchingEpisodes().first()

            UserDataCache.writeMovies(context, providerCtx.provider, movies + watchingMovies)
            UserDataCache.writeTvShows(context, providerCtx.provider, tvShows)
            UserDataCache.writeEpisodes(context, providerCtx.provider, watchingEpisodes)
            Log.d(TAG, "CACHE: Built cache for provider ${providerCtx.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error building cache for provider ${providerCtx.name}", e)
        }
    }

    private fun addDatabaseFilesToZip(zip: ZipOutputStream, providerName: String) {
        val prefixedDbName = AppDatabase.databaseNameFor(providerName).removeSuffix(".db")
        listOf("", "-wal", "-shm").forEach { suffix ->
            val file = context.getDatabasePath("$prefixedDbName.db$suffix")
            if (!file.exists()) return@forEach
            zip.putNextEntry(ZipEntry("databases/${file.name}"))
            file.inputStream().use { input -> input.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun restoreDatabaseZip(input: InputStream) {
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.startsWith("databases/")) {
                    val fileName = entry.name.removePrefix("databases/")
                    val target = context.getDatabasePath(fileName)
                    target.parentFile?.mkdirs()
                    if (target.exists()) target.delete()
                    target.outputStream().use { output ->
                        zip.copyTo(output)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

}
private fun Long.toCalendar(): Calendar = Calendar.getInstance().apply { timeInMillis = this@toCalendar }

private fun WatchItem.WatchHistory.toJson(): JSONObject =
    JSONObject().apply {
        put("lastEngagementTimeUtcMillis", lastEngagementTimeUtcMillis)
        put("lastPlaybackPositionMillis", lastPlaybackPositionMillis)
        put("durationMillis", durationMillis)
    }

private fun JSONObject.toWatchHistory(): WatchItem.WatchHistory? {
    val duration = optLong("durationMillis", 0L)

    if (duration <= 0) return null

    return WatchItem.WatchHistory(
        lastEngagementTimeUtcMillis = optLong("lastEngagementTimeUtcMillis", 0L),
        lastPlaybackPositionMillis = optLong("lastPlaybackPositionMillis", 0L),
        durationMillis = duration
    )
}


private fun JSONObject.optLongOrNull(name: String): Long? {
    return if (has(name) && !isNull(name)) optLong(name) else null
}

private fun JSONObject.optStringOrNull(name: String): String? {
    return if (has(name) && !isNull(name)) optString(name) else null
}
