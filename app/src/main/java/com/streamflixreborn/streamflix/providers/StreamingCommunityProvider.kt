package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.google.gson.annotations.SerializedName
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.VixcloudExtractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.InertiaUtils
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TmdbUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject
import com.google.gson.Gson
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query
import android.os.Looper
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup

class StreamingCommunityProvider(private val _language: String? = null) : Provider {

    private val mutex = Mutex()
    private val totalCounts = mutableMapOf<String, Int>()

    override val language: String
        get() = _language ?: UserPreferences.providerLanguage ?: "it"

    private val LANG: String
        get() = if (language == "en") "en" else "it"

    private val TAG: String
        get() = "SCProviderDebug[$LANG]"

    private val DEFAULT_DOMAIN: String = "streamingunity.cc"
    private val BLOCKED_DOMAINS = setOf("streamingcommunityz.green", "streamingunity.club", "streamingunity.bike", "streamingcommunityz.buzz")
    override val baseUrl = DEFAULT_DOMAIN
    private var _domain: String? = null
    private var domain: String
        get() {
            if (!_domain.isNullOrEmpty())
                return _domain!!

            val storedDomain = UserPreferences.streamingcommunityDomain

            if (storedDomain.isNullOrEmpty() || BLOCKED_DOMAINS.any { storedDomain.contains(it) }) {
                if (!storedDomain.isNullOrEmpty()) UserPreferences.streamingcommunityDomain = DEFAULT_DOMAIN
                _domain = DEFAULT_DOMAIN
            } else {
                _domain = storedDomain
            }

            return _domain!!
        }
        set(value) {
            val currentDomain = _domain ?: UserPreferences.streamingcommunityDomain.ifEmpty { DEFAULT_DOMAIN }
            if (value != currentDomain) {
                Log.d(TAG, "Domain changed via setter from $currentDomain to $value")
                UserPreferences.clearProviderCache(name)
                _domain = value
                UserPreferences.streamingcommunityDomain = value
                invalidateService()
            }
        }

    override val name: String
        get() = if (language == "it") "StreamingCommunity" else "StreamingCommunity (EN)"

    override val logo get() = "https://${domain.ifEmpty { DEFAULT_DOMAIN }}/apple-touch-icon.png"
    
    private val MAX_SEARCH_RESULTS = 60

    private var _service: StreamingCommunityService? = null
    private var _serviceLanguage: String? = null
    private var _serviceDomain: String? = null

    private fun invalidateService() {
        _service = null
        _serviceLanguage = null
        _serviceDomain = null
        version = ""
    }

    private suspend fun getService(): StreamingCommunityService {
        return mutex.withLock {
            val currentLang = language
            val currentDom = domain
            if (_service == null || _serviceLanguage != currentLang || _serviceDomain != currentDom) {
                Log.d(TAG, "Building service for: https://$currentDom/ with lang $currentLang")
                val finalBase = resolveFinalBaseUrl("https://$currentDom/")
                val host = finalBase.substringAfter("https://").substringBefore("/")
                
                _serviceLanguage = currentLang
                _serviceDomain = host
                _domain = host
                _service = StreamingCommunityService.build(finalBase, currentLang, { domain }, { nd ->
                    _domain = nd
                    UserPreferences.streamingcommunityDomain = nd
                }, LANG)
            }
            _service!!
        }
    }

    suspend fun rebuildService(newDomain: String = domain) {
        mutex.withLock {
            val prefsDomain = UserPreferences.streamingcommunityDomain
            val desiredDomain = if (!prefsDomain.isNullOrEmpty() && prefsDomain != domain && prefsDomain != newDomain) prefsDomain else newDomain
            
            Log.d(TAG, "Forcing service rebuild for: https://$desiredDomain/")
            val finalBase = resolveFinalBaseUrl("https://$desiredDomain/")
            val host = finalBase.substringAfter("https://").substringBefore("/")
            
            _domain = host
            UserPreferences.streamingcommunityDomain = host
            _serviceLanguage = language
            _serviceDomain = host
            _service = StreamingCommunityService.build(finalBase, language, { domain }, { nd ->
                _domain = nd
                UserPreferences.streamingcommunityDomain = nd
            }, LANG)
        }
    }

    private fun resolveFinalBaseUrl(startBaseUrl: String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return startBaseUrl

        return try {
            val client = NetworkClient.default.newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor(RefererInterceptor(startBaseUrl))
                .addInterceptor(UserAgentInterceptor(StreamingCommunityService.USER_AGENT, { language }))
                .build()
            
            val req = okhttp3.Request.Builder()
                .url(startBaseUrl)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val finalUri = resp.request.url
                finalUri.scheme + "://" + finalUri.host + "/"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving final URL: ${e.message}")
            startBaseUrl
        }
    }

    private suspend fun <T> withSslFallback(block: suspend (StreamingCommunityService) -> T): T {
        return try {
            block(getService())
        } catch (e: Exception) {
            val isSsl = e is javax.net.ssl.SSLHandshakeException || e is java.security.cert.CertPathValidatorException
            if (!isSsl) throw e
            
            mutex.withLock {
                val finalBase = resolveFinalBaseUrl("https://$domain/")
                val host = finalBase.substringAfter("https://").substringBefore("/")
                _domain = host
                _serviceDomain = host
                _service = StreamingCommunityService.buildUnsafe(finalBase, language, LANG)
            }
            block(getService())
        }
    }

    private var version: String? = ""

    private suspend fun ensureVersion(): String {
        version?.takeIf { it.isNotEmpty() }?.let { return it }

        val json = InertiaUtils.parseInertiaData(withSslFallback { it.getHome() })
        return Gson().fromJson(json.toString(), StreamingCommunityService.HomeRes::class.java)
            .let { 
                val v = it.version ?: ""
                version = v
                v
            }
    }

    private fun getImageLink(filename: String?): String? {
        if (filename.isNullOrEmpty()) return null
        return "https://cdn.$domain/images/$filename"
    }

    override suspend fun getHome(): List<Category> {
        val res: StreamingCommunityService.HomeRes = try {
            if (version.isNullOrEmpty()) {
                val json = InertiaUtils.parseInertiaData(withSslFallback { it.getHome() })
                Gson().fromJson(json.toString(), StreamingCommunityService.HomeRes::class.java).also {
                    if (version != it.version) version = it.version ?: ""
                }
            } else {
                try {
                    withSslFallback { it.getHome(version = version!!) }.also { fetched ->
                        if (version != fetched.version) version = fetched.version ?: ""
                    }
                } catch (e: Exception) {
                    val json = InertiaUtils.parseInertiaData(withSslFallback { it.getHome() })
                    Gson().fromJson(json.toString(), StreamingCommunityService.HomeRes::class.java).also {
                        if (version != it.version) version = it.version ?: ""
                    }
                }
            }
        } catch (e: Exception) { throw e }

        val sliders = res.props?.sliders ?: listOf()
        val categories = mutableListOf<Category>()

        // Helper per il mapping
        fun mapTitles(titles: List<StreamingCommunityService.Show>) = titles.map {
            if (it.type == "movie") Movie(id = it.id + "-" + it.slug, title = it.name, released = it.lastAirDate, rating = it.score?.toDoubleOrNull(), poster = getImageLink(it.images.find { img -> img.type == "poster" }?.filename), banner = getImageLink(it.images.find { img -> img.type == "background" }?.filename))
            else TvShow(id = it.id + "-" + it.slug, title = it.name, released = it.lastAirDate, rating = it.score?.toDoubleOrNull(), poster = getImageLink(it.images.find { img -> img.type == "poster" }?.filename), banner = getImageLink(it.images.find { img -> img.type == "background" }?.filename))
        }

        // 1. Identifichiamo il carosello in evidenza (Hero)
        val heroSlider = sliders.find { it.name == "hero" } ?: sliders.firstOrNull()
        if (heroSlider != null) {
            categories.add(Category(name = Category.FEATURED, list = mapTitles(heroSlider.titles).take(10)))
        }

        // 2. Mappiamo gli slider conosciuti con nomi italiani standard
        val processedSliderNames = mutableSetOf<String>()
        if (heroSlider != null) processedSliderNames.add(heroSlider.name)

        sliders.forEach { slider ->
            val titles = slider.titles
            if (titles.isEmpty()) return@forEach
            
            val italianName = when {
                slider.name.contains("trending", true) -> "I titoli del momento"
                slider.name.contains("latest-movies", true) -> "Film aggiunti di recente"
                slider.name.contains("latest-tv-shows", true) -> "Serie TV aggiunte di recente"
                slider.name.contains("top-10", true) -> "Top 10 titoli di oggi"
                slider.name.contains("upcoming", true) -> "In arrivo"
                slider.name.contains("new-releases", true) -> "Nuove uscite"
                else -> null
            }

            if (italianName != null) {
                categories.add(Category(italianName, mapTitles(titles)))
                processedSliderNames.add(slider.name)
            }
        }

        // 3. Aggiungiamo i fallback da props (se non già aggiunti dagli slider)
        val propsMapping = listOf(
            "I titoli del momento" to (res.props?.trendingTitles ?: res.props?.trending),
            "Film aggiunti di recente" to res.props?.latestMovies,
            "Serie TV aggiunte di recente" to res.props?.latestTvShows,
            "Top 10 titoli di oggi" to (res.props?.top10Titles ?: res.props?.top10),
            "In arrivo" to (res.props?.upcomingTitles ?: res.props?.upcoming)
        )
        propsMapping.forEach { (name, list) ->
            if (list != null && list.isNotEmpty()) {
                categories.add(Category(name, mapTitles(list)))
            }
        }

        // 4. Aggiungiamo tutti gli altri slider non ancora processati
        sliders.forEach { slider ->
            if (!processedSliderNames.contains(slider.name) && slider.titles.isNotEmpty()) {
                categories.add(Category(slider.label ?: slider.name, mapTitles(slider.titles)))
            }
        }

        // 5. Aggiungiamo le sezioni ArchivePage
        val archiveSections = listOf(
            "Film" to res.props?.movies,
            "Serie TV" to res.props?.tvShows,
            "Titoli" to res.props?.titles,
            "TV" to res.props?.tv,
            "Archivio" to res.props?.archive
        )
        archiveSections.forEach { (name, page) ->
            page?.data?.let { if (it.isNotEmpty()) categories.add(Category(name, mapTitles(it))) }
        }

        return categories
            .filter { it.list.isNotEmpty() }
            .distinctBy { it.name.lowercase().trim() }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            val currentVersion = ensureVersion()
            val res = try {
                withSslFallback { it.getHome(version = currentVersion) }
            } catch (e: Exception) {
                val json = InertiaUtils.parseInertiaData(withSslFallback { it.getHome() })
                Gson().fromJson(json.toString(), StreamingCommunityService.HomeRes::class.java)
            }
            if (version != res.version) version = res.version ?: ""
            return res.props?.genres?.map { Genre(id = it.id, name = it.name) }?.sortedBy { it.name } ?: listOf()
        }
        val res = withSslFallback { it.search(query, page, LANG) }
        if (res.currentPage == null || (res.lastPage != null && res.currentPage > res.lastPage)) return listOf()
        return res.data.map {
            val poster = getImageLink(it.images.find { img -> img.type == "poster" }?.filename)
            if (it.type == "movie") Movie(id = it.id + "-" + it.slug, title = it.name, released = it.lastAirDate, rating = it.score?.toDoubleOrNull(), poster = poster)
            else TvShow(id = it.id + "-" + it.slug, title = it.name, released = it.lastAirDate, rating = it.score?.toDoubleOrNull(), poster = poster)
        }
    }

    private fun getTitlesFromInertiaJson(json: JSONObject): List<StreamingCommunityService.Show> {
        val gson = Gson()
        val showListType: Type = object : TypeToken<List<StreamingCommunityService.Show>>() {}.type
        val props = json.optJSONObject("props") ?: return listOf()

        if (props.has("titles") && props.optJSONArray("titles") != null) {
            val jsonArray = props.optJSONArray("titles")
            return gson.fromJson<List<StreamingCommunityService.Show>>(jsonArray?.toString() ?: "[]", showListType) ?: listOf()
        }

        val res: StreamingCommunityService.ArchiveRes? = try {
            gson.fromJson(json.toString(), StreamingCommunityService.ArchiveRes::class.java)
        } catch (e: Exception) { null }

        res?.version?.let { version = it ?: "" }
        return res?.props?.let { p -> p.archive?.data ?: p.titles?.data ?: p.movies?.data ?: p.tv?.data ?: p.tvShows?.data } ?: listOf()
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val offset = (page - 1) * 60
        val shows = try {
            if (page == 1) {
                val json = InertiaUtils.parseInertiaData(withSslFallback { it.getMoviesHtml() })
                getTitlesFromInertiaJson(json)
            } else {
                withSslFallback { it.getArchiveApi(lang = language, offset = offset, type = "movie") }.titles
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching movies page $page: ${e.message}")
            listOf()
        }

        return shows.map { title ->
            Movie(id = title.id + "-" + title.slug, title = title.name, released = title.lastAirDate, rating = title.score?.toDoubleOrNull(), poster = getImageLink(title.images.find { img -> img.type == "poster" }?.filename))
        }.distinctBy { it.id }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val offset = (page - 1) * 60
        val shows = try {
            if (page == 1) {
                val json = InertiaUtils.parseInertiaData(withSslFallback { it.getTvShowsHtml() })
                getTitlesFromInertiaJson(json)
            } else {
                withSslFallback { it.getArchiveApi(lang = language, offset = offset, type = "tv") }.titles
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching tv shows page $page: ${e.message}")
            listOf()
        }

        return shows.map { title ->
            TvShow(id = title.id + "-" + title.slug, title = title.name, released = title.lastAirDate, rating = title.score?.toDoubleOrNull(), poster = getImageLink(title.images.find { img -> img.type == "poster" }?.filename))
        }.distinctBy { it.id }
    }

    override suspend fun getMovie(id: String): Movie = coroutineScope {
        val resDeferred = async {
            val currentVersion = ensureVersion()
            try {
                withSslFallback { it.getDetails(id, version = currentVersion, language = LANG) }.also {
                    if (version != it.version) version = it.version ?: ""
                }
            } catch (e: Exception) {
                // Se riceviamo 401 o altro errore Inertia, ripieghiamo sull'HTML puro (Shadow Bypass)
                Log.w(TAG, "Inertia getDetails failed ($e), falling back to HTML parsing")
                val doc = StreamingCommunityService.fetchDocumentWithRedirectsAndSslFallback("https://$domain/$LANG/titles/$id", "https://$domain/", language)
                val json = InertiaUtils.parseInertiaData(doc)
                Gson().fromJson(json.toString(), StreamingCommunityService.HomeRes::class.java).also {
                    if (version != it.version) version = it.version ?: ""
                }
            }
        }

        var res = resDeferred.await()
        if (res.props == null) {
            Log.w(TAG, "Inertia getDetails returned null props, falling back to HTML parsing")
            val doc = StreamingCommunityService.fetchDocumentWithRedirectsAndSslFallback("https://$domain/$LANG/titles/$id", "https://$domain/", language)
            val json = InertiaUtils.parseInertiaData(doc)
            res = Gson().fromJson(json.toString(), StreamingCommunityService.HomeRes::class.java).also {
                if (version != it.version) version = it.version ?: ""
            }
        }
        val title = res.props!!.title
        val tmdbMovieDeferred = async { title.tmdbId?.let { TmdbUtils.getMovieById(it, language = language) } }
        val tmdbMovie = tmdbMovieDeferred.await()

        return@coroutineScope Movie(
            id = id, title = tmdbMovie?.title ?: title.name, overview = tmdbMovie?.overview ?: title.plot, released = title.lastAirDate, rating = title.score?.toDoubleOrNull(), quality = title.quality, runtime = title.runtime, 
            poster = getImageLink(title.images.find { img -> img.type == "poster" }?.filename), banner = getImageLink(title.images.find { img -> img.type == "background" }?.filename), 
            genres = title.genres?.map { Genre(id = it.id, name = it.name) } ?: listOf(), 
            cast = title.actors?.map { actor ->
                val tmdbPerson = tmdbMovie?.cast?.find { p -> p.name.equals(actor.name, ignoreCase = true) }
                People(id = actor.name, name = actor.name, image = tmdbPerson?.image)
            } ?: listOf(), 
            trailer = title.trailers?.find { t -> t.youtubeId != "" }?.youtubeId?.let { yid -> "https://youtube.com/watch?v=$yid" }, 
            recommendations = res.props.sliders?.find { it.titles.isNotEmpty() }?.titles?.map { 
                if (it.type == "movie") Movie(id = it.id + "-" + it.slug, title = it.name, rating = it.score?.toDoubleOrNull(), poster = getImageLink(it.images.find { img -> img.type == "poster" }?.filename))
                else TvShow(id = it.id + "-" + it.slug, title = it.name, rating = it.score?.toDoubleOrNull(), poster = getImageLink(it.images.find { img -> img.type == "poster" }?.filename))
            } ?: listOf()
        )
    }

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        val resDeferred = async {
             val currentVersion = ensureVersion()
             try {
                withSslFallback { it.getDetails(id, version = currentVersion, language = LANG) }.also {
                    if (version != it.version) version = it.version ?: ""
                }
            } catch (e: Exception) {
                Log.w(TAG, "Inertia getDetails failed ($e), falling back to HTML parsing")
                val doc = StreamingCommunityService.fetchDocumentWithRedirectsAndSslFallback("https://$domain/$LANG/titles/$id", "https://$domain/", language)
                val json = InertiaUtils.parseInertiaData(doc)
                Gson().fromJson(json.toString(), StreamingCommunityService.HomeRes::class.java).also {
                    if (version != it.version) version = it.version ?: ""
                }
            }
        }

        var res = resDeferred.await()
        if (res.props == null) {
            Log.w(TAG, "Inertia getDetails returned null props, falling back to HTML parsing")
            val doc = StreamingCommunityService.fetchDocumentWithRedirectsAndSslFallback("https://$domain/$LANG/titles/$id", "https://$domain/", language)
            val json = InertiaUtils.parseInertiaData(doc)
            res = Gson().fromJson(json.toString(), StreamingCommunityService.HomeRes::class.java).also {
                if (version != it.version) version = it.version ?: ""
            }
        }
        val title = res.props!!.title
        val tmdbShowDeferred = async { title.tmdbId?.let { TmdbUtils.getTvShowById(it, language = language) } }
        val tmdbShow = tmdbShowDeferred.await()

        return@coroutineScope TvShow(id = id, title = tmdbShow?.title ?: title.name, overview = tmdbShow?.overview ?: title.plot, released = title.lastAirDate, rating = title.score?.toDoubleOrNull(), quality = title.quality, 
            poster = getImageLink(title.images.find { img -> img.type == "poster" }?.filename), banner = getImageLink(title.images.find { img -> img.type == "background" }?.filename), 
            genres = title.genres?.map { Genre(id = it.id, name = it.name) } ?: listOf(), 
            cast = title.actors?.map { actor ->
                val tmdbPerson = tmdbShow?.cast?.find { p -> p.name.equals(actor.name, ignoreCase = true) }
                People(id = actor.name, name = actor.name, image = tmdbPerson?.image)
            } ?: listOf(), 
            trailer = title.trailers?.find { t -> t.youtubeId != "" }?.youtubeId?.let { yid -> "https://youtube.com/watch?v=$yid" }, 
            recommendations = res.props.sliders?.find { it.titles.isNotEmpty() }?.titles?.map {
                if (it.type == "movie") Movie(id = it.id + "-" + it.slug, title = it.name, rating = it.score?.toDoubleOrNull(), poster = getImageLink(it.images.find { img -> img.type == "poster" }?.filename))
                else TvShow(id = it.id + "-" + it.slug, title = it.name, rating = it.score?.toDoubleOrNull(), poster = getImageLink(it.images.find { img -> img.type == "poster" }?.filename))
            } ?: listOf(), 
            seasons = title.seasons?.map { s ->
                val seasonNumber = s.number.toIntOrNull() ?: (title.seasons.indexOf(s) + 1)
                Season(id = "$id/season-${s.number}", number = seasonNumber, title = s.name, poster = tmdbShow?.seasons?.find { ts -> ts.number == seasonNumber }?.poster)
        } ?: listOf())
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val currentVersion = ensureVersion()
        val res: StreamingCommunityService.SeasonRes = try {
            withSslFallback { it.getSeasonDetails(seasonId, version = currentVersion, language = LANG) }.also {
                if (version != it.version) version = it.version ?: ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Inertia getSeasonDetails failed ($e), falling back to HTML parsing")
            val doc = StreamingCommunityService.fetchDocumentWithRedirectsAndSslFallback("https://$domain/$LANG/titles/$seasonId", "https://$domain/", language)
            val json = InertiaUtils.parseInertiaData(doc)
            Gson().fromJson(json.toString(), StreamingCommunityService.SeasonRes::class.java).also {
                if (version != it.version) version = it.version ?: ""
            }
        }
        return res.props!!.loadedSeason.episodes.map {
            Episode(id = "${seasonId.substringBefore("-")}?episode_id=${it.id}", number = it.number.toIntOrNull() ?: (res.props!!.loadedSeason.episodes.indexOf(it) + 1), title = it.name, poster = getImageLink(it.images.find { img -> img.type == "cover" }?.filename), overview = it.plot)
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val name = ""
        val offset = (page - 1) * 60
        
        // Skip if we already know there's no more data
        totalCounts[id]?.let { total ->
            if (offset >= total) return Genre(id = id, name = name, shows = emptyList())
        }

        val shows = try {
            if (page == 1) {
                val json = InertiaUtils.parseInertiaData(withSslFallback { it.getArchiveHtml(genreId = id) })
                val props = json.optJSONObject("props")
                if (props != null) {
                    val total = props.optInt("totalCount", 0)
                    if (total > 0) totalCounts[id] = total
                }
                getTitlesFromInertiaJson(json)
            } else {
                withSslFallback { it.getArchiveApi(lang = language, offset = offset, genreId = id) }.titles
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching genre $id page $page: ${e.message}")
            listOf()
        }

        return Genre(id = id, name = name, shows = shows.map { title ->
            val poster = getImageLink(title.images.find { img -> img.type == "poster" }?.filename)
            if (title.type == "movie") Movie(id = title.id + "-" + title.slug, title = title.name, released = title.lastAirDate, rating = title.score?.toDoubleOrNull(), poster = poster)
            else TvShow(id = title.id + "-" + title.slug, title = title.name, released = title.lastAirDate, rating = title.score?.toDoubleOrNull(), poster = poster)
        })
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val res = withSslFallback { it.search(id, page, LANG) }
        if (res.currentPage == null || (res.lastPage != null && res.currentPage > res.lastPage)) return People(id = id, name = id)
        return People(id = id, name = id, filmography = res.data.map {
            val poster = getImageLink(it.images.find { img -> img.type == "poster" }?.filename)
            if (it.type == "movie") Movie(id = it.id + "-" + it.slug, title = it.name, released = it.lastAirDate, rating = it.score?.toDoubleOrNull(), poster = poster)
            else TvShow(id = it.id + "-" + it.slug, title = it.name, released = it.lastAirDate, rating = it.score?.toDoubleOrNull(), poster = poster)
        })
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        Log.i("StreamFlixES", "[PROV] -> StreamingCommunity: getServers for $id")
        val base = "https://$domain/"
        
        val isEpisode = videoType is Video.Type.Episode || id.contains("?episode_id=")
        val iframeUrl = if (isEpisode) {
            base + "$LANG/iframe/" + id.substringBefore("?") + "?episode_id=" + id.substringAfter("episode_id=").substringBefore("&") + "&next_episode=1" + "&language=$LANG"
        } else {
            base + "$LANG/iframe/" + id.substringBefore("-") + "?language=$LANG"
        }
        
        Log.d(TAG, "Fetching iframe from: $iframeUrl")
        val document = StreamingCommunityService.fetchDocumentWithRedirectsAndSslFallback(iframeUrl, base, language)
        val src = document.selectFirst("iframe")?.attr("src") ?: ""

        if (src.isEmpty()) {
            Log.e(TAG, "No iframe found in /iframe/ endpoint")
            return listOf()
        }

        Log.i("StreamFlixES", "[PROV] -> Found Vixcloud src: $src")
        return listOf(Video.Server(id = id, name = "Vixcloud", src = src))
    }

    override suspend fun getVideo(server: Video.Server): Video {
        Log.i("StreamFlixES", "[SERVER] -> StreamingCommunity: Using ${server.name} (Src: ${server.src})")
        
        val base = "https://$domain/"
        val isEpisode = server.id.contains("?episode_id=")
        val iframeUrl = if (isEpisode) {
            base + "$LANG/iframe/" + server.id.substringBefore("?") + "?episode_id=" + server.id.substringAfter("episode_id=").substringBefore("&") + "&next_episode=1" + "&language=$LANG"
        } else {
            base + "$LANG/iframe/" + server.id.substringBefore("-") + "?language=$LANG"
        }

        return try {
            VixcloudExtractor(language, customReferer = iframeUrl).extract(server.src)
        } catch (e: Exception) {
            val isGone = e.message?.contains("410") == true
            if (isGone) {
                Log.w(TAG, "Vixcloud token probably expired (410), retrying by re-fetching iframe...")
                val document = StreamingCommunityService.fetchDocumentWithRedirectsAndSslFallback(iframeUrl, base, language)
                val newSrc = document.selectFirst("iframe")?.attr("src") ?: throw e
                VixcloudExtractor(language, customReferer = iframeUrl).extract(newSrc)
            } else {
                throw e
            }
        }
    }

    private class UserAgentInterceptor(private val userAgent: String, private val languageProvider: () -> String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val language = languageProvider()
            val requestBuilder = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .header("Accept-Language", if (language == "en") "en-US,en;q=0.9" else "it-IT,it;q=0.9")
                .header("Cookie", "language=$language")
            return chain.proceed(requestBuilder.build())
        }
    }

    private class RefererInterceptor(private val referer: String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request().newBuilder().header("Referer", referer).build())
    }

    private class RedirectInterceptor(private val domainProvider: () -> String, private val onDomainChanged: (String) -> Unit) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var request = chain.request()
            var response = chain.proceed(request)
            val visited = mutableSetOf<String>()
            val currentDomain = domainProvider()
            while (response.isRedirect) {
                val location = response.header("Location") ?: break
                val newUrl = if (location.startsWith("http")) location else request.url.resolve(location)?.toString() ?: break
                if (!visited.add(newUrl)) break
                val host = newUrl.substringAfter("https://").substringBefore("/")
                if (host.isNotEmpty() && host != currentDomain && !host.contains("streamingcommunityz.green") && !host.contains("streamingunity.club") && !host.contains("streamingunity.bike") && !host.contains("streamingcommunityz.buzz")) onDomainChanged(host)
                response.close()
                request = request.newBuilder().url(newUrl).build()
                response = chain.proceed(request)
            }
            return response
        }
    }

    private interface StreamingCommunityService {
        companion object {
            const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

            fun build(baseUrl: String, language: String, domainProvider: () -> String, onDomainChanged: (String) -> Unit, lang: String): StreamingCommunityService {
                val client = NetworkClient.default.newBuilder()
                    .addInterceptor(RefererInterceptor(baseUrl))
                    .addInterceptor(UserAgentInterceptor(USER_AGENT, { language }))
                    .addInterceptor(RedirectInterceptor(domainProvider, onDomainChanged))
                    .build()

                return Retrofit.Builder()
                    .baseUrl("$baseUrl$lang/")
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()
                    .create(StreamingCommunityService::class.java)
            }

            fun buildUnsafe(baseUrl: String, language: String, lang: String): StreamingCommunityService {
                val client = NetworkClient.trustAll.newBuilder()
                    .addInterceptor(RefererInterceptor(baseUrl))
                    .addInterceptor(UserAgentInterceptor(USER_AGENT, { language }))
                    .build()

                return Retrofit.Builder()
                    .baseUrl("$baseUrl$lang/")
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()
                    .create(StreamingCommunityService::class.java)
            }

            fun fetchDocumentWithRedirectsAndSslFallback(url: String, referer: String, language: String): Document {
                val client = NetworkClient.default.newBuilder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .addInterceptor(RefererInterceptor(referer))
                    .addInterceptor(UserAgentInterceptor(USER_AGENT, { language }))
                    .build()
                
                return try {
                    client.newCall(okhttp3.Request.Builder().url(url).header("X-Requested-With", "XMLHttpRequest").get().build()).execute().use { resp ->
                        Jsoup.parse(resp.body?.string() ?: "")
                    }
                } catch (e: Exception) { Jsoup.parse("") }
            }
        }

        @GET("./") suspend fun getHome(): Document
        @GET("./") suspend fun getHome(@Header("x-inertia") xInertia: String = "true", @Header("x-inertia-version") version: String, @Header("X-Requested-With") xRequestedWith: String = "XMLHttpRequest"): HomeRes
        @GET("archive?type=movie") suspend fun getMoviesHtml(): Document
        @GET("archive?type=tv") suspend fun getTvShowsHtml(): Document
        @GET("search") suspend fun search(@Query("q", encoded = true) keyword: String, @Query("page") page: Int = 1, @Query("lang") language: String, @Header("Accept") accept: String = "application/json, text/plain, */*"): SearchRes
        @GET("/api/archive") suspend fun getArchiveApi(@Query("lang") lang: String, @Query("offset") offset: Int, @Query("genre[]") genreId: String? = null, @Query("type") type: String? = null): ApiArchiveRes
        @GET("archive") suspend fun getArchiveHtml(@Query("genre[]") genreId: String): Document
        @GET("titles/{id}") suspend fun getDetails(@Path("id") id: String, @Header("x-inertia") xInertia: String = "true", @Header("x-inertia-version") version: String, @Query("lang") language: String, @Header("X-Requested-With") xRequestedWith: String = "XMLHttpRequest"): HomeRes
        @GET("titles/{id}/") suspend fun getSeasonDetails(@Path("id") id: String, @Header("x-inertia") xInertia: String = "true", @Header("x-inertia-version") version: String, @Query("lang") language: String, @Header("X-Requested-With") xRequestedWith: String = "XMLHttpRequest"): SeasonRes

        data class Image(val filename: String, val type: String)
        data class Genre(val id: String, val name: String)
        data class Actor(val id: String, val name: String)
        data class Trailer(@SerializedName("youtube_id") val youtubeId: String?)
        data class Season(val number: String, val name: String?)
        data class Show(val id: String, val name: String, val type: String, @SerializedName("tmdb_id") val tmdbId: Int?, val score: String?, @SerializedName("last_air_date") val lastAirDate: String?, val images: List<Image>, val slug: String, val plot: String?, val genres: List<Genre>?, @SerializedName("main_actors") val actors: List<Actor>?, val trailers: List<Trailer>?, val seasons: List<Season>?, val quality: String?, val runtime: Int?)
        data class Slider(val label: String?, val name: String, val titles: List<Show>)
        data class Props(
            val genres: List<Genre>,
            val sliders: List<Slider>?,
            val archive: ArchivePage?,
            val titles: ArchivePage?,
            val movies: ArchivePage?,
            val tv: ArchivePage?,
            @SerializedName("tv_shows") val tvShows: ArchivePage?,
            @SerializedName("latest_movies") val latestMovies: List<Show>?,
            @SerializedName("latest_tv_shows") val latestTvShows: List<Show>?,
            @SerializedName("trending_titles") val trendingTitles: List<Show>?,
            @SerializedName("trending") val trending: List<Show>?,
            @SerializedName("top_10_titles") val top10Titles: List<Show>?,
            @SerializedName("top_10") val top10: List<Show>?,
            @SerializedName("upcoming_titles") val upcomingTitles: List<Show>?,
            @SerializedName("upcoming") val upcoming: List<Show>?,
            val title: Show
        )
        data class HomeRes(val version: String?, val props: Props?)
        data class SearchRes(val data: List<Show>, @SerializedName("current_page") val currentPage: Int?, @SerializedName("last_page") val lastPage: Int?)
        data class SeasonPropsEpisodes(val id: String, val images: List<Image>, val name: String, val number: String, val plot: String? = null)
        data class SeasonPropsDetails(val episodes: List<SeasonPropsEpisodes>)
        data class SeasonProps(val loadedSeason: SeasonPropsDetails)
        data class SeasonRes(val version: String?, val props: SeasonProps?)
        data class ArchivePage(val data: List<Show>?, @SerializedName("current_page") val currentPage: Int?, @SerializedName("last_page") val lastPage: Int?)
        data class ArchiveProps(val archive: ArchivePage?, val titles: ArchivePage?, val movies: ArchivePage?, val tv: ArchivePage?, @SerializedName("tv_shows") val tvShows: ArchivePage?)
        data class ArchiveRes(val version: String, val props: ArchiveProps?)
        data class ApiArchiveRes(val titles: List<Show>)
    }
}
