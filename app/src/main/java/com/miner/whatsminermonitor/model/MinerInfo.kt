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
    val totalHashrateGhs: Double? = null,    // هشریت کل دستگاه (GH/s) - GHS 5s
    val ghsAverage: Double? = null,          // هشریت میانگین (GH/s) - GHS av
    val firmwareVersion: String? = null,
    val minerType: String? = null,           // مدل دستگاه
    val controlBoard: String? = null,        // نسخه کنترل‌برد
    val accepted: Int? = null,               // تعداد اکسپت‌ها
    val rejected: Int? = null,               // تعداد رجکت‌ها
    val poolResponseMs: Int? = null,         // زمان پاسخ پول (ms)
    val hashboards: List<HashboardInfo> = emptyList(),
    val macAddress: String? = null,          // آدرس MAC (در صورت موجود بودن در پاسخ دستگاه)
    val powerSupplyModel: String? = null,    // مدل پاور (در صورت موجود بودن)
    val poolWorkerName: String? = null,      // نام Worker تنظیم‌شده در استخر
    val poolUrl: String? = null,             // آدرس استخر متصل
    val errorCodes: List<Int> = emptyList()  // کدهای خطای فعال دستگاه
) {
    // آیا دستگاه سالم است (بدون کد خطای فعال)؟
    val isHealthy: Boolean
        get() = isReachable && errorCodes.isEmpty()

    val errorDetails: List<WhatsminerErrorDetail>
        get() = errorCodes.map { WhatsminerErrorCatalog.describe(it) }

    val totalHashrateThs: Double?
        get() = totalHashrateGhs?.div(1000.0)

    val ghsAverageThs: Double?
        get() = ghsAverage?.div(1000.0)

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

    // محاسبه درآمد روزانه تخمینی (BTC) بر اساس هشریت
    // فرمول ساده‌شده: (hashrate_TH/s / network_hashrate_TH/s) * block_reward * blocks_per_day
    // از یک ضریب تخمینی استفاده می‌کنیم که به‌صورت realtime از قیمت استخراج می‌شود
    fun estimatedDailyBtc(networkHashrateEh: Double = 800.0): Double {
        val ths = ghsAverageThs ?: totalHashrateThs ?: return 0.0
        // blocks per day ~= 144, reward = 3.125 BTC
        // income = (ths / (networkHashrateEh * 1_000_000)) * 144 * 3.125
        return (ths / (networkHashrateEh * 1_000_000.0)) * 144.0 * 3.125
    }
}
