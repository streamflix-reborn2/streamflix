package com.streamflixreborn.streamflix.models

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.utils.format
import com.streamflixreborn.streamflix.utils.toCalendar

@Entity(
    "tv_shows",
    indices = [
        Index(value = ["isWatching"]),
    ]
)
class TvShow(
    @PrimaryKey
    var id: String = "",
    var title: String = "",
    var overview: String? = null,
    released: String? = null,
    var runtime: Int? = null,
    var trailer: String? = null,
    var quality: String? = null,
    var rating: Double? = null,
    var poster: String? = null,
    var banner: String? = null,

    @Ignore
    var imdbId: String? = null,

    @Ignore
    var providerName: String? = null,
    @Ignore
    val seasons: List<Season> = listOf(),
    @Ignore
    val genres: List<Genre> = listOf(),
    @Ignore
    val directors: List<People> = listOf(),
    @Ignore
    val cast: List<People> = listOf(),
    @Ignore
    val recommendations: List<Show> = listOf(),
    override var isFavorite: Boolean = false,
) : Show, AppAdapter.Item {

    var released = released?.toCalendar()
    var favoritedAtMillis: Long? = null
    var lastPlayedAtMillis: Long? = null
    var lastPlayedEpisodeId: String? = null

    @Ignore
    var lastPlayedEpisode: Episode? = null

    var isWatching: Boolean = true

    val episodeToWatch: Episode?
        get() {
            val sortedSeasons = seasons
                .sortedWith(compareBy<Season> { it.number == 0 }.thenBy { it.number })
            val episodes = sortedSeasons
                .flatMap { season ->
                    season.episodes
                        .sortedBy { it.number }
                        .onEach { episode ->
                            episode.season = season
                            episode.tvShow = this
                        }
                }
            val episode = episodes
                .filter { it.watchHistory != null }
                .sortedByDescending { it.watchHistory?.lastEngagementTimeUtcMillis }
                .firstOrNull()
                ?: episodes.indexOfLast { it.isWatched }
                    .takeIf { it != -1 && it + 1 < episodes.size }
                    ?.let { episodes.getOrNull(it + 1) }
                ?: sortedSeasons.firstOrNull { it.number != 0 }
                    ?.episodes
                    ?.sortedBy { it.number }
                    ?.firstOrNull()
                ?: episodes.firstOrNull()
            return episode
        }

    fun isSame(tvShow: TvShow): Boolean {
        if (isFavorite != tvShow.isFavorite) return false
        if (favoritedAtMillis != tvShow.favoritedAtMillis) return false
        if (isWatching != tvShow.isWatching) return false
        if (lastPlayedAtMillis != tvShow.lastPlayedAtMillis) return false
        if (lastPlayedEpisodeId != tvShow.lastPlayedEpisodeId) return false
        return true
    }

    fun merge(tvShow: TvShow): TvShow {
        this.isFavorite = tvShow.isFavorite
        this.favoritedAtMillis = tvShow.favoritedAtMillis
        this.isWatching = tvShow.isWatching
        this.lastPlayedAtMillis = tvShow.lastPlayedAtMillis
        this.lastPlayedEpisodeId = tvShow.lastPlayedEpisodeId
        this.lastPlayedEpisode = tvShow.lastPlayedEpisode
        return this
    }


    @Ignore
    override lateinit var itemType: AppAdapter.Type


    fun copy(
        id: String = this.id,
        title: String = this.title,
        overview: String? = this.overview,
        released: String? = this.released?.format("yyyy-MM-dd"),
        runtime: Int? = this.runtime,
        trailer: String? = this.trailer,
        quality: String? = this.quality,
        rating: Double? = this.rating,
        poster: String? = this.poster,
        banner: String? = this.banner,
        imdbId: String? = this.imdbId,
        seasons: List<Season> = this.seasons,
        genres: List<Genre> = this.genres,
        directors: List<People> = this.directors,
        cast: List<People> = this.cast,
        recommendations: List<Show> = this.recommendations,
        isFavorite: Boolean = this.isFavorite
    ) = TvShow(
        id,
        title,
        overview,
        released,
        runtime,
        trailer,
        quality,
        rating,
        poster,
        banner,
        imdbId,
        providerName,
        seasons,
        genres,
        directors,
        cast,
        recommendations,
        isFavorite,
    ).apply {
        lastPlayedAtMillis = this@TvShow.lastPlayedAtMillis
        lastPlayedEpisodeId = this@TvShow.lastPlayedEpisodeId
        lastPlayedEpisode = this@TvShow.lastPlayedEpisode
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TvShow

        if (id != other.id) return false
        if (title != other.title) return false
        if (overview != other.overview) return false
        if (runtime != other.runtime) return false
        if (trailer != other.trailer) return false
        if (quality != other.quality) return false
        if (rating != other.rating) return false
        if (poster != other.poster) return false
        if (banner != other.banner) return false
        if (imdbId != other.imdbId) return false
        if (seasons != other.seasons) return false
        if (genres != other.genres) return false
        if (directors != other.directors) return false
        if (cast != other.cast) return false
        if (recommendations != other.recommendations) return false
        if (released != other.released) return false
        if (isFavorite != other.isFavorite) return false
        if (favoritedAtMillis != other.favoritedAtMillis) return false
        if (isWatching != other.isWatching) return false
        if (lastPlayedAtMillis != other.lastPlayedAtMillis) return false
        if (lastPlayedEpisodeId != other.lastPlayedEpisodeId) return false
        if (isFavorite != other.isFavorite) return false
        if (!::itemType.isInitialized || !other::itemType.isInitialized) return false
        return itemType == other.itemType
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + (overview?.hashCode() ?: 0)
        result = 31 * result + (runtime ?: 0)
        result = 31 * result + (trailer?.hashCode() ?: 0)
        result = 31 * result + (quality?.hashCode() ?: 0)
        result = 31 * result + (rating?.hashCode() ?: 0)
        result = 31 * result + (poster?.hashCode() ?: 0)
        result = 31 * result + (banner?.hashCode() ?: 0)
        result = 31 * result + (imdbId?.hashCode() ?: 0)
        result = 31 * result + seasons.hashCode()
        result = 31 * result + genres.hashCode()
        result = 31 * result + directors.hashCode()
        result = 31 * result + cast.hashCode()
        result = 31 * result + recommendations.hashCode()
        result = 31 * result + (released?.hashCode() ?: 0)
        result = 31 * result + isFavorite.hashCode()
        result = 31 * result + (favoritedAtMillis?.hashCode() ?: 0)
        result = 31 * result + isWatching.hashCode()
        result = 31 * result + (lastPlayedAtMillis?.hashCode() ?: 0)
        result = 31 * result + (lastPlayedEpisodeId?.hashCode() ?: 0)
        result = 31 * result + (if (::itemType.isInitialized) itemType.hashCode() else 0)
        return result
    }
}
