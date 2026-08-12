package com.streamflixreborn.streamflix.fragments.player

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.CustomTabHelper
import com.streamflixreborn.streamflix.utils.EpisodeManager
import com.streamflixreborn.streamflix.utils.OpenSubtitles
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import com.streamflixreborn.streamflix.utils.SubDL

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
    private var activeServerDiscovery: Job? = null
    private var activeVideoResolution: Job? = null
    private val playbackRequestGate = PlaybackRequestSessionGate()
    private val tvViewReplay = TvPlaybackViewReplay<Video.Server>()
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
                imdbId = ep.tvShow.imdbId
            ),
            season = Video.Type.Episode.Season(
                number = ep.season.number,
                title = ep.season.title
            )
        )

        playEpisode(nextEpisode)

        viewModelScope.launch {
            _playPreviousOrNextEpisode.emit(nextEpisode)
        }
    }

    enum class Direction { PREVIOUS, NEXT }
    fun playPreviousEpisode() =
        playEpisode(Direction.PREVIOUS)

    fun playNextEpisode() =
        playEpisode(Direction.NEXT)

    fun autoplayNextEpisode() {
        if (UserPreferences.autoplay) {
            playEpisode(Direction.NEXT)
        }
    }
    fun playEpisode(episode: Video.Type.Episode) {
        getServers(episode, episode.id)
        getSubtitles(episode)
    }

    private fun getServers(videoType: Video.Type, id: String): Job {
        lastVideoType = videoType
        lastId = id
        val request = playbackRequestGate.beginDiscovery()
        activeVideoResolution?.cancel()
        activeServerDiscovery?.cancel()
        tvViewReplay.record(emptyList())
        playbackRequestGate.runIfCurrent(request) {
            _state.value = State.LoadingServers
        }

        return viewModelScope.launch(Dispatchers.IO) {
            Log.d("PlayerViewModel", "Inizio ricerca server per ID: $id")
            try {
                val provider = UserPreferences.currentProvider
                    ?: throw IllegalStateException("No provider selected")
                val servers = provider.getServers(id, videoType)
                ensureActive()
                if (servers.isEmpty()) {
                    playbackRequestGate.runIfCurrent(request) {
                        _state.value = State.FailedLoadingServers(
                            Exception("No servers found"),
                            State.ServerDiscoveryFailure.NO_SOURCES,
                        )
                    }
                    return@launch
                }

                Log.i("StreamFlixES", "[SERVERS LIST] -> Provider: ${provider.name}")
                Log.i("StreamFlixES", "[SERVERS LIST] -> Found ${servers.size} servers: ${servers.joinToString { it.name }}")
                Log.d("PlayerViewModel", "Ricerca server completata: ${servers.size} server trovati")
                playbackRequestGate.runIfCurrent(request) {
                    tvViewReplay.record(servers)
                    _state.value = State.SuccessLoadingServers(servers)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                playbackRequestGate.runIfCurrent(request) {
                    Log.e("PlayerViewModel", "Errore ricerca server: ", e)
                    _state.value = State.FailedLoadingServers(e)
                }
            }
        }.also { activeServerDiscovery = it }
    }

    fun beginTvPlaybackView(): Long = tvViewReplay.beginView()

    fun markTvServersObserved(viewToken: Long): Boolean = tvViewReplay.markObserved(viewToken)

    fun markTvPlaybackAccepted(viewToken: Long): Boolean =
        tvViewReplay.markPlaybackAccepted(viewToken)

    fun replayServersForTvView(viewToken: Long) {
        val discoveryStateIsReplayable = _state.value is State.LoadingServers ||
            _state.value is State.SuccessLoadingServers
        val servers = tvViewReplay.candidatesForNewView(
            viewToken,
            discoveryStateIsReplayable,
        ) ?: return
        playbackRequestGate.beginResolution()
        activeVideoResolution?.cancel()
        _state.value = State.SuccessLoadingServers(servers)
    }

    fun getVideo(server: Video.Server, recoveryToken: Long = 0L): Job {
        val request = if (recoveryToken != 0L) playbackRequestGate.beginResolution() else null
        if (recoveryToken != 0L) activeVideoResolution?.cancel()
        return viewModelScope.launch(Dispatchers.IO) {
            ensureActive()
            Log.d("PlayerViewModel", "Inizio estrazione video dal server: ${server.name}")
            if (request != null) {
                if (!playbackRequestGate.runIfCurrent(request) {
                        _state.value = State.LoadingVideo(server, recoveryToken)
                    }) return@launch
            } else {
                _state.emit(State.LoadingVideo(server, recoveryToken))
            }
            try {
                val video = UserPreferences.currentProvider!!.getVideo(server)
                ensureActive()
                if (recoveryToken == 0L && video.source.isEmpty()) throw Exception("No source found")

                // LOGICA SOTTOTITOLI GLOBALE:
                // Se il provider non ha già impostato un default (es. i "forced" in spagnolo),
                // allora proviamo ad attivare l'ultimo sottotitolo usato dall'utente.
                // MA: se siamo su un provider spagnolo e non ci sono forced, non dobbiamo attivare nulla.
                val currentProviderLang = UserPreferences.currentProvider?.language ?: ""
                val hasDefaultAlready = video.subtitles.any { it.default }

                if (!hasDefaultAlready && currentProviderLang != "es") {
                    if (!(video.useServerSubtitleSetting && UserPreferences.serverAutoSubtitlesDisabled)) {
                        video.subtitles
                            .firstOrNull { it.label.startsWith(UserPreferences.subtitleName ?: "") }
                            ?.default = true
                    }
                }

                Log.d("PlayerViewModel", "Estrazione video completata con successo")
                ensureActive()
                if (request != null) {
                    if (!playbackRequestGate.runIfCurrent(request) {
                            _state.value = State.SuccessLoadingVideo(video, server, recoveryToken)
                        }) return@launch
                } else {
                    _state.emit(State.SuccessLoadingVideo(video, server, recoveryToken))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                if (request != null) {
                    if (!playbackRequestGate.runIfCurrent(request) {
                            Log.e("PlayerViewModel", "Errore estrazione video: ", e)
                            _state.value = State.FailedLoadingVideo(e, server, recoveryToken)
                        }) return@launch
                } else {
                    Log.e("PlayerViewModel", "Errore estrazione video: ", e)
                    _state.emit(State.FailedLoadingVideo(e, server, recoveryToken))
                }
            }
        }.also { if (recoveryToken != 0L) activeVideoResolution = it }
    }

    fun getSubtitles(videoType: Video.Type) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio ricerca sottotitoli")
        _subtitleState.emit(SubtitleState.Loading)

        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca OpenSubtitles")
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> {
                        OpenSubtitles.search(
                            query = videoType.tvShow.title,
                            season = videoType.season.number,
                            episode = videoType.number,
                        )
                    }
                    is Video.Type.Movie -> {
                        OpenSubtitles.search(query = videoType.title)
                    }
                }.sortedWith(compareBy({ it.languageName }, { it.subDownloadsCnt }))
                
                Log.d("PlayerViewModel", "Ricerca OpenSubtitles completata: ${subtitles.size} risultati")
                _subtitleState.emit(SubtitleState.SuccessOpenSubtitles(subtitles))
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore OpenSubtitles: ", e)
                _subtitleState.emit(SubtitleState.FailedOpenSubtitles(e))
            }
        }

        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca SubDL")
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> {
                        SubDL.search(
                            filmName = videoType.tvShow.title,
                            seasonNumber = videoType.season.number,
                            episodeNumber = videoType.number,
                            type = "tv"
                        )
                    }
                    is Video.Type.Movie -> {
                        SubDL.search(
                            filmName = videoType.title,
                            type = "movie"
                        )
                    }
                }
                
                Log.d("PlayerViewModel", "Ricerca SubDL completata: ${subtitles.size} risultati")
                _subtitleState.emit(SubtitleState.SuccessSubDLSubtitles(subtitles))
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore SubDL: ", e)
                _subtitleState.emit(SubtitleState.FailedSubDLSubtitles(e))
            }
        }
    }

    fun downloadSubtitle(subtitle: OpenSubtitles.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio download sottotitolo OpenSubtitles: ${subtitle.subFileName}")
        _subtitleState.emit(SubtitleState.DownloadingOpenSubtitle)
        try {
            val uri = OpenSubtitles.download(subtitle)
            Log.d("PlayerViewModel", "Download OpenSubtitles completato: $uri")
            _subtitleState.emit(SubtitleState.SuccessDownloadingOpenSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore download OpenSubtitles: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingOpenSubtitle(e, subtitle))
        }
    }

    fun downloadSubDLSubtitle(subtitle: SubDL.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio download sottotitolo SubDL: ${subtitle.name}")
        _subtitleState.emit(SubtitleState.DownloadingSubDLSubtitle)
        try {
            val uri = SubDL.download(subtitle)
            Log.d("PlayerViewModel", "Download SubDL completato: $uri")
            _subtitleState.emit(SubtitleState.SuccessDownloadingSubDLSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore download SubDL: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingSubDLSubtitle(e, subtitle))
        }
    }

    sealed class State {
        data object LoadingServers : State()
        data class SuccessLoadingServers(val servers: List<Video.Server>) : State()
        data class FailedLoadingServers(
            val error: Exception,
            val kind: ServerDiscoveryFailure = ServerDiscoveryFailure.FAILED,
        ) : State()
        enum class ServerDiscoveryFailure { NO_SOURCES, FAILED }
        data class LoadingVideo(val server: Video.Server, val recoveryToken: Long = 0L) : State()
        data class SuccessLoadingVideo(val video: Video, val server: Video.Server, val recoveryToken: Long = 0L) : State()
        data class FailedLoadingVideo(val error: Exception, val server: Video.Server, val recoveryToken: Long = 0L) : State()
    }

    sealed class SubtitleState {
        data object Loading : SubtitleState()
        data class SuccessOpenSubtitles(val subtitles: List<OpenSubtitles.Subtitle>) : SubtitleState()
        data class FailedOpenSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingOpenSubtitle : SubtitleState()
        data class SuccessDownloadingOpenSubtitle(val subtitle: OpenSubtitles.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingOpenSubtitle(val error: Exception, val subtitle: OpenSubtitles.Subtitle) : SubtitleState()

        data class SuccessSubDLSubtitles(val subtitles: List<SubDL.Subtitle>) : SubtitleState()
        data class FailedSubDLSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingSubDLSubtitle : SubtitleState()
        data class SuccessDownloadingSubDLSubtitle(val subtitle: SubDL.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingSubDLSubtitle(val error: Exception, val subtitle: SubDL.Subtitle) : SubtitleState()
    }
    private var lastVideoType: Video.Type? = null
    private var lastId: String? = null
    fun reloadServersAfterBypass() {
        val type = lastVideoType ?: return
        val id = lastId ?: return
        getServers(type, id)
    }
}
