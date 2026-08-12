package com.streamflixreborn.streamflix.adapters.viewholders

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.KeyEvent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.Toast
import android.util.Log
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.*
import com.streamflixreborn.streamflix.fragments.home.HomeTvFragment
import com.streamflixreborn.streamflix.fragments.home.HomeTvFragmentDirections
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import android.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.streamflixreborn.streamflix.fragments.movie.MovieMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.tv_show.TvShowMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.tv_show.TvShowTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.movies.MoviesMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.movies.MoviesTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.search.SearchMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.search.SearchTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.genre.GenreMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.genre.GenreTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.people.PeopleMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.people.PeopleTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.home.HomeMobileFragmentDirections
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.ui.ShowOptionsMobileDialog
import com.streamflixreborn.streamflix.ui.ShowOptionsTvDialog
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format
import com.streamflixreborn.streamflix.utils.toActivity
import com.streamflixreborn.streamflix.utils.getCurrentFragment
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.loadTvShowBanner
import com.streamflixreborn.streamflix.utils.loadTvShowPoster
import com.streamflixreborn.streamflix.utils.ArtworkRepair
import com.streamflixreborn.streamflix.providers.Provider
import java.util.Locale

class TvShowViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private val database: AppDatabase
        get() = AppDatabase.getInstance(context)
    private lateinit var tvShow: TvShow
    private var onTvShowClick: ((TvShow) -> Unit)? = null
    private var onTvShowLongClick: ((TvShow) -> Unit)? = null
    private var onTvShowKey: ((TvShow, KeyEvent) -> Boolean)? = null
    private var itemSelected: Boolean = false
    private var ribbonStateJob: Job? = null

    val childRecyclerView: RecyclerView?
        get() = when (_binding) {
            is ContentTvShowSeasonsMobileBinding -> _binding.rvTvShowSeasons
            is ContentTvShowSeasonsTvBinding -> _binding.hgvTvShowSeasons
            is ContentTvShowCastMobileBinding -> _binding.rvTvShowCast
            is ContentTvShowCastTvBinding -> _binding.hgvTvShowCast
            is ContentTvShowRecommendationsMobileBinding -> _binding.rvTvShowRecommendations
            is ContentTvShowRecommendationsTvBinding -> _binding.hgvTvShowRecommendations
            else -> null
        }

    fun bind(
        tvShow: TvShow,
        onTvShowClick: ((TvShow) -> Unit)? = null,
        onTvShowLongClick: ((TvShow) -> Unit)? = null,
        onTvShowKey: ((TvShow, KeyEvent) -> Boolean)? = null,
        itemSelected: Boolean = false,
    ) {
        this.tvShow = tvShow
        this.onTvShowClick = onTvShowClick
        this.onTvShowLongClick = onTvShowLongClick
        this.onTvShowKey = onTvShowKey
        this.itemSelected = itemSelected

        when (_binding) {
            is ItemTvShowMobileBinding -> displayMobileItem(_binding)
            is ItemTvShowTvBinding -> displayTvItem(_binding)
            is ItemTvShowGridMobileBinding -> displayGridMobileItem(_binding)
            is ItemTvShowGridBinding -> displayGridTvItem(_binding)
            is ItemCategorySwiperMobileBinding -> displaySwiperMobileItem(_binding)

            is ContentTvShowMobileBinding -> displayTvShowMobile(_binding)
            is ContentTvShowTvBinding -> displayTvShowTv(_binding)
            is ContentTvShowSeasonsMobileBinding -> displaySeasonsMobile(_binding)
            is ContentTvShowSeasonsTvBinding -> displaySeasonsTv(_binding)
            is ContentTvShowDirectorsMobileBinding -> displayDirectorsMobile(_binding)
            is ContentTvShowDirectorsTvBinding -> displayDirectorsTv(_binding)
            is ContentTvShowCastMobileBinding -> displayCastMobile(_binding)
            is ContentTvShowCastTvBinding -> displayCastTv(_binding)
            is ContentTvShowRecommendationsMobileBinding -> displayRecommendationsMobile(_binding)
            is ContentTvShowRecommendationsTvBinding -> displayRecommendationsTv(_binding)
        }
    }

    fun setItemSelected(selected: Boolean) {
        itemSelected = selected
        when (_binding) {
            is ItemTvShowGridMobileBinding -> {
                _binding.root.isActivated = selected
                applyMobileSelection(_binding.root)
            }
            is ItemTvShowGridBinding -> _binding.root.isActivated = selected
        }
    }

    private fun isIptvProvider(): Boolean {
        val name = tvShow.providerName ?: UserPreferences.currentProvider?.name ?: ""
        val provider = Provider.providers.keys.find { it.name == name }
        return provider is IptvProvider
    }

    private fun episodeBadgeText(): String {
        if (isIptvProvider()) return ""
        return tvShow.lastPlayedEpisode?.let { "E${it.number}" }
            ?: tvShow.seasons.lastOrNull()?.episodes?.lastOrNull()?.let { "E${it.number}" }
            ?: tvShow.released?.format("yyyy")
            ?: context.getString(R.string.tv_show_item_type)
    }

    private fun checkProviderAndRun(action: () -> Unit) {
        if (!tvShow.providerName.isNullOrBlank() && tvShow.providerName != UserPreferences.currentProvider?.name) {
            Provider.providers.keys.find { it.name == tvShow.providerName }?.let {
                UserPreferences.currentProvider = it
            }
        }
        action()
    }

    private fun handleDirectPlay(navController: NavController) {
        val videoType = Video.Type.Episode(
            id = tvShow.id,
            number = 1,
            title = tvShow.title,
            poster = tvShow.poster,
            overview = tvShow.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = tvShow.id,
                title = tvShow.title,
                poster = tvShow.poster,
                banner = tvShow.banner,
                releaseDate = tvShow.released?.format("yyyy-MM-dd"),
                imdbId = tvShow.imdbId,
            ),
            season = Video.Type.Episode.Season(
                number = 1,
                title = "Live",
            ),
        )
        
        val args = Bundle().apply {
            putString("id", tvShow.id)
            putString("title", tvShow.title)
            putString("subtitle", tvShow.title)
            putSerializable("videoType", videoType)
        }
        navController.navigate(R.id.player, args)
    }

    private fun tvShowArgs(): Bundle {
        return Bundle().apply {
            putString("id", tvShow.id)
            putString("poster", tvShow.poster)
            putString("banner", tvShow.banner)
        }
    }

    private fun resolveEpisodeSeason(episode: Episode?): Season? {
        if (episode == null) return null

        val currentSeason = episode.season
        val seasonKey = episode.id.substringBeforeLast("/", "")
            .takeIf { it.isNotBlank() }
        if (currentSeason != null && currentSeason.number != 0) {
            return currentSeason
        }

        return tvShow.seasons.firstOrNull { season ->
            season.id == seasonKey ||
                season.id == currentSeason?.id ||
                season.episodes.any { it.id == episode.id } ||
                (episode.number != 0 && season.episodes.any { it.number == episode.number && it.title == episode.title })
        } ?: currentSeason
    }

    private fun setPoster(imageView: ImageView) {
        imageView.scaleType = if (isIptvProvider()) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        imageView.loadTvShowPoster(tvShow) {
            fallback(R.drawable.glide_fallback_cover)
            transition(DrawableTransitionOptions.withCrossFade())
        }
    }

    private fun displayMobileItem(binding: ItemTvShowMobileBinding) {
        binding.root.setOnClickListener {
            onTvShowClick?.let { listener ->
                listener(tvShow)
                return@setOnClickListener
            }
            checkProviderAndRun {
                if (isIptvProvider()) {
                    handleDirectPlay(binding.root.findNavController())
                } else {
                    binding.root.findNavController().navigate(R.id.tv_show, tvShowArgs())
                }
            }
        }
        binding.root.setOnLongClickListener {
            onTvShowLongClick?.let { listener ->
                listener(tvShow)
                return@setOnLongClickListener true
            }
            ShowOptionsMobileDialog(context, tvShow).show()
            true
        }
        setPoster(binding.ivTvShowPoster)
        bindRibbons(binding.ivTvShowFavoriteRibbon, binding.ivTvShowWatchedRibbon)
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            isVisible = !text.isNullOrEmpty()
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }
        binding.tvTvShowLastEpisode.text = episodeBadgeText()
        binding.tvTvShowTitle.text = tvShow.title
    }

    private fun displayTvItem(binding: ItemTvShowTvBinding) {
        binding.root.apply {
            setOnClickListener {
                onTvShowClick?.let { listener ->
                    listener(tvShow)
                    return@setOnClickListener
                }
                checkProviderAndRun {
                    if (isIptvProvider()) {
                        handleDirectPlay(findNavController())
                    } else {
                        findNavController().navigate(R.id.tv_show, tvShowArgs())
                    }
                }
            }
            setOnLongClickListener {
                onTvShowLongClick?.let { listener ->
                    listener(tvShow)
                    return@setOnLongClickListener true
                }
                ShowOptionsTvDialog(context, tvShow).show()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                val animation = if (hasFocus) AnimationUtils.loadAnimation(context, R.anim.zoom_in) else AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                startAnimation(animation)
                animation.fillAfter = true
                (context.toActivity()?.getCurrentFragment() as? HomeTvFragment)?.let { fragment ->
                    if (hasFocus) {
                        fragment.pinBackground(tvShow.banner)
                    } else {
                        fragment.releasePinnedBackground()
                    }
                }
            }
        }
        setPoster(binding.ivTvShowPoster)
        bindRibbons(binding.ivTvShowFavoriteRibbon, binding.ivTvShowWatchedRibbon)
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            isVisible = !text.isNullOrEmpty()
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }
        binding.tvTvShowLastEpisode.text = episodeBadgeText()
        binding.tvTvShowTitle.text = tvShow.title
    }

    private fun displayGridMobileItem(binding: ItemTvShowGridMobileBinding) {
        binding.root.alpha = 1f
        binding.root.isActivated = itemSelected
        applyMobileSelection(binding.root)
        binding.root.setOnKeyListener { _, _, event -> onTvShowKey?.invoke(tvShow, event) ?: false }
        binding.root.setOnClickListener {
            onTvShowClick?.let { listener ->
                listener(tvShow)
                return@setOnClickListener
            }
            checkProviderAndRun {
                if (isIptvProvider()) {
                    handleDirectPlay(binding.root.findNavController())
                } else {
                    binding.root.findNavController().navigate(R.id.tv_show, tvShowArgs())
                }
            }
        }
        binding.root.setOnLongClickListener {
            onTvShowLongClick?.let { listener ->
                listener(tvShow)
                return@setOnLongClickListener true
            }
            ShowOptionsMobileDialog(context, tvShow).show()
            true
        }
        setPoster(binding.ivTvShowPoster)
        bindRibbons(binding.ivTvShowFavoriteRibbon, binding.ivTvShowWatchedRibbon)
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            isVisible = !text.isNullOrEmpty()
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }
        binding.tvTvShowLastEpisode.text = episodeBadgeText()
        binding.tvTvShowTitle.text = tvShow.title
    }

    private fun displayGridTvItem(binding: ItemTvShowGridBinding) {
        binding.root.apply {
            alpha = 1f
            isActivated = itemSelected
            setOnKeyListener { _, _, event -> onTvShowKey?.invoke(tvShow, event) ?: false }
            setOnClickListener {
                onTvShowClick?.let { listener ->
                    listener(tvShow)
                    return@setOnClickListener
                }
                checkProviderAndRun {
                    if (isIptvProvider()) {
                        handleDirectPlay(findNavController())
                    } else {
                        findNavController().navigate(R.id.tv_show, tvShowArgs())
                    }
                }
            }
            setOnLongClickListener {
                onTvShowLongClick?.let { listener ->
                    listener(tvShow)
                    return@setOnLongClickListener true
                }
                ShowOptionsTvDialog(context, tvShow).show()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                val animation = if (hasFocus) AnimationUtils.loadAnimation(context, R.anim.zoom_in) else AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                startAnimation(animation)
                animation.fillAfter = true
            }
        }
        setPoster(binding.ivTvShowPoster)
        bindRibbons(binding.ivTvShowFavoriteRibbon, binding.ivTvShowWatchedRibbon)
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            isVisible = !text.isNullOrEmpty()
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }
        binding.tvTvShowLastEpisode.text = episodeBadgeText()
        binding.tvTvShowTitle.text = tvShow.title
    }

    private fun applyMobileSelection(view: View) {
        if (itemSelected) {
            val width = (4 * context.resources.displayMetrics.density).toInt()
            view.background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(width, ContextCompat.getColor(context, R.color.favorite_selected))
            }
            view.setPadding(width, width, width, width)
        } else {
            view.background = null
            view.setPadding(0, 0, 0, 0)
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun getInstalledSmartTubePackages(): List<String> {
        val installed = mutableListOf<String>()
        if (isPackageInstalled("org.smarttube.stable")) installed.add("org.smarttube.stable")
        if (isPackageInstalled("org.smarttube.beta")) installed.add("org.smarttube.beta")
        return installed
    }

    private fun launchSmartTube(packageName: String, trailerUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, trailerUrl.toUri())
        intent.setPackage(packageName)
        context.startActivity(intent)
    }

    private fun showSmartTubeVersionDialog(packages: List<String>, trailerUrl: String, shouldSavePreference: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = prefs.edit()

        val items = packages.map { pkg ->
            if (pkg == "org.smarttube.stable") context.getString(R.string.smarttube_stable)
            else context.getString(R.string.smarttube_beta)
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.choose_smarttube_version))
            .setItems(items) { _, which ->
                val selectedPackage = packages[which]

                if (shouldSavePreference) {
                    editor.putString("preferred_smarttube_package", selectedPackage).apply()
                }

                launchSmartTube(selectedPackage, trailerUrl)
            }.show()
    }

    private fun handleSmartTubeSelection(trailerUrl: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val savedPackage = prefs.getString("preferred_smarttube_package", null)
        val stPackages = getInstalledSmartTubePackages()

        if (stPackages.isEmpty()) {
            context.startActivity(Intent(Intent.ACTION_VIEW, trailerUrl.toUri()))
            return
        }

        if (stPackages.size == 1) {
            launchSmartTube(stPackages[0], trailerUrl)
            return
        }

        if (savedPackage != null && stPackages.contains(savedPackage)) {
            launchSmartTube(savedPackage, trailerUrl)
        } else {
            showSmartTubeVersionDialog(stPackages, trailerUrl, true)
        }
    }

    private fun safeLaunchYoutube(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("TvShowViewHolder", "Failed to launch YouTube intent", e)
            Toast.makeText(context, context.getString(R.string.player_external_player_error_video), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleTrailerClick(trailer: String) {
        val youtubeIntent = Intent(Intent.ACTION_VIEW, trailer.toUri())
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val preferredPlayer = prefs.getString("preferred_player", "ask")

        when (preferredPlayer) {
            "smarttube" -> {
                handleSmartTubeSelection(trailer)
            }
            "smarttube_stable" -> {
                launchSmartTube("org.smarttube.stable", trailer)
            }
            "smarttube_beta" -> {
                launchSmartTube("org.smarttube.beta", trailer)
            }
            "youtube" -> {
                safeLaunchYoutube(youtubeIntent)
            }
            else -> {
                val stPackages = getInstalledSmartTubePackages()
                if (stPackages.isNotEmpty()) {
                    AlertDialog.Builder(context)
                        .setTitle(context.getString(R.string.watch_trailer_with))
                        .setItems(arrayOf(context.getString(R.string.youtube), context.getString(R.string.smarttube))) { _, which ->
                            if (which == 0) {
                                safeLaunchYoutube(youtubeIntent)
                            } else {
                                if (stPackages.size > 1) {
                                    showSmartTubeVersionDialog(stPackages, trailer, false)
                                } else {
                                    launchSmartTube(stPackages[0], trailer)
                                }
                            }
                        }.show()
                } else {
                    safeLaunchYoutube(youtubeIntent)
                }
            }
        }
    }

    private fun displaySwiperMobileItem(binding: ItemCategorySwiperMobileBinding) {
        binding.ivSwiperBackground.loadTvShowBanner(tvShow) {
            centerCrop().transition(DrawableTransitionOptions.withCrossFade())
        }
        binding.tvSwiperTitle.text = tvShow.title
        binding.tvSwiperTvShowLastEpisode.text = if (isIptvProvider()) "" else tvShow.seasons.lastOrNull()?.episodes?.lastOrNull()?.let { "E${it.number}" } ?: context.getString(R.string.tv_show_item_type)
        
        binding.tvSwiperQuality.apply {
            text = tvShow.quality
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvSwiperReleased.apply {
            text = tvShow.released?.format("yyyy")
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvSwiperRating.apply {
            text = tvShow.rating?.let { String.format(Locale.ROOT, "%.1f", it) }
            isVisible = !text.isNullOrEmpty()
        }
        binding.ivSwiperRatingIcon.isVisible = binding.tvSwiperRating.isVisible

        binding.tvSwiperOverview.text = tvShow.overview
        binding.btnSwiperWatchNow.setOnClickListener {
            if (isIptvProvider()) {
                handleDirectPlay(binding.root.findNavController())
            } else {
                binding.root.findNavController().navigate(R.id.tv_show, tvShowArgs())
            }
        }
    }

    private fun bindRibbons(favoriteRibbon: View, watchedRibbon: View) {
        ribbonStateJob?.cancel()

        val boundTvShowId = tvShow.id
        favoriteRibbon.isVisible = tvShow.isFavorite
        watchedRibbon.isVisible = false
        val lifecycleOwner = itemView.findViewTreeLifecycleOwner()
            ?: context.toActivity()
            ?: return

        ribbonStateJob = lifecycleOwner.lifecycleScope.launch {
            combine(
                database.tvShowDao().getByIdAsFlow(boundTvShowId),
                database.episodeDao().isTvShowFullyWatchedAsFlow(boundTvShowId),
            ) { persistedTvShow, isFullyWatched ->
                (persistedTvShow?.isFavorite ?: tvShow.isFavorite) to isFullyWatched
            }.collect { (isFavorite, isFullyWatched) ->
                if (tvShow.id == boundTvShowId) {
                    favoriteRibbon.isVisible = isFavorite
                    watchedRibbon.isVisible = isFullyWatched
                }
            }
        }
    }

    private fun displayTvShowMobile(binding: ContentTvShowMobileBinding) {
        binding.ivTvShowPoster.run {
            loadTvShowPoster(tvShow) {
                fallback(R.drawable.glide_fallback_cover)
                transition(DrawableTransitionOptions.withCrossFade())
            }
            visibility = if (tvShow.poster.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        binding.tvTvShowTitle.text = tvShow.title

        binding.tvTvShowRating.apply {
            text = tvShow.rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"
            isVisible = !text.isNullOrEmpty()
        }
        binding.ivTvShowRatingIcon.isVisible = binding.tvTvShowRating.isVisible

        binding.tvTvShowQuality.apply {
            text = tvShow.quality
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowReleased.apply {
            text = tvShow.released?.format("yyyy")
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowRuntime.apply {
            text = tvShow.runtime?.let {
                val hours = it / 60
                val minutes = it % 60
                when {
                    hours > 0 -> context.getString(R.string.tv_show_runtime_hours_minutes, hours, minutes)
                    else -> context.getString(R.string.tv_show_runtime_minutes, minutes)
                }
            }
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowGenres.apply {
            text = tvShow.genres.joinToString(", ") { it.name }
            isVisible = tvShow.genres.isNotEmpty()
        }

        binding.tvTvShowOverview.text = tvShow.overview
        val episodeToWatch = tvShow.episodeToWatch
        val episodeSeason = resolveEpisodeSeason(episodeToWatch)
        binding.btnTvShowWatchNow.apply {
            isVisible = episodeToWatch != null
            setOnClickListener {
                if (isIptvProvider()) {
                    handleDirectPlay(findNavController())
                } else {
                    val videoType = Video.Type.Episode(
                        id = episodeToWatch!!.id,
                        number = episodeToWatch.number,
                        title = episodeToWatch.title,
                        poster = episodeToWatch.poster,
                        overview = episodeToWatch.overview,
                        tvShow = Video.Type.Episode.TvShow(
                            id = tvShow.id,
                            title = tvShow.title,
                            poster = tvShow.poster,
                            banner = tvShow.banner,
                            releaseDate = tvShow.released?.format("yyyy-MM-dd"),
                            imdbId = tvShow.imdbId,
                        ),
                        season = Video.Type.Episode.Season(
                            number = episodeSeason?.number ?: 1,
                            title = episodeSeason?.title ?: "",
                        ),
                    )
                    val args = Bundle().apply {
                        putString("id", episodeToWatch.id)
                        putString("title", tvShow.title)
                        putString("subtitle", "S${videoType.season.number} E${videoType.number}  •  ${videoType.title}")
                        putSerializable("videoType", videoType)
                    }
                    findNavController().navigate(R.id.player, args)
                }
            }
            text = if (isIptvProvider()) context.getString(R.string.movie_watch_now) else context.getString(R.string.tv_show_watch_season_episode, episodeSeason?.number ?: 1, episodeToWatch?.number ?: 1)
        }

        binding.pbTvShowProgressEpisode.apply {
            val watchHistory = episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }

        binding.btnTvShowTrailer.apply {
            val trailer = tvShow.trailer
            setOnClickListener {
                if (trailer != null) handleTrailerClick(trailer)
            }
            isVisible = trailer != null
        }

        binding.btnTvShowFavorite.apply {
            fun Boolean.drawable() = when (this) {
                true -> R.drawable.ic_favorite_enable
                false -> R.drawable.ic_favorite_disable
            }

            setOnClickListener {
                checkProviderAndRun {
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.tvShowDao()
                        val current = dao.getById(tvShow.id)?.isFavorite ?: false
                        val newValue = !current
                        val resolvedTvShow = ArtworkRepair.resolveTvShowForFavorite(context, tvShow, newValue)

                        dao.upsertFavorite(resolvedTvShow, newValue)

                        withContext(Dispatchers.Main) {
                            tvShow.poster = resolvedTvShow.poster
                            tvShow.banner = resolvedTvShow.banner
                            tvShow.isFavorite = newValue
                            setImageDrawable(
                                ContextCompat.getDrawable(context, newValue.drawable())
                            )
                        }
                    }
                }
            }

            setImageDrawable(
                ContextCompat.getDrawable(context, tvShow.isFavorite.drawable())
            )
        }
    }

    private fun displayTvShowTv(binding: ContentTvShowTvBinding) {
        binding.ivTvShowPoster.run {
            loadTvShowPoster(tvShow) {
                fallback(R.drawable.glide_fallback_cover)
                transition(DrawableTransitionOptions.withCrossFade())
            }
            visibility = if (tvShow.poster.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        binding.tvTvShowTitle.text = tvShow.title

        binding.tvTvShowRating.apply {
            text = tvShow.rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"
            isVisible = !text.isNullOrEmpty()
        }
        binding.ivTvShowRatingIcon.isVisible = binding.tvTvShowRating.isVisible

        binding.tvTvShowQuality.apply {
            text = tvShow.quality
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowReleased.apply {
            text = tvShow.released?.format("yyyy")
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowRuntime.apply {
            text = tvShow.runtime?.let {
                val hours = it / 60
                val minutes = it % 60
                when {
                    hours > 0 -> context.getString(R.string.tv_show_runtime_hours_minutes, hours, minutes)
                    else -> context.getString(R.string.tv_show_runtime_minutes, minutes)
                }
            }
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowGenres.apply {
            text = tvShow.genres.joinToString(", ") { it.name }
            isVisible = tvShow.genres.isNotEmpty()
        }

        binding.tvTvShowOverview.text = tvShow.overview
        val episodeToWatch = tvShow.episodeToWatch
        val episodeSeason = resolveEpisodeSeason(episodeToWatch)
        binding.btnTvShowWatchNow.apply {
            isVisible = episodeToWatch != null
            setOnClickListener {
                if (isIptvProvider()) {
                    handleDirectPlay(findNavController())
                } else {
                    val videoType = Video.Type.Episode(
                        id = episodeToWatch!!.id,
                        number = episodeToWatch.number,
                        title = episodeToWatch.title,
                        poster = episodeToWatch.poster,
                        overview = episodeToWatch.overview,
                        tvShow = Video.Type.Episode.TvShow(
                            id = tvShow.id,
                            title = tvShow.title,
                            poster = tvShow.poster,
                            banner = tvShow.banner,
                            releaseDate = tvShow.released?.format("yyyy-MM-dd"),
                            imdbId = tvShow.imdbId,
                        ),
                        season = Video.Type.Episode.Season(
                            number = episodeSeason?.number ?: 1,
                            title = episodeSeason?.title ?: "",
                        ),
                    )
                    val args = Bundle().apply {
                        putString("id", episodeToWatch.id)
                        putString("title", tvShow.title)
                        putString("subtitle", "S${videoType.season.number} E${videoType.number}  •  ${videoType.title}")
                        putSerializable("videoType", videoType)
                    }
                    findNavController().navigate(R.id.player, args)
                }
            }
            text = if (isIptvProvider()) context.getString(R.string.movie_watch_now) else context.getString(R.string.tv_show_watch_season_episode, episodeSeason?.number ?: 1, episodeToWatch?.number ?: 1)
        }

        binding.pbTvShowProgressEpisode.apply {
            val watchHistory = episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }

        binding.btnTvShowTrailer.apply {
            val trailer = tvShow.trailer
            setOnClickListener {
                if (trailer != null) handleTrailerClick(trailer)
            }
            isVisible = trailer != null
        }

        binding.btnTvShowFavorite.apply {
            fun Boolean.drawable() = when (this) {
                true -> R.drawable.ic_favorite_enable
                false -> R.drawable.ic_favorite_disable
            }

            setOnClickListener {
                checkProviderAndRun {
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.tvShowDao()
                        val current = dao.getById(tvShow.id)?.isFavorite ?: false
                        val newValue = !current
                        val resolvedTvShow = ArtworkRepair.resolveTvShowForFavorite(context, tvShow, newValue)

                        dao.upsertFavorite(resolvedTvShow, newValue)

                        withContext(Dispatchers.Main) {
                            tvShow.poster = resolvedTvShow.poster
                            tvShow.banner = resolvedTvShow.banner
                            tvShow.isFavorite = newValue
                            setImageDrawable(
                                ContextCompat.getDrawable(context, newValue.drawable())
                            )
                        }
                    }
                }
            }

            setImageDrawable(
                ContextCompat.getDrawable(context, tvShow.isFavorite.drawable())
            )
        }
    }

    private fun displaySeasonsMobile(binding: ContentTvShowSeasonsMobileBinding) {
        binding.rvTvShowSeasons.apply {
            adapter = AppAdapter().apply { submitList(tvShow.seasons.onEach { it.itemType = AppAdapter.Type.SEASON_MOBILE_ITEM }) }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(10.dp(context)))
            }
        }
    }

    private fun displaySeasonsTv(binding: ContentTvShowSeasonsTvBinding) {
        binding.hgvTvShowSeasons.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply { submitList(tvShow.seasons.onEach { it.itemType = AppAdapter.Type.SEASON_TV_ITEM }) }
            setItemSpacing(20)
        }
    }

    private fun displayDirectorsMobile(binding: ContentTvShowDirectorsMobileBinding) { binding.rvTvShowDirectors.text = tvShow.directors.joinToString(", ") { it.name } }
    private fun displayDirectorsTv(binding: ContentTvShowDirectorsTvBinding) { binding.hgvTvShowDirectors.text = tvShow.directors.joinToString(", ") { it.name } }
    private fun displayCastMobile(binding: ContentTvShowCastMobileBinding) {
        binding.rvTvShowCast.apply {
            adapter = AppAdapter().apply {
                submitList(tvShow.cast.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
                })
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(10.dp(context)))
            }
        }
    }

    private fun displayCastTv(binding: ContentTvShowCastTvBinding) {
        binding.hgvTvShowCast.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(tvShow.cast.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
                })
            }
            setItemSpacing(20)
        }
    }
    private fun displayRecommendationsMobile(binding: ContentTvShowRecommendationsMobileBinding) {
        binding.rvTvShowRecommendations.apply {
            adapter = AppAdapter().apply {
                submitList(tvShow.recommendations.onEach {
                    when (it) {
                        is Movie -> it.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
                        is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                    }
                })
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(10.dp(context)))
            }
        }
    }

    private fun displayRecommendationsTv(binding: ContentTvShowRecommendationsTvBinding) {
        binding.hgvTvShowRecommendations.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(tvShow.recommendations.onEach {
                    when (it) {
                        is Movie -> it.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                        is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                    }
                })
            }
            setItemSpacing(20)
        }
    }
}
