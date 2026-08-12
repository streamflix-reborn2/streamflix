package com.streamflixreborn.streamflix.extractors

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class VidxGoExtractor : Extractor() {
    override val name = "VidxGo"
    override val mainUrl = "https://v.vidxgo.co"

    override suspend fun extract(link: String): Video {
        val client = OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val uri = Uri.parse(link)
        val referer = "${uri.scheme}://${uri.host}/"
        val requestBuilder = Request.Builder()
            .url(link)
            .header("Referer", referer)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            )

        if (!link.contains("/t/")) {
            requestBuilder.header("sec-fetch-dest", "iframe")
        }

        val html = client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("VidxGo: initial request failed with HTTP ${response.code}")
            }
            response.body?.string() ?: throw Exception("Failed to get HTML from VidxGo")
        }

        if (link.contains("/t/")) {
            val videoUrlRaw = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
                .find(html)?.groupValues?.get(1)
                ?: throw Exception("VidxGo: Could not find url in TV series response")
            val videoUrl = videoUrlRaw.replace("\\/", "/")

            var expireTime = normalizeTokenExpiryMillis(
                Regex("\"expire\"\\s*:\\s*(\\d+)")
                    .find(html)?.groupValues?.get(1)?.toLongOrNull(),
            )

            val initialUri = Uri.parse(videoUrl)
            val session = TokenManager.beginSession(initialUri.encodedQuery)
            Log.d(
                "TokenManager",
                "[INIT] Session=$session expire=$expireTime query=${TokenManager.latestQuery?.take(60)}...",
            )

            TokenManager.launchRefresh(session) {
                while (TokenManager.isCurrent(session)) {
                    val delayMs = if (expireTime != null) {
                        val remaining = expireTime!! - System.currentTimeMillis()
                        (remaining - 15_000L).coerceAtLeast(5_000L)
                    } else {
                        150_000L
                    }

                    Log.d(
                        "TokenManager",
                        "[SCHEDULE] Session=$session refresh in ${delayMs / 1000}s",
                    )
                    delay(delayMs)
                    if (!TokenManager.isCurrent(session)) break

                    try {
                        val updateRequest = Request.Builder()
                            .url(link)
                            .header("Referer", referer)
                            .header(
                                "User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                            )
                            .header("sec-fetch-dest", "empty")
                            .build()

                        client.newCall(updateRequest).execute().use { response ->
                            val newHtml = response.body?.string()
                            if (!response.isSuccessful) {
                                Log.w(
                                    "TokenManager",
                                    "[REFRESH] Session=$session HTTP ${response.code}",
                                )
                                return@use
                            }
                            if (newHtml.isNullOrBlank()) {
                                Log.w("TokenManager", "[REFRESH] Session=$session empty response")
                                return@use
                            }

                            expireTime = normalizeTokenExpiryMillis(
                                Regex("\"expire\"\\s*:\\s*(\\d+)")
                                    .find(newHtml)?.groupValues?.get(1)?.toLongOrNull(),
                            )
                            val newUrl = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
                                .find(newHtml)?.groupValues?.get(1)
                                ?.replace("\\/", "/")

                            if (newUrl != null) {
                                val updated = TokenManager.updateQuery(
                                    session,
                                    Uri.parse(newUrl).encodedQuery,
                                )
                                if (updated) {
                                    Log.d(
                                        "TokenManager",
                                        "[REFRESH] Session=$session token updated; expire=$expireTime",
                                    )
                                }
                            } else {
                                Log.w(
                                    "TokenManager",
                                    "[REFRESH] Session=$session URL missing in response",
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("TokenManager", "[REFRESH] Session=$session failed", e)
                        expireTime = System.currentTimeMillis() + 15_000L
                    }
                }
            }

            return Video(
                source = videoUrl,
                headers = mapOf(
                    "origin" to "https://v.vidxgo.co",
                    "referer" to "https://v.vidxgo.co/",
                    "sec-fetch-dest" to "empty",
                    "sec-fetch-site" to "cross-site",
                ),
                maintainToken = true,
            )
        }

        val scriptRegex = Regex(
            "<script[\\s\\S]*?>[\\s\\S]*?\\(function\\(\\)\\s*\\{[\\s\\S]*?\\}\\s*\\)\\(\\);[\\s\\S]*?</script>",
            RegexOption.IGNORE_CASE,
        )
        val scriptMatches = scriptRegex.findAll(html).toList()

        if (scriptMatches.size < 5) {
            Log.e(
                "VidxGoExtractor",
                "Could not find enough encrypted scripts. Found: ${scriptMatches.size}",
            )
            throw Exception("VidxGo: Could not find fifth encrypted script")
        }

        val targetScript = scriptMatches[4].value
        val k = Regex("var\\s+k\\s*=\\s*['\"]([^'\"]+)['\"]")
            .find(targetScript)?.groupValues?.get(1)
            ?: throw Exception("VidxGo: Could not find key 'k'")
        val d = Regex("atob\\(['\"]([^'\"]+)['\"]\\)")
            .find(targetScript)?.groupValues?.get(1)
            ?: throw Exception("VidxGo: Could not find data 'd'")

        val decodedD = Base64.decode(d, Base64.DEFAULT)
        val decrypted = ByteArray(decodedD.size)
        for (i in decodedD.indices) {
            decrypted[i] = ((decodedD[i].toInt() and 0xFF) xor (k[i % k.length].code and 0xFF)).toByte()
        }
        val decryptedText = String(decrypted)

        val videoUrlRaw = Regex("currentSrc\\s*=\\s*['\"]([^'\"]+)['\"]")
            .find(decryptedText)?.groupValues?.get(1)
            ?: throw Exception("VidxGo: Could not find currentSrc in decrypted script")
        val videoUrl = videoUrlRaw.replace("\\/", "/")

        val filmPathSegment = uri.pathSegments.firstOrNull()
        val filmRefreshUrl = filmPathSegment?.let { "https://v.vidxgo.co/t/$it" }
        val initialUri = Uri.parse(videoUrl)

        val currentToken = Regex("let\\s+currentToken\\s*=\\s*['\"]([^'\"]+)['\"]")
            .find(decryptedText)?.groupValues?.get(1)
        val initialExpireTime = normalizeTokenExpiryMillis(
            Regex("let\\s+currentExpire\\s*=\\s*(\\d+)")
                .find(decryptedText)?.groupValues?.get(1)?.toLongOrNull(),
        )

        val session = TokenManager.beginSession(initialUri.encodedQuery)
        Log.d(
            "TokenManager",
            "[FILM-INIT] Session=$session token=${currentToken?.take(12)}... expire=$initialExpireTime",
        )

        if (filmRefreshUrl != null) {
            TokenManager.launchRefresh(session) {
                var expireTime: Long? = initialExpireTime
                while (TokenManager.isCurrent(session)) {
                    val delayMs = if (expireTime != null) {
                        val remaining = expireTime!! - System.currentTimeMillis()
                        (remaining - 15_000L).coerceAtLeast(5_000L)
                    } else {
                        150_000L
                    }

                    Log.d(
                        "TokenManager",
                        "[FILM-SCHEDULE] Session=$session refresh in ${delayMs / 1000}s",
                    )
                    delay(delayMs)
                    if (!TokenManager.isCurrent(session)) break

                    try {
                        val updateRequest = Request.Builder()
                            .url(filmRefreshUrl)
                            .header("Referer", referer)
                            .header(
                                "User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                            )
                            .header("sec-fetch-dest", "empty")
                            .build()

                        client.newCall(updateRequest).execute().use { response ->
                            val newHtml = response.body?.string()
                            if (!response.isSuccessful) {
                                Log.w(
                                    "TokenManager",
                                    "[FILM-REFRESH] Session=$session HTTP ${response.code}",
                                )
                                return@use
                            }
                            if (newHtml.isNullOrBlank()) {
                                Log.w(
                                    "TokenManager",
                                    "[FILM-REFRESH] Session=$session empty response",
                                )
                                return@use
                            }

                            expireTime = normalizeTokenExpiryMillis(
                                Regex("\"expire\"\\s*:\\s*(\\d+)")
                                    .find(newHtml)?.groupValues?.get(1)?.toLongOrNull(),
                            )
                            val newUrl = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
                                .find(newHtml)?.groupValues?.get(1)
                                ?.replace("\\/", "/")

                            if (newUrl != null) {
                                val updated = TokenManager.updateQuery(
                                    session,
                                    Uri.parse(newUrl).encodedQuery,
                                )
                                if (updated) {
                                    Log.d(
                                        "TokenManager",
                                        "[FILM-REFRESH] Session=$session token updated; expire=$expireTime",
                                    )
                                }
                            } else {
                                Log.w(
                                    "TokenManager",
                                    "[FILM-REFRESH] Session=$session URL missing in response",
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("TokenManager", "[FILM-REFRESH] Session=$session failed", e)
                        expireTime = System.currentTimeMillis() + 15_000L
                    }
                }
            }
        }

        return Video(
            source = videoUrl,
            headers = mapOf(
                "origin" to "https://v.vidxgo.co",
                "referer" to "https://v.vidxgo.co/",
                "sec-fetch-dest" to "empty",
                "sec-fetch-site" to "cross-site",
            ),
            maintainToken = true,
        )
    }
}
