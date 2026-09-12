package com.streamflixreborn.streamflix.providers

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
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.TmdbUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object KinoGerProvider : Provider {
    override val name = "KinoGer"
    override val baseUrl: String
        get() = UserPreferences.kinogerDomain.trimEnd('/') + "/"
    override val logo = "${baseUrl}favicon.ico"
    override val language = "de"

    private const val MOVIES = "kinofilme-online/"
    private const val SERIES = "serienstream-deutsch/"

    private interface Service {
        @GET
        suspend fun get(@Url url: String): Document

        @FormUrlEncoded
        @POST("index.php?do=search")
        suspend fun search(
            @Field("story") story: String,
            @Field("do") action: String = "search",
            @Field("subaction") subaction: String = "search",
            @Field("titleonly") titleOnly: String = "3"
        ): Document

        companion object {
            fun build(baseUrl: String): Service {
                val client = OkHttpClient.Builder()
                    .dns(DnsResolver.doh)
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(25, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()
                return Retrofit.Builder()
                    .baseUrl(baseUrl.trimEnd('/') + "/")
                    .client(client)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()
                    .create(Service::class.java)
            }
        }
    }

    @Volatile
    private var cachedService: Service? = null

    @Volatile
    private var cachedServiceBaseUrl: String? = null

    private val service: Service
        get() {
            val currentBaseUrl = baseUrl

            val existing = cachedService
            if (
                existing != null &&
                cachedServiceBaseUrl == currentBaseUrl
            ) {
                return existing
            }

            return synchronized(this) {
                val synchronizedExisting = cachedService

                if (
                    synchronizedExisting != null &&
                    cachedServiceBaseUrl == currentBaseUrl
                ) {
                    synchronizedExisting
                } else {
                    Service.build(currentBaseUrl).also {
                        cachedService = it
                        cachedServiceBaseUrl = currentBaseUrl
                    }
                }
            }
        }

    override suspend fun getHome(): List<Category> = listOf(
        Category(name = "Filme", list = getMovies(1)),
        Category(name = "Serien", list = getTvShows(1))
    )

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return getMovies(page)
        if (page > 1) return emptyList()
        return runCatching { parseCatalog(service.search(query.trim()), null) }.getOrDefault(emptyList())
    }

    override suspend fun getMovies(page: Int): List<Movie> =
        runCatching { parseCatalog(service.get(pageUrl(MOVIES, page)), false).filterIsInstance<Movie>() }
            .getOrDefault(emptyList())

    override suspend fun getTvShows(page: Int): List<TvShow> =
        runCatching {
            parseCatalog(service.get(pageUrl(SERIES, page)), true)
                .filterIsInstance<TvShow>()
                .distinctBy { normalizeSeriesTitle(it.title).lowercase() }
                .map { it.copy(title = normalizeSeriesTitle(it.title)) }
        }.getOrDefault(emptyList())

    override suspend fun getMovie(id: String): Movie {
        val doc = service.get(absolute(id))
        val raw = title(doc)
        val clean = cleanMovieTitle(raw)
        val tmdb = runCatching { TmdbUtils.getMovie(clean, language = language) }.getOrNull()
        val localPoster = poster(doc)
        return Movie(
            id = id,
            title = clean,
            overview = tmdb?.overview ?: overview(doc),
            released = year(doc, raw),
            rating = tmdb?.rating,
            poster = tmdb?.poster ?: localPoster,
            banner = tmdb?.banner ?: tmdb?.poster ?: localPoster,
            genres = tmdb?.genres ?: emptyList(),
            runtime = tmdb?.runtime,
            trailer = tmdb?.trailer,
            imdbId = tmdb?.imdbId,
            quality = quality(doc.text())
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val doc = service.get(absolute(id))
        val raw = title(doc)
        val clean = normalizeSeriesTitle(raw)
        val tmdb = runCatching { TmdbUtils.getTvShow(clean, language = language) }.getOrNull()
        val localPoster = poster(doc)

        val foundSeasons = mutableMapOf<Int, String>()
        val currentSeason = seasonNumber(raw) ?: 1
        foundSeasons[currentSeason] = id

        runCatching {
            parseEntries(service.search(clean)).forEach { e ->
                val s = seasonNumber(e.title) ?: return@forEach
                if (normalizeSeriesTitle(e.title).equals(clean, ignoreCase = true)) foundSeasons[s] = e.id
            }
        }

        val seasons = foundSeasons.toSortedMap().map { (n, sid) ->
            Season(id = "$sid#season-$n", number = n, episodes = emptyList(),
                poster = tmdb?.seasons?.firstOrNull { it.number == n }?.poster)
        }

        return TvShow(
            id = id,
            title = clean,
            overview = tmdb?.overview ?: overview(doc),
            released = year(doc, raw),
            rating = tmdb?.rating,
            poster = tmdb?.poster ?: localPoster,
            banner = tmdb?.banner ?: tmdb?.poster ?: localPoster,
            genres = tmdb?.genres ?: emptyList(),
            runtime = tmdb?.runtime,
            trailer = tmdb?.trailer,
            imdbId = tmdb?.imdbId,
            quality = quality(doc.text()),
            seasons = seasons.ifEmpty { listOf(Season(id = "$id#season-1", number = 1, episodes = emptyList())) }
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val showId = seasonId.substringBefore("#season-")
        val season = seasonId.substringAfter("#season-", "1").toIntOrNull() ?: 1
        val doc = service.get(absolute(showId))
        val clean = normalizeSeriesTitle(title(doc))
        val tmdb = runCatching { TmdbUtils.getTvShow(clean, language = language) }.getOrNull()
        val tmdbEpisodes = tmdb?.let {
            runCatching { TmdbUtils.getEpisodesBySeason(it.id, season, language = language) }.getOrDefault(emptyList())
        } ?: emptyList()

        val native = discoverEpisodeNumbers(doc)
        val numbers = when {
            native.isNotEmpty() -> native
            tmdbEpisodes.isNotEmpty() -> tmdbEpisodes.map { it.number }
            else -> listOf(1)
        }

        return numbers.distinct().sorted().map { n ->
            val meta = tmdbEpisodes.firstOrNull { it.number == n }
            Episode(
                id = "$showId#s${season}e$n",
                number = n,
                title = meta?.title ?: "Episode $n",
                poster = meta?.poster,
                overview = meta?.overview
            )
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val showId = id.substringBefore("#s")
        val episodeNo = Regex("#s\\d+e(\\d+)", RegexOption.IGNORE_CASE).find(id)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val doc = service.get(absolute(showId))
        val urls = linkedSetOf<String>()

        // xStream parity for KinoGer:
        // movies expose hoster URLs in show(..., 'URL') calls;
        // series keep per-season/per-episode provider URL arrays.
        val idMatch = Regex("#s(\\d+)e(\\d+)", RegexOption.IGNORE_CASE).find(id)
        val seasonNo = idMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        val selectedEpisodeNo = idMatch?.groupValues?.getOrNull(2)?.toIntOrNull()

        if (seasonNo != null && selectedEpisodeNo != null) {
            extractXStreamEpisodeUrls(doc.html(), seasonNo, selectedEpisodeNo)
                .filter(::isStreamCandidate)
                .forEach(urls::add)
        } else {
            Regex(
                """show[^>]*?\\d+[^>]*?'(https?://[^']+)'""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
                .findAll(doc.html())
                .map { it.groupValues[1].replace("\\/", "/") }
                .filter(::isStreamCandidate)
                .forEach(urls::add)
        }

        doc.select("iframe[src], source[src], a[href]").forEach { el ->
            val raw = el.attr(if (el.hasAttr("src")) "src" else "href").trim()
            val u = normalizeUrl(raw)
            if (isStreamCandidate(u)) urls += u
        }

        val html = doc.html()
        Regex("""https?://[^"'\\\s<>]+""").findAll(html).forEach { m ->
            val u = m.value.replace("\\/", "/")
            if (isStreamCandidate(u)) urls += u
        }

        // KinoGer/iOS fallback: MeineCloud serial page for episode server discovery.
        if (episodeNo != null) {
            extractImdbId(doc)?.let { imdb ->
                runCatching {
                    val serial = service.get("https://meinecloud.click/serial/${imdb.removePrefix("tt")}")
                    serial.select("._ep, [data-link], [data-url], iframe[src], a[href]").forEach { el ->
                        val label = el.attr("data-label")
                        if (label.isBlank() || Regex("E0*${episodeNo}\\b", RegexOption.IGNORE_CASE).containsMatchIn(label)) {
                            listOf("data-link", "data-url", "src", "href").forEach { attr ->
                                val u = normalizeUrl(el.attr(attr))
                                if (isStreamCandidate(u)) urls += u
                            }
                        }
                    }
                }
            }
        }

        return urls.mapIndexed { index, src ->
            Video.Server(id = "kinoger-$index", name = hostName(src), src = src)
        }
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.src)

    override suspend fun getGenre(id: String, page: Int): Genre {
        val items = runCatching { parseCatalog(service.get(pageUrl(id, page)), null) }.getOrDefault(emptyList())
        return Genre(id = id, name = id.substringAfterLast('/').ifBlank { "KinoGer" }, shows = items.filterIsInstance<Show>())
    }

    override suspend fun getPeople(id: String, page: Int): People =
        People(id = id, name = id, filmography = search(id, page).filterIsInstance<Show>())

    private data class Entry(val id: String, val title: String, val poster: String?, val year: String?, val quality: String?, val tv: Boolean)

    private fun parseCatalog(doc: Document, forceTv: Boolean?): List<Show> =
        parseEntries(doc)
            .mapNotNull { e ->
                val tv = forceTv ?: e.tv
                val t = if (tv) normalizeSeriesTitle(e.title) else cleanMovieTitle(e.title)

                if (t.isBlank()) {
                    null
                } else if (tv) {
                    TvShow(
                        id = e.id,
                        title = t,
                        poster = e.poster,
                        released = e.year,
                        quality = e.quality
                    )
                } else {
                    Movie(
                        id = e.id,
                        title = t,
                        poster = e.poster,
                        released = e.year,
                        quality = e.quality
                    )
                }
            }
            .filterIsInstance<Show>()
            .distinctBy { show ->
                when (show) {
                    is Movie -> "movie:${show.id}"
                    is TvShow -> "tv:${show.id}"
                }
            }


    private fun parseEntries(doc: Document): List<Entry> {
        val out = mutableListOf<Entry>()
        val seen = mutableSetOf<String>()
        val blocks = doc.select("div.title")
        val anchors = if (blocks.isNotEmpty()) blocks.mapNotNull { it.selectFirst("a[href]") } else doc.select("a[href$=.html]")
        anchors.forEach { a ->
            val href = absolute(a.attr("href"))
            val expectedHost = runCatching {
                java.net.URI(baseUrl).host?.removePrefix("www.")
            }.getOrNull()

            val hrefHost = runCatching {
                java.net.URI(href).host?.removePrefix("www.")
            }.getOrNull()

            if (
                expectedHost == null ||
                hrefHost == null ||
                !(hrefHost.equals(expectedHost, ignoreCase = true) ||
                  hrefHost.endsWith(".$expectedHost", ignoreCase = true))
            ) return@forEach
            if (!seen.add(href)) return@forEach
            val raw = a.text().trim().ifBlank { a.parent()?.text()?.trim().orEmpty() }
            if (raw.length < 2) return@forEach
            val container = a.parents().firstOrNull { it.select("img").isNotEmpty() } ?: a.parent() ?: a
            val text = container.text()
            out += Entry(
                id = href,
                title = raw,
                poster = poster(container),
                year = Regex("\\b(?:19|20)\\d{2}\\b").find(text)?.value,
                quality = quality(text),
                tv = raw.contains("Staffel", true) || text.contains("Episode", true) || href.contains("serie", true)
            )
        }
        return out
    }

    private fun pageUrl(path: String, page: Int): String {
        val p = absolute(path)
        return if (page <= 1) p else "${p.trimEnd('/')}/page/$page/"
    }

    private fun absolute(raw: String): String {
        val s = raw.trim()
        return when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.startsWith("//") -> "https:$s"
            else -> baseUrl + s.trimStart('/')
        }
    }

    private fun normalizeUrl(raw: String): String = absolute(raw.replace("&amp;", "&"))

    private fun title(doc: Document): String =
        doc.selectFirst("h1, .content_text h1, .fullstory h1, .title h1, .title h2")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.title().substringBefore(" | ").substringBefore(" » ").trim()

    private fun overview(doc: Document): String? =
        doc.selectFirst(".content_text .text, .full-text, .fullstory, [itemprop=description]")?.text()?.trim()
            ?.takeIf { it.length > 20 }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }

    private fun poster(doc: Document): String? =
        doc.select(".content_text img, .fullstory img, img.poster, img[data-src], img[src]")
            .asSequence().mapNotNull { poster(it) }.firstOrNull()

    private fun poster(el: Element): String? {
        val raw = listOf("data-src", "data-lazy-src", "src").map { el.attr(it).trim() }.firstOrNull { it.isNotBlank() } ?: return null
        val u = normalizeUrl(raw)
        val l = u.lowercase()
        return u.takeUnless { l.contains("logo") || l.contains("favicon") || l.contains("header") || l.contains("no_image") || l.contains("noimage") }
    }

    private fun year(doc: Document, raw: String): String? =
        Regex("\\b(?:19|20)\\d{2}\\b").find(raw)?.value ?: Regex("\\b(?:19|20)\\d{2}\\b").find(doc.text())?.value

    private fun quality(text: String): String? =
        Regex("\\b(?:4K|UHD|1080p|720p|HD|WEB[- ]?DL|BluRay|BDRip|HDRip)\\b", RegexOption.IGNORE_CASE)
            .find(text)?.value

    private fun cleanMovieTitle(raw: String): String =
        raw.replace(Regex("\\s*\\((?:19|20)\\d{2}\\)\\s*$"), "")
            .replace(Regex("\\s*[-–|]\\s*(?:4K|UHD|1080p|720p|HD).*$", RegexOption.IGNORE_CASE), "")
            .trim()

    private fun normalizeSeriesTitle(raw: String): String =
        raw.replace(Regex("\\s*[-–:]?\\s*Staffel\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Season\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\((?:19|20)\\d{2}\\)\\s*$"), "")
            .trim()

    private fun seasonNumber(raw: String): Int? =
        Regex("(?:Staffel|Season)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun discoverEpisodeNumbers(doc: Document): List<Int> {
        val nums = linkedSetOf<Int>()
        val text = doc.html()
        listOf(
            Regex("""(?:episode|folge|ep)[\s_\-:]*(\d{1,3})""", RegexOption.IGNORE_CASE),
            Regex("""\bS\d{1,2}E(\d{1,3})\b""", RegexOption.IGNORE_CASE),
            Regex("""data-(?:episode|ep)=["']?(\d{1,3})""", RegexOption.IGNORE_CASE)
        ).forEach { r -> r.findAll(text).forEach { nums += it.groupValues[1].toIntOrNull() ?: return@forEach } }
        return nums.filter { it in 1..999 }
    }

    private fun extractImdbId(doc: Document): String? =
        Regex("""tt\d{5,10}""").find(doc.html())?.value

    private fun extractXStreamEpisodeUrls(html: String, season: Int, episode: Int): List<String> {
        if (season < 1 || episode < 1) return emptyList()

        val result = linkedSetOf<String>()
        listOf("sst", "ollhd", "pw", "go").forEach { provider ->
            val block = Regex(
                """${provider}\\.show(.*?)</script>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(html)?.groupValues?.getOrNull(1) ?: return@forEach

            val seasonGroups = Regex("""\\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
                .findAll(block)
                .map { it.groupValues[1] }
                .toList()

            val group = seasonGroups.getOrNull(season - 1)
                ?: seasonGroups.firstOrNull()
                ?: block

            val links = Regex("""https?://[^'",\\]\\s]+""", RegexOption.IGNORE_CASE)
                .findAll(group)
                .map { it.value.replace("\\/", "/") }
                .toList()

            links.getOrNull(episode - 1)?.let(result::add)
        }

        return result.toList()
    }

    private fun isStreamCandidate(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val l = url.lowercase()
        if (l.contains("youtube.") || l.contains("youtu.be") || l.contains("imdb.com") || l.contains("tmdb.org")) return false
        return listOf("voe", "vidoza", "streamtape", "dood", "luluvdo", "veo", "vidmoly", "filemoon",
            "streamwish", "mixdrop", "upstream", "uqload", "supervideo", "mcloud", "meinecloud",
            ".m3u8", ".mp4", "/embed", "/e/").any { l.contains(it) }
    }

    private fun hostName(url: String): String =
        runCatching { java.net.URI(url).host?.removePrefix("www.") ?: "Server" }.getOrDefault("Server")
}
