package com.streamflixreborn.streamflix.fragments.search

internal fun <T> resolveSearchResultProvider(
    resultProviderName: String?,
    currentProvider: T?,
    availableProviders: Iterable<T>,
    providerName: (T) -> String,
): T? {
    val requestedName = resultProviderName?.takeIf { it.isNotBlank() }
        ?: return currentProvider

    if (currentProvider != null && requestedName == providerName(currentProvider)) {
        return currentProvider
    }

    return availableProviders.firstOrNull { providerName(it) == requestedName }
        ?: currentProvider
}
