package com.streamflixreborn.streamflix.sync

import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RemoteMediaState(
    @SerialName("user_id") val userId: String,
    val provider: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("media_id") val mediaId: String,
    @EncodeDefault @SerialName("parent_show_id") val parentShowId: String? = null,
    @EncodeDefault @SerialName("parent_show_title") val parentShowTitle: String? = null,
    @EncodeDefault @SerialName("parent_show_poster") val parentShowPoster: String? = null,
    @EncodeDefault @SerialName("parent_show_banner") val parentShowBanner: String? = null,
    @EncodeDefault @SerialName("season_id") val seasonId: String? = null,
    @EncodeDefault @SerialName("season_number") val seasonNumber: Int? = null,
    @EncodeDefault @SerialName("season_title") val seasonTitle: String? = null,
    @EncodeDefault @SerialName("season_poster") val seasonPoster: String? = null,
    @EncodeDefault @SerialName("episode_number") val episodeNumber: Int? = null,
    @EncodeDefault val title: String = "",
    @EncodeDefault val poster: String? = null,
    @EncodeDefault val banner: String? = null,
    @EncodeDefault @SerialName("is_favorite") val isFavorite: Boolean = false,
    @EncodeDefault @SerialName("favorited_at_millis") val favoritedAtMillis: Long? = null,
    @EncodeDefault @SerialName("is_watched") val isWatched: Boolean = false,
    @EncodeDefault @SerialName("watched_at_millis") val watchedAtMillis: Long? = null,
    @EncodeDefault @SerialName("last_engagement_at_millis")
    val lastEngagementAtMillis: Long? = null,
    @EncodeDefault @SerialName("playback_position_millis")
    val playbackPositionMillis: Long? = null,
    @EncodeDefault @SerialName("duration_millis") val durationMillis: Long? = null,
    @EncodeDefault @SerialName("is_watching") val isWatching: Boolean? = null,
    @SerialName("client_updated_at_millis") val clientUpdatedAtMillis: Long,
) {
    val queueKey: String
        get() = listOf(userId, provider, mediaType, mediaId).joinToString("\u001f")

    companion object {
        fun fromMovie(userId: String, provider: String, movie: Movie, now: Long) =
            RemoteMediaState(
                userId = userId,
                provider = provider,
                mediaType = "movie",
                mediaId = movie.id,
                title = movie.title,
                poster = movie.poster,
                banner = movie.banner,
                isFavorite = movie.isFavorite,
                favoritedAtMillis = movie.favoritedAtMillis,
                isWatched = movie.isWatched,
                watchedAtMillis = movie.watchedDate?.timeInMillis,
                lastEngagementAtMillis = movie.watchHistory?.lastEngagementTimeUtcMillis,
                playbackPositionMillis = movie.watchHistory?.lastPlaybackPositionMillis,
                durationMillis = movie.watchHistory?.durationMillis,
                clientUpdatedAtMillis = now,
            )

        fun fromTvShow(userId: String, provider: String, show: TvShow, now: Long) =
            RemoteMediaState(
                userId = userId,
                provider = provider,
                mediaType = "tv_show",
                mediaId = show.id,
                title = show.title,
                poster = show.poster,
                banner = show.banner,
                isFavorite = show.isFavorite,
                favoritedAtMillis = show.favoritedAtMillis,
                isWatching = show.isWatching,
                clientUpdatedAtMillis = now,
            )

        fun fromEpisode(userId: String, provider: String, episode: Episode, now: Long) =
            RemoteMediaState(
                userId = userId,
                provider = provider,
                mediaType = "episode",
                mediaId = episode.id,
                parentShowId = episode.tvShow?.id,
                parentShowTitle = episode.tvShow?.title,
                parentShowPoster = episode.tvShow?.poster,
                parentShowBanner = episode.tvShow?.banner,
                seasonId = episode.season?.id,
                seasonNumber = episode.season?.number,
                seasonTitle = episode.season?.title,
                seasonPoster = episode.season?.poster,
                episodeNumber = episode.number,
                title = episode.title.orEmpty(),
                poster = episode.poster,
                isWatched = episode.isWatched,
                watchedAtMillis = episode.watchedDate?.timeInMillis,
                lastEngagementAtMillis = episode.watchHistory?.lastEngagementTimeUtcMillis,
                playbackPositionMillis = episode.watchHistory?.lastPlaybackPositionMillis,
                durationMillis = episode.watchHistory?.durationMillis,
                clientUpdatedAtMillis = now,
            )
    }
}
