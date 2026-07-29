package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Url
import retrofit2.Response
import okhttp3.ResponseBody
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.UserPreferences
import org.jsoup.nodes.Element
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import kotlin.collections.map
import kotlin.collections.mapNotNull
import kotlin.collections.mapIndexedNotNull
import kotlin.math.round

object FrenchStreamProvider : Provider, ProviderPortalUrl, ProviderConfigUrl {
    override val name = "FrenchStream"

    override val defaultPortalUrl: String = "https://fstream.info/"

    override val portalUrl: String = defaultPortalUrl
        get() {
            val cachePortalURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL)
            return cachePortalURL.ifEmpty { field }
        }

    override val defaultBaseUrl: String = "https://fs16.lol/"
    override val baseUrl: String = defaultBaseUrl
        get() {
            val cacheURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            return cacheURL.ifEmpty { field }
        }

    override val logo: String
        get() {
            val cacheLogo = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_LOGO)
            return cacheLogo.ifEmpty { portalUrl + "favicon-96x96.png" }
        }
    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private lateinit var service: Service
    private var serviceInitialized = false
    private val initializationMutex = Mutex()

    override suspend fun getHome(): List<Category> {
        initializeService()
        // val isNewInterface = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_NEW_INTERFACE) != "false"
        val isNewInterface = false // Forced false for now
        val document = if (isNewInterface) {
            service.postHome()
        } else {
            service.getHome("dle_skin=VFV1")
        }
        val cookie = if (isNewInterface) "dle_skin=VFV25" else "dle_skin=VFV1"
        val categories = mutableListOf<Category>()
        if ( cookie.contains("VFV25")) {
            var first = true
            document.select("section.vod-section").map { cat_item ->
                val title = cat_item
                    .selectFirst("> div.vod-header h2.vod-title-section")
                    ?.let {
                        listOfNotNull(
                            it.ownText().trim(),
                            it.select("span").firstOrNull()?.text()?.trim()
                        ).joinToString(" ")
                    } ?: ""

                val movies = cat_item
                    .select("> div.vod-wrap > div.vod-slider > article.vod-card")
                    .mapNotNull { item ->
                        val a = item.selectFirst("a") ?: return@mapNotNull null
                        val link = a.attr("href")
                        val href = link.substringAfterLast("/")
                        val title = a.selectFirst("div.vod-name")?.text() ?: ""
                        val poster = a.selectFirst("div.vod-poster > img")?.attr("src") ?: ""
                        val mtype = item.selectFirst("> div.vod-br > span.vod-tag a")?.attr("href") ?: ""
                        if (link.startsWith("/s-tv/") || link.contains("-saison-") || title.contains(" - Saison ") || mtype.contains("-serie"))
                            TvShow(
                                id = href,
                                title = title,
                                poster = poster,
                                banner = if (first) poster else null
                            )
                        else
                            Movie(
                                id = href,
                                title = title,
                                poster = poster,
                                banner = if (first) poster else null
                            )
                    }

                if (movies.isNotEmpty()) {
                    categories.add(
                        Category(
                            name = if (first) Category.FEATURED else title,
                            list = movies
                        )
                    )
                    first = false
                }
            }
        } else {
            categories.add(
                Category(
                    name = "Nouveautés Séries",
                    list = document.select("div.pages.clearfix").getOrNull(1)?.select("div.short")
                        ?.map {
                            TvShow(
                                id = it.selectFirst("a.short-poster")
                                    ?.attr("href")?.substringAfterLast("/")
                                    ?: "",
                                title = listOfNotNull(
                                    it.selectFirst("div.short-title")?.text(),
                                    it.selectFirst("span.film-version")?.text(),
                                ).joinToString(" - "),
                                poster = it.selectFirst("img")
                                    ?.attr("src")
                                    ?: "",
                            )
                        } ?: emptyList(),
                )
            )

            categories.add(
                Category(
                    name = "Nouveautés Films",
                    list = document.select("div.pages.clearfix").getOrNull(0)?.select("div.short")
                        ?.map {
                            Movie(
                                id = it.selectFirst("a.short-poster")
                                    ?.attr("href")?.substringAfterLast("/")
                                    ?: "",
                                title = it.selectFirst("div.short-title")
                                    ?.text()
                                    ?: "",
                                poster = it.selectFirst("img")
                                    ?.attr("src")
                                    ?: "",
                            )
                        } ?: emptyList(),
                )
            )

            categories.add(
                Category(
                    name = "Ajouts de la Commu",
                    list = document.select("div.pages.clearfix").getOrNull(2)?.select("div.short")
                        ?.map {
                            Movie(
                                id = it.selectFirst("a.short-poster")
                                    ?.attr("href")?.substringAfterLast("/")
                                    ?: "",
                                title = it.selectFirst("div.short-title")
                                    ?.text()
                                    ?: "",
                                poster = it.selectFirst("img")
                                    ?.attr("src")
                                    ?: "",
                            )
                        } ?: emptyList(),
                )
            )

            categories.add(
                Category(
                    name = "BOX OFFICE",
                    list = document.select("div.pages.clearfix").getOrNull(3)?.select("div.short")
                        ?.map {
                            Movie(
                                id = it.selectFirst("a.short-poster")
                                    ?.attr("href")?.substringAfterLast("/")
                                    ?: "",
                                title = it.selectFirst("div.short-title")
                                    ?.text()
                                    ?: "",
                                poster = it.selectFirst("img")
                                    ?.attr("src")
                                    ?: "",
                            )
                        } ?: emptyList(),
                )
            )
        }

        return categories
    }

    fun ignoreSource(source: String, href: String): Boolean {
        if (source.trim().equals("Dood.Stream", ignoreCase = true) && href.contains("/bigwar5/")) return true
        return false
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        initializeService()
        if (query.isEmpty()) {
            val document = service.getHome()

            val genres = document.selectFirst("div.menu-section")?.select(">a")?.map {
                Genre(
                    id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                    name = it.text(),
                )
            } ?: emptyList()

            return genres
        }
        val document = service.search(
            query = query
        )
        val results = document.select("div.search-item")
            .mapNotNull {
                val id = it
                    .attr("onclick").substringAfter("/").substringBefore("'")
                if (id.isEmpty()) return@mapNotNull null
                val title = it.selectFirst("div.search-title")
                    ?.text()?.replace("\\'","'")
                    ?: ""
                var poster = it.selectFirst("img")
                    ?.attr("src")
                    ?: ""
                if (id.contains("-saison-") || title.contains(" - Saison "))
                    TvShow(
                        id = id,
                        title = title,
                        poster = poster,
                    )
                else
                    Movie(
                        id = id,
                        title = title,
                        poster = poster,
                    )
            }

        return results
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        initializeService()

        val document = service.getMovies(page)

        val movies = document.select("div#dle-content>div.short").map {
            Movie(
                id = it.selectFirst("a.short-poster")
                    ?.attr("href")?.substringAfterLast("/")
                    ?: "",
                title = it.selectFirst("div.short-title")
                    ?.text()
                    ?: "",
                poster = it.selectFirst("img")
                    ?.attr("src"),
            )
        }

        return movies
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        initializeService()
        val document = service.getTvShows(page)

        val tvShows = document.select("div#dle-content>div.short").map {
            TvShow(
                id = it.selectFirst("a.short-poster")
                    ?.attr("href")?.substringAfterLast("/")
                    ?: "",
                title = it.selectFirst("div.short-title")
                    ?.text()
                    ?: "",
                poster = it.selectFirst("img")
                    ?.attr("src"),
            )
        }

        return tvShows
    }

    suspend fun getRating(votes: Element): Double {
        val voteplus = votes
            ?.selectFirst("span.ratingtypeplusminus")
            ?.text()
            ?.toIntOrNull() ?: 0

        val votenum = votes
            ?.select("span[id]")
            ?.last()
            ?.text()
            ?.toIntOrNull() ?: 0

        val rating = if (votenum >= voteplus && votenum > 0) {
            round((votenum - (votenum - voteplus) / 2.0) / votenum * 100) / 10
        } else 0.0

        return rating
    }

    override suspend fun getMovie(id: String): Movie {
        initializeService()
        val document = service.getItem(id)
        val itemId = id.substringAfter("newsid=")

        val actors = extractActors(document)
        val filmData = try {
            service.getFilmData(itemId)
        } catch (e: Exception) {
            null
        }
        val trailerURL = filmData ?.meta?.trailer
                                  ?.let { "https://www.youtube.com/watch?v=$it" }
        val poster = filmData ?.meta?.affiche
        val banner = filmData ?.meta?.affiche2

        val votes = document.selectFirst("div.fr-votes")
        val rating = if (votes != null) getRating(votes) else null
        val movie = Movie(
            id = id,
            title = document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: "",
            overview = document.selectFirst("div#s-desc")
                ?.apply {
                    selectFirst("p.desc-text")?.remove()
                }
                ?.text()
                ?.trim()
                ?: "",
            released = document.selectFirst("span.release_date")
                ?.text()
                ?.substringAfter("-")
                ?.trim(),
            runtime = document.select("span.runtime")
                .text().substringAfter(" ").let {
                    val hours = it.substringBefore("h").toIntOrNull() ?: 0
                    val minutes =
                        it.substringAfter("h").trim().toIntOrNull() ?: 0
                    hours * 60 + minutes
                }.takeIf { it != 0 },
            quality = document.selectFirst("span[id=film_quality]")
                ?.text(),
            poster = poster,
            banner = banner,
            trailer = trailerURL,
            genres = document.select("span.genres")
                .select("a").mapNotNull {
                    Genre(
                        id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                        name = it.text(),
                    )
                }.ifEmpty {
                    listOf(Genre(id = "unknown", name = ""))
                },
            directors = document.select("ul#s-list li")
                .find {
                    it.selectFirst("span")?.text()?.contains("alisateur") == true
                }
                ?.select("a")?.mapIndexedNotNull { index, it ->
                    People(
                        id = "director$index",
                        name = it.text(),
                    )
                }
                ?: emptyList(),
            cast = actors.map {
                People(
                    id = it[0].replace(" ", "+"),
                    name = it[0],
                    image = it[1]
                )
            },
            rating = rating

        )

        return movie
    }

    override suspend fun getTvShow(id: String): TvShow {
        initializeService()
        val document = service.getItem(id, "dle_skin=VFV25")
        val actors = extractActors(document)
        val itemId = id.substringAfter("newsid=")

        val tvShowData = try {
            service.getFilmData(itemId)
        } catch (e: Exception) {
            null
        }
        val seasonsData = try {
            service.getSeasonsData(tvShowData?.meta?.tagz?:"")
        } catch (e: Exception) {
            null
        }

        val trailerURL = tvShowData ?.meta?.trailer
            ?.let { "https://www.youtube.com/watch?v=$it" }
        val poster = tvShowData ?.meta?.affiche
        val banner = tvShowData ?.meta?.affiche2
        val votes = document.selectFirst("div.fr-votes")
        val rating = if (votes != null) getRating(votes) else null
        val title = document.selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?: ""

        val seasonNumber = title.substringAfter("Saison ").trim().toIntOrNull() ?: 0

        val seasons = seasonsData
            ?.mapIndexed { idx, season ->
                Season(
                    id = season.id ?: idx.toString(),
                    number = season.title?.substringAfter("Saison ")?.toIntOrNull() ?: (idx + 1),
                    title = season.title ?: "Saison ${idx + 1}",
                    poster = season.affiche
                )
            }
            ?.toMutableList()
            ?: mutableListOf()

        if (seasons.none { it.number == seasonNumber }) {
            seasons.add(
                Season(
                    id = itemId,
                    number = seasonNumber,
                    title = if (title.contains("- Saison")) "Saison "+title.substringAfter("- Saison ") else title,
                    poster = poster
                )
            )
        }

        seasons.sortBy { it.number }

        val tvShow = TvShow(
            id = id,
            title = title.substringBeforeLast("- Saison"),
            overview = document.selectFirst("div.fdesc > p")
                ?.text()
                ?.trim()
                ?: "",
            released = document.selectFirst("span.release")
                ?.text()
                ?.substringBefore("-")
                ?.trim(),
            runtime = document.select("span.runtime")
                .text().substringAfter(" ").let {
                    val hours = it.substringBefore("h").toIntOrNull() ?: 0
                    val minutes =
                        it.substringAfter("h").trim().toIntOrNull() ?: 0
                    hours * 60 + minutes
                }.takeIf { it != 0 },
            quality = document.selectFirst("span[id=film_quality]")
                ?.text(),
            poster = poster,
            banner = banner,
            trailer = trailerURL,
            seasons = seasons,
            genres = document.select("span.genres").text().
                split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map {
                    Genre(
                        id = it,
                        name = it,
                    )
                },
            directors = document.select("ul#s-list li")
                .find {
                    it.selectFirst("span")?.text()?.contains("alisateur") == true
                }
                ?.select("a")?.mapIndexedNotNull { index, it ->
                    People(
                        id = "director$index",
                        name = it.text(),
                    )
                }
                ?: emptyList(),
            cast = actors.map {
                People(
                    id = it[0].replace(" ", "+"),
                    name = it[0],
                    image = it[1]
                )
            },
            rating = rating
        )

        return tvShow
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        initializeService()

        val episodesData = try {
            service.getEpisodesData(seasonId)
        } catch (e: Exception) {
            return emptyList()
        }

        val result = mutableListOf<Episode>()
        var number = 1
        val maps = listOf(episodesData.vf, episodesData.vostfr, episodesData.vo )
        while (maps.any { it?.containsKey(number.toString()) == true }) {
            val info = episodesData.info?.get(number.toString())
            result.add(
                Episode(
                    id = "$seasonId/$number",
                    number = number,
                    poster = info?.poster ?: "",
                    title = info?.title?.replace("\\'", "'") ?: "Episode $number",
                    overview = info?.synopsis?.replace("\\'", "'") ?: ""
                )
            )

            number++
        }

        return result
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        initializeService()
        val document = service.getGenre(id, page)

        val genre = Genre(
            id = id,
            name = "",
            shows = document.select("div#dle-content>div.short").map {
                Movie(
                    id = it.selectFirst("a.short-poster")
                        ?.attr("href")?.substringAfterLast("/")
                        ?: "",
                    title = it.selectFirst("div.short-title")
                        ?.text()
                        ?: "",
                    poster = it.selectFirst("img")
                        ?.attr("src"),
                )
            },
        )

        return genre
    }

    override suspend fun getPeople(id: String, page: Int): People {
        initializeService()

        val document = try {
            service.getPeople(id, page)
        } catch (e: HttpException) {
            when (e.code()) {
                404 -> return People(id, "")
                else -> throw e
            }
        }

        val people = People(
            id = id,
            name = "",
            filmography = document.select("div#dle-content > div.short").mapNotNull {
                val href = it.selectFirst("a.short-poster")
                    ?.attr("href")
                    ?: ""
                val id = href.substringAfterLast("/")

                val title = it.selectFirst("div.short-title")
                    ?.text()
                    ?: ""
                val poster = it.selectFirst("img")
                    ?.attr("src")
                    ?: ""

                if (href.contains("-saison-") || href.contains("s-tv/") || title.contains(" - Saison ")) {
                    TvShow(
                        id = id,
                        title = title,
                        poster = poster,
                    )
                } else if (href.isNotBlank()) {
                    Movie(
                        id = id,
                        title = title,
                        poster = poster,
                    )
                } else {
                    null
                }
            },
        )

        return people
    }

    fun extractTvShowVersions(document: Document, episodesData: EpisodesData? = null): MutableList<String> {
        val versions = listOf("vostfr", "vf", "vo")
        val found = mutableListOf<String>()

        for (version in versions) {
            val hasAtLeastOneEpInJson = episodesData?.let {
                when (version) {
                    "vf" -> !it.vf.isNullOrEmpty()
                    "vostfr" -> !it.vostfr.isNullOrEmpty()
                    "vo" -> !it.vo.isNullOrEmpty()
                    else -> false
                }
            } ?: false

            if (hasAtLeastOneEpInJson) {
                found.add(version)
            }
        }

        return found
    }

    suspend fun extractActors(document: Document): List<List<String>> {
        val scriptContent = document.select("script").joinToString("\n") { it.data() }
        val arrayContentRegex = Regex("""actorData\s*=\s*\[(.*?)];""", RegexOption.DOT_MATCHES_ALL)

        return arrayContentRegex.find(scriptContent)
            ?.groupValues?.get(1)
            ?.let { content ->
                Regex(""""(.+?)\s*\(.*?\)\s*-\s*([^"]+)"""")
                    .findAll(content)
                    .map { match ->
                        val actorName = match.groupValues[1].trim()
                        val poster = match.groupValues[2].trim()
                        listOf(actorName, poster)
                    }
                    .toList()
            } ?: emptyList()
    }

    data class VideoProvider(
        val id: Number,
        val order: Int,
        val name: String,
        val lang: String,
        val url: String
    )

    data class EpisodesData(
        val vf: Map<String, Map<String, String>>? = null,
        val vostfr: Map<String, Map<String, String>>? = null,
        val vo: Map<String, Map<String, String>>? = null,
        val info: Map<String, EpisodeInfo>? = null
    )

    data class EpisodeInfo(
        val title: String? = null,
        val synopsis: String? = null,
        val poster: String? = null
    )

    data class FilmData(
        val players: Map<String, Map<String, String>>? = null,
        val meta: FilmMeta? = null
    )

    data class FilmMeta(
        val affiche: String? = null,
        val affiche2: String? = null,
        val trailer: String? = null,
        val tagz: String? = null,
        val bkp: String? = null
    )

    data class SeasonData(
        val affiche: String? = null,
        val alt_name: String? = null,
        val full_url: String? = null,
        val id: String? = null,
        val serie_annee: String? = null,
        val title: String? = null
    )

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        initializeService()

        val servers = when (videoType) {
            is Video.Type.Episode -> {
                val (tvShowId, tvShowNumber) = id.split("/")
                val episodesData = try {
                    service.getEpisodesData(tvShowId)
                } catch (e: Exception) {
                    null
                }
                val tvShowServers = mutableListOf<Video.Server>()

                episodesData?.vf?.get(tvShowNumber)?.forEach { (provider, url) ->
                    tvShowServers.add(Video.Server(
                        id = "vf$provider",
                        name = provider.replaceFirstChar { it.uppercase() }+" (VF)",
                        src = url
                    ))
                }
                episodesData?.vostfr?.get(tvShowNumber)?.forEach { (provider, url) ->
                    tvShowServers.add(Video.Server(
                        id = "vostfr$provider",
                        name = provider.replaceFirstChar { it.uppercase() }+" (VOSTFR)",
                        src = url
                    ))
                }
                episodesData?.vo?.get(tvShowNumber)?.forEach { (provider, url) ->
                    tvShowServers.add(Video.Server(
                        id = "vo$provider",
                        name = provider.replaceFirstChar { it.uppercase() }+" (VO)",
                        src = url
                    ))
                }
                tvShowServers
            }

            is Video.Type.Movie -> {
                val itemId = id.substringAfter("newsid=")
                val filmData = try {
                    service.getFilmData(itemId)
                } catch (e: Exception) {
                    null
                }
                var serverIndex = 0

                val labels = mapOf(
                    "vff" to "TrueFrench",
                    "vfq" to "French",
                    "vostfr" to "VOSTFR",
                    "vo" to "VO"
                )

                val movieServers = mutableListOf<Video.Server>()

                if (filmData?.players != null) {
                    val langOrder = listOf("vff", "vfq", "vostfr", "vo")

                    filmData.players.forEach { (provider, langMap) ->

                        val seenUrlsForProvider = mutableSetOf<String>()
                        val defaultUrl = langMap["default"]

                        langMap.entries
                            .filter { it.key != "default" }
                            .sortedBy { langOrder.indexOf(it.key).let { i -> if (i == -1) Int.MAX_VALUE else i } }
                            .forEach { (lang, url) ->

                                if (url.startsWith("http") || url.isNotBlank()) {

                                    if (seenUrlsForProvider.add(url) && !ignoreSource(provider, url)) {

                                        val langLabel = labels[lang] ?: lang
                                        val displayName =
                                            provider.replaceFirstChar { it.uppercase() } +
                                                    if (langLabel.isNotBlank()) " ($langLabel)" else ""

                                        movieServers.add(
                                            Video.Server(
                                                id = "vid${serverIndex++}",
                                                name = displayName,
                                                src = url
                                            )
                                        )
                                    }
                                }
                            }

                        // only add the "default" URL if it is not already present
                        if (!defaultUrl.isNullOrBlank()
                            && defaultUrl !in seenUrlsForProvider
                            && !ignoreSource(provider, defaultUrl)
                        ) {
                            movieServers.add(
                                Video.Server(
                                    id = "vid${serverIndex++}",
                                    name = provider.replaceFirstChar { it.uppercase() },
                                    src = defaultUrl
                                )
                            )
                        }
                    }
                }

                movieServers
            }
        }

        return servers
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val finalUrl = if (server.src.contains("kokoflix.lol", ignoreCase = true) || server.src.contains("kakaflix.lol", ignoreCase = true) || server.src.contains("newPlayer.php", ignoreCase = true)) {
            val response = service.getRedirectLink(server.src, baseUrl)
                .let { response -> response.raw() as okhttp3.Response }
            response.request.url.toString()
        } else {
            server.src
        }

        val video = Extractor.extract(finalUrl)

        return video
    }

    /**
     * Initializes the service with the current domain URL.
     * This function is necessary because the provider's domain frequently changes.
     * We fetch the latest URL from a dedicated website that tracks these changes.
     */
    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            if (forceRefresh || UserPreferences.getProviderCache(this,UserPreferences.PROVIDER_AUTOUPDATE) != "false") {
                val addressService = Service.buildAddressFetcher()
                try {
                    val document = addressService.getHome()

                    val newUrl = document.select("div.container > div.url-card")
                        .selectFirst("a")
                        ?.attr("href")
                        ?.trim()
                    if (!newUrl.isNullOrEmpty()) {
                        val newUrl = if (newUrl.endsWith("/")) newUrl else "$newUrl/"
                        UserPreferences.setProviderCache(this,UserPreferences.PROVIDER_URL, newUrl)
                        UserPreferences.setProviderCache(
                            this,
                            UserPreferences.PROVIDER_LOGO,
                            newUrl + "favicon-96x96.png"
                        )
                    }
                } catch (e: Exception) {
                    // In case of failure, we'll use the default URL
                    // No need to throw as we already have a fallback URL
                }
            }
            service = Service.build(baseUrl)
            serviceInitialized = true
        }
        return baseUrl
    }

    private suspend fun initializeService() {
        initializationMutex.withLock {
            if (serviceInitialized) return
            onChangeUrl()
        }
    }

    private interface Service {

        companion object {
            private val client = OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .dns(DnsResolver.doh)
                .addInterceptor { chain ->
                    val original = chain.request()
                    val cookie = original.header("Cookie")
                    val requestBuilder = original.newBuilder()
                    if (cookie != null) {
                        requestBuilder.header("Cookie", "$cookie; fsschal=1")
                    } else {
                        requestBuilder.header("Cookie", "fsschal=1")
                    }
                    chain.proceed(requestBuilder.build())
                }
                .build()

            fun buildAddressFetcher(): Service {
                val addressRetrofit = Retrofit.Builder()
                    .baseUrl(portalUrl)

                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)

                    .build()

                return addressRetrofit.create(Service::class.java)
            }

            fun build(baseUrl: String): Service {
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()

                return retrofit.create(Service::class.java)
            }
        }

        @GET(".")
        suspend fun getHome(
            @Header("Cookie") cookie: String = "dle_skin=VFV1"
        ): Document

        @FormUrlEncoded
        @POST(".")
        suspend fun postHome(
            @Field("skin_name") skinName: String = "VFV25",
            @Field("action_skin_change") actionSkinChange: String = "yes",
            @Header("Cookie") cookie: String = "dle_skin=VFV25"
        ): Document

        @FormUrlEncoded
        @POST("engine/ajax/search.php")
        suspend fun search(
            @Field("query") query: String,
            @Field("page") page: Int = 1
        ): Document

        @GET("films/page/{page}/")
        suspend fun getMovies(@Path("page") page: Int): Document

        @GET("s-tv/page/{page}")
        suspend fun getTvShows(@Path("page") page: Int): Document

        @GET
        suspend fun getItem(
            @Url url: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1"
        ): Document

        @GET("films/{id}")
        suspend fun getMovie(
            @Path(value = "id", encoded = true) id: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1"
        ): Document

        @GET("s-tv/{id}")
        suspend fun getTvShow(
            @Path(value = "id", encoded = true) id: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1"
        ): Document


        @GET("film-en-streaming/{genre}/page/{page}")
        suspend fun getGenre(
            @Path(value = "genre", encoded = true) genre: String,
            @Path("page") page: Int,
        ): Document

        @GET("xfsearch/actors/{id}/page/{page}")
        suspend fun getPeople(
            @Path(value = "id", encoded = true) id: String,
            @Path("page") page: Int,
        ): Document

        @GET("engine/ajax/sx.php")
        suspend fun getEpisodesData(
            @Query("id") id: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1",
            @Header("X-Requested-With") requestedWith: String = "XMLHttpRequest"
        ): EpisodesData

        @GET("engine/ajax/film_api.php")
        suspend fun getFilmData(
            @Query("id") id: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1",
            @Header("X-Requested-With") requestedWith: String = "XMLHttpRequest"
        ): FilmData

        @POST("engine/ajax/get_seasons.php")
        @FormUrlEncoded
        suspend fun getSeasonsData(
            @Field("serie_tag") id: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1",
            @Header("X-Requested-With") requestedWith: String = "XMLHttpRequest"
        ): List<SeasonData>

        @GET
        suspend fun getRedirectLink(
            @Url url: String,
            @Header("Referer") referer: String,
            @Header("User-Agent") userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
        ): Response<ResponseBody>
    }
}
