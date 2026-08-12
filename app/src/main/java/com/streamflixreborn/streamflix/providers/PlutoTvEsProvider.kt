package com.streamflixreborn.streamflix.providers

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.*
import okhttp3.*
import java.util.concurrent.TimeUnit

object PlutoTvEsProvider : IptvProvider {

    override val name = "Pluto TV Es"
    override val baseUrl = "https://raw.githubusercontent.com"
    override val logo = "https://i.ibb.co/TBjWz2Zw/Pluto-TV-es.jpg"
    override val language = "es"

    private const val TAG = "PlutoTvMxProvider"
    private const val PLAYLIST_URL = "https://raw.githubusercontent.com/BuddyChewChew/app-m3u-generator/main/playlists/plutotv_es.m3u"

    // 🛡️ La Vacuna Anti-Bots (Edición IPTV con CookieJar integrado) 🛡️
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

    // 💾 Sistema de Caché en Memoria
    private var cachedChannels: List<M3UChannel>? = null
    private var lastFetchTime: Long = 0
    private const val CACHE_DURATION = 30 * 60 * 1000 // 30 minutos

    data class M3UChannel(
        val name: String,
        val url: String,
        val logo: String?,
        val group: String?,
        val userAgent: String? = null,
        val referrer: String? = null
    )

    private fun createId(channel: M3UChannel): String {
        val rawId = "${channel.url}|${channel.name}|${channel.logo ?: ""}|${channel.userAgent ?: ""}|${channel.referrer ?: ""}"
        return Base64.encodeToString(rawId.toByteArray(), Base64.NO_WRAP)
    }

    private fun decodeId(id: String): Triple<String, String, String> {
        if (id == "creador-info" || id == "apoyo-nando") {
            return Triple(id, "", "")
        }
        return try {
            val decoded = String(Base64.decode(id, Base64.DEFAULT))
            val parts = decoded.split("|")
            Triple(parts[0], parts[1], parts.getOrNull(2) ?: "")
        } catch (e: Exception) {
            Triple(id, "Canal Desconocido", "")
        }
    }

    private fun getMetadataFromId(id: String): Map<String, String?> {
        return try {
            val decoded = String(Base64.decode(id, Base64.DEFAULT))
            val parts = decoded.split("|")
            mapOf(
                "ua" to parts.getOrNull(3).takeIf { it?.isNotEmpty() == true },
                "referer" to parts.getOrNull(4).takeIf { it?.isNotEmpty() == true }
            )
        } catch (e: Exception) { emptyMap() }
    }

    private fun getAllChannels(): List<M3UChannel> {
        val now = System.currentTimeMillis()
        if (cachedChannels != null && (now - lastFetchTime) < CACHE_DURATION) return cachedChannels!!

        return try {
            val request = Request.Builder().url(PLAYLIST_URL).build()
            val body = client.newCall(request).execute().body?.string() ?: return emptyList()
            val channels = parseM3U(body)
            cachedChannels = channels
            lastFetchTime = now
            channels
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error obteniendo M3U: ${e.message}")
            cachedChannels ?: emptyList()
        }
    }

    override suspend fun getHome(): List<Category> {
        val channels = getAllChannels()
        val categories = mutableListOf<Category>()

        // 1. Agrupamos todos los canales por su "group-title"
        val channelCategories = channels
            .filter { it.group != null && it.group.isNotEmpty() }
            .groupBy { it.group!! }
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

        // Agrupamos canales sin grupo en "General" si existen
        val ungrouped = channels.filter { it.group.isNullOrEmpty() }
        if (ungrouped.isNotEmpty()) {
            categories.add(
                Category(
                    name = "General",
                    list = ungrouped.distinctBy { it.name }.take(25).map { channel ->
                        TvShow(
                            id = createId(channel),
                            title = channel.name,
                            poster = channel.logo ?: "",
                            banner = channel.logo ?: ""
                        )
                    }
                )
            )
        }

        // 2. Añadimos el módulo de soporte al final
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

        val channelResults = allChannels.filter {
            it.name.contains(query, ignoreCase = true) || (it.group?.contains(query, ignoreCase = true) == true)
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

    override suspend fun getPeople(id: String, page: Int): People {
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
            overview = "Canal de Pluto TV: $name\nSeñal obtenida vía lista M3U.",
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

        Log.d(TAG, "🎬 Reproduciendo: $url")
        meta["ua"]?.let { Log.d(TAG, "🛡️ Header UA detectado en M3U: $it") }
        meta["referer"]?.let { Log.d(TAG, "🛡️ Header Referer detectado en M3U: $it") }

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
        return channels
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