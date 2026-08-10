package com.miner.whatsminermonitor.network

import com.miner.whatsminermonitor.model.HashboardInfo
import com.miner.whatsminermonitor.model.MinerInfo
import com.miner.whatsminermonitor.model.PoolEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * نتیجه یک دستور ممتاز (privileged) مثل ریبوت یا تغییر پول
 */
data class PrivilegedResult(
    val success: Boolean,
    val message: String,
    val wrongPassword: Boolean = false
)

private data class TokenInfo(val time: String, val salt: String, val newSalt: String)

/**
 * کلاینت ارتباط با API دستگاه Whatsminer روی پورت TCP 4028
 * بر اساس مستندات رسمی MicroBT (Whatsminer API v2.0.5):
 *  - همه دستورها با کلید "cmd" ارسال می‌شوند (نه "command")
 *  - دستورهای نوشتنی (reboot / update_pools / ...) نیاز به توکن دارند که
 *    با «رمز عبور ادمین دستگاه» (پیش‌فرض admin) امضا می‌شود
 */
object WhatsminerClient {

    const val API_PORT = 4028
    private const val CONNECT_TIMEOUT_MS = 2500
    private const val READ_TIMEOUT_MS = 3000

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
                    sb.toString().trim('\u0000', '\n', '\r', ' ')
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // ================= دستورهای خواندنی (Readable API) =================

    suspend fun fetchSummary(ip: String): JSONObject? {
        val raw = sendRawCommand(ip, """{"cmd":"summary"}""") ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    suspend fun fetchDevs(ip: String): JSONObject? {
        val raw = sendRawCommand(ip, """{"cmd":"devs"}""") ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    // نسخه فریمور و پلتفرم دستگاه
    suspend fun fetchVersion(ip: String): JSONObject? {
        val raw = sendRawCommand(ip, """{"cmd":"get_version"}""") ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    // جزئیات هش‌برد؛ شامل مدل دقیق دستگاه (مثلاً M31S+VE40)
    suspend fun fetchDevDetails(ip: String): JSONObject? {
        val raw = sendRawCommand(ip, """{"cmd":"devdetails"}""") ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    // اطلاعات منبع تغذیه (پاور)
    suspend fun fetchPsu(ip: String): JSONObject? {
        val raw = sendRawCommand(ip, """{"cmd":"get_psu"}""") ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    // اطلاعات شبکه (از جمله MAC)
    suspend fun fetchMinerInfo(ip: String): JSONObject? {
        val raw = sendRawCommand(ip, """{"cmd":"get_miner_info","info":"mac,ip,hostname"}""") ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    suspend fun fetchPools(ip: String): JSONObject? {
        val raw = sendRawCommand(ip, """{"cmd":"pools"}""") ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    // فهرست کدهای خطای فعال دستگاه
    suspend fun fetchErrorCode(ip: String): JSONObject? {
        val raw = sendRawCommand(ip, """{"cmd":"get_error_code"}""") ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    suspend fun queryMiner(ip: String): MinerInfo {
        val summaryRoot = fetchSummary(ip)
        if (summaryRoot == null) {
            return MinerInfo(ip = ip, isReachable = false, errorMessage = "پاسخی از دستگاه دریافت نشد")
        }

        val summaryObj = firstArrayObject(summaryRoot, "SUMMARY")
        val devsRoot = fetchDevs(ip)
        val devsArray = devsRoot?.optJSONArray("DEVS")
        val versionRoot = fetchVersion(ip)
        val versionMsg = versionRoot?.optJSONObject("Msg")
        val devDetailsRoot = fetchDevDetails(ip)
        val devDetailsObj = firstArrayObject(devDetailsRoot, "DEVDETAILS")
        val psuRoot = fetchPsu(ip)
        val psuMsg = psuRoot?.optJSONObject("Msg")
        val minerInfoRoot = fetchMinerInfo(ip)
        val minerInfoMsg = minerInfoRoot?.optJSONObject("Msg")
        val poolsRoot = fetchPools(ip)
        val poolObj = firstArrayObject(poolsRoot, "POOLS")
        val errorRoot = fetchErrorCode(ip)
        val errorCodes = parseErrorCodes(errorRoot)

        val hashboards = parseHashboards(devsArray)
        val avgTemp = hashboards.mapNotNull { it.temperaturePcb ?: it.temperatureChip }
            .takeIf { it.isNotEmpty() }?.average()

        // GHS 5s (لحظه‌ای) - مقدار خام از دستگاه به MH/s است؛ برای تبدیل به GH/s بر ۱۰۰۰ تقسیم می‌شود
        val totalHashrate = findDouble(summaryObj, listOf("MHS 5s"))
            ?.div(1000.0)
            ?: hashboards.mapNotNull { it.hashrateGhs }.takeIf { it.isNotEmpty() }?.sum()

        // GHS av (میانگین)
        val ghsAv = findDouble(summaryObj, listOf("MHS av"))?.div(1000.0)

        // زمان پاسخ پول
        val poolResponseMs = poolObj?.let { findInt(it, listOf("Last Share Time")) }

        return MinerInfo(
            ip = ip,
            isReachable = true,
            elapsedSeconds = findLong(summaryObj, listOf("Elapsed")),
            fanSpeedIn = findInt(summaryObj, listOf("Fan Speed In")),
            fanSpeedOut = findInt(summaryObj, listOf("Fan Speed Out")),
            powerWatt = findInt(summaryObj, listOf("Power")),
            averageTemperature = avgTemp ?: findDouble(summaryObj, listOf("Temperature")),
            totalHashrateGhs = totalHashrate,
            ghsAverage = ghsAv,
            firmwareVersion = findString(versionMsg, listOf("fw_ver")),
            minerType = findString(devDetailsObj, listOf("Model")),
            controlBoard = findString(versionMsg, listOf("platform")),
            accepted = findInt(summaryObj, listOf("Accepted")),
            rejected = findInt(summaryObj, listOf("Rejected")),
            poolResponseMs = poolResponseMs,
            hashboards = hashboards,
            macAddress = findString(minerInfoMsg, listOf("mac")),
            powerSupplyModel = findString(psuMsg, listOf("name", "model")),
            poolWorkerName = findString(poolObj, listOf("User")),
            poolUrl = findString(poolObj, listOf("URL")),
            errorCodes = errorCodes
        )
    }

    // پارس کردن پاسخ get_error_code به فهرستی از کدهای عددی خطای فعال
    // طبق مستندات رسمی، ساختار پاسخ یک شیء است: {"error_code": {"<code>": "<timestamp>", ...}}
    // اما برای اطمینان، حالت آرایه‌ای قدیمی هم به‌عنوان پشتیبان پارس می‌شود
    private fun parseErrorCodes(root: JSONObject?): List<Int> {
        if (root == null) return emptyList()
        val msg = root.optJSONObject("Msg") ?: root
        val result = sortedSetOf<Int>()

        val obj = msg.optJSONObject("error_code")
        if (obj != null) {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val code = keys.next().trim().toIntOrNull()
                if (code != null && code != 0) result.add(code)
            }
            return result.toList()
        }

        val arr = msg.optJSONArray("error_code") ?: return emptyList()
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            val code: Int? = when (item) {
                is JSONObject -> listOf(
                    item.optString("error_code"),
                    item.optString("code"),
                    item.optString("ErrCode")
                ).firstOrNull { it.isNotBlank() }?.toIntOrNull()
                is Number -> item.toInt()
                is String -> item.trim().toIntOrNull()
                else -> null
            }
            if (code != null && code != 0) result.add(code)
        }
        return result.toList()
    }

    private fun parseHashboards(devsArray: JSONArray?): List<HashboardInfo> {
        if (devsArray == null) return emptyList()
        val list = mutableListOf<HashboardInfo>()
        for (i in 0 until devsArray.length()) {
            val dev = devsArray.optJSONObject(i) ?: continue
            val id = findInt(dev, listOf("ASC", "ID")) ?: i
            val hashGhs = findDouble(dev, listOf("MHS 5s", "MHS av"))?.div(1000.0)
            list.add(
                HashboardInfo(
                    id = id,
                    temperaturePcb = findDouble(dev, listOf("Temperature")),
                    temperatureChip = findDouble(dev, listOf("Chip Temp Avg")),
                    hashrateGhs = hashGhs,
                    frequencyMhz = findDouble(dev, listOf("Chip Frequency")),
                    effectiveChips = findInt(dev, listOf("Effective Chips")),
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

    // ================= دستورهای ممتاز (Writable API) =================
    // طبق مستندات رسمی MicroBT:
    // 1) {"cmd":"get_token"} -> {"Msg":{"time","salt","newsalt"}}
    // 2) key  = md5(salt + password)
    //    sign = md5(newsalt + key + آخرین ۴ کاراکتر time)
    // 3) دستور نهایی با فیلد "token": sign ارسال می‌شود

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private suspend fun getToken(ip: String): TokenInfo? {
        val raw = sendRawCommand(ip, """{"cmd":"get_token"}""") ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val msg = json.optJSONObject("Msg") ?: return null
        val time = msg.optString("time")
        val salt = msg.optString("salt")
        val newSalt = msg.optString("newsalt")
        if (time.isBlank() || salt.isBlank() || newSalt.isBlank()) return null
        return TokenInfo(time, salt, newSalt)
    }

    private fun computeSign(password: String, time: String, salt: String, newSalt: String): String {
        val key = md5Hex(salt + password)
        val timeSuffix = if (time.length >= 4) time.takeLast(4) else time
        return md5Hex(newSalt + key + timeSuffix)
    }

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * یک دستور ممتاز را با گرفتن توکن تازه و امضا کردن آن با رمز عبور دستگاه اجرا می‌کند.
     * buildCommand باید JSON کامل شامل فیلد "token" را بسازد.
     */
    private suspend fun sendPrivileged(
        ip: String,
        password: String,
        buildCommand: (token: String) -> String
    ): PrivilegedResult {
        val token = getToken(ip)
            ?: return PrivilegedResult(false, "اتصال برای دریافت توکن از دستگاه ناموفق بود")
        val sign = computeSign(password, token.time, token.salt, token.newSalt)
        val commandJson = buildCommand(sign)
        val raw = sendRawCommand(ip, commandJson)
            ?: return PrivilegedResult(false, "پاسخی از دستگاه دریافت نشد")
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return PrivilegedResult(false, "پاسخ نامعتبر از دستگاه")

        val code = json.optInt("Code", -1)
        val status = json.optString("STATUS")
        return when {
            code == 45 -> PrivilegedResult(false, "رمز عبور اشتباه است", wrongPassword = true)
            code == 131 || status.equals("S", ignoreCase = true) ->
                PrivilegedResult(true, "عملیات با موفقیت انجام شد")
            else -> PrivilegedResult(false, json.optString("Msg").ifBlank { "خطای نامشخص از دستگاه (کد $code)" })
        }
    }

    suspend fun reboot(ip: String, password: String): PrivilegedResult =
        sendPrivileged(ip, password) { token -> """{"token":"$token","cmd":"reboot"}""" }

    /**
     * تعویض پول‌های ماینینگ دستگاه (حداکثر ۳ پول). عملیات بلافاصله پس از اجرا اعمال می‌شود.
     */
    suspend fun updatePools(ip: String, password: String, pools: List<PoolEntry>): PrivilegedResult {
        if (pools.isEmpty()) return PrivilegedResult(false, "هیچ پولی برای تنظیم مشخص نشده است")
        val padded = (0 until 3).map { idx -> pools.getOrNull(idx) ?: pools.last() }

        return sendPrivileged(ip, password) { token ->
            val sb = StringBuilder()
            sb.append("""{"token":"$token","cmd":"update_pools"""")
            padded.forEachIndexed { idx, pool ->
                val n = idx + 1
                sb.append(""","pool$n":"${jsonEscape(pool.url)}"""")
                sb.append(""","worker$n":"${jsonEscape(pool.worker)}"""")
                sb.append(""","passwd$n":"${jsonEscape(pool.pass)}"""")
            }
            sb.append("}")
            sb.toString()
        }
    }
}
