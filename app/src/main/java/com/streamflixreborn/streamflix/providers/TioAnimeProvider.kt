package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url

object TioAnimeProvider : Provider {
    override val name = "TioAnime"
    override val baseUrl = "https://tioanime.com"
    override val logo = "$baseUrl/assets/img/logo-dark.png"
    override val language = "es"

    private val service = Retrofit.Builder().baseUrl("$baseUrl/")
        .addConverterFactory(JsoupConverterFactory.create()).client(NetworkClient.default)
        .build().create(Service::class.java)

    private interface Service {
        @GET
        suspend fun page(@Url url: String): Document
    }

    override suspend fun getHome(): List<Category> {
        val document = service.page(baseUrl)
        return buildList {
            parseHomeEpisodes(document).takeIf { it.isNotEmpty() }?.let { add(Category("Últimos episodios", it)) }
            parseHomeSection(document, "Últimas Peliculas", movie = true)
                .takeIf { it.isNotEmpty() }?.let { add(Category("Últimas Peliculas", it)) }
            parseHomeSection(document, "Últimos Ovas")
                .takeIf { it.isNotEmpty() }?.let { add(Category("Últimos Ovas", it)) }
            parseHomeSection(document, "Últimos Especiales")
                .takeIf { it.isNotEmpty() }?.let { add(Category("Últimos Especiales", it)) }
            parseHomeSection(document, "Últimos Animes")
                .takeIf { it.isNotEmpty() }?.let { add(Category("Últimos Animes", it)) }
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return GENRES
        return parseDirectory(directoryPage(page, query = query))
    }

    override suspend fun getMovies(page: Int): List<Movie> =
        parseDirectory(directoryPage(page, type = "1"), movieOverride = true).filterIsInstance<Movie>()

    override suspend fun getTvShows(page: Int): List<TvShow> =
        parseDirectory(directoryPage(page, type = "0"), movieOverride = false).filterIsInstance<TvShow>()

    override suspend fun getMovie(id: String): Movie {
        val slug = normalizeSlug(id)
        val details = details(service.page(animeUrl(slug)), slug)
        return Movie(
            id = slug, title = details.title, overview = details.overview,
            poster = details.poster, banner = details.poster, genres = details.genres,
            recommendations = details.recommendations
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val slug = normalizeSlug(id)
        val details = details(service.page(animeUrl(slug)), slug)
        return TvShow(
            id = slug, title = details.title, overview = details.overview,
            poster = details.poster, banner = details.poster,
            seasons = listOf(Season("${details.numericId}|$slug", 1, "Episodios", details.poster)),
            genres = details.genres, recommendations = details.recommendations
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val slug = seasonId.substringAfter('|')
        return episodes(service.page(animeUrl(slug)), slug)
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        return Genre(
            id, GENRES.firstOrNull { it.id == id }?.name
                ?: id.replace('-', ' ').replaceFirstChar { it.uppercase() },
            parseDirectory(directoryPage(page, genre = id)).mapNotNull { it as? Show })
    }

    override suspend fun getPeople(id: String, page: Int): People =
        throw UnsupportedOperationException("TioAnime does not expose people pages")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val episodeUrl = if (id.contains("/ver/")) absolute(id) else {
            val slug = normalizeSlug(id)
            episodes(service.page(animeUrl(slug)), slug).firstOrNull()?.id ?: return emptyList()
        }
        val document = service.page(episodeUrl)
        val source = document.select("script").firstNotNullOfOrNull { script ->
            extractArray(script.data().ifBlank { script.html() }, "var videos")
        } ?: return emptyList()
        val videos = runCatching { JSONArray(source) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until videos.length()) {
                val video = videos.optJSONArray(i) ?: continue
                val label = video.optString(0).trim()
                val url = video.optString(1).trim()
                if (url.isBlank() || SUPPORTED_SERVERS.none { it.equals(label, true) }) continue
                add(Video.Server(url, label, url))
            }
        }.distinctBy { it.id }
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id, server)

    private suspend fun directoryPage(
        page: Int,
        query: String? = null,
        type: String? = null,
        genre: String? = null
    ): Document {
        val url = "$baseUrl/directorio".toHttpUrl().newBuilder().apply {
            query?.let { addQueryParameter("q", it) }
            type?.let { addQueryParameter("type[]", it) }
            genre?.let { addQueryParameter("genero[]", it) }
            addQueryParameter("p", page.coerceAtLeast(1).toString())
        }.build().toString()
        return service.page(url)
    }

    private fun parseDirectory(document: Document, movieOverride: Boolean? = null): List<AppAdapter.Item> =
        document.select("main article.anime").mapNotNull { article ->
            val link = article.selectFirst("a[href*='/anime/']") ?: return@mapNotNull null
            val slug = normalizeSlug(link.attr("href"));
            val title = article.selectFirst("h3.title")?.text()?.trim().orEmpty()
            if (slug.isBlank() || title.isBlank()) return@mapNotNull null
            val poster = image(article.selectFirst("img")?.attr("src"))
            val isMovie =
                movieOverride ?: article.selectFirst(".anime-type-peli")?.text()?.contains("Película", true) == true
            if (isMovie)
                Movie(slug, title, poster = poster) else TvShow(slug, title, poster = poster)
        }.distinctBy {
            when (it) {
                is Movie -> "m:${it.id}"; is TvShow -> "t:${it.id}"; else -> it.toString()
            }
        }

    private fun parseHomeSection(document: Document, heading: String, movie: Boolean = false): List<AppAdapter.Item> {
        val section = document.select("section").firstOrNull {
            it.selectFirst(".header .title")?.text()?.trim()?.equals(heading, ignoreCase = true) == true
        } ?: return emptyList()
        return section.select("article.anime").mapNotNull { article ->
            val link = article.selectFirst("a[href*='/anime/']") ?: return@mapNotNull null
            val slug = normalizeSlug(link.attr("href"));
            val title = article.selectFirst("h3.title")?.text()?.trim().orEmpty()
            if (slug.isBlank() || title.isBlank()) return@mapNotNull null
            val poster = image(article.selectFirst("img")?.attr("src"))
            if (movie) Movie(slug, title, poster = poster) else TvShow(slug, title, poster = poster)
        }.distinctBy {
            when (it) {
                is Movie -> "movie:${it.id}"
                is TvShow -> "tv:${it.id}"
                else -> it.toString()
            }
        }.take(24)
    }

    private fun parseHomeEpisodes(document: Document): List<TvShow> =
        document.select(".episodes article.episode").mapNotNull { article ->
            val link = article.selectFirst("a[href*='/ver/']") ?: return@mapNotNull null
            val epSlug = normalizeSlug(link.attr("href")).substringAfter("ver/")
            val slug = epSlug.replace(Regex("-\\d+$"), "")
            if (slug.isBlank()) return@mapNotNull null
            val poster = article.selectFirst("img")?.attr("src")
                ?.replace("/uploads/thumbs/", "/uploads/portadas/")
            TvShow(
                slug,
                article.selectFirst("h3.title")?.text()?.trim().orEmpty(),
                poster = image(poster)
            )
        }.distinctBy { it.id }.take(24)

    private fun details(document: Document, slug: String): Details {
        val article = document.selectFirst("article.anime-single") ?: error("TioAnime detail not found: $slug")
        val numericId =
            Regex("""var\s+anime_info\s*=\s*\[\s*[\"'](\d+)""").find(document.html())?.groupValues?.get(1) ?: slug
        return Details(
            numericId, article.selectFirst("h1.title")?.text()?.trim().orEmpty(),
            article.selectFirst("p.sinopsis")?.text()?.trim()?.takeIf { it.isNotBlank() },
            image(article.selectFirst(".thumb img")?.attr("src")),
            article.select("p.genres a[href*='genero=']")
                .map { Genre(it.attr("href").substringAfter("genero="), it.text().trim()) },
            emptyList()
        )
    }

    private fun episodes(document: Document, slug: String): List<Episode> {
        val script =
            document.select("script").firstOrNull { (it.data().ifBlank { it.html() }).contains("var episodes") }
                ?: return emptyList()
        val array = extractArray(script.data().ifBlank { script.html() }, "var episodes") ?: return emptyList()
        val numbers = runCatching { JSONArray(array) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until numbers.length()) {
                val number = numbers.optInt(i); if (number <= 0) continue
                add(Episode("$baseUrl/ver/$slug-$number", number, "$slug - Episodio $number"))
            }
        }.sortedBy { it.number }
    }

    private fun extractArray(source: String, marker: String): String? {
        val start = source.indexOf('[', source.indexOf(marker).takeIf { it >= 0 } ?: return null)
        if (start < 0) return null
        var depth = 0;
        var quoted = false;
        var escaped = false
        for (i in start until source.length) {
            when (val c = source[i]) {
                '"' -> if (!escaped) quoted = !quoted
                '\\' -> if (quoted) escaped = !escaped
                else -> {
                    escaped = false; if (!quoted && c == '[') depth++; if (!quoted && c == ']') {
                        depth--; if (depth == 0) return source.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun normalizeSlug(value: String): String =
        value.trim().substringAfter("tioanime.com/", value.trim()).substringBefore('?').trim('/').removePrefix("anime/")

    private fun animeUrl(slug: String) = "$baseUrl/anime/${normalizeSlug(slug)}"
    private fun absolute(value: String) =
        if (value.startsWith("http", true)) value else "$baseUrl/${value.trimStart('/')}"

    private fun image(value: String?): String? = value?.trim()?.takeIf { it.isNotBlank() }?.let(::absolute)

    private data class Details(
        val numericId: String,
        val title: String,
        val overview: String?,
        val poster: String?,
        val genres: List<Genre>,
        val recommendations: List<Show>
    )

    private val SUPPORTED_SERVERS = setOf("Voe", "YourUpload")
    private val GENRES = listOf(
        "accion" to "Acción",
        "aventura" to "Aventuras",
        "ciencia-ficcion" to "Ciencia Ficción",
        "comedia" to "Comedia",
        "deportes" to "Deportes",
        "drama" to "Drama",
        "fantasia" to "Fantasía",
        "historico" to "Histórico",
        "magia" to "Magia",
        "mecha" to "Mecha",
        "misterio" to "Misterio",
        "musica" to "Música",
        "romance" to "Romance",
        "samurai" to "Samurai",
        "seinen" to "Seinen",
        "shoujo" to "Shoujo",
        "shounen" to "Shounen",
        "sobrenatural" to "Sobrenatural",
        "terror" to "Terror",
        "vampiros" to "Vampiros",
        "yaoi" to "Yaoi",
        "yuri" to "Yuri"
    ).map { Genre(it.first, it.second) }
}
