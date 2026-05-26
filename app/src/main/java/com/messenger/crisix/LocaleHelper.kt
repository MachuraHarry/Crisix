package com.messenger.crisix

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Hilfsklasse zum Umschalten der App-Sprache.
 * Speichert die ausgewählte Sprache in SharedPreferences
 * und aktualisiert das Locale der App.
 */
object LocaleHelper {

    private const val PREF_NAME = "crisix_locale"
    private const val KEY_LANGUAGE = "language_code"

    /**
     * Verfügbare Sprachen.
     */
    enum class AppLanguage(val code: String, val displayName: String) {
        GERMAN("de", "Deutsch"),
        ENGLISH("en", "English")
    }

    /**
     * Aktuelle Sprache aus SharedPreferences laden.
     */
    fun getLanguage(context: Context): AppLanguage {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_LANGUAGE, "de") ?: "de"
        return when (code) {
            "en" -> AppLanguage.ENGLISH
            else -> AppLanguage.GERMAN
        }
    }

    /**
     * Sprache setzen und in SharedPreferences speichern.
     */
    fun setLanguage(context: Context, language: AppLanguage) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
        updateLocale(context, language)
    }

    /**
     * App-Kontext mit neuem Locale aktualisieren.
     */
    fun updateLocale(context: Context, language: AppLanguage) {
        val locale = Locale.forLanguageTag(language.code)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    /**
     * Context mit dem gespeicherten Locale versehen.
     * Wird in der Activity beim onCreate aufgerufen.
     */
    fun wrapContext(context: Context): Context {
        val language = getLanguage(context)
        val locale = Locale.forLanguageTag(language.code)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
