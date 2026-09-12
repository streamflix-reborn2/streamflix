package com.streamflixreborn.streamflix.models

/** A certification returned by metadata, preserving its official display label. */
data class ContentRating(
    val certification: String,
    val minimumAge: Int? = null,
    val country: String? = null,
    val source: String? = null,
    val scope: Scope,
) {
    enum class Scope { MOVIE, EPISODE, SEASON, SERIES }

    companion object {
        fun mostSpecific(
            episode: ContentRating?, season: ContentRating?, series: ContentRating?,
        ): ContentRating? = episode ?: season ?: series

        fun create(
            certification: String?,
            minimumAge: Int? = null,
            country: String? = null,
            source: String? = null,
            scope: Scope,
        ): ContentRating? {
            val normalized = certification
                ?.trim()
                ?.replace('_', '-')
                ?.replace(Regex("\\s+"), " ")
                ?.uppercase()
                ?.takeIf { it.isNotBlank() && it !in setOf("N/A", "NR", "UR", "UNRATED", "NOT RATED", "UNKNOWN") }
                ?: return null
            return ContentRating(normalized, minimumAge, country?.uppercase(), source, scope)
        }
    }
}
