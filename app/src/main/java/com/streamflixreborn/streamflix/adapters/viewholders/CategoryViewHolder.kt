package com.streamflixreborn.streamflix.adapters.viewholders

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.children
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import androidx.viewpager2.widget.ViewPager2
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.databinding.ContentCategorySwiperMobileBinding
import com.streamflixreborn.streamflix.databinding.ContentCategorySwiperTvBinding
import com.streamflixreborn.streamflix.databinding.ItemCategoryMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemCategoryTvBinding
import com.streamflixreborn.streamflix.fragments.home.HomeTvFragment
import com.streamflixreborn.streamflix.fragments.home.HomeTvFragmentDirections
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format
import com.streamflixreborn.streamflix.utils.getCurrentFragment
import com.streamflixreborn.streamflix.utils.toActivity
import java.util.Locale

class CategoryViewHolder(
    private val _binding: ViewBinding,
) : RecyclerView.ViewHolder(_binding.root) {

    private val context = itemView.context
    private lateinit var category: Category
    private var mobileSwiperHandler: Handler? = null
    private var mobileSwiperCallback: ViewPager2.OnPageChangeCallback? = null

    val childRecyclerView: RecyclerView?
        get() = when (_binding) {
            is ItemCategoryMobileBinding -> _binding.rvCategory
            is ItemCategoryTvBinding -> _binding.hgvCategory
            is ContentCategorySwiperMobileBinding -> _binding.vpCategorySwiper.javaClass
                .getDeclaredField("mRecyclerView")
                .let {
                    it.isAccessible = true
                    it.get(_binding.vpCategorySwiper) as RecyclerView
                }
            else -> null
        }

    fun bind(
        category: Category,
        onMovieClick: ((Movie) -> Unit)? = null,
        onTvShowClick: ((TvShow) -> Unit)? = null,
        onMovieLongClick: ((Movie) -> Unit)? = null,
        onTvShowLongClick: ((TvShow) -> Unit)? = null,
    ) {
        this.category = category

        when (_binding) {
            is ItemCategoryMobileBinding -> displayMobileItem(
                _binding,
                onMovieClick,
                onTvShowClick,
                onMovieLongClick,
                onTvShowLongClick,
            )
            is ItemCategoryTvBinding -> displayTvItem(
                _binding,
                onMovieClick,
                onTvShowClick,
                onMovieLongClick,
                onTvShowLongClick,
            )
            is ContentCategorySwiperMobileBinding -> displayMobileSwiper(
                _binding,
                onMovieClick,
                onTvShowClick,
                onMovieLongClick,
                onTvShowLongClick,
            )
            is ContentCategorySwiperTvBinding -> displayTvSwiper(_binding)
        }
    }

    private fun displayMobileItem(
        binding: ItemCategoryMobileBinding,
        onMovieClick: ((Movie) -> Unit)?,
        onTvShowClick: ((TvShow) -> Unit)?,
        onMovieLongClick: ((Movie) -> Unit)?,
        onTvShowLongClick: ((TvShow) -> Unit)?,
    ) {
        binding.tvCategoryTitle.text = category.name

        binding.rvCategory.apply {
            val categoryAdapter = (adapter as? AppAdapter) ?: AppAdapter().also {
                adapter = it
                itemAnimator = null
            }
            categoryAdapter.apply {
                onMovieClickListener = onMovieClick
                onTvShowClickListener = onTvShowClick
                onMovieLongClickListener = onMovieLongClick
                onTvShowLongClickListener = onTvShowLongClick
                submitList(category.list)
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(category.itemSpacing))
            }
        }
    }

    private fun displayTvItem(
        binding: ItemCategoryTvBinding,
        onMovieClick: ((Movie) -> Unit)?,
        onTvShowClick: ((TvShow) -> Unit)?,
        onMovieLongClick: ((Movie) -> Unit)?,
        onTvShowLongClick: ((TvShow) -> Unit)?,
    ) {
        binding.tvCategoryTitle.text = category.name

        binding.hgvCategory.apply {
            val categoryAdapter = (adapter as? AppAdapter) ?: AppAdapter().also { newAdapter ->
                adapter = newAdapter
                setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
                setItemViewCacheSize(6)
                itemAnimator = null
                isFocusable = true
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }

            categoryAdapter.apply {
                onMovieClickListener = onMovieClick
                onTvShowClickListener = onTvShowClick
                onMovieLongClickListener = onMovieLongClick
                onTvShowLongClickListener = onTvShowLongClick
                submitList(category.list)
            }
            setItemSpacing(category.itemSpacing)
        }
    }

    private fun displayMobileSwiper(
        binding: ContentCategorySwiperMobileBinding,
        onMovieClick: ((Movie) -> Unit)?,
        onTvShowClick: ((TvShow) -> Unit)?,
        onMovieLongClick: ((Movie) -> Unit)?,
        onTvShowLongClick: ((TvShow) -> Unit)?,
    ) {
        binding.tvCategoryTitle.text = category.name
        if (category.list.isEmpty()) return

        mobileSwiperHandler?.removeCallbacksAndMessages(null)
        mobileSwiperCallback?.let(binding.vpCategorySwiper::unregisterOnPageChangeCallback)

        val handler = Handler(Looper.getMainLooper()).also { mobileSwiperHandler = it }
        val items = buildList {
            category.list.lastOrNull()?.let(::add)
            addAll(category.list)
            category.list.firstOrNull()?.let(::add)
        }

        binding.vpCategorySwiper.apply {
            val pagerAdapter = (adapter as? AppAdapter) ?: AppAdapter().also { adapter = it }
            pagerAdapter.apply {
                onMovieClickListener = onMovieClick
                onTvShowClickListener = onTvShowClick
                onMovieLongClickListener = onMovieLongClick
                onTvShowLongClickListener = onTvShowLongClick
                submitList(items)
            }
        }

        ensureDots(binding.llDotsIndicator, category.list.size)

        val callback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val indicatorPosition = when (position) {
                    0 -> category.list.lastIndex
                    items.lastIndex -> 0
                    else -> position - 1
                }
                updateDots(binding.llDotsIndicator, indicatorPosition)
                handler.removeCallbacksAndMessages(null)
                if (category.list.size > 1) {
                    handler.postDelayed(8_000L) {
                        binding.vpCategorySwiper.currentItem += 1
                    }
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state != ViewPager2.SCROLL_STATE_IDLE) return
                when (binding.vpCategorySwiper.currentItem) {
                    0 -> binding.vpCategorySwiper.setCurrentItem(items.lastIndex - 1, false)
                    items.lastIndex -> binding.vpCategorySwiper.setCurrentItem(1, false)
                }
            }
        }.also { mobileSwiperCallback = it }
        binding.vpCategorySwiper.registerOnPageChangeCallback(callback)

        if (category.list.size > 1) {
            handler.postDelayed(8_000L) {
                binding.vpCategorySwiper.currentItem += 1
            }
        }
    }

    private fun displayTvSwiper(binding: ContentCategorySwiperTvBinding) {
        binding.tvCategoryTitle.text = category.name
        val selected = category.list.getOrNull(category.selectedIndex) as? Show ?: return

        fun checkProviderAndRun(show: Show, action: () -> Unit) {
            val providerName = when (show) {
                is Movie -> show.providerName
                is TvShow -> show.providerName
            }
            if (!providerName.isNullOrBlank() && providerName != UserPreferences.currentProvider?.name) {
                Provider.providers.keys.find { it.name == providerName }?.let {
                    UserPreferences.currentProvider = it
                }
            }
            action()
        }

        // HomeTvFragment owns background scheduling. Do not start a Glide decode or reset the
        // auto-rotation timer every time RecyclerView merely rebinds this holder.
        binding.tvSwiperTitle.text = when (selected) {
            is Movie -> selected.title
            is TvShow -> selected.title
        }

        binding.tvSwiperTvShowLastEpisode.text = when (selected) {
            is TvShow -> selected.seasons.lastOrNull()?.let { season ->
                season.episodes.lastOrNull()?.let { episode ->
                    if (season.number != 0) {
                        context.getString(
                            R.string.tv_show_item_season_number_episode_number,
                            season.number,
                            episode.number,
                        )
                    } else {
                        context.getString(R.string.tv_show_item_episode_number, episode.number)
                    }
                }
            } ?: context.getString(R.string.tv_show_item_type)
            else -> context.getString(R.string.movie_item_type)
        }

        binding.tvSwiperQuality.apply {
            text = when (selected) {
                is Movie -> selected.quality
                is TvShow -> selected.quality
            }
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        binding.tvSwiperReleased.apply {
            text = when (selected) {
                is Movie -> selected.released?.format("yyyy")
                is TvShow -> selected.released?.format("yyyy")
            }
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        binding.tvSwiperRating.apply {
            text = when (selected) {
                is Movie -> selected.rating?.let { String.format(Locale.ROOT, "%.1f", it) }
                is TvShow -> selected.rating?.let { String.format(Locale.ROOT, "%.1f", it) }
            }
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        binding.ivSwiperRatingIcon.visibility = binding.tvSwiperRating.visibility

        binding.tvSwiperOverview.text = when (selected) {
            is Movie -> selected.overview
            is TvShow -> selected.overview
        }

        binding.btnSwiperWatchNow.apply {
            setOnClickListener {
                checkProviderAndRun(selected) {
                    findNavController().navigate(
                        when (selected) {
                            is Movie -> HomeTvFragmentDirections.actionHomeToMovie(selected.id)
                            is TvShow -> HomeTvFragmentDirections.actionHomeToTvShow(
                                id = selected.id,
                                poster = selected.poster,
                                banner = selected.banner,
                            )
                        },
                    )
                }
            }

            setOnKeyListener { _, _, event ->
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    val fragment = context.toActivity()?.getCurrentFragment() as? HomeTvFragment
                    fragment?.resetSwiperSchedule()
                    category.selectedIndex = (category.selectedIndex + 1) % category.list.size
                    when (val next = category.list[category.selectedIndex]) {
                        is Movie -> fragment?.updateBackground(next.banner, true)
                        is TvShow -> fragment?.updateBackground(next.banner, true)
                    }

                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        bindingAdapter?.notifyItemChanged(position)
                    }
                    return@setOnKeyListener true
                }
                false
            }
        }

        binding.pbSwiperProgress.apply {
            val watchHistory = (selected as? Movie)?.watchHistory
            progress = if (watchHistory != null && watchHistory.durationMillis > 0L) {
                (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble())
                    .toInt()
                    .coerceIn(0, 100)
            } else {
                0
            }
            visibility = if (watchHistory != null) View.VISIBLE else View.GONE
        }

        ensureDots(binding.llDotsIndicator, category.list.size)
        updateDots(binding.llDotsIndicator, category.selectedIndex)
    }

    private fun ensureDots(container: LinearLayout, count: Int) {
        if (container.childCount == count) return
        container.removeAllViews()
        repeat(count) {
            container.addView(
                View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(15, 15).apply {
                        setMargins(10, 0, 10, 0)
                    }
                    setBackgroundResource(R.drawable.bg_dot_indicator)
                },
            )
        }
    }

    private fun updateDots(container: LinearLayout, selectedIndex: Int) {
        container.children.forEachIndexed { index, view ->
            view.isSelected = index == selectedIndex
        }
    }
}
