package com.streamflixreborn.streamflix.sync

import android.content.Context
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.ProfileManager

object CloudSyncHooks {
    fun movie(context: Context, provider: Provider, movie: Movie) {
        enqueue(context) { userId, now ->
            RemoteMediaState.fromMovie(userId, provider.name, movie, now)
        }
    }

    fun movie(context: Context, provider: Provider, id: String) {
        if (CloudSyncManager.isApplyingRemote) return
        val profileId = ProfileManager.activeProfileId ?: return
        val movie = runCatching { AppDatabase.getInstanceForProvider(provider.name, context, profileId).movieDao().getById(id) }.getOrNull()
            ?: Movie(id = id)
        movie(context, provider, movie)
    }

    fun tvShow(context: Context, provider: Provider, show: TvShow) {
        enqueue(context) { userId, now ->
            RemoteMediaState.fromTvShow(userId, provider.name, show, now)
        }
    }

    fun tvShow(context: Context, provider: Provider, id: String) {
        if (CloudSyncManager.isApplyingRemote) return
        val profileId = ProfileManager.activeProfileId ?: return
        val show = runCatching { AppDatabase.getInstanceForProvider(provider.name, context, profileId).tvShowDao().getById(id) }.getOrNull()
            ?: TvShow(id = id)
        tvShow(context, provider, show)
    }

    fun episode(context: Context, provider: Provider, episode: Episode) {
        enqueue(context) { userId, now ->
            RemoteMediaState.fromEpisode(userId, provider.name, episode, now)
        }
    }

    fun episode(context: Context, provider: Provider, id: String) {
        if (CloudSyncManager.isApplyingRemote) return
        val profileId = ProfileManager.activeProfileId ?: return
        val episode = runCatching { AppDatabase.getInstanceForProvider(provider.name, context, profileId).episodeDao().getById(id) }.getOrNull()
            ?: Episode(id = id)
        episode(context, provider, episode)
    }

    private inline fun enqueue(
        context: Context,
        state: (userId: String, now: Long) -> RemoteMediaState,
    ) {
        if (CloudSyncManager.isApplyingRemote) return
        val profileId = ProfileManager.activeProfileId ?: return
        val userId = CloudSyncManager.currentUserId(profileId)
            ?: CloudAccountStore.activeUserId(context, profileId)
            ?: return
        CloudMutationStore.enqueue(
            context.applicationContext,
            profileId,
            state(userId, System.currentTimeMillis()),
        )
        CloudSyncScheduler.enqueue(context, profileId, userId)
    }
}
