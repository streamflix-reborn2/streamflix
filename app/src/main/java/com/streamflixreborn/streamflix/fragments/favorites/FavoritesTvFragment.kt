package com.streamflixreborn.streamflix.fragments.favorites

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentFavoritesTvBinding
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.ui.ShowOptionsTvDialog
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class FavoritesTvFragment : Fragment() {

    private var _binding: FragmentFavoritesTvBinding? = null
    private val binding get() = _binding!!
    private val appAdapter = AppAdapter()
    private var rearrangeMode = false
    private val selectedItems = mutableSetOf<String>()
    private var moveSelectionMode = false
    private var selectLongPressHandled = false
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var pendingLongPress: Runnable? = null
    private var gridColumnCount = 4
    private val rearrangeBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setRearrangeMode(false)
        }
    }
    private val providerName get() = UserPreferences.currentProvider?.name.orEmpty()
    private val viewModel by viewModelsFactory {
        FavoritesViewModel(AppDatabase.getInstance(requireContext()), providerName)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentFavoritesTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        gridColumnCount = maxOf(4, resources.configuration.screenWidthDp / 180)
        binding.rvFavorites.apply {
            layoutManager = object : GridLayoutManager(requireContext(), gridColumnCount) {
                init {
                    spanSizeLookup = object : SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int =
                            if (appAdapter.items.getOrNull(position) is FavoriteSectionHeader) {
                                gridColumnCount
                            } else {
                                1
                            }
                    }
                }

                override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
                    if (direction == View.FOCUS_UP) {
                        val itemView = binding.rvFavorites.findContainingItemView(focused)
                        val position = itemView?.let(binding.rvFavorites::getChildAdapterPosition)
                            ?: RecyclerView.NO_POSITION
                        if (isInFirstGridRow(position)) {
                            return binding.btnFavoritesReorderMode
                        }
                    }
                    return super.onInterceptFocusSearch(focused, direction)
                }
            }
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(SpacingItemDecoration(10.dp(requireContext())))
        }
        binding.btnFavoritesReorder.setOnClickListener { showSortDialog() }
        binding.btnFavoritesReorderMode.setOnClickListener { setRearrangeMode(!rearrangeMode) }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, rearrangeBackCallback)
        setRearrangeMode(false)
        binding.root.requestFocus()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sections.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect(::display)
        }
    }

    private fun setRearrangeMode(enabled: Boolean) {
        cancelPendingLongPress()
        rearrangeMode = enabled
        rearrangeBackCallback.isEnabled = enabled
        moveSelectionMode = false
        if (!enabled) selectedItems.clear()
        binding.btnFavoritesReorderMode.apply {
            isSelected = enabled
            text = getString(if (enabled) R.string.favorites_rearrange_off else R.string.favorites_rearrange_on)
        }
        configureAdapterInteractions()
        appAdapter.notifyDataSetChanged()
        if (!enabled) {
            binding.btnFavoritesReorderMode.post {
                _binding?.btnFavoritesReorderMode?.requestFocus()
            }
        }
    }

    private fun configureAdapterInteractions() {
        appAdapter.isItemSelectedListener = { itemKey(it) in selectedItems }
        if (rearrangeMode) {
            // TV remotes often synthesize a click after OK. Consume it here and handle selection
            // exclusively through key events so a single press can never toggle twice.
            appAdapter.onMovieClickListener = { }
            appAdapter.onTvShowClickListener = { }
            appAdapter.onMovieLongClickListener = { item ->
                enterMoveSelection(FavoritesViewModel.Section.MOVIES, item.id)
            }
            appAdapter.onTvShowLongClickListener = { item ->
                enterMoveSelection(FavoritesViewModel.Section.TV_SHOWS, item.id)
            }
            appAdapter.onMovieKeyListener = { item, event ->
                handleRearrangeKey(FavoritesViewModel.Section.MOVIES, item.id, event)
            }
            appAdapter.onTvShowKeyListener = { item, event ->
                handleRearrangeKey(FavoritesViewModel.Section.TV_SHOWS, item.id, event)
            }
        } else {
            appAdapter.onMovieClickListener = null
            appAdapter.onTvShowClickListener = null
            appAdapter.onMovieLongClickListener = null
            appAdapter.onTvShowLongClickListener = null
            appAdapter.onMovieKeyListener = null
            appAdapter.onTvShowKeyListener = null
        }
    }

    private fun handleRearrangeKey(
        section: FavoritesViewModel.Section,
        id: String,
        event: KeyEvent,
    ): Boolean {
        if (!rearrangeMode) return false
        val key = selectionKey(section, id)
        val isSelectKey = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (isSelectKey) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (!moveSelectionMode) scheduleLongPress(section, id)
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
                if (!moveSelectionMode && !selectLongPressHandled) {
                    cancelPendingLongPress()
                    enterMoveSelection(section, id)
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) {
                cancelPendingLongPress()
                when {
                    selectLongPressHandled -> {
                        selectLongPressHandled = false
                    }
                    moveSelectionMode -> {
                        moveSelectionMode = false
                        binding.btnFavoritesReorderMode.setText(R.string.favorites_rearrange_off)
                    }
                    else -> {
                        toggleSelection(section, id)
                    }
                }
            }
            return true
        }
        val delta = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_UP -> -gridColumnCount
            KeyEvent.KEYCODE_DPAD_DOWN -> gridColumnCount
            else -> return false
        }
        if (!moveSelectionMode || key !in selectedItems) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            moveSelectedItems(section, delta)
        }
        return true
    }

    private fun scheduleLongPress(section: FavoritesViewModel.Section, id: String) {
        cancelPendingLongPress()
        pendingLongPress = Runnable {
            pendingLongPress = null
            if (rearrangeMode && !moveSelectionMode) enterMoveSelection(section, id)
        }.also {
            longPressHandler.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong())
        }
    }

    private fun cancelPendingLongPress() {
        pendingLongPress?.let(longPressHandler::removeCallbacks)
        pendingLongPress = null
    }

    private fun enterMoveSelection(section: FavoritesViewModel.Section, id: String) {
        val key = selectionKey(section, id)
        if (key !in selectedItems) toggleSelection(section, id)
        selectLongPressHandled = true
        moveSelectionMode = true
        binding.btnFavoritesReorderMode.setText(R.string.favorites_moving_selected)
    }

    private fun isInFirstGridRow(position: Int): Boolean {
        val firstItemPosition = appAdapter.items.indexOfFirst { it !is FavoriteSectionHeader }
        return firstItemPosition >= 0 &&
            position in firstItemPosition until (firstItemPosition + gridColumnCount)
    }

    private fun toggleSelection(section: FavoritesViewModel.Section, id: String) {
        val key = selectionKey(section, id)
        if (!selectedItems.add(key)) selectedItems.remove(key)
        val position = appAdapter.items.indexOfFirst { itemKey(it) == key }
        if (position >= 0) appAdapter.notifyItemSelectionChanged(position)
    }

    private fun moveSelectedItems(section: FavoritesViewModel.Section, delta: Int) {
        val sectionItems = itemsInSection(section)
        val moving = sectionItems.filter { itemKey(it) in selectedItems }
        if (moving.isEmpty()) return
        val anchor = sectionItems.indexOfFirst { itemKey(it) in selectedItems }
        val remaining = sectionItems.filterNot { it in moving }.toMutableList()
        val insertAt = (anchor + delta).coerceIn(0, remaining.size)
        remaining.addAll(insertAt, moving)
        replaceSectionItems(section, remaining)
        persistSectionOrder(section)
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
        val nextHeaderOffset = appAdapter.items.drop(headerIndex + 1)
            .indexOfFirst { it is FavoriteSectionHeader }
        val endIndex = if (nextHeaderOffset >= 0) headerIndex + 1 + nextHeaderOffset else appAdapter.items.size
        val reordered = appAdapter.items.toMutableList().apply {
            subList(headerIndex + 1, endIndex).clear()
            addAll(headerIndex + 1, newSectionItems)
        }
        appAdapter.replaceItemOrder(reordered)
    }

    private fun persistSectionOrder(section: FavoritesViewModel.Section) {
        val ids = itemsInSection(section).mapNotNull {
            when (it) {
                is Movie -> it.id
                is TvShow -> it.id
                else -> null
            }
        }
        viewModel.setManualItemOrder(section, ids)
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
                        is Movie -> AppAdapter.Type.MOVIE_GRID_TV_ITEM
                        is TvShow -> AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
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
        cancelPendingLongPress()
        setRearrangeMode(false)
        appAdapter.onSaveInstanceState(binding.rvFavorites)
        _binding = null
        super.onDestroyView()
    }
}
