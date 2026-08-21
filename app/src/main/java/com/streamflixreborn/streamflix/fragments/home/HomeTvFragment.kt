package com.streamflixreborn.streamflix.fragments.home

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.databinding.FragmentHomeTvBinding
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.Runnable
import com.streamflixreborn.streamflix.utils.CacheUtils
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.streamflixreborn.streamflix.utils.LoggingUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier

class HomeTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentHomeTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by lazy {
        val providerKey = UserPreferences.currentProvider?.name ?: "default"
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel() as T
            }
        }
        ViewModelProvider(this, factory).get(providerKey, HomeViewModel::class.java)
    }

    private val appAdapter = AppAdapter()

    private val swiperHandler = Handler(Looper.getMainLooper())
    private var isBackgroundPinned = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeHome()

        // Lightweight refresh when provider changes
        viewLifecycleOwner.lifecycleScope.launch {
            ProviderChangeNotifier.providerChangeFlow.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect {
                viewModel.getHome()
            }
        }

        // Initial load
        viewModel.getHome()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    HomeViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is HomeViewModel.State.SuccessLoading -> {
                        displayHome(state.categories)
                        binding.vgvHome.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is HomeViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.getHome()
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                            btnIsLoadingRetry.setOnClickListener { viewModel.getHome() }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                viewModel.getHome()
                            }
                            btnIsLoadingErrorDetails.setOnClickListener {
                                LoggingUtils.showErrorDialog(requireContext(), state.error)
                            }
                            binding.vgvHome.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }
    
    override fun onStart() {
        super.onStart()
        appAdapter.items
            .filterIsInstance<Category>()
            .firstOrNull { it.name == Category.FEATURED }
            ?.let {
                resetSwiperSchedule()
            }
    }

    override fun onStop() {
        super.onStop()
        swiperHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        appAdapter.onSaveInstanceState(binding.vgvHome)
        _binding = null
    }


    private var swiperHasLastFocus: Boolean = false
    fun updateBackground(uri: String?, swiperHasFocus: Boolean? = false) {
        if (swiperHasFocus == null && isBackgroundPinned) return
        if (swiperHasFocus == null && !swiperHasLastFocus) return

        Glide.with(requireContext())
            .load(uri)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(binding.ivHomeBackground)
        swiperHasLastFocus = swiperHasFocus ?: swiperHasLastFocus
    }

    fun pinBackground(uri: String?) {
        isBackgroundPinned = true
        Glide.with(requireContext())
            .load(uri)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(binding.ivHomeBackground)
    }

    fun releasePinnedBackground() {
        if (!isBackgroundPinned) return
        isBackgroundPinned = false
        syncFeaturedBackground()
    }

    private fun initializeHome() {
        binding.vgvHome.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(resources.getDimension(R.dimen.home_spacing).toInt() * 2)
        }

        binding.root.requestFocus()
    }

    private fun displayHome(categories: List<Category>) {
        categories
            .find { it.name == Category.FEATURED }
            ?.also {
                val index = appAdapter.items
                    .filterIsInstance<Category>()
                    .find { item -> item.name == Category.FEATURED }
                    ?.selectedIndex
                    ?: 0
                it.selectedIndex = index
                
                // Initialize background with first item from featured category immediately
                val firstItem = it.list.getOrNull(index)
                val poster = when (firstItem) {
                    is Movie -> firstItem.banner
                    is TvShow -> firstItem.banner
                    else -> null
                }
                // Force background update without waiting for focus
                if (poster != null) {
                    updateBackground(poster, null)
                }
                
                resetSwiperSchedule()
            }

        categories
            .find { it.name == Category.CONTINUE_WATCHING }
            ?.also {
                it.name = getString(R.string.home_continue_watching)
                it.list.forEach { show ->
                    when (show) {
                        is Episode -> show.itemType = AppAdapter.Type.EPISODE_CONTINUE_WATCHING_TV_ITEM
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM
                    }
                }
            }

        categories
            .find { it.name == Category.RECENTLY_WATCHED }
            ?.also {
                it.name = getString(R.string.home_recently_watched)
            }

        categories
            .find { it.name == Category.FAVORITE_MOVIES }
            ?.also { it.name = getString(R.string.home_favorite_movies) }

        categories
            .find { it.name == Category.FAVORITE_TV_SHOWS }
            ?.also { it.name = getString(R.string.home_favorite_tv_shows) }

        appAdapter.submitList(
            categories
                .filter { it.list.isNotEmpty() }
                .onEach { category ->
                    if (category.name != getString(R.string.home_continue_watching)) {
                        category.list.forEach { show ->
                            when (show) {
                                is Episode -> show.itemType = AppAdapter.Type.EPISODE_TV_ITEM
                                is Movie -> show.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                                is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                            }
                        }
                    }
                    category.itemSpacing = resources.getDimension(R.dimen.home_spacing).toInt()
                    category.itemType = when (category.name) {
                        Category.FEATURED -> AppAdapter.Type.CATEGORY_TV_SWIPER
                        else -> AppAdapter.Type.CATEGORY_TV_ITEM
                    }
                }
        )
    }

    fun resetSwiperSchedule() {
        swiperHandler.removeCallbacksAndMessages(null)
        swiperHandler.postDelayed(object : Runnable {
            override fun run() {
                if (isBackgroundPinned) {
                    swiperHandler.postDelayed(this, 8_000)
                    return
                }

                val position = appAdapter.items
                    .filterIsInstance<Category>()
                    .find { it.name == Category.FEATURED }
                    ?.let { category ->
                        category.selectedIndex = (category.selectedIndex + 1) % category.list.size
                        
                        // Update background when swiper rotates automatically
                        val currentItem = category.list.getOrNull(category.selectedIndex)
                        val poster = when (currentItem) {
                            is Movie -> currentItem.banner
                            is TvShow -> currentItem.banner
                            else -> null
                        }
                        // Update background if it's not null
                        if (poster != null) {
                            updateBackground(poster, null)
                        }

                        appAdapter.items.indexOf(category)
                    }
                    ?.takeIf { it != -1 }

                if (position == null) {
                    swiperHandler.removeCallbacksAndMessages(null)
                    return
                }

                appAdapter.notifyItemChanged(position)
                swiperHandler.postDelayed(this, 8_000)
            }
        }, 8_000)
    }

    private fun syncFeaturedBackground() {
        val featured = appAdapter.items
            .filterIsInstance<Category>()
            .find { it.name == Category.FEATURED }
            ?: return

        val currentItem = featured.list.getOrNull(featured.selectedIndex)
        val poster = when (currentItem) {
            is Movie -> currentItem.banner
            is TvShow -> currentItem.banner
            else -> null
        }

        if (poster != null) {
            updateBackground(poster, null)
        }
    }
}
