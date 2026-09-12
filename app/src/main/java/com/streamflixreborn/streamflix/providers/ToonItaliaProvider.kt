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
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.TmdbUtils
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Provider for ToonItalia (https://toonitalia.xyz).
 *
 * Verified against the live site (WordPress + GeneratePress, Yoast SEO):
 * - Home (`/`): `<div class="grid">` > `<div class="col">` sections with `<h2>`
 *   (Ultimi Aggiornamenti, Anime, Serie TV, Film Animazione); cards are
 *   `<div class="item">` > `a.card-link[href]` with `img[src]` + `span.title`.
 *   Logo: `/wp-content/uploads/2026/04/Toonitalia-personaggi.png`.
 * - Search: `/?s={query}` (Yoast SearchAction template); results are
 *   `article h2.entry-title a[href]` (verified with `?s=one+piece`).
 * - Category archives: `/category/anime/`, `/category/serie-tv/`,
 *   `/category/film-animazione/` return `article` lists (100 per page,
 *   verified on `/category/anime/`); paginated via `/page/{n}/`.
 * - Detail posts: `h1.entry-title`, `meta[property=og:image]`, `.entry-content`.
 *   Anime (e.g. `/one-piece/`): uqload.vc embed links (PLAYER1, ~1336 links) +
 *   chuckle-tube.com embed links (PLAYER2). Serie TV (e.g. `/supernatural/`):
 *   uprot.net msf links (PLAYER1, 328 links). Film (e.g. `/nimona/`):
 *   `Link Streaming` with a VOE-labeled chuckle-tube link and a VIDHIDE
 *   dhtpre.com file link.
 *
 * Playback notes:
 * - `chuckle-tube.com` redirects to `johnfullwonder.com`; both are VOE
 *   aliases handled by VoeExtractor.
 * - ToonItalia animation films commonly expose PLAYER1 via `uqload.vc`
 *   and PLAYER2 via `chuckle-tube.com`; PLAYER2 is the reliable VOE path.
 * - uprot.net msf links resolve to Maxstream via CB01-style UPROT API
 *   keys (see CB01Provider.callUprotApi + Keys); without keys the raw URL is
 *   exposed and generic extraction fails, same as CB01 without secrets.
 * - `dhtpre.com` works via VidHideExtractor.
 * - `uqload.vc` is still recognized by UqloadExtractor, but its current
 *   embed flow is not reliably supported; PLAYER2 uses the working VOE path.
 */
object ToonItaliaProvider : Provider {

    override val name = "ToonItalia"
    override val baseUrl = "https://toonitalia.xyz"
    override val language = "it"
    override val logo: String get() = "$baseUrl/wp-content/uploads/2026/04/Toonitalia-personaggi.png"

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    private fun getOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", DEFAULT_USER_AGENT)
                        .build()
                )
            }
            .dns(DnsResolver.doh)
            .build()
    }

    private val retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(getOkHttpClient())
        .build()
    private val service = retrofit.create(Service::class.java)

    private interface Service {
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    override suspend fun getHome(): List<Category> {
        return try {
            val doc = service.getPage(baseUrl)
            doc.select("div.grid > div.col").mapNotNull { col ->
                val name = col.selectFirst("h2")?.text()?.trim().orEmpty()
                if (name.isBlank()) return@mapNotNull null
                val shows = col.select("div.item").mapNotNull { parseCard(it) }
                if (shows.isEmpty()) null else Category(name = name, list = shows)
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseCard(el: Element): TvShow? {
        val a = el.selectFirst("a.card-link[href]") ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank()) return null
        val img = a.selectFirst("img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }.orEmpty()
        val title = a.selectFirst("span.title")?.text()?.trim().orEmpty()
        if (title.isBlank()) return null
        return TvShow(
            id = href,
            title = title,
            poster = img.ifBlank { null },
            banner = img.ifBlank { null },
        )
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            // Verified category archives used by getGenre().
            return listOf(
                Genre(id = "anime", name = "Anime"),
                Genre(id = "serie-tv", name = "Serie TV"),
                Genre(id = "film-animazione", name = "Film Animazione"),
            )
        }
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = if (page > 1) "$baseUrl/page/$page/?s=$encoded" else "$baseUrl/?s=$encoded"
            parseArticles(service.getPage(url))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val url = if (page > 1) "$baseUrl/category/film-animazione/page/$page/" else "$baseUrl/category/film-animazione/"
            parseArticles(service.getPage(url)).mapNotNull { it as? Movie ?: movieFromShow(it) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val url = if (page > 1) "$baseUrl/category/anime/page/$page/" else "$baseUrl/category/anime/"
            parseArticles(service.getPage(url)).mapNotNull {
                when (it) {
                    is TvShow -> it
                    is Movie -> TvShow(id = it.id, title = it.title, poster = it.poster, banner = it.banner)
                    else -> null
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun movieFromShow(item: AppAdapter.Item): Movie? {
        return when (item) {
            is Movie -> item
            is TvShow -> Movie(id = item.id, title = item.title, poster = item.poster, banner = item.banner)
            else -> null
        }
    }

    private fun parseArticles(doc: Document): List<AppAdapter.Item> {
        // Search + category archives render posts as article > h2.entry-title > a.
        // Category cards may carry a featured image; search excerpts do not.
        return doc.select("article").mapNotNull { article ->
            val a = article.selectFirst("h2.entry-title a[href]") ?: return@mapNotNull null
            val href = a.attr("href").trim()
            val title = a.text().trim()
            if (href.isBlank() || title.isBlank()) return@mapNotNull null
            val img = article.selectFirst("img[src*=/uploads/]")?.attr("src")?.trim().orEmpty()
            val isFilm = href.contains("/film-") ||
                article.classNames().any { it.contains("film-animazione") } ||
                article.select(".cat-links a").eachText().any { it.contains("Film", ignoreCase = true) }
            if (isFilm) {
                Movie(id = href, title = title, poster = img.ifBlank { null }, banner = img.ifBlank { null })
            } else {
                TvShow(id = href, title = title, poster = img.ifBlank { null }, banner = img.ifBlank { null })
            }
        }.distinctBy {
            when (it) {
                is Movie -> it.id
                is TvShow -> it.id
                else -> it.toString()
            }
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val page = parsePost(service.getPage(id))
        val tmdbMovie = try {
            TmdbUtils.getMovie(page.title, language = language)
        } catch (_: Exception) { null }
        return Movie(
            id = id,
            title = page.title,
            poster = tmdbMovie?.poster ?: page.poster,
            banner = tmdbMovie?.banner ?: page.poster,
            overview = tmdbMovie?.overview ?: page.overview,
            rating = tmdbMovie?.rating,
            released = tmdbMovie?.released?.let { "${it.get(java.util.Calendar.YEAR)}" },
            genres = page.categories.map { Genre(id = it, name = it) },
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val page = parsePost(service.getPage(id))
        val tmdbShow = try {
            TmdbUtils.getTvShow(page.title, language = language)
        } catch (_: Exception) { null }
        return TvShow(
            id = id,
            title = page.title,
            poster = tmdbShow?.poster ?: page.poster,
            banner = tmdbShow?.banner ?: page.poster,
            overview = tmdbShow?.overview ?: page.overview,
            rating = tmdbShow?.rating,
            genres = page.categories.map { Genre(id = it, name = it) },
            seasons = page.seasons.map {
                Season(id = "$id#s=${it.number}", number = it.number, title = it.title)
            },
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val seasonNumber = seasonId.substringAfter("#s=", "").toIntOrNull() ?: 1
        val pageUrl = seasonId.substringBefore("#s=")
        val block = parsePost(service.getPage(pageUrl)).seasons
            .firstOrNull { it.number == seasonNumber } ?: return emptyList()
        return block.lines.mapIndexedNotNull { index, line ->
            if (line.players.isEmpty()) null
            else Episode(
                id = "$pageUrl#s=$seasonNumber&e=$index",
                number = line.episode ?: (index + 1),
                title = line.label.ifBlank { "Episodio ${index + 1}" },
            )
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        return try {
            val slug = when {
                id.startsWith("http") -> id.trimEnd('/').substringAfterLast("/").substringBefore("?").substringBefore("#")
                else -> id.trim('/').substringAfterLast("/").substringBefore("?").substringBefore("#")
            }.ifBlank { id }
            val base = "$baseUrl/category/$slug/"
            val url = if (page > 1) "${base}page/$page/" else base
            val doc = service.getPage(url)
            val name = doc.selectFirst("h1.page-title, h1.entry-title")?.text()?.trim()?.ifBlank { null }
                ?: slug.replace('-', ' ')
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            Genre(id = slug, name = name, shows = parseArticles(doc).mapNotNull { it as? Show })
        } catch (_: Exception) { Genre(id = id, name = id) }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        throw Exception("ToonItalia non ha schede per attori e autori")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        // Movies carry their "Link Streaming" players on the post itself.
        if (videoType is Video.Type.Movie) {
            return try {
                val players = parsePost(service.getPage(videoType.id)).seasons
                    .flatMap { it.lines }.flatMap { it.players }
                players.map { (name, url) ->
                    Video.Server(id = url, name = name, src = url)
                }
            } catch (_: Exception) { emptyList() }
        }
        return try {
            val pageUrl = id.substringBefore("#s=")
            val season = id.substringAfter("#s=", "").substringBefore("&e=").toIntOrNull() ?: 1
            val index = id.substringAfter("&e=", "").toIntOrNull() ?: 0
            val line = parsePost(service.getPage(pageUrl)).seasons
                .firstOrNull { it.number == season }
                ?.lines?.getOrNull(index) ?: return emptyList()
            line.players.map { (name, url) ->
                Video.Server(id = url, name = name, src = url)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.src, server)
    }

    private data class PlayerLink(val name: String, val url: String)
    private data class EpisodeLine(val label: String, val episode: Int?, val players: List<PlayerLink>)
    private data class SeasonBlock(val number: Int, val title: String, val lines: List<EpisodeLine>)
    private data class PostPage(
        val title: String,
        val poster: String?,
        val overview: String?,
        val categories: List<String>,
        val seasons: List<SeasonBlock>,
    )

    private val excludedHosts = setOf(
        "toonitalia.xyz",
        "animeclick.it", "www.animeclick.it",
        "wikipedia.org",
        "myanimelist.net",
        "mymovies.it", "filmtv.it", "comingsoon.it",
        "youtube.com", "www.youtube.com", "youtu.be",
        "facebook.com", "twitter.com", "instagram.com", "t.me", "telegram.me",
    )

    private fun parsePost(doc: Document): PostPage {
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty()
            .ifBlank {
                doc.selectFirst("div.entry-content img[src*=uploads]:not([src$=.gif])")
                    ?.attr("src")?.trim().orEmpty()
            }
            .ifBlank { null }
        val overview = extractOverview(doc)
        val categories = doc.select("footer .cat-links a[rel*=category], .cat-links a").eachText()
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        val bySeason = linkedMapOf<Int, MutableList<EpisodeLine>>()
        val specials = mutableListOf<EpisodeLine>()
        var current: Int? = null
        val content = doc.selectFirst("div.entry-content")
        if (content != null) {
            // Season headers are plain <p>/<h2>/<h3> text on the live site, e.g.
            // "Scegli Stagione:", "1° Stagione:", "Stagione-01", "2° Stagione:".
            for (el in content.select("h2, h3, p")) {
                val headerNumber = seasonNumber(el.text())
                val hasPlayers = el.select("a[href]").isNotEmpty()
                if (headerNumber != null && !hasPlayers) {
                    current = headerNumber
                    continue
                }
                // Header + first episode(s) can share one <p>; keep header active
                // and still parse the players below.
                if (headerNumber != null) current = headerNumber
                val lines = parseLines(el)
                if (lines.isEmpty()) continue
                if (current == null) specials.addAll(lines)
                else bySeason.getOrPut(current) { mutableListOf() }.addAll(lines)
            }
        }
        val seasons = bySeason.map { (n, lines) ->
            SeasonBlock(n, "Stagione $n", lines)
        }.toMutableList()
        if (seasons.isEmpty() && specials.isNotEmpty()) {
            // Film / single-season posts (e.g. /nimona/ "Link Streaming: ...").
            seasons.add(SeasonBlock(1, "Stagione 1", specials))
        } else if (specials.isNotEmpty()) {
            seasons.add(0, SeasonBlock(0, "Speciali", specials))
        }
        return PostPage(title, poster, overview, categories, seasons)
    }

    private fun extractOverview(doc: Document): String? {
        // Film posts: <h3>Trama:</h3><p>...Fonte: Wikipedia</p> (verified /nimona/).
        doc.select("h3").firstOrNull { it.text().contains("Trama", ignoreCase = true) }
            ?.nextElementSibling()?.text()?.trim()
            ?.substringBefore("Fonte:")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        // Anime/serie posts: <p><span>Trama:</span><br>...Fonte: Wikipedia</p>
        // (verified /one-piece/, /supernatural/).
        doc.select("div.entry-content p").firstOrNull { it.text().contains("Trama", ignoreCase = true) }
            ?.text()?.trim()
            ?.substringAfter("Trama")?.trim(' ', ':', '-', '–', '—')
            ?.substringBefore("Fonte:")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return null
    }

    private fun seasonNumber(header: String): Int? {
        val text = header.trim()
        if (text.isEmpty()) return null
        // "1° Stagione", "1a Stagione", "Stagione 1", "Stagione-01", "Stagione_02".
        Regex("(\\d+)\\s*[°ªoa]?\\s*stagione", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("stagione\\D*(\\d+)", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun parseLines(p: Element): List<EpisodeLine> {
        val out = mutableListOf<EpisodeLine>()
        for (chunk in p.html().split(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE))) {
            if (chunk.isBlank()) continue
            val frag = Jsoup.parseBodyFragment(chunk)
            val players = frag.select("a[href]").mapNotNull { toPlayer(it) }
            if (players.isEmpty()) continue
            var label = frag.text().replace(Regex("\\s+"), " ")
            for (a in frag.select("a")) label = label.replace(a.text().trim(), "")
            label = label.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', ':', '|', '·')
            val ep = Regex("(\\d+)\\s*[x×]\\s*(\\d+)").find(label)
                ?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: Regex("(?:episodio|ep\\.?|puntata)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(label)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
            out.add(EpisodeLine(label, ep, players))
        }
        return out
    }

    private fun toPlayer(a: Element): PlayerLink? {
        val url = a.attr("href").trim()
        if (!url.startsWith("http")) return null
        val host = url.substringAfter("://").substringBefore("/").substringBefore(":").lowercase()
        if (host.isBlank() || host in excludedHosts || host.endsWith(".toonitalia.xyz")) return null
        val name = a.text().trim().ifBlank {
            host.replaceFirst("www.", "").substringBefore(".")
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        return PlayerLink(name, url)
    }
}
