package com.streamflixreborn.streamflix.adapters.viewholders

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.ContentMovieCastMobileBinding
import com.streamflixreborn.streamflix.databinding.ContentMovieCastTvBinding
import com.streamflixreborn.streamflix.databinding.ContentMovieMobileBinding
import com.streamflixreborn.streamflix.databinding.ContentMovieRecommendationsMobileBinding
import com.streamflixreborn.streamflix.databinding.ContentMovieRecommendationsTvBinding
import com.streamflixreborn.streamflix.databinding.ContentMovieTvBinding
import com.streamflixreborn.streamflix.databinding.ItemCategorySwiperMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemMovieGridMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemMovieGridTvBinding
import com.streamflixreborn.streamflix.databinding.ItemMovieMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemMovieTvBinding
import com.streamflixreborn.streamflix.fragments.favorites.FavoritesMobileFragment
import com.streamflixreborn.streamflix.fragments.favorites.FavoritesMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.favorites.FavoritesTvFragment
import com.streamflixreborn.streamflix.fragments.favorites.FavoritesTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.genre.GenreMobileFragment
import com.streamflixreborn.streamflix.fragments.genre.GenreMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.genre.GenreTvFragment
import com.streamflixreborn.streamflix.fragments.genre.GenreTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.home.HomeMobileFragment
import com.streamflixreborn.streamflix.fragments.home.HomeMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.home.HomeTvFragment
import com.streamflixreborn.streamflix.fragments.home.HomeTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.movie.MovieMobileFragment
import com.streamflixreborn.streamflix.fragments.movie.MovieMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.movie.MovieTvFragment
import com.streamflixreborn.streamflix.fragments.movie.MovieTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.movies.MoviesMobileFragment
import com.streamflixreborn.streamflix.fragments.movies.MoviesMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.movies.MoviesTvFragment
import com.streamflixreborn.streamflix.fragments.movies.MoviesTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.people.PeopleMobileFragment
import com.streamflixreborn.streamflix.fragments.people.PeopleMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.people.PeopleTvFragment
import com.streamflixreborn.streamflix.fragments.people.PeopleTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.search.SearchMobileFragment
import com.streamflixreborn.streamflix.fragments.search.SearchMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.search.SearchTvFragment
import com.streamflixreborn.streamflix.fragments.search.SearchTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.tv_show.TvShowMobileFragment
import com.streamflixreborn.streamflix.fragments.tv_show.TvShowMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.tv_show.TvShowTvFragment
import com.streamflixreborn.streamflix.fragments.tv_show.TvShowTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.tv_shows.TvShowsTvFragment
import com.streamflixreborn.streamflix.fragments.tv_shows.TvShowsTvFragmentDirections
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.ui.ShowOptionsMobileDialog
import com.streamflixreborn.streamflix.ui.ShowOptionsTvDialog
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.dp
import androidx.preference.Preference
import com.streamflixreborn.streamflix.utils.format
import com.streamflixreborn.streamflix.utils.getCurrentFragment
import com.streamflixreborn.streamflix.utils.loadMovieBanner
import com.streamflixreborn.streamflix.utils.loadMoviePoster
import com.streamflixreborn.streamflix.utils.ArtworkRepair
import com.streamflixreborn.streamflix.utils.toActivity
import java.util.Locale
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.providers.Provider
import android.view.KeyEvent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.streamflixreborn.streamflix.databinding.ContentMovieDirectorsMobileBinding
import com.streamflixreborn.streamflix.databinding.ContentMovieDirectorsTvBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MovieViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private val database: AppDatabase
        get() = AppDatabase.getInstance(context)
    private lateinit var movie: Movie
    private var onMovieClick: ((Movie) -> Unit)? = null
    private var onMovieLongClick: ((Movie) -> Unit)? = null
    private var onMovieKey: ((Movie, KeyEvent) -> Boolean)? = null
    private var itemSelected: Boolean = false
    private var ribbonStateJob: Job? = null
    private val TAG = "TrailerChoiceDebug" // Logging Tag

    companion object {
        private const val KEY_PREFERRED_PLAYER = "preferred_player"
        private const val KEY_SMARTTUBE_PACKAGE = "preferred_smarttube_package" // New key for saving the exact package
        private const val PLAYER_YOUTUBE = "youtube"
        private const val PLAYER_SMARTTUBE = "smarttube"
        private const val PLAYER_SMARTTUBE_STABLE = "smarttube_stable"
        private const val PLAYER_SMARTTUBE_BETA = "smarttube_beta"
        private const val PLAYER_ASK = "ask"
        private const val SMARTTUBE_STABLE_PACKAGE = "org.smarttube.stable"
        private const val SMARTTUBE_BETA_PACKAGE = "org.smarttube.beta"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val YOUTUBE_TV_PACKAGE = "com.google.android.tv.youtube"
    }

    val childRecyclerView: RecyclerView?
        get() = when (_binding) {
            is ContentMovieCastMobileBinding -> _binding.rvMovieCast
            is ContentMovieCastTvBinding -> _binding.hgvMovieCast
            is ContentMovieRecommendationsMobileBinding -> _binding.rvMovieRecommendations
            is ContentMovieRecommendationsTvBinding -> _binding.hgvMovieRecommendations
            else -> null
        }

    fun bind(
        movie: Movie,
        onMovieClick: ((Movie) -> Unit)? = null,
        onMovieLongClick: ((Movie) -> Unit)? = null,
        onMovieKey: ((Movie, KeyEvent) -> Boolean)? = null,
        itemSelected: Boolean = false,
    ) {
        this.movie = movie
        this.onMovieClick = onMovieClick
        this.onMovieLongClick = onMovieLongClick
        this.onMovieKey = onMovieKey
        this.itemSelected = itemSelected

        when (_binding) {
            is ItemMovieMobileBinding -> displayMobileItem(_binding)
            is ItemMovieTvBinding -> displayTvItem(_binding)
            is ItemMovieGridMobileBinding -> displayGridMobileItem(_binding)
            is ItemMovieGridTvBinding -> displayGridTvItem(_binding)
            is ItemCategorySwiperMobileBinding -> displaySwiperMobileItem(_binding)

            is ContentMovieMobileBinding -> displayMovieMobile(_binding)
            is ContentMovieTvBinding -> displayMovieTv(_binding)
            is ContentMovieDirectorsMobileBinding -> displayDirectorsMobile(_binding)
            is ContentMovieDirectorsTvBinding -> displayDirectorsTv(_binding)
            is ContentMovieCastMobileBinding -> displayCastMobile(_binding)
            is ContentMovieCastTvBinding -> displayCastTv(_binding)
            is ContentMovieRecommendationsMobileBinding -> displayRecommendationsMobile(_binding)
            is ContentMovieRecommendationsTvBinding -> displayRecommendationsTv(_binding)
        }
    }

    fun setItemSelected(selected: Boolean) {
        itemSelected = selected
        when (_binding) {
            is ItemMovieGridMobileBinding -> {
                _binding.root.isActivated = selected
                applyMobileSelection(_binding.root)
            }
            is ItemMovieGridTvBinding -> _binding.root.isActivated = selected
        }
    }

    private fun checkProviderAndRun(action: () -> Unit) {
        if (!movie.providerName.isNullOrBlank() && movie.providerName != UserPreferences.currentProvider?.name) {
            Provider.providers.keys.find { it.name == movie.providerName }?.let {
                UserPreferences.currentProvider = it
            }
        }
        action()
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun getInstalledSmartTubePackages(): List<String> {
        val installed = mutableListOf<String>()
        if (isPackageInstalled(SMARTTUBE_STABLE_PACKAGE)) installed.add(SMARTTUBE_STABLE_PACKAGE)
        if (isPackageInstalled(SMARTTUBE_BETA_PACKAGE)) installed.add(SMARTTUBE_BETA_PACKAGE)
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
            if (pkg == SMARTTUBE_STABLE_PACKAGE) context.getString(R.string.smarttube_stable)
            else context.getString(R.string.smarttube_beta)
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.choose_smarttube_version))
            .setItems(items) { _, which ->
                val selectedPackage = packages[which]
                
                if (shouldSavePreference) {
                    // Salva la scelta dell'utente se la preferenza principale è "smarttube"
                    editor.putString(KEY_SMARTTUBE_PACKAGE, selectedPackage).apply()
                    Log.d(TAG, "SmartTube version saved: $selectedPackage")
                }
                
                launchSmartTube(selectedPackage, trailerUrl)
            }.show()
    }

    private fun safeLaunchYoutube(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch YouTube intent", e)
            Toast.makeText(context, context.getString(R.string.player_external_player_error_video), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSmartTubeSelection(trailerUrl: String, logPrefix: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val savedPackage = prefs.getString(KEY_SMARTTUBE_PACKAGE, null)
        val stPackages = getInstalledSmartTubePackages()

        Log.d(TAG, "$logPrefix: SmartTube packages found: ${stPackages.size}. Saved package: $savedPackage")
        
        if (stPackages.isEmpty()) {
            // Caso 1: Nessuna SmartTube installata. Fallback su YouTube.
            Log.d(TAG, "$logPrefix: No SmartTube installed, falling back to YouTube")
            safeLaunchYoutube(Intent(Intent.ACTION_VIEW, trailerUrl.toUri()))
            return
        }

        if (stPackages.size == 1) {
            // Caso 2: Una sola SmartTube installata. Avvia direttamente.
            Log.d(TAG, "$logPrefix: Only one SmartTube installed: ${stPackages[0]}. Launching directly.")
            launchSmartTube(stPackages[0], trailerUrl)
            return
        }
        
        // Caso 3: Stable e Beta installate.
        if (savedPackage != null && stPackages.contains(savedPackage)) {
            // Caso 3a: Versione preferita è installata. Avvia direttamente la versione salvata.
            Log.d(TAG, "$logPrefix: Saved SmartTube version found: $savedPackage. Launching directly.")
            launchSmartTube(savedPackage, trailerUrl)
        } else {
            // Caso 3b: Nessuna preferenza salvata O la versione salvata non è più installata. Chiedi all'utente e salva la nuova scelta.
            Log.d(TAG, "$logPrefix: Saved version invalid or missing. Asking user which version to use.")
            showSmartTubeVersionDialog(stPackages, trailerUrl, true)
        }
    }

    private fun handleTrailerClick(trailer: String, logPrefix: String) {
        Log.d(TAG, "$logPrefix: Clicked. Trailer URL: $trailer")

        val youtubeIntent = Intent(Intent.ACTION_VIEW, trailer.toUri())
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val preferredPlayer = prefs.getString(KEY_PREFERRED_PLAYER, PLAYER_ASK)
        Log.d(TAG, "$logPrefix: Preferred player from settings: $preferredPlayer")

        when (preferredPlayer) {
            PLAYER_SMARTTUBE -> {
                handleSmartTubeSelection(trailer, logPrefix)
            }
            PLAYER_SMARTTUBE_STABLE -> {
                Log.d(TAG, "$logPrefix: Launching SmartTube Stable (Preferred)")
                launchSmartTube(SMARTTUBE_STABLE_PACKAGE, trailer)
            }
            PLAYER_SMARTTUBE_BETA -> {
                Log.d(TAG, "$logPrefix: Launching SmartTube Beta (Preferred)")
                launchSmartTube(SMARTTUBE_BETA_PACKAGE, trailer)
            }
            PLAYER_YOUTUBE -> {
                Log.d(TAG, "$logPrefix: Launching YouTube (Preferred)")
                safeLaunchYoutube(youtubeIntent)
            }
            else -> { // PLAYER_ASK or nothing set
                val stPackages = getInstalledSmartTubePackages()
                if (stPackages.isNotEmpty()) {
                    Log.d(TAG, "$logPrefix: Showing choice dialog (Ask)")
                    AlertDialog.Builder(context)
                        .setTitle(context.getString(R.string.watch_trailer_with))
                        .setItems(arrayOf(context.getString(R.string.youtube), context.getString(R.string.smarttube))) { _, which ->
                            if (which == 0) {
                                Log.d(TAG, "$logPrefix: Dialog (Ask): YouTube selected")
                                safeLaunchYoutube(youtubeIntent)
                            } else {
                                Log.d(TAG, "$logPrefix: Dialog (Ask): SmartTube selected")
                                // Qui, non salvare la preferenza per la versione SmartTube,
                                // ma chiedi quale usare se ci sono due installazioni.
                                if (stPackages.size > 1) {
                                    showSmartTubeVersionDialog(stPackages, trailer, false)
                                } else {
                                    launchSmartTube(stPackages[0], trailer)
                                }
                            }
                        }.show()
                } else {
                    Log.d(TAG, "$logPrefix: SmartTube not found, launching YouTube directly")
                    safeLaunchYoutube(youtubeIntent)
                }
            }
        }
    }

    private fun displayMobileItem(binding: ItemMovieMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                onMovieClick?.let { listener ->
                    listener(movie)
                    return@setOnClickListener
                }
                checkProviderAndRun {
                    when (context.toActivity()?.getCurrentFragment()) {
                        is HomeMobileFragment -> {
                            findNavController().navigate(HomeMobileFragmentDirections.actionHomeToMovie(id = movie.id))
                            if (movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM) {
                                findNavController().navigate(MovieMobileFragmentDirections.actionMovieToPlayer(
                                    id = movie.id,
                                    title = movie.title,
                                    subtitle = movie.released?.format("yyyy") ?: "",
                                    videoType = Video.Type.Movie(id = movie.id, title = movie.title, releaseDate = movie.released?.format("yyyy-MM-dd") ?: "", poster = movie.poster ?: "", imdbId = movie.imdbId),
                                ))
                            }
                        }
                        is MovieMobileFragment -> findNavController().navigate(MovieMobileFragmentDirections.actionMovieToMovie(id = movie.id))
                        is TvShowMobileFragment -> findNavController().navigate(TvShowMobileFragmentDirections.actionTvShowToMovie(id = movie.id))
                        is FavoritesMobileFragment -> findNavController().navigate(FavoritesMobileFragmentDirections.actionFavoritesToMovie(id = movie.id))
                    }
                }
            }
            setOnLongClickListener {
                onMovieLongClick?.let { listener ->
                    listener(movie)
                    return@setOnLongClickListener true
                }
                ShowOptionsMobileDialog(context, movie).show()
                true
            }
        }

        binding.ivMoviePoster.loadMoviePoster(movie) {
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }
        bindRibbons(binding.ivMovieFavoriteRibbon, binding.ivMovieWatchedRibbon)

        binding.tvMovieQuality.apply {
            text = movie.quality ?: ""
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieReleasedYear.text = movie.released?.format("yyyy")
            ?: context.getString(R.string.movie_item_type)

        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvMovieTitle.text = movie.title
    }

    private fun displayTvItem(binding: ItemMovieTvBinding) {
        binding.root.apply {
            isFocusable = true
            setOnClickListener {
                onMovieClick?.let { listener ->
                    listener(movie)
                    return@setOnClickListener
                }
                checkProviderAndRun {
                    when (context.toActivity()?.getCurrentFragment()) {
                        is HomeTvFragment -> {
                            if (movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM) {
                                findNavController().navigate(
                                    R.id.action_global_player,
                                    Bundle().apply {
                                        putString("id", movie.id)
                                        putString("title", movie.title)
                                        putString("subtitle", movie.released?.format("yyyy") ?: "")
                                        putSerializable(
                                            "videoType",
                                            Video.Type.Movie(
                                                id = movie.id,
                                                title = movie.title,
                                                releaseDate = movie.released?.format("yyyy-MM-dd") ?: "",
                                                poster = movie.poster ?: movie.banner ?: "",
                                                imdbId = movie.imdbId,
                                            )
                                        )
                                    }
                                )
                            } else {
                                findNavController().navigate(HomeTvFragmentDirections.actionHomeToMovie(id = movie.id))
                            }
                        }
                        is MoviesTvFragment -> findNavController().navigate(MoviesTvFragmentDirections.actionMoviesToMovie(id = movie.id))
                        is GenreTvFragment -> findNavController().navigate(GenreTvFragmentDirections.actionGenreToMovie(id = movie.id))
                        is SearchTvFragment -> findNavController().navigate(SearchTvFragmentDirections.actionSearchToMovie(id = movie.id))
                        is MovieTvFragment -> findNavController().navigate(MovieTvFragmentDirections.actionMovieToMovie(id = movie.id))
                        is TvShowTvFragment -> findNavController().navigate(TvShowTvFragmentDirections.actionTvShowToMovie(id = movie.id))
                        is PeopleTvFragment -> findNavController().navigate(PeopleTvFragmentDirections.actionPeopleToMovie(id = movie.id))
                        is FavoritesTvFragment -> findNavController().navigate(FavoritesTvFragmentDirections.actionFavoritesToMovie(id = movie.id))
                    }
                }
            }


            setOnLongClickListener {
                onMovieLongClick?.let { listener ->
                    listener(movie)
                    return@setOnLongClickListener true
                }
                ShowOptionsTvDialog(context, movie).show()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                val animation = when {
                    hasFocus -> AnimationUtils.loadAnimation(context, R.anim.zoom_in)
                    else -> AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                }
                binding.root.startAnimation(animation)
                animation.fillAfter = true

                when (val fragment = context.toActivity()?.getCurrentFragment()) {
                    is HomeTvFragment -> {
                        if (hasFocus) {
                            fragment.pinBackground(movie.banner)
                        } else {
                            fragment.releasePinnedBackground()
                        }
                    }
                }
            }
        }

        binding.ivMoviePoster.loadMoviePoster(movie) {
            fallback(R.drawable.glide_fallback_cover)
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }
        bindRibbons(binding.ivMovieFavoriteRibbon, binding.ivMovieWatchedRibbon)
        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory
            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }
        binding.tvMovieQuality.apply {
            text = movie.quality ?: ""
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }
        binding.tvMovieReleasedYear.text = movie.released?.format("yyyy")
            ?: context.getString(R.string.movie_item_type)
        binding.tvMovieTitle.text = movie.title
    }

    private fun displayGridMobileItem(binding: ItemMovieGridMobileBinding) {
        binding.root.apply {
            alpha = 1f
            isActivated = itemSelected
            applyMobileSelection(this)
            setOnKeyListener { _, _, event -> onMovieKey?.invoke(movie, event) ?: false }
            setOnClickListener {
                onMovieClick?.let { listener ->
                    listener(movie)
                    return@setOnClickListener
                }
                checkProviderAndRun {
                    when (context.toActivity()?.getCurrentFragment()) {
                        is GenreMobileFragment -> findNavController().navigate(GenreMobileFragmentDirections.actionGenreToMovie(id = movie.id))
                        is MoviesMobileFragment -> findNavController().navigate(MoviesMobileFragmentDirections.actionMoviesToMovie(id = movie.id))
                        is PeopleMobileFragment -> findNavController().navigate(PeopleMobileFragmentDirections.actionPeopleToMovie(id = movie.id))
                        is SearchMobileFragment -> findNavController().navigate(SearchMobileFragmentDirections.actionSearchToMovie(id = movie.id))
                        is FavoritesMobileFragment -> findNavController().navigate(FavoritesMobileFragmentDirections.actionFavoritesToMovie(id = movie.id))
                    }
                }
            }
            setOnLongClickListener {
                onMovieLongClick?.let { listener ->
                    listener(movie)
                    return@setOnLongClickListener true
                }
                ShowOptionsMobileDialog(context, movie).show()
                true
            }
        }

        binding.ivMoviePoster.loadMoviePoster(movie) {
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }
        bindRibbons(binding.ivMovieFavoriteRibbon, binding.ivMovieWatchedRibbon)

        binding.tvMovieQuality.apply {
            text = movie.quality ?: ""
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieReleasedYear.text = movie.released?.format("yyyy")
            ?: context.getString(R.string.movie_item_type)

        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvMovieTitle.text = movie.title
    }

    private fun displayGridTvItem(binding: ItemMovieGridTvBinding) {
        binding.root.apply {
            isFocusable = true
            alpha = 1f
            isActivated = itemSelected
            setOnKeyListener { _, _, event -> onMovieKey?.invoke(movie, event) ?: false }
            setOnClickListener {
                onMovieClick?.let { listener ->
                    listener(movie)
                    return@setOnClickListener
                }
                checkProviderAndRun {
                    when (context.toActivity()?.getCurrentFragment()) {
                        is HomeTvFragment -> findNavController().navigate(HomeTvFragmentDirections.actionHomeToMovie(id = movie.id))
                        is MoviesTvFragment -> findNavController().navigate(MoviesTvFragmentDirections.actionMoviesToMovie(id = movie.id))
                        is GenreTvFragment -> findNavController().navigate(GenreTvFragmentDirections.actionGenreToMovie(id = movie.id))
                        is SearchTvFragment -> findNavController().navigate(SearchTvFragmentDirections.actionSearchToMovie(id = movie.id))
                        is PeopleTvFragment -> findNavController().navigate(PeopleTvFragmentDirections.actionPeopleToMovie(id = movie.id))
                        is FavoritesTvFragment -> findNavController().navigate(FavoritesTvFragmentDirections.actionFavoritesToMovie(id = movie.id))
                    }
                }
            }

            setOnLongClickListener {
                onMovieLongClick?.let { listener ->
                    listener(movie)
                    return@setOnLongClickListener true
                }
                ShowOptionsTvDialog(context, movie).show()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                val animation = when {
                    hasFocus -> AnimationUtils.loadAnimation(context, R.anim.zoom_in)
                    else -> AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                }
                binding.root.startAnimation(animation)
                animation.fillAfter = true
            }
        }
        binding.ivMoviePoster.loadMoviePoster(movie) {
            fallback(R.drawable.glide_fallback_cover)
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }
        bindRibbons(binding.ivMovieFavoriteRibbon, binding.ivMovieWatchedRibbon)
        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory
            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }
        binding.tvMovieQuality.apply {
            text = movie.quality ?: ""
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }
        binding.tvMovieReleasedYear.text = movie.released?.format("yyyy")
            ?: context.getString(R.string.movie_item_type)
        binding.tvMovieTitle.text = movie.title
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
    private fun bindRibbons(favoriteRibbon: View, watchedRibbon: View) {
        favoriteRibbon.visibility = if (movie.isFavorite) View.VISIBLE else View.GONE
        watchedRibbon.visibility = if (movie.isWatched) View.VISIBLE else View.GONE

        ribbonStateJob?.cancel()
        val boundMovieId = movie.id
        val lifecycleOwner = itemView.findViewTreeLifecycleOwner()
            ?: context.toActivity()
            ?: return

        ribbonStateJob = lifecycleOwner.lifecycleScope.launch {
            database.movieDao().getByIdAsFlow(boundMovieId).collect { persistedMovie ->
                if (movie.id != boundMovieId || persistedMovie == null) return@collect
                favoriteRibbon.visibility = if (persistedMovie.isFavorite) View.VISIBLE else View.GONE
                watchedRibbon.visibility = if (persistedMovie.isWatched) View.VISIBLE else View.GONE
            }
        }
    }

    private fun displaySwiperMobileItem(binding: ItemCategorySwiperMobileBinding) {
        binding.ivSwiperBackground.loadMovieBanner(movie) {
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }

        binding.tvSwiperTitle.text = movie.title

        binding.tvSwiperTvShowLastEpisode.text = context.getString(R.string.movie_item_type)

        binding.tvSwiperQuality.apply {
            text = movie.quality
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvSwiperReleased.apply {
            text = movie.released?.format("yyyy")
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvSwiperRating.apply {
            text = movie.rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.ivSwiperRatingIcon.visibility = binding.tvSwiperRating.visibility

        binding.tvSwiperOverview.apply {
            setOnClickListener {
                maxLines = when (maxLines) {
                    2 -> Int.MAX_VALUE
                    else -> 2
                }
            }

            text = movie.overview
        }

        binding.btnSwiperWatchNow.apply {
            setOnClickListener {
                findNavController().navigate(
                    HomeMobileFragmentDirections.actionHomeToMovie(
                        id = movie.id,
                    )
                )
            }
        }

        binding.pbSwiperProgress.apply {
            val watchHistory = movie.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }
    }


    private fun displayMovieMobile(binding: ContentMovieMobileBinding) {
        binding.ivMoviePoster.run {
            loadMoviePoster(movie) {
                transition(DrawableTransitionOptions.withCrossFade())
            }
            visibility = when {
                movie.poster.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieTitle.text = movie.title

        binding.tvMovieRating.text = movie.rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"

        binding.tvMovieQuality.apply {
            text = movie.quality
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieReleased.apply {
            text = movie.released?.format("yyyy")
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieRuntime.apply {
            text = movie.runtime?.let {
                val hours = it / 60
                val minutes = it % 60
                when {
                    hours > 0 -> context.getString(
                        R.string.movie_runtime_hours_minutes,
                        hours,
                        minutes
                    )
                    else -> context.getString(R.string.movie_runtime_minutes, minutes)
                }
            }
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieGenres.apply {
            text = movie.genres.joinToString(", ") { it.name }
            visibility = when {
                movie.genres.isEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieOverview.text = movie.overview

        binding.btnMovieWatchNow.apply {
            setOnClickListener {
                // Este botón ya navega al reproductor, no a otra página de detalles.
                // Generalmente non necesita el cambio de proveedor, pero lo añadimos por seguridad.
                checkProviderAndRun {
                    findNavController().navigate(MovieMobileFragmentDirections.actionMovieToPlayer(
                        id = movie.id,
                        title = movie.title,
                        subtitle = movie.released?.format("yyyy") ?: "",
                        videoType = Video.Type.Movie(id = movie.id, title = movie.title, releaseDate = movie.released?.format("yyyy-MM-dd") ?: "", poster = movie.poster ?: movie.banner ?: "", imdbId = movie.imdbId),
                    ))
                }
            }
        }

        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.btnMovieTrailer.apply {
            val trailer = movie.trailer
            setOnClickListener {
                if (trailer != null) handleTrailerClick(trailer, "MovieMobile")
            }
            visibility = if (trailer != null) View.VISIBLE else View.GONE
        }

        binding.btnMovieFavorite.apply {

            fun Boolean.drawable() = when (this) {
                true -> R.drawable.ic_favorite_enable
                false -> R.drawable.ic_favorite_disable
            }

            setOnClickListener {
                checkProviderAndRun {
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.movieDao()
                        val current = dao.getById(movie.id)?.isFavorite ?: false
                        val newValue = !current
                        val resolvedMovie = ArtworkRepair.resolveMovieForFavorite(context, movie, newValue)

                        dao.upsertFavorite(resolvedMovie, newValue)

                        withContext(Dispatchers.Main) {
                            movie.poster = resolvedMovie.poster
                            movie.banner = resolvedMovie.banner
                            movie.isFavorite = newValue
                            setImageDrawable(
                                ContextCompat.getDrawable(context, newValue.drawable())
                            )
                        }
                    }
                }
            }

            setImageDrawable(
                ContextCompat.getDrawable(context, movie.isFavorite.drawable())
            )
        }
    }

    private fun displayMovieTv(binding: ContentMovieTvBinding) {
        binding.ivMoviePoster.run {
            loadMoviePoster(movie) {
                transition(DrawableTransitionOptions.withCrossFade())
            }
            visibility = when {
                movie.poster.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieTitle.text = movie.title

        binding.tvMovieRating.text = movie.rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"

        binding.tvMovieQuality.apply {
            text = movie.quality
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieReleased.apply {
            text = movie.released?.format("yyyy")
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieRuntime.apply {
            text = movie.runtime?.let {
                val hours = it / 60
                val minutes = it % 60
                when {
                    hours > 0 -> context.getString(
                        R.string.movie_runtime_hours_minutes,
                        hours,
                        minutes
                    )
                    else -> context.getString(R.string.movie_runtime_minutes, minutes)
                }
            }
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieGenres.apply {
            text = movie.genres.joinToString(", ") { it.name }
            visibility = when {
                movie.genres.isEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieOverview.text = movie.overview

        binding.btnMovieWatchNow.apply {
            setOnClickListener {
                checkProviderAndRun {
                    findNavController().navigate(MovieTvFragmentDirections.actionMovieToPlayer(
                        id = movie.id,
                        title = movie.title,
                        subtitle = movie.released?.format("yyyy") ?: "",
                        videoType = Video.Type.Movie(id = movie.id, title = movie.title, releaseDate = movie.released?.format("yyyy-MM-dd") ?: "", poster = movie.poster ?: movie.banner ?: "", imdbId = movie.imdbId),
                    ))
                }
            }
        }

        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.btnMovieTrailer.apply {
            val trailer = movie.trailer
            setOnClickListener {
                if (trailer != null) handleTrailerClick(trailer, "MovieTv")
            }
            visibility = if (trailer != null) View.VISIBLE else View.GONE
        }

        binding.btnMovieFavorite.apply {

            fun Boolean.drawable() = when (this) {
                true -> R.drawable.ic_favorite_enable
                false -> R.drawable.ic_favorite_disable
            }

            setOnClickListener {
                checkProviderAndRun {
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.movieDao()
                        val current = dao.getById(movie.id)?.isFavorite ?: false
                        val newValue = !current
                        val resolvedMovie = ArtworkRepair.resolveMovieForFavorite(context, movie, newValue)

                        dao.upsertFavorite(resolvedMovie, newValue)

                        withContext(Dispatchers.Main) {
                            movie.poster = resolvedMovie.poster
                            movie.banner = resolvedMovie.banner
                            movie.isFavorite = newValue
                            setImageDrawable(
                                ContextCompat.getDrawable(context, newValue.drawable())
                            )
                        }
                    }
                }
            }

            setImageDrawable(
                ContextCompat.getDrawable(context, movie.isFavorite.drawable())
            )
        }
    }

    private fun displayCastMobile(binding: ContentMovieCastMobileBinding) {
        binding.rvMovieCast.apply {
            adapter = AppAdapter().apply {
                submitList(movie.cast.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
                })
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(20.dp(context)))
            }
        }
    }

    private fun displayCastTv(binding: ContentMovieCastTvBinding) {
        binding.hgvMovieCast.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(movie.cast.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
                })
            }
            setItemSpacing(80)
        }
    }

    private fun displayDirectorsMobile(binding: ContentMovieDirectorsMobileBinding) {
        binding.rvMovieDirectors.text = movie.directors.joinToString (separator =", ") { it.name }
    }
    private fun displayDirectorsTv(binding: ContentMovieDirectorsTvBinding) {
        binding.rvMovieDirectors.text = movie.directors.joinToString (separator =", ") { it.name }
    }

    private fun displayRecommendationsMobile(binding: ContentMovieRecommendationsMobileBinding) {
        binding.rvMovieRecommendations.apply {
            adapter = AppAdapter().apply {
                submitList(movie.recommendations.onEach {
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

    private fun displayRecommendationsTv(binding: ContentMovieRecommendationsTvBinding) {
        binding.hgvMovieRecommendations.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(movie.recommendations.onEach {
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
