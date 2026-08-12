package com.streamflixreborn.streamflix.fragments.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.sync.SupabaseProvider
import kotlinx.coroutines.launch

object SupabaseSettingsController {
    private const val SETUP_INSTRUCTIONS_URL =
        "https://github.com/streamflix-reborn/streamflix/blob/main/supabase_installation.md"

    fun bind(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        findPreference: (String) -> Preference?,
    ) {
        val context = fragment.requireContext()
        val url = findPreference("supabase_url") as? EditTextPreference
        val key = findPreference("supabase_public_key") as? EditTextPreference
        findPreference("supabase_instructions")?.setOnPreferenceClickListener {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(SETUP_INSTRUCTIONS_URL)),
            )
            true
        }
        findPreference("supabase_copy_sql")?.setOnPreferenceClickListener {
            val sql = context.resources.openRawResource(R.raw.supabase_setup)
                .bufferedReader()
                .use { it.readText() }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Supabase setup SQL", sql))
            Toast.makeText(context, R.string.supabase_copy_sql_done, Toast.LENGTH_SHORT).show()
            true
        }
        findPreference("supabase_open_sql")?.setOnPreferenceClickListener {
            val projectRef = Uri.parse(SupabaseProvider.getUrl(context)).host
                ?.takeIf { it.endsWith(".supabase.co") }
                ?.substringBefore(".supabase.co")
                ?.takeIf { it.isNotBlank() }
            val destination = if (projectRef != null) {
                "https://supabase.com/dashboard/project/$projectRef/sql/new"
            } else {
                "https://supabase.com/dashboard/projects"
            }
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination)))
            true
        }

        fun refresh() {
            val configured = SupabaseProvider.configured(context)
            url?.apply {
                text = SupabaseProvider.getUrl(context)
                summary = if (configured) SupabaseProvider.getUrl(context)
                else context.getString(R.string.supabase_url_summary)
            }
            key?.apply {
                text = SupabaseProvider.getPublicKey(context)
                summary = context.getString(R.string.supabase_public_key_summary)
            }
        }

        url?.setOnPreferenceChangeListener { _, newValue ->
            val newUrl = newValue.toString().trim()
            if (newUrl.isEmpty()) {
                scope.launch {
                    SupabaseProvider.clearConfig(context)
                    refresh()
                    CloudAccountSettingsController.bind(fragment, scope, findPreference)
                }
                Toast.makeText(context, R.string.supabase_config_removed, Toast.LENGTH_SHORT).show()
                return@setOnPreferenceChangeListener false
            }
            val currentKey = SupabaseProvider.getPublicKey(context)
            runCatching {
                SupabaseProvider.saveConfig(context, newUrl, currentKey)
            }.onSuccess {
                refresh()
                scope.launch {
                    SupabaseProvider.initialize(context)
                    CloudAccountSettingsController.bind(fragment, scope, findPreference)
                }
                Toast.makeText(context, R.string.supabase_config_saved, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, R.string.supabase_config_invalid, Toast.LENGTH_LONG).show()
            }
            false
        }

        key?.setOnPreferenceChangeListener { _, newValue ->
            val newKey = newValue.toString().trim()
            val currentUrl = SupabaseProvider.getUrl(context)
            runCatching {
                SupabaseProvider.saveConfig(context, currentUrl, newKey)
            }.onSuccess {
                refresh()
                scope.launch {
                    SupabaseProvider.initialize(context)
                    CloudAccountSettingsController.bind(fragment, scope, findPreference)
                }
                Toast.makeText(context, R.string.supabase_config_saved, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, R.string.supabase_config_invalid, Toast.LENGTH_LONG).show()
            }
            false
        }

        refresh()
        CloudAccountSettingsController.bind(fragment, scope, findPreference)
    }
}
