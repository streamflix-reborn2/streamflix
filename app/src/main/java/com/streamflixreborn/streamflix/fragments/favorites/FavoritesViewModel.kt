package com.streamflixreborn.streamflix.fragments.favorites

import androidx.lifecycle.ViewModel
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

class FavoritesViewModel(
    database: AppDatabase,
    private val providerName: String,
) : ViewModel() {

    enum class Section(val key: String) {
        MOVIES("movies"),
        TV_SHOWS("tv_shows");

        companion object {
            fun fromKey(key: String): Section? = entries.firstOrNull { it.key == key }
        }
    }

    enum class SortMode(val key: String) {
        MANUAL("manual"),
        RECENTLY_ADDED("recently_added"),
        TITLE_ASCENDING("title_ascending"),
        TITLE_DESCENDING("title_descending");

        companion object {
            fun fromKey(key: String): SortMode = entries.firstOrNull { it.key == key } ?: MANUAL
        }
    }

    data class FavoriteSection(
        val section: Section,
        val items: List<AppAdapter.Item>,
    )

    private val order = MutableStateFlow(readOrder())
    private val sortMode = MutableStateFlow(SortMode.fromKey(UserPreferences.getFavoriteSortMode(providerName)))
    private val orderRevision = MutableStateFlow(0)
    @Volatile
    private var currentSections: List<FavoriteSection> = emptyList()

    val sections: Flow<List<FavoriteSection>> = combine(
        database.movieDao().getFavorites(),
        database.tvShowDao().getFavorites(),
        order,
        combine(sortMode, orderRevision) { mode, _ -> mode },
    ) { movies, tvShows, sectionOrder, mode ->
        sectionOrder.map { section ->
            when (section) {
                Section.MOVIES -> FavoriteSection(section, sortItems(section, movies, mode))
                Section.TV_SHOWS -> FavoriteSection(section, sortItems(section, tvShows, mode))
            }
        }.also { currentSections = it }
    }.flowOn(Dispatchers.IO)

    fun reverseCategoryOrder() {
        setCategoryOrder(order.value.reversed())
    }

    fun setCategoryOrder(newOrder: List<Section>) {
        val normalized = (newOrder + Section.entries).distinct()
        order.value = normalized
        UserPreferences.setFavoriteCategoryOrder(providerName, normalized.map { it.key })
    }

    fun setSortMode(mode: SortMode) {
        sortMode.value = mode
        UserPreferences.setFavoriteSortMode(providerName, mode.key)
    }

    fun moveItem(section: Section, itemId: String, delta: Int) {
        val ids = currentSections.firstOrNull { it.section == section }
            ?.items
            ?.mapNotNull(::itemId)
            ?.toMutableList()
            ?: return
        val from = ids.indexOf(itemId)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        if (from == to) return
        val moved = ids.removeAt(from)
        ids.add(to, moved)
        UserPreferences.setFavoriteItemOrder(providerName, section.key, ids)
        UserPreferences.setFavoriteSortMode(providerName, SortMode.MANUAL.key)
        sortMode.value = SortMode.MANUAL
        orderRevision.value += 1
    }

    fun setManualItemOrder(section: Section, itemIds: List<String>) {
        UserPreferences.setFavoriteItemOrder(providerName, section.key, itemIds)
        UserPreferences.setFavoriteSortMode(providerName, SortMode.MANUAL.key)
        sortMode.value = SortMode.MANUAL
        orderRevision.value += 1
    }

    fun currentSortMode(): SortMode = sortMode.value

    private fun sortItems(
        section: Section,
        items: List<AppAdapter.Item>,
        mode: SortMode,
    ): List<AppAdapter.Item> = when (mode) {
        SortMode.MANUAL -> {
            val savedOrder = UserPreferences.getFavoriteItemOrder(providerName, section.key)
            val currentIds = items.mapNotNull(::itemId)
            val currentIdSet = currentIds.toSet()
            val normalizedOrder = (
                savedOrder.filter { it in currentIdSet } +
                    currentIds.filterNot { it in savedOrder }
                ).distinct()

            if (normalizedOrder != savedOrder) {
                UserPreferences.setFavoriteItemOrder(providerName, section.key, normalizedOrder)
            }

            val positions = normalizedOrder.withIndex().associate { it.value to it.index }
            items.sortedBy { positions[itemId(it)] ?: Int.MAX_VALUE }
        }
        SortMode.RECENTLY_ADDED -> items.sortedByDescending(::favoriteTime)
        SortMode.TITLE_ASCENDING -> items.sortedBy(::titleLowercase)
        SortMode.TITLE_DESCENDING -> items.sortedByDescending(::titleLowercase)
    }

    private fun itemId(item: AppAdapter.Item): String? = when (item) {
        is Movie -> item.id
        is TvShow -> item.id
        else -> null
    }

    private fun favoriteTime(item: AppAdapter.Item): Long = when (item) {
        is Movie -> item.favoritedAtMillis ?: 0L
        is TvShow -> item.favoritedAtMillis ?: 0L
        else -> 0L
    }

    private fun titleLowercase(item: AppAdapter.Item): String = when (item) {
        is Movie -> item.title.lowercase()
        is TvShow -> item.title.lowercase()
        else -> ""
    }

    private fun readOrder(): List<Section> = UserPreferences
        .getFavoriteCategoryOrder(providerName)
        .mapNotNull(Section::fromKey)
        .let { (it + Section.entries).distinct() }
}

