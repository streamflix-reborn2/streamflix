package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.sync.Mutex

interface ProviderPortalUrl {
    val portalUrl: String
    val defaultPortalUrl: String
}

interface ProviderConfigUrl {
    val defaultBaseUrl: String

    suspend fun onChangeUrl(forceRefresh: Boolean = false): String
    val changeUrlMutex: Mutex
}

interface IptvProvider : Provider

interface Provider {

    val baseUrl: String
    val name: String
    val logo: String
    val language: String

    suspend fun getHome(): List<Category>

    suspend fun search(query: String, page: Int = 1): List<AppAdapter.Item>

    suspend fun getMovies(page: Int = 1): List<Movie>

    suspend fun getTvShows(page: Int = 1): List<TvShow>

    suspend fun getMovie(id: String): Movie

    suspend fun getTvShow(id: String): TvShow

    suspend fun getEpisodesBySeason(seasonId: String): List<Episode>

    suspend fun getGenre(id: String, page: Int = 1): Genre

    suspend fun getPeople(id: String, page: Int = 1): People

    suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server>

    suspend fun getVideo(server: Video.Server): Video

    companion object {
        data class ProviderSupport(
            val movies: Boolean,
            val tvShows: Boolean
        )

        val providers = mapOf(
            SflixProvider to ProviderSupport(movies = true, tvShows = true),
            FanpelisProvider to ProviderSupport(movies = true, tvShows = true),
            SerienStreamProvider to ProviderSupport(movies = false, tvShows = true),
            StreamingCommunityProvider("it") to ProviderSupport(movies = true, tvShows = true),
            StreamingCommunityProvider("en") to ProviderSupport(movies = true, tvShows = true),
            AnimeWorldProvider to ProviderSupport(movies = true, tvShows = true),
            MkissaProvider to ProviderSupport(movies = true, tvShows = true),
            AniWorldProvider to ProviderSupport(movies = false, tvShows = true),
            RidomoviesProvider to ProviderSupport(movies = true, tvShows = true),
            AnikotoProvider to ProviderSupport(movies = true, tvShows = true),
            WiflixProvider to ProviderSupport(movies = true, tvShows = true),
            MStreamProvider to ProviderSupport(movies = true, tvShows = true),
            FrenchAnimeProvider to ProviderSupport(movies = true, tvShows = true),
            FilmPalastProvider to ProviderSupport(movies = true, tvShows = true),
            PoseidonHD2Provider to ProviderSupport(movies = true, tvShows = true),
            CuevanaEuProvider to ProviderSupport(movies = true, tvShows = true),
            LatanimeProvider to ProviderSupport(movies = true, tvShows = true),
            DoramasflixProvider to ProviderSupport(movies = true, tvShows = true),
            CineCalidadProvider to ProviderSupport(movies = true, tvShows = true),
            SeriesFlixProvider to ProviderSupport(movies = false, tvShows = true),
            SeriesTurcasProvider to ProviderSupport(movies = false, tvShows = true),
            FlixLatamProvider to ProviderSupport(movies = true, tvShows = true),
            LaCartoonsProvider to ProviderSupport(movies = false, tvShows = true),
            AnimefenixProvider to ProviderSupport(movies = false, tvShows = true),
            AnimeFlvProvider to ProviderSupport(movies = false, tvShows = true),
            JKAnimeProvider to ProviderSupport(movies = true, tvShows = true),
            TioAnimeProvider to ProviderSupport(movies = true, tvShows = true),
            AnimeAv1Provider to ProviderSupport(movies = false, tvShows = true),
            AnimeOnlineNinjaProvider to ProviderSupport(movies = true, tvShows = true),
            SoloLatinoProvider to ProviderSupport(movies = true, tvShows = true),
            Cine24hProvider to ProviderSupport(movies = true, tvShows = true),
            PelisplustoProvider to ProviderSupport(movies = true, tvShows = true),
            PelisflixHdProvider to ProviderSupport(movies = true, tvShows = true),
            CableVisionHDProvider to ProviderSupport(movies = false, tvShows = true),
            Altadefinizione01Provider to ProviderSupport(movies = true, tvShows = true),
            GuardaFlixProvider to ProviderSupport(movies = true, tvShows = false),
            CB01Provider to ProviderSupport(movies = true, tvShows = true),
            AnimeUnityProvider to ProviderSupport(movies = true, tvShows = true),
            AnimeSaturnProvider to ProviderSupport(movies = false, tvShows = true),
            FrenchStreamProvider to ProviderSupport(movies = true, tvShows = true),
            GuardaSerieProvider to ProviderSupport(movies = true, tvShows = true),
            EinschaltenProvider to ProviderSupport(movies = true, tvShows = false),
            HDFilmeProvider to ProviderSupport(movies = true, tvShows = true),
            MEGAKinoProvider to ProviderSupport(movies = true, tvShows = true),
            FilmyOnlineCcProvider to ProviderSupport(movies = true, tvShows = true),
            ZaluknijProvider to ProviderSupport(movies = true, tvShows = true),
            TvporinternetHDProvider to ProviderSupport(movies = false, tvShows = true),
            FrembedProvider to ProviderSupport(movies = true, tvShows = true),
            KidrazProvider to ProviderSupport(movies = true, tvShows = false),
            FrenchMangaProvider to ProviderSupport(movies = false, tvShows = true),
            IptvOrgProvider to ProviderSupport(movies = false, tvShows = true),
            IptvSpainProvider to ProviderSupport(movies = false, tvShows = true),
            TvLibrefutbolProvider to ProviderSupport(movies = false, tvShows = true),
            PelotaLibreTvHdProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvMxProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvArProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvDeProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvEsProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvFrProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvItProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvUsProvider to ProviderSupport(movies = false, tvShows = true),
            CineCityProvider to ProviderSupport(movies = false, tvShows = true),
            CineHaxProvider to ProviderSupport(movies = true, tvShows = true),
            VavooProvider("de") to ProviderSupport(movies = false, tvShows = true),
            VavooProvider("it") to ProviderSupport(movies = false, tvShows = true),
            VavooProvider("fr") to ProviderSupport(movies = false, tvShows = true),
            VavooProvider("es") to ProviderSupport(movies = false, tvShows = true),
            VavooProvider("pl") to ProviderSupport(movies = false, tvShows = true)
        )

        // Helper functions to check support
        fun supportsMovies(provider: Provider): Boolean {
            val support = providers[provider] ?: ProviderSupport(movies = true, tvShows = true)
            return support.movies
        }

        fun supportsTvShows(provider: Provider): Boolean {
            val support = providers[provider] ?: ProviderSupport(movies = true, tvShows = true)
            return support.tvShows
        }

        fun findByName(name: String): Provider? {
            return providers.keys.find { it.name == name }
        }
    }
}
