package com.streamflixreborn.streamflix.sync

import android.content.Context
import android.net.Uri
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.utils.ProfileManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SupabaseProvider {
    private const val PREFS = "supabase_connection"
    private const val URL_KEY = "url"
    private const val PUBLIC_KEY = "public_key"
    private const val SESSION_KEY = "streamflix_supabase_session"
    private val clientsMutex = Mutex()

    private data class ProfileClient(
        val fingerprint: String,
        val client: SupabaseClient,
    )

    private val clients = mutableMapOf<String, ProfileClient>()

    val isConfigured: Boolean
        get() = readConfig(StreamFlixApp.instance)?.let { it.first.isNotEmpty() } == true

    fun clientOrNull(profileId: String): SupabaseClient? = clients[profileId]?.client

    @Deprecated("Use clientFor with an explicit profile ID")
    val client: SupabaseClient
        get() = clientOrNull(ProfileManager.activeProfileId ?: "default")
            ?: error("Supabase has not been initialized for the active profile")

    @Deprecated("Use clientOrNull with an explicit profile ID")
    fun activeClientOrNull(): SupabaseClient? =
        clientOrNull(ProfileManager.activeProfileId ?: "default")

    fun configured(context: Context): Boolean = readConfig(context) != null

    fun getUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(URL_KEY, "").orEmpty()

    fun getPublicKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PUBLIC_KEY, "").orEmpty()

    fun saveConfig(context: Context, url: String, publicKey: String) {
        val normalizedUrl = normalizeUrl(url)
            ?: throw IllegalArgumentException("Enter a valid HTTPS Supabase URL")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(URL_KEY, normalizedUrl)
            .putString(PUBLIC_KEY, publicKey.trim())
            .apply()
        kotlinx.coroutines.runBlocking {
            CloudRealtimeSync.stop()
            clientsMutex.withLock {
                clients.values.forEach { runCatching { it.client.close() } }
                clients.clear()
            }
        }
    }

    suspend fun clientFor(context: Context, profileId: String): SupabaseClient {
        val config = readConfig(context)
            ?: error("Configure Supabase in Settings > Account & sync before signing in")
        val fingerprint = config.first + "\u0000" + config.second
        clients[profileId]?.takeIf { it.fingerprint == fingerprint }?.let { return it.client }
        return clientsMutex.withLock {
            clients[profileId]?.takeIf { it.fingerprint == fingerprint }?.let { return@withLock it.client }
            val client = createSupabaseClient(
                supabaseUrl = config.first,
                supabaseKey = config.second,
            ) {
                install(Auth) {
                    sessionManager = SettingsSessionManager(
                        key = sessionKey(profileId, fingerprint),
                    )
                }
                install(Postgrest)
                install(Realtime)
            }
            clients[profileId] = ProfileClient(fingerprint, client)
            client
        }
    }

    /**
     * Initializes the active profile when a connection has been configured.
     *
     * Startup is allowed to run without Supabase. Callers that explicitly need
     * Supabase should continue to use [clientFor], which reports the actionable
     * configuration error.
     */
    suspend fun initialize(context: Context): SupabaseClient? {
        if (readConfig(context) == null) return null
        return clientFor(context, ProfileManager.activeProfileId ?: "default")
    }

    suspend fun removeProfile(profileId: String) {
        clientsMutex.withLock {
            clients.remove(profileId)?.client?.let { runCatching { it.close() } }
        }
    }

    suspend fun clearConfig(context: Context) {
        CloudRealtimeSync.stop()
        clientsMutex.withLock {
            clients.values.forEach { runCatching { it.client.close() } }
            clients.clear()
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun readConfig(context: Context): Pair<String, String>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = normalizeUrl(prefs.getString(URL_KEY, null)) ?: return null
        val key = prefs.getString(PUBLIC_KEY, null)?.trim().orEmpty()
        if (key.isEmpty()) return null
        return url to key
    }

    private fun normalizeUrl(raw: String?): String? {
        val parsed = raw?.trim()?.let(Uri::parse) ?: return null
        if (parsed.scheme != "https" || parsed.host.isNullOrBlank()) return null
        return raw.trim().trimEnd('/')
    }

    private fun sessionKey(profileId: String, fingerprint: String): String {
        val legacy = "$SESSION_KEY-${fingerprint.hashCode()}"
        return if (profileId == "default") legacy else "$legacy-$profileId"
    }
}
