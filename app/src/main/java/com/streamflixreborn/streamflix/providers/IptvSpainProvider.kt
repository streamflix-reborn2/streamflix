package com.streamflixreborn.streamflix.providers

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.*
import okhttp3.*
import java.util.concurrent.TimeUnit

object IptvSpainProvider : IptvProvider {

    override val name = "IPTV Spain"
    override val baseUrl = "https://iptv-org.github.io/iptv/languages/spa.m3u"
    override val logo = "https://i.ibb.co/SD7860Mv/IPTV-Spain-Canales.jpg"
    override val language = "es"

    private const val TAG = "IptvSpainProvider"

    private val OFFICIAL_CATEGORIES = listOf(
        "Animation", "Auto", "Business", "Classic", "Comedy", "Cooking", "Culture",
        "Documentary", "Education", "Entertainment", "Family", "General", "Interactive",
        "Kids", "Legislative", "Lifestyle", "Movies", "Music", "News", "Outdoor",
        "Public", "Relax", "Religious", "Science", "Series", "Shop", "Sports",
        "Travel", "Weather", "Undefined"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            private val cookieStore = mutableMapOf<String, List<Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: listOf()
            }
        })
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .build()

    private var cachedChannels: List<M3UChannel>? = null
    private var lastFetchTime: Long = 0
    private const val CACHE_DURATION = 30 * 60 * 1000

    data class M3UChannel(
        val name: String,
        val url: String,
        val logo: String?,
        val group: String?,
        val userAgent: String? = null,
        val referrer: String? = null
    )

    private fun createId(channel: M3UChannel): String {
        val payload = encodeM3uPlaybackIdentity(
            M3uPlaybackIdentity(
                provider = this.name,
                url = channel.url,
                name = channel.name,
                logo = channel.logo,
                userAgent = channel.userAgent,
                referrer = channel.referrer,
            ),
        )
        return Base64.encodeToString(payload.toByteArray(), Base64.NO_WRAP)
    }

    private fun decodeIdentity(id: String): M3uPlaybackIdentity =
        requireM3uPlaybackIdentityFromBase64(
            encoded = id,
            expectedProvider = name,
            decodeBase64 = { Base64.decode(it, Base64.DEFAULT) },
            encodeBase64 = { Base64.encodeToString(it, Base64.NO_WRAP) },
        )

    private fun decodeId(id: String): Triple<String, String, String> {
        if (id == "creador-info" || id == "apoyo-nando") {
            return Triple(id, "", "")
        }
        val identity = decodeIdentity(id)
        return Triple(identity.url, identity.name, identity.logo.orEmpty())
    }

    private fun getMetadataFromId(id: String): Map<String, String?> {
        val identity = decodeIdentity(id)
        return mapOf(
            "ua" to identity.userAgent,
            "referer" to identity.referrer,
        )
    }

    private fun getAllChannels(): List<M3UChannel> {
        val now = System.currentTimeMillis()
        if (cachedChannels != null && (now - lastFetchTime) < CACHE_DURATION) return cachedChannels!!

        return try {
            val request = Request.Builder().url(baseUrl).build()
            val body = client.newCall(request).execute().body?.string() ?: return emptyList()
            val channels = parseM3U(body)
            cachedChannels = channels
            lastFetchTime = now
            channels
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error M3U: ${e.message}")
            cachedChannels ?: emptyList()
        }
    }

    override suspend fun getHome(): List<Category> {
        val channels = getAllChannels()
        val homeGroups = listOf("News", "Sports", "Movies", "Entertainment", "Music", "General")

        val categories = mutableListOf<Category>()

        // 1. Procesamos categorías de canales primero
        val channelCategories = channels
            .filter { it.group != null && homeGroups.any { target -> it.group!!.contains(target, ignoreCase = true) } }
            .groupBy { channel ->
                homeGroups.find { channel.group!!.contains(it, ignoreCase = true) } ?: "General"
            }
            .map { (groupName, channelList) ->
                Category(
                    name = groupName,
                    list = channelList.distinctBy { it.name }.take(25).map { channel ->
                        TvShow(
                            id = createId(channel),
                            title = channel.name,
                            poster = channel.logo ?: "",
                            banner = channel.logo ?: ""
                        )
                    }
                )
            }.sortedBy { it.name }

        categories.addAll(channelCategories)

        // 2. AÑADIMOS SOPORTE AL FINAL (PARTE ULTIMA)
        categories.add(
            Category(
                name = "Soporte y Ayuda",
                list = listOf(
                    getInfoItem("creador-info"),
                    getInfoItem("apoyo-nando")
                )
            )
        )

        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        val allChannels = getAllChannels()
        val results = mutableListOf<AppAdapter.Item>()

        val matchingGroups = OFFICIAL_CATEGORIES.filter { it.contains(query, ignoreCase = true) }.sorted()
        matchingGroups.forEach { results.add(Genre(id = it, name = "📂 Categoría: $it", shows = emptyList())) }

        val channelResults = allChannels.filter {
            it.name.contains(query, ignoreCase = true)
        }.distinctBy { it.name }.take(80).map { channel ->
            TvShow(id = createId(channel), title = channel.name, poster = channel.logo ?: "")
        }

        results.addAll(channelResults)
        return results
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val groupChannels = getAllChannels().filter {
            it.group?.contains(id, ignoreCase = true) ?: false
        }.distinctBy { it.name }

        val pageSize = 40
        val start = (page - 1) * pageSize
        val pagedList = groupChannels.drop(start).take(pageSize).map { channel ->
            TvShow(id = createId(channel), title = channel.name, poster = channel.logo ?: "")
        }

        return Genre(id = id, name = id, shows = pagedList)
    }

    override suspend fun getPeople(
        id: String,
        page: Int
    ): People {
        TODO("Not yet implemented")
    }

    override suspend fun getTvShow(id: String): TvShow {
        if (id == "creador-info" || id == "apoyo-nando") {
            return getInfoItem(id)
        }

        val (_, name, logo) = decodeId(id)
        return TvShow(
            id = id,
            title = name,
            poster = logo,
            banner = logo,
            overview = "Canal en Español: $name\nFuente: IPTV-Org",
            seasons = listOf(Season(id = id, number = 1, title = "Señal en Vivo"))
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        if (seasonId == "creador-info" || seasonId == "apoyo-nando") return emptyList()
        return listOf(Episode(id = seasonId, number = 1, title = "Reproducir Señal", season = null))
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        if (id == "creador-info" || id == "apoyo-nando") return emptyList()
        return listOf(Video.Server(id = id, name = "Stream Directo"))
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val (url, _, _) = decodeId(server.id)
        val meta = getMetadataFromId(server.id)


        return m3uPlaybackVideo(
            source = url,
            userAgent = meta["ua"],
            referrer = meta["referer"],
        )
    }

    private fun getInfoItem(id: String): TvShow {
        val isReport = id == "creador-info"
        return TvShow(
            id = id,
            title = if (isReport) "Reportar problemas" else "Apoya al Proveedor",
            poster = if (isReport) "https://i.ibb.co/dsknGBHT/Imagen-de-Whats-App-2025-09-06-a-las-19-00-50-e8e5bcaa.jpg" else "https://i.ibb.co/B5gKLkqS/nuevo-formato-2-K-202604112205.jpg",
            banner = if (isReport) "https://i.ibb.co/dsknGBHT/Imagen-de-Whats-App-2025-09-06-a-las-19-00-50-e8e5bcaa.jpg" else "https://i.ibb.co/B5gKLkqS/nuevo-formato-2-K-202604112205.jpg",
            overview = if (isReport) {
                "Si algún canal no funciona o encuentras errores en el proveedor, por favor repórtalo en nuestro grupo oficial de Telegram."
            } else {
                "Si te gusta nuestro contenido y quieres ayudarnos a mantener los servidores activos, puedes realizar una donación voluntaria. ¡Gracias por tu apoyo!"
            },
            seasons = emptyList()
        )
    }

    private fun parseM3U(m3uRaw: String): List<M3UChannel> {
        val channels = mutableListOf<M3UChannel>()
        val lines = m3uRaw.lines()

        var curName = ""
        var curLogo = ""
        var curGroup = ""
        var curUA: String? = null
        var curRef: String? = null

        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("#EXTINF")) {
                curName = t.substringAfterLast(",").trim()
                curLogo = Regex("""tvg-logo="([^"]+)"""").find(t)?.groupValues?.get(1) ?: ""
                curGroup = Regex("""group-title="([^"]+)"""").find(t)?.groupValues?.get(1) ?: ""
                curUA = Regex("""http-user-agent="([^"]+)"""").find(t)?.groupValues?.get(1)
                curRef = Regex("""http-referrer="([^"]+)"""").find(t)?.groupValues?.get(1)
            } else if (t.startsWith("#EXTVLCOPT:")) {
                if (t.contains("http-user-agent=")) curUA = t.substringAfter("http-user-agent=").trim()
                if (t.contains("http-referrer=")) curRef = t.substringAfter("http-referrer=").trim()
            } else if (t.startsWith("http")) {
                if (curName.isNotEmpty()) {
                    channels.add(M3UChannel(curName, t, curLogo, curGroup, curUA, curRef))
                    curName = ""; curLogo = ""; curGroup = ""; curUA = null; curRef = null
                }
            }
        }
        return channels.filter { channel ->
            isValidM3uPlaybackIdentity(
                url = channel.url,
                name = channel.name,
                logo = channel.logo,
                userAgent = channel.userAgent,
                referrer = channel.referrer,
            )
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getTvShows(page: Int): List<TvShow> {
        val channels = getAllChannels()
        val pageSize = 50
        val start = (page - 1) * pageSize
        if (start >= channels.size) return emptyList()
        return channels.drop(start).take(pageSize).map { channel ->
            TvShow(id = createId(channel), title = channel.name, poster = channel.logo ?: "")
        }
    }
    override suspend fun getMovie(id: String): Movie = Movie(id = id, title = "Live", poster = "")
}