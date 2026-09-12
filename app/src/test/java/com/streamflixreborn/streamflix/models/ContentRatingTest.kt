package com.streamflixreborn.streamflix.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentRatingTest {
    @Test fun `movie labels are preserved`() {
        listOf("G", "PG", "PG-13", "R").forEach { label ->
            assertEquals(label, ContentRating.create(label, scope = ContentRating.Scope.MOVIE)?.certification)
        }
    }

    @Test fun `tv labels are normalized without losing certification`() {
        mapOf("tv-y" to "TV-Y", " TV-PG " to "TV-PG", "tv_14" to "TV-14", "TV-MA" to "TV-MA")
            .forEach { (raw, expected) ->
                assertEquals(expected, ContentRating.create(raw, scope = ContentRating.Scope.SERIES)?.certification)
            }
    }

    @Test fun `missing and unrated labels are null`() {
        listOf(null, "", "  ", "N/A", "Unknown", "NR").forEach { raw ->
            assertNull(ContentRating.create(raw, scope = ContentRating.Scope.MOVIE))
        }
    }

    @Test fun `episode then season then series fallback preserves scope`() {
        val series = ContentRating.create("TV-PG", scope = ContentRating.Scope.SERIES)
        val season = ContentRating.create("TV-14", scope = ContentRating.Scope.SEASON)
        val episode = ContentRating.create("TV-MA", scope = ContentRating.Scope.EPISODE)
        assertEquals(ContentRating.Scope.EPISODE, ContentRating.mostSpecific(episode, season, series)?.scope)
        assertEquals(ContentRating.Scope.SEASON, ContentRating.mostSpecific(null, season, series)?.scope)
        assertEquals(ContentRating.Scope.SERIES, ContentRating.mostSpecific(null, null, series)?.scope)
        assertNull(ContentRating.mostSpecific(null, null, null))
    }
}
