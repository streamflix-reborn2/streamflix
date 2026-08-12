package com.streamflixreborn.streamflix.fragments.player

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.CookieManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.SubtitleView
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.fragments.player.settings.PlayerSettingsView
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.ContentExoControllerTvBinding
import com.streamflixreborn.streamflix.databinding.FragmentPlayerTvBinding
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.models.WatchItem
import com.streamflixreborn.streamflix.providers.SerienStreamProvider
import com.streamflixreborn.streamflix.sync.CloudSyncHooks
import com.streamflixreborn.streamflix.ui.PlayerTvView
import com.streamflixreborn.streamflix.utils.SubtitleOffsetRenderersFactory
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.EpisodeManager
import com.streamflixreborn.streamflix.utils.MediaServer
import com.streamflixreborn.streamflix.utils.PlayerGestureHelper
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.UserDataCache
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.getFileName
import com.streamflixreborn.streamflix.utils.next
import com.streamflixreborn.streamflix.utils.plus
import com.streamflixreborn.streamflix.utils.setMediaServerId
import com.streamflixreborn.streamflix.utils.setMediaServers
import com.streamflixreborn.streamflix.utils.toSubtitleMimeType
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.internal.userAgent
import java.util.Calendar
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import java.util.Base64
import java.io.File
import java.io.FileOutputStream
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.streamflixreborn.streamflix.utils.BypassWebSocketServer
import com.streamflixreborn.streamflix.utils.BypassWebSocketEndpointHelper
import com.streamflixreborn.streamflix.utils.QrUtils
import com.streamflixreborn.streamflix.utils.UserDataCache.toEpisode
import com.streamflixreborn.streamflix.utils.UserDataCache.toMovie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import com.streamflixreborn.streamflix.extractors.TokenManager

class PlayerTvFragment : Fragment() {
    companion object {
        private const val NEXT_EPISODE_PREFETCH_THRESHOLD_MS = 60_000L
        private const val NEXT_EPISODE_OVERLAY_MIN_THRESHOLD_MS = 30_000L
        private const val NEXT_EPISODE_OVERLAY_ALPHA_UNFOCUSED = 0.72f
        private const val NEXT_EPISODE_OVERLAY_ALPHA_FOCUSED = 0.96f
    }

    private data class BypassSession(
        val token: String,
        val serverUrl: String,
        val bypassUrl: String,
    )

    private var _binding: FragmentPlayerTvBinding? = null
    private val binding get() = _binding!!
    private var isSetupDone = false

    private val PlayerControlView.binding
        get() = ContentExoControllerTvBinding.bind(this.findViewById(R.id.cl_exo_controller))

    private val args by navArgs<PlayerTvFragmentArgs>()
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { PlayerViewModel(args.videoType, args.id) }

    private lateinit var player: ExoPlayer
    private lateinit var httpDataSource: HttpDataSource.Factory
    private lateinit var dataSourceFactory: DataSource.Factory
    private lateinit var mediaSession: MediaSession
    private lateinit var progressHandler: android.os.Handler
    private lateinit var progressRunnable: Runnable
    private lateinit var gestureHelper: PlayerGestureHelper

    private var servers = listOf<Video.Server>()
    private var zoomToast: Toast? = null

    private var currentVideo: Video? = null
    private var currentServer: Video.Server? = null
    private var waitingForBypass = false
    private var bypassDone = false
    private var activeBypassSession: BypassSession? = null
    private var qrDialog: androidx.appcompat.app.AlertDialog? = null
    private var wsServer: BypassWebSocketServer? = null
    private var nextEpisodePrefetchTargetId: String? = null
    private var nextEpisodePrefetchJob: Job? = null
    private var nextEpisodeOverlayDismissed = false
    private val chooserReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val clickedComponent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(
                        Intent.EXTRA_CHOSEN_COMPONENT,
                        android.content.ComponentName::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
                }
                Log.i(
                    "ExternalPlayer",
                    "TV - App selezionata: ${clickedComponent?.packageName ?: "Sconosciuta"}"
                )
            }
        }
    }

    private val pickLocalSubtitle = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        val fileName = uri.getFileName(requireContext()) ?: uri.toString()

        val currentPosition = player.currentPosition
        val currentSubtitleConfigurations =
            player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                MediaItem.SubtitleConfiguration.Builder(it.uri)
                    .setMimeType(it.mimeType)
                    .setLabel(it.label)
                    .setLanguage(it.language)
                    .setSelectionFlags(0)
                    .build()
            } ?: listOf()
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(player.currentMediaItem?.localConfiguration?.uri)
                .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                .setSubtitleConfigurations(
                    currentSubtitleConfigurations
                            + MediaItem.SubtitleConfiguration.Builder(uri)
                        .setMimeType(fileName.toSubtitleMimeType())
                        .setLabel(fileName)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
                .setMediaMetadata(player.mediaMetadata)
                .build()
        )
        player.seekTo(currentPosition)
        player.play()
    }

    override fun onResume() {
        super.onResume()
        if (!isSetupDone) {
            requireActivity().requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            val window = requireActivity().window
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            isSetupDone = true
        }

        try {
            val filter = IntentFilter("ACTION_PLAYER_CHOSEN_TV")
            ContextCompat.registerReceiver(
                requireContext(),
                chooserReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (ignored: Exception) {}
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializePlayer(false)
        initializeVideo()
        binding.pvPlayer.onMediaPreviousClicked = ::handleMediaPrevious
        binding.pvPlayer.onMediaNextClicked = ::handleMediaNext
        gestureHelper = PlayerGestureHelper(
            requireContext(),
            binding.pvPlayer,
            binding.llBrightness,
            binding.pbBrightness,
            binding.tvBrightnessPercentage,
            binding.llVolume,
            binding.pbVolume,
            binding.tvVolumePercentage
        )

        // Stato Video
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.CREATED).collect { state ->
                when (state) {
                    PlayerViewModel.State.LoadingServers -> {}
                    is PlayerViewModel.State.SuccessLoadingServers -> {
                        servers = state.servers

                        val sToServer = servers.firstOrNull {
                            isSerienStreamBypassUrl(it.id)
                        }
                        if (sToServer != null && !waitingForBypass && !bypassDone) {
                            waitingForBypass = true

                            val bypassUrl = buildSerienStreamBypassUrl()
                            if (bypassUrl.isNullOrBlank()) {
                                waitingForBypass = false
                                Toast.makeText(
                                    requireContext(),
                                    "Unable to prepare TV bypass page.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@collect
                            }

                            val session = BypassSession(
                                token = UUID.randomUUID().toString(),
                                serverUrl = sToServer.id,
                                bypassUrl = bypassUrl,
                            )
                            activeBypassSession = session

                            val actualPort = startWebSocketServer()
                            if (actualPort == -1) {
                                clearBypassSession()
                                Toast.makeText(
                                    requireContext(),
                                    "Unable to start TV bypass. Please try again.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@collect
                            }

                            val wsUrl = BypassWebSocketEndpointHelper.getAdvertisedWsUrl(actualPort)
                                ?: return@collect

                            val qrContent = "streamflix://resolve?ws=${Uri.encode(wsUrl)}&token=${Uri.encode(session.token)}"

                            wsServer?.registerSession(
                                session.token,
                                JSONObject()
                                    .put("url", session.bypassUrl)
                                    .toString()
                            )
                            requireActivity().runOnUiThread {
                                showQrDialog(qrContent)
                                Log.d("Bypass", "Advertised WS URL: $wsUrl")
                            }

                            return@collect
                        }



                        val providerName = UserPreferences.currentProvider?.name ?: ""
                        val isTmdb = providerName.contains("TMDb", ignoreCase = true)
                        val isAD = providerName.contains("AfterDark", ignoreCase = true)

                        if (servers.isEmpty()) {
                            val message = if (isTmdb || isAD) {
                                val langCode = providerName.substringAfter("(").substringBefore(")")
                                val locale = Locale.forLanguageTag(langCode)
                                val langDisplayName = locale.getDisplayLanguage(Locale.getDefault())
                                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

                                if (isTmdb) getString(
                                    R.string.player_not_available_lang_message,
                                    langDisplayName
                                )
                                else getString(R.string.player_retry_later_message)
                            } else {
                                "No servers found for this content."
                            }
                            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                            findNavController().navigateUp()
                            return@collect
                        }

                        player.playlistMetadata = MediaMetadata.Builder()
                            .setTitle(state.toString())
                            .setMediaServers(state.servers.map {
                                MediaServer(
                                    id = it.id,
                                    name = it.name,
                                )
                            })
                            .build()
                        binding.settings.setOnServerSelectedListener { server ->
                            viewModel.getVideo(state.servers.find { server.id == it.id }!!)
                        }
                        val preferredServer = state.servers.firstOrNull {
                            it.name.equals(args.preferredServerName, ignoreCase = true)
                        }
                        viewModel.getVideo(preferredServer ?: state.servers.first())

                    }
                        is PlayerViewModel.State.FailedLoadingServers -> {
                            Toast.makeText(
                                requireContext(),
                                state.error.message ?: "",
                                Toast.LENGTH_LONG
                            ).show()
                            findNavController().navigateUp()
                        }

                        is PlayerViewModel.State.LoadingVideo -> {
                            player.setMediaItem(
                                MediaItem.Builder()
                                    .setUri("".toUri())
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setMediaServerId(state.server.id)
                                            .build()
                                    )
                                    .build()
                            )
                        }

                        is PlayerViewModel.State.SuccessLoadingVideo -> {
                            PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)
                            PlayerSettingsView.Settings.SoftwareDecoder.init(false)
                            displayVideo(state.video, state.server)
                        }

                        is PlayerViewModel.State.FailedLoadingVideo -> {
                            val nextServer = servers.getOrNull(servers.indexOf(state.server) + 1)
                            if (nextServer != null) {
                                viewModel.getVideo(nextServer)
                            } else {
                                val providerName = UserPreferences.currentProvider?.name ?: ""
                                val isTmdb = providerName.contains("TMDb", ignoreCase = true)
                                val isAD = providerName.contains("AfterDark", ignoreCase = true)

                                val message = if (isTmdb || isAD) {
                                    val langCode =
                                        providerName.substringAfter("(").substringBefore(")")
                                    val locale = Locale.forLanguageTag(langCode)
                                    val langDisplayName =
                                        locale.getDisplayLanguage(Locale.getDefault())
                                            .replaceFirstChar {
                                                if (it.isLowerCase()) it.titlecase(
                                                    Locale.getDefault()
                                                ) else it.toString()
                                            }

                                    if (isTmdb) getString(
                                        R.string.player_not_available_lang_message,
                                        langDisplayName
                                    )
                                    else getString(R.string.player_retry_later_message)
                                } else {
                                    "All servers failed to load the video."
                                }

                                Toast.makeText(
                                    requireContext(),
                                    message,
                                    Toast.LENGTH_LONG
                                ).show()
                                findNavController().navigateUp()
                            }
                        }
                    }
                }
            }

            // Stato Sottotitoli
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.subtitleState.flowWithLifecycle(lifecycle, Lifecycle.State.CREATED)
                    .collect { state ->
                        when (state) {
                            PlayerViewModel.SubtitleState.Loading -> {}
                            is PlayerViewModel.SubtitleState.SuccessOpenSubtitles -> {
                                binding.settings.openSubtitles = state.subtitles
                            }

                            is PlayerViewModel.SubtitleState.FailedOpenSubtitles -> {}

                            PlayerViewModel.SubtitleState.DownloadingOpenSubtitle -> {}
                            is PlayerViewModel.SubtitleState.SuccessDownloadingOpenSubtitle -> {
                                val fileName =
                                    state.uri.getFileName(requireContext()) ?: state.uri.toString()
                                val currentPosition = player.currentPosition
                                val currentSubtitleConfigurations =
                                    player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                                        MediaItem.SubtitleConfiguration.Builder(it.uri)
                                            .setMimeType(it.mimeType)
                                            .setLabel(it.label)
                                            .setLanguage(it.language)
                                            .setSelectionFlags(0)
                                            .build()
                                    } ?: listOf()
                                player.setMediaItem(
                                    MediaItem.Builder()
                                        .setUri(player.currentMediaItem?.localConfiguration?.uri)
                                        .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                                        .setSubtitleConfigurations(
                                            currentSubtitleConfigurations
                                                    + MediaItem.SubtitleConfiguration.Builder(state.uri)
                                                .setMimeType(fileName.toSubtitleMimeType())
                                                .setLabel(fileName)
                                                .setLanguage(state.subtitle.languageName)
                                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                                .build()
                                        )
                                        .setMediaMetadata(player.mediaMetadata)
                                        .build()
                                )
                                UserPreferences.subtitleName =
                                    (state.subtitle.languageName ?: fileName).substringBefore(" ")
                                player.seekTo(currentPosition)
                                player.play()
                            }

                            is PlayerViewModel.SubtitleState.FailedDownloadingOpenSubtitle -> {
                                Toast.makeText(
                                    requireContext(),
                                    "${state.subtitle.subFileName}: ${state.error.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }

                            is PlayerViewModel.SubtitleState.SuccessSubDLSubtitles -> {
                                binding.settings.subDLSubtitles = state.subtitles
                            }

                            is PlayerViewModel.SubtitleState.FailedSubDLSubtitles -> {}

                            PlayerViewModel.SubtitleState.DownloadingSubDLSubtitle -> {}
                            is PlayerViewModel.SubtitleState.SuccessDownloadingSubDLSubtitle -> {
                                val fileName =
                                    state.uri.getFileName(requireContext()) ?: state.uri.toString()
                                val currentPosition = player.currentPosition
                                val currentSubtitleConfigurations =
                                    player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                                        MediaItem.SubtitleConfiguration.Builder(it.uri)
                                            .setMimeType(it.mimeType)
                                            .setLabel(it.label)
                                            .setLanguage(it.language)
                                            .setSelectionFlags(0)
                                            .build()
                                    } ?: listOf()
                                player.setMediaItem(
                                    MediaItem.Builder()
                                        .setUri(player.currentMediaItem?.localConfiguration?.uri)
                                        .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                                        .setSubtitleConfigurations(
                                            currentSubtitleConfigurations
                                                    + MediaItem.SubtitleConfiguration.Builder(state.uri)
                                                .setMimeType(fileName.toSubtitleMimeType())
                                                .setLabel(
                                                    state.subtitle.releaseName
                                                        ?: state.subtitle.name ?: fileName
                                                )
                                                .setLanguage(
                                                    state.subtitle.lang ?: state.subtitle.language
                                                    ?: "Unknown"
                                                )
                                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                                .build()
                                        )
                                        .setMediaMetadata(player.mediaMetadata)
                                        .build()
                                )
                                UserPreferences.subtitleName =
                                    (state.subtitle.releaseName ?: state.subtitle.name
                                    ?: fileName).substringBefore(" ")
                                player.seekTo(currentPosition)
                                player.play()
                            }

                            is PlayerViewModel.SubtitleState.FailedDownloadingSubDLSubtitle -> {
                                Toast.makeText(
                                    requireContext(),
                                    "${state.subtitle.name}: ${state.error.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
            }

            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.playPreviousOrNextEpisode.collect { nextEpisode ->
                        releasePlayer()
                        isSetupDone = false

                        val args = Bundle().apply {
                            putString("id", nextEpisode.id)
                            putSerializable("videoType", nextEpisode)
                            putString("title", nextEpisode.tvShow.title)
                            putString(
                                "subtitle",
                                "S${nextEpisode.season.number} E${nextEpisode.number}  •  ${nextEpisode.title}"
                            )
                            putString("preferredServerName", currentServer?.name)
                        }

                        hideNextEpisodeOverlay()
                        findNavController().navigate(
                            R.id.player,
                            args,
                            NavOptions.Builder()
                                .setPopUpTo(
                                    findNavController().currentDestination?.id ?: return@collect,
                                    true
                                )
                                .setLaunchSingleTop(false)
                                .build()
                        )
                    }
                }
            }


        }

    override fun onPause() {
        super.onPause()

        if (::player.isInitialized) {
            try {
                player.pause()
            } catch (e: Exception) {
                Log.w("Player", "pause() ignored, player already released")
            }
        }

        stopProgressHandler()
        hideNextEpisodeOverlay()
    }

        override fun onDestroyView() {
            super.onDestroyView()
            nextEpisodePrefetchJob?.cancel()
            clearBypassSession(dismissDialog = true)
            releasePlayer()
            try {
                requireContext().unregisterReceiver(chooserReceiver)
            } catch (ignored: Exception) {
            }
            _binding = null
            isSetupDone = false
        }

    fun onBackPressed(): Boolean = when {


        (binding.pvPlayer as? PlayerTvView)?.isManualZoomEnabled == true -> {
            (binding.pvPlayer as? PlayerTvView)?.exitManualZoomMode()
            true
        }

        binding.settings.isVisible -> {
            binding.settings.onBackPressed()
        }

        binding.pvPlayer.controller.isVisible -> {
            binding.pvPlayer.hideController()
            true
        }

        else -> false
    }

    private fun handleMediaPrevious(): Boolean {
        return when (args.videoType) {
            is Video.Type.Episode -> {
                if (!EpisodeManager.hasPreviousEpisode()) return false
                viewModel.playPreviousEpisode()
                true
            }
            is Video.Type.Movie -> false
        }
    }

    private fun handleMediaNext(): Boolean {
        return when (args.videoType) {
            is Video.Type.Episode -> {
                playNextEpisodeAcrossSeasons()
                true
            }
            is Video.Type.Movie -> false
        }
    }

    private fun refreshEpisodeNavigation(type: Video.Type.Episode) {
        lifecycleScope.launch(Dispatchers.IO) {
            EpisodeManager.ensureNextEpisodeAvailable(type, database)
            withContext(Dispatchers.Main) {
                setupEpisodeNavigationButtons()
            }
        }
    }

    private fun playNextEpisodeAcrossSeasons(autoplay: Boolean = false) {
        val type = args.videoType as? Video.Type.Episode ?: return

        lifecycleScope.launch {
            val hasNextEpisode = withContext(Dispatchers.IO) {
                EpisodeManager.ensureNextEpisodeAvailable(type, database)
            }

            setupEpisodeNavigationButtons()

            if (!hasNextEpisode) return@launch
            if (autoplay && !UserPreferences.autoplay) return@launch

            viewModel.playNextEpisode()
        }
    }


        private fun updatePlayerScale() {
            val videoSurfaceView = binding.pvPlayer.videoSurfaceView
            val playerResize = UserPreferences.playerResize

            // Let PlayerView handle aspect ratio changes via resizeMode. Manual scale transforms on the
            // underlying surface can leave stale geometry behind after a quality switch, which is what
            // causes smaller variants to render in the top-left corner.
            binding.pvPlayer.resizeMode = playerResize.resizeMode

            videoSurfaceView?.apply {
                scaleX = 1f
                scaleY = 1f
                translationX = 0f
                translationY = 0f
                pivotX = width / 2f
                pivotY = height / 2f

                (layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                    if (
                        params.width != FrameLayout.LayoutParams.MATCH_PARENT ||
                        params.height != FrameLayout.LayoutParams.MATCH_PARENT ||
                        params.gravity != Gravity.CENTER
                    ) {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER
                        )
                    }
                }

                requestLayout()
            }
            binding.pvPlayer.requestLayout()
        }

        private fun reloadCurrentVideoForQualityChange() {
            val video = currentVideo ?: return
            val server = currentServer ?: return
            val resumePosition = player.currentPosition
            val shouldPlay = player.isPlaying || player.playWhenReady

            initializePlayer(currentExtraBuffering, currentSoftwareDecoder)
            player.playlistMetadata = MediaMetadata.Builder()
                .setTitle(resolvePlayerTitle())
                .setMediaServers(servers.map {
                    MediaServer(
                        id = it.id,
                        name = it.name,
                    )
                })
                .build()

            displayVideo(
                video = video,
                server = server,
                startPositionMs = resumePosition,
                shouldPlay = shouldPlay,
            )
        }

        private fun initializeVideo() {
            when (val type = args.videoType) {
                is Video.Type.Episode -> {
                    nextEpisodeOverlayDismissed = false
                    nextEpisodePrefetchTargetId = null

                    if (EpisodeManager.listIsEmpty(type)) {
                        EpisodeManager.clearEpisodes()
                        lifecycleScope.launch(Dispatchers.IO) {
                            EpisodeManager.addEpisodesFromDb(type, database)
                            withContext(Dispatchers.Main) {
                                EpisodeManager.setCurrentEpisode(type)
                                updatePlayerHeader(type)
                                setupEpisodeNavigationButtons()
                                refreshEpisodeNavigation(type)
                            }
                        }
                    } else {
                        EpisodeManager.setCurrentEpisode(type)
                        setupEpisodeNavigationButtons()
                        refreshEpisodeNavigation(type)
                    }
                }

                is Video.Type.Movie -> {
                    nextEpisodeOverlayDismissed = false
                    nextEpisodePrefetchTargetId = null
                    EpisodeManager.clearEpisodes()
                    hideNextEpisodeOverlay()
                }
            }
            setupEpisodeNavigationButtons()
            binding.pvPlayer.resizeMode = UserPreferences.playerResize.resizeMode
            binding.pvPlayer.subtitleView?.apply {
                setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * UserPreferences.captionTextSize)
                setStyle(UserPreferences.captionStyle)
                setPadding(0, 0, 0, UserPreferences.captionMargin.dp(context))
            }
            binding.settings.setOnExtraBufferingSelectedListener {
                displayVideo(
                    currentVideo ?: return@setOnExtraBufferingSelectedListener,
                    currentServer ?: return@setOnExtraBufferingSelectedListener
                )
            }
            binding.settings.setOnSoftwareDecoderSelectedListener { useSoftware ->
                currentSoftwareDecoder = useSoftware
                displayVideo(
                    currentVideo ?: return@setOnSoftwareDecoderSelectedListener,
                    currentServer ?: return@setOnSoftwareDecoderSelectedListener
                )
            }

            updatePlayerHeader()

            binding.pvPlayer.controller.binding.btnExoExternalPlayer.setOnClickListener {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.player_external_player_error_video),
                    Toast.LENGTH_SHORT
                ).show()
            }

            binding.pvPlayer.controller.binding.exoReplay.setOnClickListener {
                player.seekTo(0)
            }

            binding.pvPlayer.controller.binding.exoProgress.setKeyTimeIncrement(10_000)

            binding.pvPlayer.controller.binding.btnExoAspectRatio.setOnClickListener {
                val newResize = UserPreferences.playerResize.next()
                zoomToast?.cancel()
                zoomToast =
                    Toast.makeText(requireContext(), newResize.stringRes, Toast.LENGTH_SHORT)
                zoomToast?.show()

                UserPreferences.playerResize = newResize
                binding.pvPlayer.controllerShowTimeoutMs = binding.pvPlayer.controllerShowTimeoutMs
                updatePlayerScale()
            }

            binding.pvPlayer.controller.binding.exoSettings.setOnClickListener {
                binding.pvPlayer.controllerShowTimeoutMs = binding.pvPlayer.controllerShowTimeoutMs
                binding.settings.show()
            }

            binding.pvPlayer.controller.binding.btnSkipIntro.setOnClickListener {
                player.seekTo(player.currentPosition + 85000)
                it.visibility = View.GONE
            }

            binding.btnNextEpisodeAction.setOnClickListener {
                hideNextEpisodeOverlay()
                playNextEpisodeAcrossSeasons()
            }
            binding.btnNextEpisodeDismiss.setOnClickListener {
                nextEpisodeOverlayDismissed = true
                hideNextEpisodeOverlay()
            }
            binding.btnNextEpisodeAction.setOnFocusChangeListener { _, hasFocus ->
                updateNextEpisodeOverlayAlpha(hasFocus || binding.btnNextEpisodeDismiss.hasFocus())
            }
            binding.btnNextEpisodeDismiss.setOnFocusChangeListener { _, hasFocus ->
                updateNextEpisodeOverlayAlpha(hasFocus || binding.btnNextEpisodeAction.hasFocus())
            }

            binding.settings.setOnLocalSubtitlesClickedListener {
                pickLocalSubtitle.launch(
                    arrayOf(
                        "text/plain",
                        "text/str",
                        "application/octet-stream",
                        MimeTypes.TEXT_UNKNOWN,
                        MimeTypes.TEXT_VTT,
                        MimeTypes.TEXT_SSA,
                        MimeTypes.APPLICATION_TTML,
                        MimeTypes.APPLICATION_MP4VTT,
                        MimeTypes.APPLICATION_SUBRIP,
                    )
                )
            }

            binding.settings.setOnOpenSubtitleSelectedListener { subtitle ->
                viewModel.downloadSubtitle(subtitle.openSubtitle)
            }
            binding.settings.setOnSubDLSubtitleSelectedListener { subtitle ->
                viewModel.downloadSubDLSubtitle(subtitle.subDLSubtitle)
            }
            binding.settings.setOnQualitySelectedListener {
                reloadCurrentVideoForQualityChange()
            }
            binding.settings.setOnExtraBufferingSelectedListener {
                displayVideo(
                    currentVideo ?: return@setOnExtraBufferingSelectedListener,
                    currentServer ?: return@setOnExtraBufferingSelectedListener
                )
            }
            binding.settings.onManualZoomClicked = {
                binding.settings.hide()
                binding.pvPlayer.hideController()
                (binding.pvPlayer as? PlayerTvView)?.enterManualZoomMode()
                binding.pvPlayer.requestFocus()
            }
        }

        fun setupEpisodeNavigationButtons() {
            val btnPrevious = binding.pvPlayer.controller.binding.btnCustomPrev
            val btnNext = binding.pvPlayer.controller.binding.btnCustomNext

            fun handleNavigationButton(
                button: ImageView,
                hasEpisode: () -> Boolean,
                playEpisode: () -> Unit
            ) {
                if (!hasEpisode()) {
                    button.visibility = View.GONE
                    return
                }

                button.visibility = View.VISIBLE
                button.setOnClickListener {
                    if (!hasEpisode()) return@setOnClickListener

                    val videoType = args.videoType
                    val watchItem: WatchItem? = when (videoType) {
                        is Video.Type.Movie -> database.movieDao().getById(videoType.id)
                        is Video.Type.Episode -> database.episodeDao().getById(videoType.id)
                    }

                    watchItem?.apply {
                        isWatched = false
                        watchedDate = null
                        watchHistory = WatchItem.WatchHistory(
                            lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                            lastPlaybackPositionMillis = player.currentPosition,
                            durationMillis = player.duration
                        )
                    }

                    when (videoType) {
                        is Video.Type.Movie -> {
                            val provider = UserPreferences.currentProvider ?: return@setOnClickListener
                            (watchItem as? Movie)?.let { database.movieDao().update(it) }
                            (watchItem as? Movie)?.let { UserDataCache.addMovieToContinueWatching(requireContext(), provider, it) }
                        }

                        is Video.Type.Episode -> {
                            val provider = UserPreferences.currentProvider ?: return@setOnClickListener
                            (watchItem as? Episode)?.let { episode ->
                                if (player.hasFinished()) {
                                    episode.isWatched = true
                                    episode.watchedDate = Calendar.getInstance()
                                    episode.watchHistory = null
                                    database.episodeDao().resetProgressionFromEpisode(videoType.id)
                                    UserDataCache.removeEpisodeFromContinueWatching(requireContext(), provider, episode.id)
                                }

                                database.episodeDao().update(episode)
                                if (!player.hasFinished()) {
                                    (watchItem as? Episode)?.let { UserDataCache.addEpisodeToContinueWatching(requireContext(), provider, it) }
                                }

                                episode.tvShow?.let { tvShow ->
                                    database.tvShowDao().getById(tvShow.id)
                                }?.let { tvShow ->

                                    val isWatchingValue = if (player.hasFinished()) {
                                        database.episodeDao().hasAnyWatchHistoryForTvShow(tvShow.id)
                                    } else {
                                        true
                                    }

                                    val updatedTvShow = tvShow.copy().apply {
                                        merge(tvShow)
                                        isWatching = isWatchingValue
                                    }
                                    database.tvShowDao().update(updatedTvShow)
                                    CloudSyncHooks.tvShow(
                                        requireContext(),
                                        provider,
                                        updatedTvShow,
                                    )
                                }
                            }
                        }
                    }

                    playEpisode()
                }
            }

            handleNavigationButton(
                btnPrevious,
                EpisodeManager::hasPreviousEpisode,
                viewModel::playPreviousEpisode
            )
            handleNavigationButton(
                btnNext,
                EpisodeManager::hasNextEpisode,
                ::playNextEpisodeAcrossSeasons
            )
        }

        private fun decodeBase64Uri(uri: String): String? {
            return try {
                val parts = uri.split(",")
                if (parts.size == 2 && parts[0].contains(";base64")) {
                    val base64Data = parts[1]
                    val decodedBytes = Base64.getDecoder().decode(base64Data)
                    String(decodedBytes, Charsets.UTF_8)
                } else {
                    null
                }
            } catch (ignored: Exception) {
                null
            }
        }

        private fun extractUrlFromPlaylist(playlist: String): String? {
            return try {
                val lines = playlist.lines().map { it.trim() }
                lines.firstOrNull { it.startsWith("http") }
                    ?: lines.firstNotNullOfOrNull { line ->
                        val regex = """URI=["'](http[^"']+)["']""".toRegex()
                        regex.find(line)?.groupValues?.get(1)
                    }
            } catch (ignored: Exception) {
                null
            }
        }

        private fun displayVideo(
            video: Video,
            server: Video.Server,
            startPositionMs: Long? = null,
            shouldPlay: Boolean = true,
        ) {
            currentVideo = video
            currentServer = server
            updatePlayerHeader()
            val extraBuffering = PlayerSettingsView.Settings.ExtraBuffering.isEnabled
            val softwareDecoder = PlayerSettingsView.Settings.SoftwareDecoder.isEnabled
            val needsReinit =
                extraBuffering != currentExtraBuffering || softwareDecoder != currentSoftwareDecoder
            if (needsReinit) {
                initializePlayer(extraBuffering, softwareDecoder)
                player.playlistMetadata = MediaMetadata.Builder()
                    .setTitle(resolvePlayerTitle())
                    .setMediaServers(servers.map {
                        MediaServer(
                            id = it.id,
                            name = it.name,
                        )
                    })
                    .build()
            }

            val currentPosition = startPositionMs ?: player.currentPosition

            httpDataSource.setDefaultRequestProperties(
                mapOf(
                    "User-Agent" to userAgent,
                ) + (video.headers ?: emptyMap())
            )
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(video.source.toUri())
                    .setMimeType(video.type)
                    .setSubtitleConfigurations(video.subtitles.map { subtitle ->
                        MediaItem.SubtitleConfiguration.Builder(subtitle.file.toUri())
                            .setMimeType(subtitle.file.toSubtitleMimeType())
                            .setLabel(subtitle.label)
                            .setSelectionFlags(if (subtitle.default) C.SELECTION_FLAG_DEFAULT else 0)
                            .build()
                    })
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setMediaServerId(server.id)
                            .build()
                    )
                    .build()
            )

            binding.pvPlayer.controller.binding.btnExoExternalPlayer.setOnClickListener {
                val videoTitle = when (val type = args.videoType) {
                    is Video.Type.Movie -> type.title
                    is Video.Type.Episode -> "${type.tvShow.title} • S${type.season.number} E${type.number}"
                }

                var sourceUri: Uri
                val mimeType = "video/*"

                val initialSource = video.source

                if (initialSource.startsWith("data:application/vnd.apple.mpegurl;base64,")) {
                    val playlistContent = decodeBase64Uri(initialSource)
                    val extractedUrl =
                        if (playlistContent != null) extractUrlFromPlaylist(playlistContent) else null

                    if (extractedUrl != null) {
                        sourceUri = extractedUrl.toUri()
                        Log.i("ExternalPlayer", "Link reale estratto TV: $sourceUri")
                    } else {
                        try {
                            val file = File(requireContext().cacheDir, "stream.m3u8")
                            FileOutputStream(file).use {
                                it.write(
                                    playlistContent?.toByteArray() ?: ByteArray(0)
                                )
                            }
                            sourceUri = FileProvider.getUriForFile(
                                requireContext(),
                                "${requireContext().packageName}.provider",
                                file
                            )
                        } catch (ignored: Exception) {
                            sourceUri = initialSource.toUri()
                        }
                    }
                } else {
                    sourceUri = initialSource.toUri()
                }

                Log.i("ExternalPlayer", "Avvio intent TV con URI: $sourceUri e MIME: $mimeType")

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(sourceUri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                    putExtra("title", videoTitle)
                    putExtra("position", player.currentPosition.toInt())
                    putExtra("return_result", true)

                    video.headers?.forEach { (key, value) ->
                        putExtra(key, value)
                    }

                    putExtra(
                        "extra_headers",
                        video.headers?.map { "${it.key}: ${it.value}" }?.toTypedArray()
                    )

                    if (video.headers != null) {
                        val headersArray =
                            video.headers.flatMap { listOf(it.key, it.value) }.toTypedArray()
                        putExtra("headers", headersArray)
                    }
                }

                try {
                    val receiverIntent = Intent("ACTION_PLAYER_CHOSEN_TV").apply {
                        setPackage(requireContext().packageName)
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        requireContext(), 0, receiverIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        startActivity(
                            Intent.createChooser(
                                intent,
                                getString(R.string.player_external_player_title),
                                pendingIntent.intentSender
                            )
                        )
                    } else {
                        startActivity(
                            Intent.createChooser(
                                intent,
                                getString(R.string.player_external_player_title)
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e("ExternalPlayer", "Errore selettore app TV", e)
                    startActivity(
                        Intent.createChooser(
                            intent,
                            getString(R.string.player_external_player_title)
                        )
                    )
                }
            }

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    super.onPlaybackStateChanged(playbackState)

                    if (playbackState == Player.STATE_READY) {
                        binding.pvPlayer.controller.binding.exoPlayPause.nextFocusDownId = -1
                        val videoFormat = player.videoFormat
                        updatePlayerScale()
                    }
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    super.onTracksChanged(tracks)
                    val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                    val videoTracks = videoGroups.sumOf { it.length }
                    val selectedHeights = buildList {
                        videoGroups.forEach { group ->
                            for (i in 0 until group.length) {
                                if (group.isTrackSelected(i)) {
                                    add(group.getTrackFormat(i).height)
                                }
                            }
                        }
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    super.onVideoSizeChanged(videoSize)
                    updatePlayerScale()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    binding.pvPlayer.keepScreenOn = isPlaying

                    if (isPlaying) {
                        recordRecentlyWatchedStart()
                        startProgressHandler()
                    } else {
                        stopProgressHandler()
                    }
                    val hasUri = player.currentMediaItem?.localConfiguration?.uri
                        ?.toString()?.isNotEmpty()
                        ?: false

                    if (!isPlaying && hasUri) {
                        val videoType = args.videoType
                        val watchItem: WatchItem? = when (videoType) {
                            is Video.Type.Movie -> database.movieDao().getById(videoType.id)
                            is Video.Type.Episode -> database.episodeDao().getById(videoType.id)
                        }

                        when {
                            player.hasStarted() && !player.hasFinished() -> {
                                watchItem?.isWatched = false
                                watchItem?.watchedDate = null
                                watchItem?.watchHistory = WatchItem.WatchHistory(
                                    lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                                    lastPlaybackPositionMillis = player.currentPosition,
                                    durationMillis = player.duration,
                                )
                            }

                            player.hasFinished() -> {
                                watchItem?.isWatched = true
                                watchItem?.watchedDate = Calendar.getInstance()
                                watchItem?.watchHistory = null


                            }
                        }

                        when (videoType) {
                            is Video.Type.Movie -> {
                                val provider = UserPreferences.currentProvider ?: return
                                (watchItem as? Movie)?.let {
                                    database.movieDao().update(it)
                                    UserDataCache.syncMovieToCache(requireContext(), provider, it)
                                }
                            }

                            is Video.Type.Episode -> {
                                val provider = UserPreferences.currentProvider ?: return
                                (watchItem as? Episode)?.let { episode ->
                                    if (player.hasFinished()) {
                                        database.episodeDao()
                                            .resetProgressionFromEpisode(videoType.id)
                                        UserDataCache.removeEpisodeFromContinueWatching(requireContext(), provider, episode.id)
                                        queueNextEpisodeForContinueWatching(provider)
                                    }
                                    database.episodeDao().update(episode)
                                    if (!player.hasFinished()) {
                                        UserDataCache.syncEpisodeToCache(requireContext(), provider, episode)
                                    }

                                    episode.tvShow?.let { tvShow ->
                                        database.tvShowDao().getById(tvShow.id)
                                    }?.let { tvShow ->
                                        val episodeDao = database.episodeDao()
                                        val isStillWatching =
                                            episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)

                                        val updatedTvShow = tvShow.copy().apply {
                                            merge(tvShow)
                                            isWatching =
                                                !player.hasReallyFinished() || isStillWatching
                                        }
                                        database.tvShowDao().update(updatedTvShow)
                                        CloudSyncHooks.tvShow(
                                            requireContext(),
                                            provider,
                                            updatedTvShow,
                                        )
                                    }
                                }
                            }

                        }
                        if (player.hasReallyFinished()) {
                            if (UserPreferences.autoplay) {
                                playNextEpisodeAcrossSeasons(autoplay = true)
                            }
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    super.onPlayerError(error)
                    Log.e("PlayerTvFragment", "onPlayerError: ", error)

                    val nextServer = servers.getOrNull(servers.indexOf(currentServer) + 1)
                    if (nextServer != null) {
                        Log.i("PlayerTvFragment", "Playback failed, trying next server: ${nextServer.name}")
                        viewModel.getVideo(nextServer)
                    }
                }
            })

            if (startPositionMs != null) {
                player.seekTo(startPositionMs)
            } else if (currentPosition == 0L) {
                val videoType = args.videoType
                val provider = UserPreferences.currentProvider
                
                val watchItem: WatchItem? = when (videoType) {
                    is Video.Type.Movie -> {
                        // Try cache first, then DB
                        var movie = if (provider != null) {
                            UserDataCache.read(requireContext(), provider)?.continueWatchingMovies
                                ?.find { it.id == videoType.id }?.toMovie()
                        } else null
                        movie ?: database.movieDao().getById(videoType.id)
                    }
                    is Video.Type.Episode -> {
                        // Try cache first, then DB
                        var episode = if (provider != null) {
                            UserDataCache.read(requireContext(), provider)?.continueWatchingEpisodes
                                ?.find { it.id == videoType.id }?.toEpisode()
                        } else null
                        episode ?: database.episodeDao().getById(videoType.id)
                    }
                }
                
                val lastPlaybackPositionMillis = watchItem?.watchHistory
                    ?.let { it.lastPlaybackPositionMillis - 10.seconds.inWholeMilliseconds }

                player.seekTo(lastPlaybackPositionMillis ?: 0)
            } else {
                player.seekTo(currentPosition)
            }

            player.prepare()
            player.playWhenReady = shouldPlay
        }


        private fun ExoPlayer.hasStarted(): Boolean {
            return (this.currentPosition > (this.duration * 0.005) || this.currentPosition > 20.seconds.inWholeMilliseconds)
        }

        private fun recordRecentlyWatchedStart() {
            val playedAtMillis = System.currentTimeMillis()
            when (val videoType = currentVideoTypeForUi()) {
                is Video.Type.Movie -> {
                    if (database.movieDao().markRecentlyWatched(videoType.id, playedAtMillis) == 0) {
                        database.movieDao().insert(
                            Movie(
                                id = videoType.id,
                                title = videoType.title,
                                released = videoType.releaseDate,
                                poster = videoType.poster,
                                imdbId = videoType.imdbId,
                            ).apply {
                                lastPlayedAtMillis = playedAtMillis
                            }
                        )
                    }
                }

                is Video.Type.Episode -> {
                    val storedTvShow = database.tvShowDao().getById(videoType.tvShow.id)
                        ?: TvShow(
                            id = videoType.tvShow.id,
                            title = videoType.tvShow.title,
                            released = videoType.tvShow.releaseDate,
                            poster = videoType.tvShow.poster,
                            banner = videoType.tvShow.banner,
                            imdbId = videoType.tvShow.imdbId,
                        ).apply {
                            lastPlayedAtMillis = playedAtMillis
                            lastPlayedEpisodeId = videoType.id
                            database.tvShowDao().insert(this)
                        }

                    if (database.episodeDao().getById(videoType.id) == null) {
                        database.episodeDao().insert(
                            Episode(
                                id = videoType.id,
                                number = videoType.number,
                                title = videoType.title,
                                poster = videoType.poster,
                                overview = videoType.overview,
                                tvShow = storedTvShow,
                                season = Season(
                                    number = videoType.season.number,
                                    title = videoType.season.title.orEmpty(),
                                ),
                            )
                        )
                    }
                    database.tvShowDao().markRecentlyWatched(
                        id = videoType.tvShow.id,
                        episodeId = videoType.id,
                        playedAtMillis = playedAtMillis,
                    )
                }
            }
        }

        private fun ExoPlayer.hasFinished(): Boolean {
            return (this.currentPosition > (this.duration * 0.90))
        }

        private fun ExoPlayer.hasReallyFinished(): Boolean {
            return this.duration > 0 &&
                    this.currentPosition >= (this.duration - UserPreferences.autoplayBuffer * 1000)
        }

        private fun currentVideoTypeForUi(): Video.Type = when (val type = args.videoType) {
            is Video.Type.Episode -> EpisodeManager.getCurrentEpisode()
                ?.takeIf { currentEpisode -> currentEpisode.id == type.id }
                ?: type
            is Video.Type.Movie -> type
        }

        private fun resolvePlayerTitle(videoType: Video.Type = currentVideoTypeForUi()): String {
            return when (videoType) {
                is Video.Type.Movie -> videoType.title
                is Video.Type.Episode -> videoType.tvShow.title.ifBlank { args.title }
            }
        }

        private fun resolvePlayerSubtitle(videoType: Video.Type = currentVideoTypeForUi()): String {
            return when (videoType) {
                is Video.Type.Movie -> args.subtitle
                is Video.Type.Episode -> {
                    val episodeTitle = videoType.title?.takeUnless { it.isBlank() } ?: args.subtitle
                    "S${videoType.season.number} E${videoType.number}  •  $episodeTitle"
                }
            }
        }

        private fun updatePlayerHeader(videoType: Video.Type = currentVideoTypeForUi()) {
            binding.pvPlayer.controller.binding.tvExoTitle.text = resolvePlayerTitle(videoType)
            binding.pvPlayer.controller.binding.tvExoSubtitle.text = resolvePlayerSubtitle(videoType)
        }

        private fun queueNextEpisodeForContinueWatching(provider: com.streamflixreborn.streamflix.providers.Provider) {
            val nextEpisode = EpisodeManager.peekNextEpisode() ?: return
            val episodeDao = database.episodeDao()
            val persistedNextEpisode = episodeDao.getById(nextEpisode.id)?.apply {
                isWatched = false
                watchedDate = null
                watchHistory = WatchItem.WatchHistory(
                    lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                    lastPlaybackPositionMillis = 0L,
                    durationMillis = 0L,
                )
            } ?: Episode(
                id = nextEpisode.id,
                number = nextEpisode.number,
                title = nextEpisode.title,
                poster = nextEpisode.poster,
                overview = nextEpisode.overview,
                tvShow = database.tvShowDao().getById(nextEpisode.tvShow.id) ?: TvShow(
                    id = nextEpisode.tvShow.id,
                    title = nextEpisode.tvShow.title,
                    poster = nextEpisode.tvShow.poster,
                    banner = nextEpisode.tvShow.banner,
                ),
                season = Season(
                    number = nextEpisode.season.number,
                    title = nextEpisode.season.title,
                ),
            ).apply {
                isWatched = false
                watchedDate = null
                watchHistory = WatchItem.WatchHistory(
                    lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                    lastPlaybackPositionMillis = 0L,
                    durationMillis = 0L,
                )
            }

            episodeDao.save(persistedNextEpisode)
            UserDataCache.syncEpisodeToCache(requireContext(), provider, persistedNextEpisode)
        }

        private fun startProgressHandler() {
            progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
            progressRunnable = Runnable {
                if (player.isPlaying) {
                    val show = player.currentPosition in 3000..120000
                    showSkipIntroButton(show)
                    updateNextEpisodeOverlay()
                }
                progressHandler.postDelayed(progressRunnable, 1000)
            }
            progressHandler.post(progressRunnable)
        }

        private fun stopProgressHandler() {
            if (::progressHandler.isInitialized) {
                progressHandler.removeCallbacks(progressRunnable)
            }
        }

        private fun updateNextEpisodeOverlay() {
            val currentEpisode = currentVideoTypeForUi() as? Video.Type.Episode ?: run {
                hideNextEpisodeOverlay()
                return
            }
            val duration = player.duration.takeIf { it > 0 } ?: run {
                hideNextEpisodeOverlay()
                return
            }
            val remainingMs = (duration - player.currentPosition).coerceAtLeast(0L)

            if (nextEpisodeOverlayDismissed) {
                hideNextEpisodeOverlay()
                return
            }

            if (remainingMs <= NEXT_EPISODE_PREFETCH_THRESHOLD_MS) {
                ensureNextEpisodePrepared(currentEpisode)
            }

            val nextEpisode = EpisodeManager.peekNextEpisode()
            val overlayThresholdMs = maxOf(
                NEXT_EPISODE_OVERLAY_MIN_THRESHOLD_MS,
                UserPreferences.autoplayBuffer * 1000L
            )
            if (nextEpisode == null || remainingMs == 0L || remainingMs > overlayThresholdMs) {
                hideNextEpisodeOverlay()
                return
            }

            showNextEpisodeOverlay(nextEpisode, remainingMs)
        }

        private fun ensureNextEpisodePrepared(currentEpisode: Video.Type.Episode) {
            if (EpisodeManager.peekNextEpisode() != null) return
            if (nextEpisodePrefetchTargetId == currentEpisode.id && nextEpisodePrefetchJob?.isActive == true) {
                return
            }

            nextEpisodePrefetchTargetId = currentEpisode.id
            nextEpisodePrefetchJob?.cancel()
            nextEpisodePrefetchJob = lifecycleScope.launch(Dispatchers.IO) {
                val loaded = EpisodeManager.ensureNextEpisodeAvailable(currentEpisode, database)
                withContext(Dispatchers.Main) {
                    if (!isAdded || _binding == null) return@withContext
                    setupEpisodeNavigationButtons()
                    if (loaded && player.isPlaying) {
                        updateNextEpisodeOverlay()
                    }
                }
            }
        }

        private fun showNextEpisodeOverlay(nextEpisode: Video.Type.Episode, remainingMs: Long) {
            updateNextEpisodeOverlayFocusBindings(true)
            binding.tvNextEpisodeMeta.text = getString(
                R.string.tv_show_item_season_number_episode_number,
                nextEpisode.season.number,
                nextEpisode.number
            )
            binding.tvNextEpisodeTitle.text = nextEpisode.title
                ?: getString(R.string.episode_number, nextEpisode.number)
            binding.tvNextEpisodeCountdown.text = if (UserPreferences.autoplay) {
                getString(
                    R.string.player_next_episode_autoplay_in,
                    ((remainingMs + 999L) / 1000L).toInt()
                )
            } else {
                getString(R.string.player_next_episode_ready)
            }

            Glide.with(this)
                .load(nextEpisode.poster ?: nextEpisode.tvShow.poster)
                .error(R.drawable.glide_fallback_cover)
                .fallback(R.drawable.glide_fallback_cover)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(binding.ivNextEpisodePoster)

            if (binding.layoutNextEpisodeOverlay.isGone) {
                val fadeIn = android.view.animation.AnimationUtils.loadAnimation(
                    requireContext(),
                    R.anim.fade_in
                )
                updateNextEpisodeOverlayAlpha(
                    binding.btnNextEpisodeAction.hasFocus() || binding.btnNextEpisodeDismiss.hasFocus()
                )
                binding.layoutNextEpisodeOverlay.startAnimation(fadeIn)
                binding.layoutNextEpisodeOverlay.isVisible = true
                binding.btnNextEpisodeAction.post {
                    if (_binding == null || !binding.layoutNextEpisodeOverlay.isVisible) return@post
                    binding.btnNextEpisodeAction.requestFocus()
                }
            }
        }

        private fun hideNextEpisodeOverlay() {
            if (_binding == null) return
            updateNextEpisodeOverlayFocusBindings(false)
            if (binding.layoutNextEpisodeOverlay.isVisible) {
                val fadeOut = android.view.animation.AnimationUtils.loadAnimation(
                    requireContext(),
                    R.anim.fade_out
                )
                binding.layoutNextEpisodeOverlay.startAnimation(fadeOut)
                binding.layoutNextEpisodeOverlay.isGone = true
            }
        }

        private fun updateNextEpisodeOverlayAlpha(hasFocus: Boolean) {
            if (_binding == null) return
            binding.layoutNextEpisodeOverlay.alpha =
                if (hasFocus) NEXT_EPISODE_OVERLAY_ALPHA_FOCUSED
                else NEXT_EPISODE_OVERLAY_ALPHA_UNFOCUSED
        }

        private fun updateNextEpisodeOverlayFocusBindings(overlayVisible: Boolean) {
            val controllerBinding = binding.pvPlayer.controller.binding
            val overlayActionId = binding.btnNextEpisodeAction.id
            val overlayDismissId = binding.btnNextEpisodeDismiss.id

            controllerBinding.exoSettings.nextFocusUpId = if (overlayVisible) overlayActionId else View.NO_ID
            controllerBinding.btnExoAspectRatio.nextFocusUpId = if (overlayVisible) overlayActionId else View.NO_ID
            controllerBinding.exoProgress.nextFocusUpId = View.NO_ID
            controllerBinding.btnCustomNext.nextFocusDownId = R.id.exo_progress
            controllerBinding.exoPlayPause.nextFocusDownId = R.id.exo_progress

            controllerBinding.btnSkipIntro.nextFocusLeftId = if (overlayVisible) overlayActionId else View.NO_ID
            controllerBinding.btnSkipIntro.nextFocusUpId = if (overlayVisible) overlayActionId else View.NO_ID
            controllerBinding.btnSkipIntro.nextFocusDownId = if (overlayVisible) overlayActionId else View.NO_ID

            binding.btnNextEpisodeAction.nextFocusLeftId = overlayDismissId
            binding.btnNextEpisodeAction.nextFocusRightId = overlayDismissId
            binding.btnNextEpisodeAction.nextFocusUpId = controllerBinding.exoPlayPause.id
            binding.btnNextEpisodeAction.nextFocusDownId =
                if (controllerBinding.btnSkipIntro.isVisible) controllerBinding.btnSkipIntro.id
                else controllerBinding.exoSettings.id

            binding.btnNextEpisodeDismiss.nextFocusLeftId = overlayActionId
            binding.btnNextEpisodeDismiss.nextFocusRightId = overlayActionId
            binding.btnNextEpisodeDismiss.nextFocusUpId = controllerBinding.exoPlayPause.id
            binding.btnNextEpisodeDismiss.nextFocusDownId =
                if (controllerBinding.btnSkipIntro.isVisible) controllerBinding.btnSkipIntro.id
                else controllerBinding.exoSettings.id
        }

        private fun showSkipIntroButton(show: Boolean) {
            val btnSkipIntro = binding.pvPlayer.controller.binding.btnSkipIntro
            if (show && btnSkipIntro.isGone) {
                val fadeIn = android.view.animation.AnimationUtils.loadAnimation(
                    requireContext(),
                    R.anim.fade_in
                )
                btnSkipIntro.startAnimation(fadeIn)
                btnSkipIntro.isVisible = true
                if (binding.layoutNextEpisodeOverlay.isVisible) {
                    updateNextEpisodeOverlayFocusBindings(true)
                }
            } else if (!show && btnSkipIntro.isVisible) {
                val fadeOut = android.view.animation.AnimationUtils.loadAnimation(
                    requireContext(),
                    R.anim.fade_out
                )
                btnSkipIntro.startAnimation(fadeOut)
                btnSkipIntro.isGone = true
                if (binding.layoutNextEpisodeOverlay.isVisible) {
                    updateNextEpisodeOverlayFocusBindings(true)
                }
            }
        }

        private var currentExtraBuffering = false
        private var currentSoftwareDecoder = false

        private fun buildPlayer(extraBuffering: Boolean): ExoPlayer {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    if (extraBuffering) 300_000 else DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .build()

            val renderersFactory = SubtitleOffsetRenderersFactory(requireContext()).apply {
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1 || currentSoftwareDecoder) {
                    setEnableDecoderFallback(true)
                    if (currentSoftwareDecoder) {
                        setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    }
                }
            }
            val baseBuilder = ExoPlayer.Builder(requireContext(), renderersFactory)

            return baseBuilder
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setLoadControl(loadControl)
                .build()
        }

        private fun initializePlayer(extraBuffering: Boolean, softwareDecoder: Boolean = currentSoftwareDecoder) {
            releasePlayer()
            currentExtraBuffering = extraBuffering
            currentSoftwareDecoder = softwareDecoder

            var tokenLogged = false
            val okHttpClient = OkHttpClient.Builder()
                .dns(DnsResolver.doh)
                .addInterceptor { chain ->
                    var request = chain.request()
                    
                    if (currentVideo?.maintainToken == true) {
                        val latestQuery = TokenManager.latestQuery
                        if (latestQuery != null) {
                            val origHttpUrl = request.url
                            val updatedHttpUrl = origHttpUrl.newBuilder().query(latestQuery).build()
                            request = request.newBuilder().url(updatedHttpUrl).build()
                            if (!tokenLogged) {
                                android.util.Log.d("TokenManager", "[TV-INTERCEPTOR] Token successfully injected (applied to all segments)")
                                tokenLogged = true
                            }
                        } else {
                            android.util.Log.w("TokenManager", "[TV-INTERCEPTOR] maintainToken=true but latestQuery is null! URL: ${request.url.host}")
                        }
                    }
                    
                    chain.proceed(request)
                }
                .build()
            httpDataSource = OkHttpDataSource.Factory(okHttpClient)

            dataSourceFactory = DefaultDataSource.Factory(requireContext(), httpDataSource)

            player = buildPlayer(extraBuffering).also { player ->
                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        true,
                    )

                    val lang = UserPreferences.currentProvider?.language?.substringBefore("-")
                    if (lang == "es") {
                        player.trackSelectionParameters =
                            player.trackSelectionParameters.buildUpon()
                                .setPreferredAudioLanguage("spa")
                                .build()
                    }

                    mediaSession = MediaSession.Builder(requireContext(), player)
                        .build()
                }

            binding.pvPlayer.player = player
            binding.settings.player = player
            binding.settings.subtitleView = binding.pvPlayer.subtitleView
            binding.settings.onSubtitlesClicked = {
                viewModel.getSubtitles(args.videoType)
            }
        }

        private fun releasePlayer() {
            stopProgressHandler()
            binding.pvPlayer.player = null
            binding.settings.player = null
            binding.settings.subtitleView = null
            if (::player.isInitialized) {
                player.release()
            }
            if (::mediaSession.isInitialized) {
                mediaSession.release()
            }
        }

    private fun showQrDialog(content: String) {
        val displayMetrics: DisplayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        val dialogWidth = (displayMetrics.widthPixels * 0.72f).toInt()
        val qrSize = minOf(
            (dialogWidth - (density * 64).toInt()).coerceAtLeast((density * 240).toInt()),
            (displayMetrics.heightPixels * 0.45f).toInt().coerceAtLeast((density * 240).toInt()),
        )
        val bitmap = QrUtils.generate(content, qrSize) ?: return

        val imageView = ImageView(requireContext()).apply {
            setImageBitmap(bitmap)
            setBackgroundColor(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }

        val instructionsView = TextView(requireContext()).apply {
            text = buildString {
                append("Solve captcha on phone")
                if (BypassWebSocketEndpointHelper.isProbablyEmulator()) {
                    append("\n\nEmulator note: set 'Bypass advertised host' in TV settings to your PC LAN IP and forward TCP 8081 to the emulator.")
                }
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
        }

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                (density * 24).toInt(),
                (density * 16).toInt(),
                (density * 24).toInt(),
                (density * 12).toInt(),
            )
        }
        container.addView(
            imageView,
            LinearLayout.LayoutParams(qrSize, qrSize)
        )
        container.addView(
            instructionsView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        )

        val scrollView = ScrollView(requireContext()).apply {
            isFillViewport = true
            addView(container)
        }

        qrDialog = androidx.appcompat.app.AlertDialog.Builder(requireActivity())
            .setTitle("Scan with phone")
            .setView(scrollView)
            .setCancelable(true)
            .setOnCancelListener {
                Log.d("Bypass", "QR dialog cancelled")
                clearBypassSession(dismissDialog = false)
            }
            .create()

        qrDialog?.show()
        qrDialog?.window?.setLayout(dialogWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun isSerienStreamBypassUrl(url: String): Boolean {
        return runCatching {
            Uri.parse(url).host.equals("serienstream.to", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun buildSerienStreamBypassUrl(): String? {
        val provider = UserPreferences.currentProvider ?: return null
        if (provider != SerienStreamProvider) return null

        val episodeId = when (val type = args.videoType) {
            is Video.Type.Episode -> type.id
            is Video.Type.Movie -> return null
        }

        return "${SerienStreamProvider.baseUrl}serie/$episodeId"
    }

    private fun startWebSocketServer(): Int {
        if (wsServer != null) return wsServer?.address?.port ?: 8081

        val ports = listOf(8081, 8082, 8887, 0)
        for (port in ports) {
            val server = BypassWebSocketServer(port) { token, cookies ->
                requireActivity().runOnUiThread {
                    Log.d("BypassWS", "DONE received for token: $token")
                    onBypassCompleted(token, cookies)
                }
            }
            wsServer = server
            try {
                server.start()
                if (server.awaitStart(5_000)) {
                    val actualPort = server.address.port
                    Log.d("BypassWS", "WebSocket server started on port $actualPort")
                    return actualPort
                } else {
                    val error = server.getStartError()
                    Log.e("BypassWS", "Server failed to start on port $port: ${error?.message}")
                    stopWebSocketServer()
                }
            } catch (e: Exception) {
                Log.e("BypassWS", "Failed to start on port $port", e)
                stopWebSocketServer()
            }
        }
        return -1
    }
    private fun stopWebSocketServer() {
        try {
            wsServer?.stop()
        } catch (_: Exception) {}
        wsServer = null
    }

    private fun clearBypassSession(
        dismissDialog: Boolean = true,
        resetBypassDone: Boolean = false,
    ) {
        activeBypassSession?.let { session ->
            wsServer?.clearSession(session.token)
        }
        activeBypassSession = null
        waitingForBypass = false
        if (resetBypassDone) {
            bypassDone = false
        }
        if (dismissDialog) {
            qrDialog?.dismiss()
        }
        qrDialog = null
        stopWebSocketServer()
    }


    private fun onBypassCompleted(token: String, cookies: String?) {
        val session = activeBypassSession
        if (session == null || session.token != token) {
            Log.w("BypassWS", "Ignoring bypass completion for stale token: $token")
            return
        }

        val extraBuffering = PlayerSettingsView.Settings.ExtraBuffering.isEnabled
        currentExtraBuffering = extraBuffering

        val okHttpClient = NetworkClient.default
        httpDataSource = OkHttpDataSource.Factory(okHttpClient)

        dataSourceFactory = DefaultDataSource.Factory(requireContext(), httpDataSource)

        player = buildPlayer(extraBuffering).also { player ->
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
            }

        // Bind new player to UI view
        binding.pvPlayer.player = player
        binding.settings.player = player
        binding.settings.subtitleView = binding.pvPlayer.subtitleView

        bypassDone = true
        waitingForBypass = false
        activeBypassSession = null

        clearBypassSession(dismissDialog = true)
        applyBypassCookies(session.serverUrl, cookies)

        lifecycleScope.launch {
            delay(300)

            // 🔴 restore episode context BEFORE reload
            when (val type = args.videoType) {
                is Video.Type.Episode -> {
                    EpisodeManager.setCurrentEpisode(type)
                }
                else -> {}
            }

            viewModel.reloadServersAfterBypass()
        }
    }

    private fun applyBypassCookies(url: String, cookieHeader: String?) {
        val cookies = cookieHeader?.trim().orEmpty()
        if (cookies.isBlank()) return

        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        val targets = linkedSetOf<String>().apply {
            if (url.isNotBlank()) add(url)
            if (host.isNotBlank()) {
                add("https://$host/")
                add("http://$host/")
            }
        }
        if (targets.isEmpty()) return

        val cookieManager = CookieManager.getInstance()
        cookies.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .forEach { cookie ->
                targets.forEach { target ->
                    cookieManager.setCookie(target, cookie)
                }
            }
        cookieManager.flush()
    }


    }
