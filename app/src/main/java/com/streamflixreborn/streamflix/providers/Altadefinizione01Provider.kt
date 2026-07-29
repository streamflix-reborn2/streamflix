package com.streamflixreborn.streamflix.providers

import android.util.Log
import android.net.Uri

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.TmdbUtils
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Header

object Altadefinizione01Provider : Provider {

    override val name: String = "Altadefinizione01"
    override val baseUrl: String = "https://altadefinizione-01.fun"
    override val logo: String get() = "$baseUrl/templates/altadefinizione01/images/logo.png"
    override val language: String = "it"

    private const val USER_AGENT = "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private interface Altadefinizione01Service {
        companion object {
            fun build(baseUrl: String): Altadefinizione01Service {
                val clientBuilder = OkHttpClient.Builder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)

                val client = clientBuilder.dns(DnsResolver.doh).build()

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                    .create(Altadefinizione01Service::class.java)
            }
        }

        @Headers(USER_AGENT)
        @GET(".")
        suspend fun getHome(): Document

        @Headers(USER_AGENT)
        @GET("cinema/")
        suspend fun getCinema(): Document

        @Headers(USER_AGENT)
        @GET("cinema/page/{page}/")
        suspend fun getCinema(@Path("page") page: Int): Document

        @Headers(USER_AGENT)
        @GET("serie-tv/")
        suspend fun getSerieTv(): Document

        @Headers(USER_AGENT)
        @GET("serie-tv/page/{page}/")
        suspend fun getSerieTv(@Path("page") page: Int): Document

        @Headers(USER_AGENT)
        @GET("index.php")
        suspend fun searchFirst(
            @Query("do") doParam: String = "search",
            @Query("subaction") subaction: String = "search",
            @Query("titleonly") titleonly: Int = 3,
            @Query(value = "story", encoded = true) story: String,
            @Query("full_search") fullSearch: Int = 0,
        ): Document

        @Headers(USER_AGENT)
        @GET("index.php")
        suspend fun searchPaged(
            @Query("do") doParam: String = "search",
            @Query("subaction") subaction: String = "search",
            @Query("titleonly") titleonly: Int = 3,
            @Query("full_search") fullSearch: Int = 0,
            @Query("search_start") searchStart: Int,
            @Query("result_from") resultFrom: Int,
            @Query(value = "story", encoded = true) story: String,
        ): Document

        @Headers(USER_AGENT)
        @GET
        suspend fun getPage(
            @Url url: String,
            @Header("Referer") referer: String = "",
            @Header("sec-fetch-dest") secFetchDest: String = "document"
        ): Document

        @Headers(USER_AGENT)
        @GET("https://v.vidxgo.co/seasons.php")
        suspend fun getVidxGoSeasons(
            @Query("imdb") imdbId: String,
            @Query("season") seasonNumber: Int,
            @Header("Referer") referer: String = "",
            @Header("sec-fetch-dest") secFetchDest: String = "empty"
        ): okhttp3.ResponseBody
    }

    private val service = Altadefinizione01Service.build(baseUrl)

    

    override suspend fun getHome(): List<Category> {
        val doc = service.getHome()

        val categories = mutableListOf<Category>()

        doc.selectFirst("div.slider")?.let { slider ->
            val title = slider.selectFirst(".slider-strip b")?.text()?.trim() ?: return@let
            val items = slider.select("#slider .boxgrid.caption, .boxgrid.caption").mapNotNull { parseGridItem(it) }
            if (items.isNotEmpty()) {
                categories.add(Category(name = title, list = items))
            }
        }

        doc.selectFirst("div.son_eklenen_head")?.let { head ->
            val kapsul = head.nextElementSibling()
            val container = when {
                kapsul?.id() == "son_eklenen_kapsul" -> kapsul
                else -> head.parent()?.selectFirst("#son_eklenen_kapsul")
            }
            val items = container?.select(".boxgrid.caption")?.mapNotNull { parseGridItem(it) } ?: emptyList()
            if (items.isNotEmpty()) {
                categories.add(Category(name = "Ultimi inseriti", list = items))
            }
        }

        return categories
    }

    private fun parseGridItem(el: Element): AppAdapter.Item? {
        val titleAnchor = el.selectFirst(".cover.boxcaption h2 a, h3 a, .boxcaption h2 a") ?: return null
        val title = titleAnchor.text().trim()
        val href = titleAnchor.attr("href").trim()
        val img = el.selectFirst("a > img")
        val imgUrlRaw = img?.attr("data-src") ?: ""
        val poster = normalizeUrl(imgUrlRaw)
        val isTv = el.selectFirst(".se_num") != null || el.selectFirst(".ml-cat a[href*='/serie-tv/']") != null

        return if (isTv) {
            TvShow(
                id = href,
                title = title,
                poster = poster,
                banner = null,
                rating = null,
            )
        } else {
            Movie(
                id = href,
                title = title,
                poster = poster,
                banner = null,
                rating = null
            )
        }
    }

    private fun normalizeUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> baseUrl.trimEnd('/') + url
            url.isBlank() -> ""
            else -> url
        }
    }

    private fun parseEpisodeTitleAndOverview(rawTitle: String): Pair<String?, String?> {
        val trimmed = rawTitle.trim()
        if (trimmed.contains(":")) {
            val title = trimmed.substringBefore(":").trim().ifBlank { null }
            val overview = trimmed.substringAfter(":").trim().ifBlank { null }
            return Pair(title, overview)
        }
        return Pair(trimmed.ifBlank { null }, null)
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            val home = service.getHome()
            val genreLinks = home
                .select(".widget-title:matches(^Categorie in Altadefinizione$)")
                .firstOrNull()?.parent()
                ?.select("#wtab1 .kategori_list li > a[href]") ?: emptyList()
            return genreLinks.mapNotNull { a ->
                val href = a.attr("href").trim()
                val text = a.text().trim()
                if (href.isBlank() || text.isBlank()) return@mapNotNull null
                Genre(
                    id = if (href.startsWith("http")) href else baseUrl + "/" + href.removePrefix("/")
                        .removePrefix(baseUrl.removeSuffix("/")),
                    name = text
                )
            }.sortedBy { (it as Genre).name }
        }

        val encoded = URLEncoder.encode(query, "UTF-8")
        val firstDoc = service.searchFirst(story = encoded)
        val hasPager = firstDoc.selectFirst("div.page_nav") != null
        if (page > 1 && !hasPager) return emptyList()

        val doc: Document = if (page <= 1) firstDoc else {
            val searchStart = page
            val resultFrom = (page - 1) * 50 + 1
            service.searchPaged(searchStart = searchStart, resultFrom = resultFrom, story = encoded)
        }

        return doc.select("#dle-content .boxgrid.caption")
            .mapNotNull { el -> parseGridItem(el) }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val doc = if (page > 1) service.getCinema(page) else service.getCinema()

        return doc.select("#dle-content .boxgrid.caption")
            .mapNotNull { el -> parseGridItem(el) as? Movie }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val doc = if (page > 1) service.getSerieTv(page) else service.getSerieTv()

        return doc.select("#dle-content .boxgrid.caption")
            .mapNotNull { el -> parseGridItem(el) as? TvShow }
    }

    override suspend fun getMovie(id: String): Movie {
        val doc = service.getPage(id)
        val title = (doc.selectFirst("#single .data h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("h1,h2,title")?.text()?.trim()
            ?: "")
            .replace("Streaming HD - Altadefinizione01", "")
            .trim()

        val tmdbMovie = TmdbUtils.getMovie(title, language = language)

        val poster = tmdbMovie?.poster ?: normalizeUrl(doc.selectFirst(".fix img")?.attr("data-src") ?: "")

        return Movie(
            id = id,
            title = title,
            overview = tmdbMovie?.overview ?: doc.selectFirst(".sbox .entry-content p")?.ownText()?.trim(),
            released = tmdbMovie?.released?.let { "${it.get(java.util.Calendar.YEAR)}-${it.get(java.util.Calendar.MONTH) + 1}-${it.get(java.util.Calendar.DAY_OF_MONTH)}" }?: doc.select("p.meta_dd:has(b.icon-clock)").text().replace(Regex("[^0-9]"), "").takeIf { it.isNotBlank() },
            runtime = tmdbMovie?.runtime ?: doc.select("p.meta_dd:has(b.icon-time)").text().replace(Regex("[^0-9]"), "").toIntOrNull(),
            trailer = tmdbMovie?.trailer ?: doc.selectFirst(".btn_trailer a[href]")?.attr("href")?.takeIf { it.contains("youtube", true) },
            rating = tmdbMovie?.rating ?: doc.selectFirst("div.imdb_r [itemprop=ratingValue]")?.text()?.trim()?.toDoubleOrNull(),
            poster = poster,
            quality = doc.select("p.meta_dd:has(b.icon-playback-play)").text().replace("Qualita", "").trim().takeIf { it.isNotBlank() },
            banner = tmdbMovie?.banner,
            imdbId = tmdbMovie?.imdbId,
            genres = tmdbMovie?.genres ?: doc.select("p.meta_dd b[title=Genere]").firstOrNull()?.parent()?.select("a")?.map { Genre(it.attr("href"), it.text().trim()) } ?: emptyList(),
            cast = doc.select("p.meta_dd.limpiar:has(b.icon-male) a[href]").map { el ->
                val href = el.attr("href").trim()
                val name = el.text().trim()
                val tmdbPerson = tmdbMovie?.cast?.find { it.name.equals(name, ignoreCase = true) }
                People(id = href, name = name, image = tmdbPerson?.image)
            }
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val doc = service.getPage(id)
        val title = (doc.selectFirst("#single .data h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("h1,h2,title")?.text()?.trim()
            ?: "")
            .replace("Streaming Gratis - Serie TV - Altadefinizione01", "")
            .replace(" - Serie TV", "")
            .trim()

        val tmdbTvShow = TmdbUtils.getTvShow(title, language = language)

        val poster = tmdbTvShow?.poster ?: normalizeUrl(doc.selectFirst(".fix img")?.attr("data-src") ?: "")

        val seasons = mutableListOf<Season>()
        doc.select("#tt_holder .tt_season ul li a[data-toggle=tab]").forEach { a ->
            val seasonId = a.attr("href").removePrefix("#")
            val seasonNumber = a.text().trim().toIntOrNull() ?: 0
            val episodes = mutableListOf<Episode>()
            val seasonPane = doc.selectFirst("#${seasonId}")
            seasonPane?.select("ul > li > a[allowfullscreen][data-link]")?.forEach { ep ->
                val epNum = ep.attr("data-num").substringAfter('x').toIntOrNull()
                    ?: ep.text().trim().toIntOrNull() ?: 0
                val (epTitle, epOverview) = parseEpisodeTitleAndOverview(ep.attr("data-title"))
                episodes.add(
                    Episode(
                        id = "$id#s${seasonNumber}e$epNum",
                        number = epNum,
                        title = epTitle,
                        poster = null,
                        overview = epOverview
                    )
                )
            }
            seasons.add(
                Season(
                    id = "$id#season-$seasonNumber",
                    number = seasonNumber,
                    episodes = episodes,
                    poster = tmdbTvShow?.seasons?.find { it.number == seasonNumber }?.poster
                )
            )
        }

        if (seasons.isEmpty()) {
            val vidxgoIframe = doc.selectFirst("iframe#vidxgo-player")
            if (vidxgoIframe != null) {
                val scripts = doc.select("script")
                val imdbId = scripts.firstNotNullOfOrNull { script ->
                    Regex("var\\s+imdb\\s*=\\s*['\"]tt(\\d+)['\"]").find(script.html())?.groupValues?.get(1)
                }
                if (imdbId != null) {
                    try {
                        val uri = Uri.parse(id)
                        val referer = "${uri.scheme}://${uri.host}/"
                        val vidxgoDoc = service.getPage("https://v.vidxgo.co/$imdbId", referer, "iframe")
                        val seasonTabs = vidxgoDoc.select(".ep-season-tab")
                        if (seasonTabs.isNotEmpty()) {
                            seasonTabs.forEach { tab ->
                                val sNum = tab.attr("data-season").toIntOrNull() ?: return@forEach
                                seasons.add(
                                    Season(
                                        id = "$id#season-$sNum",
                                        number = sNum,
                                        episodes = emptyList(),
                                        poster = tmdbTvShow?.seasons?.find { it.number == sNum }?.poster
                                    )
                                )
                            }
                            seasons.sortBy { it.number }
                        } else {
                            val episodesList = vidxgoDoc.select("#episodesList a.ep-item")
                            val groupedEpisodes = episodesList.mapNotNull { a ->
                                val href = a.attr("href")
                                val parts = href.trim('/').split('/')
                                if (parts.size < 3) return@mapNotNull null
                                val s = parts[1].toIntOrNull() ?: return@mapNotNull null
                                val e = parts[2].toIntOrNull() ?: return@mapNotNull null
                                val epName = a.selectFirst(".ep-name")?.text()?.trim()
                                val epPlot = a.selectFirst(".ep-plot")?.text()?.trim()
                                val epPoster = a.selectFirst("img.ep-thumb")?.attr("src")
                                
                                Triple(s, e, Episode(
                                    id = "$id#s${s}e$e",
                                    number = e,
                                    title = epName,
                                    overview = epPlot,
                                    poster = epPoster
                                ))
                            }.groupBy { it.first }
                            
                            groupedEpisodes.forEach { (sNum, eps) ->
                                seasons.add(
                                    Season(
                                        id = "$id#season-$sNum",
                                        number = sNum,
                                        episodes = eps.map { it.third }.sortedBy { it.number },
                                        poster = tmdbTvShow?.seasons?.find { it.number == sNum }?.poster
                                    )
                                )
                            }
                            seasons.sortBy { it.number }
                        }
                    } catch (e: Exception) {
                        Log.e("Altadefinizione01", "Error fetching VidxGo episodes: ${e.message}")
                    }
                }
            }
        }

        return TvShow(
            id = id,
            title = title,
            overview = tmdbTvShow?.overview ?: doc.selectFirst(".sbox .entry-content p")?.ownText()?.trim(),
            released = tmdbTvShow?.released?.let { "${it.get(java.util.Calendar.YEAR)}-${it.get(java.util.Calendar.MONTH) + 1}-${it.get(java.util.Calendar.DAY_OF_MONTH)}" },
            runtime = tmdbTvShow?.runtime,
            trailer = tmdbTvShow?.trailer ?: doc.selectFirst(".btn_trailer a[href]")?.attr("href")?.takeIf { it.contains("youtube", true) },
            rating = tmdbTvShow?.rating ?: doc.selectFirst("div.imdb_r [itemprop=ratingValue]")?.text()?.trim()?.toDoubleOrNull(),
            poster = poster,
            banner = tmdbTvShow?.banner,
            imdbId = tmdbTvShow?.imdbId,
            seasons = seasons,
            genres = tmdbTvShow?.genres ?: doc.select("p.meta_dd:has(b.icon-medal) a[href]").map { Genre(it.attr("href"), it.text().trim()) } ?: emptyList(),
            cast = doc.select("p.meta_dd.limpiar:has(b.icon-male) a[href]").map { el ->
                val href = el.attr("href").trim()
                val name = el.text().trim()
                val tmdbPerson = tmdbTvShow?.cast?.find { it.name.equals(name, ignoreCase = true) }
                People(id = href, name = name, image = tmdbPerson?.image)
            }
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val showUrl = seasonId.substringBefore("#")
        val seasonNumber = seasonId.substringAfter("#season-").toIntOrNull() ?: 0
        val doc = try { service.getPage(showUrl) } catch (e: Exception) { null } ?: return emptyList()

        val paneId = "season-$seasonNumber"
        val seasonPane = doc.selectFirst("#${paneId}")
        val episodes = seasonPane?.select("ul > li > a[allowfullscreen][data-link]")?.map { ep ->
            val epNum = ep.attr("data-num").substringAfter('x').toIntOrNull()
                ?: ep.text().trim().toIntOrNull() ?: 0
            val (epTitle, epOverview) = parseEpisodeTitleAndOverview(ep.attr("data-title"))
            
            val episodeMirrors = ep.parent()?.select("a[data-link]")?.filter { link ->
                link.text().contains("Dropload", true)
            } ?: emptyList()
            
            val episodePoster = episodeMirrors.firstOrNull()?.attr("data-link")?.let { link ->
                val droploadId = link.substringAfter("/e/")
                "https://img.dropcdn.io/$droploadId.jpg"
            }
            
            Episode(
                id = "$showUrl#s${seasonNumber}e$epNum",
                number = epNum,
                title = epTitle,
                poster = episodePoster,
                overview = epOverview
            )
        }

        if (episodes.isNullOrEmpty()) {
            val vidxgoIframe = doc.selectFirst("iframe#vidxgo-player")
            if (vidxgoIframe != null) {
                val scripts = doc.select("script")
                val imdbId = scripts.firstNotNullOfOrNull { script ->
                    Regex("var\\s+imdb\\s*=\\s*['\"]tt(\\d+)['\"]").find(script.html())?.groupValues?.get(1)
                }
                if (imdbId != null) {
                    try {
                        val uri = Uri.parse(showUrl)
                        val referer = "${uri.scheme}://${uri.host}/"
                        val responseBody = service.getVidxGoSeasons(imdbId, seasonNumber, referer)
                        val json = org.json.JSONObject(responseBody.string())
                        if (json.optInt("ok") == 1) {
                            val episodesArray = json.getJSONArray("episodes")
                            val resultList = mutableListOf<Episode>()
                            for (i in 0 until episodesArray.length()) {
                                val ep = episodesArray.getJSONObject(i)
                                val eNum = ep.getInt("number")
                                resultList.add(Episode(
                                    id = "$showUrl#s${seasonNumber}e${eNum}",
                                    number = eNum,
                                    title = ep.optString("name"),
                                    overview = ep.optString("overview"),
                                    poster = ep.optString("still")
                                ))
                            }
                            return resultList
                        }
                        
                        // Fallback to scraping the page if JSON fails or season is 1
                        val vidxgoDoc = service.getPage("https://v.vidxgo.co/$imdbId", referer, "iframe")
                        return vidxgoDoc.select("#episodesList a.ep-item").mapNotNull { a ->
                            val href = a.attr("href")
                            val parts = href.trim('/').split('/')
                            if (parts.size < 3) return@mapNotNull null
                            val s = parts[1].toIntOrNull() ?: return@mapNotNull null
                            val e = parts[2].toIntOrNull() ?: return@mapNotNull null
                            if (s != seasonNumber) return@mapNotNull null
                            
                            val epName = a.selectFirst(".ep-name")?.text()?.trim()
                            val epPlot = a.selectFirst(".ep-plot")?.text()?.trim()
                            val epPoster = a.selectFirst("img.ep-thumb")?.attr("src")
                            
                            Episode(
                                id = "$showUrl#s${s}e${e}",
                                number = e,
                                title = epName,
                                overview = epPlot,
                                poster = epPoster
                            )
                        }.sortedBy { it.number }
                    } catch (e: Exception) {
                        Log.e("Altadefinizione01", "Error fetching VidxGo episodes for season $seasonNumber: ${e.message}")
                    }
                }
            }
        }
        
        return episodes ?: emptyList()
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = if (page <= 1) id else id.trimEnd('/') + "/page/$page/"
        val doc = service.getPage(url)

        val name = ""

        val shows = doc.select("#dle-content .boxgrid.caption")
            .mapNotNull { el -> parseGridItem(el) as? com.streamflixreborn.streamflix.models.Show }

        return Genre(id = id, name = name, shows = shows)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val baseDoc = service.getPage(id)
        val hasPager = baseDoc.selectFirst("div.page_nav") != null
        if (page > 1 && !hasPager) {
            return People(id = id, name = "", filmography = emptyList())
        }
        val doc = if (page <= 1) baseDoc else run {
            val base = id.trimEnd('/')
            val pagedBase = if (base.contains("/xfsearch/attori/")) base.replace("/xfsearch/attori/", "/find/") else base
            service.getPage("$pagedBase/page/$page/")
        }

        val name = ""

        val filmography = doc.select("#dle-content .boxgrid.caption")
            .mapNotNull { el -> parseGridItem(el) as? com.streamflixreborn.streamflix.models.Show }

        return People(
            id = id,
            name = name,
            filmography = filmography
        )
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        if (videoType is Video.Type.Episode) {
            return try {
                val showUrl = id.substringBefore('#')
                val sPart = id.substringAfter('#', "") // format s{season}e{ep}
                val seasonNum = sPart.substringAfter('s').substringBefore('e').toIntOrNull() ?: 0
                val epNum = sPart.substringAfter('e').toIntOrNull() ?: 0
                val doc = service.getPage(showUrl)
                val results = mutableListOf<Video.Server>()

                try {
                    val pane = doc.selectFirst("#season-$seasonNum")
                    val epAnchor = pane?.select("ul > li > a[allowfullscreen][data-link]")
                        ?.firstOrNull { a ->
                            val numAttr = a.attr("data-num")
                            val n = numAttr.substringAfter('x').toIntOrNull() ?: a.text().trim().toIntOrNull() ?: -1
                            n == epNum
                        }
                    
                    val mirrors = epAnchor?.parent()?.select(".mirrors a[data-link]") ?: emptyList()
                    mirrors
                        .filterNot { m -> m.text().contains("4K", true) }
                        .mapNotNull { m ->
                            val link = m.attr("data-link").trim()
                            if (link.isBlank()) return@mapNotNull null
                            val normalized = when {
                                link.startsWith("//") -> "https:$link"
                                link.startsWith("http") -> link
                                else -> link
                            }
                            val name = m.text().trim().ifBlank { "Server" }
                            Video.Server(id = normalized, name = name, src = normalized)
                        }.forEach { results.add(it) }
                } catch (_: Exception) {}

                val vidxgoIframe = doc.selectFirst("iframe#vidxgo-player")
                if (vidxgoIframe != null) {
                    val scripts = doc.select("script")
                    val imdbId = scripts.firstNotNullOfOrNull { script ->
                        Regex("var\\s+imdb\\s*=\\s*['\"]tt(\\d+)['\"]").find(script.html())?.groupValues?.get(1)
                    }
                    if (imdbId != null) {
                        val vidxgoUrl = "https://v.vidxgo.co/t/$imdbId/$seasonNum/$epNum"
                        results.add(
                            Video.Server(
                                id = vidxgoUrl,
                                name = "VidxGo",
                                src = vidxgoUrl
                            )
                        )
                    }
                }
                results
            } catch (_: Exception) { emptyList() }
        }

        val doc = try { service.getPage(id) } catch (e: Exception) { null } ?: throw Exception("Failed to load page")
        
        val guardahdIframe = doc.selectFirst("iframe[src*='guardahd.stream']")
        if (guardahdIframe != null) {
            val iframeSrc = guardahdIframe.attr("src")
            val embedUrl = normalizeUrl(iframeSrc)
            try {
                val embedDoc = service.getPage(embedUrl)
                val servers = embedDoc.select("ul._player-mirrors li[data-link]")
                    .filterNot { li -> li.hasClass("fullhd") || li.text().contains("4K", true) }
                    .mapNotNull { li ->
                        val dataLink = li.attr("data-link").trim()
                        if (dataLink.isBlank()) return@mapNotNull null
                        val normalized = when {
                            dataLink.startsWith("//") -> "https:$dataLink"
                            dataLink.startsWith("http") -> dataLink
                            else -> "https://$dataLink"
                        }
                        val nameText = li.ownText().ifBlank { li.text() }.trim()
                        val name = nameText.ifBlank { "Server" }
                        Video.Server(id = normalized, name = name, src = normalized)
                    }
                    .filter { it.src.isNotBlank() }
                if (servers.isNotEmpty()) return servers
            } catch (e: Exception) {
                Log.e("Altadefinizione01", "Error fetching guardahd servers: ${e.message}")
            }
        }

        val vidxgoIframe = doc.selectFirst("iframe#vidxgo-player-film")
        if (vidxgoIframe != null) {
            val scripts = doc.select("script")
            val imdbId = scripts.firstNotNullOfOrNull { script ->
                Regex("var\\s+imdb\\s*=\\s*['\"]tt(\\d+)['\"]").find(script.html())?.groupValues?.get(1)
            }
            if (imdbId != null) {
                val vidxgoUrl = "https://v.vidxgo.co/$imdbId"
                return listOf(
                    Video.Server(
                        id = vidxgoUrl,
                        name = "VidxGo",
                        src = vidxgoUrl
                    )
                )
            }
        }

        throw Exception("Embed iframe not found")
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.src)
    }
}
