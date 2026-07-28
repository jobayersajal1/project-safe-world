package com.safeworld.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Applies the user's chosen UI language by wrapping a `Context` with an
 * overridden locale.
 *
 * Done this way rather than with `AppCompatDelegate.setApplicationLocales`
 * because that API only takes effect for activities extending
 * `AppCompatActivity`, and this app is pure Compose on `ComponentActivity`.
 * `createConfigurationContext` works on every supported API level and keeps the
 * dependency list as it is.
 *
 * Reads `SharedPreferences` directly instead of going through [SettingsStore]:
 * this runs from `attachBaseContext`, before the activity's context is usable
 * for much, and the service reads it off the main thread. It's a single string.
 */
object LocaleHelper {

    /** The languages offered in Settings. "" means "follow the system". */
    val SUPPORTED = listOf("", "en", "bn", "es", "ar")

    /** The stored tag, or "" when the user hasn't chosen (the default). */
    fun storedTag(context: Context): String =
        context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SettingsStore.KEY_LANGUAGE, "")
            .orEmpty()

    /**
     * [context] with the stored language applied, or unchanged when the user is
     * following the system — which is the default, so a fresh install already
     * comes up in the phone's language with no configuration.
     */
    fun wrap(context: Context): Context {
        val tag = storedTag(context)
        if (tag.isEmpty()) return context
        return withLocale(context, Locale.forLanguageTag(tag))
    }

    private fun withLocale(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        // setLayoutDirection matters for Arabic: the configuration's direction
        // is what Compose reads to mirror the layout, and it does not follow
        // from the locale automatically here.
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    /**
     * Display name of a language in *its own* language, so someone who has
     * landed in a language they can't read can still find their way out.
     */
    fun displayName(tag: String, context: Context): String {
        if (tag.isEmpty()) return context.getString(R.string.language_system)
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayLanguage(locale).replaceFirstChar { it.uppercase(locale) }
    }
}
