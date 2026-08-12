package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.suspendCancellableCoroutine
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object AnimeOnlineNinjaCronetClient {
    private const val CACHE_SIZE_BYTES = 20L * 1024L * 1024L
    private const val READ_BUFFER_SIZE = 32 * 1024

    data class Response(
        val statusCode: Int,
        val finalUrl: String,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    ) {
        val isSuccessful: Boolean get() = statusCode in 200..299

        fun bodyAsString(): String = body.toString(Charsets.UTF_8)
    }

    fun interface Callback {
        fun onComplete(result: Result<Response>)
    }

    class Call internal constructor() {
        @Volatile
        private var request: UrlRequest? = null
        private val cancelled = AtomicBoolean(false)

        internal fun attach(request: UrlRequest) {
            this.request = request
            if (cancelled.get()) request.cancel()
        }

        fun cancel() {
            cancelled.set(true)
            request?.cancel()
        }

        internal fun isCancelled(): Boolean = cancelled.get()
    }

    @Volatile
    private var engine: CronetEngine? = null
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "anime-ninja-cronet").apply { isDaemon = true }
    }

    fun init(context: Context) {
        engine(context)
    }

    suspend fun get(
        context: Context,
        url: String,
        headers: Map<String, String>,
        useCache: Boolean = true,
    ): Response = suspendCancellableCoroutine { continuation ->
        val call = get(context, url, headers, useCache) { result ->
            if (!continuation.isActive) return@get
            result.fold(continuation::resume, continuation::resumeWithException)
        }
        continuation.invokeOnCancellation { call.cancel() }
    }

    fun get(
        context: Context,
        url: String,
        headers: Map<String, String>,
        useCache: Boolean = true,
        callback: Callback,
    ): Call {
        val call = Call()
        val completed = AtomicBoolean(false)
        val output = ByteArrayOutputStream()

        fun complete(result: Result<Response>) {
            if (!call.isCancelled() && completed.compareAndSet(false, true)) {
                callback.onComplete(result)
            }
        }

        val requestCallback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(
                request: UrlRequest,
                info: UrlResponseInfo,
                newLocationUrl: String,
            ) {
                persistCookies(info)
                request.followRedirect()
            }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                persistCookies(info)
                request.read(ByteBuffer.allocateDirect(READ_BUFFER_SIZE))
            }

            override fun onReadCompleted(
                request: UrlRequest,
                info: UrlResponseInfo,
                byteBuffer: ByteBuffer,
            ) {
                byteBuffer.flip()
                val bytes = ByteArray(byteBuffer.remaining())
                byteBuffer.get(bytes)
                output.write(bytes)
                byteBuffer.clear()
                request.read(byteBuffer)
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                persistCookies(info)
                complete(
                    Result.success(
                        Response(
                            statusCode = info.httpStatusCode,
                            finalUrl = info.url,
                            headers = info.allHeaders,
                            body = output.toByteArray(),
                        )
                    )
                )
            }

            override fun onFailed(
                request: UrlRequest,
                info: UrlResponseInfo?,
                error: CronetException,
            ) {
                complete(Result.failure(error))
            }

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) = Unit
        }

        val builder = engine(context).newUrlRequestBuilder(url, requestCallback, executor)
            .setHttpMethod("GET")
        if (!useCache) builder.disableCache()
        headers.forEach { (name, value) ->
            if (value.isNotBlank()) builder.addHeader(name, value)
        }
        val request = builder.build()
        call.attach(request)
        request.start()
        return call
    }

    @Synchronized
    private fun engine(context: Context): CronetEngine {
        engine?.let { return it }
        return CronetEngine.Builder(context.applicationContext)
            .enableHttp2(true)
            .enableQuic(true)
            .enableBrotli(true)
            .setStoragePath(context.cacheDir.resolve("anime-ninja-cronet").apply { mkdirs() }.absolutePath)
            .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, CACHE_SIZE_BYTES)
            .build()
            .also { engine = it }
    }

    private fun persistCookies(info: UrlResponseInfo) {
        val setCookies = info.allHeaders.entries
            .filter { (name, _) -> name.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
        if (setCookies.isEmpty()) return

        CookieManager.getInstance().apply {
            setCookies.forEach { cookie -> setCookie(info.url, cookie) }
            flush()
        }
    }
}