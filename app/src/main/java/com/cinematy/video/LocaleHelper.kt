package com.cinematy.video

import android.content.Context
import android.content.res.Configuration
import com.cinematy.video.data.Prefs
import java.util.Locale

object LocaleHelper {
    /** Wraps the base context with the user's stored language (0 = follow device). */
    fun wrap(context: Context): Context {
        val tag = when (Prefs(context).language) {
            1 -> "ar"
            2 -> "en"
            else -> return context
        }
        val locale = Locale(tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }
}
