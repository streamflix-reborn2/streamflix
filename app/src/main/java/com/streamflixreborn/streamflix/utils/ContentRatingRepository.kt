package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.models.ContentRating
import com.streamflixreborn.streamflix.models.Video
import java.util.concurrent.ConcurrentHashMap

/** Single entry point for certification metadata used by details, parental controls and playback. */
object ContentRatingRepository {
    private val cache = ConcurrentHashMap<String, ContentRating?>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    suspend fun movie(
        tmdbId: Int?, title: String, year: Int? = null, language: String? = null,
    ): ContentRating? = cached("movie:${tmdbId ?: title.lowercase()}:${year ?: ""}:US") {
        tmdbId?.let { TmdbUtils.getMovieContentRatingById(it, language) }
            ?: TmdbUtils.getMovieContentRating(title, year, language)
    }

    suspend fun series(
        tmdbId: Int?, title: String, year: Int? = null, language: String? = null,
    ): ContentRating? = cached("tv:${tmdbId ?: title.lowercase()}:${year ?: ""}:US") {
        tmdbId?.let { TmdbUtils.getTvShowContentRatingById(it, language) }
            ?: TmdbUtils.getTvShowContentRating(title, year, language)
    }

    /**
     * Resolves the most specific supplied rating. TMDb currently supplies series ratings only;
     * callers with a reliable provider episode/season certification may pass it without
     * misrepresenting a series fallback as episode-specific.
     */
    suspend fun episode(
        seriesTmdbId: Int?, seriesTitle: String, seasonNumber: Int, episodeNumber: Int,
        episodeCertification: String? = null, seasonCertification: String? = null,
        language: String? = null,
    ): ContentRating? {
        val key = "episode:${seriesTmdbId ?: seriesTitle.lowercase()}:S$seasonNumber:E$episodeNumber:US"
        return cached(key) {
            ContentRating.mostSpecific(
                ContentRating.create(episodeCertification, source = "provider", scope = ContentRating.Scope.EPISODE),
                ContentRating.create(seasonCertification, source = "provider", scope = ContentRating.Scope.SEASON),
                series(seriesTmdbId, seriesTitle, language = language),
            )
        }
    }

    suspend fun playing(
        type: Video.Type, language: String? = null, idsAreTmdb: Boolean = false,
    ): ContentRating? = when (type) {
        is Video.Type.Movie -> movie(type.id.toIntOrNull().takeIf { idsAreTmdb }, type.title, type.releaseDate.take(4).toIntOrNull(), language)
        is Video.Type.Episode -> episode(
            type.tvShow.id.toIntOrNull().takeIf { idsAreTmdb }, type.tvShow.title, type.season.number, type.number,
            type.contentRating, type.season.contentRating, language,
        )
    }

    private suspend fun cached(key: String, loader: suspend () -> ContentRating?): ContentRating? {
        cache[key]?.let { return it }
        if (key in missing) return null
        val value = runCatching { loader() }.getOrNull()
        if (value == null) missing += key else cache[key] = value
        return value
    }
}
