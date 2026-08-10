package com.miner.whatsminermonitor.ui

import android.content.Context

/**
 * ذخیره‌سازی ساده رمز عبور ادمین هر دستگاه (کلید = IP دستگاه).
 * پیش‌فرض کارخانه‌ای Whatsminer برای admin برابر "admin" است.
 */
object CredentialsStore {
    private const val PREFS_NAME = "miner_credentials"
    const val DEFAULT_PASSWORD = "admin"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPassword(context: Context, ip: String): String =
        prefs(context).getString(ip, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD

    fun setPassword(context: Context, ip: String, password: String) {
        prefs(context).edit().putString(ip, password).apply()
    }
}
