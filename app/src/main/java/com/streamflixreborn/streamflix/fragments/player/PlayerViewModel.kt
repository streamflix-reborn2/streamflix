package com.streamflixreborn.streamflix.fragments.player

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.EpisodeManager
import com.streamflixreborn.streamflix.utils.OpenSubtitles
import com.streamflixreborn.streamflix.utils.SubDL
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

class PlayerViewModel(
    videoType: Video.Type,
    id: String,
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.LoadingServers)
    val state: Flow<State> = _state

    private val _subtitleState = MutableSharedFlow<SubtitleState>()
    val subtitleState: SharedFlow<SubtitleState> = _subtitleState

    private val _playPreviousOrNextEpisode = MutableSharedFlow<Video.Type.Episode>()
    val playPreviousOrNextEpisode: SharedFlow<Video.Type.Episode> = _playPreviousOrNextEpisode

    private var videoLoadJob: Job? = null
    private var videoLoadKey: String? = null
    private var videoLoadGeneration: Long = 0L

    init {
        getServers(videoType, id)
        getSubtitles(videoType)
    }

    fun playEpisode(direction: Direction) {
        val hasEpisode = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.hasPreviousEpisode()
            Direction.NEXT -> EpisodeManager.hasNextEpisode()
        }
        if (!hasEpisode) return

        val ep = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.getPreviousEpisode()
            Direction.NEXT -> EpisodeManager.getNextEpisode()
        } ?: return

        val nextEpisode = Video.Type.Episode(
            id = ep.id,
            number = ep.number,
            title = ep.title,
            poster = ep.poster,
            overview = ep.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = ep.tvShow.id,
                title = ep.tvShow.title,
                poster = ep.tvShow.poster,
                banner = ep.tvShow.banner,
                releaseDate = ep.tvShow.releaseDate,
                imdbId = ep.tvShow.imdbId,
            ),
            season = Video.Type.Episode.Season(
                number = ep.season.number,
                title = ep.season.title,
            ),
        )

        playEpisode(nextEpisode)
        viewModelScope.launch {
            _playPreviousOrNextEpisode.emit(nextEpisode)
        }
    }

    enum class Direction { PREVIOUS, NEXT }

    fun playPreviousEpisode() = playEpisode(Direction.PREVIOUS)
    fun playNextEpisode() = playEpisode(Direction.NEXT)

    fun autoplayNextEpisode() {
        if (UserPreferences.autoplay) playEpisode(Direction.NEXT)
    }

    fun playEpisode(episode: Video.Type.Episode) {
        cancelVideoLoad()
        getServers(episode, episode.id)
        getSubtitles(episode)
    }

    private fun getServers(videoType: Video.Type, id: String) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Starting server lookup for ID: $id")
        lastVideoType = videoType
        lastId = id
        _state.emit(State.LoadingServers)

        val provider = UserPreferences.currentProvider
        if (provider == null) {
            _state.emit(State.FailedLoadingServers(Exception("No active provider")))
            return@launch
        }

        try {
            val servers = provider.getServers(id, videoType)
            if (servers.isEmpty()) throw Exception("No servers found")

            Log.i("StreamFlixES", "[SERVERS LIST] -> Provider: ${provider.name}")
            Log.i(
                "StreamFlixES",
                "[SERVERS LIST] -> Found ${servers.size} servers: ${servers.joinToString { it.name }}",
            )
            _state.emit(State.SuccessLoadingServers(servers))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Server lookup failed", e)
            _state.emit(State.FailedLoadingServers(e))
        }
    }

    fun getVideo(server: Video.Server) {
        val key = "${server.id}\u0000${server.name}\u0000${server.src}"
        val provider = UserPreferences.currentProvider

        val generation: Long
        synchronized(this) {
            val activeJob = videoLoadJob
            if (activeJob?.isActive == true && videoLoadKey == key) {
                Log.d(
                    "PlayerViewModel",
                    "Ignoring duplicate in-flight video request for ${server.name}",
                )
                return
            }

            activeJob?.cancel()
            videoLoadKey = key
            videoLoadGeneration += 1L
            generation = videoLoadGeneration
        }

        if (provider == null) {
            videoLoadJob = viewModelScope.launch {
                if (isCurrentVideoLoad(generation)) {
                    _state.emit(State.FailedLoadingVideo(Exception("No active provider"), server))
                }
            }
            return
        }

        val job = viewModelScope.launch(Dispatchers.IO) {
            Log.d(
                "PlayerViewModel",
                "Starting video extraction from ${server.name}; generation=$generation",
            )
            if (isCurrentVideoLoad(generation)) {
                _state.emit(State.LoadingVideo(server))
            }

            try {
                // Capture the provider at request start. A global-search/provider UI change that happens
                // while extraction is running must not cause the result to come from another provider.
                val video = provider.getVideo(server)
                if (video.source.isBlank()) throw Exception("No source found")
                if (!isCurrentVideoLoad(generation)) return@launch

                val currentProviderLang = provider.language
                val hasDefaultAlready = video.subtitles.any { it.default }
                if (!hasDefaultAlready && currentProviderLang != "es") {
                    if (!(video.useServerSubtitleSetting && UserPreferences.serverAutoSubtitlesDisabled)) {
                        video.subtitles
                            .firstOrNull { it.label.startsWith(UserPreferences.subtitleName ?: "") }
                            ?.default = true
                    }
                }

                Log.d(
                    "PlayerViewModel",
                    "Video extraction succeeded for ${server.name}; generation=$generation",
                )
                _state.emit(State.SuccessLoadingVideo(video, server))
            } catch (e: CancellationException) {
                Log.d(
                    "PlayerViewModel",
                    "Video extraction cancelled for ${server.name}; generation=$generation",
                )
                throw e
            } catch (e: Exception) {
                if (!isCurrentVideoLoad(generation)) return@launch
                Log.e(
                    "PlayerViewModel",
                    "Video extraction failed for ${server.name}; generation=$generation",
                    e,
                )
                _state.emit(State.FailedLoadingVideo(e, server))
            }
        }

        synchronized(this) {
            if (generation == videoLoadGeneration) {
                videoLoadJob = job
            } else {
                job.cancel()
            }
        }
    }

    @Synchronized
    private fun isCurrentVideoLoad(generation: Long): Boolean = generation == videoLoadGeneration

    @Synchronized
    private fun cancelVideoLoad() {
        videoLoadGeneration += 1L
        videoLoadKey = null
        videoLoadJob?.cancel()
        videoLoadJob = null
    }

    fun getSubtitles(videoType: Video.Type) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Starting subtitle lookup")
        _subtitleState.emit(SubtitleState.Loading)

        launch {
            try {
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> OpenSubtitles.search(
                        query = videoType.tvShow.title,
                        season = videoType.season.number,
                        episode = videoType.number,
                    )
                    is Video.Type.Movie -> OpenSubtitles.search(query = videoType.title)
                }.sortedWith(compareBy({ it.languageName }, { it.subDownloadsCnt }))
                _subtitleState.emit(SubtitleState.SuccessOpenSubtitles(subtitles))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "OpenSubtitles lookup failed", e)
                _subtitleState.emit(SubtitleState.FailedOpenSubtitles(e))
            }
        }

        launch {
            try {
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> SubDL.search(
                        filmName = videoType.tvShow.title,
                        seasonNumber = videoType.season.number,
                        episodeNumber = videoType.number,
                        type = "tv",
                    )
                    is Video.Type.Movie -> SubDL.search(
                        filmName = videoType.title,
                        type = "movie",
                    )
                }
                _subtitleState.emit(SubtitleState.SuccessSubDLSubtitles(subtitles))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "SubDL lookup failed", e)
                _subtitleState.emit(SubtitleState.FailedSubDLSubtitles(e))
            }
        }
    }

    fun downloadSubtitle(subtitle: OpenSubtitles.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        _subtitleState.emit(SubtitleState.DownloadingOpenSubtitle)
        try {
            val uri = OpenSubtitles.download(subtitle)
            _subtitleState.emit(SubtitleState.SuccessDownloadingOpenSubtitle(subtitle, uri))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "OpenSubtitles download failed", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingOpenSubtitle(e, subtitle))
        }
    }

    fun downloadSubDLSubtitle(subtitle: SubDL.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        _subtitleState.emit(SubtitleState.DownloadingSubDLSubtitle)
        try {
            val uri = SubDL.download(subtitle)
            _subtitleState.emit(SubtitleState.SuccessDownloadingSubDLSubtitle(subtitle, uri))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "SubDL download failed", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingSubDLSubtitle(e, subtitle))
        }
    }

    sealed class State {
        data object LoadingServers : State()
        data class SuccessLoadingServers(val servers: List<Video.Server>) : State()
        data class FailedLoadingServers(val error: Exception) : State()
        data class LoadingVideo(val server: Video.Server) : State()
        data class SuccessLoadingVideo(val video: Video, val server: Video.Server) : State()
        data class FailedLoadingVideo(val error: Exception, val server: Video.Server) : State()
    }

    sealed class SubtitleState {
        data object Loading : SubtitleState()
        data class SuccessOpenSubtitles(val subtitles: List<OpenSubtitles.Subtitle>) : SubtitleState()
        data class FailedOpenSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingOpenSubtitle : SubtitleState()
        data class SuccessDownloadingOpenSubtitle(val subtitle: OpenSubtitles.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingOpenSubtitle(
            val error: Exception,
            val subtitle: OpenSubtitles.Subtitle,
        ) : SubtitleState()

        data class SuccessSubDLSubtitles(val subtitles: List<SubDL.Subtitle>) : SubtitleState()
        data class FailedSubDLSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingSubDLSubtitle : SubtitleState()
        data class SuccessDownloadingSubDLSubtitle(val subtitle: SubDL.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingSubDLSubtitle(
            val error: Exception,
            val subtitle: SubDL.Subtitle,
        ) : SubtitleState()
    }

    private var lastVideoType: Video.Type? = null
    private var lastId: String? = null

    fun reloadServersAfterBypass() {
        val type = lastVideoType ?: return
        val id = lastId ?: return
        cancelVideoLoad()
        getServers(type, id)
    }

    override fun onCleared() {
        cancelVideoLoad()
        super.onCleared()
    }
}
