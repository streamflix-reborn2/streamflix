package com.streamflixreborn.streamflix.fragments.search

internal fun <T> resolveSearchResultProvider(
    resultProviderName: String?,
    currentProvider: T?,
    availableProviders: Iterable<T>,
    providerName: (T) -> String,
): T? {
    val requestedName = resultProviderName
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return currentProvider
    val normalizedRequestedName = requestedName.lowercase()

    if (
        currentProvider != null &&
        providerName(currentProvider).trim().lowercase() == normalizedRequestedName
    ) {
        return currentProvider
    }

    val matches = availableProviders
        .filter { providerName(it).trim().lowercase() == normalizedRequestedName }
        .take(2)

    return if (matches.size == 1) matches.single() else currentProvider
}
