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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentHomeTvBinding
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.LoggingUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.launch
import kotlin.math.min

class HomeTvFragment : Fragment() {

    companion object {
        private const val MAX_HOME_ROWS = 14
        private const val MAX_ITEMS_PER_ROW = 24
        private const val MAX_FEATURED_ITEMS = 8
        private const val SWIPER_INTERVAL_MS = 8_000L
        private const val BACKGROUND_DEBOUNCE_MS = 85L
        private const val MAX_BACKGROUND_WIDTH = 1920
        private const val MAX_BACKGROUND_HEIGHT = 1080
    }

    private var hasAutoCleared409 = false

    private var _binding: FragmentHomeTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by lazy {
        val providerKey = UserPreferences.currentProvider?.name ?: "default"
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(AppDatabase.getInstance(requireContext())) as T
            }
        }
        ViewModelProvider(this, factory)[providerKey, HomeViewModel::class.java]
    }

    private val appAdapter = AppAdapter()
    private val swiperHandler = Handler(Looper.getMainLooper())
    private val backgroundHandler = Handler(Looper.getMainLooper())

    private var isBackgroundPinned = false
    private var lastBackgroundUri: String? = null
    private var pendingBackgroundUri: String? = null

    private val backgroundRunnable = Runnable {
        val uri = pendingBackgroundUri ?: return@Runnable
        pendingBackgroundUri = null
        loadBackgroundNow(uri)
    }

    private val swiperRunnable = object : Runnable {
        override fun run() {
            if (_binding == null || !isAdded) return
            if (isBackgroundPinned) {
                swiperHandler.postDelayed(this, SWIPER_INTERVAL_MS)
                return
            }

            val category = appAdapter.items
                .filterIsInstance<Category>()
                .firstOrNull { it.name == Category.FEATURED }
                ?: return

            if (category.list.size <= 1) return

            category.selectedIndex = (category.selectedIndex + 1) % category.list.size
            when (val currentItem = category.list.getOrNull(category.selectedIndex)) {
                is Movie -> queueBackground(currentItem.banner)
                is TvShow -> queueBackground(currentItem.banner)
            }

            val position = appAdapter.items.indexOf(category)
            if (position >= 0) appAdapter.notifyItemChanged(position)
            swiperHandler.postDelayed(this, SWIPER_INTERVAL_MS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeHome()

        // HomeViewModel owns both the initial load and provider-change refresh. Keeping those
        // responsibilities in one layer prevents two or three identical provider requests from racing.
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
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.clear_cache_done_409),
                                Toast.LENGTH_SHORT,
                            ).show()
                            viewModel.getHome()
                            return@collect
                        }

                        Toast.makeText(
                            requireContext(),
                            state.error.message.orEmpty(),
                            Toast.LENGTH_SHORT,
                        ).show()
                        binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                            btnIsLoadingRetry.setOnClickListener { viewModel.getHome() }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.clear_cache_done),
                                    Toast.LENGTH_SHORT,
                                ).show()
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
        if (featuredCategory()?.list?.size.orZero() > 1) resetSwiperSchedule()
    }

    override fun onStop() {
        swiperHandler.removeCallbacks(swiperRunnable)
        backgroundHandler.removeCallbacks(backgroundRunnable)
        super.onStop()
    }

    override fun onDestroyView() {
        swiperHandler.removeCallbacksAndMessages(null)
        backgroundHandler.removeCallbacksAndMessages(null)
        pendingBackgroundUri = null
        appAdapter.onSaveInstanceState(binding.vgvHome)
        Glide.with(this).clear(binding.ivHomeBackground)
        _binding = null
        super.onDestroyView()
    }

    /** Called by featured/swiper holders. Rapid DPAD movement is intentionally debounced. */
    fun updateBackground(uri: String?, swiperHasFocus: Boolean? = false) {
        if (uri.isNullOrBlank()) return
        if (isBackgroundPinned && swiperHasFocus != true) return
        queueBackground(uri)
    }

    fun pinBackground(uri: String?) {
        if (uri.isNullOrBlank()) return
        isBackgroundPinned = true
        queueBackground(uri)
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
            itemAnimator = null
            setItemViewCacheSize(4)
            recycledViewPool.setMaxRecycledViews(AppAdapter.Type.CATEGORY_TV_ITEM.ordinal, 5)
            recycledViewPool.setMaxRecycledViews(AppAdapter.Type.CATEGORY_TV_SWIPER.ordinal, 1)
            setItemSpacing(resources.getDimension(R.dimen.home_spacing).toInt() * 2)
        }
        binding.root.requestFocus()
    }

    private fun displayHome(categories: List<Category>) {
        val previousFeaturedIndex = featuredCategory()?.selectedIndex ?: 0
        val spacing = resources.getDimension(R.dimen.home_spacing).toInt()

        val visibleCategories = categories
            .asSequence()
            .filter { it.list.isNotEmpty() }
            .take(MAX_HOME_ROWS)
            .map { source ->
                val itemLimit = if (source.name == Category.FEATURED) {
                    MAX_FEATURED_ITEMS
                } else {
                    MAX_ITEMS_PER_ROW
                }
                source.copy(list = source.list.take(itemLimit)).also { category ->
                    category.selectedIndex = if (source.name == Category.FEATURED) {
                        previousFeaturedIndex.coerceIn(0, (category.list.size - 1).coerceAtLeast(0))
                    } else {
                        source.selectedIndex.coerceIn(0, (category.list.size - 1).coerceAtLeast(0))
                    }
                    category.itemSpacing = spacing
                }
            }
            .toMutableList()

        visibleCategories.firstOrNull { it.name == Category.CONTINUE_WATCHING }?.also { category ->
            category.name = getString(R.string.home_continue_watching)
            category.list.forEach { show ->
                when (show) {
                    is Episode -> show.itemType = AppAdapter.Type.EPISODE_CONTINUE_WATCHING_TV_ITEM
                    is Movie -> show.itemType = AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM
                }
            }
        }

        visibleCategories.firstOrNull { it.name == Category.RECENTLY_WATCHED }
            ?.also { it.name = getString(R.string.home_recently_watched) }
        visibleCategories.firstOrNull { it.name == Category.FAVORITE_MOVIES }
            ?.also { it.name = getString(R.string.home_favorite_movies) }
        visibleCategories.firstOrNull { it.name == Category.FAVORITE_TV_SHOWS }
            ?.also { it.name = getString(R.string.home_favorite_tv_shows) }

        visibleCategories.forEach { category ->
            if (category.name != getString(R.string.home_continue_watching)) {
                category.list.forEach { show ->
                    when (show) {
                        is Episode -> show.itemType = AppAdapter.Type.EPISODE_TV_ITEM
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                        is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                    }
                }
            }
            category.itemType = if (category.name == Category.FEATURED) {
                AppAdapter.Type.CATEGORY_TV_SWIPER
            } else {
                AppAdapter.Type.CATEGORY_TV_ITEM
            }
        }

        appAdapter.submitList(visibleCategories)

        featuredCategory()?.let { featured ->
            when (val firstItem = featured.list.getOrNull(featured.selectedIndex)) {
                is Movie -> queueBackground(firstItem.banner, immediate = true)
                is TvShow -> queueBackground(firstItem.banner, immediate = true)
            }
            if (featured.list.size > 1) resetSwiperSchedule() else stopSwiperSchedule()
        } ?: stopSwiperSchedule()
    }

    fun resetSwiperSchedule() {
        swiperHandler.removeCallbacks(swiperRunnable)
        if (featuredCategory()?.list?.size.orZero() > 1) {
            swiperHandler.postDelayed(swiperRunnable, SWIPER_INTERVAL_MS)
        }
    }

    private fun stopSwiperSchedule() {
        swiperHandler.removeCallbacks(swiperRunnable)
    }

    private fun featuredCategory(): Category? = appAdapter.items
        .filterIsInstance<Category>()
        .firstOrNull { it.name == Category.FEATURED }

    private fun syncFeaturedBackground() {
        val featured = featuredCategory() ?: return
        when (val currentItem = featured.list.getOrNull(featured.selectedIndex)) {
            is Movie -> queueBackground(currentItem.banner)
            is TvShow -> queueBackground(currentItem.banner)
        }
    }

    private fun queueBackground(uri: String?, immediate: Boolean = false) {
        if (uri.isNullOrBlank()) return
        if (uri == lastBackgroundUri && pendingBackgroundUri == null) return

        pendingBackgroundUri = uri
        backgroundHandler.removeCallbacks(backgroundRunnable)
        if (immediate) {
            backgroundRunnable.run()
        } else {
            backgroundHandler.postDelayed(backgroundRunnable, BACKGROUND_DEBOUNCE_MS)
        }
    }

    private fun loadBackgroundNow(uri: String) {
        val currentBinding = _binding ?: return
        if (uri == lastBackgroundUri) return
        lastBackgroundUri = uri

        val metrics = resources.displayMetrics
        val targetWidth = min(metrics.widthPixels.coerceAtLeast(1), MAX_BACKGROUND_WIDTH)
        val targetHeight = min(metrics.heightPixels.coerceAtLeast(1), MAX_BACKGROUND_HEIGHT)

        Glide.with(this)
            .load(uri)
            .override(targetWidth, targetHeight)
            .centerCrop()
            .dontAnimate()
            .into(currentBinding.ivHomeBackground)
    }

    private fun Int?.orZero(): Int = this ?: 0
}
