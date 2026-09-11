package com.suseoaa.locationspoofer.utils

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

class LocaleUtils {
    companion object {
        fun wrap(context: Context, language: String): Context {
            val locale = if (language.isEmpty()) {
                // 如果没有设置，使用系统默认
                Configuration(context.resources.configuration).locales[0] ?: Locale.getDefault()
            } else {
                Locale.forLanguageTag(language)
            }

            Locale.setDefault(locale)

            val resources = context.resources
            val configuration = Configuration(resources.configuration)

            configuration.setLocale(locale)
            val localeList = LocaleList(locale)
            configuration.setLocales(localeList)
            configuration.setLayoutDirection(locale)

            return context.createConfigurationContext(configuration)
        }
    }
}
