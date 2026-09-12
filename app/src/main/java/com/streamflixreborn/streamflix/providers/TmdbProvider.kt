package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.AfterDarkExtractor
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.extractors.MoflixExtractor
import com.streamflixreborn.streamflix.extractors.MoviesapiExtractor
import com.streamflixreborn.streamflix.extractors.TwoEmbedExtractor
import com.streamflixreborn.streamflix.extractors.VidsrcNetExtractor
import com.streamflixreborn.streamflix.extractors.VidsrcToExtractor
import com.streamflixreborn.streamflix.extractors.VidzeeExtractor
import com.streamflixreborn.streamflix.extractors.VixSrcExtractor
import com.streamflixreborn.streamflix.extractors.VidLinkExtractor
import com.streamflixreborn.streamflix.extractors.VidsrcRuExtractor
import com.streamflixreborn.streamflix.extractors.EinschaltenExtractor
import com.streamflixreborn.streamflix.extractors.FrembedExtractor
import com.streamflixreborn.streamflix.extractors.VidflixExtractor
import com.streamflixreborn.streamflix.extractors.VidrockExtractor
import com.streamflixreborn.streamflix.extractors.VideasyExtractor
import com.streamflixreborn.streamflix.extractors.PrimeSrcExtractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.safeSubList
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class TmdbProvider(override val language: String) : Provider {
    override val baseUrl: String
        get() = ""

    override val name = "TMDb ($language)"
    override val logo =
        "https://upload.wikimedia.org/wikipedia/commons/thumb/8/89/Tmdb.new.logo.svg/1280px-Tmdb.new.logo.svg.png"

    override suspend fun getHome(): List<Category> = coroutineScope {
        val categories = mutableListOf<Category>()
        val watchRegion = if (language == "en") "US" else language.uppercase()

        val mapMulti: (TMDb3.MultiItem) -> AppAdapter.Item? = { multi ->
            when (multi) {
                is TMDb3.Movie -> Movie(
                    id = multi.id.toString(),
                    title = multi.title,
                    overview = multi.overview,
                    released = multi.releaseDate,
                    rating = multi.voteAverage.toDouble(),
                    poster = multi.posterPath?.w500,
                    banner = multi.backdropPath?.original,
                )

                is TMDb3.Tv -> TvShow(
                    id = multi.id.toString(),
                    title = multi.name,
                    overview = multi.overview,
                    released = multi.firstAirDate,
                    rating = multi.voteAverage.toDouble(),
                    poster = multi.posterPath?.w500,
                    banner = multi.backdropPath?.original,
                )

                else -> null
            }
        }

        val trendingDeferred = async {
            awaitAll(
                async { TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 1, language = language) },
                async { TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 2, language = language) },
                async { TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 3, language = language) },
            ).flatMap { it.results }
        }

        val popularMoviesDeferred = async {
            awaitAll(
                async { TMDb3.MovieLists.popular(page = 1, language = language) },
                async { TMDb3.MovieLists.popular(page = 2, language = language) },
                async { TMDb3.MovieLists.popular(page = 3, language = language) },
            ).flatMap { it.results }
        }

        val popularTvShowsDeferred = async {
            awaitAll(
                async { TMDb3.TvSeriesLists.popular(page = 1, language = language) },
                async { TMDb3.TvSeriesLists.popular(page = 2, language = language) },
                async { TMDb3.TvSeriesLists.popular(page = 3, language = language) },
            ).flatMap { it.results }
        }

        val popularAnimeDeferred = async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        withKeywords = TMDb3.Params.WithBuilder(TMDb3.Keyword.KeywordId.ANIME)
                            .or(TMDb3.Keyword.KeywordId.BASED_ON_ANIME),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withKeywords = TMDb3.Params.WithBuilder(TMDb3.Keyword.KeywordId.ANIME)
                            .or(TMDb3.Keyword.KeywordId.BASED_ON_ANIME),
                    )
                },
            ).flatMap { it.results }
        }

        // TMDB_CONFIGURABLE_STREAMING_CATALOGS_V1
        data class StreamingCatalog(
            val key: String,
            val label: String,
            val movieProvider: TMDb3.Provider.WatchProviderId?,
            val tvNetwork: TMDb3.Network.NetworkId?,
        )

        val availableStreamingCatalogs = listOf(
            StreamingCatalog(
                "netflix",
                "Netflix",
                TMDb3.Provider.WatchProviderId.NETFLIX,
                TMDb3.Network.NetworkId.NETFLIX,
            ),
            StreamingCatalog(
                "prime",
                "Prime Video",
                TMDb3.Provider.WatchProviderId.AMAZON_VIDEO,
                TMDb3.Network.NetworkId.AMAZON,
            ),
            StreamingCatalog(
                "disney",
                "Disney+",
                TMDb3.Provider.WatchProviderId.DISNEY_PLUS,
                TMDb3.Network.NetworkId.DISNEY_PLUS,
            ),
            StreamingCatalog(
                "apple",
                "Apple TV+",
                TMDb3.Provider.WatchProviderId.APPLE_TV_PLUS,
                TMDb3.Network.NetworkId.APPLE_TV,
            ),
            StreamingCatalog(
                "max",
                "Max / HBO",
                TMDb3.Provider.WatchProviderId.HBO_MAX,
                TMDb3.Network.NetworkId.HBO,
            ),
            StreamingCatalog(
                "hulu",
                "Hulu",
                TMDb3.Provider.WatchProviderId.HULU,
                TMDb3.Network.NetworkId.HULU,
            ),
        ).filter {
            it.movieProvider != null || it.tvNetwork != null
        }

        val selectedStreamingCatalogs =
            availableStreamingCatalogs.filter {
                it.key in UserPreferences.tmdbCatalogProviders
            }

        val selectedCatalogModes =
            UserPreferences.tmdbCatalogModes
                .ifEmpty { setOf("popular") }

        suspend fun loadStreamingCatalog(
            catalog: StreamingCatalog,
            mode: String,
        ): List<TMDb3.MultiItem> = coroutineScope {

            val movieSort = when (mode) {
                "top" ->
                    TMDb3.Params.SortBy.Movie.VOTE_AVERAGE_DESC

                "new" ->
                    TMDb3.Params.SortBy.Movie.PRIMARY_RELEASE_DATE_DESC

                else ->
                    TMDb3.Params.SortBy.Movie.POPULARITY_DESC
            }

            val tvSort = when (mode) {
                "top" ->
                    TMDb3.Params.SortBy.Tv.VOTE_AVERAGE_DESC

                "new" ->
                    TMDb3.Params.SortBy.Tv.FIRST_AIR_DATE_DESC

                else ->
                    TMDb3.Params.SortBy.Tv.POPULARITY_DESC
            }

            val movies = catalog.movieProvider?.let { provider ->
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        sortBy = movieSort,
                        withWatchProviders =
                            TMDb3.Params.WithBuilder(provider),
                    ).results.map { it as TMDb3.MultiItem }
                }
            }

            val tvShows = catalog.tvNetwork?.let { network ->
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        watchRegion = watchRegion,
                        sortBy = tvSort,
                        withNetworks =
                            TMDb3.Params.WithBuilder(network),
                    ).results.map { it as TMDb3.MultiItem }
                }
            }

            buildList {
                movies?.await()?.let { addAll(it) }
                tvShows?.await()?.let { addAll(it) }
            }
        }

        val streamingCatalogDeferred =
            selectedStreamingCatalogs.flatMap { catalog ->
                selectedCatalogModes.map { mode ->
                    Triple(
                        catalog,
                        mode,
                        async {
                            loadStreamingCatalog(catalog, mode)
                        }
                    )
                }
            }

        val trending = trendingDeferred.await()
        categories.add(
            Category(
                name = Category.FEATURED,
                list = trending.safeSubList(0, 5).mapNotNull(mapMulti)
            )
        )

        categories.add(
            Category(
                name = getTranslation("Trending"),
                list = trending.safeSubList(5, trending.size).mapNotNull(mapMulti)
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular Movies"),
                list = popularMoviesDeferred.await().mapNotNull(mapMulti)
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular TV Shows"),
                list = popularTvShowsDeferred.await().mapNotNull(mapMulti)
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular Anime"),
                list = popularAnimeDeferred.await()
                    .sortedByDescending {
                        when (it) {
                            is TMDb3.Movie -> it.popularity
                            is TMDb3.Person -> it.popularity
                            is TMDb3.Tv -> it.popularity
                        }
                    }
                    .mapNotNull(mapMulti),
            )
        )

        streamingCatalogDeferred.forEach { (catalog, mode, deferred) ->
            val modeLabel = when (mode) {
                "top" -> getTranslation("Top Rated")
                "new" -> getTranslation("Newest")
                else -> getTranslation("Popular")
            }

            val rawItems = deferred.await()
                .distinctBy {
                    when (it) {
                        is TMDb3.Movie -> "movie:${it.id}"
                        is TMDb3.Tv -> "tv:${it.id}"
                        is TMDb3.Person -> "person:${it.id}"
                    }
                }

            val movieItems = rawItems
                .filterIsInstance<TMDb3.Movie>()
                .mapNotNull(mapMulti)

            val seriesItems = rawItems
                .filterIsInstance<TMDb3.Tv>()
                .mapNotNull(mapMulti)

            if (movieItems.isNotEmpty()) {
                categories.add(
                    Category(
                        name =
                            "${catalog.label} · " +
                            "${getTranslation("Movies")} · " +
                            modeLabel,
                        list = movieItems,
                    )
                )
            }

            if (seriesItems.isNotEmpty()) {
                categories.add(
                    Category(
                        name =
                            "${catalog.label} · " +
                            "${getTranslation("TV Shows")} · " +
                            modeLabel,
                        list = seriesItems,
                    )
                )
            }
        }

        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            val genres = listOf(
                TMDb3.Genres.movieList(language = language),
                TMDb3.Genres.tvList(language = language),
            ).flatMap { it.genres }
                .distinctBy { it.id }
                .sortedBy { it.name }
                .map {
                    Genre(
                        id = it.id.toString(),
                        name = it.name,
                    )
                }

            return genres
        }

        val results = TMDb3.Search.multi(query, page = page, language = language).results.mapNotNull { multi ->
            when (multi) {
                is TMDb3.Movie -> Movie(
                    id = multi.id.toString(),
                    title = multi.title,
                    overview = multi.overview,
                    released = multi.releaseDate,
                    rating = multi.voteAverage.toDouble(),
                    poster = multi.posterPath?.w500,
                    banner = multi.backdropPath?.original,
                )

                is TMDb3.Tv -> TvShow(
                    id = multi.id.toString(),
                    title = multi.name,
                    overview = multi.overview,
                    released = multi.firstAirDate,
                    rating = multi.voteAverage.toDouble(),
                    poster = multi.posterPath?.w500,
                    banner = multi.backdropPath?.original,
                )

                else -> null
            }
        }

        return results
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val movies = TMDb3.MovieLists.popular(page = page, language = language).results.map { movie ->
            Movie(
                id = movie.id.toString(),
                title = movie.title,
                overview = movie.overview,
                released = movie.releaseDate,
                rating = movie.voteAverage.toDouble(),
                poster = movie.posterPath?.w500,
                banner = movie.backdropPath?.original,
            )
        }

        return movies
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val tvShows = TMDb3.TvSeriesLists.popular(page = page, language = language).results.map { tv ->
            TvShow(
                id = tv.id.toString(),
                title = tv.name,
                overview = tv.overview,
                released = tv.firstAirDate,
                rating = tv.voteAverage.toDouble(),
                poster = tv.posterPath?.w500,
                banner = tv.backdropPath?.original,
            )
        }

        return tvShows
    }

    override suspend fun getMovie(id: String): Movie {
        val movie = TMDb3.Movies.details(
            movieId = id.toInt(),
            appendToResponse = listOf(
                TMDb3.Params.AppendToResponse.Movie.CREDITS,
                TMDb3.Params.AppendToResponse.Movie.RECOMMENDATIONS,
                TMDb3.Params.AppendToResponse.Movie.VIDEOS,
                TMDb3.Params.AppendToResponse.Movie.EXTERNAL_IDS,
            ),
            language = language
        ).let { movie ->
            Movie(
                id = movie.id.toString(),
                title = movie.title,
                overview = movie.overview,
                released = movie.releaseDate,
                runtime = movie.runtime,
                trailer = movie.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = movie.voteAverage.toDouble(),
                poster = movie.posterPath?.original,
                banner = movie.backdropPath?.original,
                imdbId = movie.externalIds?.imdbId,

                genres = movie.genres.map { genre ->
                    Genre(
                        genre.id.toString(),
                        genre.name,
                    )
                },
                cast = movie.credits?.cast?.map { cast ->
                    People(
                        id = cast.id.toString(),
                        name = cast.name,
                        image = cast.profilePath?.w500,
                    )
                } ?: listOf(),
                recommendations = movie.recommendations?.results?.mapNotNull { multi ->
                    when (multi) {
                        is TMDb3.Movie -> Movie(
                            id = multi.id.toString(),
                            title = multi.title,
                            overview = multi.overview,
                            released = multi.releaseDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.original,
                        )

                        is TMDb3.Tv -> TvShow(
                            id = multi.id.toString(),
                            title = multi.name,
                            overview = multi.overview,
                            released = multi.firstAirDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.original,
                        )

                        else -> null
                    }
                } ?: listOf(),
            )
        }

        return movie
    }

    override suspend fun getTvShow(id: String): TvShow {
        val tvShow = TMDb3.TvSeries.details(
            seriesId = id.toInt(),
            appendToResponse = listOf(
                TMDb3.Params.AppendToResponse.Tv.CREDITS,
                TMDb3.Params.AppendToResponse.Tv.RECOMMENDATIONS,
                TMDb3.Params.AppendToResponse.Tv.VIDEOS,
                TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS,
            ),
            language = language
        ).let { tv ->
            TvShow(
                id = tv.id.toString(),
                title = tv.name,
                overview = tv.overview,
                released = tv.firstAirDate,
                trailer = tv.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = tv.voteAverage.toDouble(),
                poster = tv.posterPath?.original,
                banner = tv.backdropPath?.original,
                imdbId = tv.externalIds?.imdbId,

                seasons = tv.seasons.map { season ->
                    Season(
                        id = "${tv.id}-${season.seasonNumber}",
                        number = season.seasonNumber,
                        title = season.name,
                        poster = season.posterPath?.w500,
                    )
                },
                genres = tv.genres.map { genre ->
                    Genre(
                        genre.id.toString(),
                        genre.name,
                    )
                },
                cast = tv.credits?.cast?.map { cast ->
                    People(
                        id = cast.id.toString(),
                        name = cast.name,
                        image = cast.profilePath?.w500,
                    )
                } ?: listOf(),
                recommendations = tv.recommendations?.results?.mapNotNull { multi ->
                    when (multi) {
                        is TMDb3.Movie -> Movie(
                            id = multi.id.toString(),
                            title = multi.title,
                            overview = multi.overview,
                            released = multi.releaseDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.original,
                        )

                        is TMDb3.Tv -> TvShow(
                            id = multi.id.toString(),
                            title = multi.name,
                            overview = multi.overview,
                            released = multi.firstAirDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.original,
                        )

                        else -> null
                    }
                } ?: listOf(),
            )
        }

        return tvShow
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val (tvShowId, seasonNumber) = seasonId.split("-")

        val episodes = TMDb3.TvSeasons.details(
            seriesId = tvShowId.toInt(),
            seasonNumber = seasonNumber.toInt(),
            language = language
        ).episodes?.map {
            Episode(
                id = it.id.toString(),
                number = it.episodeNumber,
                title = it.name ?: "",
                released = it.airDate,
                poster = it.stillPath?.w500,
            )
        } ?: listOf()

        return episodes
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        fun <T> List<T>.mix(other: List<T>): List<T> {
            return sequence {
                val first = iterator()
                val second = other.iterator()
                while (first.hasNext() && second.hasNext()) {
                    yield(first.next())
                    yield(second.next())
                }

                yieldAll(first)
                yieldAll(second)
            }.toList()
        }

        val genre = Genre(
            id = id,
            name = "",

            shows = TMDb3.Discover.movie(
                page = page,
                withGenres = TMDb3.Params.WithBuilder(id),
                language = language
            ).results.map { movie ->
                Movie(
                    id = movie.id.toString(),
                    title = movie.title,
                    overview = movie.overview,
                    released = movie.releaseDate,
                    rating = movie.voteAverage.toDouble(),
                    poster = movie.posterPath?.w500,
                    banner = movie.backdropPath?.original,
                )
            }.mix(TMDb3.Discover.tv(
                page = page,
                withGenres = TMDb3.Params.WithBuilder(id),
                language = language
            ).results.map { tv ->
                TvShow(
                    id = tv.id.toString(),
                    title = tv.name,
                    overview = tv.overview,
                    released = tv.firstAirDate,
                    rating = tv.voteAverage.toDouble(),
                    poster = tv.posterPath?.w500,
                    banner = tv.backdropPath?.original,
                )
            })
        )

        return genre
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val people = TMDb3.People.details(
            personId = id.toInt(),
            appendToResponse = listOfNotNull(
                if (page > 1) null else TMDb3.Params.AppendToResponse.Person.COMBINED_CREDITS,
            ),
            language = language
        ).let { person ->
            People(
                id = person.id.toString(),
                name = person.name,
                image = person.profilePath?.w500,
                biography = person.biography,
                placeOfBirth = person.placeOfBirth,
                birthday = person.birthday,
                deathday = person.deathday,

                filmography = person.combinedCredits?.cast
                    ?.mapNotNull { multi ->
                        when (multi) {
                            is TMDb3.Movie -> Movie(
                                id = multi.id.toString(),
                                title = multi.title,
                                overview = multi.overview,
                                released = multi.releaseDate,
                                rating = multi.voteAverage.toDouble(),
                                poster = multi.posterPath?.w500,
                                banner = multi.backdropPath?.original,
                            )

                            is TMDb3.Tv -> TvShow(
                                id = multi.id.toString(),
                                title = multi.name,
                                overview = multi.overview,
                                released = multi.firstAirDate,
                                rating = multi.voteAverage.toDouble(),
                                poster = multi.posterPath?.w500,
                                banner = multi.backdropPath?.original,
                            )

                        else -> null
                    }
                }
                    ?.sortedBy {
                        when (it) {
                            is Movie -> it.released
                            is TvShow -> it.released
                        }
                    }
                    ?.reversed()
                    ?: listOf()
            )
        }

        return people
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val servers = mutableListOf<Video.Server>()
        val lang = language.lowercase().substringBefore("-")

        Log.d("TmdbProvider", "getServers: lang=$language, simplifiedLang=$lang")

        when (lang) {
            "it" -> {
                // Se la lingua è italiano, includiamo solo i server noti per l'italiano.
                servers.add(VixSrcExtractor().server(videoType))
            }
            "de" -> {
                // German TMDb sources:
                // keep the existing extractors and additionally search
                // supported native German providers.
                servers.addAll(0, MoflixExtractor().servers(videoType))

                val nativeGermanServers = coroutineScope {
                    germanTmdbProviders(videoType).map { candidate ->
                        async(Dispatchers.IO) {
                            runCatching {
                                findGermanProviderServers(candidate, videoType)
                            }.getOrElse { error ->
                                Log.w(
                                    "TmdbProvider",
                                    "TMDB-DE ${candidate.label} failed",
                                    error
                                )
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten()
                }

                servers.addAll(nativeGermanServers)

                if (videoType is Video.Type.Movie) {
                    servers.add(EinschaltenExtractor().server(videoType))
                }

                VideasyExtractor()
                    .server(videoType, language)
                    ?.let { servers.add(it) }
            }
            "fr" -> {
                // Solo server francesi
                servers.addAll(FrembedExtractor(UserPreferences.getProviderCache(FrembedProvider, UserPreferences.PROVIDER_URL)).servers(videoType))
                servers.addAll(AfterDarkExtractor(UserPreferences.getProviderCache(AfterDarkProvider, UserPreferences.PROVIDER_URL)).servers(videoType))
            }
            "es" -> {
                // TMDB Spagnolo: Utilizza ESCLUSIVAMENTE server certificati con audio spagnolo ([LAT] o [CAST])

                val targetTitle = when (videoType) {
                    is Video.Type.Movie -> videoType.title
                    is Video.Type.Episode -> videoType.tvShow.title
                }

                Log.i("StreamFlixES", "[SEARCH START] -> Target: $targetTitle (${if (videoType is Video.Type.Movie) "Movie" else "TV Show"})")

                // Funzione di matching rigorosa per i titoli e tipo
                fun isMatch(item: AppAdapter.Item, target: String): Boolean {
                    val isCorrectType = if (videoType is Video.Type.Movie) item is Movie else item is TvShow
                    if (!isCorrectType) return false

                    val itemTitle = if (item is Movie) item.title else (item as TvShow).title
                    val nItem = itemTitle.lowercase().replace(Regex("[^a-z0-9]"), "")
                    val nTarget = target.lowercase().replace(Regex("[^a-z0-9]"), "")

                    // Match esatto (normalizzato) ha la priorità
                    if (nItem == nTarget) return true

                    // Match parziale se contenuto e differenza lunghezza minima
                    if (nItem.contains(nTarget) || nTarget.contains(nItem)) {
                        val diff = Math.abs(nItem.length - nTarget.length)
                        if (diff <= 5) return true
                    }

                    // Match per parole (almeno una deve corrispondere esattamente se il target è corto, o tutte se lungo)
                    val cleanWords: (String) -> Set<String> = { s ->
                        s.lowercase()
                            .replace(Regex("[^a-z0-9 ]"), " ")
                            .split(Regex("\\s+"))
                            .filter { it.length > 2 }
                            .toSet()
                    }
                    val nItemWords = cleanWords(itemTitle)
                    val nTargetWords = cleanWords(target)

                    if (nItemWords.isEmpty() || nTargetWords.isEmpty()) return false

                    // Se il target ha solo una parola importante, deve esserci
                    if (nTargetWords.size == 1) return nItemWords.contains(nTargetWords.first())

                    // Altrimenti tutte le parole del target devono essere presenti nell'item
                    return nItemWords.containsAll(nTargetWords) || nTargetWords.containsAll(nItemWords)
                }

                coroutineScope {
                    val providers = listOf(CuevanaEuProvider, PelisplustoProvider, SoloLatinoProvider, CineCalidadProvider, PoseidonHD2Provider)
                    val deferred = providers.map { provider ->
                        async {
                            try {
                                val searchResults = provider.search(targetTitle, 1)
                                val bestMatch = searchResults.firstOrNull { isMatch(it, targetTitle) }
                                val id = if (bestMatch is Movie) bestMatch.id else (bestMatch as? TvShow)?.id

                                if (id != null) {
                                    val matchTitle = if (bestMatch is Movie) bestMatch.title else (bestMatch as? TvShow)?.title
                                    Log.i("StreamFlixES", "[MATCH FOUND] -> Provider: ${provider.name}, Matched: '$matchTitle', ID: $id")

                                    val allServers = provider.getServers(id, videoType)
                                    val filtered = allServers.filter { s ->
                                        val n = s.name.uppercase()
                                        n.contains("[LAT]") || n.contains("[CAST]") || n.contains("[CAS]") || n.contains("[ES]") ||
                                        n.contains("(LAT)") || n.contains("(ESP)") || n.contains("LATINO") || n.contains("CASTELLANO")
                                    }
                                    Log.i("StreamFlixES", "[SERVERS OK] -> ${provider.name}: ${filtered.size}/${allServers.size} servers kept")
                                    filtered
                                } else {
                                    Log.d("StreamFlixES", "[NO MATCH] -> ${provider.name} did not find a valid match for '$targetTitle'")
                                    emptyList()
                                }
                            } catch (e: Exception) {
                                Log.e("StreamFlixES", "[PROVIDER ERROR] -> ${provider.name}: ${e.message}")
                                emptyList()
                            }
                        }
                    }
                    servers.addAll(deferred.awaitAll().flatten())
                }
            }
            else -> {
                // Per inglese (en) o altre lingue non specifiche, usiamo i server globali
                servers.addAll(listOf(
                    VixSrcExtractor().server(videoType),
                    TwoEmbedExtractor().server(videoType),
                    VidsrcNetExtractor().server(videoType),
                    VidLinkExtractor().server(videoType),
                    VidsrcRuExtractor().server(videoType),
                    VidflixExtractor().server(videoType),
                ))

                if (videoType is Video.Type.Movie) {
                    servers.add(2, MoviesapiExtractor().server(videoType))
                }

                servers.addAll(VidrockExtractor().servers(videoType))
                servers.addAll(VidzeeExtractor().servers(videoType))
                servers.addAll(PrimeSrcExtractor().servers(videoType))

                if (language == "en") {
                    servers.addAll(1, VideasyExtractor().servers(videoType, language))
                }
            }
        }

        // ORDINE PRIORITÀ FINALE: Portiamo i server con audio Spagnolo e Filemoon in cima
        val finalServers = if (language.startsWith("es")) {
            servers.sortedByDescending { server ->
                val n = server.name.uppercase()
                when {
                    // Filemoon e tag audio spagnoli hanno la massima priorità
                    n.contains("FILEMOON") -> 110
                    n.contains("[CAS]") || n.contains("[LAT]") || n.contains("[ES]") || n.contains("SPAIN") || n.contains("[CAST]") ||
                    n.contains("LATINO") || n.contains("SPANISH") || n.contains("CASTELLANO") || n.contains("(LAT)") || n.contains("(ESP)") -> 100

                    // Altri aggregatori multi-lingua
                    n.contains("VIDSRC") || n.contains("VIDLINK") -> 80

                    // Sottotitoli o inglese
                    n.contains("[EN]") || n.contains("[SUB]") || n.contains("(EN)") || n.contains("(SUB)") -> 50

                    else -> 0
                }
            }
        } else {
            servers
        }

        Log.i("StreamFlixES", "[SERVERS LIST] -> Found ${finalServers.size} servers: ${finalServers.joinToString { it.name }}")
        return finalServers.distinctBy { it.id }
    }

    private data class GermanTmdbProvider(
        val key: String,
        val label: String,
        val provider: Provider,
        val movies: Boolean,
        val tvShows: Boolean,
    )

    private fun germanTmdbProviders(
        videoType: Video.Type
    ): List<GermanTmdbProvider> {
        val all = listOf(
            GermanTmdbProvider(
                "vavoo",
                "Vavoo VOD",
                VavooVodProvider.DE,
                movies = true,
                tvShows = true,
            ),
            GermanTmdbProvider(
                "kinoger",
                "KinoGer",
                KinoGerProvider,
                movies = true,
                tvShows = true,
            ),
            GermanTmdbProvider(
                "kellerkino",
                "Kellerkino",
                KellerKinoProvider,
                movies = true,
                tvShows = false,
            ),
            GermanTmdbProvider(
                "hdfilme",
                "HDFilme",
                HDFilmeProvider,
                movies = true,
                tvShows = true,
            ),
            GermanTmdbProvider(
                "megakino",
                "MegaKino",
                MEGAKinoProvider,
                movies = true,
                tvShows = true,
            ),
            GermanTmdbProvider(
                "filmpalast",
                "FilmPalast",
                FilmPalastProvider,
                movies = true,
                tvShows = true,
            ),
            GermanTmdbProvider(
                "serienstream",
                "SerienStream",
                SerienStreamProvider,
                movies = false,
                tvShows = true,
            ),
        )

        return when (videoType) {
            is Video.Type.Movie -> all.filter { it.movies }
            is Video.Type.Episode -> all.filter { it.tvShows }
        }
    }

    private fun normalizeGermanTitle(value: String): String =
        value.lowercase()
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace("ß", "ss")
            .replace(Regex("[^a-z0-9]"), "")

    private fun titleScore(
        candidate: String,
        target: String
    ): Int {
        val c = normalizeGermanTitle(candidate)
        val t = normalizeGermanTitle(target)

        if (c.isBlank() || t.isBlank()) return 0
        if (c == t) return 100
        if (c.startsWith(t) || t.startsWith(c)) return 80
        if (c.contains(t) || t.contains(c)) return 60

        return 0
    }

    private suspend fun findGermanProviderServers(
        candidate: GermanTmdbProvider,
        videoType: Video.Type,
    ): List<Video.Server> = withTimeoutOrNull(15_000L) {

        val targetTitle = when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title
        }

        val results = candidate.provider.search(targetTitle, 1)

        when (videoType) {
            is Video.Type.Movie -> {
                val match = results
                    .filterIsInstance<Movie>()
                    .map {
                        it to titleScore(it.title, targetTitle)
                    }
                    .filter { it.second >= 60 }
                    .maxByOrNull { it.second }
                    ?.first
                    ?: return@withTimeoutOrNull emptyList()

                candidate.provider
                    .getServers(match.id, videoType)
                    .map {
                        wrapGermanTmdbServer(candidate, it)
                    }
            }

            is Video.Type.Episode -> {
                val match = results
                    .filterIsInstance<TvShow>()
                    .map {
                        it to titleScore(it.title, targetTitle)
                    }
                    .filter { it.second >= 60 }
                    .maxByOrNull { it.second }
                    ?.first
                    ?: return@withTimeoutOrNull emptyList()

                val details =
                    candidate.provider.getTvShow(match.id)

                val season =
                    details.seasons.firstOrNull {
                        it.number == videoType.season.number
                    }
                    ?: return@withTimeoutOrNull emptyList()

                val episodes =
                    candidate.provider
                        .getEpisodesBySeason(season.id)

                val episode =
                    episodes.firstOrNull {
                        it.number == videoType.number
                    }
                    ?: return@withTimeoutOrNull emptyList()

                candidate.provider
                    .getServers(episode.id, videoType)
                    .map {
                        wrapGermanTmdbServer(candidate, it)
                    }
            }
        }
    } ?: emptyList()

    private fun wrapGermanTmdbServer(
        candidate: GermanTmdbProvider,
        server: Video.Server,
    ): Video.Server =
        Video.Server(
            id = "tmdbde:${candidate.key}:${server.id}",
            name = "${candidate.label} • ${server.name}",
            src = server.src,
        ).also { wrapped ->
            wrapped.video = server.video
        }

    private fun germanProviderByKey(
        key: String
    ): Provider? =
        when (key) {
            "vavoo" -> VavooVodProvider.DE
            "kinoger" -> KinoGerProvider
            "kellerkino" -> KellerKinoProvider
            "hdfilme" -> HDFilmeProvider
            "megakino" -> MEGAKinoProvider
            "filmpalast" -> FilmPalastProvider
            "serienstream" -> SerienStreamProvider
            else -> null
        }

    private suspend fun getGermanProviderVideo(
        server: Video.Server
    ): Video? {
        if (!server.id.startsWith("tmdbde:")) {
            return null
        }

        val parts =
            server.id.split(":", limit = 3)

        if (parts.size != 3) {
            return null
        }

        val provider =
            germanProviderByKey(parts[1])
                ?: return null

        val original =
            Video.Server(
                id = parts[2],
                name = server.name.substringAfter(
                    " • ",
                    server.name
                ),
                src = server.src,
            ).also {
                it.video = server.video
            }

        return provider.getVideo(original)
    }

    override suspend fun getVideo(server: Video.Server): Video {
        getGermanProviderVideo(server)?.let { return it }

        val url = server.src.ifEmpty { server.id }
        Log.i("StreamFlixES", "[SERVER] -> Using: ${server.name} (URL: $url)")

        val video = when {
            server.video != null -> server.video!!
            else -> Extractor.extract(url, server)
        }

        // LOGICA SOTTOTITOLI FORZATI: Se siamo in spagnolo, attiviamo solo i forced di default
        if (language.startsWith("es")) {
            var forcedFound = false
            video.subtitles.forEach { sub ->
                val label = sub.label.lowercase()
                val isSpanish = label.contains("spanish") || label.contains("español") ||
                                label.contains("espanol") || label.contains("castellano") ||
                                label.contains(" lat ")
                val isForced = label.contains("forced") || label.contains("forzati") || label.contains("forzato")

                if (isSpanish && isForced) {
                    sub.default = true
                    forcedFound = true
                    Log.i("StreamFlixES", "[SUBTITLE] -> TMDb (es): Selected FORCED subtitle: ${sub.label}")
                } else {
                    sub.default = false
                }
            }

            if (!forcedFound) {
                video.subtitles.forEach { it.default = false }
                Log.i("StreamFlixES", "[SUBTITLE] -> TMDb (es): No forced subs found, keeping them OFF")
            }
        }

        Log.i("StreamFlixES", "[VIDEO] -> Final source: ${video.source}")
        return video
    }

    private fun getTranslation(key: String): String {
        return when (language) {
            "it" -> when (key) {
                "Trending" -> "Di tendenza"
                "Movies" -> "Film"
                "TV Shows" -> "Serie TV"
                "Popular" -> "Popolari"
                "Top Rated" -> "Più votati"
                "Newest" -> "Più recenti"
                "Popular Movies" -> "Film popolari"
                "Popular TV Shows" -> "Serie TV popolari"
                "Popular Anime" -> "Anime popolari"
                "Popular on Netflix" -> "Popolari su Netflix"
                "Popular on Amazon" -> "Popolari su Amazon"
                "Popular on Disney+" -> "Popolari su Disney+"
                "Popular on Hulu" -> "Popolari su Hulu"
                "Popular on Apple TV+" -> "Popolari su Apple TV+"
                "Popular on HBO" -> "Popolari su HBO"
                else -> key
            }
            "es" -> when (key) {
                "Trending" -> "Tendencias"
                "Movies" -> "Películas"
                "TV Shows" -> "Series"
                "Popular" -> "Popular"
                "Top Rated" -> "Mejor valorado"
                "Newest" -> "Más recientes"
                "Popular Movies" -> "Películas populares"
                "Popular TV Shows" -> "Series de TV populares"
                "Popular Anime" -> "Anime populares"
                "Popular on Netflix" -> "Popular en Netflix"
                "Popular on Amazon" -> "Popular en Amazon"
                "Popular on Disney+" -> "Popular en Disney+"
                "Popular on Hulu" -> "Popular en Hulu"
                "Popular on Apple TV+" -> "Popular en Apple TV+"
                "Popular on HBO" -> "Popular en HBO"
                else -> key
            }
            "de" -> when (key) {
                "Trending" -> "Trends"
                "Movies" -> "Filme"
                "TV Shows" -> "Serien"
                "Popular" -> "Beliebt"
                "Top Rated" -> "Top bewertet"
                "Newest" -> "Neueste"
                "Popular Movies" -> "Beliebte Filme"
                "Popular TV Shows" -> "Beliebte Serien"
                "Popular Anime" -> "Beliebte Anime"
                "Popular on Netflix" -> "Beliebt bei Netflix"
                "Popular on Amazon" -> "Beliebt bei Amazon"
                "Popular on Disney+" -> "Beliebt bei Disney+"
                "Popular on Hulu" -> "Beliebt bei Hulu"
                "Popular on Apple TV+" -> "Beliebt bei Apple TV+"
                "Popular on HBO" -> "Beliebt bei HBO"
                else -> key
            }
            "fr" -> when (key) {
                "Trending" -> "Tendances"
                "Movies" -> "Films"
                "TV Shows" -> "Séries"
                "Popular" -> "Populaires"
                "Top Rated" -> "Les mieux notés"
                "Newest" -> "Nouveautés"
                "Popular Movies" -> "Films populaires"
                "Popular TV Shows" -> "Séries populaires"
                "Popular Anime" -> "Animes populaires"
                "Popular on Netflix" -> "Populaire sur Netflix"
                "Popular on Amazon" -> "Populaire sur Amazon"
                "Popular on Disney+" -> "Populaire sur Disney+"
                "Popular on Hulu" -> "Populaire sur Hulu"
                "Popular on Apple TV+" -> "Populaire sur Apple TV+"
                "Popular on HBO" -> "Populaire sur HBO"
                else -> key
            }
            else -> key
        }
    }
}
