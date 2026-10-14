package com.shixiaoshi0417.lunexsu.ui.theme.pack

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.os.Handler
import android.os.Looper
import java.util.Locale

internal object ThemePackLanguage {
    const val KEY = "fpt_app_language"

    fun validate(tags: String): String {
        require(tags.length <= 256) { "Language tags are too long" }
        if (tags.isEmpty()) return tags
        return tags.split(',').joinToString(",") {
            require(it.isNotBlank()) { "Empty language tag" }
            Locale.Builder().setLanguageTag(it).build().toLanguageTag()
        }
    }

    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val tags = context.getSharedPreferences("settings", 0).getString(KEY, "").orEmpty()
        if (tags.isEmpty()) return context
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(tags))
        })
    }

    /** Called after a successful preference publication, never while an import is only prepared. */
    fun refresh(context: Context, previous: String) {
        val tags = context.getSharedPreferences("settings", 0).getString(KEY, "").orEmpty()
        if (tags == previous) return
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tags)
        } else {
            var base = context
            while (base is ContextWrapper && base !is Activity) base = base.baseContext
            (base as? Activity)?.let { activity -> Handler(Looper.getMainLooper()).post { activity.recreate() } }
        }
    }
}
