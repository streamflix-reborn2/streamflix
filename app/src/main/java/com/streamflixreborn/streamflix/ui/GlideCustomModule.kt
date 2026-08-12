package com.streamflixreborn.streamflix.ui

import android.content.Context
import android.graphics.drawable.PictureDrawable
import android.webkit.CookieManager
import com.bumptech.glide.Glide
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import com.caverock.androidsvg.SVG
import com.streamflixreborn.streamflix.utils.ArtworkRequestHeaders
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import okhttp3.*
import okhttp3.OkHttpClient.Builder
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

    private fun getOkHttpClient(context: Context): OkHttpClient {
        val appCache = Cache(File(context.cacheDir, "glide-okhttp-cache"), 10 * 1024 * 1024)

        val logging = HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC)

        val trustAllCerts = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }
        )
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, trustAllCerts, SecureRandom()) }
        val trustManager = trustAllCerts[0] as X509TrustManager

        return Builder()
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
                        "Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
                    )
                }
                chain.proceed(requestBuilder.build())
            }
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
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
            .addInterceptor(logging)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .dns(DnsResolver.doh)
            .build()
    }

    override fun registerComponents(
        context: Context, glide: Glide, registry: com.bumptech.glide.Registry
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

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return NetworkClient.cookieJar.loadForRequest(url)
        }
    }

}
