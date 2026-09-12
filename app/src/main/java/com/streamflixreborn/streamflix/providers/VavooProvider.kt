package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.VavooTls
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class VavooProvider(override val language: String) : IptvProvider {

    companion object {
        private const val TAG = "VavooProvider"
        private const val CACHE_DURATION = 30 * 60 * 1000L
        private const val POSTER = "https://www.clipartmax.com/png/full/46-463028_television-images-clip-art.png"

        // Only the languages supported by the app
        private val LANG_CONFIG = mapOf(
            "de" to Triple("de", "DE", listOf("Germany", "GERMANY")),
            "it" to Triple("it", "IT", listOf("Italy")),
            "fr" to Triple("fr", "FR", listOf("France", "France Sport")),
            "es" to Triple("es", "ES", listOf("Spain")),
            "pl" to Triple("pl", "PL", listOf("Poland")),
            "en" to Triple("en", "GB", listOf("United Kingdom"))
        )

        private const val PLAYBACK_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/116.0.0.0 Safari/537.36"

        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        // Kurze Timeouts: tote Vavoo-Quellen sollen automatisch
        // verworfen werden, ohne den Nutzer lange warten zu lassen.
        private val probeClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override val baseUrl: String
        get() = UserPreferences.vavooDomain

    private val CATALOG_URL: String
        get() = "$baseUrl/mediahubmx-catalog.json"

    private val RESOLVE_URL: String
        get() = "$baseUrl/mediahubmx-resolve.json"

    // Cache for home categories per language to avoid instant re-fetching
    private val homeCache = mutableMapOf<String, List<VavooChannel>>()
    private val cacheTimestamps = mutableMapOf<String, Long>()
    
    // Cache to temporarily map channel IDs to their names when found via search/genres
    private val searchCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    data class VavooChannel(
        val id: String,
        val name: String,
        val url: String
    )

    // Config for this instance
    private val config = LANG_CONFIG[language] ?: LANG_CONFIG["de"]!!

    override val name: String = "Vavoo ${config.third.first()} Live TV"
    override val logo: String
        get() = "$baseUrl/assets/favicon-Djqjt9PL.ico"

    private val primaryGroups: List<String> = config.third

    private fun fetchChannels(search: String, group: String, cursor: Int? = null): Pair<List<VavooChannel>, Int?> {
        val filterObj = JSONObject().apply {
            put("group", group)
        }
        val body = JSONObject().apply {
            put("language", config.first)
            put("region", config.second)
            put("catalogId", "iptv")
            put("id", "")
            put("adult", false)
            put("search", search)
            put("sort", "name")
            put("filter", filterObj)
            if (cursor != null) put("cursor", cursor) else put("cursor", JSONObject.NULL)
        }.toString()

        return try {
            val request = Request.Builder()
                .url(CATALOG_URL)
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Origin", baseUrl)
                .header("Referer", "$baseUrl/")
                .build()

            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: return Pair(emptyList(), null))
            val items = json.optJSONArray("items") ?: return Pair(emptyList(), null)
            val nextCursor = if (json.isNull("nextCursor")) null else json.optInt("nextCursor")

            val channels = (0 until items.length()).mapNotNull { i ->
                val item = items.getJSONObject(i)
                val url = item.optString("url").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val name = item.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                VavooChannel(
                    id = item.optJSONObject("ids")?.optString("id") ?: url,
                    name = name,
                    url = url
                )
            }
            Pair(channels, nextCursor)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching channels (search='$search', group='$group'): ${e.message}")
            Pair(emptyList(), null)
        }
    }

    private fun loadHomeGroupChannels(group: String): List<VavooChannel> {
        val now = System.currentTimeMillis()
        val cached = homeCache[group]
        if (cached != null && (now - (cacheTimestamps[group] ?: 0)) < CACHE_DURATION) {
            return cached
        }
        val (channels, _) = fetchChannels("", group)
        if (channels.isNotEmpty()) {
            homeCache[group] = channels
            cacheTimestamps[group] = now
        }
        return channels
    }

    data class ResolvedChannel(val name: String, val url: String)

    private fun resolveChannel(vavooUrl: String): ResolvedChannel? {
        val body = JSONObject().apply {
            put("language", config.first)
            put("region", config.second)
            put("url", vavooUrl)
        }.toString()
        return try {
            val request = Request.Builder()
                .url(RESOLVE_URL)
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Origin", baseUrl)
                .header("Referer", "$baseUrl/")
                .build()
            val response = client.newCall(request).execute()
            val jsonArray = org.json.JSONArray(response.body?.string() ?: return null)
            if (jsonArray.length() > 0) {
                val obj = jsonArray.getJSONObject(0)
                val url = obj.optString("url").takeIf { it.isNotEmpty() } ?: return null
                val name = obj.optString("name")
                ResolvedChannel(name = name, url = url)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Resolve error for $vavooUrl: ${e.message}")
            null
        }
    }

    override suspend fun getHome(): List<Category> {
        return primaryGroups.map { group ->
            val channels = loadHomeGroupChannels(group)
            Category(
                name = "Vavoo $group Live TV",
                list = channels.take(300).map { ch ->
                    TvShow(id = ch.id, title = ch.name, poster = POSTER, banner = POSTER)
                }
            )
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        val cursor = if (page > 1) (page - 1) * 300 else null
        val channels = primaryGroups.flatMap { group -> fetchChannels(query, group, cursor).first }
        return channels.map { ch ->
            searchCache[ch.id] = ch.name
            TvShow(id = ch.id, title = ch.name, poster = POSTER)
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val cursor = if (page > 1) (page - 1) * 300 else null
        val channels = primaryGroups.flatMap { group -> fetchChannels("", group, cursor).first }
        return channels.map { ch ->
            searchCache[ch.id] = ch.name
            TvShow(id = ch.id, title = ch.name, poster = POSTER)
        }
    }

    override suspend fun getMovie(id: String): Movie = Movie(id = id, title = "Live", poster = "")

    override suspend fun getTvShow(id: String): TvShow {
        // 1. Try in-memory caches first (fast path)
        var cachedName: String? = null
        for (group in primaryGroups) {
            val found = homeCache[group]?.find { it.id == id }
            if (found != null) {
                cachedName = found.name
                break
            }
        }
        if (cachedName == null) {
            cachedName = searchCache[id]
        }

        // 2. If not found (e.g. after app restart), call the resolve API:
        //    the response contains the real channel name on the "name" field
        val title = cachedName ?: run {
            val vavooUrl = "$baseUrl/vavoo-iptv/play/$id"
            val resolved = resolveChannel(vavooUrl)
            if (resolved != null && resolved.name.isNotEmpty()) {
                searchCache[id] = resolved.name
                resolved.name
            } else {
                id
            }
        }

        return TvShow(
            id = id,
            title = title,
            poster = POSTER,
            banner = POSTER,
            overview = "Vavoo Live IPTV Stream",
            seasons = listOf(Season(id = id, number = 1, title = "Watch"))
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        return listOf(Episode(id = seasonId, number = 1, title = "Watch Now", season = null))
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val cursor = if (page > 1) (page - 1) * 300 else null
        val channels = primaryGroups.flatMap { group -> fetchChannels(id, group, cursor).first }
        val tvShows = channels.map { ch ->
            searchCache[ch.id] = ch.name
            TvShow(id = ch.id, title = ch.name, poster = POSTER)
        }
        return Genre(id = id, name = id, shows = tvShows)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = "Vavoo", image = logo, biography = "", birthday = "", deathday = "", placeOfBirth = "")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return listOf(Video.Server(id = id, name = "Vavoo"))
    }

    private fun cachedChannelName(id: String): String? {
        searchCache[id]?.let { return it }

        for (group in primaryGroups) {
            homeCache[group]
                ?.firstOrNull { it.id == id }
                ?.name
                ?.let { return it }
        }

        return null
    }

    /**
     * Entfernt Vavoo-Quellen-/Qualitätszusätze, damit z.B.
     *
     * 3SAT .b
     * 3SAT .c
     * 3SAT HD .b
     *
     * als Varianten desselben Senders erkannt werden.
     */
    private fun channelBaseName(name: String): String {
        var result = name.trim()

        repeat(3) {
            result = result
                .replace(
                    Regex(
                        """\s*\.(?:b|c|s)\s*$""",
                        RegexOption.IGNORE_CASE
                    ),
                    ""
                )
                .replace(
                    Regex(
                        """\s*\(backup\)\s*$""",
                        RegexOption.IGNORE_CASE
                    ),
                    ""
                )
                .replace(
                    Regex(
                        """\s+(?:HD\+?|FHD|UHD|RAW|SD)\s*$""",
                        RegexOption.IGNORE_CASE
                    ),
                    ""
                )
                .trim()
        }

        return result
    }

    private fun canonicalChannelName(name: String): String =
        channelBaseName(name)
            .uppercase()
            .replace(Regex("""[^A-Z0-9]+"""), "")

    private fun playbackHeaders(): Map<String, String> =
        mapOf(
            "User-Agent" to PLAYBACK_USER_AGENT,
            "Referer" to "${baseUrl.trimEnd('/')}/",
            "Origin" to baseUrl.trimEnd('/')
        )

    /**
     * Prüft auf demselben Android-Gerät, auf dem danach auch
     * ExoPlayer läuft, ob die aufgelöste HLS-Quelle wirklich
     * erreichbar ist.
     *
     * Abgelaufene TLS-Zertifikate werden NICHT akzeptiert.
     */
    private fun probeResolvedStream(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", PLAYBACK_USER_AGENT)
                .header(
                    "Referer",
                    "${baseUrl.trimEnd('/')}/"
                )
                .header(
                    "Origin",
                    baseUrl.trimEnd('/')
                )
                .header("Range", "bytes=0-4095")
                .build()

            val probeHost =
                runCatching {
                    java.net.URI(url).host
                }.getOrNull()

            val effectiveProbeClient =
                if (!probeHost.isNullOrBlank()) {
                    VavooTls
                        .relaxForHost(
                            probeClient.newBuilder(),
                            probeHost
                        )
                        .build()
                } else {
                    probeClient
                }

            effectiveProbeClient
                .newCall(request)
                .execute()
                .use { response ->
                if (!response.isSuccessful) {
                    Log.w(
                        TAG,
                        "Probe HTTP ${response.code}: $url"
                    )
                    return@use false
                }

                val contentType =
                    response.header("Content-Type")
                        .orEmpty()
                        .lowercase()

                val body =
                    response.body
                        ?.string()
                        .orEmpty()
                        .take(4096)

                val hls =
                    body.contains("#EXTM3U") ||
                        contentType.contains(
                            "mpegurl"
                        ) ||
                        contentType.contains(
                            "application/vnd.apple"
                        )

                Log.d(
                    TAG,
                    "Probe ${if (hls) "OK" else "INVALID"} " +
                        "HTTP=${response.code} " +
                        "type=$contentType"
                )

                hls
            }
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Probe failed: ${e.javaClass.simpleName}: ${e.message}"
            )

            false
        }
    }

    /**
     * Liefert zuerst die angeklickte Quelle und anschließend
     * weitere Vavoo-Varianten desselben Senders.
     */
    private fun playbackCandidates(
        id: String
    ): List<VavooChannel> {

        val currentUrl =
            if (id.startsWith("http")) {
                id
            } else {
                "$baseUrl/vavoo-iptv/play/$id"
            }

        val currentName =
            cachedChannelName(id)
                ?: return listOf(
                    VavooChannel(
                        id = id,
                        name = id,
                        url = currentUrl
                    )
                )

        val wanted =
            canonicalChannelName(currentName)

        val query =
            channelBaseName(currentName)

        val alternatives =
            primaryGroups
                .flatMap { group ->
                    fetchChannels(
                        search = query,
                        group = group
                    ).first
                }
                .filter {
                    canonicalChannelName(
                        it.name
                    ) == wanted
                }

        val current =
            VavooChannel(
                id = id,
                name = currentName,
                url = currentUrl
            )

        return (listOf(current) + alternatives)
            .distinctBy { it.url }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val candidates =
            playbackCandidates(server.id)

        Log.d(
            TAG,
            "[$language] Found ${candidates.size} " +
                "candidate(s) for ${server.id}"
        )

        candidates.forEachIndexed { index, candidate ->

            Log.d(
                TAG,
                "[$language] Candidate ${index + 1}/" +
                    "${candidates.size}: ${candidate.name} " +
                    "(${candidate.url})"
            )

            val resolved =
                resolveChannel(candidate.url)

            if (resolved == null) {
                Log.w(
                    TAG,
                    "[$language] Resolve failed: " +
                        candidate.name
                )

                return@forEachIndexed
            }

            Log.d(
                TAG,
                "[$language] Resolved ${candidate.name}: " +
                    resolved.url
            )

            if (!probeResolvedStream(resolved.url)) {
                Log.w(
                    TAG,
                    "[$language] Source unusable, " +
                        "trying next: ${candidate.name}"
                )

                return@forEachIndexed
            }

            Log.d(
                TAG,
                "[$language] PLAYABLE source selected: " +
                    candidate.name
            )

            return Video(
                source = resolved.url,
                subtitles = emptyList(),
                headers = playbackHeaders()
            )
        }

        throw Exception(
            "Vavoo: no playable source found for ${server.id}"
        )
    }
}

