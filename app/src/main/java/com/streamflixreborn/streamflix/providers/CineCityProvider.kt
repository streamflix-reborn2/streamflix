package com.streamflixreborn.streamflix.providers

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.*
import okhttp3.*
import java.util.concurrent.TimeUnit

object CineCityProvider : IptvProvider {

    override val name = "MAGISTV"
    override val baseUrl = "https://raw.githubusercontent.com"
    override val logo = "https://i.ibb.co/39Ld2wbt/MAGISTV.png"
    override val language = "es"

    private const val TAG = "CineCityProvider"

    private const val OBFUSCATED_PLAYLIST = "aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL0NJTkVDSVRZMjAyMy9jaW5lY2l0eS9jaW5lY2l0eS5uZXQvcHJpbmNpcGFsLm0zdQ=="

    private const val FALLBACK_VIDEO_URL = "https://raw.githubusercontent.com/NANDOFS/ModoPrueba/main/VIDEO/SIN-SE%C3%91AL.mp4"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
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
            decodeBase64 = { Base64.decode(it, Base64.DEFAULT) },
        )

    private fun decodeId(id: String): Triple<String, String, String> {
        if (id == "creador-info" || id == "apoyo-nando") return Triple(id, "", "")
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
            val decodedUrl = String(Base64.decode(OBFUSCATED_PLAYLIST, Base64.DEFAULT))
            Log.d(TAG, "🗺️ Obteniendo lista desde origen seguro.")

            val request = Request.Builder().url(decodedUrl).build()
            val body = client.newCall(request).execute().body?.string() ?: return emptyList()
            val channels = parseM3U(body)
            cachedChannels = channels
            lastFetchTime = now
            channels
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error obteniendo M3U de CineCity: ${e.message}")
            cachedChannels ?: emptyList()
        }
    }

    override suspend fun getHome(): List<Category> {
        val channels = getAllChannels()
        val categories = mutableListOf<Category>()

        val channelCategories = channels
            .filter { it.group != null && it.group.isNotEmpty() }
            .groupBy { it.group!! }
            .map { (groupName, channelList) ->
                Category(
                    name = groupName,
                    list = channelList.distinctBy { it.name }.take(30).map { channel ->
                        TvShow(id = createId(channel), title = channel.name, poster = channel.logo ?: "", banner = channel.logo ?: "")
                    }
                )
            }.sortedBy { it.name }

        categories.addAll(channelCategories)

        val ungrouped = channels.filter { it.group.isNullOrEmpty() }
        if (ungrouped.isNotEmpty()) {
            categories.add(
                Category(
                    name = "General / Sin Categoría",
                    list = ungrouped.distinctBy { it.name }.take(30).map { channel ->
                        TvShow(id = createId(channel), title = channel.name, poster = channel.logo ?: "", banner = channel.logo ?: "")
                    }
                )
            )
        }

        categories.add(Category(name = "Soporte y Ayuda", list = listOf(getInfoItem("creador-info"), getInfoItem("apoyo-nando"))))
        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        val allChannels = getAllChannels()
        return allChannels.filter {
            it.name.contains(query, ignoreCase = true) || (it.group?.contains(query, ignoreCase = true) == true)
        }.distinctBy { it.name }.take(80).map { channel ->
            TvShow(id = createId(channel), title = channel.name, poster = channel.logo ?: "")
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val groupChannels = getAllChannels().filter {
            it.group?.contains(id, ignoreCase = true) ?: false
        }.distinctBy { it.name }
        val pagedList = groupChannels.drop((page - 1) * 40).take(40).map { channel ->
            TvShow(id = createId(channel), title = channel.name, poster = channel.logo ?: "")
        }
        return Genre(id = id, name = id, shows = pagedList)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = "CineCity", image = logo, biography = "", birthday = "", deathday = "", placeOfBirth = "")
    }

    override suspend fun getTvShow(id: String): TvShow {
        if (id == "creador-info" || id == "apoyo-nando") return getInfoItem(id)
        val (_, name, logo) = decodeId(id)
        return TvShow(
            id = id, title = name, poster = logo, banner = logo,
            overview = "Transmisión: $name\nFuente: CineCity M3U.",
            seasons = listOf(Season(id = id, number = 1, title = "Reproducir"))
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        if (seasonId == "creador-info" || seasonId == "apoyo-nando") return emptyList()
        return listOf(Episode(id = seasonId, number = 1, title = "Ver Ahora", season = null))
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        if (id == "creador-info" || id == "apoyo-nando") return emptyList()
        return listOf(Video.Server(id = id, name = "CineCity Stream"))
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val (url, _, _) = decodeId(server.id)
        val meta = getMetadataFromId(server.id)

        Log.d(TAG, "🎬 Solicitando Reproducción: $url")

        val videoHeaders = mutableMapOf<String, String>()
        meta["ua"]?.let { videoHeaders["User-Agent"] = it }
        meta["referer"]?.let { videoHeaders["Referer"] = it }

        return try {
            val checkRequest = Request.Builder()
                .url(url)
                .apply { videoHeaders.forEach { (k, v) -> addHeader(k, v) } }
                .build()

            val response = client.newCall(checkRequest).execute()
            var isAlive = response.isSuccessful


            if (isAlive) {
                val contentType = response.header("Content-Type") ?: ""
                if (contentType.contains("text/html", ignoreCase = true)) {
                    isAlive = false
                    Log.e(TAG, "🔴 Falso Positivo: El servidor devolvió una página web (HTML), no un video.")
                } else if (url.contains(".mpd") || url.contains(".m3u8")) {
                    // Aumentamos la visión a 15KB para escanear en profundidad sin descargar todo el archivo
                    val peekBody = response.peekBody(15360).string()

                    if (url.contains(".mpd")) {
                        if (!peekBody.contains("<MPD", ignoreCase = true)) {
                            isAlive = false
                            Log.e(TAG, "🔴 MPD Falso: No contiene etiqueta XML.")
                        } else if (peekBody.contains("ContentProtection", ignoreCase = true) || peekBody.contains("cenc:pssh", ignoreCase = true)) {
                            // ☠️ AQUÍ ATRAPAMOS AL CULPABLE DE TUS CRASHES
                            isAlive = false
                            Log.e(TAG, "🔴 ALERTA DRM: MPD Encriptado detectado. ExoPlayer crashearía sin llaves. ¡Activando Salvavidas!")
                        }
                    } else if (url.contains(".m3u8") && !peekBody.contains("#EXTM3U", ignoreCase = true)) {
                        isAlive = false
                        Log.e(TAG, "🔴 M3U8 Falso: No contiene la cabecera válida.")
                    }
                }
            }
            response.close()

            if (isAlive) {
                Log.d(TAG, "🟢 Explorador OK. Limpio de DRM. Enviando al reproductor.")
                Video(
                    source = url,
                    subtitles = emptyList(),
                    headers = if (videoHeaders.isNotEmpty()) videoHeaders else null
                )
            } else {
                Log.e(TAG, "🔴 Canal Muerto o Encriptado. ¡Activando Video Salvavidas!")
                Video(source = FALLBACK_VIDEO_URL, subtitles = emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "🔴 Timeout de Red o Error Grave. ¡Activando Video Salvavidas! Detalle: ${e.message}")
            Video(source = FALLBACK_VIDEO_URL, subtitles = emptyList())
        }
    }

    private fun getInfoItem(id: String): TvShow {
        val isReport = id == "creador-info"
        return TvShow(
            id = id,
            title = if (isReport) "Reportar problemas" else "Apoya al Proveedor",
            poster = if (isReport) "https://i.ibb.co/dsknGBHT/Imagen-de-Whats-App-2025-09-06-a-las-19-00-50-e8e5bcaa.jpg" else "https://i.ibb.co/B5gKLkqS/nuevo-formato-2-K-202604112205.jpg",
            banner = if (isReport) "https://i.ibb.co/dsknGBHT/Imagen-de-Whats-App-2025-09-06-a-las-19-00-50-e8e5bcaa.jpg" else "https://i.ibb.co/B5gKLkqS/nuevo-formato-2-K-202604112205.jpg",
            overview = if (isReport) "Si algún canal no funciona, por favor repórtalo en Telegram." else "Donación voluntaria para los servidores.",
            seasons = emptyList()
        )
    }

    private fun parseM3U(m3uRaw: String): List<M3UChannel> {
        val channels = mutableListOf<M3UChannel>()
        var curName = ""; var curLogo = ""; var curGroup = ""
        var curUA: String? = null; var curRef: String? = null

        for (line in m3uRaw.lines()) {
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
        val start = (page - 1) * 50
        if (start >= channels.size) return emptyList()
        return channels.drop(start).take(50).map { channel ->
            TvShow(id = createId(channel), title = channel.name, poster = channel.logo ?: "")
        }
    }

    override suspend fun getMovie(id: String): Movie = Movie(id = id, title = "Live", poster = "")
}