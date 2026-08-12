package com.streamflixreborn.streamflix.sync

import android.content.Context
import android.net.Uri
import com.streamflixreborn.streamflix.StreamFlixApp
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

    @Volatile
    private var clientInstance: SupabaseClient? = null
    @Volatile
    private var clientFingerprint: String? = null

    val isConfigured: Boolean
        get() = readConfig(StreamFlixApp.instance)?.let { it.first.isNotEmpty() } == true

    val client: SupabaseClient
        get() = clientInstance ?: error("Supabase has not been initialized")

    fun activeClientOrNull(): SupabaseClient? = clientInstance

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
        clientInstance = null
        clientFingerprint = null
    }

    suspend fun initialize(context: Context) {
        val config = readConfig(context)
            ?: return
        val fingerprint = config.first + "\u0000" + config.second
        clientInstance?.takeIf { clientFingerprint == fingerprint }?.let { return }
        clientsMutex.withLock {
            clientInstance?.takeIf { clientFingerprint == fingerprint }?.let { return@withLock }
            createSupabaseClient(
                supabaseUrl = config.first,
                supabaseKey = config.second,
            ) {
                install(Auth) {
                    sessionManager = SettingsSessionManager(
                        key = "$SESSION_KEY-${fingerprint.hashCode()}",
                    )
                }
                install(Postgrest)
                install(Realtime)
            }.also {
                clientInstance = it
                clientFingerprint = fingerprint
            }
        }
    }

    suspend fun clearConfig(context: Context) {
        clientInstance?.close()
        clientInstance = null
        clientFingerprint = null
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
}
