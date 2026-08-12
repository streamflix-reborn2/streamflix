package com.streamflixreborn.streamflix.providers

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.AnimeOnlineNinjaCronetClient
import com.streamflixreborn.streamflix.utils.ArtworkRequestHeaders
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.WebViewResolver
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

object AnimeOnlineNinjaProvider : Provider {

    private const val SITE_BASE_URL = "https://ww3.animeonline.ninja"

    override val name = "Anime Online Ninja"
    override val baseUrl = SITE_BASE_URL
    override val logo: String
        get() = artworkUrl("$baseUrl/wp-content/uploads/2019/09/cropped-avatar2-1-300x300.jpg")
            ?: "$baseUrl/wp-content/uploads/2019/09/cropped-avatar2-1-300x300.jpg"
    override val language = "es"

    private const val TAG = "AnimeOnlineNinja"
    private const val MAIN_HOST = "ww3.animeonline.ninja"
    internal val cronetHost: String get() = MAIN_HOST
    private const val DOCUMENT_CACHE_TTL_MS = 2 * 60 * 1000L

    private val providerMutex = Mutex()
    private var webViewResolver: WebViewResolver? = null
    private val documentCache = ConcurrentHashMap<String, CachedDocument>()
    @Volatile
    private var clearanceCookieHeader: String? = null

    fun init(context: Context) {
        webViewResolver = WebViewResolver(context)
        AnimeOnlineNinjaCronetClient.init(context)
        syncClearanceCookieState()
    }

    fun reload() {
        documentCache.clear()
    }

    private fun getResolver(): WebViewResolver {
        return webViewResolver ?: WebViewResolver(StreamFlixApp.instance).also {
            webViewResolver = it
        }
    }

    private class ChallengeRequiredException(
        message: String,
        val rejectedClearance: String?,
    ) : IllegalStateException(message)

    private suspend fun getDocument(url: String): Document {
        // Do this even when the document is cached. CookieManager is the source of
        // truth for expiry; an old in-memory cf_clearance must never reach Cronet.
        syncClearanceCookieState()
        cachedDocument(url)?.let { cached ->
            return cached.document.clone().apply { setBaseUri(cached.finalUrl) }
        }

        val document = try {
            fetchDocumentDirect(url)
        } catch (challenge: ChallengeRequiredException) {
            solveCloudflareChallenge(url, baseUrl, challenge.rejectedClearance)
            fetchDocumentDirect(url)
        }

        return document?.also { cacheDocument(url, it) }?.clone()
            ?: throw IllegalStateException("AnimeOnline Ninja Cronet returned no usable page for $url")
    }

    private fun pageHeaders(referer: String): Map<String, String> {
        return buildMap {
            put("User-Agent", NetworkClient.USER_AGENT)
            put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            put("Accept-Language", "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7")
            put("Referer", referer)
            currentClearanceCookie()?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        }
    }

    private fun hasUsableSiteContent(html: String, currentUrl: String): Boolean {
        if (html.length < 1000) return false
        if (currentUrl.contains("/wp-json/", ignoreCase = true)) {
            return html.trimStart().startsWith("{") || html.trimStart().startsWith("[")
        }

        return html.contains("wp-content", ignoreCase = true) ||
                html.contains("dooplay", ignoreCase = true) ||
                html.contains("TPost", ignoreCase = true) ||
                html.contains("result-item", ignoreCase = true) ||
                html.contains("module", ignoreCase = true) ||
                html.contains("episodios", ignoreCase = true) ||
                html.contains("post-", ignoreCase = true)
    }
    private suspend fun solveCloudflareChallenge(
        url: String,
        referer: String,
        rejectedClearance: String?,
    ) {
        providerMutex.withLock {
            val currentClearance = clearanceToken(currentClearanceCookie())
            if (!currentClearance.isNullOrBlank() && currentClearance != rejectedClearance) {
                Log.d(TAG, "Another request already renewed Cloudflare clearance; skipping WebView")
                return@withLock
            }

            clearStaleClearanceCookie()
            Log.d(TAG, "Solving confirmed Cloudflare challenge in WebView -> url=$url")
            getResolver().getResult(
                url = url,
                headers = pageHeaders(referer).minus("Cookie"),
                completion = { currentUrl, html, cookies ->
                    val newClearance = clearanceToken(cookies)
                    val hasClearance = !newClearance.isNullOrBlank()
                    val hasUsableContent = hasUsableSiteContent(html, currentUrl)
                    if (hasClearance) promoteClearanceCookieHeader(cookies)
                    Log.d(
                        TAG,
                        "WebView challenge poll -> url=$currentUrl clearance=$hasClearance " +
                                "changed=${newClearance != currentClearance} content=$hasUsableContent"
                    )
                    hasClearance || hasUsableContent
                },
                shouldAllowNavigation = { targetUrl, _ ->
                    runCatching {
                        val target = URL(targetUrl)
                        target.host.equals(MAIN_HOST, ignoreCase = true) ||
                                target.path.contains("/cdn-cgi/", ignoreCase = true)
                    }.getOrDefault(false)
                },
            )
        }

        if (clearanceToken(currentClearanceCookie()).isNullOrBlank()) {
            throw ChallengeRequiredException(
                message = "Cloudflare clearance was not obtained for $url",
                rejectedClearance = rejectedClearance,
            )
        }
    }

    private fun clearanceToken(cookieHeader: String?): String? {
        return cookieHeader
            ?.split(';')
            ?.map(String::trim)
            ?.firstOrNull { it.startsWith("cf_clearance=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.takeIf(String::isNotBlank)
    }

    private fun clearStaleClearanceCookie() {
        val cookieManager = CookieManager.getInstance()
        listOf(SITE_BASE_URL, "$SITE_BASE_URL/").forEach { target ->
            cookieManager.setCookie(target, "cf_clearance=; Max-Age=0; Path=/; Secure")
        }
        cookieManager.flush()

        clearanceCookieHeader = clearanceCookieHeader
            ?.split(';')
            ?.map(String::trim)
            ?.filterNot { it.startsWith("cf_clearance=", ignoreCase = true) }
            ?.joinToString("; ")
            ?.takeIf(String::isNotBlank)
    }

    private fun artworkUrl(url: String?, referer: String = baseUrl): String? {
        val image = url?.trim().orEmpty()
        if (image.isBlank()) return null

        val normalized = when {
            image.startsWith("//") -> "https:$image"
            image.startsWith("http", ignoreCase = true) -> image
            image.startsWith("/") -> "$baseUrl$image"
            else -> "$baseUrl/$image"
        }

        val isProviderArtwork = runCatching {
            URL(normalized).host.equals(MAIN_HOST, ignoreCase = true)
        }.getOrDefault(false)
        if (!isProviderArtwork) return normalized

        return ArtworkRequestHeaders.withHeaders(
            url = normalized,
            referer = referer,
            origin = SITE_BASE_URL,
            userAgent = NetworkClient.USER_AGENT,
            accept = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            cookie = currentClearanceCookie()
        )
    }

    private suspend fun fetchJson(url: String, referer: String): JSONObject {
        val body = getJsonBody(url, referer)
        return JSONObject(body)
    }

    private suspend fun getJsonBody(url: String, referer: String): String {
        syncClearanceCookieState()
        val body = try {
            fetchJsonDirect(url, referer)
        } catch (challenge: ChallengeRequiredException) {
            solveCloudflareChallenge(url, referer, challenge.rejectedClearance)
            fetchJsonDirect(url, referer)
        }

        return body ?: throw IllegalStateException("AnimeOnline Ninja Cronet returned invalid JSON for $url")
    }

    private suspend fun fetchJsonDirect(url: String, referer: String): String? {
        val headers = pageHeaders(referer) + ("Accept" to "application/json,text/plain,*/*")
        val response = AnimeOnlineNinjaCronetClient.get(
            context = StreamFlixApp.instance,
            url = url,
            headers = headers,
            useCache = false,
        )
        val body = response.bodyAsString()
        val trimmed = body.trim()

        if (response.isSuccessful &&
            (trimmed.startsWith("{") || trimmed.startsWith("["))
        ) {
            return trimmed
        }

        if (requiresClearance(body) || response.finalUrl.contains("/cdn-cgi/", ignoreCase = true)) {
            Log.w(TAG, "Cronet JSON received a challenge -> url=$url")
            throw ChallengeRequiredException(
                message = "AnimeOnline Ninja Cloudflare challenge detected for $url",
                rejectedClearance = clearanceToken(headers["Cookie"]),
            )
        }
        if (!response.isSuccessful || body.isBlank()) {
            Log.w(TAG, "Cronet JSON rejected -> code=${response.statusCode} url=$url")
            return null
        }
        return null
    }

    override suspend fun getHome(): List<Category> {
        val document = getDocument("$baseUrl/inicio/")
        val categories = parseHomeCategories(document).takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("AnimeOnline Ninja home page contained no recognizable categories")
        return resolveHomeEpisodeCards(categories)
    }

    private suspend fun resolveHomeEpisodeCards(categories: List<Category>): List<Category> = coroutineScope {
        val episodeCards = categories
            .flatMap { it.list }
            .filterIsInstance<TvShow>()
            .filter { it.id.contains("/episodio/", ignoreCase = true) }

        if (episodeCards.isEmpty()) return@coroutineScope categories

        val requestLimit = Semaphore(4)
        val resolvedByTitle = episodeCards
            .distinctBy { titleKey(it.title) }
            .map { episodeCard ->
                async {
                    val tvShowResult = runCatching {
                        requestLimit.withPermit { getTvShow(episodeCard.id) }
                    }
                    val movieResult = if (tvShowResult.isFailure) {
                        runCatching {
                            requestLimit.withPermit { resolveHomeEpisodeMovie(episodeCard) }
                        }
                    } else {
                        Result.success(null)
                    }
                    val resolved: Show? = tvShowResult.getOrNull() ?: movieResult.getOrNull()

                    if (resolved == null) {
                        Log.w(
                            TAG,
                            "Unable to resolve home episode ${episodeCard.id} to a movie or TV show",
                            tvShowResult.exceptionOrNull() ?: movieResult.exceptionOrNull(),
                        )
                    }
                    titleKey(episodeCard.title) to resolved
                }
            }
            .awaitAll()
            .toMap()

        categories.map { category ->
            category.copy(
                list = category.list
                    .map { item ->
                        if (item is TvShow && item.id.contains("/episodio/", ignoreCase = true)) {
                            resolvedByTitle[titleKey(item.title)] ?: item
                        } else {
                            item
                        }
                    }
                    .distinctBy(::itemKey),
            )
        }
    }

    private suspend fun resolveHomeEpisodeMovie(episodeCard: TvShow): Movie? {
        val query = URLEncoder.encode(episodeCard.title, "UTF-8")
        val searchDocument = getDocument("$baseUrl/?s=$query")
        val movieUrl = findMatchingShowUrl(searchDocument, episodeCard.title, "/pelicula/")
            ?: return null

        return getMovie(normalizeId(movieUrl, "/pelicula/"))
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return listOf(
                Genre("anime-castellano", "Audio castellano"),
                Genre("audio-latino", "Audio latino"),
                Genre("en-emision-1", "En emisión"),
                Genre("blu-ray-dvd-2", "BluRay-DVD"),
                Genre("live-action", "Live action"),
                Genre("tendencias", "Popular en la web"),
                Genre("ratings", "Mejores valorados"),
                Genre("award-winning-anime", "Ganadores de premios"),
                Genre("accion", "Accion"),
                Genre("aventura", "Aventura"),
                Genre("comedia", "Comedia"),
                Genre("shonen", "Shonen"),
                Genre("terror", "Terror"),
                Genre("ver-anime", "Ver Anime"),
                Genre("pelicula", "Peliculas"),

            )
        }

        if (page > 1) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = getDocument("$baseUrl/?s=$encoded")
        return parseSearchItems(document)
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return getListing(listingUrl("pelicula", page)).filterIsInstance<Movie>()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return getListing(listingUrl("online", page)).filterIsInstance<TvShow>()
    }

    override suspend fun getMovie(id: String): Movie {
        val url = toAbsoluteUrl(id, "/pelicula/")
        val document = getDocument(url)

        val title = document.extractDetailTitle().ifBlank { id }
        val overview = extractOverview(document, title)
        val poster = extractDetailArtwork(document, url)
        val released = document.selectFirst("meta[property='article:published_time']")?.attr("content")?.take(10)

        return Movie(
            id = normalizeId(url, "/pelicula/"),
            title = cleanTitle(title),
            overview = overview,
            released = released,
            poster = poster,
            banner = poster
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val requestedUrl = toAbsoluteUrl(id, "/online/")
        val isParentLookup = requestedUrl.contains("/temporada/", ignoreCase = true) ||
                requestedUrl.contains("/episodio/", ignoreCase = true)
        val (url, document) = if (isParentLookup) {
            val sourceDocument = getDocument(requestedUrl)
            val parentUrl = resolveParentTvShowUrl(sourceDocument)
                ?: throw IllegalStateException(
                    "AnimeOnline Ninja parent TV show not found for $requestedUrl"
                )
            parentUrl to getDocument(parentUrl)
        } else {
            requestedUrl to getDocument(requestedUrl)
        }
        val title = document.extractDetailTitle().ifBlank { id }
        val overview = extractOverview(document, title)
        val poster = extractDetailArtwork(document, url)
        val banner = poster
        val released = document.selectFirst("meta[property='article:published_time']")?.attr("content")?.take(10)
        val seasons = parseSeasons(document, url, poster)
        val recommendations = document.select("#single_relacionados article, #single_relacionados .item")
            .mapNotNull { parseListingItem(it) }
            .filterIsInstance<Show>()
            .distinctBy { item ->
                when (item) {
                    is Movie -> "movie:${item.id}"
                    is TvShow -> "tv:${item.id}"
                }
            }

        return TvShow(
            id = normalizeId(url, "/online/"),
            title = cleanTitle(title),
            overview = overview,
            released = released,
            poster = poster,
            banner = banner,
            seasons = seasons,
            recommendations = recommendations
        )
    }

    private suspend fun resolveParentTvShowUrl(sourceDocument: Document): String? {
        sourceDocument.select(".pag_episodes .item a[href]")
            .firstOrNull { link ->
                link.text().contains("lista de episodios", ignoreCase = true) &&
                        link.attr("href").contains("/online/", ignoreCase = true)
            }
            ?.let { link -> link.absUrl("href").ifBlank { link.attr("href") } }
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val sourceTitle = sourceDocument.extractDetailTitle()
        val parentTitle = cleanTitle(sourceTitle)
            .replace(
                Regex(
                    """\s*[-–—:]?\s*(?:temporada|episodio|cap[ií]tulo|cap)\s*\d+.*$""",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .replace(Regex("""\s*[-–—:]?\s*\d+\s*[x×]\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { cleanTitle(sourceTitle) }

        findMatchingTvShowUrl(sourceDocument, parentTitle)?.let { return it }

        val query = URLEncoder.encode(parentTitle, "UTF-8")
        val searchDocument = getDocument("$baseUrl/?s=$query")
        return findMatchingTvShowUrl(searchDocument, parentTitle)
    }

    private fun findMatchingTvShowUrl(document: Document, parentTitle: String): String? {
        return findMatchingShowUrl(document, parentTitle, "/online/")
    }

    private fun findMatchingShowUrl(
        document: Document,
        parentTitle: String,
        path: String,
    ): String? {
        val targetKey = titleKey(parentTitle)
        if (targetKey.isBlank()) return null

        return document.select("a[href*='$path']")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank()) return@mapNotNull null

                val labels = listOf(
                    link.text(),
                    link.attr("title"),
                    link.selectFirst("img[alt]")?.attr("alt").orEmpty(),
                    link.parent()?.text().orEmpty(),
                    runCatching {
                        URLDecoder.decode(normalizeId(href, path), "UTF-8")
                            .replace('-', ' ')
                    }.getOrDefault(""),
                )
                val score = labels.maxOf { label ->
                    val candidateKey = titleKey(label)
                    when {
                        candidateKey == targetKey -> 100
                        candidateKey.startsWith(targetKey) -> 80
                        targetKey.startsWith(candidateKey) && candidateKey.length >= 6 -> 70
                        else -> 0
                    }
                }
                href to score
            }
            .maxByOrNull { (_, score) -> score }
            ?.takeIf { (_, score) -> score > 0 }
            ?.first
    }

    private fun titleKey(value: String): String {
        return Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}|\p{Cf}"""), "")
            .replace(Regex("""[^a-z0-9]+"""), "")
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val pageUrl = seasonId.substringBefore("#season-").ifBlank { seasonId }
        val seasonNumber = seasonId.substringAfter("#season-", "1").toIntOrNull() ?: 1
        val document = getDocument(pageUrl)

        val seasonBlock = document.select("#seasons .se-c, .se-c")
            .getOrNull(seasonNumber - 1)
            ?: document.select("#seasons .se-c, .se-c").firstOrNull()

        val episodeElements = seasonBlock?.select("ul.episodios li, ul.episodios > li") ?: document.select("ul.episodios li, ul.episodios > li")

        return episodeElements.mapIndexedNotNull { index, element ->
            val link = element.selectFirst("a[href]") ?: return@mapIndexedNotNull null
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            if (href.isBlank()) return@mapIndexedNotNull null

            val numberText = element.selectFirst(".numerando, .num, .numero")?.text()?.trim().orEmpty()
            val number = parseEpisodeNumber(numberText, index + 1)

            val title = link.text().trim().ifBlank {
                element.selectFirst(".episodiotitle, .title, h3")?.text()?.trim().orEmpty()
            }
            val poster = element.selectFirst("img")?.let { image ->
                image.absUrl("data-src").ifBlank { image.absUrl("src") }.ifBlank { image.attr("src") }
            }?.takeIf { it.isNotBlank() }?.let { artworkUrl(it, href) }

            Episode(
                id = href,
                number = number,
                title = title.ifBlank { "Episodio $number" },
                poster = poster
            )
        }.distinctBy { it.id }
            .sortedBy { it.number }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val slug = id.trim().trim('/')
        val path = if (slug == "tendencias" || slug == "ratings") slug else "genero/$slug"
        val url = if (page <= 1) "$baseUrl/$path/" else "$baseUrl/$path/page/$page/"

        val document = getDocument(url)
        val title = document.selectFirst(".module .content.right header h1, .module .content.right h1")
            ?.text()
            ?.trim()
            ?: slug.replace('-', ' ').replaceFirstChar { it.uppercase() }

        return Genre(
            id = id,
            name = title,
            shows = parseArchiveItems(document).mapNotNull {
                when (it) {
                    is Movie -> it
                    is TvShow -> it
                    else -> null
                }
            }
        )
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = id, filmography = emptyList())
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val pageUrl = when (videoType) {
            is Video.Type.Movie -> toAbsoluteUrl(id, "/pelicula/")
            is Video.Type.Episode -> toAbsoluteUrl(id, "/episodio/")
        }

        val document = runCatching { getDocument(pageUrl) }
            .onFailure { error -> logFailure("player page request", pageUrl, error) }
            .getOrElse { error -> throw IllegalStateException("Unable to load player page $pageUrl", error) }
        val fallbackType = when (videoType) {
            is Video.Type.Movie -> "movie"
            is Video.Type.Episode -> "tv"
        }
        val discoveredSources = parsePlayerSources(document, fallbackType)
        val postId = discoveredSources.firstOrNull()?.postId ?: resolvePostId(pageUrl, document)
        ?: throw IllegalStateException("AnimeOnline Ninja post id not found for $pageUrl")
        val sources = discoveredSources.ifEmpty {
            Log.w(TAG, "No player source metadata found; using compatibility probe -> post=$postId")
            (1..5).map { source ->
                PlayerSource(
                    postId = postId,
                    type = fallbackType,
                    number = source,
                    label = "Server $source",
                )
            }
        }

        val collected = linkedMapOf<String, Video.Server>()
        for (source in sources) {
            val apiUrl = "$baseUrl/wp-json/dooplayer/v1/post/${source.postId}?type=${source.type}&source=${source.number}"
            val json = runCatching { fetchJson(apiUrl, referer = pageUrl) }
                .onFailure { error -> logFailure("player source ${source.number}", apiUrl, error) }
                .getOrNull() ?: continue
            val embedUrl = normalizeExternalUrl(json.optString("embed_url"), baseUrl)
            if (embedUrl == null) {
                Log.w(TAG, "Player source returned no embed URL -> source=${source.number} post=${source.postId}")
                continue
            }

            val servers = runCatching { resolveServers(embedUrl, source.number, pageUrl) }
                .onFailure { error -> logFailure("embed resolution", embedUrl, error) }
                .getOrDefault(emptyList())
            if (servers.isEmpty()) {
                collected.putIfAbsent(
                    embedUrl,
                    Video.Server(
                        id = embedUrl,
                        name = source.label.ifBlank { hostLabel(embedUrl, source.number) },
                        src = embedUrl
                    )
                )
            } else {
                servers.forEach { server ->
                    collected.putIfAbsent(server.id, server)
                }
            }
        }

        if (collected.isEmpty()) {
            directPlayerEmbeds(document).forEachIndexed { index, embedUrl ->
                collected.putIfAbsent(
                    embedUrl,
                    Video.Server(
                        id = embedUrl,
                        name = hostLabel(embedUrl, index + 1),
                        src = embedUrl,
                    ),
                )
            }
        }

        if (collected.isEmpty()) {
            Log.w(TAG, "No playable servers found -> page=$pageUrl sources=${sources.size}")
        }
        return prioritizeServers(collected.values.toList())
    }

    private fun parsePlayerSources(document: Document, fallbackType: String): List<PlayerSource> {
        return document.select(
            "#playeroptionsul [data-nume], li.dooplay_player_option[data-nume], [data-post][data-nume][data-type]",
        ).mapNotNull { element ->
            val number = element.attr("data-nume").toIntOrNull() ?: return@mapNotNull null
            val postId = element.attr("data-post").trim()
                .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?: return@mapNotNull null
            val type = element.attr("data-type").trim().ifBlank { fallbackType }
            val label = element.selectFirst(".title, span.title, .server, span")
                ?.text()
                ?.trim()
                .orEmpty()
                .ifBlank { "Server $number" }

            PlayerSource(postId, type, number, label)
        }.distinctBy { source -> "${source.postId}:${source.type}:${source.number}" }
    }

    private fun resolvePostId(pageUrl: String, document: Document?): String? {
        Regex("""[?&]p=(\d+)""").find(pageUrl)?.groupValues?.getOrNull(1)?.let { return it }

        if (document != null) {
            val shortlink = document.selectFirst("link[rel=shortlink]")?.attr("href").orEmpty()
            Regex("""[?&]p=(\d+)""").find(shortlink)?.groupValues?.getOrNull(1)?.let { return it }

            val html = document.outerHtml()
            listOf(
                Regex("""postid-(\d+)"""),
                Regex("""post-(\d+)"""),
                Regex("""data-post=["'](\d+)["']"""),
                Regex("""data-id=["'](\d+)["']""")
            ).firstNotNullOfOrNull { pattern ->
                pattern.find(html)?.groupValues?.getOrNull(1)
            }?.let { return it }
        }

        return null
    }

    private fun directPlayerEmbeds(document: Document): List<String> {
        return document.select(
            "#dooplay_player_response iframe[src], #playeroptions iframe[src], .player_sist iframe[src], .playex iframe[src]",
        ).mapNotNull { element ->
            normalizeExternalUrl(element.absUrl("src").ifBlank { element.attr("src") }, document.baseUri())
        }.distinct()
    }

    private fun normalizeExternalUrl(value: String?, referer: String): String? {
        val url = value?.trim().orEmpty()
        if (url.isBlank() || url.equals("about:blank", ignoreCase = true)) return null
        return runCatching {
            when {
                url.startsWith("//") -> "https:$url"
                url.startsWith("http://", true) || url.startsWith("https://", true) -> url
                else -> URL(URL(referer), url).toString()
            }
        }.getOrNull()
    }

    private data class PlayerSource(
        val postId: String,
        val type: String,
        val number: Int,
        val label: String,
    )

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.src.ifBlank { server.id }, server)
    }

    private suspend fun getListing(url: String): List<AppAdapter.Item> {
        return getDocument(url).let(::parseArchiveItems)
    }

    private fun listingUrl(path: String, page: Int): String {
        return if (page <= 1) {
            "$baseUrl/$path/"
        } else {
            "$baseUrl/$path/page/$page/"
        }
    }

    private fun parseHomeCategories(document: Document): List<Category> {
        val categories = linkedMapOf<String, Category>()

        parseHomeModules(document).forEach { category ->
            categories.putIfAbsent(category.name, category)
        }

        listOfNotNull(
            parseHomeSection(document, "#featured-titles", Category.FEATURED),
            parseHomeSection(document, "#dt-episodes", "ÚLTIMOS EPISODIOS"),
            parseHomeSection(document, "#slider-movies-tvshows", "EN EMISIÓN 🔥 RECOMENDADOS"),
            parseHomeSection(document, "#slider-tvshows", "ÚLTIMOS ANIMES AGREGADOS 💥"),
            parseHomeSection(document, "#slider-movies", "ÚLTIMAS PELICULAS AGREGADAS 🎬"),
            parseHomeSection(document, "#dt-seasons", "TEMPORADAS 📺")
        ).forEach { category -> categories.putIfAbsent(category.name, category) }

        return categories.values.toList()
    }

    private fun parseHomeModules(document: Document): List<Category> {
        return document.select(".module header").mapNotNull { header ->
            val title = header.selectFirst("h1, h2, h3")?.text()?.trim()?.let(::cleanTitle)
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val items = generateSequence(header.nextElementSibling()) { it.nextElementSibling() }
                .takeWhile { sibling -> sibling.tagName() != "header" }
                .flatMap { sibling -> parseListingItems(sibling).asSequence() }
                .distinctBy(::itemKey)
                .toList()

            items.takeIf { it.isNotEmpty() }?.let { Category(title, it) }
        }
    }

    private fun parseHomeSection(document: Document, selector: String, title: String): Category? {
        val root = document.selectFirst(selector) ?: return null
        val items = parseListingItems(root).distinctBy(::itemKey)

        return items.takeIf { it.isNotEmpty() }?.let { Category(title, it) }
    }

    private fun parseListingItems(root: Element): List<AppAdapter.Item> {
        val selectors = listOf(
            ".search-page .result-item article",
            ".result-item article",
            "article.TPost",
            "li.TPostMv article",
            ".TPost",
            ".items .item",
            "article[class*='post-']",
            "article"
        )

        return selectors
            .flatMap { selector -> root.select(selector) }
            .mapNotNull { parseListingItem(it) }
            .distinctBy(::itemKey)
    }

    private fun parseSearchItems(document: Document): List<AppAdapter.Item> {
        return document
            .select(".search-page > .result-item > article, .search-page .result-item article")
            .mapNotNull(::parseListingItem)
            .distinctBy(::itemKey)
    }

    private fun parseArchiveItems(document: Document): List<AppAdapter.Item> {
        val grid = document.selectFirst("#archive-content")
            ?: document.selectFirst(".module > .content.right > .items:not(.featured)")
            ?: document.selectFirst(".module .content.right .items:not(.featured)")
            ?: return emptyList()

        return grid
            .children()
            .filter { child -> child.hasClass("item") || child.tagName() == "article" }
            .mapNotNull(::parseListingItem)
            .distinctBy(::itemKey)
    }

    private fun parseListingItem(element: Element): AppAdapter.Item? {
        val link = element.selectFirst("a[href]") ?: return null
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        if (href.isBlank()) return null

        val title = listOfNotNull(
            element.selectFirst(".details .title a, .data h3.title, .data h3 a, .data h3, h2 a, h2, h3 a, h3, .Title, .name, .title")?.text()?.trim(),
            link.text().trim().takeIf { it.isNotBlank() },
            link.attr("title").trim().takeIf { it.isNotBlank() },
            element.selectFirst("img[alt]")?.attr("alt")?.trim()
        ).firstOrNull()
            ?.let(::cleanTitle)
            .orEmpty()

        val poster = element.selectFirst("img")?.let { image ->
            image.absUrl("data-src")
                .ifBlank { image.absUrl("src") }
                .ifBlank { image.attr("src") }
        }?.takeIf { it.isNotBlank() }?.let { artworkUrl(it, element.ownerDocument()?.location().orEmpty().ifBlank { baseUrl }) }

        return when {
            href.contains("/pelicula/", ignoreCase = true) -> Movie(
                id = normalizeId(href, "/pelicula/"),
                title = title.ifBlank { href.substringAfterLast('/').replace('-', ' ') },
                poster = poster
            )

            href.contains("/online/", ignoreCase = true) -> TvShow(
                id = normalizeId(href, "/online/"),
                title = title.ifBlank { href.substringAfterLast('/').replace('-', ' ') },
                poster = poster
            )

            href.contains("/episodio/", ignoreCase = true) -> parseParentTvShow(element, href, poster)

            href.contains("/temporada/", ignoreCase = true) -> parseParentTvShow(element, href, poster)

            else -> null
        }
    }

    private fun extractDetailArtwork(document: Document, referer: String): String? {
        val pagePoster = document
            .selectFirst(".sheader .poster img, #single .poster img, article.post .poster img")
            ?.let { image ->
                image.absUrl("data-src")
                    .ifBlank { image.attr("data-src") }
                    .ifBlank { image.absUrl("src") }
                    .ifBlank { image.attr("src") }
            }
            ?.takeIf { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }

        val openGraphPoster = document
            .selectFirst("meta[property='og:image']")
            ?.attr("content")
            ?.trim()
            ?.takeIf(String::isNotBlank)

        return artworkUrl(pagePoster ?: openGraphPoster, referer)
    }

    private fun parseParentTvShow(element: Element, href: String, poster: String?): TvShow? {
        val parentTitle = listOfNotNull(
            element.selectFirst("img[alt]")?.attr("alt")?.substringBefore(" Temporada")?.substringBefore(" Cap")?.trim(),
            element.selectFirst(".data h3")?.text()?.trim(),
            element.selectFirst(".season_m .c")?.text()?.trim()
        ).firstOrNull { it.isNotBlank() }?.let(::cleanTitle) ?: return null

        return TvShow(
            // Season and episode permalinks are authoritative lookup sources.
            // Their parent /online/ URL is resolved when the card is opened.
            id = href,
            title = parentTitle,
            poster = poster
        )
    }

    private fun cleanTitle(value: String): String {
        return value
            .substringBefore("|")
            .removePrefix("▷")
            .replace(Regex("""\s*【.*?】\s*"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun extractOverview(document: Document, title: String?): String? {
        val synopsis = extractSynopsisText(document, title)
        return synopsis?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name='description']")
                ?.attr("content")
                ?.cleanOverviewText(title)
    }

    private fun extractSynopsisText(document: Document, title: String?): String? {
        val heading = document.select("*").firstOrNull { element ->
            val text = element.ownText().trim()
            text.equals("Sinopsis", ignoreCase = true) ||
                    text.startsWith("Sinopsis", ignoreCase = true)
        } ?: return null

        val synopsisContainer = when {
            heading.nextElementSibling()?.classNames()?.contains("wp-content") == true -> heading.nextElementSibling()
            heading.parent()?.classNames()?.contains("wp-content") == true -> heading.parent()
            else -> heading.nextElementSibling()?.selectFirst(".wp-content")
                ?: heading.parent()?.selectFirst(".wp-content")
        }

        synopsisContainer?.select("p, li")
            ?.mapNotNull { block -> block.text().trim().cleanOverviewText(title) }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it.joinToString("\n\n") }

        val siblingBlocks = mutableListOf<String>()
        var sibling = heading.nextElementSibling()
        var attempts = 0
        while (sibling != null && attempts < 10) {
            if (sibling.tagName().equals("p", ignoreCase = true) ||
                sibling.tagName().equals("li", ignoreCase = true)) {
                sibling.text().trim().cleanOverviewText(title)?.let(siblingBlocks::add)
            }
            sibling = sibling.nextElementSibling()
            attempts++
        }

        return siblingBlocks.distinct().takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    private fun String.cleanOverviewText(title: String?): String? {
        val normalized = replace(Regex("""\s+"""), " ").trim()
        // Some pages only provide a concise synopsis (for example,
        // "Secuela de Chainsaw Man"), so length alone is not a reliable
        // indication that a synopsis block is junk.
        if (normalized.length < 10) return null

        val lower = normalized.lowercase()
        if (Regex("""^ver\s+.+\s+(online|mega|sub español|audio español)""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) {
            return null
        }
        if (lower.contains("sakura mail") && lower.contains("online") && lower.contains("descargar") && lower.contains("mega")) {
            return null
        }
        if (lower == title?.lowercase()) return null

        return normalized
    }

    private fun Document.extractDetailTitle(): String {
        return selectFirst(".sheader .data h1, .sheader h1, #single h1, main h1, h1[itemprop='name'], meta[property='og:title'], meta[name='twitter:title']")
            ?.let { element ->
                when {
                    element.tagName().equals("meta", ignoreCase = true) -> element.attr("content").trim()
                    else -> element.text().trim()
                }
            }
            .orEmpty()
    }

    private fun parseSeasons(document: Document, pageUrl: String, poster: String?): List<Season> {
        val seasonBlocks = document.select("#seasons .se-c, .se-c")
        if (seasonBlocks.isEmpty()) {
            val episodes = document.select("ul.episodios li, ul.episodios > li")
                .mapIndexedNotNull { index, element ->
                    val link = element.selectFirst("a[href]") ?: return@mapIndexedNotNull null
                    val href = link.absUrl("href").ifBlank { link.attr("href") }
                    if (href.isBlank()) return@mapIndexedNotNull null

                    Episode(
                        id = href,
                        number = index + 1,
                        title = link.text().trim().ifBlank { "Episodio ${index + 1}" },
                        poster = element.selectFirst("img")?.let { image ->
                            image.absUrl("data-src").ifBlank { image.absUrl("src") }.ifBlank { image.attr("src") }
                        }?.takeIf { it.isNotBlank() }?.let { artworkUrl(it, href) }
                    )
                }

            return if (episodes.isNotEmpty()) {
                listOf(
                    Season(
                        id = "$pageUrl#season-1",
                        number = 1,
                        title = "Temporada 1",
                        poster = poster,
                        episodes = episodes
                    )
                )
            } else {
                emptyList()
            }
        }

        return seasonBlocks.mapIndexed { index, block ->
            val seasonNumber = block.attr("data-season").toIntOrNull()
                ?: Regex("""\d+""").find(block.selectFirst(".se-t, .title, .season-title")?.text().orEmpty())?.value?.toIntOrNull()
                ?: (index + 1)

            val title = block.selectFirst(".se-t, .title, .season-title")?.text()?.trim()
                ?: "Temporada $seasonNumber"

            val episodes = block.select("ul.episodios li, ul.episodios > li")
                .mapIndexedNotNull { epIndex, element ->
                    val link = element.selectFirst("a[href]") ?: return@mapIndexedNotNull null
                    val href = link.absUrl("href").ifBlank { link.attr("href") }
                    if (href.isBlank()) return@mapIndexedNotNull null

                    val numberText = element.selectFirst(".numerando, .num, .numero")?.text()?.trim().orEmpty()
                    val number = parseEpisodeNumber(numberText, epIndex + 1)

                    Episode(
                        id = href,
                        number = number,
                        title = link.text().trim().ifBlank { "Episodio $number" },
                        poster = element.selectFirst("img")?.let { image ->
                            image.absUrl("data-src").ifBlank { image.absUrl("src") }.ifBlank { image.attr("src") }
                        }?.takeIf { it.isNotBlank() }?.let { artworkUrl(it, href) }
                    )
                }
                .distinctBy { it.id }
                .sortedBy { it.number }

            Season(
                id = "$pageUrl#season-$seasonNumber",
                number = seasonNumber,
                title = title,
                poster = poster,
                episodes = episodes
            )
        }.sortedBy { it.number }
    }

    private fun parseEpisodeNumber(numberText: String, fallback: Int): Int {
        val numbers = Regex("""\d+""")
            .findAll(numberText)
            .mapNotNull { match -> match.value.toIntOrNull() }
            .toList()

        // DooPlay commonly renders this as "season - episode" (for example
        // "1 - 8"). The final component is therefore the episode number.
        return numbers.lastOrNull()?.takeIf { it > 0 } ?: fallback
    }

    private fun toAbsoluteUrl(id: String, preferredPrefix: String? = null): String {
        return when {
            id.startsWith("http", ignoreCase = true) -> id
            preferredPrefix != null -> {
                val prefix = preferredPrefix.trim('/')
                val cleanId = id.trim('/')
                if (cleanId.startsWith(prefix)) {
                    "$baseUrl/$cleanId"
                } else {
                    "$baseUrl/$prefix/$cleanId"
                }
            }
            id.startsWith("/") -> "$baseUrl$id"
            else -> "$baseUrl/$id"
        }
    }

    private fun normalizeId(url: String, prefix: String): String {
        return url.substringAfter(prefix, url).trim('/').removeSuffix("/")
    }

    private fun hostLabel(url: String, source: Int): String {
        return runCatching {
            val host = URL(url).host.removePrefix("www.")
            host.substringBefore(".").replaceFirstChar { it.uppercase() }
        }.getOrNull() ?: "Server $source"
    }

    private suspend fun resolveServers(embedUrl: String, source: Int, pageUrl: String): List<Video.Server> {
        val response = AnimeOnlineNinjaCronetClient.get(
            context = StreamFlixApp.instance,
            url = embedUrl,
            headers = mapOf(
                "Referer" to pageUrl,
                "User-Agent" to NetworkClient.USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7",
            ),
            useCache = false,
        )
        if (!response.isSuccessful) {
            throw IllegalStateException("Cronet embed HTTP ${response.statusCode}: $embedUrl")
        }
        val document = Jsoup.parse(response.bodyAsString(), response.finalUrl)
        val servers = linkedMapOf<String, Video.Server>()

        document.select("li[onclick*='go_to_player']").forEachIndexed { index, element ->
            val onclick = element.attr("onclick")
            val serverUrl = Regex("""go_to_player\s*\(\s*['\"]([^'\"]+)['\"]\s*\)""")
                .find(onclick)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { normalizeExternalUrl(it, embedUrl) }
                ?: return@forEachIndexed

            val label = element.selectFirst("span")?.text()?.trim().orEmpty()
                .ifBlank { hostLabel(serverUrl, index + 1) }
            val group = element.parents().firstOrNull { parent ->
                parent.classNames().any { it.startsWith("OD_") }
            }?.classNames()?.firstOrNull { it.startsWith("OD_") }?.removePrefix("OD_")
            val name = if (group.isNullOrBlank()) {
                label
            } else {
                "$label ${group.uppercase()}"
            }

            servers.putIfAbsent(
                serverUrl,
                Video.Server(
                    id = serverUrl,
                    name = name,
                    src = serverUrl
                )
            )
        }

        document.select("li[data-link], li[data-url], .server[data-link], .server[data-url]")
            .forEachIndexed { index, element ->
                val serverUrl = normalizeExternalUrl(
                    element.attr("data-link").ifBlank { element.attr("data-url") },
                    embedUrl,
                ) ?: return@forEachIndexed
                val label = element.selectFirst(".title, span")?.text()?.trim()
                    .orEmpty()
                    .ifBlank { hostLabel(serverUrl, index + 1) }

                servers.putIfAbsent(
                    serverUrl,
                    Video.Server(
                        id = serverUrl,
                        name = label,
                        src = serverUrl,
                    ),
                )
            }

        if (servers.isEmpty()) {
            document.select("iframe[src]").forEachIndexed { index, element ->
                val serverUrl = normalizeExternalUrl(
                    element.absUrl("src").ifBlank { element.attr("src") },
                    embedUrl,
                ) ?: return@forEachIndexed

                servers.putIfAbsent(
                    serverUrl,
                    Video.Server(
                        id = serverUrl,
                        name = hostLabel(serverUrl, index + 1),
                        src = serverUrl
                    )
                )
            }
        }

        return servers.values.toList()
    }

    private fun prioritizeServers(servers: List<Video.Server>): List<Video.Server> {
        val preferred = UserPreferences
            .getProviderCache(this, UserPreferences.PROVIDER_PREFERRED_SERVER)
            .trim()
            .uppercase()

        if (preferred.isBlank()) return servers

        val (preferredServers, fallbackServers) = servers.partition { server ->
            serverMatchesPreference(server, preferred)
        }

        return if (preferredServers.isEmpty()) servers else preferredServers + fallbackServers
    }

    private fun serverMatchesPreference(server: Video.Server, preferred: String): Boolean {
        val tokens = server.name
            .uppercase()
            .split(Regex("""[^A-Z0-9]+"""))
            .filter { it.isNotBlank() }
            .toSet()
        return preferred in tokens
    }

    private fun requiresClearance(html: String): Boolean {
        // Do not match every /cdn-cgi/challenge-platform/ reference. Cloudflare
        // injects its lightweight JSD script and __cf_chl_tk links into ordinary,
        // fully usable pages. Only markers belonging to the interstitial itself
        // are considered a challenge here.
        return html.contains("cf-browser-verification", ignoreCase = true) ||
                html.contains("Just a moment...", ignoreCase = true) ||
                html.contains("Checking your browser", ignoreCase = true) ||
                html.contains("window._cf_chl_opt", ignoreCase = true) ||
                html.contains("challenge-form", ignoreCase = true)
    }

    private fun itemKey(item: AppAdapter.Item): String {
        return when (item) {
            is Movie -> "movie:${item.id}"
            is TvShow -> "tv:${item.id}"
            is Genre -> "genre:${item.id}"
            else -> item.toString()
        }

    }
    private fun syncClearanceCookieState(): String? {
        val cookieManager = CookieManager.getInstance()
        val candidates = listOf(
            "$SITE_BASE_URL/",
            SITE_BASE_URL,
            "$baseUrl/",
            baseUrl
        )

        val liveCookieHeaders = candidates.mapNotNull { candidate ->
            cookieManager.getCookie(candidate)?.trim()?.takeIf(String::isNotBlank)
        }.distinct()
        val liveCookieHeader = liveCookieHeaders.firstOrNull { header ->
            !clearanceToken(header).isNullOrBlank()
        } ?: liveCookieHeaders.firstOrNull()

        val staleClearance = clearanceToken(clearanceCookieHeader)
        if (!staleClearance.isNullOrBlank() && clearanceToken(liveCookieHeader).isNullOrBlank()) {
            Log.d(TAG, "Discarding expired Cloudflare clearance before request")
            reload()
        }

        clearanceCookieHeader = liveCookieHeader
        return liveCookieHeader
    }

    private fun currentClearanceCookie(): String? = syncClearanceCookieState()

    fun hasCurrentClearanceCookie(): Boolean {
        return !clearanceToken(syncClearanceCookieState()).isNullOrBlank()
    }

    fun clearanceCookieForCronet(): String? = currentClearanceCookie()

    private suspend fun fetchDocumentDirect(url: String): Document? {
        val headers = pageHeaders(baseUrl)
        val response = AnimeOnlineNinjaCronetClient.get(
            context = StreamFlixApp.instance,
            url = url,
            headers = headers,
            useCache = false,
        )
        val body = response.bodyAsString()

        // Cloudflare may append challenge-related scripts or tokenized links to a
        // normal WordPress page. Accept a successful, recognizable provider page
        // before inspecting those incidental markers.
        if (response.isSuccessful &&
            body.isNotBlank() &&
            hasUsableSiteContent(body, response.finalUrl)
        ) {
            return Jsoup.parse(body, response.finalUrl).apply { setBaseUri(response.finalUrl) }
        }

        if (requiresClearance(body) || response.finalUrl.contains("/cdn-cgi/", ignoreCase = true)) {
            Log.w(TAG, "Cronet page received a challenge -> url=${response.finalUrl}")
            throw ChallengeRequiredException(
                message = "AnimeOnline Ninja Cloudflare challenge detected for $url",
                rejectedClearance = clearanceToken(headers["Cookie"]),
            )
        }
        if (!response.isSuccessful || body.isBlank()) {
            Log.w(TAG, "Cronet page rejected -> code=${response.statusCode} url=$url")
            return null
        }

        Log.w(TAG, "Cronet page was incomplete -> url=${response.finalUrl} size=${body.length}")
        return null
    }

    private fun cacheDocument(requestUrl: String, document: Document) {
        documentCache[requestUrl] = CachedDocument(
            document = document.clone(),
            finalUrl = document.baseUri(),
            expiresAt = System.currentTimeMillis() + DOCUMENT_CACHE_TTL_MS
        )
    }

    private fun cachedDocument(url: String): CachedDocument? {
        val cached = documentCache[url] ?: return null
        if (cached.expiresAt < System.currentTimeMillis()) {
            documentCache.remove(url)
            return null
        }
        return cached
    }

    private fun promoteClearanceCookieHeader(cookieHeader: String?) {
        val normalized = cookieHeader?.trim()?.takeIf(String::isNotBlank) ?: return

        if (normalized == clearanceCookieHeader) return

        reload()
        clearanceCookieHeader = normalized
    }

    private fun logFailure(operation: String, url: String, error: Throwable) {
        Log.w(TAG, "$operation failed -> url=$url type=${error.javaClass.simpleName} message=${error.message}")
    }

    private data class CachedDocument(
        val document: Document,
        val finalUrl: String,
        val expiresAt: Long,
    )

}
