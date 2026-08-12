package com.streamflixreborn.streamflix.ui

import android.content.Context
import android.graphics.drawable.PictureDrawable
import com.bumptech.glide.Glide
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import com.caverock.androidsvg.SVG
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.utils.ArtworkRequestHeaders
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@GlideModule
class GlideCustomModule : AppGlideModule() {

    companion object {
        private const val IMAGE_CACHE_BYTES = 32L * 1024L * 1024L
        private const val IMAGE_CONNECT_TIMEOUT_SECONDS = 10L
        private const val IMAGE_READ_TIMEOUT_SECONDS = 15L
        private const val IMAGE_CALL_TIMEOUT_SECONDS = 20L
    }

    private fun getOkHttpClient(context: Context): OkHttpClient {
        // TV screens revisit the same posters/backgrounds frequently. A modestly larger disk cache
        // prevents network/refetch churn without retaining additional decoded bitmaps in memory.
        val appCache = Cache(File(context.cacheDir, "glide-okhttp-cache"), IMAGE_CACHE_BYTES)

        val trustAllCerts = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<java.security.cert.X509Certificate>,
                    authType: String,
                ) = Unit

                override fun checkServerTrusted(
                    chain: Array<java.security.cert.X509Certificate>,
                    authType: String,
                ) = Unit

                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                    emptyArray()
            },
        )
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }
        val trustManager = trustAllCerts[0] as X509TrustManager

        val dispatcher = Dispatcher().apply {
            // Avoid a large TV grid monopolizing CPU/network sockets while the user is navigating.
            maxRequests = 12
            maxRequestsPerHost = 5
        }

        val builder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .cache(appCache)
            .cookieJar(imageCookieJar)
            .addInterceptor { chain ->
                val original = chain.request()
                val requestBuilder = original.newBuilder()

                if (original.header("User-Agent") == null) {
                    requestBuilder.header("User-Agent", NetworkClient.USER_AGENT)
                }
                if (original.header("Accept") == null) {
                    requestBuilder.header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                }
                if (original.header("Accept-Language") == null) {
                    requestBuilder.header(
                        "Accept-Language",
                        "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
                    )
                }
                chain.proceed(requestBuilder.build())
            }
            .connectTimeout(IMAGE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(IMAGE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(IMAGE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val headers = ArtworkRequestHeaders.headersFor(request.url)
                val strippedUrl = ArtworkRequestHeaders.stripHeaders(request.url)
                val fixedRequest = if (headers.isNotEmpty() || strippedUrl != request.url) {
                    request.newBuilder()
                        .url(strippedUrl)
                        .apply {
                            headers.forEach { (name, value) -> header(name, value) }
                        }
                        .build()
                } else {
                    request
                }
                chain.proceed(fixedRequest)
            }
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .dns(DnsResolver.doh)

        // BASIC logging for every poster/banner creates avoidable Logcat/string churn on debug TV APKs.
        // Keep it on other debug layouts where it remains useful for provider/image diagnostics.
        if (BuildConfig.DEBUG && !BuildConfig.APP_LAYOUT.equals("tv", ignoreCase = true)) {
            builder.addInterceptor(
                HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC),
            )
        }

        return builder.build()
    }

    override fun registerComponents(
        context: Context,
        glide: Glide,
        registry: com.bumptech.glide.Registry,
    ) {
        val okHttpClient = getOkHttpClient(context)
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            AnimeOnlineNinjaCronetUrlLoader.Factory(context, okHttpClient),
        )
        registry.append(
            InputStream::class.java,
            SVG::class.java,
            SvgDecoder(),
        )
        registry.register(
            SVG::class.java,
            PictureDrawable::class.java,
            SvgDrawableTranscoder(),
        )
    }

    private val imageCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            NetworkClient.cookieJar.saveFromResponse(url, cookies)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            NetworkClient.cookieJar.loadForRequest(url)
    }
}
