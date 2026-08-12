package com.streamflixreborn.streamflix.sync

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.WatchItem
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.ui.UserDataNotifier
import com.streamflixreborn.streamflix.utils.UserDataCache
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object CloudSyncManager {
    private const val TAG = "CloudSync"
    private const val TABLE = "user_media_state"
    private const val FETCH_PAGE_SIZE = 500L
    private val accountSyncMutex = Mutex()

    @Volatile
    var isApplyingRemote: Boolean = false
        private set

    fun currentUserId(): String? = if (!SupabaseProvider.isConfigured) {
        null
    } else {
        SupabaseProvider.activeClientOrNull()?.auth?.currentSessionOrNull()?.user?.id
    }

    fun currentUserEmail(): String? = if (!SupabaseProvider.isConfigured) {
        null
    } else {
        SupabaseProvider.activeClientOrNull()?.auth?.currentSessionOrNull()?.user?.email
    }

    suspend fun initialize(context: Context) {
        val appContext = context.applicationContext
        if (!SupabaseProvider.isConfigured) return
        SupabaseProvider.initialize(appContext)

        // Auth restores its persisted session asynchronously. Reading the session while it is
        // still Initializing briefly looks like a sign-out and must not clear local user data.
        SupabaseProvider.client.auth.awaitInitialization()
        val userId = currentUserId()
        if (userId == null) {
            CloudRealtimeSync.stop()
            // Keep local media state when the persisted session is absent. A
            // signed-out user should stop syncing, not lose local favorites or
            // watch history.
            CloudAccountStore.setActiveUserId(appContext, null)
            return
        }
        activateAccount(appContext, userId)
        CloudRealtimeSync.start(appContext, userId)
    }

    suspend fun signIn(
        context: Context,
        email: String,
        password: String,
        onProgress: (CloudSyncProgress) -> Unit = {},
    ) {
        requireConfigured()
        SupabaseProvider.initialize(context.applicationContext)
        onProgress(CloudSyncProgress(CloudSyncProgress.Stage.AUTHENTICATING))
        SupabaseProvider.client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        val userId = currentUserId() ?: error("Sign in did not create a session")
        activateAccount(
            context = context.applicationContext,
            userId = userId,
            onProgress = onProgress,
            mergeLocalOnLogin = true,
        )
        CloudRealtimeSync.start(context.applicationContext, userId)
    }

    suspend fun signUp(
        context: Context,
        email: String,
        password: String,
        onProgress: (CloudSyncProgress) -> Unit = {},
    ): Boolean {
        requireConfigured()
        SupabaseProvider.initialize(context.applicationContext)
        onProgress(CloudSyncProgress(CloudSyncProgress.Stage.AUTHENTICATING))
        SupabaseProvider.client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
        val userId = currentUserId() ?: return false
        activateAccount(
            context = context.applicationContext,
            userId = userId,
            onProgress = onProgress,
            mergeLocalOnLogin = true,
        )
        CloudRealtimeSync.start(context.applicationContext, userId)
        return true
    }

    suspend fun signOut(context: Context) {
        val appContext = context.applicationContext
        CloudRealtimeSync.stop()
        runCatching { flushPending(appContext) }
        if (SupabaseProvider.isConfigured) {
            SupabaseProvider.client.auth.signOut()
        }
        CloudAccountStore.setActiveUserId(appContext, null)
    }

    suspend fun syncNow(
        context: Context,
        onProgress: (CloudSyncProgress) -> Unit = {},
    ) = accountSyncMutex.withLock {
        syncNowLocked(context, onProgress)
    }

    private suspend fun syncNowLocked(
        context: Context,
        onProgress: (CloudSyncProgress) -> Unit,
    ) {
        val appContext = context.applicationContext
        val userId = currentUserId() ?: error("Sign in before synchronizing")
        flushPending(appContext, onProgress)
        onProgress(CloudSyncProgress(CloudSyncProgress.Stage.CHECKING_CLOUD))
        val remote = fetchRemote()
        onProgress(
            CloudSyncProgress(
                CloudSyncProgress.Stage.APPLYING_CLOUD,
                current = remote.size,
                total = remote.size,
            ),
        )
        withContext(Dispatchers.IO) { applyRemote(appContext, remote) }
        onProgress(CloudSyncProgress(CloudSyncProgress.Stage.FINALIZING))
        CloudAccountStore.setActiveUserId(appContext, userId)
    }

    suspend fun flushPending(
        context: Context,
        onProgress: (CloudSyncProgress) -> Unit = {},
    ) {
        val userId = currentUserId() ?: return
        while (true) {
            val pending = CloudMutationStore.pendingForUser(context, userId)
            if (pending.isEmpty()) return
            // The queue can contain playback state created before this device
            // went offline. Fetch first so it cannot overwrite newer progress
            // that another device has already uploaded.
            val remoteByKey = fetchRemote().associateBy { it.queueKey }
            val uploadable = pending.filter { mutation ->
                val remote = remoteByKey[mutation.queueKey]
                remote == null || pendingStateWins(mutation, remote)
            }
            if (uploadable.isNotEmpty()) {
                upsert(uploadable, onProgress)
            }
            // Acknowledge stale mutations too. acknowledge() keeps any newer
            // version that was queued while this upload was in progress.
            CloudMutationStore.acknowledge(context, pending)
        }
    }

    private suspend fun activateAccount(
        context: Context,
        userId: String,
        onProgress: (CloudSyncProgress) -> Unit = {},
        mergeLocalOnLogin: Boolean = false,
    ) = accountSyncMutex.withLock {
        val previousUserId = CloudAccountStore.activeUserId(context)
        if (previousUserId == userId && !mergeLocalOnLogin) {
            syncNowLocked(context, onProgress)
            return@withLock
        }

        onProgress(CloudSyncProgress(CloudSyncProgress.Stage.CHECKING_CLOUD))
        val remote = fetchRemote()
        val legacyOwnerId = CloudAccountStore.legacyOwnerId(context)
        val canMergeLocal = shouldMergeLocal(
            previousUserId = previousUserId,
            legacyOwnerId = legacyOwnerId,
            userId = userId,
            mergeLocalOnLogin = mergeLocalOnLogin,
        )

        isApplyingRemote = true
        try {
            if (canMergeLocal) {
                onProgress(CloudSyncProgress(CloudSyncProgress.Stage.PREPARING_LOCAL))
                val local = withContext(Dispatchers.IO) {
                    collectLocalState(context, userId)
                }
                onProgress(CloudSyncProgress(CloudSyncProgress.Stage.MERGING))
                val merged = mergeForFirstLogin(
                    remote = remote,
                    local = local,
                    mergedAtMillis = System.currentTimeMillis(),
                )
                if (local.isNotEmpty()) {
                    val localKeys = local.mapTo(hashSetOf()) { it.queueKey }
                    upsert(merged.filter { it.queueKey in localKeys }, onProgress)
                }
                val finalRemote = if (local.isEmpty()) remote else {
                    onProgress(CloudSyncProgress(CloudSyncProgress.Stage.CHECKING_CLOUD))
                    fetchRemote()
                }
                onProgress(
                    CloudSyncProgress(
                        CloudSyncProgress.Stage.APPLYING_CLOUD,
                        current = finalRemote.size,
                        total = finalRemote.size,
                    ),
                )
                withContext(Dispatchers.IO) {
                    applyRemoteInternal(context, finalRemote)
                }
                CloudAccountStore.claimLegacyData(context, userId)
            } else {
                val local = withContext(Dispatchers.IO) {
                    collectLocalState(context, userId)
                }
                if (local.isNotEmpty()) {
                    // Do not silently destroy local state when reconnecting a
                    // device to a different cloud account.
                    runCatching { SupabaseProvider.client.auth.signOut() }
                    CloudRealtimeSync.stop()
                    throw CloudAccountDataConflictException()
                }

                onProgress(
                    CloudSyncProgress(
                        CloudSyncProgress.Stage.APPLYING_CLOUD,
                        current = remote.size,
                        total = remote.size,
                    ),
                )
                withContext(Dispatchers.IO) {
                    applyRemoteInternal(context, remote)
                }
            }
            onProgress(CloudSyncProgress(CloudSyncProgress.Stage.FINALIZING))
            CloudAccountStore.setActiveUserId(context, userId)
        } finally {
            isApplyingRemote = false
        }
    }

    internal suspend fun applyRealtimeState(
        context: Context,
        state: RemoteMediaState,
    ) = accountSyncMutex.withLock {
        val userId = currentUserId()
        val pending = userId?.let {
            CloudMutationStore.pendingForUser(context, it)
        }.orEmpty()
        if (!shouldApplyRealtimeState(userId, state, pending)) return@withLock

        withContext(Dispatchers.IO) {
            applyRemote(context.applicationContext, listOf(state))
        }
    }

    internal fun shouldApplyRealtimeState(
        currentUserId: String?,
        state: RemoteMediaState,
        pending: List<RemoteMediaState>,
    ): Boolean {
        if (currentUserId == null || state.userId != currentUserId) return false
        return pending.none { mutation ->
            mutation.queueKey == state.queueKey && pendingStateWins(mutation, state)
        }
    }

    /**
     * client_updated_at is the enqueue time, not necessarily when playback
     * happened. Compare actual user-state timestamps before using it as a
     * tie-breaker.
     */
    internal fun pendingStateWins(
        pending: RemoteMediaState,
        remote: RemoteMediaState,
    ): Boolean {
        val pendingStateTime = pending.userStateTimestamp()
        val remoteStateTime = remote.userStateTimestamp()
        return if (pendingStateTime != remoteStateTime) {
            pendingStateTime > remoteStateTime
        } else {
            pending.clientUpdatedAtMillis >= remote.clientUpdatedAtMillis
        }
    }

    private fun RemoteMediaState.userStateTimestamp(): Long = listOfNotNull(
        watchedAtMillis,
        lastEngagementAtMillis,
        favoritedAtMillis,
    ).maxOrNull() ?: clientUpdatedAtMillis

    internal fun shouldMergeLocal(
        previousUserId: String?,
        legacyOwnerId: String?,
        userId: String,
        mergeLocalOnLogin: Boolean,
    ): Boolean {
        val localDataBelongsToUser =
            legacyOwnerId == null || legacyOwnerId == userId
        val accountCanOwnCurrentLocalData =
            previousUserId == null || (mergeLocalOnLogin && previousUserId == userId)
        return localDataBelongsToUser && accountCanOwnCurrentLocalData
    }

    internal fun mergeForFirstLogin(
        remote: List<RemoteMediaState>,
        local: List<RemoteMediaState>,
        mergedAtMillis: Long,
    ): List<RemoteMediaState> {
        val merged = remote.associateByTo(linkedMapOf()) { it.queueKey }
        local.forEach { localState ->
            val remoteState = merged[localState.queueKey]
            merged[localState.queueKey] = if (remoteState == null) {
                localState.copy(
                    clientUpdatedAtMillis = maxOf(
                        localState.clientUpdatedAtMillis,
                        mergedAtMillis,
                    ),
                )
            } else {
                mergeState(remoteState, localState, mergedAtMillis)
            }
        }
        return merged.values.toList()
    }

    private fun mergeState(
        remote: RemoteMediaState,
        local: RemoteMediaState,
        mergedAtMillis: Long,
    ): RemoteMediaState {
        val newest = if (local.clientUpdatedAtMillis >= remote.clientUpdatedAtMillis) {
            local
        } else {
            remote
        }
        val oldest = if (newest === local) remote else local
        val latestHistory = when {
            newest.isWatched && newest.lastEngagementAtMillis == null -> null
            local.lastEngagementAtMillis == null -> remote.takeIf {
                it.lastEngagementAtMillis != null
            }
            remote.lastEngagementAtMillis == null -> local
            local.lastEngagementAtMillis >= remote.lastEngagementAtMillis -> local
            else -> remote
        }
        return newest.copy(
            parentShowId = newest.parentShowId ?: oldest.parentShowId,
            parentShowTitle = newest.parentShowTitle ?: oldest.parentShowTitle,
            parentShowPoster = newest.parentShowPoster ?: oldest.parentShowPoster,
            parentShowBanner = newest.parentShowBanner ?: oldest.parentShowBanner,
            seasonId = newest.seasonId ?: oldest.seasonId,
            seasonNumber = newest.seasonNumber ?: oldest.seasonNumber,
            seasonTitle = newest.seasonTitle ?: oldest.seasonTitle,
            seasonPoster = newest.seasonPoster ?: oldest.seasonPoster,
            episodeNumber = newest.episodeNumber ?: oldest.episodeNumber,
            title = newest.title.ifBlank { oldest.title },
            poster = newest.poster ?: oldest.poster,
            banner = newest.banner ?: oldest.banner,
            isFavorite = remote.isFavorite || local.isFavorite,
            favoritedAtMillis = maxNullable(
                remote.favoritedAtMillis,
                local.favoritedAtMillis,
            ),
            // Watched state is replaceable. OR made it impossible for a newer
            // local "unwatched" state to clear a stale cloud completion.
            isWatched = newest.isWatched,
            watchedAtMillis = newest.watchedAtMillis,
            lastEngagementAtMillis = latestHistory?.lastEngagementAtMillis,
            playbackPositionMillis = latestHistory?.playbackPositionMillis,
            durationMillis = latestHistory?.durationMillis,
            isWatching = local.isWatching ?: remote.isWatching ?: newest.isWatching,
            clientUpdatedAtMillis = maxOf(
                remote.clientUpdatedAtMillis,
                local.clientUpdatedAtMillis,
                mergedAtMillis,
            ),
        )
    }

    private fun maxNullable(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }

    private suspend fun fetchRemote(): List<RemoteMediaState> =
        collectPages(FETCH_PAGE_SIZE) { from, to ->
            SupabaseProvider.client.from(TABLE).select {
                order("provider", Order.ASCENDING)
                order("media_type", Order.ASCENDING)
                order("media_id", Order.ASCENDING)
                range(from, to)
            }.decodeList()
        }

    internal suspend fun <T> collectPages(
        pageSize: Long,
        fetchPage: suspend (from: Long, to: Long) -> List<T>,
    ): List<T> {
        require(pageSize > 0)
        val items = mutableListOf<T>()
        var from = 0L
        do {
            val page = fetchPage(from, from + pageSize - 1)
            items += page
            from += page.size
        } while (page.size == pageSize.toInt())
        return items
    }

    private suspend fun upsert(
        states: List<RemoteMediaState>,
        onProgress: (CloudSyncProgress) -> Unit = {},
    ) {
        var uploaded = 0
        onProgress(
            CloudSyncProgress(
                CloudSyncProgress.Stage.UPLOADING,
                current = uploaded,
                total = states.size,
            ),
        )
        states.chunked(250).forEach { chunk ->
            SupabaseProvider.client.from(TABLE).upsert(chunk) {
                onConflict = "user_id,provider,media_type,media_id"
            }
            uploaded += chunk.size
            onProgress(
                CloudSyncProgress(
                    CloudSyncProgress.Stage.UPLOADING,
                    current = uploaded,
                    total = states.size,
                ),
            )
        }
    }

    private fun collectLocalState(context: Context, userId: String): List<RemoteMediaState> {
        val states = mutableListOf<RemoteMediaState>()
        existingProviders(context).forEach { provider ->
            val db = AppDatabase.getInstanceForProvider(provider.name, context)
            try {
                db.movieDao().getAll()
                    .filter { movie ->
                        movie.isFavorite || movie.isWatched || movie.watchedDate != null ||
                            movie.watchHistory != null
                    }
                    .forEach { movie ->
                        states += RemoteMediaState.fromMovie(
                            userId,
                            provider.name,
                            movie,
                            movie.stateTimestamp(),
                        )
                    }
                db.tvShowDao().getAllForBackup()
                    .filter { show ->
                        show.isFavorite || !show.isWatching
                    }
                    .forEach { show ->
                        states += RemoteMediaState.fromTvShow(
                            userId,
                            provider.name,
                            show,
                            show.stateTimestamp(),
                        )
                    }
                db.episodeDao().getAllForBackup()
                    .filter { episode ->
                        episode.isWatched || episode.watchedDate != null || episode.watchHistory != null
                    }
                    .forEach { episode ->
                        states += RemoteMediaState.fromEpisode(
                            userId,
                            provider.name,
                            episode,
                            episode.stateTimestamp(),
                        )
                    }
            } finally {
                db.close()
            }
        }
        return states
    }

    private fun applyRemote(context: Context, states: List<RemoteMediaState>) {
        isApplyingRemote = true
        try {
            applyRemoteInternal(context, states)
        } finally {
            isApplyingRemote = false
        }
    }

    private fun applyRemoteInternal(context: Context, states: List<RemoteMediaState>) {
        states.groupBy { it.provider }.forEach { (providerName, providerStates) ->
            val provider = providerByName(providerName) ?: run {
                Log.w(TAG, "Skipping state for unavailable provider $providerName")
                return@forEach
            }
            val db = AppDatabase.getInstanceForProvider(provider.name, context)
            try {
                val statesToApply = providerStates.filter { state ->
                    shouldApplyRemoteState(db, state)
                }
                if (statesToApply.isEmpty()) return@forEach

                db.runInTransaction {
                    statesToApply.filter { it.mediaType == "movie" }.forEach { state ->
                        val movie = db.movieDao().getById(state.mediaId)
                            ?: Movie(
                                id = state.mediaId,
                                title = state.title,
                                poster = state.poster,
                                banner = state.banner,
                            )
                        movie.isFavorite = state.isFavorite
                        movie.favoritedAtMillis = state.favoritedAtMillis
                        movie.isWatched = state.isWatched
                        movie.watchedDate = state.watchedAtMillis.toCalendar()
                        movie.watchHistory = state.toWatchHistory()
                        db.movieDao().insert(movie)
                    }

                    statesToApply.filter { it.mediaType == "tv_show" }.forEach { state ->
                        val show = db.tvShowDao().getById(state.mediaId)
                            ?: TvShow(
                                id = state.mediaId,
                                title = state.title,
                                poster = state.poster,
                                banner = state.banner,
                            )
                        show.isFavorite = state.isFavorite
                        show.favoritedAtMillis = state.favoritedAtMillis
                        show.isWatching = state.isWatching ?: true
                        db.tvShowDao().insert(show)
                    }

                    statesToApply.filter { it.mediaType == "episode" }.forEach { state ->
                        val show = state.parentShowId?.let { showId ->
                            db.tvShowDao().getById(showId) ?: TvShow(
                                id = showId,
                                title = state.parentShowTitle.orEmpty(),
                                poster = state.parentShowPoster,
                                banner = state.parentShowBanner,
                            ).also(db.tvShowDao()::insert)
                        }
                        val season = state.seasonId?.let { seasonId ->
                            db.seasonDao().getById(seasonId) ?: Season(
                                id = seasonId,
                                number = state.seasonNumber ?: 0,
                                title = state.seasonTitle,
                                poster = state.seasonPoster,
                                tvShow = show,
                            ).also(db.seasonDao()::insert)
                        }
                        val episode = db.episodeDao().getById(state.mediaId)
                            ?: Episode(
                                id = state.mediaId,
                                number = state.episodeNumber ?: 0,
                                title = state.title,
                                poster = state.poster,
                                tvShow = show,
                                season = season,
                            )
                        episode.isWatched = state.isWatched
                        episode.watchedDate = state.watchedAtMillis.toCalendar()
                        episode.watchHistory = state.toWatchHistory()
                        db.episodeDao().insert(episode)
                    }
                }

                UserDataCache.writeMovies(context, provider, db.movieDao().getAll())
                UserDataCache.writeTvShows(context, provider, db.tvShowDao().getAllForBackup())
                UserDataCache.writeEpisodes(context, provider, db.episodeDao().getAllForBackup())
            } finally {
                db.close()
            }
        }
        UserDataNotifier.notifyChanged()
    }

    /**
     * Realtime can deliver the same row more than once. Replacing an identical
     * Room entity still invalidates every observing Flow, so only apply newer,
     * materially different state.
     */
    private fun shouldApplyRemoteState(
        database: AppDatabase,
        state: RemoteMediaState,
    ): Boolean {
        return when (state.mediaType) {
            "movie" -> database.movieDao().getById(state.mediaId)?.let { movie ->
                if (movie.cloudStateTimestamp() > state.clientUpdatedAtMillis) return false
                !movie.matchesRemoteState(state)
            } ?: true

            "tv_show" -> database.tvShowDao().getById(state.mediaId)?.let { show ->
                if (show.cloudStateTimestamp() > state.clientUpdatedAtMillis) return false
                !show.matchesRemoteState(state)
            } ?: true

            "episode" -> database.episodeDao().getById(state.mediaId)?.let { episode ->
                if (episode.cloudStateTimestamp() > state.clientUpdatedAtMillis) return false
                !episode.matchesRemoteState(state)
            } ?: true

            else -> false
        }
    }

    private fun Movie.matchesRemoteState(state: RemoteMediaState): Boolean =
        isFavorite == state.isFavorite &&
            favoritedAtMillis == state.favoritedAtMillis &&
            isWatched == state.isWatched &&
            watchedDate?.timeInMillis == state.watchedAtMillis &&
            watchHistory?.lastEngagementTimeUtcMillis == state.lastEngagementAtMillis &&
            watchHistory?.lastPlaybackPositionMillis == state.playbackPositionMillis &&
            watchHistory?.durationMillis == state.durationMillis

    private fun TvShow.matchesRemoteState(state: RemoteMediaState): Boolean =
        isFavorite == state.isFavorite &&
            favoritedAtMillis == state.favoritedAtMillis &&
            isWatching == (state.isWatching ?: true)

    private fun Episode.matchesRemoteState(state: RemoteMediaState): Boolean =
        isWatched == state.isWatched &&
            watchedDate?.timeInMillis == state.watchedAtMillis &&
            watchHistory?.lastEngagementTimeUtcMillis == state.lastEngagementAtMillis &&
            watchHistory?.lastPlaybackPositionMillis == state.playbackPositionMillis &&
            watchHistory?.durationMillis == state.durationMillis

    private fun Movie.cloudStateTimestamp(): Long = listOfNotNull(
        favoritedAtMillis,
        watchedDate?.timeInMillis,
        watchHistory?.lastEngagementTimeUtcMillis,
    ).maxOrNull() ?: Long.MIN_VALUE

    private fun TvShow.cloudStateTimestamp(): Long = listOfNotNull(
        favoritedAtMillis,
    ).maxOrNull() ?: Long.MIN_VALUE

    private fun Episode.cloudStateTimestamp(): Long = listOfNotNull(
        watchedDate?.timeInMillis,
        watchHistory?.lastEngagementTimeUtcMillis,
    ).maxOrNull() ?: Long.MIN_VALUE

    private fun clearLocalUserState(context: Context) {
        existingProviders(context).forEach { provider ->
            val db = AppDatabase.getInstanceForProvider(provider.name, context)
            try {
                db.runInTransaction {
                    db.movieDao().clearUserState()
                    db.tvShowDao().clearUserState()
                    db.episodeDao().clearUserState()
                }
            } finally {
                db.close()
            }
        }
        UserDataCache.clearAll(context)
        UserDataNotifier.notifyChanged()
    }

    private fun existingProviders(context: Context): List<Provider> = allProviders()
        .distinctBy { it.name }
        .filter { provider ->
        context.getDatabasePath(AppDatabase.databaseNameFor(provider.name)).exists()
        }

    private fun allProviders(): List<Provider> = (Provider.providers.keys +
        listOf("it", "en", "es", "de", "fr").map(::TmdbProvider)).toList()

    private fun providerByName(name: String): Provider? =
        allProviders().firstOrNull { it.name == name }

    private fun requireConfigured() {
        check(SupabaseProvider.isConfigured) {
            "Configure Supabase in Settings > Account & sync before signing in"
        }
    }

    private fun Movie.stateTimestamp(): Long = listOfNotNull(
        favoritedAtMillis,
        watchedDate?.timeInMillis,
        watchHistory?.lastEngagementTimeUtcMillis,
    ).maxOrNull() ?: System.currentTimeMillis()

    private fun TvShow.stateTimestamp(): Long = listOfNotNull(
        favoritedAtMillis,
    ).maxOrNull() ?: System.currentTimeMillis()

    private fun Episode.stateTimestamp(): Long = listOfNotNull(
        watchedDate?.timeInMillis,
        watchHistory?.lastEngagementTimeUtcMillis,
    ).maxOrNull() ?: System.currentTimeMillis()

    private fun RemoteMediaState.toWatchHistory(): WatchItem.WatchHistory? =
        lastEngagementAtMillis?.let { engagedAt ->
            WatchItem.WatchHistory(
                lastEngagementTimeUtcMillis = engagedAt,
                lastPlaybackPositionMillis = playbackPositionMillis ?: 0L,
                durationMillis = durationMillis ?: 0L,
            )
        }

    private fun Long?.toCalendar(): Calendar? = this?.let { millis ->
        Calendar.getInstance().apply { timeInMillis = millis }
    }
}
