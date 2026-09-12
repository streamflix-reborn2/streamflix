package com.streamflixreborn.streamflix.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

object DomainRedirectChecker {

    data class Result(
        val originalUrl: String,
        val finalUrl: String,
        val changed: Boolean,
    )

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun check(url: String): Result = withContext(Dispatchers.IO) {
        val normalized = normalizeInput(url)

        val request = Request.Builder()
            .url(normalized)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Safari/537.36"
            )
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "HTTP ${response.code}"
                )
            }

            val finalUrl = response.request.url
                .newBuilder()
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build()
                .toString()
                .trimEnd('/')

            val originalBase = request.url
                .newBuilder()
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build()
                .toString()
                .trimEnd('/')

            Result(
                originalUrl = originalBase,
                finalUrl = finalUrl,
                changed = !sameHost(originalBase, finalUrl),
            )
        }
    }

    fun hostOnly(url: String): String {
        val normalized = normalizeInput(url)
        return normalized.toHttpUrl()
            .host
            .removePrefix("www.")
    }

    private fun normalizeInput(value: String): String {
        val trimmed = value.trim()

        if (
            trimmed.startsWith("http://") ||
            trimmed.startsWith("https://")
        ) {
            return trimmed
        }

        return "https://$trimmed"
    }

    private fun sameHost(first: String, second: String): Boolean {
        val a = first.toHttpUrl().host.removePrefix("www.")
        val b = second.toHttpUrl().host.removePrefix("www.")
        return a.equals(b, ignoreCase = true)
    }
}
