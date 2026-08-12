package com.streamflixreborn.streamflix.providers

import android.net.Uri
import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * CineHax proxies TMDb metadata (its item ids ARE TMDb ids) through a WordPress theme, so
 * catalog/search/detail lean on [TmdbUtils] instead of scraping HTML for that data. The only
 * scraping needed is resolving playable servers: the /watch/ detail page links to an "UNLIMPLAY"
 * embed page whose HTML contains a server-rendered `const EMBEDS = {...}` JSON blob mapping
 * language -> server label -> real embed URL (remux.unlimplay.com is a first-party direct MP4
 * CDN; the rest are hosts already covered by the shared [Extractor] system).
 */
object CineHaxProvider : Provider {

    override val name = "CineHax"
    override val baseUrl = "https://cinehax.com"
    override val logo = "https://cinehax.com/wp-content/uploads/2026/06/cropped-favicon-192x192.jpg"
    override val language = "es"

    private const val TAG = "CineHaxProvider"
    private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
    private const val UNLIMPLAY_HOST = "unlimplay.com"
    private const val REMUX_HOST = "remux.unlimplay.com"

    private val LANGUAGE_ORDER = listOf("latino", "subtitulado", "castellano")
    private val LANGUAGE_LABELS = mapOf(
        "latino" to "Latino",
        "subtitulado" to "Subtitulado",
        "castellano" to "Castellano",
    )
    private val PRIORITY_SERVERS = listOf("remux")

    private val HOME_CATEGORY_LABELS = mapOf(
        "trending_all" to "Tendencias",
        "trending_movies" to "Películas en tendencia",
        "trending_series" to "Series en tendencia",
        "popular_movies" to "Películas populares",
        "popular_tv" to "Series populares",
        "top_rated_movies" to "Películas mejor valoradas",
        "top_rated_tv" to "Series mejor valoradas",
        "now_playing_movies" to "En cartelera",
        "upcoming_movies" to "Próximamente",
    )

    private val GENRES = listOf(
        "movie:action" to "Acción",
        "movie:adventure" to "Aventura",
        "movie:comedy" to "Comedia",
        "movie:fantasy" to "Fantasía",
        "movie:history" to "Historia",
        "movie:horror" to "Terror",
        "movie:music" to "Música",
        "movie:mystery" to "Misterio",
        "movie:romance" to "Romance",
        "movie:war" to "Bélica",
        "movie:western" to "Western",
        "tv:action-adventure" to "Acción y aventura",
        "tv:kids" to "Infantil",
        "tv:sci-fi-fantasy" to "Ciencia ficción y fantasía",
        "tv:soap" to "Telenovela",
        "tv:war-politics" to "Guerra y política",
    )

    // region HTTP

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                .build()
            chain.proceed(request)
        }
        .build()

    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $url")
            return response.body?.string().orEmpty()
        }
    }

    // endregion

    // region Catalog (TMDb-shaped JSON, no HTML scraping)

    private fun JSONObject.toShow(): Show? {
        val id = optInt("id", -1).takeIf { it != -1 } ?: return null
        val isTv = optString("media_type") == "tv" || (!has("title") && has("name"))
        val poster = optString("poster_path").takeIf { it.isNotEmpty() }?.let { "$TMDB_IMAGE_BASE$it" }
        val backdrop = optString("backdrop_path").takeIf { it.isNotEmpty() }?.let { "$TMDB_IMAGE_BASE$it" }
        val overview = optString("overview").takeIf { it.isNotEmpty() }

        return if (isTv) {
            TvShow(
                id = id.toString(),
                title = optString("name").ifEmpty { optString("title") },
                overview = overview,
                poster = poster,
                banner = backdrop,
            )
        } else {
            Movie(
                id = id.toString(),
                title = optString("title").ifEmpty { optString("name") },
                overview = overview,
                poster = poster,
                banner = backdrop,
            )
        }
    }

    private fun humanizeKey(key: String) = key.replace('_', ' ').replaceFirstChar { it.uppercase() }

    override suspend fun getHome(): List<Category> {
        val json = JSONObject(get("$baseUrl/wp-json/primeshow/v1/home-data"))
        val categories = mutableListOf<Category>()
        json.keys().forEach { key ->
            val items = json.optJSONArray(key) ?: return@forEach
            val shows = (0 until items.length()).mapNotNull { items.optJSONObject(it)?.toShow() }
            if (shows.isNotEmpty()) {
                categories.add(Category(name = HOME_CATEGORY_LABELS[key] ?: humanizeKey(key), list = shows))
            }
        }
        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            return GENRES.map { (id, genreName) -> Genre(id = id, name = genreName) }
        }
        if (page > 1) return emptyList()

        val url = "$baseUrl/wp-admin/admin-ajax.php?action=tmdb_live_search&query=${URLEncoder.encode(query, "UTF-8")}"
        val json = JSONObject(get(url))
        if (!json.optBoolean("success")) return emptyList()
        val results = json.optJSONArray("data") ?: return emptyList()
        return (0 until results.length()).mapNotNull { results.optJSONObject(it)?.toShow() }
    }

    private fun fetchExplorePage(type: String, page: Int, genre: String = "", sort: String = "popular"): List<Show> {
        val url = "$baseUrl/wp-admin/admin-ajax.php?action=load_explore_data" +
                "&page=$page&type=$type&genre=$genre&network=&language=&sort=$sort&q="
        val json = JSONObject(get(url))
        if (!json.optBoolean("success")) return emptyList()
        val html = json.optJSONObject("data")?.optString("html").orEmpty()
        if (html.isBlank()) return emptyList()

        return Jsoup.parse(html).select("a[href*=/watch/]").mapNotNull { a ->
            val id = Regex("""id=(\d+)""").find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            val title = a.selectFirst("h3")?.text().orEmpty()
            val poster = a.selectFirst("img")?.attr("src")
            if (type == "tv") {
                TvShow(id = id, title = title, poster = poster, banner = poster)
            } else {
                Movie(id = id, title = title, poster = poster, banner = poster)
            }
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> =
        fetchExplorePage("movie", page).filterIsInstance<Movie>()

    override suspend fun getTvShows(page: Int): List<TvShow> =
        fetchExplorePage("tv", page).filterIsInstance<TvShow>()

    override suspend fun getGenre(id: String, page: Int): Genre {
        val type = id.substringBefore(":")
        val slug = id.substringAfter(":")
        val shows = fetchExplorePage(type, page, genre = slug)
        return Genre(id = id, name = GENRES.toMap()[id] ?: slug, shows = shows)
    }

    // endregion

    // region Detail
    //
    // cinehax.com used to server-render a schema.org JSON-LD block with the title/overview/
    // rating/genres, but that block disappeared from /watch/ pages (verified against multiple
    // ids with cache-busting - not a stale-cache fluke). The same data is still on the page in
    // other forms though: the title/backdrop are query params on the "data-url" embed link (the
    // same one getServers() already reads), the overview sits right after an <h3>Descripción</h3>,
    // and rating/genres/release-year are plain DOM text. [parseWatchPageFromDom] reads those
    // directly. [parseWatchPage] still tries the JSON-LD first in case they bring it back.

    private data class WatchPageMeta(
        val title: String,
        val overview: String?,
        val poster: String?,
        val backdrop: String?,
        val rating: Double?,
        val released: String?,
        val genres: List<Genre>,
        val trailer: String?,
    )

    private fun parseWatchPage(html: String): WatchPageMeta {
        return parseWatchPageFromJsonLd(html)?.takeIf { it.title.isNotBlank() }
            ?: parseWatchPageFromDom(html)
    }

    private fun parseWatchPageFromJsonLd(html: String): WatchPageMeta? {
        val json = Regex("""<script type="application/ld\+json">\s*(\{.*?\})\s*</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null

        val genres = json.optJSONArray("genre")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotEmpty) }
        }.orEmpty().map { Genre(id = it.lowercase(), name = it) }

        return WatchPageMeta(
            title = json.optString("name"),
            overview = json.optString("description").takeIf { it.isNotEmpty() },
            poster = extractPoster(html),
            backdrop = json.optString("image").takeIf { it.isNotEmpty() },
            rating = json.optJSONObject("aggregateRating")?.optDouble("ratingValue")?.takeIf { !it.isNaN() },
            released = json.optString("datePublished").takeIf { it.isNotEmpty() }
                ?: json.optString("startDate").takeIf { it.isNotEmpty() },
            genres = genres,
            trailer = extractTrailer(html),
        )
    }

    private fun parseWatchPageFromDom(html: String): WatchPageMeta {
        val embedDataUrl = Regex("""data-url="(https://$UNLIMPLAY_HOST[^"]*)"""")
            .find(html)?.groupValues?.get(1)?.replace("&#038;", "&")
            ?: throw Exception("No se pudo leer la metadata de CineHax")
        val embedUri = Uri.parse(embedDataUrl)

        val overview = Regex("""Descripción</h3>\s*<p[^>]*>([^<]*)</p>""")
            .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

        val rating = Regex("""font-bold text-white flex items-center gap-0\.5">([\d.]+)</div>""")
            .find(html)?.groupValues?.get(1)?.toDoubleOrNull()

        val released = Regex("""\bde (\d{4})</span>""").find(html)?.groupValues?.get(1)

        val genres = Regex("""px-2 py-1 bg-white/5 border border-gray-700 rounded-full text-white text-xs">\s*([^<]+?)\s*</div>""")
            .findAll(html)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .map { Genre(id = it.lowercase(), name = it) }
            .toList()

        return WatchPageMeta(
            title = embedUri.getQueryParameter("title").orEmpty(),
            overview = overview,
            poster = extractPoster(html),
            backdrop = embedUri.getQueryParameter("backdrop"),
            rating = rating,
            released = released,
            genres = genres,
            trailer = extractTrailer(html),
        )
    }

    private fun extractPoster(html: String) =
        Regex("""https://image\.tmdb\.org/t/p/w500/[^"]+""").find(html)?.value

    private fun extractTrailer(html: String): String? {
        val embedUrl = Regex("""id="iframe-trailer" src="([^"]+)"""").find(html)?.groupValues?.get(1)
        return embedUrl?.substringAfterLast("/")?.let { "https://www.youtube.com/watch?v=$it" }
    }

    override suspend fun getMovie(id: String): Movie {
        val meta = parseWatchPage(get("$baseUrl/watch/?type=movie&id=$id"))
        return Movie(
            id = id,
            title = meta.title,
            overview = meta.overview,
            released = meta.released,
            rating = meta.rating,
            poster = meta.poster,
            banner = meta.backdrop,
            trailer = meta.trailer,
            genres = meta.genres,
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val html = get("$baseUrl/watch/?type=tv&id=$id&season=1&episode=1")
        val meta = parseWatchPage(html)
        // The season chips and every episode link on the page both carry "season=N"; collecting
        // every distinct N this way is more robust than pinning to one exact CSS structure.
        val seasonNumbers = Regex("""season=(\d+)""").findAll(html)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .distinct()
            .sorted()
            .toList()
            .ifEmpty { listOf(1) }

        return TvShow(
            id = id,
            title = meta.title,
            overview = meta.overview,
            released = meta.released,
            rating = meta.rating,
            poster = meta.poster,
            banner = meta.backdrop,
            trailer = meta.trailer,
            genres = meta.genres,
            seasons = seasonNumbers.map { number -> Season(id = "$id-$number", number = number, title = "Temporada $number") },
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val tvId = seasonId.substringBeforeLast("-")
        val seasonNumber = seasonId.substringAfterLast("-").toIntOrNull() ?: 1
        val html = get("$baseUrl/watch/?type=tv&id=$tvId&season=$seasonNumber&episode=1")

        // Episode entries are the only "season=N&episode=" anchors that contain a thumbnail
        // <img> - the season-selector chips link to the same URL shape but have no image.
        return Jsoup.parse(html).select("a[href*=season=$seasonNumber&episode=]:has(img)")
            .mapNotNull { a ->
                val number = Regex("""episode=(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                Episode(
                    id = "$tvId|$seasonNumber|$number",
                    number = number,
                    title = "Episodio $number",
                    poster = a.selectFirst("img")?.attr("src"),
                )
            }
            .distinctBy { it.number }
            .sortedBy { it.number }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        // cinehax.com's /watch/ pages don't list cast/crew anywhere (verified: no actor names,
        // profile images, or "Reparto" section in the markup), so there's no source to scrape.
        TODO("Not yet implemented")
    }

    // endregion

    // region Playback

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val watchUrl = when (videoType) {
            is Video.Type.Movie -> "$baseUrl/watch/?type=movie&id=$id"
            is Video.Type.Episode -> {
                val parts = id.split("|")
                val tvId = parts.getOrNull(0) ?: return emptyList()
                val season = parts.getOrNull(1) ?: "1"
                val episode = parts.getOrNull(2) ?: "1"
                "$baseUrl/watch/?type=tv&id=$tvId&season=$season&episode=$episode"
            }
            else -> return emptyList()
        }

        val doc = Jsoup.parse(get(watchUrl))
        val embedPageUrls = doc.select("[data-url*=$UNLIMPLAY_HOST]")
            .map { it.attr("data-url") }
            .distinct()
        if (embedPageUrls.isEmpty()) return emptyList()

        val servers = mutableListOf<Video.Server>()
        for (embedPageUrl in embedPageUrls) {
            try {
                servers.addAll(resolveUnlimplayServers(embedPageUrl))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve embed page $embedPageUrl: ${e.message}")
            }
        }
        return servers
    }

    private fun resolveUnlimplayServers(embedPageUrl: String): List<Video.Server> {
        val embedHtml = get(embedPageUrl)
        val embedsJson = Regex("""const EMBEDS\s*=\s*(\{.*?\});""")
            .find(embedHtml)?.groupValues?.get(1)
            ?: return emptyList()
        val embeds = JSONObject(embedsJson)

        val languages = embeds.keys().asSequence().toList()
        val orderedLanguages = LANGUAGE_ORDER.filter { it in languages } + (languages - LANGUAGE_ORDER.toSet())

        return orderedLanguages.flatMap { lang ->
            val langServers = embeds.optJSONObject(lang) ?: return@flatMap emptyList()
            val labels = langServers.keys().asSequence().toList()
            val orderedLabels = PRIORITY_SERVERS.filter { it in labels } + (labels - PRIORITY_SERVERS.toSet())

            orderedLabels.mapNotNull { serverLabel ->
                val url = langServers.optString(serverLabel).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val langLabel = LANGUAGE_LABELS[lang] ?: lang.replaceFirstChar { it.uppercase() }
                Video.Server(
                    id = url,
                    name = "${serverLabel.replaceFirstChar { it.uppercase() }} · $langLabel",
                    src = url,
                )
            }
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        if (server.src.contains(REMUX_HOST)) {
            return Video(source = server.src, type = MimeTypes.VIDEO_MP4)
        }
        return Extractor.extract(server.src, server)
    }

    // endregion
}
