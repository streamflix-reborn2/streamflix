package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.ContentRating
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

object TmdbUtils {
    private const val MIN_ACCEPTABLE_SCORE = 60
    private const val MAX_LOCALIZED_DETAIL_CANDIDATES = 5
    private const val UNKNOWN_AGE_RATING = Int.MIN_VALUE
    private val movieAgeCache = ConcurrentHashMap<String, Int>()
    private val tvAgeCache = ConcurrentHashMap<String, Int>()
    private val movieContentRatingCache = ConcurrentHashMap<String, ContentRating>()
    private val tvContentRatingCache = ConcurrentHashMap<String, ContentRating>()
    private val missingContentRatings = ConcurrentHashMap.newKeySet<String>()

    suspend fun getMovie(title: String, year: Int? = null, language: String? = null): Movie? {
        if (!UserPreferences.enableTmdb) return null
        return try {
            val effectiveYear = year ?: extractYear(title)
            val movie = findBestMovieMatch(title, effectiveYear, language) ?: return null

            val details = TMDb3.Movies.details(
                movieId = movie.id,
                appendToResponse = listOf(
                    TMDb3.Params.AppendToResponse.Movie.CREDITS,
                    TMDb3.Params.AppendToResponse.Movie.RECOMMENDATIONS,
                    TMDb3.Params.AppendToResponse.Movie.VIDEOS,
                    TMDb3.Params.AppendToResponse.Movie.EXTERNAL_IDS,
                ),
                language = language
            )

            Movie(
                id = details.id.toString(),
                title = details.title,
                overview = details.overview,
                released = details.releaseDate,
                runtime = details.runtime,
                trailer = details.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = details.voteAverage.toDouble(),
                poster = details.posterPath?.original,
                banner = details.backdropPath?.original,
                imdbId = details.externalIds?.imdbId,
                genres = details.genres.map { Genre(it.id.toString(), it.name) },
                cast = details.credits?.cast?.map { People(it.id.toString(), it.name, it.profilePath?.w500) } ?: listOf(),
            )
        } catch (_: Exception) { null }
    }

    suspend fun getTvShow(title: String, year: Int? = null, language: String? = null): TvShow? {
        if (!UserPreferences.enableTmdb) return null
        return try {
            val effectiveYear = year ?: extractYear(title)
            val tv = findBestTvMatch(title, effectiveYear, language) ?: return null

            val details = TMDb3.TvSeries.details(
                seriesId = tv.id,
                appendToResponse = listOf(
                    TMDb3.Params.AppendToResponse.Tv.CREDITS,
                    TMDb3.Params.AppendToResponse.Tv.RECOMMENDATIONS,
                    TMDb3.Params.AppendToResponse.Tv.VIDEOS,
                    TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS,
                ),
                language = language
            )

            TvShow(
                id = details.id.toString(),
                title = details.name,
                overview = details.overview,
                released = details.firstAirDate,
                trailer = details.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = details.voteAverage.toDouble(),
                poster = details.posterPath?.original,
                banner = details.backdropPath?.original,
                imdbId = details.externalIds?.imdbId,
                seasons = details.seasons.map {
                    Season(
                        id = "${details.id}-${it.seasonNumber}",
                        number = it.seasonNumber,
                        title = it.name,
                        poster = it.posterPath?.w500,
                    )
                },
                genres = details.genres.map { Genre(it.id.toString(), it.name) },
                cast = details.credits?.cast?.map { People(it.id.toString(), it.name, it.profilePath?.w500) } ?: listOf(),
            )
        } catch (_: Exception) { null }
    }

    suspend fun getEpisodesBySeason(tvShowId: String, seasonNumber: Int, language: String? = null): List<Episode> {
        if (!UserPreferences.enableTmdb) return listOf()
        return try {
            TMDb3.TvSeasons.details(
                seriesId = tvShowId.toInt(),
                seasonNumber = seasonNumber,
                language = language
            ).episodes?.map {
                Episode(
                    id = it.id.toString(),
                    number = it.episodeNumber,
                    title = it.name ?: "",
                    released = it.airDate,
                    poster = it.stillPath?.w500,
                    overview = it.overview,
                )
            } ?: listOf()
        } catch (_: Exception) { listOf() }
    }

    suspend fun getMovieAgeRating(title: String, year: Int? = null, language: String? = null): Int? {
        if (!UserPreferences.enableTmdb) return null

        val effectiveYear = year ?: extractYear(title)
        val cacheKey = buildLookupCacheKey("movie", title, effectiveYear, language)
        movieAgeCache[cacheKey]?.let(::decodeAgeRatingCacheValue)?.let { return it }
        if (movieAgeCache.containsKey(cacheKey)) return null

        val ageRating = runCatching {
            val movie = findBestMovieMatch(title, effectiveYear, language) ?: return@runCatching null
            getMovieAgeRatingById(movie.id, language)
        }.getOrNull()

        movieAgeCache[cacheKey] = encodeAgeRatingCacheValue(ageRating)
        return ageRating
    }

    suspend fun getMovieContentRating(title: String, year: Int? = null, language: String? = null): ContentRating? {
        if (!UserPreferences.enableTmdb) return null
        val effectiveYear = year ?: extractYear(title)
        val key = buildLookupCacheKey("movie-rating", title, effectiveYear, language)
        movieContentRatingCache[key]?.let { return it }
        if (key in missingContentRatings) return null
        val value = runCatching {
            findBestMovieMatch(title, effectiveYear, language)?.let { getMovieContentRatingById(it.id, language) }
        }.getOrNull()
        if (value == null) missingContentRatings += key else movieContentRatingCache[key] = value
        return value
    }

    suspend fun getTvShowAgeRating(title: String, year: Int? = null, language: String? = null): Int? {
        if (!UserPreferences.enableTmdb) return null

        val effectiveYear = year ?: extractYear(title)
        val cacheKey = buildLookupCacheKey("tv", title, effectiveYear, language)
        tvAgeCache[cacheKey]?.let(::decodeAgeRatingCacheValue)?.let { return it }
        if (tvAgeCache.containsKey(cacheKey)) return null

        val ageRating = runCatching {
            val tvShow = findBestTvMatch(title, effectiveYear, language) ?: return@runCatching null
            getTvShowAgeRatingById(tvShow.id, language)
        }.getOrNull()

        tvAgeCache[cacheKey] = encodeAgeRatingCacheValue(ageRating)
        return ageRating
    }

    suspend fun getTvShowContentRating(title: String, year: Int? = null, language: String? = null): ContentRating? {
        if (!UserPreferences.enableTmdb) return null
        val effectiveYear = year ?: extractYear(title)
        val key = buildLookupCacheKey("tv-rating", title, effectiveYear, language)
        tvContentRatingCache[key]?.let { return it }
        if (key in missingContentRatings) return null
        val value = runCatching {
            findBestTvMatch(title, effectiveYear, language)?.let { getTvShowContentRatingById(it.id, language) }
        }.getOrNull()
        if (value == null) missingContentRatings += key else tvContentRatingCache[key] = value
        return value
    }

    suspend fun getMovieById(id: Int, language: String? = null): Movie? {
        if (!UserPreferences.enableTmdb) return null
        return try {
            val details = TMDb3.Movies.details(
                movieId = id,
                appendToResponse = listOf(
                    TMDb3.Params.AppendToResponse.Movie.CREDITS,
                    TMDb3.Params.AppendToResponse.Movie.RECOMMENDATIONS,
                    TMDb3.Params.AppendToResponse.Movie.VIDEOS,
                    TMDb3.Params.AppendToResponse.Movie.EXTERNAL_IDS,
                ),
                language = language
            )

            Movie(
                id = details.id.toString(),
                title = details.title,
                overview = details.overview,
                released = details.releaseDate,
                runtime = details.runtime,
                trailer = details.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = details.voteAverage.toDouble(),
                poster = details.posterPath?.original,
                banner = details.backdropPath?.original,
                imdbId = details.externalIds?.imdbId,
                genres = details.genres.map { Genre(it.id.toString(), it.name) },
                cast = details.credits?.cast?.map { People(it.id.toString(), it.name, it.profilePath?.w500) } ?: listOf(),
            )
        } catch (_: Exception) { null }
    }

    suspend fun getMovieAgeRatingById(id: Int, language: String? = null): Int? {
        if (!UserPreferences.enableTmdb) return null

        val cacheKey = "movie-id|$id|${language.orEmpty()}"
        movieAgeCache[cacheKey]?.let(::decodeAgeRatingCacheValue)?.let { return it }
        if (movieAgeCache.containsKey(cacheKey)) return null

        val ageRating = runCatching {
            val details = TMDb3.Movies.details(
                movieId = id,
                appendToResponse = listOf(TMDb3.Params.AppendToResponse.Movie.RELEASES_DATES),
                language = language
            )
            extractMovieAgeRating(details, language)
        }.getOrNull()

        movieAgeCache[cacheKey] = encodeAgeRatingCacheValue(ageRating)
        return ageRating
    }

    suspend fun getMovieContentRatingById(id: Int, language: String? = null): ContentRating? {
        if (!UserPreferences.enableTmdb) return null
        val key = "movie:$id:US"
        movieContentRatingCache[key]?.let { return it }
        if (key in missingContentRatings) return null
        val value = runCatching {
            val details = TMDb3.Movies.details(
                movieId = id,
                appendToResponse = listOf(TMDb3.Params.AppendToResponse.Movie.RELEASES_DATES),
                language = language,
            )
            extractMovieContentRating(details, language)
        }.getOrNull()
        if (value == null) missingContentRatings += key else movieContentRatingCache[key] = value
        return value
    }

    suspend fun getTvShowById(id: Int, language: String? = null): TvShow? {
        if (!UserPreferences.enableTmdb) return null
        return try {
            val details = TMDb3.TvSeries.details(
                seriesId = id,
                appendToResponse = listOf(
                    TMDb3.Params.AppendToResponse.Tv.CREDITS,
                    TMDb3.Params.AppendToResponse.Tv.RECOMMENDATIONS,
                    TMDb3.Params.AppendToResponse.Tv.VIDEOS,
                    TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS,
                ),
                language = language
            )

            TvShow(
                id = details.id.toString(),
                title = details.name,
                overview = details.overview,
                released = details.firstAirDate,
                trailer = details.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = details.voteAverage.toDouble(),
                poster = details.posterPath?.original,
                banner = details.backdropPath?.original,
                imdbId = details.externalIds?.imdbId,
                seasons = details.seasons.map {
                    Season(
                        id = "${details.id}-${it.seasonNumber}",
                        number = it.seasonNumber,
                        title = it.name,
                        poster = it.posterPath?.w500,
                    )
                },
                genres = details.genres.map { Genre(it.id.toString(), it.name) },
                cast = details.credits?.cast?.map { People(it.id.toString(), it.name, it.profilePath?.w500) } ?: listOf(),
            )
        } catch (_: Exception) { null }
    }

    suspend fun getTvShowAgeRatingById(id: Int, language: String? = null): Int? {
        if (!UserPreferences.enableTmdb) return null

        val cacheKey = "tv-id|$id|${language.orEmpty()}"
        tvAgeCache[cacheKey]?.let(::decodeAgeRatingCacheValue)?.let { return it }
        if (tvAgeCache.containsKey(cacheKey)) return null

        val ageRating = runCatching {
            val details = TMDb3.TvSeries.details(
                seriesId = id,
                appendToResponse = listOf(TMDb3.Params.AppendToResponse.Tv.CONTENT_RATING),
                language = language
            )
            extractTvShowAgeRating(details, language)
        }.getOrNull()

        tvAgeCache[cacheKey] = encodeAgeRatingCacheValue(ageRating)
        return ageRating
    }

    suspend fun getTvShowContentRatingById(id: Int, language: String? = null): ContentRating? {
        if (!UserPreferences.enableTmdb) return null
        val key = "tv:$id:US"
        tvContentRatingCache[key]?.let { return it }
        if (key in missingContentRatings) return null
        val value = runCatching {
            val details = TMDb3.TvSeries.details(
                seriesId = id,
                appendToResponse = listOf(TMDb3.Params.AppendToResponse.Tv.CONTENT_RATING),
                language = language,
            )
            extractTvShowContentRating(details, language)
        }.getOrNull()
        if (value == null) missingContentRatings += key else tvContentRatingCache[key] = value
        return value
    }

    private suspend fun findBestMovieMatch(
        rawTitle: String,
        year: Int?,
        language: String?,
    ): TMDb3.Movie? {
        val results = searchMovieCandidates(rawTitle, language)
        val scoredResults = results.map { movie ->
            movie to scoreCandidate(
                candidateTitles = listOf(movie.title, movie.originalTitle),
                queryTitle = rawTitle,
                year = year,
                candidateYear = extractYear(movie.releaseDate),
            )
        }

        val bestSearchMatch = scoredResults.maxByOrNull { it.second }
        if (bestSearchMatch != null && bestSearchMatch.second >= MIN_ACCEPTABLE_SCORE) {
            return bestSearchMatch.first
        }

        val localizedFallback = findBestLocalizedMovieCandidate(
            rawTitle = rawTitle,
            year = year,
            language = language,
            candidates = scoredResults
                .sortedByDescending { it.second }
                .map { it.first }
                .take(MAX_LOCALIZED_DETAIL_CANDIDATES),
        )
        if (localizedFallback != null) return localizedFallback

        return bestSearchMatch
            ?.takeIf { it.second > 0 }
            ?.first
    }

    private suspend fun findBestTvMatch(
        rawTitle: String,
        year: Int?,
        language: String?,
    ): TMDb3.Tv? {
        val results = searchTvCandidates(rawTitle, language)
        val scoredResults = results.map { tv ->
            tv to scoreCandidate(
                candidateTitles = listOf(tv.name, tv.originalName),
                queryTitle = rawTitle,
                year = year,
                candidateYear = extractYear(tv.firstAirDate),
            )
        }

        val bestSearchMatch = scoredResults.maxByOrNull { it.second }
        if (bestSearchMatch != null && bestSearchMatch.second >= MIN_ACCEPTABLE_SCORE) {
            return bestSearchMatch.first
        }

        val localizedFallback = findBestLocalizedTvCandidate(
            rawTitle = rawTitle,
            year = year,
            language = language,
            candidates = scoredResults
                .sortedByDescending { it.second }
                .map { it.first }
                .take(MAX_LOCALIZED_DETAIL_CANDIDATES),
        )
        if (localizedFallback != null) return localizedFallback

        return bestSearchMatch
            ?.takeIf { it.second > 0 }
            ?.first
    }

    private suspend fun searchMovieCandidates(rawTitle: String, language: String?): List<TMDb3.Movie> {
        val variants = buildTitleVariants(rawTitle)
        val languages = listOfNotNull(language).plus(null).distinct()

        return languages
            .flatMap { searchLanguage ->
                variants.flatMap { query ->
                    TMDb3.Search.multi(query, language = searchLanguage).results.filterIsInstance<TMDb3.Movie>()
                }
            }
            .distinctBy { it.id }
    }

    private suspend fun searchTvCandidates(rawTitle: String, language: String?): List<TMDb3.Tv> {
        val variants = buildTitleVariants(rawTitle)
        val languages = listOfNotNull(language).plus(null).distinct()

        return languages
            .flatMap { searchLanguage ->
                variants.flatMap { query ->
                    TMDb3.Search.multi(query, language = searchLanguage).results.filterIsInstance<TMDb3.Tv>()
                }
            }
            .distinctBy { it.id }
    }

    private suspend fun findBestLocalizedMovieCandidate(
        rawTitle: String,
        year: Int?,
        language: String?,
        candidates: List<TMDb3.Movie>,
    ): TMDb3.Movie? {
        if (language.isNullOrBlank() || candidates.isEmpty()) return null

        return candidates
            .mapNotNull { movie ->
                val details = runCatching {
                    TMDb3.Movies.details(
                        movieId = movie.id,
                        appendToResponse = listOf(TMDb3.Params.AppendToResponse.Movie.ALTERNATIVE_TITLES),
                        language = language,
                    )
                }.getOrNull() ?: return@mapNotNull null

                val score = scoreCandidate(
                    candidateTitles = listOf(details.title, details.originalTitle),
                    queryTitle = rawTitle,
                    year = year,
                    candidateYear = extractYear(details.releaseDate),
                )
                movie to score
            }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= MIN_ACCEPTABLE_SCORE }
            ?.first
    }

    private suspend fun findBestLocalizedTvCandidate(
        rawTitle: String,
        year: Int?,
        language: String?,
        candidates: List<TMDb3.Tv>,
    ): TMDb3.Tv? {
        if (language.isNullOrBlank() || candidates.isEmpty()) return null

        return candidates
            .mapNotNull { tv ->
                val details = runCatching {
                    TMDb3.TvSeries.details(
                        seriesId = tv.id,
                        appendToResponse = listOf(TMDb3.Params.AppendToResponse.Tv.ALTERNATIVE_TITLES),
                        language = language,
                    )
                }.getOrNull() ?: return@mapNotNull null

                val score = scoreCandidate(
                    candidateTitles = listOf(details.name, details.originalName),
                    queryTitle = rawTitle,
                    year = year,
                    candidateYear = extractYear(details.firstAirDate),
                )
                tv to score
            }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= MIN_ACCEPTABLE_SCORE }
            ?.first
    }

    private fun scoreCandidate(
        candidateTitles: List<String?>,
        queryTitle: String,
        year: Int?,
        candidateYear: Int?,
    ): Int {
        val queryVariants = buildTitleVariants(queryTitle).map(::normalizeTitle)
        val candidateVariants = candidateTitles
            .filterNotNull()
            .map(::normalizeTitle)
            .distinct()

        val titleScore = candidateVariants.maxOfOrNull { candidate ->
            queryVariants.maxOfOrNull { query ->
                when {
                    candidate == query -> 120
                    candidate.replace(" ", "") == query.replace(" ", "") -> 110
                    candidate.startsWith(query) || query.startsWith(candidate) -> 85
                    candidate.contains(query) || query.contains(candidate) -> 70
                    overlapScore(candidate, query) >= 0.8 -> 55
                    else -> 0
                }
            } ?: 0
        } ?: 0

        val yearScore = when {
            year == null || candidateYear == null -> 0
            year == candidateYear -> 20
            max(year, candidateYear) - minOf(year, candidateYear) == 1 -> 5
            else -> -25
        }

        return titleScore + yearScore
    }

    private fun buildTitleVariants(title: String): List<String> {
        val trimmed = title.trim()
        val withoutTrailingYear = trimmed
            .replace(Regex("\\s*\\((19|20)\\d{2}\\)\\s*$"), "")
            .replace(Regex("\\s*\\[(19|20)\\d{2}]\\s*$"), "")
            .trim()
        val withoutDecorators = withoutTrailingYear
            .replace(Regex("\\s*[\\-–:]\\s*(sub|dub|ita|ger|de|eng|en)\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()

        return listOf(trimmed, withoutTrailingYear, withoutDecorators)
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun extractYear(value: String?): Int? {
        return value
            ?.let { Regex("(19|20)\\d{2}").find(it)?.value }
            ?.toIntOrNull()
    }

    private fun normalizeTitle(value: String): String {
        val ascii = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

        return ascii
            .lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun overlapScore(left: String, right: String): Double {
        val leftWords = left.split(" ").filter { it.isNotBlank() }.toSet()
        val rightWords = right.split(" ").filter { it.isNotBlank() }.toSet()
        if (leftWords.isEmpty() || rightWords.isEmpty()) return 0.0

        val overlap = leftWords.intersect(rightWords).size.toDouble()
        return overlap / max(leftWords.size, rightWords.size).toDouble()
    }

    private fun buildLookupCacheKey(type: String, title: String, year: Int?, language: String?): String {
        return "$type|${normalizeTitle(title)}|${year ?: -1}|${language.orEmpty()}"
    }

    private fun encodeAgeRatingCacheValue(ageRating: Int?): Int = ageRating ?: UNKNOWN_AGE_RATING

    private fun decodeAgeRatingCacheValue(value: Int): Int? {
        return value.takeUnless { it == UNKNOWN_AGE_RATING }
    }

    private fun extractMovieAgeRating(details: TMDb3.Movie.Detail, language: String?): Int? {
        val releaseDates = details.releaseDates?.results.orEmpty()
        val preferredCountries = buildPreferredCertificationCountries(language)

        preferredCountries.forEach { countryCode ->
            releaseDates
                .firstOrNull { it.iso3166.equals(countryCode, ignoreCase = true) }
                ?.releaseDates
                ?.mapNotNull { parseAgeRating(it.certification) }
                ?.maxOrNull()
                ?.let { return it }
        }

        return releaseDates
            .asSequence()
            .flatMap { it.releaseDates.asSequence() }
            .mapNotNull { parseAgeRating(it.certification) }
            .maxOrNull()
    }

    private fun extractMovieContentRating(details: TMDb3.Movie.Detail, language: String?): ContentRating? {
        val releases = details.releaseDates?.results.orEmpty()
        for (country in buildPreferredCertificationCountries(language)) {
            val candidates = releases.firstOrNull { it.iso3166.equals(country, true) }?.releaseDates.orEmpty()
            // Theatrical is the canonical classification; limited/premiere then home releases are fallbacks.
            candidates.sortedBy { release ->
                when (release.type) {
                    TMDb3.Movie.ReleaseType.THEATRICAL -> 0
                    TMDb3.Movie.ReleaseType.THEATRICAL_LIMITED -> 1
                    TMDb3.Movie.ReleaseType.PREMIERE -> 2
                    TMDb3.Movie.ReleaseType.DIGITAL -> 3
                    TMDb3.Movie.ReleaseType.PHYSICAL -> 4
                    else -> 5
                }
            }.firstNotNullOfOrNull { release ->
                val label = normalizeCertification(release.certification)
                ContentRating.create(label, parseAgeRating(label), country, "TMDb", ContentRating.Scope.MOVIE)
            }?.let { return it }
        }
        return null
    }

    private fun extractTvShowAgeRating(details: TMDb3.Tv.Detail, language: String?): Int? {
        val contentRatings = details.contentRatings?.results.orEmpty()
        val preferredCountries = buildPreferredCertificationCountries(language)

        preferredCountries.forEach { countryCode ->
            contentRatings
                .filter { it.iso3166.equals(countryCode, ignoreCase = true) }
                .mapNotNull { parseAgeRating(it.rating) }
                .maxOrNull()
                ?.let { return it }
        }

        return contentRatings
            .mapNotNull { parseAgeRating(it.rating) }
            .maxOrNull()
    }

    private fun extractTvShowContentRating(details: TMDb3.Tv.Detail, language: String?): ContentRating? {
        val ratings = details.contentRatings?.results.orEmpty()
        for (country in buildPreferredCertificationCountries(language)) {
            ratings.firstNotNullOfOrNull { rating ->
                if (!rating.iso3166.equals(country, true)) return@firstNotNullOfOrNull null
                val label = normalizeCertification(rating.rating)
                ContentRating.create(label, parseAgeRating(label), country, "TMDb", ContentRating.Scope.SERIES)
            }?.let { return it }
        }
        return null
    }

    private fun buildPreferredCertificationCountries(language: String?): List<String> {
        val primary = when (language?.substringBefore('-')?.lowercase()) {
            "de" -> "DE"
            "es" -> "ES"
            "fr" -> "FR"
            "it" -> "IT"
            "pt" -> "PT"
            "en" -> "US"
            else -> language
                ?.substringAfterLast('-')
                ?.takeIf { it.length == 2 }
                ?.uppercase()
        }

        return listOfNotNull("US", primary, "GB", "DE", "FR", "ES", "IT", "PT")
            .distinct()
    }

    internal fun normalizeCertification(raw: String?): String? = raw
        ?.trim()
        ?.replace('_', '-')
        ?.replace(Regex("\\s+"), " ")
        ?.uppercase()
        ?.takeIf { it.isNotBlank() && it !in setOf("N/A", "NR", "UR", "UNRATED", "NOT RATED", "UNKNOWN") }

    private fun parseAgeRating(raw: String?): Int? {
        val normalized = raw
            ?.trim()
            ?.uppercase()
            ?.replace('_', '-')
            ?.replace(Regex("\\s+"), " ")
            ?: return null

        if (normalized.isBlank()) return null

        listOf(18, 17, 16, 15, 14, 13, 12, 10, 7, 6, 0).forEach { age ->
            if (Regex("(^|\\D)$age(\\D|$)").containsMatchIn(normalized)) {
                return age
            }
        }

        return when (normalized) {
            "TV-MA", "MA", "MATURE", "X", "RX", "C" -> 18
            "NC-17", "R" -> 17
            "TV-14" -> 14
            "PG-13" -> 13
            "TV-PG", "PG" -> 10
            "TV-Y7", "TV-Y7-FV" -> 7
            "TV-G", "TV-Y", "G", "U", "A", "AL", "ATP", "TP", "L", "T", "ALL" -> 0
            "NR", "UR", "UNRATED", "NOT RATED", "N/A" -> null
            else -> when {
                normalized.contains("TOUS PUBLICS") -> 0
                normalized.contains("APTA") -> 0
                normalized.contains("TODOS") -> 0
                normalized.contains("MA") -> 17
                normalized.contains("PG") -> 10
                else -> null
            }
        }
    }
}
