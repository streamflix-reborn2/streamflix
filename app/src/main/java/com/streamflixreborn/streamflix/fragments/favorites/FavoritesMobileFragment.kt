package com.streamflixreborn.streamflix.fragments.favorites

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentFavoritesMobileBinding
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class FavoritesMobileFragment : Fragment() {

    private var _binding: FragmentFavoritesMobileBinding? = null
    private val binding get() = _binding!!
    private val appAdapter = AppAdapter()
    private var rearrangeMode = false
    private val selectedItems = mutableSetOf<String>()
    private val providerName get() = UserPreferences.currentProvider?.name.orEmpty()
    private val viewModel by viewModelsFactory {
        FavoritesViewModel(AppDatabase.getInstance(requireContext()), providerName)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentFavoritesMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val columnCount = maxOf(3, resources.configuration.screenWidthDp / 120)
        val gridLayoutManager = GridLayoutManager(requireContext(), columnCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (appAdapter.items.getOrNull(position) is FavoriteSectionHeader) columnCount else 1
            }
        }
        binding.rvFavorites.apply {
            layoutManager = gridLayoutManager
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(SpacingItemDecoration(8.dp(requireContext())))
        }
        createDragHelper().attachToRecyclerView(binding.rvFavorites)
        binding.btnFavoritesReorder.setOnClickListener { showSortDialog() }
        binding.btnFavoritesReorderMode.setOnClickListener { setRearrangeMode(!rearrangeMode) }
        setRearrangeMode(false)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sections.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect(::display)
        }
    }

    private fun createDragHelper() = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
        0,
    ) {
        private var draggedSection: FavoritesViewModel.Section? = null

        override fun isLongPressDragEnabled(): Boolean = rearrangeMode

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            val position = viewHolder.bindingAdapterPosition
            return if (!rearrangeMode || appAdapter.items.getOrNull(position) is FavoriteSectionHeader) {
                makeMovementFlags(0, 0)
            } else {
                super.getMovementFlags(recyclerView, viewHolder)
            }
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            val fromSection = sectionAt(from) ?: return false
            if (sectionAt(to) != fromSection || appAdapter.items.getOrNull(to) is FavoriteSectionHeader) {
                return false
            }
            draggedSection = fromSection
            return reorderForDrag(fromSection, from, to)
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            draggedSection?.let(::persistSectionOrder)
            draggedSection = null
        }
    })

    private fun setRearrangeMode(enabled: Boolean) {
        rearrangeMode = enabled
        if (!enabled) selectedItems.clear()
        binding.btnFavoritesReorderMode.apply {
            isSelected = enabled
            alpha = if (enabled) 1f else 0.65f
            contentDescription = getString(
                if (enabled) R.string.favorites_rearrange_off else R.string.favorites_rearrange_on
            )
        }
        configureAdapterInteractions()
        appAdapter.notifyDataSetChanged()
    }

    private fun configureAdapterInteractions() {
        appAdapter.isItemSelectedListener = { itemKey(it) in selectedItems }
        if (rearrangeMode) {
            appAdapter.onMovieClickListener = { toggleSelection(FavoritesViewModel.Section.MOVIES, it.id) }
            appAdapter.onTvShowClickListener = { toggleSelection(FavoritesViewModel.Section.TV_SHOWS, it.id) }
            appAdapter.onMovieLongClickListener = { }
            appAdapter.onTvShowLongClickListener = { }
        } else {
            appAdapter.onMovieClickListener = null
            appAdapter.onTvShowClickListener = null
            appAdapter.onMovieLongClickListener = null
            appAdapter.onTvShowLongClickListener = null
        }
    }

    private fun toggleSelection(section: FavoritesViewModel.Section, id: String) {
        val key = selectionKey(section, id)
        if (!selectedItems.add(key)) selectedItems.remove(key)
        appAdapter.items.indexOfFirst { itemKey(it) == key }
            .takeIf { it >= 0 }
            ?.let(appAdapter::notifyItemSelectionChanged)
    }

    private fun reorderForDrag(
        section: FavoritesViewModel.Section,
        fromPosition: Int,
        toPosition: Int,
    ): Boolean {
        val source = appAdapter.items.getOrNull(fromPosition) ?: return false
        val sourceKey = itemKey(source) ?: return false
        val movingKeys = selectedItems
            .filterTo(mutableSetOf()) { it.startsWith("${section.key}:") }
            .takeIf { sourceKey in it && it.isNotEmpty() }
            ?: mutableSetOf(sourceKey)
        val sectionItems = itemsInSection(section)
        val moving = sectionItems.filter { itemKey(it) in movingKeys }
        val target = appAdapter.items.getOrNull(toPosition) ?: return false
        if (moving.isEmpty()) return false
        if (target in moving) return true
        val remaining = sectionItems.filterNot { it in moving }.toMutableList()
        val targetIndex = remaining.indexOf(target).takeIf { it >= 0 } ?: return false
        val insertAt = (targetIndex + if (toPosition > fromPosition) 1 else 0).coerceIn(0, remaining.size)
        remaining.addAll(insertAt, moving)
        replaceSectionItems(section, remaining)
        return true
    }

    private fun sectionAt(position: Int): FavoritesViewModel.Section? {
        if (position !in appAdapter.items.indices) return null
        return (position downTo 0)
            .asSequence()
            .mapNotNull { appAdapter.items[it] as? FavoriteSectionHeader }
            .firstOrNull()
            ?.section
    }

    private fun persistSectionOrder(section: FavoritesViewModel.Section) {
        val ids = itemsInSection(section)
            .mapNotNull {
                when (it) {
                    is Movie -> it.id
                    is TvShow -> it.id
                    else -> null
                }
            }
        viewModel.setManualItemOrder(section, ids)
    }

    private fun itemsInSection(section: FavoritesViewModel.Section): List<AppAdapter.Item> = appAdapter.items
        .dropWhile { it !is FavoriteSectionHeader || it.section != section }
        .drop(1)
        .takeWhile { it !is FavoriteSectionHeader }

    private fun replaceSectionItems(section: FavoritesViewModel.Section, newSectionItems: List<AppAdapter.Item>) {
        val headerIndex = appAdapter.items.indexOfFirst {
            it is FavoriteSectionHeader && it.section == section
        }
        if (headerIndex < 0) return
        val nextHeaderOffset = appAdapter.items
            .drop(headerIndex + 1)
            .indexOfFirst { it is FavoriteSectionHeader }
        val endIndex = if (nextHeaderOffset >= 0) {
            headerIndex + 1 + nextHeaderOffset
        } else {
            appAdapter.items.size
        }
        val reordered = appAdapter.items.toMutableList().apply {
            subList(headerIndex + 1, endIndex).clear()
            addAll(headerIndex + 1, newSectionItems)
        }
        appAdapter.replaceItemOrder(reordered)
    }

    private fun itemKey(item: AppAdapter.Item): String? = when (item) {
        is Movie -> selectionKey(FavoritesViewModel.Section.MOVIES, item.id)
        is TvShow -> selectionKey(FavoritesViewModel.Section.TV_SHOWS, item.id)
        else -> null
    }

    private fun selectionKey(section: FavoritesViewModel.Section, id: String) = "${section.key}:$id"

    private fun display(sections: List<FavoritesViewModel.FavoriteSection>) {
        val gridItems = sections.flatMap { favoriteSection ->
            if (favoriteSection.items.isEmpty()) return@flatMap emptyList()
            val title = when (favoriteSection.section) {
                FavoritesViewModel.Section.MOVIES -> getString(R.string.home_favorite_movies)
                FavoritesViewModel.Section.TV_SHOWS -> getString(R.string.home_favorite_tv_shows)
            }
            listOf<AppAdapter.Item>(FavoriteSectionHeader(title, favoriteSection.section)) +
                favoriteSection.items.onEach { item ->
                    item.itemType = when (item) {
                        is Movie -> AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
                        is TvShow -> AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
                        else -> item.itemType
                    }
                }
        }
        binding.tvFavoritesEmpty.isVisible = gridItems.isEmpty()
        binding.rvFavorites.isVisible = gridItems.isNotEmpty()
        binding.btnFavoritesReorder.isEnabled = gridItems.isNotEmpty()
        appAdapter.submitList(gridItems)
    }

    private fun showSortDialog() {
        val modes = FavoritesViewModel.SortMode.entries
        val labels = arrayOf(
            getString(R.string.favorites_sort_manual),
            getString(R.string.favorites_sort_recent),
            getString(R.string.favorites_sort_title_ascending),
            getString(R.string.favorites_sort_title_descending),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.favorites_sort_title)
            .setSingleChoiceItems(labels, modes.indexOf(viewModel.currentSortMode())) { dialog, which ->
                if (modes[which] != FavoritesViewModel.SortMode.MANUAL) setRearrangeMode(false)
                viewModel.setSortMode(modes[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.option_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        setRearrangeMode(false)
        appAdapter.onSaveInstanceState(binding.rvFavorites)
        _binding = null
        super.onDestroyView()
    }
}
