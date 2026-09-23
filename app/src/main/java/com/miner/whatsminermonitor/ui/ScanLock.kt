package com.miner.whatsminermonitor.ui

import android.content.Context

/**
 * قفل برنامه روی دکمهٔ اسکن: تا وقتی رمز درست وارد نشده باشد، اسکن کار نمی‌کند
 * تا غریبه نتواند از برنامه استفاده کند. رمز فقط یک‌بار پرسیده و ذخیره می‌شود؛
 * تا حذف و نصب مجدد برنامه دیگر پرسیده نمی‌شود.
 */
object ScanLock {
    private const val PREFS_NAME = "app_scan_lock"
    private const val KEY_UNLOCKED = "scan_unlocked"

    const val PASSWORD = "3528"

    fun isUnlocked(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_UNLOCKED, false)

    fun unlock(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_UNLOCKED, true).apply()
    }
}
