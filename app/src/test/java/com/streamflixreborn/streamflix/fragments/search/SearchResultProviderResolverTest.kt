package com.streamflixreborn.streamflix.fragments.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchResultProviderResolverTest {
    private val providers = listOf("TMDb", "SFlix", "AnimeWorld")

    @Test fun `unstamped normal result keeps current provider`() {
        assertEquals(
            "TMDb",
            resolveSearchResultProvider(
                resultProviderName = null,
                currentProvider = "TMDb",
                availableProviders = providers,
                providerName = { it },
            ),
        )
    }

    @Test fun `blank result provider keeps current provider`() {
        assertEquals(
            "TMDb",
            resolveSearchResultProvider(
                resultProviderName = "   ",
                currentProvider = "TMDb",
                availableProviders = providers,
                providerName = { it },
            ),
        )
    }

    @Test fun `unknown stale provider keeps current provider`() {
        assertEquals(
            "TMDb",
            resolveSearchResultProvider(
                resultProviderName = "RemovedProvider",
                currentProvider = "TMDb",
                availableProviders = providers,
                providerName = { it },
            ),
        )
    }

    @Test fun `registered global result switches provider`() {
        assertEquals(
            "SFlix",
            resolveSearchResultProvider(
                resultProviderName = "SFlix",
                currentProvider = "TMDb",
                availableProviders = providers,
                providerName = { it },
            ),
        )
    }

    @Test fun `registered provider is selected when current provider is absent`() {
        assertEquals(
            "AnimeWorld",
            resolveSearchResultProvider(
                resultProviderName = "AnimeWorld",
                currentProvider = null,
                availableProviders = providers,
                providerName = { it },
            ),
        )
    }
}
