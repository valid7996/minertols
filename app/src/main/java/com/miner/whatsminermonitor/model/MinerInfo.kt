package com.miner.whatsminermonitor.model

/**
 * اطلاعات یک هش‌بورد (برد ماینینگ) داخل دستگاه ماینر
 */
data class HashboardInfo(
    val id: Int,
    val temperaturePcb: Double? = null,      // دمای برد (PCB)
    val temperatureChip: Double? = null,     // دمای چیپ
    val hashrateGhs: Double? = null,         // هشریت این برد (GH/s)
    val frequencyMhz: Double? = null,        // فرکانس کاری چیپ‌ها
    val effectiveChips: Int? = null,         // تعداد چیپ‌های فعال
    val status: String? = null               // وضعیت برد (مثلا Alive)
)

/**
 * اطلاعات کامل یک دستگاه ماینر Whatsminer
 */
data class MinerInfo(
    val ip: String,
    val isReachable: Boolean = true,
    val errorMessage: String? = null,
    val elapsedSeconds: Long? = null,        // مدت زمان روشن بودن (uptime) بر حسب ثانیه
    val fanSpeedIn: Int? = null,             // دور فن ورودی (RPM)
    val fanSpeedOut: Int? = null,            // دور فن خروجی (RPM)
    val powerWatt: Int? = null,              // مصرف برق تقریبی (وات)
    val averageTemperature: Double? = null,  // میانگین دمای هش‌بردها
    val totalHashrateGhs: Double? = null,     // هشریت کل دستگاه (GH/s)
    val firmwareVersion: String? = null,
    val minerType: String? = null,
    val hashboards: List<HashboardInfo> = emptyList()
) {
    val totalHashrateThs: Double?
        get() = totalHashrateGhs?.div(1000.0)

    fun uptimeFormatted(): String {
        val secs = elapsedSeconds ?: return "—"
        val days = secs / 86400
        val hours = (secs % 86400) / 3600
        val minutes = (secs % 3600) / 60
        return buildString {
            if (days > 0) append("${days}روز ")
            append("${hours}س ${minutes}د")
        }
    }
}
