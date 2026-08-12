package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLHandshakeException

/**
 * Lightweight resilience/cache facade around StreamingCommunityProvider.
 *
 * StreamingCommunity changes domains and short-lived iframe URLs frequently. The legacy provider
 * already knows how to rebuild itself, but TV screens may request home/details/episodes concurrently.
 * This facade coalesces those requests, caches stable metadata, and performs at most one shared
 * recovery rebuild when a stale domain or transient HTTP failure is observed.
 */
class OptimizedStreamingCommunityProvider(
    private val configuredLanguage: String,
) : Provider {

    companion object {
        private const val TAG = "StreamingCommunityFast"
        private const val HOME_TTL_MS = 45_000L
        private const val DETAIL_TTL_MS = 5 * 60_000L
        private const val SEASON_TTL_MS = 5 * 60_000L
        private const val RECOVERY_COOLDOWN_MS = 2_000L
        private const val TV_MAX_HOME_ROWS = 14
        private const val TV_MAX_ROW_ITEMS = 24
        private const val TV_MAX_FEATURED_ITEMS = 8
        private const val MAX_METADATA_CACHE_ENTRIES = 96
        private const val MUTEX_STRIPES = 16
    }

    private data class TimedValue<T>(
        val value: T,
        val createdAtMs: Long,
    ) {
        fun isFresh(ttlMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
            nowMs - createdAtMs <= ttlMs
    }

    private val delegate = StreamingCommunityProvider(configuredLanguage)
    private val homeMutex = Mutex()
    private val recoveryMutex = Mutex()
    private val detailMutexes = List(MUTEX_STRIPES) { Mutex() }

    @Volatile
    private var homeCache: TimedValue<List<Category>>? = null

    private val movieCache = ConcurrentHashMap<String, TimedValue<Movie>>()
    private val tvShowCache = ConcurrentHashMap<String, TimedValue<TvShow>>()
    private val seasonCache = ConcurrentHashMap<String, TimedValue<List<Episode>>>()

    @Volatile
    private var observedDomain: String = UserPreferences.streamingcommunityDomain

    @Volatile
    private var lastRecoveryAtMs: Long = 0L

    override val language: String
        get() = delegate.language

    override val baseUrl: String
        get() = delegate.baseUrl

    override val name: String
        get() = delegate.name

    override val logo: String
        get() = delegate.logo

    override suspend fun getHome(): List<Category> {
        syncDomainPreference()
        val now = System.currentTimeMillis()
        homeCache?.takeIf { it.isFresh(HOME_TTL_MS, now) }?.let { return it.value }

        return homeMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            homeCache?.takeIf { it.isFresh(HOME_TTL_MS, lockedNow) }?.let {
                return@withLock it.value
            }

            val stale = homeCache?.value
            try {
                withRecovery("home") { delegate.getHome() }
                    .let(::shapeHomeForLayout)
                    .also { categories ->
                        if (categories.isNotEmpty()) {
                            homeCache = TimedValue(categories, System.currentTimeMillis())
                        }
                    }
            } catch (e: Exception) {
                if (!stale.isNullOrEmpty()) {
                    Log.w(TAG, "Home refresh failed; serving stale in-memory home", e)
                    stale
                } else {
                    throw e
                }
            }
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> =
        withRecovery("search") { delegate.search(query, page) }

    override suspend fun getMovies(page: Int): List<Movie> =
        withRecovery("movies") { delegate.getMovies(page) }

    override suspend fun getTvShows(page: Int): List<TvShow> =
        withRecovery("tv-shows") { delegate.getTvShows(page) }

    override suspend fun getMovie(id: String): Movie {
        syncDomainPreference()
        movieCache[id]?.takeIf { it.isFresh(DETAIL_TTL_MS) }?.let { return it.value }
        warmHomeForMetadata()
        return mutexFor(id).withLock {
            movieCache[id]?.takeIf { it.isFresh(DETAIL_TTL_MS) }?.let {
                return@withLock it.value
            }
            withRecovery("movie:$id") { delegate.getMovie(id) }.also { movie ->
                putBounded(movieCache, id, TimedValue(movie, System.currentTimeMillis()))
            }
        }
    }

    override suspend fun getTvShow(id: String): TvShow {
        syncDomainPreference()
        tvShowCache[id]?.takeIf { it.isFresh(DETAIL_TTL_MS) }?.let { return it.value }
        warmHomeForMetadata()
        return mutexFor(id).withLock {
            tvShowCache[id]?.takeIf { it.isFresh(DETAIL_TTL_MS) }?.let {
                return@withLock it.value
            }
            withRecovery("tv-show:$id") { delegate.getTvShow(id) }.also { tvShow ->
                putBounded(tvShowCache, id, TimedValue(tvShow, System.currentTimeMillis()))
            }
        }
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        syncDomainPreference()
        seasonCache[seasonId]?.takeIf { it.isFresh(SEASON_TTL_MS) }?.let { return it.value }
        warmHomeForMetadata()
        return mutexFor(seasonId).withLock {
            seasonCache[seasonId]?.takeIf { it.isFresh(SEASON_TTL_MS) }?.let {
                return@withLock it.value
            }
            withRecovery("season:$seasonId") {
                delegate.getEpisodesBySeason(seasonId)
            }.also { episodes ->
                if (episodes.isNotEmpty()) {
                    putBounded(
                        seasonCache,
                        seasonId,
                        TimedValue(episodes, System.currentTimeMillis()),
                    )
                }
            }
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre =
        withRecovery("genre:$id:$page") { delegate.getGenre(id, page) }

    override suspend fun getPeople(id: String, page: Int): People =
        withRecovery("people:$id:$page") { delegate.getPeople(id, page) }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        syncDomainPreference()
        val first = withRecovery("servers:$id") {
            delegate.getServers(id, videoType)
        }
        if (first.isNotEmpty()) return first

        // The legacy raw-document helper historically converted an HTTP 404 into an empty document.
        // Treat an empty iframe result as a stale-domain signal and rebuild once internally.
        performRecovery("empty-iframe:$id", force = true)
        return delegate.getServers(id, videoType).also { captureResolvedDomain() }
    }

    override suspend fun getVideo(server: Video.Server): Video =
        withRecovery("video:${server.name}") { delegate.getVideo(server) }

    /** Exposed for settings screens/future callers; normal requests self-sync automatically. */
    suspend fun rebuildService() {
        performRecovery("manual", force = true)
    }

    /**
     * Detail/season calls in HomeViewModel can arrive while the first home request is still building
     * StreamingCommunity's Inertia version. Wait for that one single-flight request rather than
     * allowing every detail coroutine to bootstrap the version independently.
     */
    private suspend fun warmHomeForMetadata() {
        if (homeCache != null) return
        runCatching { getHome() }
            .onFailure { error ->
                Log.d(TAG, "Home warm-up unavailable; continuing metadata request: ${error.message}")
            }
    }

    private fun shapeHomeForLayout(categories: List<Category>): List<Category> {
        if (!BuildConfig.APP_LAYOUT.equals("tv", ignoreCase = true)) return categories

        return categories.asSequence()
            .filter { it.list.isNotEmpty() }
            .take(TV_MAX_HOME_ROWS)
            .map { category ->
                val limit = if (category.name == Category.FEATURED) {
                    TV_MAX_FEATURED_ITEMS
                } else {
                    TV_MAX_ROW_ITEMS
                }
                category.copy(list = category.list.take(limit))
            }
            .toList()
    }

    private suspend fun syncDomainPreference() {
        val current = UserPreferences.streamingcommunityDomain
        if (current == observedDomain) return

        recoveryMutex.withLock {
            val lockedCurrent = UserPreferences.streamingcommunityDomain
            if (lockedCurrent == observedDomain) return@withLock

            Log.i(TAG, "StreamingCommunity domain preference changed: $observedDomain -> $lockedCurrent")
            rebuildDelegateForPreference(lockedCurrent)
            observedDomain = UserPreferences.streamingcommunityDomain
            clearCaches()
            lastRecoveryAtMs = System.currentTimeMillis()
        }
    }

    private suspend fun <T> withRecovery(
        operation: String,
        block: suspend () -> T,
    ): T {
        syncDomainPreference()
        return try {
            block().also { captureResolvedDomain() }
        } catch (e: Exception) {
            if (!isRecoverableStreamingCommunityError(e)) throw e

            Log.w(TAG, "Recoverable StreamingCommunity failure in $operation: ${e.message}")
            performRecovery(operation)
            block().also { captureResolvedDomain() }
        }
    }

    private suspend fun performRecovery(operation: String, force: Boolean = false) {
        recoveryMutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && now - lastRecoveryAtMs < RECOVERY_COOLDOWN_MS) {
                Log.d(TAG, "Reusing recent recovery for $operation")
                return@withLock
            }

            Log.i(TAG, "Rebuilding StreamingCommunity service for $operation")
            rebuildDelegateForPreference(UserPreferences.streamingcommunityDomain)
            captureResolvedDomain()
            clearCaches()
            lastRecoveryAtMs = System.currentTimeMillis()
        }
    }

    private suspend fun rebuildDelegateForPreference(preferredDomain: String) {
        if (preferredDomain.isBlank()) {
            delegate.rebuildService()
        } else {
            delegate.rebuildService(preferredDomain)
        }
    }

    private fun captureResolvedDomain() {
        val resolvedHost = delegate.logo.toHttpUrlOrNull()?.host
            ?.takeIf { it.isNotBlank() }
            ?: return
        if (resolvedHost == observedDomain && resolvedHost == UserPreferences.streamingcommunityDomain) {
            return
        }

        observedDomain = resolvedHost
        if (UserPreferences.streamingcommunityDomain != resolvedHost) {
            Log.i(TAG, "Persisting resolved StreamingCommunity host: $resolvedHost")
            UserPreferences.streamingcommunityDomain = resolvedHost
        }
    }

    private fun mutexFor(key: String): Mutex {
        val positiveHash = key.hashCode() and Int.MAX_VALUE
        return detailMutexes[positiveHash % detailMutexes.size]
    }

    private fun <T> putBounded(
        cache: ConcurrentHashMap<String, TimedValue<T>>,
        key: String,
        value: TimedValue<T>,
    ) {
        if (cache.size >= MAX_METADATA_CACHE_ENTRIES && !cache.containsKey(key)) {
            cache.clear()
        }
        cache[key] = value
    }

    private fun clearCaches() {
        homeCache = null
        movieCache.clear()
        tvShowCache.clear()
        seasonCache.clear()
    }
}

internal fun isRecoverableStreamingCommunityError(error: Throwable): Boolean {
    val statusCode = (error as? HttpException)?.code()
    if (statusCode != null) {
        return statusCode in setOf(401, 403, 404, 408, 409, 410, 425, 429, 500, 502, 503, 504)
    }

    if (
        error is SocketTimeoutException ||
        error is ConnectException ||
        error is UnknownHostException ||
        error is SSLHandshakeException ||
        error is CertPathValidatorException
    ) {
        return true
    }

    if (error is IOException) {
        val message = error.message.orEmpty()
        return RECOVERABLE_HTTP_TEXT.containsMatchIn(message) ||
            message.contains("timeout", ignoreCase = true) ||
            message.contains("connection", ignoreCase = true)
    }

    return false
}

private val RECOVERABLE_HTTP_TEXT = Regex(
    """(?:HTTP|status|response)[^0-9]*(401|403|404|408|409|410|425|429|500|502|503|504)\b""",
    RegexOption.IGNORE_CASE,
)
