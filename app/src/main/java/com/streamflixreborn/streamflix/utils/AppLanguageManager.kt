package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import java.util.Locale

object AppLanguageManager {
    const val SYSTEM_LANGUAGE = "system"

    private val preferredLanguageOrder = listOf(
        "en",
        "ar",
        "de",
        "es",
        "fr",
        "it",
        "pl",
    )

    fun wrap(context: Context): Context {
        val languageTag = getSelectedLanguage(context)
            .takeUnless { it == SYSTEM_LANGUAGE }
            ?: return context

        val locale = Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)

        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)

        return context.createConfigurationContext(configuration)
    }

    fun getSelectedLanguage(context: Context): String {
        val profileId = context
            .getSharedPreferences("${BuildConfig.APPLICATION_ID}.profile_global", Context.MODE_PRIVATE)
            .getString("ACTIVE_PROFILE_ID", null)

        val storedLanguage = profileId?.let { id ->
            context
                .getSharedPreferences("${BuildConfig.APPLICATION_ID}.preferences_$id", Context.MODE_PRIVATE)
                .getString("CURRENT_LANGUAGE", null)
        }?.takeIf { it == SYSTEM_LANGUAGE || it in getAvailableLanguageTags(context) }
            ?: context
                .getSharedPreferences("${BuildConfig.APPLICATION_ID}.preferences", Context.MODE_PRIVATE)
                .getString("CURRENT_LANGUAGE", null)
                ?.takeIf { it == SYSTEM_LANGUAGE || it in getAvailableLanguageTags(context) }

        return storedLanguage ?: SYSTEM_LANGUAGE
    }

    fun getProfileLanguage(context: Context, profileId: String): String {
        return context
            .getSharedPreferences("${BuildConfig.APPLICATION_ID}.preferences_$profileId", Context.MODE_PRIVATE)
            .getString("CURRENT_LANGUAGE", null)
            ?.takeIf { it == SYSTEM_LANGUAGE || it in getAvailableLanguageTags(context) }
            ?: SYSTEM_LANGUAGE
    }

    fun setSelectedLanguage(languageTag: String?) {
        val normalized = when (languageTag) {
            null, SYSTEM_LANGUAGE -> SYSTEM_LANGUAGE
            else -> normalizeLanguageTag(languageTag) ?: SYSTEM_LANGUAGE
        }
        UserPreferences.currentLanguage = normalized
    }

    fun buildLanguageEntries(context: Context): Array<CharSequence> {
        val configuration = context.resources.configuration
        val displayLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            configuration.locale
        } ?: Locale.getDefault()
        return buildList {
            add(context.getString(R.string.settings_app_language_system))
            addAll(
                getAvailableLanguageTags(context).map { languageTag ->
                    languageTag.toLocalizedDisplayName(context, displayLocale)
                }
            )
        }.toTypedArray()
    }

    fun buildLanguageValues(context: Context): Array<CharSequence> = buildList {
        add(SYSTEM_LANGUAGE)
        addAll(getAvailableLanguageTags(context))
    }.toTypedArray()

    private fun getAvailableLanguageTags(context: Context): List<String> {
        val discoveredLanguages = context.assets.locales
            .mapNotNull(::normalizeLanguageTag)
            .toMutableSet()

        // English is provided by the base `values/` resources even when no explicit locale folder exists.
        discoveredLanguages.add("en")

        return preferredLanguageOrder.filter { it in discoveredLanguages }
    }

    private fun normalizeLanguageTag(tag: String): String? {
        if (tag.isBlank()) return null

        val normalizedTag = when {
            tag.startsWith("b+") -> tag.removePrefix("b+").replace('+', '-')
            else -> tag.replace('_', '-')
        }

        return Locale.forLanguageTag(normalizedTag)
            .language
            .takeUnless { it.isBlank() || it == "und" }
    }

    private fun String.toLocalizedDisplayName(
        context: Context,
        displayLocale: Locale,
    ): String {
        val stringRes = when (this) {
            "en" -> R.string.settings_app_language_english
            "ar" -> R.string.settings_app_language_arabic
            "de" -> R.string.settings_app_language_german
            "es" -> R.string.settings_app_language_spanish
            "fr" -> R.string.settings_app_language_french
            "it" -> R.string.settings_app_language_italian
            "pl" -> R.string.settings_app_language_polish
            else -> null
        }

        return stringRes
            ?.let(context::getString)
            ?: Locale.forLanguageTag(this)
                .getDisplayLanguage(displayLocale)
                .replaceFirstChar { char ->
                    if (char.isLowerCase()) {
                        char.titlecase(displayLocale)
                    } else {
                        char.toString()
                    }
                }
    }
}
