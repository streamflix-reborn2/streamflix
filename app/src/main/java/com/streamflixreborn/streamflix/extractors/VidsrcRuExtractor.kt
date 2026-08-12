package com.streamflixreborn.streamflix.extractors

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class VidsrcRuExtractor : Extractor() {

    override val name = "Vidsrc.Ru"
    override val mainUrl = "https://vidsrc.ru"

    fun server(videoType: Video.Type): Video.Server {
        return Video.Server(
            id = name,
            name = name,
            src = when (videoType) {
                is Video.Type.Movie -> "$mainUrl/movie/${videoType.id}"
                is Video.Type.Episode -> "$mainUrl/tv/${videoType.tvShow.id}/${videoType.season.number}/${videoType.number}"
            },
        )
    }

    override suspend fun extract(link: String): Video {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(StreamFlixApp.instance.applicationContext)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                }
                val webViewUserAgent = webView.settings.userAgentString

                val timeoutHandler = Handler(Looper.getMainLooper())
                val timeoutRunnable = Runnable {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            Exception("Timeout waiting for VidsrcRu stream"),
                        )
                        webView.destroy()
                    }
                }
                timeoutHandler.postDelayed(timeoutRunnable, 30_000L)

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): android.webkit.WebResourceResponse? {
                        val requestUri = request?.url
                        val url = requestUri?.toString().orEmpty()
                        val isPlaylist = requestUri?.path
                            ?.endsWith(".m3u8", ignoreCase = true) == true

                        if (url.contains("/file2/") && isPlaylist) {
                            timeoutHandler.removeCallbacks(timeoutRunnable)
                            if (continuation.isActive) {
                                val requestHeaders = request?.requestHeaders.orEmpty()
                                val playbackHeaders = buildMap {
                                    requestHeaders.entries
                                        .firstOrNull { it.key.equals("Referer", ignoreCase = true) }
                                        ?.value
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { put("Referer", it) }
                                    requestHeaders.entries
                                        .firstOrNull { it.key.equals("Origin", ignoreCase = true) }
                                        ?.value
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { put("Origin", it) }
                                    requestHeaders.entries
                                        .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                                        ?.value
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { put("User-Agent", it) }

                                    if (!containsKey("Referer")) put("Referer", link)
                                    if (!containsKey("User-Agent")) put("User-Agent", webViewUserAgent)

                                    CookieManager.getInstance()
                                        .getCookie(url)
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { put("Cookie", it) }
                                }

                                val video = Video(
                                    source = url,
                                    subtitles = emptyList(),
                                    type = MimeTypes.APPLICATION_M3U8,
                                    headers = playbackHeaders.ifEmpty { null },
                                )
                                Handler(Looper.getMainLooper()).post {
                                    if (continuation.isActive) continuation.resume(video)
                                    webView.stopLoading()
                                    webView.destroy()
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                webView.loadUrl(link)

                continuation.invokeOnCancellation {
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    Handler(Looper.getMainLooper()).post {
                        webView.stopLoading()
                        webView.destroy()
                    }
                }
            }
        }
    }
}
