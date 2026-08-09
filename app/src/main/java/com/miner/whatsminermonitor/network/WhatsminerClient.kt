package com.miner.whatsminermonitor.network

import com.miner.whatsminermonitor.model.HashboardInfo
import com.miner.whatsminermonitor.model.MinerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * کلاینت ارتباط با ماینرهای Whatsminer از طریق پروتکل JSON روی TCP (سازگار با cgminer API).
 *
 * دستورات خواندنی (read-only) مثل summary / devs / version به صورت پیش‌فرض روی هر
 * ماینر Whatsminai فعال هستند و نیازی به رمزنگاری یا لاگین ندارند. اگر فریمور شما
 * این دستورات ساده متنی را قبول نکرد (خطای اتصال یا پاسخ خالی گرفتید)، یعنی API
 * رمزنگاری‌شده (نسخه‌های جدیدتر Whatsminer) فعال است و باید طبق مستندات رسمی
 * Whatsminer API از توکن/AES استفاده کرد.
 *
 * منبع پروتکل: مستندات رسمی Whatsminer API (whatsminer.com) - پورت پیش‌فرض 4028
 */
object WhatsminerClient {

    const val API_PORT = 4028
    private const val CONNECT_TIMEOUT_MS = 2500
    private const val READ_TIMEOUT_MS = 3000

    /**
     * بررسی می‌کند که آیا پورت API ماینر روی این IP باز است یا نه (برای اسکن سریع شبکه).
     */
    suspend fun isPortOpen(ip: String, timeoutMs: Int = 400): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, API_PORT), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * ارسال یک دستور خام cgminer-style و دریافت پاسخ متنی خام.
     */
    private suspend fun sendRawCommand(ip: String, command: String): String? = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, API_PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS

                socket.getOutputStream().use { out ->
                    out.write(command.toByteArray(Charsets.UTF_8))
                    out.flush()

                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    val sb = StringBuilder()
                    val buffer = CharArray(4096)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read == -1) break
                        sb.append(buffer, 0, read)
                    }
                    // پاسخ ماینر معمولا با کاراکتر null خاتمه پیدا می‌کند
                    sb.toString().trim('\u0000', '\n', '\r', ' ')
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun fetchSummary(ip: String): JSONObject? {
        val raw = sendRawCommand(ip, """{"command":"summary"}""") ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    suspend fun fetchDevs(ip: String): JSONObject? {
        val raw = sendRawCommand(ip, """{"command":"devs"}""") ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    suspend fun fetchVersion(ip: String): JSONObject? {
        val raw = sendRawCommand(ip, """{"command":"version"}""") ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    /**
     * اطلاعات کامل یک ماینر روی این IP را جمع‌آوری می‌کند (summary + devs + version).
     * چون نام کلیدهای JSON بین نسخه‌های مختلف فریمور Whatsminer کمی فرق می‌کند،
     * این تابع چند نام محتمل را برای هر فیلد امتحان می‌کند.
     */
    suspend fun queryMiner(ip: String): MinerInfo {
        val summaryRoot = fetchSummary(ip)
        if (summaryRoot == null) {
            return MinerInfo(ip = ip, isReachable = false, errorMessage = "پاسخی از دستگاه دریافت نشد")
        }

        val summaryObj = firstArrayObject(summaryRoot, "SUMMARY")
        val devsRoot = fetchDevs(ip)
        val devsArray = devsRoot?.optJSONArray("DEVS")
        val versionRoot = fetchVersion(ip)
        val versionObj = firstArrayObject(versionRoot, "VERSION")

        val hashboards = parseHashboards(devsArray)
        val avgTemp = hashboards.mapNotNull { it.temperaturePcb ?: it.temperatureChip }
            .takeIf { it.isNotEmpty() }?.average()

        val totalHashrate = findDouble(summaryObj, listOf("MHS 5s", "MHS av", "GHS 5s", "GHS av"))
            ?.let { value ->
                // اگر واحد MHS بود به GHS تبدیل کن، اگر از قبل GHS بود همان را برگردان
                if (summaryObj?.has("GHS 5s") == true || summaryObj?.has("GHS av") == true) value
                else value / 1000.0
            } ?: hashboards.mapNotNull { it.hashrateGhs }.takeIf { it.isNotEmpty() }?.sum()

        return MinerInfo(
            ip = ip,
            isReachable = true,
            elapsedSeconds = findLong(summaryObj, listOf("Elapsed")),
            fanSpeedIn = findInt(summaryObj, listOf("Fan Speed In", "Fan1")),
            fanSpeedOut = findInt(summaryObj, listOf("Fan Speed Out", "Fan2")),
            powerWatt = findInt(summaryObj, listOf("Power", "Power Curr")),
            averageTemperature = avgTemp,
            totalHashrateGhs = totalHashrate,
            firmwareVersion = findString(versionObj, listOf("Firmware Version", "FIRMWARE_VERSION", "USER")),
            minerType = findString(versionObj, listOf("Type", "MinerType", "PROD")),
            hashboards = hashboards
        )
    }

    private fun parseHashboards(devsArray: JSONArray?): List<HashboardInfo> {
        if (devsArray == null) return emptyList()
        val list = mutableListOf<HashboardInfo>()
        for (i in 0 until devsArray.length()) {
            val dev = devsArray.optJSONObject(i) ?: continue
            val id = findInt(dev, listOf("ID", "ASC")) ?: i
            val hashGhs = findDouble(dev, listOf("MHS 5s", "MHS av"))?.div(1000.0)
                ?: findDouble(dev, listOf("GHS 5s", "GHS av"))
            list.add(
                HashboardInfo(
                    id = id,
                    temperaturePcb = findDouble(dev, listOf("Temperature", "Temperature0", "Temp PCB")),
                    temperatureChip = findDouble(dev, listOf("Chip Temp Avg", "Temperature Chip", "Temp Chip")),
                    hashrateGhs = hashGhs,
                    frequencyMhz = findDouble(dev, listOf("Chip Frequency", "Frequency")),
                    effectiveChips = findInt(dev, listOf("Effective Chips", "Chip Count")),
                    status = findString(dev, listOf("Status", "Enabled"))
                )
            )
        }
        return list
    }

    private fun firstArrayObject(root: JSONObject?, key: String): JSONObject? {
        val arr = root?.optJSONArray(key) ?: return null
        return if (arr.length() > 0) arr.optJSONObject(0) else null
    }

    private fun findDouble(obj: JSONObject?, keys: List<String>): Double? {
        if (obj == null) return null
        for (k in keys) if (obj.has(k) && !obj.isNull(k)) {
            val v = obj.opt(k)
            val d = when (v) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else -> null
            }
            if (d != null) return d
        }
        return null
    }

    private fun findInt(obj: JSONObject?, keys: List<String>): Int? =
        findDouble(obj, keys)?.toInt()

    private fun findLong(obj: JSONObject?, keys: List<String>): Long? =
        findDouble(obj, keys)?.toLong()

    private fun findString(obj: JSONObject?, keys: List<String>): String? {
        if (obj == null) return null
        for (k in keys) if (obj.has(k) && !obj.isNull(k)) {
            val v = obj.optString(k)
            if (v.isNotBlank()) return v
        }
        return null
    }

    private fun String.toDoubleOrNull(): Double? = try {
        this.trim().toDouble()
    } catch (e: NumberFormatException) {
        null
    }
}
