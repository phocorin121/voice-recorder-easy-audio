package com.phocorin.easyaudio

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

object AppLanguage {
    fun code(context: Context): String = if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
    } else context.getSharedPreferences("settings", 0).getString("language", "") ?: ""

    fun set(context: Context, code: String) {
        if (AppLanguage.code(context) == code) return
        context.getSharedPreferences("settings", 0).edit().putString("language", code).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(code)
        } else if (context is Activity) context.recreate()
    }
}

fun Context.withAppLanguage(): Context {
    val code = AppLanguage.code(this)
    if (code.isEmpty()) return this
    val configuration = Configuration(resources.configuration)
    configuration.setLocales(LocaleList.forLanguageTags(code))
    return createConfigurationContext(configuration)
}

