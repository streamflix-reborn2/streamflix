package com.streamflixreborn.streamflix.fragments.settings

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.Group
import androidx.leanback.preference.LeanbackPreferenceFragmentCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreference
import androidx.preference.SwitchPreferenceCompat
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.activities.main.MainTvActivity
import com.streamflixreborn.streamflix.backup.BackupRestoreManager
import com.streamflixreborn.streamflix.backup.ProviderBackupContext
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.database.dao.EpisodeDao
import com.streamflixreborn.streamflix.database.dao.MovieDao
import com.streamflixreborn.streamflix.database.dao.TvShowDao
import com.streamflixreborn.streamflix.database.dao.SeasonDao
import com.streamflixreborn.streamflix.providers.AnimeOnlineNinjaProvider
import com.streamflixreborn.streamflix.providers.FrenchStreamProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.ProviderConfigUrl
import com.streamflixreborn.streamflix.providers.ProviderPortalUrl
import com.streamflixreborn.streamflix.providers.MStreamProvider
import com.streamflixreborn.streamflix.providers.SerienStreamProvider
import com.streamflixreborn.streamflix.providers.StreamingCommunityProvider
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.utils.BypassWebSocketEndpointHelper
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import com.streamflixreborn.streamflix.utils.QrUtils
import com.streamflixreborn.streamflix.models.Profile
import com.streamflixreborn.streamflix.utils.ProfileColorPicker
import com.streamflixreborn.streamflix.utils.ProfileManager
import com.streamflixreborn.streamflix.utils.ProfileSwitchPinGuard
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserDataCache
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.WebSocketBypassTestHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsTvFragment : LeanbackPreferenceFragmentCompat() {
    private data class SettingsScreenState(
        val rootKey: String?,
        val title: String?,
    )

    private val DEFAULT_DOMAIN_VALUE = "streamingunity.cc"
    private val DEFAULT_SERIENSTREAM_DOMAIN_VALUE = "serienstream.to"
    private val DEFAULT_MOFLIX_DOMAIN_VALUE = "moflix-stream.xyz"
    private val DEFAULT_CUEVANA_DOMAIN_VALUE = "cuevana3.la"
    private val DEFAULT_POSEIDON_DOMAIN_VALUE = "www.poseidonhd2.co"
    private val PREFS_ERROR_VALUE = "PREFS_NOT_INIT_ERROR"
    private var currentScreenState = SettingsScreenState(rootKey = null, title = null)
    private val screenBackStack = ArrayDeque<SettingsScreenState>()
    private lateinit var settingsBackCallback: OnBackPressedCallback

    private lateinit var db: AppDatabase
    private lateinit var movieDao: MovieDao
    private lateinit var tvShowDao: TvShowDao
    private lateinit var episodeDao: EpisodeDao
    private lateinit var seasonDao: SeasonDao
    private lateinit var backupRestoreManager: BackupRestoreManager
    private var backupLoadingDialog: AlertDialog? = null

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            viewLifecycleOwner.lifecycleScope.launch {
                performBackupExport(it)
            }
        }
    }

    private val exportDbBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let {
            viewLifecycleOwner.lifecycleScope.launch {
                performDatabaseBackupExport(it)
            }
        }
    }

    private val importDbBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewLifecycleOwner.lifecycleScope.launch {
                performDatabaseBackupImport(it)
            }
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewLifecycleOwner.lifecycleScope.launch {
                performBackupImport(it)
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        currentScreenState = SettingsScreenState(rootKey = rootKey, title = null)
        renderCurrentScreen()

        db = AppDatabase.getInstance(requireContext())
        movieDao = db.movieDao()
        tvShowDao = db.tvShowDao()
        episodeDao = db.episodeDao()
        seasonDao = db.seasonDao()
        
        val allProvidersToBackup = Provider.providers.keys.toMutableList().apply {
            listOf("it", "en", "es", "de", "fr").forEach { lang ->
                add(TmdbProvider(lang))
            }
        }

        backupRestoreManager = BackupRestoreManager(
            requireContext(),
            allProvidersToBackup.mapNotNull { provider ->
                try {
                    val db = AppDatabase.getInstanceForProvider(provider.name, requireContext())
                    ProviderBackupContext(
                        name = provider.name,
                        movieDao = db.movieDao(),
                        tvShowDao = db.tvShowDao(),
                        episodeDao = db.episodeDao(),
                        seasonDao = db.seasonDao(),
                        provider = provider
                    )
                } catch (e: Exception) {
                    Log.w("BackupRestore", "Skipping ${provider.name}: ${e.message}")
                    null
                }
            }
        )


        displaySettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (screenBackStack.isEmpty()) return
                currentScreenState = screenBackStack.removeLast()
                settingsBackCallback.isEnabled = screenBackStack.isNotEmpty()
                renderCurrentScreen()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, settingsBackCallback)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference is PreferenceScreen && !preference.key.isNullOrBlank()) {
            screenBackStack.addLast(currentScreenState)
            currentScreenState = SettingsScreenState(
                rootKey = preference.key,
                title = preference.title?.toString(),
            )
            settingsBackCallback.isEnabled = screenBackStack.isNotEmpty()
            renderCurrentScreen()
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference.key == "PARENTAL_CONTROL_PIN" || preference.key == "PARENTAL_CONTROL_ADMIN_PIN") {
            return
        }
        super.onDisplayPreferenceDialog(preference)
    }

    private fun applyScreenTitle() {
        activity?.title = currentScreenState.title ?: getString(R.string.player_settings_title)
    }

    private fun renderCurrentScreen() {
        setPreferencesFromResource(R.xml.settings_tv, currentScreenState.rootKey)
        if (::backupRestoreManager.isInitialized) {
            displaySettings()
        }
        applyScreenTitle()
        view?.post { listView?.requestFocus() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        SettingsListStyler.attach(view, isTv = true)
        view.post { listView?.requestFocus() }
    }

    private fun displaySettings() {
        updateOverviewLabels()
        updateProviderVisibilityState()
        SupabaseSettingsController.bind(this, lifecycleScope) { key ->
            findPreference(key)
        }

        findPreference<EditTextPreference>("provider_streamingcommunity_domain")?.apply {
            val currentValue = UserPreferences.streamingcommunityDomain
            summary = currentValue
            if (currentValue == DEFAULT_DOMAIN_VALUE || currentValue == PREFS_ERROR_VALUE) {
                text = null
            } else {
                text = currentValue
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val typed = (newValue as String).trim()
                val BLOCKED = listOf("streamingcommunityz.green", "streamingunity.club", "streamingunity.bike", "streamingcommunityz.buzz")
                val effectiveDomain = if (BLOCKED.any { typed.contains(it) }) DEFAULT_DOMAIN_VALUE else typed
                UserPreferences.streamingcommunityDomain = effectiveDomain
                preference.summary = effectiveDomain
                if (effectiveDomain != typed) {
                    findPreference<EditTextPreference>("provider_streamingcommunity_domain")?.text = null
                    Toast.makeText(requireContext(), getString(R.string.settings_streamingcommunity_domain_blocked), Toast.LENGTH_LONG).show()
                }
                if (UserPreferences.currentProvider is StreamingCommunityProvider) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        (UserPreferences.currentProvider as StreamingCommunityProvider).rebuildService()
                        requireActivity().apply {
                            finish()
                            startActivity(Intent(this, this::class.java))
                        }
                    }
                }
                true
            }
        }

        findPreference<Preference>("provider_streamingcommunity_domain_reset")?.setOnPreferenceClickListener {
            UserPreferences.streamingcommunityDomain = DEFAULT_DOMAIN_VALUE
            findPreference<EditTextPreference>("provider_streamingcommunity_domain")?.apply {
                summary = DEFAULT_DOMAIN_VALUE
                text = null
            }
            Toast.makeText(requireContext(), getString(R.string.settings_streamingcommunity_domain_reset_done), Toast.LENGTH_SHORT).show()
            if (UserPreferences.currentProvider is StreamingCommunityProvider) {
                viewLifecycleOwner.lifecycleScope.launch {
                    (UserPreferences.currentProvider as StreamingCommunityProvider).rebuildService()
                    requireActivity().apply {
                        finish()
                        startActivity(Intent(this, this::class.java))
                    }
                }
            }
            true
        }

        findPreference<EditTextPreference>("provider_serienstream_domain")?.apply {
            val currentValue = UserPreferences.serienstreamDomain
            summary = currentValue
            if (currentValue == DEFAULT_SERIENSTREAM_DOMAIN_VALUE || currentValue == PREFS_ERROR_VALUE) {
                text = null
            } else {
                text = currentValue
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val typed = (newValue as String).trim()
                val effectiveDomain = typed.ifBlank { DEFAULT_SERIENSTREAM_DOMAIN_VALUE }
                UserPreferences.serienstreamDomain = effectiveDomain
                preference.summary = effectiveDomain
                if (UserPreferences.currentProvider is SerienStreamProvider) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        SerienStreamProvider.reloadService()
                        requireActivity().apply {
                            finish()
                            startActivity(Intent(this, this::class.java))
                        }
                    }
                }
                true
            }
        }

        findPreference<Preference>("provider_serienstream_domain_reset")?.setOnPreferenceClickListener {
            UserPreferences.serienstreamDomain = DEFAULT_SERIENSTREAM_DOMAIN_VALUE
            findPreference<EditTextPreference>("provider_serienstream_domain")?.apply {
                summary = DEFAULT_SERIENSTREAM_DOMAIN_VALUE
                text = null
            }
            Toast.makeText(requireContext(), getString(R.string.settings_serienstream_domain_reset_done), Toast.LENGTH_SHORT).show()
            if (UserPreferences.currentProvider is SerienStreamProvider) {
                viewLifecycleOwner.lifecycleScope.launch {
                    SerienStreamProvider.reloadService()
                    requireActivity().apply {
                        finish()
                        startActivity(Intent(this, this::class.java))
                    }
                }
            }
            true
        }

        findPreference<EditTextPreference>("provider_moflix_domain")?.apply {
            val currentValue = UserPreferences.moflixDomain
            summary = currentValue
            if (currentValue == DEFAULT_MOFLIX_DOMAIN_VALUE || currentValue == PREFS_ERROR_VALUE) {
                text = null
            } else {
                text = currentValue
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val typed = (newValue as String).trim()
                val effectiveDomain = typed.ifBlank { DEFAULT_MOFLIX_DOMAIN_VALUE }
                UserPreferences.moflixDomain = effectiveDomain
                preference.summary = effectiveDomain
                true
            }
        }

        findPreference<Preference>("provider_moflix_domain_reset")?.setOnPreferenceClickListener {
            UserPreferences.moflixDomain = DEFAULT_MOFLIX_DOMAIN_VALUE
            findPreference<EditTextPreference>("provider_moflix_domain")?.apply {
                summary = DEFAULT_MOFLIX_DOMAIN_VALUE
                text = null
            }
            Toast.makeText(requireContext(), getString(R.string.settings_moflix_domain_reset_done), Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<EditTextPreference>("provider_cuevana_domain")?.apply {
            val currentValue = UserPreferences.cuevanaDomain
            summary = currentValue
            if (currentValue == DEFAULT_CUEVANA_DOMAIN_VALUE) {
                text = null
            } else {
                text = currentValue
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val newDomainFromDialog = newValue as String
                UserPreferences.cuevanaDomain = newDomainFromDialog
                preference.summary = UserPreferences.cuevanaDomain
                if (UserPreferences.currentProvider?.name == "Cuevana 3") {
                    requireActivity().apply {
                        finish()
                        startActivity(Intent(this, this::class.java))
                    }
                }
                true
            }
        }

        findPreference<EditTextPreference>("provider_poseidon_domain")?.apply {
            val currentValue = UserPreferences.poseidonDomain
            summary = currentValue
            if (currentValue == DEFAULT_POSEIDON_DOMAIN_VALUE) {
                text = null
            } else {
                text = currentValue
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val newDomainFromDialog = newValue as String
                UserPreferences.poseidonDomain = newDomainFromDialog
                preference.summary = UserPreferences.poseidonDomain
                if (UserPreferences.currentProvider?.name == "Poseidonhd2") {
                    requireActivity().apply {
                        finish()
                        startActivity(Intent(this, this::class.java))
                    }
                }
                true
            }
        }

        bindAnimeOnlineNinjaPreferredServer()

        findPreference<EditTextPreference>("TMDB_API_KEY")?.apply {
            summary = if (UserPreferences.tmdbApiKey.isEmpty()) getString(R.string.settings_tmdb_api_key_summary) else UserPreferences.tmdbApiKey
            text = UserPreferences.tmdbApiKey
            setOnPreferenceChangeListener { _, newValue ->
                val newKey = (newValue as String).trim()
                UserPreferences.tmdbApiKey = newKey
                summary = if (newKey.isEmpty()) getString(R.string.settings_tmdb_api_key_summary) else newKey
                val message = if (newKey.isEmpty()) {
                    getString(R.string.settings_tmdb_api_key_reset)
                } else {
                    getString(R.string.settings_tmdb_api_key_success)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                true
            }
        }
        findPreference<SwitchPreferenceCompat>("ENABLE_TMDB")?.apply {
            isChecked = UserPreferences.enableTmdb

            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                val applyChange = {
                    UserPreferences.enableTmdb = enabled
                    updateParentalControlPreferenceState()
                    ProviderChangeNotifier.notifyProviderChanged()

                    val message = if (enabled) {
                        getString(R.string.settings_enable_tmdb_enabled)
                    } else {
                        getString(R.string.settings_enable_tmdb_disabled)
                    }

                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                }

                if (!enabled && UserPreferences.parentalControlPin.isNotBlank()) {
                    changeParentalSettingWithPinCheck(onVerified = applyChange)
                    false
                } else {
                    applyChange()
                    true
                }
            }
        }

        setupParentalControlPreferences()

        findPreference<EditTextPreference>("SUBDL_API_KEY")?.apply {
            summary = if (UserPreferences.subdlApiKey.isEmpty()) getString(R.string.settings_subdl_api_key_summary) else UserPreferences.subdlApiKey
            text = UserPreferences.subdlApiKey
            setOnPreferenceChangeListener { _, newValue ->
                val newKey = (newValue as String).trim()
                UserPreferences.subdlApiKey = newKey
                summary = if (newKey.isEmpty()) getString(R.string.settings_subdl_api_key_summary) else newKey
                val message = if (newKey.isEmpty()) {
                    getString(R.string.settings_subdl_api_key_reset)
                } else {
                    getString(R.string.settings_subdl_api_key_success)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                true
            }
        }

        findPreference<Preference>("p_settings_about")?.apply {
            val palette = ThemeManager.palette(UserPreferences.selectedTheme)
            val titleStr = getString(R.string.settings_version_tv)
            val spannableTitle = SpannableString(titleStr)
            spannableTitle.setSpan(ForegroundColorSpan(palette.tvHeaderPrimary), 0, titleStr.length, 0)
            title = spannableTitle
            
            val summaryStr = BuildConfig.VERSION_NAME
            val spannableSummary = SpannableString(summaryStr)
            spannableSummary.setSpan(ForegroundColorSpan(palette.tvHeaderSecondary), 0, summaryStr.length, 0)
            summary = spannableSummary

            isSelectable = false
            setOnPreferenceClickListener(null)
        }

        findPreference<Preference>("p_settings_help")?.setOnPreferenceClickListener {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/streamflix-reborn2/streamflix")
                )
            )
            true
        }

        findPreference<Preference>("p_settings_telegram")?.setOnPreferenceClickListener {
            try {
                val tgIntent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=streamflixreborn"))
                startActivity(tgIntent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Telegram not found.", Toast.LENGTH_SHORT).show()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/streamflixreborn"))
                startActivity(intent)
            }
            true
        }

        findPreference<SwitchPreference>("AUTOPLAY")?.isChecked = UserPreferences.autoplay
        findPreference<SwitchPreference>("AUTOPLAY")?.setOnPreferenceChangeListener { _, newValue ->
            UserPreferences.autoplay = newValue as Boolean
            true
        }

        findPreference<SwitchPreference>("FORCE_EXTRA_BUFFERING")?.apply {
            isChecked = UserPreferences.forceExtraBuffering
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.forceExtraBuffering = newValue as Boolean
                true
            }
        }

        findPreference<EditTextPreference>("p_settings_autoplay_buffer")?.apply {
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                val value = pref.text?.toLongOrNull() ?: 3L
                "$value s"
            }
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.autoplayBuffer = (newValue as String).toLongOrNull() ?: 3L
                true
            }
        }

        findPreference<SwitchPreference>("SERVER_AUTO_SUBTITLES_DISABLED")?.apply {
            isChecked = UserPreferences.serverAutoSubtitlesDisabled
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.serverAutoSubtitlesDisabled = newValue as Boolean
                true
            }
        }

        findPreference<SwitchPreference>("UPDATE_CHECK_ENABLED")?.apply {
            isChecked = UserPreferences.updateCheckEnabled
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.updateCheckEnabled = newValue as Boolean
                true
            }
        }

        val HasConfigProvider = UserPreferences.currentProvider is ProviderConfigUrl
        findPreference<PreferenceCategory>("pc_provider_settings")?.apply {
            isVisible = HasConfigProvider
        }

        if (HasConfigProvider) {
            val provider = UserPreferences.currentProvider
            val configProvider = provider as? ProviderConfigUrl
            val portalProvider = provider as? ProviderPortalUrl
            var autoUpdateVal = false

            findPreference<SwitchPreference>("provider_autoupdate")?.apply {
                isVisible = portalProvider != null
                if (isVisible) {
                    autoUpdateVal = UserPreferences
                        .getProviderCache(
                            provider!!, UserPreferences
                                .PROVIDER_AUTOUPDATE
                        ) != "false"
                    isChecked = autoUpdateVal
                    setOnPreferenceChangeListener { _, newValue ->
                        val newState = newValue as Boolean
                        UserPreferences.setProviderCache(
                            null,
                            UserPreferences.PROVIDER_AUTOUPDATE,
                            newState.toString()
                        )
                        findPreference<EditTextPreference>("provider_url")?.isEnabled = newState == false
                        true
                    }
                }
            }

            findPreference<EditTextPreference>("provider_url")?.apply {
                isVisible = configProvider != null
                isEnabled = autoUpdateVal == false
                if (isVisible && provider != null && configProvider != null) {
                    summary = UserPreferences
                        .getProviderCache(
                            provider, UserPreferences
                                .PROVIDER_URL
                        )
                        .ifBlank { provider.defaultBaseUrl }
                    setOnBindEditTextListener { editText ->
                        editText.inputType = InputType.TYPE_CLASS_TEXT
                        editText.imeOptions = EditorInfo.IME_ACTION_DONE
                        editText.hint = configProvider.defaultBaseUrl

                        editText.setText(summary)
                    }
                    setOnPreferenceChangeListener { _, newValue ->
                        val toSave = (newValue as String)
                            .ifBlank { configProvider.defaultBaseUrl }
                            .trim()
                            .removeSuffix("/") + "/"
                        UserPreferences.setProviderCache(
                            null,
                            UserPreferences.PROVIDER_URL,
                            toSave
                        )
                        summary = toSave
                        viewLifecycleOwner.lifecycleScope.launch {
                            configProvider.onChangeUrl()
                            ProviderChangeNotifier.notifyProviderChanged()
                        }
                        true
                    }
                }
            }

            findPreference<EditTextPreference>("provider_portal_url")?.apply {
                isVisible = portalProvider != null
                if (isVisible && provider != null && portalProvider != null) {
                    summary = UserPreferences
                        .getProviderCache(
                            provider, UserPreferences
                                .PROVIDER_PORTAL_URL
                        )
                        .ifBlank { portalProvider.defaultPortalUrl }
                    setOnBindEditTextListener { editText ->
                        editText.inputType = InputType.TYPE_CLASS_TEXT
                        editText.imeOptions = EditorInfo.IME_ACTION_DONE
                        editText.hint = portalProvider.defaultPortalUrl
                        editText.setText(summary)
                    }
                    setOnPreferenceChangeListener { _, newValue ->
                        val toSave = (newValue as String)
                            .ifBlank { portalProvider.defaultPortalUrl }
                            .trim()
                            .removeSuffix("/") + "/"
                        summary = toSave
                        UserPreferences.setProviderCache(
                            null,
                            UserPreferences.PROVIDER_PORTAL_URL,
                            toSave
                        )
                        true
                    }
                }
            }

            findPreference<Preference>("provider_autoupdate_now")?.apply {
                isVisible = portalProvider != null
                setOnPreferenceClickListener {
                    viewLifecycleOwner.lifecycleScope.launch {
                        findPreference<EditTextPreference>("provider_url")?.summary =
                            configProvider!!.onChangeUrl(true)
                    }
                    true
                }
            }
        }

        findPreference<ListPreference>("p_doh_provider_url")?.apply {
            value = UserPreferences.dohProviderUrl
            summary = entry
            setOnPreferenceChangeListener { preference, newValue ->
                val newUrl = newValue as String
                UserPreferences.dohProviderUrl = newUrl
                DnsResolver.setDnsUrl(newUrl)
                if (preference is ListPreference) {
                    val index = preference.findIndexOfValue(newUrl)
                    if (index >= 0 && preference.entries != null && index < preference.entries.size) {
                        preference.summary = preference.entries[index]
                    } else {
                        preference.summary = null
                    }
                }
                if (UserPreferences.currentProvider is StreamingCommunityProvider) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        (UserPreferences.currentProvider as StreamingCommunityProvider).rebuildService()
                        requireActivity().apply {
                            finish()
                            startActivity(Intent(this, this::class.java))
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), getString(R.string.doh_provider_updated), Toast.LENGTH_LONG).show()
                }
                true
            }
        }

        findPreference<SwitchPreference>("pc_frenchstream_new_interface")?.apply {
            isVisible = UserPreferences.currentProvider is FrenchStreamProvider
            if (isVisible) {
                val useNewInterface = UserPreferences
                    .getProviderCache(
                        UserPreferences.currentProvider!!, UserPreferences
                            .PROVIDER_NEW_INTERFACE
                    ) != "false"
                isChecked = useNewInterface
                setOnPreferenceChangeListener { _, newValue ->
                    val newState = newValue as Boolean
                    UserPreferences.setProviderCache(
                        null,
                        UserPreferences.PROVIDER_NEW_INTERFACE,
                        newState.toString()
                    )
                    true
                }
            }
        }

        val networkSettingsCategory = findPreference<PreferenceCategory>("pc_network_settings")
        if (networkSettingsCategory != null) {
            val originalTitle = getString(R.string.settings_category_network_title)
            val currentProviderName = UserPreferences.currentProvider?.name
            if (currentProviderName != null && currentProviderName.isNotEmpty()) {
                networkSettingsCategory.title = "$originalTitle $currentProviderName"
            } else {
                networkSettingsCategory.title = originalTitle
            }

            if (BuildConfig.DEBUG && findPreference<EditTextPreference>("BYPASS_WS_ADVERTISED_HOST") == null) {
                val hostPreference = EditTextPreference(requireContext()).apply {
                    key = "BYPASS_WS_ADVERTISED_HOST"
                    title = "Bypass advertised host"
                    dialogTitle = "Bypass advertised host"
                    summary = if (UserPreferences.bypassWsAdvertisedHost.isBlank()) {
                        "Auto (device IP)"
                    } else {
                        UserPreferences.bypassWsAdvertisedHost
                    }
                    text = UserPreferences.bypassWsAdvertisedHost
                    setOnBindEditTextListener { editText ->
                        editText.setSingleLine()
                        editText.hint = "192.168.1.50"
                        editText.setText(UserPreferences.bypassWsAdvertisedHost)
                        editText.setSelection(editText.text?.length ?: 0)
                    }
                    setOnPreferenceChangeListener { preference, newValue ->
                        val value = (newValue as String).trim()
                        UserPreferences.bypassWsAdvertisedHost = value
                        preference.summary = if (value.isBlank()) {
                            "Auto (device IP)"
                        } else {
                            value
                        }
                        true
                    }
                }
                networkSettingsCategory.addPreference(hostPreference)
            }

            if (BuildConfig.DEBUG && findPreference<Preference>("test_websocket_bypass") == null) {
                val testPreference = Preference(requireContext()).apply {
                    key = "test_websocket_bypass"
                    title = "Test WebSocket bypass"
                    summary = "Generate a QR code for the mobile resolver flow"
                    setOnPreferenceClickListener {
                        showWebSocketBypassTestDialog()
                        true
                    }
                }
                networkSettingsCategory.addPreference(testPreference)
            }
        }

        findPreference<Preference>("key_backup_export_tv")?.setOnPreferenceClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "streamflix_tv_backup_$timestamp.json"
            showBackupExportOptions(fileName)
            true
        }

        findPreference<Preference>("key_backup_import_tv")?.setOnPreferenceClickListener {
            showBackupImportOptions()
            true
        }

        findPreference<SeekBarPreference>("scale_x")?.apply {
            seekBarIncrement = 1
            value = UserPreferences.paddingX
            setOnPreferenceChangeListener { preference, newValue ->
                UserPreferences.paddingX = newValue as Int
                (activity as? MainTvActivity)?.adjustLayoutDelta(UserPreferences.paddingX, null)
                true
            }
        }

        findPreference<SeekBarPreference>("scale_y")?.apply {
            seekBarIncrement = 1
            value = UserPreferences.paddingY
            setOnPreferenceChangeListener { preference, newValue ->
                UserPreferences.paddingY = newValue as Int
                (activity as? MainTvActivity)?.adjustLayoutDelta(null, UserPreferences.paddingY)
                true
            }
        }

        findPreference<Preference>("preferred_player_reset")?.setOnPreferenceClickListener {
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .remove("preferred_smarttube_package")
                .apply()
            Toast.makeText(requireContext(), R.string.settings_trailer_player_reset, Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<ListPreference>("theme_preference")?.apply {
            summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                getString(ThemeManager.titleRes(pref.value ?: ThemeManager.DEFAULT))
            }

            setOnPreferenceChangeListener { _, newValue ->
                val newTheme = newValue as String
                UserPreferences.selectedTheme = newTheme

                // Apply the theme and restart the activity
                requireActivity().apply {
                    finish()
                    startActivity(Intent(this, MainTvActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    overridePendingTransition(0, 0) // Disable transition animation
                }
                true
            }
        }

        findPreference<ListPreference>("APP_LANGUAGE")?.apply {
            entries = AppLanguageManager.buildLanguageEntries(requireContext())
            entryValues = AppLanguageManager.buildLanguageValues(requireContext())
            value = AppLanguageManager.getSelectedLanguage(requireContext())
            summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                pref.entries.getOrNull(pref.findIndexOfValue(pref.value)) ?: getString(R.string.settings_app_language_system)
            }
            setOnPreferenceChangeListener { _, newValue ->
                val newLanguage = newValue as String
                AppLanguageManager.setSelectedLanguage(newLanguage)
                requireActivity().apply {
                    finish()
                    startActivity(Intent(this, MainTvActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    overridePendingTransition(0, 0)
                }
                true
            }
        }

        findPreference<Preference>("key_backup_refresh_cache_tv")?.setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_refresh_cache_confirm)
                .setMessage(R.string.settings_refresh_cache_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val refreshed = backupRestoreManager.refreshCachesFromDatabase()
                        Toast.makeText(
                            requireContext(),
                            if (refreshed) {
                                R.string.settings_refresh_cache_success
                            } else {
                                R.string.settings_refresh_cache_success
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        findPreference<Preference>("key_backup_export_db_tv")?.setOnPreferenceClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "streamflix_tv_db_backup_$timestamp.zip"
            showDatabaseBackupExportOptions(fileName)
            true
        }

        findPreference<Preference>("key_backup_import_db_tv")?.setOnPreferenceClickListener {
            showDatabaseBackupImportOptions()
            true
        }

        findPreference<Preference>("key_profile_edit")?.setOnPreferenceClickListener {
            val active = ProfileManager.activeProfile
            if (active != null) showRenameProfileDialogTv(active)
            true
        }

        findPreference<Preference>("key_profile_manage")?.setOnPreferenceClickListener {
            showProfileManagementDialogTv()
            true
        }

        findPreference<Preference>("key_profile_add")?.setOnPreferenceClickListener {
            showCreateProfileDialogTv()
            true
        }
    }

    private suspend fun performBackupExport(uri: Uri) {
        withBackupLoading(R.string.backup_export_title) {
            val jsonData = withContext(Dispatchers.IO) {
                backupRestoreManager.exportUserData()
            }
            if (jsonData != null) {
                try {
                    requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.writer().use { it.write(jsonData) }
                        Toast.makeText(requireContext(), getString(R.string.backup_export_success), Toast.LENGTH_LONG).show()
                    } ?: run {
                        Toast.makeText(requireContext(), getString(R.string.backup_export_error_write), Toast.LENGTH_LONG).show()
                    }
                } catch (e: IOException) {
                    Toast.makeText(requireContext(), getString(R.string.backup_export_error_write), Toast.LENGTH_LONG).show()
                    Log.e("BackupExportTV", "Error writing backup file", e)
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.backup_data_not_generated), Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun performBackupImport(uri: Uri) {
        withBackupLoading(R.string.backup_import_title) {
            try {
                val jsonData = withContext(Dispatchers.IO) {
                    val stringBuilder = StringBuilder()
                    requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                        inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { stringBuilder.append(it) }
                        }
                    }
                    stringBuilder.toString()
                }
                if (jsonData.isNotBlank()) {
                    val success = withContext(Dispatchers.IO) {
                        backupRestoreManager.importUserData(jsonData)
                    }
                    if (success) {
                        Toast.makeText(requireContext(), getString(R.string.backup_import_success), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.backup_import_error), Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(requireContext(), getString(R.string.backup_import_empty_file), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.backup_import_read_error), Toast.LENGTH_LONG).show()
                Log.e("BackupImportTV", "Error reading/processing backup file", e)
            }
        }
    }

    private fun showBackupExportOptions(fileName: String) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (hasCreateDocumentHandler()) {
            options += getString(R.string.backup_export_picker_option) to {
                try {
                    exportBackupLauncher.launch(fileName)
                } catch (error: ActivityNotFoundException) {
                    Log.w("BackupExportTV", "No document picker available, using local fallback", error)
                    Toast.makeText(requireContext(), getString(R.string.backup_picker_unavailable), Toast.LENGTH_LONG).show()
                    exportBackupToLocalFile(fileName)
                }
            }
        }
        options += getString(R.string.backup_export_local_option) to {
            exportBackupToLocalFile(fileName)
        }
        if (canWriteToDownloads()) {
            options += getString(R.string.backup_export_downloads_option) to {
                exportBackupToDownloads(fileName)
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_export_title)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                options[which].second.invoke()
            }
            .show()
    }

    private fun showBackupImportOptions() {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (hasOpenDocumentHandler()) {
            options += getString(R.string.backup_import_picker_option) to {
                try {
                    importBackupLauncher.launch(arrayOf("application/json"))
                } catch (error: ActivityNotFoundException) {
                    Log.w("BackupImportTV", "No document picker available, using local fallback", error)
                    Toast.makeText(requireContext(), getString(R.string.backup_picker_unavailable), Toast.LENGTH_LONG).show()
                    showLocalBackupPicker()
                }
            }
        }
        options += getString(R.string.backup_import_local_option) to {
            showLocalBackupPicker()
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_import_title)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                options[which].second.invoke()
            }
            .show()
    }

    private fun showDatabaseBackupExportOptions(fileName: String) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (hasCreateDocumentHandler("application/zip")) {
            options += getString(R.string.backup_export_picker_option) to {
                try {
                    exportDbBackupLauncher.launch(fileName)
                } catch (error: ActivityNotFoundException) {
                    Log.w("BackupExportTV", "No document picker available for DB zip, using local fallback", error)
                    Toast.makeText(requireContext(), getString(R.string.backup_picker_unavailable), Toast.LENGTH_LONG).show()
                    exportDatabaseBackupToLocalFile(fileName)
                }
            }
        }
        options += getString(R.string.backup_export_local_option) to {
            exportDatabaseBackupToLocalFile(fileName)
        }
        if (canWriteToDownloads()) {
            options += getString(R.string.backup_export_downloads_option) to {
                exportDatabaseBackupToDownloads(fileName)
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_db_export_option)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                options[which].second.invoke()
            }
            .show()
    }

    private fun showDatabaseBackupImportOptions() {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (hasOpenDocumentHandler("application/zip")) {
            options += getString(R.string.backup_import_picker_option) to {
                try {
                    importDbBackupLauncher.launch(arrayOf("application/zip"))
                } catch (error: ActivityNotFoundException) {
                    Log.w("BackupImportTV", "No document picker available for DB zip, using local fallback", error)
                    Toast.makeText(requireContext(), getString(R.string.backup_picker_unavailable), Toast.LENGTH_LONG).show()
                    showLocalDatabaseBackupPicker()
                }
            }
        }
        options += getString(R.string.backup_import_local_option) to {
            showLocalDatabaseBackupPicker()
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_db_import_option)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                options[which].second.invoke()
            }
            .show()
    }

    private fun exportBackupToLocalFile(fileName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            withBackupLoading(R.string.backup_export_title) {
                val jsonData = withContext(Dispatchers.IO) {
                    backupRestoreManager.exportUserData()
                }
                if (jsonData.isNullOrBlank()) {
                    Toast.makeText(requireContext(), getString(R.string.backup_data_not_generated), Toast.LENGTH_LONG).show()
                    return@withBackupLoading
                }

                runCatching {
                    val file = withContext(Dispatchers.IO) {
                        File(getBackupDirectory(), fileName).apply {
                            parentFile?.mkdirs()
                            writeText(jsonData)
                        }
                    }
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.backup_export_saved_to, file.absolutePath),
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(requireContext(), getString(R.string.backup_export_error_write), Toast.LENGTH_LONG).show()
                    Log.e("BackupExportTV", "Error writing local backup file", error)
                }
            }
        }
    }

    private fun exportBackupToDownloads(fileName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            withBackupLoading(R.string.backup_export_title) {
                val jsonData = withContext(Dispatchers.IO) {
                    backupRestoreManager.exportUserData()
                }
                if (jsonData.isNullOrBlank()) {
                    Toast.makeText(requireContext(), getString(R.string.backup_data_not_generated), Toast.LENGTH_LONG).show()
                    return@withBackupLoading
                }

                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/json")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StreamFlix")
                    }
                    val uri = requireContext().contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("Unable to create download entry")
                    requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.writer().use { it.write(jsonData) }
                    } ?: error("Unable to open output stream")

                    Toast.makeText(
                        requireContext(),
                        getString(R.string.backup_export_saved_to, "Downloads/StreamFlix/$fileName"),
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(requireContext(), getString(R.string.backup_export_error_write), Toast.LENGTH_LONG).show()
                    Log.e("BackupExportTV", "Error writing backup to downloads", error)
                }
            }
        }
    }

    private fun exportDatabaseBackupToLocalFile(fileName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            withBackupLoading(R.string.backup_db_export_title) {
                val zipData = withContext(Dispatchers.IO) {
                    backupRestoreManager.exportDatabaseZip()
                }
                if (zipData == null || zipData.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.backup_data_not_generated), Toast.LENGTH_LONG).show()
                    return@withBackupLoading
                }

                runCatching {
                    val file = withContext(Dispatchers.IO) {
                        File(getBackupDirectory(), fileName).apply {
                            parentFile?.mkdirs()
                            writeBytes(zipData)
                        }
                    }
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.backup_export_saved_to, file.absolutePath),
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(requireContext(), getString(R.string.backup_export_error_write), Toast.LENGTH_LONG).show()
                    Log.e("BackupExportTV", "Error writing local database backup file", error)
                }
            }
        }
    }

    private fun exportDatabaseBackupToDownloads(fileName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            withBackupLoading(R.string.backup_db_export_title) {
                val zipData = withContext(Dispatchers.IO) {
                    backupRestoreManager.exportDatabaseZip()
                }
                if (zipData == null || zipData.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.backup_data_not_generated), Toast.LENGTH_LONG).show()
                    return@withBackupLoading
                }

                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StreamFlix")
                    }
                    val uri = requireContext().contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("Unable to create download entry")
                    requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(zipData)
                    } ?: error("Unable to open output stream")

                    Toast.makeText(
                        requireContext(),
                        getString(R.string.backup_export_saved_to, "Downloads/StreamFlix/$fileName"),
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(requireContext(), getString(R.string.backup_export_error_write), Toast.LENGTH_LONG).show()
                    Log.e("BackupExportTV", "Error writing database backup to downloads", error)
                }
            }
        }
    }

    private suspend fun performDatabaseBackupExport(uri: Uri) {
        withBackupLoading(R.string.backup_db_export_title) {
            val zipData = withContext(Dispatchers.IO) {
                backupRestoreManager.exportDatabaseZip()
            }
            if (zipData != null) {
                try {
                    requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(zipData)
                    } ?: error("Unable to open output stream")
                    Toast.makeText(requireContext(), getString(R.string.backup_db_export_success), Toast.LENGTH_LONG).show()
                } catch (error: IOException) {
                    Toast.makeText(requireContext(), getString(R.string.backup_export_error_write), Toast.LENGTH_LONG).show()
                    Log.e("BackupExportTV", "Error writing database backup", error)
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.backup_data_not_generated), Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun performDatabaseBackupImport(uri: Uri) {
        withBackupLoading(R.string.backup_db_import_title) {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes == null || bytes.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.backup_import_empty_file), Toast.LENGTH_LONG).show()
                    return@withBackupLoading
                }
                val success = withContext(Dispatchers.IO) {
                    backupRestoreManager.importDatabaseZip(bytes)
                }
                Toast.makeText(
                    requireContext(),
                    if (success) getString(R.string.backup_db_import_success) else getString(R.string.backup_import_error),
                    Toast.LENGTH_LONG
                ).show()
            } catch (error: Exception) {
                Toast.makeText(requireContext(), getString(R.string.backup_import_read_error), Toast.LENGTH_LONG).show()
                Log.e("BackupImportTV", "Error reading database backup file", error)
            }
        }
    }

    private fun showLocalBackupPicker() {
        val backups = getBackupDirectory()
            .listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

        if (backups.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.backup_import_no_local_files), Toast.LENGTH_LONG).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_import_local_option)
            .setItems(backups.map(::buildBackupListLabel).toTypedArray()) { _, which ->
                showLocalBackupFileActions(backups[which])
            }
            .show()
    }

    private fun showLocalDatabaseBackupPicker() {
        val backups = getBackupDirectory()
            .listFiles { file -> file.isFile && file.extension.equals("zip", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

        if (backups.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.backup_import_no_local_files), Toast.LENGTH_LONG).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_db_import_option)
            .setItems(backups.map(::buildBackupListLabel).toTypedArray()) { _, which ->
                showLocalDatabaseBackupFileActions(backups[which])
            }
            .show()
    }

    private fun showLocalBackupFileActions(file: File) {
        val options = arrayOf(
            getString(R.string.backup_import_action),
            getString(R.string.backup_delete_action),
        )

        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> importBackupFromFile(file)
                    1 -> confirmDeleteLocalBackup(file)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLocalDatabaseBackupFileActions(file: File) {
        val options = arrayOf(
            getString(R.string.backup_import_action),
            getString(R.string.backup_delete_action),
        )

        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> importDatabaseBackupFromFile(file)
                    1 -> confirmDeleteLocalBackup(file)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteLocalBackup(file: File) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_delete_confirm_title)
            .setMessage(getString(R.string.backup_delete_confirm_message, file.name))
            .setPositiveButton(R.string.backup_delete_action) { _, _ ->
                runCatching { file.delete() }
                    .onSuccess { deleted ->
                        val messageRes = if (deleted) {
                            R.string.backup_delete_success
                        } else {
                            R.string.backup_delete_error
                        }
                        Toast.makeText(requireContext(), getString(messageRes), Toast.LENGTH_LONG).show()
                        if (deleted) {
                            showLocalBackupPicker()
                        }
                    }
                    .onFailure { error ->
                        Toast.makeText(requireContext(), getString(R.string.backup_delete_error), Toast.LENGTH_LONG).show()
                        Log.e("BackupDeleteTV", "Error deleting local backup file", error)
                    }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importBackupFromFile(file: File) {
        viewLifecycleOwner.lifecycleScope.launch {
            withBackupLoading(R.string.backup_import_title) {
                runCatching {
                    val jsonData = withContext(Dispatchers.IO) {
                        file.readText()
                    }
                    if (jsonData.isBlank()) {
                        Toast.makeText(requireContext(), getString(R.string.backup_import_empty_file), Toast.LENGTH_LONG).show()
                        return@withBackupLoading
                    }
                    val success = withContext(Dispatchers.IO) {
                        backupRestoreManager.importUserData(jsonData)
                    }
                    if (success) {
                        Toast.makeText(requireContext(), getString(R.string.backup_import_success), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.backup_import_error), Toast.LENGTH_LONG).show()
                    }
                }.onFailure { error ->
                    Toast.makeText(requireContext(), getString(R.string.backup_import_read_error), Toast.LENGTH_LONG).show()
                    Log.e("BackupImportTV", "Error importing local backup file", error)
                }
            }
        }
    }

    private fun importDatabaseBackupFromFile(file: File) {
        viewLifecycleOwner.lifecycleScope.launch {
            withBackupLoading(R.string.backup_db_import_title) {
                runCatching {
                    val bytes = withContext(Dispatchers.IO) {
                        file.readBytes()
                    }
                    if (bytes.isEmpty()) {
                        Toast.makeText(requireContext(), getString(R.string.backup_import_empty_file), Toast.LENGTH_LONG).show()
                        return@withBackupLoading
                    }
                    val success = withContext(Dispatchers.IO) {
                        backupRestoreManager.importDatabaseZip(bytes)
                    }
                    Toast.makeText(
                        requireContext(),
                        if (success) getString(R.string.backup_db_import_success) else getString(R.string.backup_import_error),
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(requireContext(), getString(R.string.backup_import_read_error), Toast.LENGTH_LONG).show()
                    Log.e("BackupImportTV", "Error importing local database backup file", error)
                }
            }
        }
    }

    private fun getBackupDirectory(): File {
        return File(requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: requireContext().filesDir, "backups")
    }

    private suspend fun <T> withBackupLoading(titleRes: Int, block: suspend () -> T): T {
        showBackupLoadingDialog(titleRes)
        return try {
            block()
        } finally {
            hideBackupLoadingDialog()
        }
    }

    private fun showBackupLoadingDialog(titleRes: Int) {
        if (!isAdded) return
        if (backupLoadingDialog?.isShowing == true) {
            backupLoadingDialog?.setTitle(titleRes)
            return
        }

        val contentView = LayoutInflater.from(requireContext()).inflate(
            R.layout.layout_is_loading_tv,
            null
        )
        contentView.findViewById<TextView>(R.id.tv_is_loading_error)?.visibility = View.GONE
        contentView.findViewById<Group>(R.id.g_is_loading_retry)?.visibility = View.GONE

        backupLoadingDialog = AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setView(contentView)
            .setCancelable(false)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                show()
            }
    }

    private fun hideBackupLoadingDialog() {
        backupLoadingDialog?.dismiss()
        backupLoadingDialog = null
    }

    private fun buildBackupListLabel(file: File): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
        return "$timestamp  ${file.name}"
    }

    private fun hasCreateDocumentHandler(mimeType: String = "application/json"): Boolean {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeType)
        return intent.resolveActivity(requireContext().packageManager) != null
    }

    private fun hasOpenDocumentHandler(mimeType: String = "application/json"): Boolean {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeType)
        return intent.resolveActivity(requireContext().packageManager) != null
    }

    private fun canWriteToDownloads(): Boolean {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
    }

    private fun updateOverviewLabels() {
        val providerName = UserPreferences.currentProvider?.name

        findPreference<PreferenceScreen>("screen_provider")?.apply {
            title = getString(R.string.settings_provider_connection_title)
            summary = providerName?.let {
                getString(R.string.settings_screen_provider_summary_with_name, it)
            } ?: getString(R.string.settings_screen_provider_summary)
        }

        findPreference<PreferenceCategory>("pc_provider_settings")?.title = providerName?.let {
            getString(R.string.settings_provider_connection_category_title, it)
        } ?: getString(R.string.settings_category_provider_title)

        findPreference<PreferenceCategory>("pc_provider_empty_state")?.title = providerName?.let {
            getString(R.string.settings_provider_connection_category_title, it)
        } ?: getString(R.string.settings_provider_connection_title)

        findPreference<Preference>("key_profile_edit")?.summary =
            ProfileManager.activeProfile?.name ?: getString(R.string.profile_edit_btn)
    }

    private fun updateProviderVisibilityState() {
        val isStreamingCommunity = UserPreferences.currentProvider is StreamingCommunityProvider
        val isSerienStream = UserPreferences.currentProvider is SerienStreamProvider
        val isMoflix = UserPreferences.currentProvider is MStreamProvider
        val isCuevana = UserPreferences.currentProvider?.name == "Cuevana 3"
        val isPoseidon = UserPreferences.currentProvider?.name == "Poseidonhd2"
        val isAnimeOnlineNinja = UserPreferences.currentProvider is AnimeOnlineNinjaProvider
        val hasConfigProvider = UserPreferences.currentProvider is ProviderConfigUrl
        val hasSpecificOptions = isStreamingCommunity || isCuevana || isPoseidon || isAnimeOnlineNinja

        findPreference<PreferenceCategory>("pc_streamingcommunity_settings")?.isVisible = isStreamingCommunity
        findPreference<PreferenceCategory>("pc_serienstream_settings")?.isVisible = isSerienStream
        findPreference<PreferenceCategory>("pc_moflix_settings")?.isVisible = isMoflix
        findPreference<PreferenceCategory>("pc_cuevana_settings")?.isVisible = isCuevana
        findPreference<PreferenceCategory>("pc_poseidon_settings")?.isVisible = isPoseidon
        findPreference<PreferenceCategory>("pc_animeonlineninja_settings")?.isVisible = isAnimeOnlineNinja
        findPreference<PreferenceCategory>("pc_provider_empty_state")?.isVisible = !hasConfigProvider && !hasSpecificOptions
    }

    private fun bindAnimeOnlineNinjaPreferredServer() {
        val preference = findPreference<ListPreference>("provider_animeonlineninja_preferred_server") ?: return
        val currentValue = UserPreferences.getProviderCache(
            AnimeOnlineNinjaProvider,
            UserPreferences.PROVIDER_PREFERRED_SERVER
        )
        preference.value = currentValue
        preference.summary = preference.entries
            ?.getOrNull(preference.findIndexOfValue(currentValue))
            ?: getString(R.string.settings_provider_animeonlineninja_preferred_server_summary)
        preference.setOnPreferenceChangeListener { pref, newValue ->
            val value = (newValue as String).trim()
            UserPreferences.setProviderCache(
                AnimeOnlineNinjaProvider,
                UserPreferences.PROVIDER_PREFERRED_SERVER,
                value
            )
            if (pref is ListPreference) {
                pref.summary = pref.entries?.getOrNull(pref.findIndexOfValue(value))
                    ?: getString(R.string.settings_provider_animeonlineninja_preferred_server_summary)
            }
            true
        }
    }

    private fun setupParentalControlPreferences() {
        val pinPreference = findPreference<EditTextPreference>("PARENTAL_CONTROL_PIN")
        val adminPinPreference = findPreference<EditTextPreference>("PARENTAL_CONTROL_ADMIN_PIN")
        val removePinPreference = findPreference<Preference>("PARENTAL_CONTROL_REMOVE_PIN")
        val removeAdminPinPreference = findPreference<Preference>("PARENTAL_CONTROL_REMOVE_ADMIN_PIN")
        val maxAgePreference = findPreference<ListPreference>("PARENTAL_CONTROL_MAX_AGE")
        val unlockPreference = findPreference<Preference>("PARENTAL_CONTROL_UNLOCK")

        fun bindPinEditText(editText: EditText) {
            editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            editText.imeOptions = EditorInfo.IME_ACTION_DONE
            editText.hint = getString(R.string.settings_parental_pin_hint)
            editText.setText("")
        }

        pinPreference?.setOnBindEditTextListener(::bindPinEditText)
        adminPinPreference?.setOnBindEditTextListener(::bindPinEditText)

        pinPreference?.setOnPreferenceClickListener {
            showParentalPinEditor(maxAgePreference)
            true
        }

        adminPinPreference?.setOnPreferenceClickListener {
            showAdminPinEditor()
            true
        }

        removePinPreference?.setOnPreferenceClickListener {
            changeParentalSettingWithPinCheck {
                UserPreferences.parentalControlPin = ""
                UserPreferences.parentalControlMaxAge = null
                maxAgePreference?.value = ""
                UserPreferences.unlockParentalControls()
                Toast.makeText(requireContext(), getString(R.string.settings_parental_pin_removed), Toast.LENGTH_SHORT).show()
                ProviderChangeNotifier.notifyProviderChanged()
                updateParentalControlPreferenceState()
            }
            true
        }

        removeAdminPinPreference?.setOnPreferenceClickListener {
            changeAdminSettingWithPinCheck {
                UserPreferences.parentalControlAdminPin = ""
                Toast.makeText(requireContext(), getString(R.string.settings_parental_admin_pin_removed), Toast.LENGTH_SHORT).show()
                updateParentalControlPreferenceState()
            }
            true
        }

        maxAgePreference?.setOnPreferenceChangeListener { _, newValue ->
            if (!UserPreferences.enableTmdb) return@setOnPreferenceChangeListener false
            if (UserPreferences.parentalControlPin.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.settings_parental_set_pin_first), Toast.LENGTH_SHORT).show()
                return@setOnPreferenceChangeListener false
            }

            val newMaxAgeValue = newValue as String
            val newMaxAge = newMaxAgeValue.toIntOrNull()

            changeParentalSettingWithPinCheck {
                UserPreferences.parentalControlMaxAge = newMaxAge
                maxAgePreference.value = newMaxAgeValue
                Toast.makeText(requireContext(), getString(R.string.settings_parental_max_age_saved), Toast.LENGTH_SHORT).show()
                ProviderChangeNotifier.notifyProviderChanged()
                updateParentalControlPreferenceState()
            }

            false
        }

        unlockPreference?.setOnPreferenceClickListener {
            if (UserPreferences.parentalControlAdminPin.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.settings_parental_set_admin_pin_first), Toast.LENGTH_SHORT).show()
            } else {
                promptForAdminPin {
                    UserPreferences.unlockParentalControls()
                    Toast.makeText(requireContext(), getString(R.string.settings_parental_unlocked), Toast.LENGTH_SHORT).show()
                    updateParentalControlPreferenceState()
                }
            }
            true
        }

        updateParentalControlPreferenceState()
    }

    private fun updateParentalControlPreferenceState() {
        val tmdbEnabled = UserPreferences.enableTmdb
        val pinPreference = findPreference<EditTextPreference>("PARENTAL_CONTROL_PIN")
        val adminPinPreference = findPreference<EditTextPreference>("PARENTAL_CONTROL_ADMIN_PIN")
        val removePinPreference = findPreference<Preference>("PARENTAL_CONTROL_REMOVE_PIN")
        val removeAdminPinPreference = findPreference<Preference>("PARENTAL_CONTROL_REMOVE_ADMIN_PIN")
        val maxAgePreference = findPreference<ListPreference>("PARENTAL_CONTROL_MAX_AGE")
        val unlockPreference = findPreference<Preference>("PARENTAL_CONTROL_UNLOCK")
        val isLocked = UserPreferences.isParentalControlTemporarilyLocked || UserPreferences.parentalControlHardLocked

        pinPreference?.apply {
            isEnabled = tmdbEnabled && !isLocked
            text = ""
            summary = when {
                !tmdbEnabled -> getString(R.string.settings_parental_requires_tmdb)
                UserPreferences.parentalControlHardLocked -> getString(R.string.settings_parental_locked_hard)
                UserPreferences.isParentalControlTemporarilyLocked -> getString(
                    R.string.settings_parental_locked_temporary,
                    lockRemainingMinutes()
                )
                UserPreferences.parentalControlPin.isBlank() -> getString(R.string.settings_parental_pin_not_set)
                else -> getString(R.string.settings_parental_pin_set)
            }
        }

        adminPinPreference?.apply {
            isEnabled = tmdbEnabled
            text = ""
            summary = when {
                !tmdbEnabled -> getString(R.string.settings_parental_requires_tmdb)
                UserPreferences.parentalControlAdminPin.isBlank() -> getString(R.string.settings_parental_admin_pin_not_set)
                else -> getString(R.string.settings_parental_admin_pin_set)
            }
        }

        removePinPreference?.apply {
            isVisible = tmdbEnabled && UserPreferences.parentalControlPin.isNotBlank()
            isEnabled = !isLocked
        }

        removeAdminPinPreference?.apply {
            isVisible = tmdbEnabled && UserPreferences.parentalControlAdminPin.isNotBlank()
            isEnabled = true
        }

        maxAgePreference?.apply {
            isEnabled = tmdbEnabled && !isLocked
            value = UserPreferences.parentalControlMaxAge?.toString().orEmpty()
            summary = when {
                !tmdbEnabled -> getString(R.string.settings_parental_requires_tmdb)
                UserPreferences.parentalControlHardLocked -> getString(R.string.settings_parental_locked_hard)
                UserPreferences.isParentalControlTemporarilyLocked -> getString(
                    R.string.settings_parental_locked_temporary,
                    lockRemainingMinutes()
                )
                UserPreferences.parentalControlPin.isBlank() -> getString(R.string.settings_parental_set_pin_first)
                UserPreferences.parentalControlMaxAge == null -> getString(R.string.settings_parental_max_age_disabled)
                else -> "${UserPreferences.parentalControlMaxAge}+"
            }
        }

        unlockPreference?.apply {
            isVisible = isLocked
            isEnabled = tmdbEnabled && UserPreferences.parentalControlAdminPin.isNotBlank()
            summary = when {
                UserPreferences.parentalControlAdminPin.isBlank() -> getString(R.string.settings_parental_set_admin_pin_first)
                UserPreferences.parentalControlHardLocked -> getString(R.string.settings_parental_locked_hard)
                UserPreferences.isParentalControlTemporarilyLocked -> getString(
                    R.string.settings_parental_locked_temporary,
                    lockRemainingMinutes()
                )
                else -> getString(R.string.settings_parental_unlock_summary)
            }
        }
    }

    private fun changeParentalSettingWithPinCheck(onVerified: () -> Unit) {
        when {
            UserPreferences.parentalControlHardLocked -> {
                Toast.makeText(requireContext(), getString(R.string.settings_parental_locked_hard), Toast.LENGTH_SHORT).show()
                updateParentalControlPreferenceState()
                return
            }
            UserPreferences.isParentalControlTemporarilyLocked -> {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.settings_parental_locked_temporary, lockRemainingMinutes()),
                    Toast.LENGTH_SHORT
                ).show()
                updateParentalControlPreferenceState()
                return
            }
        }

        val currentPin = UserPreferences.parentalControlPin
        if (currentPin.isBlank()) {
            onVerified()
            return
        }

        promptForPin(
            titleRes = R.string.settings_parental_enter_current_pin_title,
            messageRes = R.string.settings_parental_enter_current_pin_message,
            onSubmit = { enteredPin ->
                if (enteredPin == currentPin) {
                    UserPreferences.registerParentalPinSuccess()
                    onVerified()
                    null
                } else {
                    UserPreferences.registerParentalPinFailure()
                    updateParentalControlPreferenceState()
                    when {
                        UserPreferences.parentalControlHardLocked -> R.string.settings_parental_locked_hard
                        UserPreferences.isParentalControlTemporarilyLocked -> R.string.settings_parental_locked_temporary
                        else -> R.string.settings_parental_invalid_pin
                    }.let { failureMessageRes ->
                        if (failureMessageRes == R.string.settings_parental_locked_temporary) {
                            getString(failureMessageRes, lockRemainingMinutes())
                        } else {
                            getString(failureMessageRes)
                        }
                    }
                }
            }
        )
    }

    private fun changeAdminSettingWithPinCheck(onVerified: () -> Unit) {
        val currentAdminPin = UserPreferences.parentalControlAdminPin
        if (currentAdminPin.isBlank()) {
            onVerified()
            return
        }

        promptForAdminPin(onVerified)
    }

    private fun promptForAdminPin(onVerified: () -> Unit) {
        val currentAdminPin = UserPreferences.parentalControlAdminPin
        if (currentAdminPin.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.settings_parental_set_admin_pin_first), Toast.LENGTH_SHORT).show()
            return
        }

        promptForPin(
            titleRes = R.string.settings_parental_enter_admin_pin_title,
            messageRes = R.string.settings_parental_enter_admin_pin_message,
            onSubmit = { enteredPin ->
                if (enteredPin == currentAdminPin) {
                    UserPreferences.unlockParentalControls()
                    onVerified()
                    null
                } else {
                    getString(R.string.settings_parental_invalid_admin_pin)
                }
            }
        )
    }

    private fun showParentalPinEditor(maxAgePreference: ListPreference?) {
        if (!UserPreferences.enableTmdb) {
            Toast.makeText(requireContext(), getString(R.string.settings_parental_requires_tmdb), Toast.LENGTH_SHORT).show()
            return
        }

        changeParentalSettingWithPinCheck {
            promptForPinValue(
                titleRes = R.string.settings_parental_pin_title,
                messageRes = if (UserPreferences.parentalControlPin.isBlank()) {
                    R.string.settings_parental_set_new_pin_message
                } else {
                    R.string.settings_parental_change_pin_message
                },
                allowBlank = UserPreferences.parentalControlPin.isNotBlank(),
                onSubmit = { newPin ->
                    when {
                        newPin.isBlank() -> {
                            UserPreferences.parentalControlPin = ""
                            UserPreferences.parentalControlMaxAge = null
                            maxAgePreference?.value = ""
                            UserPreferences.unlockParentalControls()
                            Toast.makeText(requireContext(), getString(R.string.settings_parental_pin_removed), Toast.LENGTH_SHORT).show()
                            ProviderChangeNotifier.notifyProviderChanged()
                            updateParentalControlPreferenceState()
                            null
                        }
                        newPin.length < 4 -> getString(R.string.settings_parental_pin_too_short)
                        else -> {
                            UserPreferences.parentalControlPin = newPin
                            Toast.makeText(requireContext(), getString(R.string.settings_parental_pin_saved), Toast.LENGTH_SHORT).show()
                            ProviderChangeNotifier.notifyProviderChanged()
                            updateParentalControlPreferenceState()
                            null
                        }
                    }
                }
            )
        }
    }

    private fun showAdminPinEditor() {
        changeAdminSettingWithPinCheck {
            promptForPinValue(
                titleRes = R.string.settings_parental_admin_pin_title,
                messageRes = if (UserPreferences.parentalControlAdminPin.isBlank()) {
                    R.string.settings_parental_set_new_admin_pin_message
                } else {
                    R.string.settings_parental_change_admin_pin_message
                },
                allowBlank = UserPreferences.parentalControlAdminPin.isNotBlank(),
                onSubmit = { newPin ->
                    when {
                        newPin.isBlank() -> {
                            UserPreferences.parentalControlAdminPin = ""
                            Toast.makeText(requireContext(), getString(R.string.settings_parental_admin_pin_removed), Toast.LENGTH_SHORT).show()
                            updateParentalControlPreferenceState()
                            null
                        }
                        newPin.length < 4 -> getString(R.string.settings_parental_pin_too_short)
                        else -> {
                            UserPreferences.parentalControlAdminPin = newPin
                            Toast.makeText(requireContext(), getString(R.string.settings_parental_admin_pin_saved), Toast.LENGTH_SHORT).show()
                            updateParentalControlPreferenceState()
                            null
                        }
                    }
                }
            )
        }
    }

    private fun promptForPin(
        titleRes: Int,
        messageRes: Int,
        onSubmit: (String) -> String?,
    ) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = getString(R.string.settings_parental_pin_hint)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                input.error = null
                val errorMessage = onSubmit(input.text?.toString()?.trim().orEmpty())
                if (errorMessage == null) {
                    dialog.dismiss()
                } else {
                    input.setText("")
                    input.error = errorMessage
                    input.requestFocus()
                }
            }
        }

        dialog.show()
    }

    private fun promptForPinValue(
        titleRes: Int,
        messageRes: Int,
        allowBlank: Boolean,
        onSubmit: (String) -> String?,
    ) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = getString(R.string.settings_parental_pin_hint)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                input.error = null
                val newValue = input.text?.toString()?.trim().orEmpty()
                if (newValue.isBlank() && !allowBlank) {
                    input.setText("")
                    input.error = getString(R.string.settings_parental_pin_too_short)
                    input.requestFocus()
                    return@setOnClickListener
                }

                val errorMessage = onSubmit(newValue)
                if (errorMessage == null) {
                    dialog.dismiss()
                } else {
                    input.setText("")
                    input.error = errorMessage
                    input.requestFocus()
                }
            }
        }

        dialog.show()
    }

    private fun lockRemainingMinutes(): Int {
        val millis = UserPreferences.parentalControlLockRemainingMillis
        return ((millis + 60_000L - 1L) / 60_000L).toInt().coerceAtLeast(1)
    }

    override fun onResume() {
        super.onResume()
        applyScreenTitle()
        updateOverviewLabels()
        updateProviderVisibilityState()

        findPreference<EditTextPreference>("provider_streamingcommunity_domain")?.apply {
            val currentValue = UserPreferences.streamingcommunityDomain
            summary = currentValue
            if (currentValue == DEFAULT_DOMAIN_VALUE || currentValue == PREFS_ERROR_VALUE) {
                text = null
            } else {
                text = currentValue
            }
        }

        findPreference<EditTextPreference>("TMDB_API_KEY")?.apply {
            summary = if (UserPreferences.tmdbApiKey.isEmpty()) getString(R.string.settings_tmdb_api_key_summary) else UserPreferences.tmdbApiKey
            text = UserPreferences.tmdbApiKey
        }

        findPreference<EditTextPreference>("SUBDL_API_KEY")?.apply {
            summary = if (UserPreferences.subdlApiKey.isEmpty()) getString(R.string.settings_subdl_api_key_summary) else UserPreferences.subdlApiKey
            text = UserPreferences.subdlApiKey
        }

        findPreference<EditTextPreference>("BYPASS_WS_ADVERTISED_HOST")?.apply {
            val currentValue = UserPreferences.bypassWsAdvertisedHost
            summary = if (currentValue.isBlank()) "Auto (device IP)" else currentValue
            text = currentValue
        }

        findPreference<ListPreference>("p_doh_provider_url")?.apply {
            summary = entry
        }

        findPreference<ListPreference>("APP_LANGUAGE")?.value =
            AppLanguageManager.getSelectedLanguage(requireContext())

        val networkSettingsCategory = findPreference<PreferenceCategory>("pc_network_settings")
        if (networkSettingsCategory != null) {
            val originalTitle = getString(R.string.settings_category_network_title)
            val currentProviderName = UserPreferences.currentProvider?.name
            if (currentProviderName != null && currentProviderName.isNotEmpty()) {
                networkSettingsCategory.title = "$originalTitle $currentProviderName"
            } else {
                networkSettingsCategory.title = originalTitle
            }
        }
        findPreference<SwitchPreference>("AUTOPLAY")?.isChecked = UserPreferences.autoplay
        findPreference<SwitchPreference>("FORCE_EXTRA_BUFFERING")?.isChecked = UserPreferences.forceExtraBuffering
        findPreference<SwitchPreference>("SERVER_AUTO_SUBTITLES_DISABLED")?.isChecked = UserPreferences.serverAutoSubtitlesDisabled
        
        val bufferPref: EditTextPreference? = findPreference("p_settings_autoplay_buffer") 
        bufferPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val value = pref.text?.toLongOrNull() ?: 3L
            "$value s"
        }
        updateParentalControlPreferenceState()
    }

    private fun showWebSocketBypassTestDialog() {
        val defaultUrl = UserPreferences.currentProvider?.baseUrl ?: UserPreferences.cuevanaDomain.let { "https://$it" }
        val input = EditText(requireContext()).apply {
            setText(defaultUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Test WebSocket bypass")
            .setMessage("Enter the URL that the mobile resolver should open.")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val targetUrl = input.text?.toString()?.trim().orEmpty()
                if (targetUrl.isBlank()) {
                    Toast.makeText(requireContext(), "URL is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val session = WebSocketBypassTestHelper.createSession(targetUrl) { token ->
                    Log.d("BypassWSTest", "Resolver completed for token=$token")
                }

                if (session == null) {
                    Toast.makeText(requireContext(), "Unable to start local websocket server", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                showWebSocketBypassQrDialog(session.deepLink, session.wsUrl, targetUrl)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showWebSocketBypassQrDialog(
        deepLink: String,
        wsUrl: String,
        targetUrl: String,
    ) {
        val displayMetrics: DisplayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        val dialogWidth = (displayMetrics.widthPixels * 0.72f).toInt()
        val qrSize = minOf(
            (dialogWidth - (density * 64).toInt()).coerceAtLeast((density * 240).toInt()),
            (displayMetrics.heightPixels * 0.45f).toInt().coerceAtLeast((density * 240).toInt()),
        )

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                (density * 24).toInt(),
                (density * 16).toInt(),
                (density * 24).toInt(),
                (density * 12).toInt(),
            )
        }

        val qrView = ImageView(requireContext()).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(BitmapDrawable(resources, QrUtils.generate(deepLink, qrSize)))
        }

        val instructionsView = TextView(requireContext()).apply {
            text = buildString {
                append("1. Open Settings on the mobile app.\n")
                append("2. Use 'Scan Resolver QR'.\n")
                append("3. Scan this code and complete the bypass page.\n\n")
                append("WebSocket: ")
                append(wsUrl)
                append("\nTarget URL: ")
                append(targetUrl)
                if (BypassWebSocketEndpointHelper.isProbablyEmulator()) {
                    append("\n\nEmulator note: set 'Bypass advertised host' to your PC LAN IP and forward TCP 8081 to the emulator.")
                }
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.BLACK)
        }

        container.addView(
            qrView,
            LinearLayout.LayoutParams(
                qrSize,
                qrSize,
            )
        )
        container.addView(
            instructionsView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        )

        val scrollView = ScrollView(requireContext()).apply {
            isFillViewport = true
            addView(container)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("WebSocket bypass QR")
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok, null)
            .show()

        dialog.window?.setLayout(dialogWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun showCreateProfileDialogTv() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.profile_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_create_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    lifecycleScope.launch {
                        ProfileManager.createProfile(name)
                        Toast.makeText(requireContext(), R.string.profile_created, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameProfileDialogTv(profile: Profile) {
        val input = EditText(requireContext()).apply {
            setText(profile.name)
            hint = getString(R.string.profile_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_rename_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    lifecycleScope.launch {
                        ProfileManager.renameProfile(profile.id, newName)
                        Toast.makeText(requireContext(), R.string.profile_renamed, Toast.LENGTH_SHORT).show()
                        updateOverviewLabels()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProfileManagementDialogTv() {
        lifecycleScope.launch {
            val profiles = ProfileManager.getAllProfiles()
            val names = profiles.map { it.name }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.profile_manage_title)
                .setItems(names) { _, which ->
                    if (which < profiles.size) {
                        showProfileActionsDialogTv(profiles[which], profiles.size)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showProfileActionsDialogTv(profile: Profile, profileCount: Int = 1) {
        val items = mutableListOf<String>().apply {
            add(getString(R.string.profile_action_switch))
            add(getString(R.string.profile_action_rename))
            add(getString(R.string.profile_action_color))
            if (profileCount > 1) {
                add(getString(R.string.profile_action_delete))
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle(profile.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    getString(R.string.profile_action_switch) -> {
                        if (profile.id == ProfileManager.activeProfileId) {
                            switchProfileFromSettings(profile)
                        } else {
                            ProfileSwitchPinGuard.verifyCurrentProfile(requireContext()) {
                                switchProfileFromSettings(profile)
                            }
                        }
                    }
                    getString(R.string.profile_action_rename) -> showRenameProfileDialogTv(profile)
                    getString(R.string.profile_action_color) -> showProfileColorDialogTv(profile)
                    getString(R.string.profile_action_delete) -> showDeleteProfileDialogTv(profile)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun switchProfileFromSettings(profile: Profile) {
        if (!isAdded) return
        val oldProfileId = ProfileManager.activeProfileId
        val oldLang = oldProfileId?.let { AppLanguageManager.getProfileLanguage(requireContext(), it) }
        lifecycleScope.launch {
            ProfileManager.switchToProfile(profile.id)
            val newLang = AppLanguageManager.getProfileLanguage(requireContext(), profile.id)
            if (newLang != (oldLang ?: AppLanguageManager.SYSTEM_LANGUAGE)) {
                requireActivity().apply {
                    finish()
                    startActivity(Intent(this, this::class.java))
                }
            } else {
                (requireActivity() as? MainTvActivity)?.recreateProviderHome()
            }
        }
    }

    private fun showDeleteProfileDialogTv(profile: Profile) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_delete_title)
            .setMessage(getString(R.string.profile_delete_message, profile.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    val success = ProfileManager.deleteProfile(profile.id)
                    if (success) {
                        Toast.makeText(requireContext(), R.string.profile_deleted, Toast.LENGTH_SHORT).show()
                        if (ProfileManager.activeProfile?.id == profile.id) {
                            requireActivity().apply {
                                finish()
                                startActivity(Intent(this, this::class.java))
                            }
                        }
                    } else {
                        Toast.makeText(requireContext(), R.string.profile_delete_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProfileColorDialogTv(profile: Profile) {
        ProfileColorPicker.show(requireContext(), profile.avatarColor) { color ->
            lifecycleScope.launch {
                if (ProfileManager.setProfileColor(profile.id, color)) {
                    Toast.makeText(requireContext(), R.string.profile_color_changed, Toast.LENGTH_SHORT).show()
                    if (profile.id == ProfileManager.activeProfileId) requireActivity().recreate()
                }
            }
        }
    }
}
