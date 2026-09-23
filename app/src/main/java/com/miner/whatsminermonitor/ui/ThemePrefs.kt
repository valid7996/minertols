package com.miner.whatsminermonitor.ui

import android.content.Context

/**
 * آپشن انتخاب تم برنامه: ۰ = سیستم، ۱ = روشن، ۲ = تاریک
 * یک گزینهٔ جدید و مستقل است و کاری با تنظیمات قبلی برنامه ندارد
 */
object ThemePrefs {
    private const val PREFS_NAME = "app_theme"
    private const val KEY_MODE = "mode"

    fun read(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_MODE, 0)

    fun write(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MODE, mode).apply()
    }
}
