package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.fragments.player.settings.PlayerSettingsView
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.Provider.Companion.providers
import com.streamflixreborn.streamflix.providers.TmdbProvider
import androidx.core.content.edit
import com.streamflixreborn.streamflix.database.AppDatabase
import org.json.JSONObject
import org.json.JSONArray

object UserPreferences {

    private const val TAG = "UserPrefsDebug"

    private lateinit var prefs: SharedPreferences
    private lateinit var globalPrefs: SharedPreferences

    @Volatile
    var profilePrefs: SharedPreferences? = null
        set(value) {
            field = value
            if (value != null) {
                prefs = value
                debugLog { "Switched to profile-specific prefs" }
            }
        }

    @Volatile
    internal var _profileManagerReady: Boolean = false

    private val effectivePrefs: SharedPreferences
        get() = if (::prefs.isInitialized) prefs else throw IllegalStateException("UserPreferences not initialized")

    private val effectiveGlobalPrefs: SharedPreferences
        get() = if (::globalPrefs.isInitialized) globalPrefs else throw IllegalStateException("UserPreferences not initialized")

    // Default DoH Provider URL (Cloudflare)
    private const val DEFAULT_DOH_PROVIDER_URL = "https://cloudflare-dns.com/dns-query"
    const val DOH_DISABLED_VALUE = "" // Value to represent DoH being disabled
    private const val DEFAULT_SERIENSTREAM_DOMAIN = "serienstream.to"
    private const val DEFAULT_MOFLIX_DOMAIN = "moflix-stream.xyz"
    private const val DEFAULT_STREAMINGCOMMUNITY_DOMAIN = "streamingunity.cc"
    private const val DEFAULT_CUEVANA_DOMAIN = "cuevana.gs"
    private const val DEFAULT_POSEIDON_DOMAIN = "www.poseidonhd2.co"

    const val PROVIDER_URL = "URL"
    const val PROVIDER_LOGO = "LOGO"
    const val PROVIDER_PORTAL_URL = "PORTAL_URL"
    const val PROVIDER_AUTOUPDATE = "AUTOUPDATE_URL"
    const val PROVIDER_NEW_INTERFACE = "NEW_INTERFACE"
    const val PROVIDER_PREFERRED_SERVER = "PREFERRED_SERVER"

    lateinit var providerCache: JSONObject

    private inline fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message())
        }
    }

    fun setup(context: Context) {
        val prefsName = "${BuildConfig.APPLICATION_ID}.preferences"
        globalPrefs = context.getSharedPreferences(
            "${BuildConfig.APPLICATION_ID}.global_preferences",
            Context.MODE_PRIVATE,
        )

        prefs = context.getSharedPreferences(
            prefsName,
            Context.MODE_PRIVATE,
        )

        if (::prefs.isInitialized) {
            debugLog { "prefs initialized: ${prefs.hashCode()}" }

            val jsonString = Key.PROVIDER_CACHE.getString() ?: "{}"
            providerCache = runCatching { JSONObject(jsonString) }.getOrDefault(JSONObject())
        }
    }

    var profileId: String?
        get() = effectiveGlobalPrefs.getString("CURRENT_PROFILE_ID", null)
        set(value) {
            effectiveGlobalPrefs.edit().putString("CURRENT_PROFILE_ID", value).apply()
        }

    var currentProvider: Provider?
        get() {
            val providerName = Key.CURRENT_PROVIDER.getString()
            if (providerName?.startsWith("TMDb (") == true && providerName.endsWith(")")) {
                val lang = providerName.substringAfter("TMDb (").substringBefore(")")
                return TmdbProvider(lang)
            }
            return Provider.providers.keys.find { it.name == providerName }
        }
        set(value) {
            AppDatabase.resetInstance()

            Key.CURRENT_PROVIDER.setString(value?.name)
            runCatching {
                ArtworkRepairScheduler.schedule(StreamFlixApp.instance, value)
            }
            ProviderChangeNotifier.notifyProviderChanged()
        }

    internal fun getCurrentProviderName(): String? = Key.CURRENT_PROVIDER.getString()

    internal fun setCurrentProviderName(name: String?) {
        Key.CURRENT_PROVIDER.setString(name)
    }

    fun getProviderCache(provider: Provider, key: String): String {
        return providerCache
            .optJSONObject(provider.name)
            ?.optString(key)
            .orEmpty()
    }

    fun setProviderCache(provider: Provider?, key: String, value: String) {
        val providerName = provider?.name ?: currentProvider?.name ?: return
        val innerJson = providerCache.optJSONObject(providerName)
            ?: JSONObject().also { providerCache.put(providerName, it) }
        innerJson.put(key, value)
        Key.PROVIDER_CACHE.setString(providerCache.toString())
    }

    fun clearProviderCache(providerName: String) {
        if (providerCache.has(providerName)) {
            debugLog { "CACHE: removing stored data for $providerName" }
            providerCache.remove(providerName)
            Key.PROVIDER_CACHE.setString(providerCache.toString())
        }
    }

    var currentLanguage: String?
        get() = Key.CURRENT_LANGUAGE.getString()
        set(value) = Key.CURRENT_LANGUAGE.setString(value)

    var providerLanguage: String?
        get() = Key.PROVIDER_LANGUAGE.getString()
        set(value) = Key.PROVIDER_LANGUAGE.setString(value)

    var captionTextSize: Float
        get() = Key.CAPTION_TEXT_SIZE.getFloat()
            ?: PlayerSettingsView.Settings.Subtitle.Style.TextSize.DEFAULT.value
        set(value) {
            Key.CAPTION_TEXT_SIZE.setFloat(value)
        }

    var autoplay: Boolean
        get() = Key.AUTOPLAY.getBoolean() ?: true
        set(value) {
            Key.AUTOPLAY.setBoolean(value)
        }

    var keepScreenOnWhenPaused: Boolean
        get() = Key.KEEP_SCREEN_ON_WHEN_PAUSED.getBoolean() ?: false
        set(value) {
            Key.KEEP_SCREEN_ON_WHEN_PAUSED.setBoolean(value)
        }

    var playerGestures: Boolean
        get() = Key.PLAYER_GESTURES.getBoolean() ?: true
        set(value) {
            Key.PLAYER_GESTURES.setBoolean(value)
        }

    var immersiveMode: Boolean
        get() = Key.IMMERSIVE_MODE.getBoolean() ?: false
        set(value) {
            Key.IMMERSIVE_MODE.setBoolean(value)
        }

    var forceExtraBuffering: Boolean
        get() = Key.FORCE_EXTRA_BUFFERING.getBoolean() ?: false
        set(value) {
            Key.FORCE_EXTRA_BUFFERING.setBoolean(value)
        }

    var autoplayBuffer: Long
        get() = Key.AUTOPLAY_BUFFER.getLong() ?: 3L
        set(value) {
            Key.AUTOPLAY_BUFFER.setLong(value)
        }

    var serverAutoSubtitlesDisabled: Boolean
        get() = Key.SERVER_AUTO_SUBTITLES_DISABLED.getBoolean() ?: true
        set(value) {
            Key.SERVER_AUTO_SUBTITLES_DISABLED.setBoolean(value)
        }

    var selectedTheme: String
        get() = Key.SELECTED_THEME.getString() ?: "default"
        set(value) = Key.SELECTED_THEME.setString(value)

    var tmdbApiKey: String
        get() = Key.TMDB_API_KEY.getString() ?: ""
        set(value) {
            Key.TMDB_API_KEY.setString(value)
            TMDb3.rebuildService()
        }
    var enableTmdb: Boolean
        get() = Key.ENABLE_TMDB.getBoolean() ?: true
        set(value) {
            Key.ENABLE_TMDB.setBoolean(value)
            TMDb3.rebuildService()
            if (value) {
                runCatching {
                    ArtworkRepairScheduler.schedule(StreamFlixApp.instance, currentProvider)
                }
            }
        }

    var parentalControlPin: String
        get() = Key.PARENTAL_CONTROL_PIN.getString() ?: ""
        set(value) {
            Key.PARENTAL_CONTROL_PIN.setString(value.trim())
        }

    var parentalControlAdminPin: String
        get() = Key.PARENTAL_CONTROL_ADMIN_PIN.getString() ?: ""
        set(value) {
            Key.PARENTAL_CONTROL_ADMIN_PIN.setString(value.trim())
        }

    var parentalControlMaxAge: Int?
        get() = Key.PARENTAL_CONTROL_MAX_AGE.getInt()
        set(value) {
            Key.PARENTAL_CONTROL_MAX_AGE.setInt(value)
        }

    var parentalControlFailedAttempts: Int
        get() = Key.PARENTAL_CONTROL_FAILED_ATTEMPTS.getInt() ?: 0
        set(value) {
            Key.PARENTAL_CONTROL_FAILED_ATTEMPTS.setInt(value)
        }

    var parentalControlLockedUntilMillis: Long
        get() = Key.PARENTAL_CONTROL_LOCKED_UNTIL.getLong() ?: 0L
        set(value) {
            Key.PARENTAL_CONTROL_LOCKED_UNTIL.setLong(value)
        }

    var parentalControlHardLocked: Boolean
        get() = Key.PARENTAL_CONTROL_HARD_LOCKED.getBoolean() ?: false
        set(value) {
            Key.PARENTAL_CONTROL_HARD_LOCKED.setBoolean(value)
        }

    val isParentalControlActive: Boolean
        get() = enableTmdb && parentalControlPin.isNotBlank() && parentalControlMaxAge != null

    val isParentalControlTemporarilyLocked: Boolean
        get() = parentalControlLockedUntilMillis > System.currentTimeMillis()

    val parentalControlLockRemainingMillis: Long
        get() = (parentalControlLockedUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)

    fun registerParentalPinSuccess() {
        parentalControlFailedAttempts = 0
        parentalControlLockedUntilMillis = 0L
        parentalControlHardLocked = false
    }

    fun registerParentalPinFailure(nowMillis: Long = System.currentTimeMillis()) {
        val attempts = parentalControlFailedAttempts + 1
        parentalControlFailedAttempts = attempts

        when {
            attempts >= 7 && parentalControlAdminPin.isNotBlank() -> {
                parentalControlHardLocked = true
                parentalControlLockedUntilMillis = 0L
            }
            attempts >= 7 -> {
                parentalControlLockedUntilMillis = nowMillis + 24L * 60L * 60L * 1000L
            }
            attempts >= 5 -> {
                parentalControlLockedUntilMillis = nowMillis + 30L * 60L * 1000L
            }
            attempts >= 3 -> {
                parentalControlLockedUntilMillis = nowMillis + 5L * 60L * 1000L
            }
        }
    }

    var updateCheckEnabled: Boolean
        get() = effectiveGlobalPrefs.getBoolean("UPDATE_CHECK_ENABLED", true)
        set(value) {
            effectiveGlobalPrefs.edit().putBoolean("UPDATE_CHECK_ENABLED", value).apply()
        }

    fun unlockParentalControls() {
        parentalControlFailedAttempts = 0
        parentalControlLockedUntilMillis = 0L
        parentalControlHardLocked = false
    }

    var subdlApiKey: String
        get() = Key.SUBDL_API_KEY.getString() ?: ""
        set(value) {
            Key.SUBDL_API_KEY.setString(value)
        }

    var bypassWsAdvertisedHost: String
        get() = Key.BYPASS_WS_ADVERTISED_HOST.getString() ?: ""
        set(value) {
            Key.BYPASS_WS_ADVERTISED_HOST.setString(value.trim())
        }

    enum class PlayerResize(
        val stringRes: Int,
        val resizeMode: Int,
    ) {
        Fit(R.string.player_aspect_ratio_fit, AspectRatioFrameLayout.RESIZE_MODE_FIT),
        Fill(R.string.player_aspect_ratio_fill, AspectRatioFrameLayout.RESIZE_MODE_FILL),
        Zoom(R.string.player_aspect_ratio_zoom, AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
        Stretch43(R.string.player_aspect_ratio_zoom_4_3, AspectRatioFrameLayout.RESIZE_MODE_FIT),
        StretchVertical(R.string.player_aspect_ratio_stretch_vertical, AspectRatioFrameLayout.RESIZE_MODE_FIT),
        SuperZoom(R.string.player_aspect_ratio_super_zoom, AspectRatioFrameLayout.RESIZE_MODE_FIT);
    }

    var playerResize: PlayerResize
        get() = PlayerResize.entries.find { it.resizeMode == Key.PLAYER_RESIZE.getInt() && it.name == Key.PLAYER_RESIZE_NAME.getString() }
            ?: PlayerResize.entries.find { it.resizeMode == Key.PLAYER_RESIZE.getInt() }
            ?: PlayerResize.Fit
        set(value) {
            Key.PLAYER_RESIZE.setInt(value.resizeMode)
            Key.PLAYER_RESIZE_NAME.setString(value.name)
        }

    var captionStyle: CaptionStyleCompat
        get() = CaptionStyleCompat(
            Key.CAPTION_STYLE_FONT_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.foregroundColor,
            Key.CAPTION_STYLE_BACKGROUND_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.backgroundColor,
            Key.CAPTION_STYLE_WINDOW_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.windowColor,
            Key.CAPTION_STYLE_EDGE_TYPE.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.edgeType,
            Key.CAPTION_STYLE_EDGE_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.edgeColor,
            PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.typeface
        )
        set(value) {
            Key.CAPTION_STYLE_FONT_COLOR.setInt(value.foregroundColor)
            Key.CAPTION_STYLE_BACKGROUND_COLOR.setInt(value.backgroundColor)
            Key.CAPTION_STYLE_WINDOW_COLOR.setInt(value.windowColor)
            Key.CAPTION_STYLE_EDGE_TYPE.setInt(value.edgeType)
            Key.CAPTION_STYLE_EDGE_COLOR.setInt(value.edgeColor)
        }

    var captionMargin: Int
        get() = Key.CAPTION_STYLE_MARGIN.getInt() ?: 24
        set(value) {
            Key.CAPTION_STYLE_MARGIN.setInt(value)
        }

    var qualityHeight: Int?
        get() = Key.QUALITY_HEIGHT.getInt()
        set(value) {
            Key.QUALITY_HEIGHT.setInt(value)
        }

    var subtitleName: String?
        get() = Key.SUBTITLE_NAME.getString()
        set(value) = Key.SUBTITLE_NAME.setString(value)
    var streamingcommunityDomain: String
        get() {
            if (!::prefs.isInitialized) {
                Log.e(TAG, "streamingcommunityDomain GET: prefs is not initialized")
                return DEFAULT_STREAMINGCOMMUNITY_DOMAIN
            }
            val storedValue = prefs.getString(Key.STREAMINGCOMMUNITY_DOMAIN.name, null)
            return if (storedValue.isNullOrEmpty()) {
                DEFAULT_STREAMINGCOMMUNITY_DOMAIN
            } else {
                storedValue
            }
        }
        set(value) {
            val oldDomain = if (::prefs.isInitialized) prefs.getString(Key.STREAMINGCOMMUNITY_DOMAIN.name, null) else null
            if (!::prefs.isInitialized) {
                Log.e(TAG, "streamingcommunityDomain SET: prefs is not initialized")
                return
            }

            if (value != oldDomain && !value.isNullOrEmpty() && !oldDomain.isNullOrEmpty()) {
                clearProviderCache("StreamingCommunity")
            }

            with(prefs.edit()) {
                if (value.isNullOrEmpty()) {
                    remove(Key.STREAMINGCOMMUNITY_DOMAIN.name)
                } else {
                    putString(Key.STREAMINGCOMMUNITY_DOMAIN.name, value)
                }
                apply()
            }
        }

    var serienstreamDomain: String
        get() {
            if (!::prefs.isInitialized) return DEFAULT_SERIENSTREAM_DOMAIN
            val storedValue = prefs.getString(Key.SERIENSTREAM_DOMAIN.name, null)
            return if (storedValue.isNullOrEmpty()) DEFAULT_SERIENSTREAM_DOMAIN else storedValue
        }
        set(value) {
            val oldDomain = if (::prefs.isInitialized) prefs.getString(Key.SERIENSTREAM_DOMAIN.name, null) else null
            if (!::prefs.isInitialized) return

            if (value != oldDomain && !value.isNullOrEmpty() && !oldDomain.isNullOrEmpty()) {
                clearProviderCache("SerienStream")
            }

            with(prefs.edit()) {
                if (value.isNullOrEmpty()) {
                    remove(Key.SERIENSTREAM_DOMAIN.name)
                } else {
                    putString(Key.SERIENSTREAM_DOMAIN.name, value)
                }
                apply()
            }
        }

    var cuevanaDomain: String
        get() {
            if (!::prefs.isInitialized) return DEFAULT_CUEVANA_DOMAIN
            val storedValue = prefs.getString(Key.CUEVANA_DOMAIN.name, null)
            return if (storedValue.isNullOrEmpty()) DEFAULT_CUEVANA_DOMAIN else storedValue
        }
        set(value) {
            val oldDomain = if (::prefs.isInitialized) prefs.getString(Key.CUEVANA_DOMAIN.name, null) else null
            if (!::prefs.isInitialized) return

            if (value != oldDomain && !value.isNullOrEmpty() && !oldDomain.isNullOrEmpty()) {
                clearProviderCache("Cuevana 3")
            }

            with(prefs.edit()) {
                if (value.isNullOrEmpty()) {
                    remove(Key.CUEVANA_DOMAIN.name)
                } else {
                    putString(Key.CUEVANA_DOMAIN.name, value)
                }
                apply()
            }
        }

    var poseidonDomain: String
        get() {
            if (!::prefs.isInitialized) return DEFAULT_POSEIDON_DOMAIN
            val storedValue = prefs.getString(Key.POSEIDON_DOMAIN.name, null)
            return if (storedValue.isNullOrEmpty()) DEFAULT_POSEIDON_DOMAIN else storedValue
        }
        set(value) {
            val oldDomain = if (::prefs.isInitialized) prefs.getString(Key.POSEIDON_DOMAIN.name, null) else null
            if (!::prefs.isInitialized) return

            if (value != oldDomain && !value.isNullOrEmpty() && !oldDomain.isNullOrEmpty()) {
                clearProviderCache("Poseidonhd2")
            }

            with(prefs.edit()) {
                if (value.isNullOrEmpty()) {
                    remove(Key.POSEIDON_DOMAIN.name)
                } else {
                    putString(Key.POSEIDON_DOMAIN.name, value)
                }
                apply()
            }
        }

    var moflixDomain: String
        get() {
            if (!::prefs.isInitialized) return DEFAULT_MOFLIX_DOMAIN
            val storedValue = prefs.getString(Key.MOFLIX_DOMAIN.name, null)
            return if (storedValue.isNullOrEmpty()) DEFAULT_MOFLIX_DOMAIN else storedValue
        }
        set(value) {
            if (!::prefs.isInitialized) return

            with(prefs.edit()) {
                if (value.isNullOrEmpty()) {
                    remove(Key.MOFLIX_DOMAIN.name)
                } else {
                    putString(Key.MOFLIX_DOMAIN.name, value)
                }
                apply()
            }
        }

    var dohProviderUrl: String
        get() = Key.DOH_PROVIDER_URL.getString() ?: DEFAULT_DOH_PROVIDER_URL
        set(value) {
            Key.DOH_PROVIDER_URL.setString(value)
            DnsResolver.setDnsUrl(value)
        }

    var paddingX: Int
        get() = Key.SCREEN_PADDING_X.getInt() ?: 0
        set(value) = Key.SCREEN_PADDING_X.setInt(value)

    var paddingY: Int
        get() = Key.SCREEN_PADDING_Y.getInt() ?: 0
        set(value) = Key.SCREEN_PADDING_Y.setInt(value)

    var favoriteProviders: Set<String>
        get() = Key.FAVORITE_PROVIDERS.getStringSet() ?: emptySet()
        set(value) {
            Key.FAVORITE_PROVIDERS.setStringSet(value)
        }

    fun getFavoriteCategoryOrder(providerName: String): List<String> {
        val key = "FAVORITE_CATEGORY_ORDER_$providerName"
        val saved = prefs.getString(key, null)
            ?.split(',')
            ?.filter { it == "movies" || it == "tv_shows" }
            .orEmpty()
        return (saved + listOf("movies", "tv_shows")).distinct()
    }

    fun setFavoriteCategoryOrder(providerName: String, order: List<String>) {
        val normalized = (order.filter { it == "movies" || it == "tv_shows" } +
            listOf("movies", "tv_shows")).distinct()
        prefs.edit { putString("FAVORITE_CATEGORY_ORDER_$providerName", normalized.joinToString(",")) }
    }

    fun getFavoriteItemOrder(providerName: String, section: String): List<String> {
        val raw = prefs.getString("FAVORITE_ITEM_ORDER_${providerName}_$section", null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            List(json.length()) { index -> json.getString(index) }
        }.getOrDefault(emptyList())
    }

    fun setFavoriteItemOrder(providerName: String, section: String, order: List<String>) {
        prefs.edit {
            putString("FAVORITE_ITEM_ORDER_${providerName}_$section", JSONArray(order).toString())
        }
    }

    fun getFavoriteSortMode(providerName: String): String =
        prefs.getString("FAVORITE_SORT_MODE_$providerName", "manual") ?: "manual"

    fun setFavoriteSortMode(providerName: String, mode: String) {
        prefs.edit { putString("FAVORITE_SORT_MODE_$providerName", mode) }
    }

    private enum class Key {
        APP_LAYOUT,
        CURRENT_LANGUAGE,
        CURRENT_PROVIDER,
        PLAYER_RESIZE,
        PLAYER_RESIZE_NAME,
        CAPTION_TEXT_SIZE,
        CAPTION_STYLE_FONT_COLOR,
        CAPTION_STYLE_BACKGROUND_COLOR,
        CAPTION_STYLE_WINDOW_COLOR,
        CAPTION_STYLE_EDGE_TYPE,
        CAPTION_STYLE_EDGE_COLOR,
        CAPTION_STYLE_MARGIN,
        SCREEN_PADDING_X,
        SCREEN_PADDING_Y,
        QUALITY_HEIGHT,
        SUBTITLE_NAME,
        SERIENSTREAM_DOMAIN,
        MOFLIX_DOMAIN,
        STREAMINGCOMMUNITY_DOMAIN,
        CUEVANA_DOMAIN,
        POSEIDON_DOMAIN,
        DOH_PROVIDER_URL,
        AUTOPLAY,
        PROVIDER_CACHE,
        KEEP_SCREEN_ON_WHEN_PAUSED,
        PLAYER_GESTURES,
        IMMERSIVE_MODE,
        TMDB_API_KEY,
        SUBDL_API_KEY,
        FORCE_EXTRA_BUFFERING,
        AUTOPLAY_BUFFER,
        SERVER_AUTO_SUBTITLES_DISABLED,
        ENABLE_TMDB,
        PARENTAL_CONTROL_PIN,
        PARENTAL_CONTROL_ADMIN_PIN,
        PARENTAL_CONTROL_MAX_AGE,
        PARENTAL_CONTROL_FAILED_ATTEMPTS,
        PARENTAL_CONTROL_LOCKED_UNTIL,
        PARENTAL_CONTROL_HARD_LOCKED,
        SELECTED_THEME,
        BYPASS_WS_ADVERTISED_HOST,
        UPDATE_CHECK_ENABLED,
        PROVIDER_LANGUAGE,
        FAVORITE_PROVIDERS;

        private fun getPrefs(): SharedPreferences {
            val up = UserPreferences
            return if (up::prefs.isInitialized) up.prefs else throw IllegalStateException("UserPreferences not initialized")
        }

        fun getStringSet(): Set<String>? = when {
            getPrefs().contains(name) -> getPrefs().getStringSet(name, null)
            else -> null
        }

        fun setStringSet(value: Set<String>?) = value?.let {
            with(getPrefs().edit()) {
                putStringSet(name, value)
                apply()
            }
        } ?: remove()

        fun getBoolean(): Boolean? = when {
            getPrefs().contains(name) -> getPrefs().getBoolean(name, false)
            else -> null
        }

        fun getFloat(): Float? = when {
            getPrefs().contains(name) -> getPrefs().getFloat(name, 0F)
            else -> null
        }

        fun getInt(): Int? = when {
            getPrefs().contains(name) -> getPrefs().getInt(name, 0)
            else -> null
        }

        fun getLong(): Long? = when {
            getPrefs().contains(name) -> getPrefs().getLong(name, 0)
            else -> null
        }

        fun getString(): String? = when {
            getPrefs().contains(name) -> getPrefs().getString(name, null)
            else -> null
        }

        fun setBoolean(value: Boolean?) = value?.let {
            with(getPrefs().edit()) {
                putBoolean(name, value)
                apply()
            }
        } ?: remove()

        fun setFloat(value: Float?) = value?.let {
            with(getPrefs().edit()) {
                putFloat(name, value)
                apply()
            }
        } ?: remove()

        fun setInt(value: Int?) = value?.let {
            with(getPrefs().edit()) {
                putInt(name, value)
                apply()
            }
        } ?: remove()

        fun setLong(value: Long?) = value?.let {
            with(getPrefs().edit()) {
                putLong(name, value)
                apply()
            }
        } ?: remove()

        fun setString(value: String?) = value?.let {
            with(getPrefs().edit()) {
                putString(name, value)
                apply()
            }
        } ?: remove()

        fun remove() = with(getPrefs().edit()) {
            remove(name)
            apply()
        }
    }
}
