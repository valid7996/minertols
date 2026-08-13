package com.miner.whatsminermonitor.ui

import android.content.Context

/**
 * ذخیره‌سازی رمز عبور ادمین ماینرها.
 * پیش‌فرض کارخانه‌ای Whatsminer برای ادمین "admin"/"admin" است (هم API و هم پنل وب دستگاه).
 *
 * چون معمولاً همهٔ دستگاه‌های یک فارم رمز یکسان دارند، یک رمز «سراسری» ذخیره می‌شود که به‌صورت
 * پیش‌فرض برای همهٔ ماینرهای پیداشده امتحان می‌شود - یعنی کاربر لازم نیست برای تک‌تک دستگاه‌ها
 * جداگانه رمز وارد کند. وقتی کاربر رمز درستی را برای یک دستگاه وارد می‌کند، همان رمز به‌عنوان
 * رمز سراسری هم ذخیره می‌شود تا دستگاه‌های بعدی که کشف می‌شوند اول همان را امتحان کنند و دوباره
 * درخواست رمز نشود. اگر رمز سراسری برای یک دستگاه خاص جواب نداد (رمز آن با بقیه فرق دارد)،
 * رمز جدید فقط مخصوص همان IP ذخیره می‌شود تا رمز سراسری برای بقیهٔ دستگاه‌ها دست‌نخورده بماند.
 */
object CredentialsStore {
    private const val PREFS_NAME = "miner_credentials"
    private const val GLOBAL_KEY = "__global_password__"
    const val DEFAULT_USERNAME = "admin"
    const val DEFAULT_PASSWORD = "admin"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** رمز سراسری فعلی (که برای دستگاه‌های جدید هم امتحان می‌شود) */
    fun getGlobalPassword(context: Context): String =
        prefs(context).getString(GLOBAL_KEY, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD

    /**
     * رمزی که باید برای این IP امتحان شود: اگر قبلاً برای همین دستگاه رمز اختصاصی متفاوتی
     * تأیید شده، همان؛ وگرنه رمز سراسری (که ممکن است همان پیش‌فرض "admin" یا رمزی باشد که
     * کاربر قبلاً برای یکی از دستگاه‌های دیگر وارد کرده است).
     */
    fun getPassword(context: Context, ip: String): String =
        prefs(context).getString(ip, null) ?: getGlobalPassword(context)

    /**
     * رمز تأییدشدهٔ یک دستگاه را ذخیره می‌کند. هم به‌عنوان رمز اختصاصی همین IP و هم به‌عنوان
     * رمز سراسری جدید (چون به احتمال زیاد بقیهٔ دستگاه‌های همین فارم هم همین رمز را دارند؛ این
     * دقیقاً همان چیزی است که از درخواست مکرر رمز برای هر دستگاه جلوگیری می‌کند).
     */
    fun setPassword(context: Context, ip: String, password: String) {
        prefs(context).edit()
            .putString(ip, password)
            .putString(GLOBAL_KEY, password)
            .apply()
    }
}
