package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.JsUnpacker
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import java.net.URL
import kotlin.coroutines.resume

class UpzoneExtractor : Extractor() {

    override val name = "Upzone"
    override val mainUrl = "https://upzone.cc"
    override val aliasUrls = listOf(
        "https://upzone.to",
        "https://upzone.net",
        "https://upzone.link"
    )

    private val context: Context = StreamFlixApp.instance.applicationContext
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    override suspend fun extract(link: String): Video {
        val resolvedUrl = resolveRedirectWithWebView(context, link)
        val pageUrl = if (resolvedUrl.contains("upzone")) resolvedUrl else link
        val service = Service.build(baseUrlOf(pageUrl))
        val referer = buildReferer(link)

        val document = service.get(
            url = pageUrl,
            referer = referer,
            userAgent = userAgent
        )

        val directSource = extractDirectSource(document, pageUrl)
        if (directSource != null) {
            return buildVideo(directSource, pageUrl)
        }

        val delegatedLink = extractDelegatedLink(document, pageUrl)
            ?: throw Exception("Upzone source not found")

        return Extractor.extract(delegatedLink)
    }

    private fun extractDirectSource(document: Document, pageUrl: String): String? {
        val html = document.outerHtml()

        val directPatterns = listOf(
            Regex("""["']file["']\s*[:=]\s*["']((?:https?://|/)[^"']+\.m3u8[^"']*)["']"""),
            Regex("""["']src["']\s*[:=]\s*["']((?:https?://|/)[^"']+\.m3u8[^"']*)["']"""),
            Regex("""["']hls\d*["']\s*[:=]\s*["']((?:https?://|/)[^"']+\.m3u8[^"']*)["']"""),
            Regex("""source\s*=\s*["']((?:https?://|/)[^"']+\.m3u8[^"']*)["']"""),
            Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s<]*""")
        )

        directPatterns.forEach { pattern ->
            val match = pattern.find(html)?.groupValues?.lastOrNull()
            if (!match.isNullOrBlank()) {
                return absolutize(match, pageUrl)
            }
        }

        val packedScript = Regex("""(eval\(function\(p,a,c,k,e,d\).*?</script>)""", RegexOption.DOT_MATCHES_ALL)
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.substringBefore("</script>")
        if (!packedScript.isNullOrBlank()) {
            val unpacked = JsUnpacker(packedScript).unpack().orEmpty()
            directPatterns.forEach { pattern ->
                val match = pattern.find(unpacked)?.groupValues?.lastOrNull()
                if (!match.isNullOrBlank()) {
                    return absolutize(match, pageUrl)
                }
            }
        }

        return null
    }

    private fun extractDelegatedLink(document: Document, pageUrl: String): String? {
        val candidates = listOfNotNull(
            document.selectFirst("a.buttonprch[href]")?.attr("href"),
            document.selectFirst("iframe[src]")?.attr("src"),
            document.selectFirst("source[src]")?.attr("src"),
            document.select("script").firstNotNullOfOrNull { script ->
                val data = script.data()
                Regex("""https?://[^"'\\\s<]+""").find(data)?.value
            }
        )

        return candidates
            .asSequence()
            .mapNotNull { it.takeIf(String::isNotBlank) }
            .map { absolutize(it, pageUrl) }
            .firstOrNull()
    }

    private fun buildVideo(source: String, pageUrl: String): Video {
        val uri = Uri.parse(pageUrl)
        return Video(
            source = source,
            headers = mapOf(
                "Referer" to "${uri.scheme}://${uri.host}/",
                "Origin" to "${uri.scheme}://${uri.host}",
                "User-Agent" to userAgent,
                "Accept" to "*/*"
            )
        )
    }

    private fun buildReferer(link: String): String {
        val uri = Uri.parse(link)
        val reff = uri.getQueryParameter("reff")
        return when {
            !reff.isNullOrBlank() && reff.startsWith("http") -> reff
            !reff.isNullOrBlank() -> "https://$reff/"
            uri.scheme != null && uri.host != null -> "${uri.scheme}://${uri.host}/"
            else -> mainUrl
        }
    }

    private fun absolutize(url: String, pageUrl: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> {
                val parsed = URL(pageUrl)
                "${parsed.protocol}://${parsed.host}$url"
            }
            else -> url
        }
    }

    private fun baseUrlOf(url: String): String {
        val parsed = URL(url)
        return "${parsed.protocol}://${parsed.host}/"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveRedirectWithWebView(context: Context, url: String): String =
        withContext(Dispatchers.Main) {
            val result = withTimeoutOrNull(30000) {
                suspendCancellableCoroutine { cont ->
                    val webView = WebView(context)
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.userAgentString = userAgent
                    // Block popup/new-tab ads the embed page tries to spawn via window.open()
                    // during this redirect hop.
                    webView.settings.javaScriptCanOpenWindowsAutomatically = false
                    webView.settings.setSupportMultipleWindows(false)
                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?,
                        ): Boolean = false
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val nextUrl = request?.url?.toString().orEmpty()
                            if (nextUrl.isNotBlank() && cont.isActive) {
                                cont.resume(nextUrl)
                                webView.destroy()
                                return true
                            }
                            return false
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (!finishedUrl.isNullOrBlank() && cont.isActive) {
                                cont.resume(finishedUrl)
                                webView.destroy()
                            }
                        }
                    }

                    webView.loadUrl(
                        url,
                        mapOf(
                            "Referer" to buildReferer(url),
                            "User-Agent" to userAgent
                        )
                    )

                    cont.invokeOnCancellation {
                        webView.stopLoading()
                        webView.destroy()
                    }
                }
            }
            result ?: url
        }

    private interface Service {
        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String,
            @Header("User-Agent") userAgent: String
        ): Document

        companion object {
            fun build(baseUrl: String): Service {
                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()
                    .create(Service::class.java)
            }
        }
    }
}
