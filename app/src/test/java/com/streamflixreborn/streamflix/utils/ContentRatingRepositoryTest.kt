package com.streamflixreborn.streamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentRatingRepositoryTest {
    @Test fun `cache preference keys are canonical and distinct`() {
        assertEquals("EN-US", ContentRatingRepository.preferenceKey(" en_US "))
        assertEquals("FR-FR", ContentRatingRepository.preferenceKey("fr-fr"))
        assertEquals("DEFAULT", ContentRatingRepository.preferenceKey(null))
    }
}
