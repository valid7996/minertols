package com.miner.whatsminermonitor.network

import android.util.Base64
import android.util.Log
import com.miner.whatsminermonitor.model.HashboardInfo
import com.miner.whatsminermonitor.model.MinerDiagnostics
import com.miner.whatsminermonitor.model.MinerInfo
import com.miner.whatsminermonitor.model.PoolEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

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
 *
 * این نسخه برای سازگاری با طیف وسیع‌تری از مدل‌ها/فریمورها مقاوم شده:
 *  - ارتباط TCP: ارسال newline، عدم بستن زودهنگام stream خروجی، تشخیص پایان JSON با شمارش آکولاد
 *  - پارس JSON: تحمل کاراکترهای اضافی (BOM، banner، null bytes)، جستجوی کلید بدون حساسیت به حروف
 *  - کلیدهای جایگزین گسترده برای هر فیلد (تفاوت‌های M2x/M3x/M5x/M6x)
 */
object WhatsminerClient {

    const val API_PORT = 4028
    const val API_PORT_V3 = 4433
    private const val CONNECT_TIMEOUT_MS = 3500
    private const val READ_TIMEOUT_MS = 4000
    private const val TAG = "WhatsminerClient"

    suspend fun isPortOpen(ip: String, timeoutMs: Int = 800, port: Int = API_PORT): Boolean = withContext(Dispatchers.IO) {
        // تلاش با تایم‌اوت کمی بیشتر؛ دستگاه‌های زیر بار ممکن است 400ms را پاسخ ندهند
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            Log.d(TAG, "isPortOpen failed ip=$ip port=$port timeout=${timeoutMs}ms err=${e.message}")
            false
        }
    }

    /**
     * For scan: check both old (4028) and new (4433) ports. New API v3 (M50/M60/M60S) listens on 4433.
     */
    suspend fun isAnyPortOpen(ip: String, timeoutMs: Int = 800): Boolean {
        if (isPortOpen(ip, timeoutMs, API_PORT)) return true
        if (isPortOpen(ip, timeoutMs, API_PORT_V3)) return true
        return false
    }

    private suspend fun sendRawCommand(ip: String, command: String, port: Int = API_PORT): String? = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                // Whatsminer/cgminer API expects newline-terminated JSON. Some firmwares ignore
                // commands without trailing newline, causing silent no-response.
                val payload = if (command.endsWith("\n")) command else command + "\n"
                val out = socket.getOutputStream()
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
                // Signal end of request without closing the socket; closing the OutputStream via .use()
                // would close the entire socket on many Android JVMs and truncate the response.
                try { socket.shutdownOutput() } catch (_: Exception) { }

                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val sb = StringBuilder()
                val buffer = CharArray(4096)
                var braceDepth = 0
                var inString = false
                var escaped = false
                var seenOpeningBrace = false
                var seenOpeningBracket = false
                var bracketDepth = 0
                val startTime = System.currentTimeMillis()
                val overallDeadline = startTime + READ_TIMEOUT_MS + 1000L

                while (true) {
                    if (System.currentTimeMillis() > overallDeadline) {
                        Log.d(TAG, "sendRawCommand timeout ip=$ip cmd=$command buffered=${sb.length}")
                        break
                    }
                    // If we already have a complete JSON object and no more data is immediately available, we can return.
                    if ((seenOpeningBrace && braceDepth == 0 || seenOpeningBracket && bracketDepth == 0) && sb.isNotEmpty()) {
                        // short grace period to allow trailing data (some firmwares send two JSON objects)
                        var grace = 0
                        var hasMore = false
                        while (grace < 120) {
                            if (reader.ready()) { hasMore = true; break }
                            Thread.sleep(15)
                            grace += 15
                        }
                        if (!hasMore) break
                    }
                    if (!reader.ready()) {
                        // Wait a little for data to arrive; if we have nothing yet, keep waiting up to timeout
                        if (sb.isEmpty()) {
                            // block briefly via read() with timeout instead of busy wait
                            Thread.sleep(20)
                            if (!reader.ready()) {
                                // If still not ready after small wait, try a blocking read with timeout
                                // reader.read will throw SocketTimeoutException after soTimeout
                            } else {
                                // data arrived, continue to read
                            }
                        } else {
                            Thread.sleep(20)
                        }
                        if (!reader.ready()) {
                            // No data available; if we have a complete JSON, break, else keep waiting a bit more
                            if (sb.isNotEmpty() && ((seenOpeningBrace && braceDepth == 0) || (seenOpeningBracket && bracketDepth == 0))) break
                            // avoid infinite loop: if we've waited overall deadline, break
                            continue
                        }
                    }
                    val read = try {
                        reader.read(buffer)
                    } catch (e: java.net.SocketTimeoutException) {
                        Log.d(TAG, "read timeout ip=$ip cmd=$command partial=${sb.length}")
                        break
                    } catch (e: Exception) {
                        Log.d(TAG, "read error ip=$ip cmd=$command err=${e.message}")
                        break
                    }
                    if (read == -1) break
                    val chunk = String(buffer, 0, read)
                    // Update JSON completeness tracking (brace/bracket depth outside strings)
                    for (ch in chunk) {
                        if (escaped) { escaped = false; continue }
                        if (ch == '\\' && inString) { escaped = true; continue }
                        if (ch == '"') { inString = !inString; continue }
                        if (inString) continue
                        when (ch) {
                            '{' -> { braceDepth++; seenOpeningBrace = true }
                            '}' -> braceDepth--
                            '[' -> { bracketDepth++; seenOpeningBracket = true }
                            ']' -> bracketDepth--
                        }
                    }
                    sb.append(chunk)
                    if (sb.length > 1_200_000) {
                        Log.w(TAG, "response too large ip=$ip cmd=$command")
                        break
                    }
                    // If we have a complete top-level object and no more data immediately, we will exit on next loop grace check
                }
                val raw = sb.toString().trim('\u0000', '\n', '\r', ' ', '\uFEFF', '\t')
                if (raw.isBlank()) {
                    Log.d(TAG, "empty response ip=$ip cmd=$command")
                    null
                } else {
                    // Strip BOM if present
                    val cleaned = if (raw.startsWith("\uFEFF")) raw.substring(1) else raw
                    cleaned
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendRawCommand failed ip=$ip cmd=$command err=${e.message}")
            null
        }
    }

    // برخی دستگاه‌ها وقتی چند اتصال TCP پشت‌سرهم و سریع باز می‌شود (مثلا در انتهای چرخهٔ خواندن
    // اطلاعات یک دستگاه) گاهی یکی از دستورها را جواب نمی‌دهند؛ برای دستورهای حساس (مثل کد خطا)
    // در صورت شکست اولیه، یک تلاش دوم بعد از کمی مکث انجام می‌شود
    private suspend fun sendRawCommandWithRetry(
        ip: String,
        command: String,
        retries: Int = 1,
        delayMs: Long = 400,
        port: Int = API_PORT
    ): String? {
        var result = sendRawCommand(ip, command, port)
        // Treat blank/empty as failure as well
        if (result != null && result.isBlank()) result = null
        var attemptsLeft = retries
        while ((result == null || result.isBlank()) && attemptsLeft > 0) {
            Log.d(TAG, "retry ip=$ip port=$port cmd=$command attemptsLeft=$attemptsLeft")
            delay(delayMs)
            result = sendRawCommand(ip, command, port)
            if (result != null && result.isBlank()) result = null
            attemptsLeft--
        }
        if (result == null) Log.w(TAG, "command failed after retries ip=$ip cmd=$command")
        return result
    }

    // بعضی مدل‌ها/فریمورها قبل یا بعد از JSON اصلی کاراکترهای اضافه (بنر، echo، بایت‌های ناقص)
    // برمی‌گردانند؛ اگر پارس مستقیم شکست بخورد، به‌جای رد کردن کل پاسخ، زیررشتهٔ بین اولین '{' و
    // آخرین '}' هم امتحان می‌شود - این باعث می‌شود مدل‌های بیشتری به‌درستی شناسایی شوند
    // نسخه مقاوم‌تر: با شمارش آکولاد، اولین شیء JSON کامل را استخراج می‌کند (حتی اگر چند شیء پشت‌سرهم باشد)
    private fun parseJsonLenient(raw: String): JSONObject? {
        val trimmed = raw.trim('\u0000', '\n', '\r', ' ', '\uFEFF', '\t')
        if (trimmed.isBlank()) return null
        // Try direct
        runCatching { JSONObject(trimmed) }.getOrNull()?.let { return it }
        // Try to extract first complete JSON object via brace counting (handles banner + JSON + trailing garbage)
        extractFirstJsonObject(trimmed)?.let { jsonStr ->
            runCatching { JSONObject(jsonStr) }.getOrNull()?.let { return it }
        }
        // Fallback: between first '{' and last '}'
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start in 0 until end) {
            return runCatching { JSONObject(trimmed.substring(start, end + 1)) }.getOrNull()
        }
        // Sometimes response is a JSON array at top level
        if (trimmed.startsWith("[")) {
            runCatching { JSONObject("{\"_array\":" + trimmed + "}") }.getOrNull()?.let { return it }
        }
        Log.d(TAG, "parseJsonLenient failed rawPreview=${trimmed.take(200)}")
        return null
    }

    private fun extractFirstJsonObject(raw: String): String? {
        var depth = 0
        var inString = false
        var escaped = false
        var startIdx = -1
        for (i in raw.indices) {
            val ch = raw[i]
            if (escaped) { escaped = false; continue }
            if (ch == '\\' && inString) { escaped = true; continue }
            if (ch == '"') { inString = !inString; continue }
            if (inString) continue
            if (ch == '{') {
                if (depth == 0) startIdx = i
                depth++
            } else if (ch == '}') {
                depth--
                if (depth == 0 && startIdx >= 0) {
                    return raw.substring(startIdx, i + 1)
                }
                if (depth < 0) depth = 0
            }
        }
        return null
    }

    // ================= دستورهای خواندنی (Readable API) =================
    //
    // همهٔ دستورهای زیر با یک‌بار تلاش مجدد (سیاست همان چیزی که قبلاً فقط برای get_error_code
    // استفاده می‌شد) اجرا می‌شوند: چون این‌ها همه بخشی از یک زنجیرهٔ ۷-۸ اتصال TCP پشت‌سرهم هستند
    // و روی بعضی مدل‌ها/فریمورها (مخصوصاً مدل‌های کندتر یا زیر بار زیاد) ممکن است یکی-دو مورد وسط
    // زنجیره بی‌پاسخ بماند - در حالی که خود دستگاه کاملاً یک Whatsminer سالم است. همین موضوع باعث
    // می‌شد بعضی دستگاه‌ها فقط دما/چیپ (از summary و devs) را نشان دهند و بقیهٔ جزئیات (فریمور،
    // مدل، کنترل‌برد، MAC، پاور) خالی بماند.

    suspend fun fetchSummary(ip: String): JSONObject? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"summary"}""", retries = 1)
        if (raw == null) { MinerDiagnostics.recordRaw(ip, "summary", API_PORT, null, false, "no response"); return null }
        val ok = parseJsonLenient(raw) != null
        MinerDiagnostics.recordRaw(ip, "summary", API_PORT, raw, ok, if (!ok) "parse failed" else null)
        return parseJsonLenient(raw)
    }

    suspend fun fetchDevs(ip: String): JSONObject? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"devs"}""", retries = 1)
        if (raw == null) { MinerDiagnostics.recordRaw(ip, "devs", API_PORT, null, false, "no response"); return null }
        val ok = parseJsonLenient(raw) != null
        MinerDiagnostics.recordRaw(ip, "devs", API_PORT, raw, ok, if (!ok) "parse failed" else null)
        return parseJsonLenient(raw)
    }

    // نسخه فریمور و پلتفرم دستگاه
    suspend fun fetchVersion(ip: String): JSONObject? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"get_version"}""", retries = 1)
        if (raw == null) { MinerDiagnostics.recordRaw(ip, "get_version", API_PORT, null, false, "no response"); return null }
        val ok = parseJsonLenient(raw) != null
        MinerDiagnostics.recordRaw(ip, "get_version", API_PORT, raw, ok, if (!ok) "parse failed" else null)
        return parseJsonLenient(raw)
    }

    // نسخهٔ استاندارد دستور "version" (پروتکل پایهٔ cgminer که Whatsminer هم روی آن ساخته شده)؛
    // به‌عنوان پشتیبان وقتی get_version چیزی برنمی‌گرداند - بعضی فریمورها/مدل‌ها فقط به این
    // فرمت پاسخ می‌دهند و مدل/فریمور را زیر کلیدهای دیگری (VERSION[0].Type / .Miner) برمی‌گردانند
    suspend fun fetchVersionStandard(ip: String): JSONObject? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"version"}""", retries = 1)
        if (raw == null) { MinerDiagnostics.recordRaw(ip, "version", API_PORT, null, false, "no response"); return null }
        val ok = parseJsonLenient(raw) != null
        MinerDiagnostics.recordRaw(ip, "version", API_PORT, raw, ok, if (!ok) "parse failed" else null)
        return parseJsonLenient(raw)
    }

    // جزئیات هش‌برد؛ شامل مدل دقیق دستگاه (مثلاً M31S+VE40)
    suspend fun fetchDevDetails(ip: String): JSONObject? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"devdetails"}""", retries = 1)
        if (raw == null) { MinerDiagnostics.recordRaw(ip, "devdetails", API_PORT, null, false, "no response"); return null }
        val ok = parseJsonLenient(raw) != null
        MinerDiagnostics.recordRaw(ip, "devdetails", API_PORT, raw, ok, if (!ok) "parse failed" else null)
        return parseJsonLenient(raw)
    }

    // اطلاعات منبع تغذیه (پاور)
    suspend fun fetchPsu(ip: String): JSONObject? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"get_psu"}""", retries = 1)
        if (raw == null) { MinerDiagnostics.recordRaw(ip, "get_psu", API_PORT, null, false, "no response"); return null }
        val ok = parseJsonLenient(raw) != null
        MinerDiagnostics.recordRaw(ip, "get_psu", API_PORT, raw, ok, if (!ok) "parse failed" else null)
        return parseJsonLenient(raw)
    }

    // اطلاعات شبکه (از جمله MAC)
    suspend fun fetchMinerInfo(ip: String): JSONObject? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"get_miner_info","info":"mac,ip,hostname"}""", retries = 1)
        if (raw == null) { MinerDiagnostics.recordRaw(ip, "get_miner_info", API_PORT, null, false, "no response"); return null }
        val ok = parseJsonLenient(raw) != null
        MinerDiagnostics.recordRaw(ip, "get_miner_info", API_PORT, raw, ok, if (!ok) "parse failed" else null)
        return parseJsonLenient(raw)
    }

    suspend fun fetchPools(ip: String): JSONObject? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"pools"}""", retries = 1) ?: return null
        MinerDiagnostics.recordRaw(ip, "pools", API_PORT, raw, parseJsonLenient(raw) != null)
        return parseJsonLenient(raw)
    }

    // اطلاعات آماری گسترده (STATS) - بعضی فریمورها فقط اینجا hashrate/power/uptime را گزارش می‌کنند
    suspend fun fetchStats(ip: String): JSONObject? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"stats"}""", retries = 1) ?: return null
        MinerDiagnostics.recordRaw(ip, "stats", API_PORT, raw, parseJsonLenient(raw) != null)
        return parseJsonLenient(raw)
    }

    // ========== API v3 (port 4433) - M50/M60/M60S new firmware ==========
    // These use same TCP JSON but different port and commands: get.miner.status / get.device.info
    suspend fun fetchMinerStatusV3(ip: String, param: String = "summary+pools+edevs"): JSONObject? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"get.miner.status","param":"$param"}""", retries = 1, port = API_PORT_V3)
        if (raw == null) {
            MinerDiagnostics.recordRaw(ip, "v3-miner-status:$param", API_PORT_V3, null, false, "no response")
            return null
        }
        val ok = parseJsonLenient(raw) != null
        MinerDiagnostics.recordRaw(ip, "v3-miner-status:$param", API_PORT_V3, raw, ok)
        return parseJsonLenient(raw)
    }

    suspend fun fetchDeviceInfoV3(ip: String): JSONObject? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"get.device.info"}""", retries = 1, port = API_PORT_V3)
        if (raw == null) {
            MinerDiagnostics.recordRaw(ip, "v3-device-info", API_PORT_V3, null, false, "no response")
            return null
        }
        val ok = parseJsonLenient(raw) != null
        MinerDiagnostics.recordRaw(ip, "v3-device-info", API_PORT_V3, raw, ok)
        return parseJsonLenient(raw)
    }

    // فهرست کدهای خطای فعال دستگاه؛ چون این دستور معمولا آخرین دستور در چرخهٔ خواندن اطلاعات یک
    // دستگاه است (بعد از ۷ اتصال دیگر) و دستگاه‌های Whatsminer گاهی به اتصال‌های پشت‌سرهم سریع
    // به‌کندی/ناپایدار پاسخ می‌دهند، در صورت شکست یک‌بار دیگر با کمی مکث تلاش می‌شود
    suspend fun fetchErrorCode(ip: String): JSONObject? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"get_error_code"}""", retries = 1)
        if (raw == null) { MinerDiagnostics.recordRaw(ip, "get_error_code", API_PORT, null, false, "no response"); return null }
        val ok = parseJsonLenient(raw) != null
        MinerDiagnostics.recordRaw(ip, "get_error_code", API_PORT, raw, ok, if (!ok) "parse failed" else null)
        return parseJsonLenient(raw)
    }

    suspend fun queryMiner(ip: String): MinerInfo {
        // مرحله اول: جمع‌آوری همه پاسخ‌ها با fallback برای تشخیص reachability
        var summaryRoot = fetchSummary(ip)
        var devsRoot: JSONObject? = null
        var statsRoot: JSONObject? = null
        var isPartialReachable = false
        if (summaryRoot == null) {
            Log.w(TAG, "queryMiner: summary null ip=$ip, trying devs/stats as fallback before marking unreachable")
            devsRoot = fetchDevs(ip)
            statsRoot = fetchStats(ip)
            if (devsRoot != null || statsRoot != null) {
                Log.w(TAG, "queryMiner: devs/stats responded but summary did not ip=$ip -> partial reachable")
                summaryRoot = JSONObject().apply { put("SUMMARY", JSONArray().apply { put(JSONObject()) }) }
                isPartialReachable = true
            } else {
                Log.w(TAG, "queryMiner: summary, devs and stats all null ip=$ip -> unreachable")
                return MinerInfo(ip = ip, isReachable = false, errorMessage = "پاسخی از دستگاه دریافت نشد")
            }
        }

        val summaryObj = firstArrayObject(summaryRoot, "SUMMARY")
            ?: findObjectCaseInsensitive(summaryRoot, "SUMMARY")
            ?: summaryRoot.optJSONObject("SUMMARY")
            ?: summaryRoot.optJSONObject("summary")
            ?: findObjectCaseInsensitive(summaryRoot, "summary")
        if (summaryObj == null) {
            Log.w(TAG, "queryMiner: SUMMARY object not found ip=$ip keys=${summaryRoot.keys().asSequence().toList()}")
        } else {
            Log.d(TAG, "queryMiner SUMMARY keys ip=$ip ${summaryObj.keys().asSequence().toList().joinToString()}")
        }

        // جمع‌آوری بقیه endpointها با تاخیر کوتاه
        delay(70)
        if (devsRoot == null) devsRoot = fetchDevs(ip)
        val devsArray = getArrayCaseInsensitive(devsRoot, "DEVS")
            ?: getArrayCaseInsensitive(devsRoot, "devs")
        if (devsRoot != null && devsArray == null) Log.d(TAG, "DEVS array not found ip=$ip keys=${devsRoot.keys().asSequence().toList().joinToString()}")
        else if (devsArray != null) Log.d(TAG, "DEVS found ip=$ip count=${devsArray.length()}")

        delay(70)
        if (statsRoot == null) statsRoot = fetchStats(ip)
        val statsObj = firstArrayObject(statsRoot, "STATS")
            ?: firstArrayObject(statsRoot, "Stats")
            ?: findObjectCaseInsensitive(statsRoot, "STATS")
            ?: statsRoot?.optJSONObject("STATS")
            ?: statsRoot?.optJSONObject("Stats")
        if (statsRoot != null) Log.d(TAG, "STATS ip=$ip available=${statsObj != null} keys=${statsObj?.keys()?.asSequence()?.toList()?.joinToString() ?: statsRoot.keys().asSequence().toList().joinToString()}")

        delay(70)
        val versionRoot = fetchVersion(ip)
        val versionMsg = versionRoot?.optJSONObject("Msg") ?: versionRoot?.optJSONObject("msg") ?: findObjectCaseInsensitive(versionRoot, "Msg")
        if (versionRoot != null && versionMsg == null) Log.d(TAG, "version Msg not found ip=$ip rootKeys=${versionRoot.keys().asSequence().toList()}")

        delay(70)
        val devDetailsRoot = fetchDevDetails(ip)
        val devDetailsObj = firstArrayObject(devDetailsRoot, "DEVDETAILS")
            ?: firstArrayObject(devDetailsRoot, "DevDetails")
            ?: findObjectCaseInsensitive(devDetailsRoot, "DEVDETAILS")

        delay(70)
        val psuRoot = fetchPsu(ip)
        val psuMsg = psuRoot?.optJSONObject("Msg") ?: psuRoot?.optJSONObject("msg") ?: findObjectCaseInsensitive(psuRoot, "Msg")
        if (psuRoot != null) Log.d(TAG, "PSU ip=$ip msgKeys=${psuMsg?.keys()?.asSequence()?.toList()?.joinToString() ?: psuRoot.keys().asSequence().toList().joinToString()}")

        delay(70)
        val minerInfoRoot = fetchMinerInfo(ip)
        val minerInfoMsg = minerInfoRoot?.optJSONObject("Msg") ?: minerInfoRoot?.optJSONObject("msg") ?: findObjectCaseInsensitive(minerInfoRoot, "Msg")

        delay(70)
        val poolsRoot = fetchPools(ip)
        val poolObj = firstArrayObject(poolsRoot, "POOLS") ?: firstArrayObject(poolsRoot, "Pools") ?: findObjectCaseInsensitive(poolsRoot, "POOLS")
        val poolsArray = getArrayCaseInsensitive(poolsRoot, "POOLS") ?: getArrayCaseInsensitive(poolsRoot, "Pools")

        // === API v3 (port 4433) for M50/M60/M60S new firmware ===
        delay(70)
        val v3StatusRoot = fetchMinerStatusV3(ip, "summary+pools+edevs")
        val v3Msg = v3StatusRoot?.optJSONObject("msg") ?: v3StatusRoot?.optJSONObject("Msg") ?: findObjectCaseInsensitive(v3StatusRoot, "msg")
        val v3Summary = v3Msg?.optJSONObject("summary") ?: findObjectCaseInsensitive(v3Msg, "summary")
        val v3PoolsArray = v3Msg?.optJSONArray("pools") ?: findArrayCaseInsensitive(v3Msg ?: JSONObject(), "pools")
        val v3EdevsArray = v3Msg?.optJSONArray("edevs") ?: findArrayCaseInsensitive(v3Msg ?: JSONObject(), "edevs")
        if (v3StatusRoot != null) Log.d(TAG, "V3 status ip=$ip v3Summary=${v3Summary != null} v3Pools=${v3PoolsArray?.length() ?: 0} v3Edevs=${v3EdevsArray?.length() ?: 0}")
        delay(70)
        val v3DeviceInfoRoot = fetchDeviceInfoV3(ip)
        val v3DeviceMsg = v3DeviceInfoRoot?.optJSONObject("msg") ?: v3DeviceInfoRoot?.optJSONObject("Msg") ?: findObjectCaseInsensitive(v3DeviceInfoRoot, "msg")
        val v3PowerObj = v3DeviceMsg?.optJSONObject("power") ?: findObjectCaseInsensitive(v3DeviceMsg, "power")
        val v3MinerObj = v3DeviceMsg?.optJSONObject("miner") ?: findObjectCaseInsensitive(v3DeviceMsg, "miner")
        val v3SystemObj = v3DeviceMsg?.optJSONObject("system") ?: findObjectCaseInsensitive(v3DeviceMsg, "system")
        if (v3DeviceInfoRoot != null) Log.d(TAG, "V3 deviceInfo ip=$ip power=${v3PowerObj != null} minerType=${v3MinerObj?.optString("type")}")

        delay(70)
        val errorRoot = fetchErrorCode(ip)
        val errorCodes = parseErrorCodes(errorRoot)
        // Also try v3 error-code as fallback (new API: msg.error-code = [{"531":"...","reason":"Slot1 not found."}])
        val v3ErrorArr = v3DeviceMsg?.optJSONArray("error-code") ?: findArrayCaseInsensitive(v3DeviceMsg ?: JSONObject(), "error-code")
        val v3ErrorCodes = if (v3ErrorArr != null) {
            // v3 format is array of objects where key is code as string; our parseErrorCodes handles that if we wrap it
            parseErrorCodes(JSONObject().apply { put("Msg", JSONObject().apply { put("error_code", v3ErrorArr) }) })
        } else emptyList()
        val allErrorCodes = (errorCodes + v3ErrorCodes).distinct()
        // errorCheckFailed only if both old and v3 error sources unavailable
        val errorCheckFailed = errorRoot == null && v3ErrorArr == null && v3DeviceInfoRoot == null

        val hashboards = parseHashboards(devsArray)
        // For v3, also try to build hashboards from edevs if DEVS empty
        val v3Hashboards = if (hashboards.isEmpty() && v3EdevsArray != null) parseHashboardsV3(v3EdevsArray) else emptyList()
        val effectiveHashboards = if (hashboards.isNotEmpty()) hashboards else v3Hashboards
        val avgTemp = effectiveHashboards.mapNotNull { it.temperaturePcb ?: it.temperatureChip }
            .takeIf { it.isNotEmpty() }?.average()
            ?: v3Summary?.let { findDouble(it, listOf("chip-temp-avg", "chip_temp_avg", "temperature")) }

        // ============ 7 فیلد کلیدی با fallback چندسطحی + لاگ تشخیصی ============

        // 1) Hashrate (TH/s -> GHS) - منبع: SUMMARY -> STATS -> V3 summary -> DEVS aggregate
        val hashrateResult = resolveHashrate(ip, summaryObj, statsObj, v3Summary, devsArray, effectiveHashboards)
        val totalHashrate = hashrateResult?.first
        val ghsAvResult = resolveGhsAverage(ip, summaryObj, statsObj, v3Summary)
        val ghsAv = ghsAvResult?.first

        // 2) Uptime / Elapsed - try SUMMARY, STATS, V3 summary, device info
        val elapsedResult = resolveElapsed(ip, summaryObj, statsObj, v3Summary, versionMsg)
        val elapsedSeconds = elapsedResult?.first

        // 3) Power (Watt) - try SUMMARY, STATS, V3 summary/power, PSU
        val powerResult = resolvePower(ip, summaryObj, statsObj, v3Summary, psuMsg, psuRoot, v3PowerObj)
        val powerWatt = powerResult?.first

        // 4) Accepted shares - try SUMMARY, STATS, POOLS, V3 pools
        val acceptedResult = resolveAccepted(ip, summaryObj, statsObj, poolObj, poolsArray, v3PoolsArray)
        val accepted = acceptedResult?.first

        // 5) Rejected shares - similar, but V3 pools have reject-rate not count
        val rejectedResult = resolveRejected(ip, summaryObj, statsObj, poolObj, poolsArray, v3PoolsArray)
        val rejected = rejectedResult?.first

        // 6) Fan In / 7) Fan Out with array + key fallback including V3
        val fanInResult = resolveFanSpeed(ip, summaryObj, statsObj, v3Summary, isInput = true)
        val fanOutResult = resolveFanSpeed(ip, summaryObj, statsObj, v3Summary, isInput = false)
        val fanSpeedIn = fanInResult?.first
        val fanSpeedOut = fanOutResult?.first

        // زمان پاسخ پول
        val poolResponseMs = poolObj?.let { findInt(it, listOf("Last Share Time", "LastShareTime", "last_share_time", "Pool Rejected%")) }

        // فریمور / کنترل‌برد / مدل
        var fwVer = findStringWithSource(versionMsg, listOf("fw_ver", "FWVersion", "fwversion", "BTMiner Version", "miner_version", "Firmware Version", "Version", "fw version", "CompileTime", "BMMiner", "Miner"))?.first
        var platform = findStringWithSource(versionMsg, listOf("platform", "Platform", "control_board", "Board", "Control Board", "control board"))?.first
        var modelFromVersion = findStringWithSource(versionMsg, listOf("prod", "miner_type", "Model", "type", "Type", "Miner Type", "miner type", "Model Name"))?.first
        if (fwVer == null || platform == null || modelFromVersion == null) {
            val stdVersionRoot = fetchVersionStandard(ip)
            val stdVersionObj = firstArrayObject(stdVersionRoot, "VERSION")
                ?: firstArrayObject(stdVersionRoot, "Version")
                ?: findObjectCaseInsensitive(stdVersionRoot, "VERSION")
            if (stdVersionObj != null) {
                if (fwVer == null) fwVer = findString(stdVersionObj, listOf("Miner", "BMMiner", "CompileTime", "Version", "FWVersion", "fw_ver"))
                if (platform == null) platform = findString(stdVersionObj, listOf("Platform", "platform", "Board"))
                if (modelFromVersion == null) modelFromVersion = findString(stdVersionObj, listOf("Type", "Model", "model", "Miner Type"))
            }
            if (fwVer == null && versionRoot != null) fwVer = findString(versionRoot, listOf("fw_ver", "FWVersion"))
            if (platform == null && versionRoot != null) platform = findString(versionRoot, listOf("platform", "Platform"))
        }

        // Try v3 fallback for model/firmware if still missing (new API: system.fwversion, platform, miner.type)
        if (fwVer == null) fwVer = findString(v3SystemObj, listOf("fwversion", "fw_version", "version", "Firmware Version")) ?: findString(v3SystemObj, listOf("Version"))
        if (platform == null) platform = findString(v3SystemObj, listOf("platform", "Platform", "control-board-version"))
        if (modelFromVersion == null) modelFromVersion = findString(v3MinerObj, listOf("type", "Type", "model", "Model"))
        val minerType = findString(devDetailsObj, listOf("Model", "model", "Type", "type", "prod"))
            ?: modelFromVersion
            ?: findString(summaryObj, listOf("Type", "Model", "model", "Miner Type", "Description"))
            ?: findString(v3MinerObj, listOf("type", "Type", "model"))

        // ========== Diagnostic logging with 0 vs missing vs invalid vs error distinction ==========
        fun recordFieldDiagnostics(field: String, result: Pair<*, String?>?, endpointForMissing: String, rawForMissing: String = "MISSING") {
            if (result != null) {
                val value = result.first
                val isZero = (value is Number && value.toDouble() == 0.0) || value.toString() == "0"
                val status = if (isZero) MinerDiagnostics.FieldResolution.Status.ZERO else MinerDiagnostics.FieldResolution.Status.OK
                val rawStr = value.toString()
                val finalStr = when (field) {
                    "hashrate" -> "${value} GHS (${String.format("%.2f", (value as Double) / 1000.0)} TH/s)"
                    "power" -> "${value} W"
                    "fanIn", "fanOut" -> "${value} RPM"
                    "elapsed" -> "${value}s"
                    else -> value.toString()
                }
                MinerDiagnostics.recordField(
                    MinerDiagnostics.FieldResolution(ip, field, result.second ?: endpointForMissing, result.second?.substringAfter(":") ?: rawForMissing, rawStr, rawStr, finalStr, status)
                )
                Log.d(TAG, "resolve $field ip=$ip value=$value source=${result.second} status=${status.name}")
            } else {
                // Determine if missing due to no raw capture vs invalid
                val hasRawForEndpoint = MinerDiagnostics.getRawCaptures(ip).any { it.endpoint.contains(endpointForMissing.split(":").first(), ignoreCase = true) && it.rawJson != null }
                val status = if (hasRawForEndpoint) MinerDiagnostics.FieldResolution.Status.MISSING else MinerDiagnostics.FieldResolution.Status.ERROR
                MinerDiagnostics.recordField(
                    MinerDiagnostics.FieldResolution(ip, field, endpointForMissing, rawForMissing, "null", "null", "—", status)
                )
                Log.d(TAG, "resolve $field ip=$ip MISSING/ERROR endpoint=$endpointForMissing status=${status.name}")
            }
        }
        // Also keep old logField for backward compatibility
        fun logField(name: String, result: Pair<*, String?>?, fallbackMsg: String = "MISSING") {
            if (result != null) Log.d(TAG, "resolve $name ip=$ip value=${result.first} source=${result.second}")
            else Log.d(TAG, "resolve $name ip=$ip $fallbackMsg")
        }
        logField("hashrate(GHS)", hashrateResult, "MISSING - tried SUMMARY/STATS/V3/DEVS")
        logField("ghsAv(GHS)", ghsAvResult)
        logField("elapsed(s)", elapsedResult)
        logField("power(W)", powerResult)
        logField("accepted", acceptedResult)
        logField("rejected", rejectedResult)
        logField("fanIn(RPM)", fanInResult)
        logField("fanOut(RPM)", fanOutResult)
        // Record structured diagnostics for export/share
        recordFieldDiagnostics("hashrate", hashrateResult, "SUMMARY/STATS/V3/DEVS")
        recordFieldDiagnostics("ghsAv", ghsAvResult, "SUMMARY/STATS/V3")
        recordFieldDiagnostics("elapsed", elapsedResult, "SUMMARY/STATS/V3")
        recordFieldDiagnostics("power", powerResult, "SUMMARY/STATS/V3/PSU")
        recordFieldDiagnostics("accepted", acceptedResult, "SUMMARY/POOLS/V3")
        recordFieldDiagnostics("rejected", rejectedResult, "SUMMARY/POOLS/V3")
        recordFieldDiagnostics("fanIn", fanInResult, "SUMMARY/V3-summary")
        recordFieldDiagnostics("fanOut", fanOutResult, "SUMMARY/V3-summary")

        // تشخیص reachability دقیق‌تر: اگر SUMMARY خالی بود ولی بقیه منابع داده دارند، reachable بماند
        val hasAnyData = listOf(totalHashrate, ghsAv, elapsedSeconds?.toDouble(), powerWatt?.toDouble(), accepted?.toDouble(), rejected?.toDouble(), fanSpeedIn?.toDouble(), fanSpeedOut?.toDouble(), avgTemp).any { it != null }
        val isReachable = !isPartialReachable || hasAnyData || summaryObj != null || v3Summary != null

        return MinerInfo(
            ip = ip,
            isReachable = isReachable,
            elapsedSeconds = elapsedSeconds,
            fanSpeedIn = fanSpeedIn,
            fanSpeedOut = fanSpeedOut,
            powerWatt = powerWatt,
            averageTemperature = avgTemp ?: findDouble(summaryObj, listOf("Temperature", "Temp", "Avg Temp", "Average Temperature", "temperature", "temp", "Temp Avg")) ?: findDouble(v3Summary, listOf("chip-temp-avg", "board-temperature", "temperature")),
            totalHashrateGhs = totalHashrate,
            ghsAverage = ghsAv,
            firmwareVersion = fwVer,
            minerType = minerType,
            controlBoard = platform,
            accepted = accepted,
            rejected = rejected,
            poolResponseMs = poolResponseMs,
            hashboards = effectiveHashboards,
            macAddress = findString(minerInfoMsg, listOf("mac", "Mac", "MAC", "macaddr", "MacAddr", "MAC Addr", "mac_address")) ?: findString(v3DeviceMsg?.optJSONObject("network") ?: v3DeviceMsg, listOf("mac", "MAC")),
            powerSupplyModel = findString(psuMsg, listOf("name", "model", "Model", "psu_model", "PSU Model", "PSUmodel", "psu name")) ?: findString(v3PowerObj, listOf("model", "type", "Model")),
            poolWorkerName = findString(poolObj, listOf("User", "user", "Worker", "worker", "username")) ?: findString(v3PoolsArray?.optJSONObject(0), listOf("account", "user", "User")),
            poolUrl = findString(poolObj, listOf("URL", "Url", "url", "Pool URL", "pool_url", "Stratum URL")) ?: findString(v3PoolsArray?.optJSONObject(0), listOf("url", "URL")),
            errorCodes = allErrorCodes,
            errorCheckFailed = errorCheckFailed
        )
    }

    // پارس کردن پاسخ get_error_code به فهرستی از کدهای عددی خطای فعال.
    // روی دستگاه واقعی (تأییدشده با یک پاسخ خام واقعی) این شکل است:
    //   {"error_code":[{"202":"2026-08-12 23:58:21"}]}
    // یعنی «error_code» یک آرایه است و هر عضو آن یک شیء تک‌کلیدی است که خودِ کلید، کد خطاست
    // (نه یک فیلد جدا به اسم "code"). قبلاً این حالت پشتیبانی نمی‌شد و به همین دلیل با اینکه
    // دستگاه کد خطای فعال داشت، برنامه چیزی نشان نمی‌داد. علاوه بر این حالت واقعی، دو حالت دیگر
    // (شیء مستقیم طبق مستندات رسمی، و آرایه‌ای از اشیای نام‌دار مثل {"code":"202",...}) هم به
    //‌عنوان پشتیبان نگه داشته شده‌اند چون ممکن است بین نسخه‌های مختلف فریمور فرق داشته باشند.
    private fun parseErrorCodes(root: JSONObject?): List<Int> {
        if (root == null) return emptyList()
        val msg = root.optJSONObject("Msg") ?: root.optJSONObject("msg") ?: findObjectCaseInsensitive(root, "Msg") ?: root
        val result = sortedSetOf<Int>()

        // Try to locate error_code key case-insensitively
        val errorCodeKey = findKeyCaseInsensitive(msg, "error_code") ?: findKeyCaseInsensitive(msg, "errorcode") ?: findKeyCaseInsensitive(msg, "ErrorCode") ?: "error_code"

        // حالت ۱: error_code مستقیماً یک شیء است -> {"error_code": {"202": "زمان", ...}}
        msg.optJSONObject(errorCodeKey)?.let { obj ->
            addCodesFromObjectKeys(obj, result)
            return result.toList()
        }
        // Also try direct object under msg if key is different
        // حالت 1b: check for object with numeric keys at top level of msg (some firmwares)
        if (msg.length() > 0) {
            var numericKeyCount = 0
            val keys = msg.keys()
            while (keys.hasNext()) { if (keys.next().trim().toIntOrNull() != null) numericKeyCount++ }
            if (numericKeyCount > 0 && result.isEmpty()) {
                addCodesFromObjectKeys(msg, result)
                if (result.isNotEmpty()) return result.toList()
            }
        }

        // حالت ۲ و ۳: error_code یک آرایه است
        val arr = msg.optJSONArray(errorCodeKey) ?: findArrayCaseInsensitive(msg, "error_code")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                when (val item = arr.opt(i)) {
                    is JSONObject -> {
                        // حالت ۲: عضو آرایه یک شیء نام‌دار است -> {"code":"202", "time":"..."}
                        val named = listOf(
                            item.optString("error_code"),
                            item.optString("code"),
                            item.optString("Code"),
                            item.optString("ErrCode"),
                            item.optString("err_code")
                        ).firstOrNull { it.isNotBlank() }?.toIntOrNull()
                        if (named != null && named != 0) {
                            result.add(named)
                        } else {
                            // حالت ۳ (همان چیزی که روی دستگاه واقعی دیده شد):
                            // عضو آرایه {"202":"زمان"} است؛ خودِ کلید شیء، کد خطاست
                            addCodesFromObjectKeys(item, result)
                        }
                    }
                    is Number -> if (item.toInt() != 0) result.add(item.toInt())
                    is String -> item.trim().toIntOrNull()?.let { if (it != 0) result.add(it) }
                    else -> {}
                }
            }
            return result.toList()
        }

        // یافت نشد؛ به‌جای فرض «بدون خطا»، به‌صورت پشتیبان دنبال هر کلیدی می‌گردیم که
        // شامل «error» باشد (برخی فریمورها ممکن است از نام دیگری به‌جای error_code استفاده کنند)
        val fallbackKeys = msg.keys().asSequence().filter { it.contains("error", ignoreCase = true) }
        for (k in fallbackKeys) {
            when (val v = msg.opt(k)) {
                is JSONObject -> addCodesFromObjectKeys(v, result)
                is JSONArray -> for (i in 0 until v.length()) {
                    when (val item = v.opt(i)) {
                        is JSONObject -> addCodesFromObjectKeys(item, result)
                        is Number -> if (item.toInt() != 0) result.add(item.toInt())
                        is String -> item.trim().toIntOrNull()?.let { c -> if (c != 0) result.add(c) }
                        else -> {}
                    }
                }
                is Number -> if (v.toInt() != 0) result.add(v.toInt())
                is String -> v.trim().toIntOrNull()?.let { if (it != 0) result.add(it) }
                else -> {}
            }
        }
        return result.toList()
    }

    // کلیدهای یک شیء JSON را به‌عنوان کد خطا استخراج می‌کند - برای حالتی که خودِ کلید شیء
    // کد خطاست، مثل {"202": "2026-08-12 23:58:21"} یا {"202": "...", "301": "..."}
    private fun addCodesFromObjectKeys(obj: JSONObject, result: MutableSet<Int>) {
        val keys = obj.keys()
        while (keys.hasNext()) {
            keys.next().trim().toIntOrNull()?.let { if (it != 0) result.add(it) }
        }
    }

    private fun parseHashboards(devsArray: JSONArray?): List<HashboardInfo> {
        if (devsArray == null) return emptyList()
        val list = mutableListOf<HashboardInfo>()
        for (i in 0 until devsArray.length()) {
            val dev = devsArray.optJSONObject(i) ?: continue
            val id = findInt(dev, listOf("ASC", "ID", "PGA", "GPU", "GHS ID", "Board ID")) ?: i
            val hashGhsRaw = findDouble(dev, listOf("MHS 5s", "MHS 5s (MHS)", "MHS av", "GHS 5s", "GHS av", "MHS 5s", "mhs 5s", "ghs 5s", "MHS5s", "Hash Rate"))
            val hashGhs = hashGhsRaw?.let { v -> if (v > 1_000_000) v / 1000.0 else v }
            list.add(
                HashboardInfo(
                    id = id,
                    temperaturePcb = findDouble(dev, listOf("Temperature", "Temp PCB", "Board Temp", "TempPCB", "PCB Temp", "Temp1", "temp_pcb", "temppcb", "Temp PCB ", "BoardTemp")),
                    temperatureChip = findDouble(dev, listOf("Chip Temp Avg", "Temperature Chip", "Chip Temp", "ChipTemp", "TempChip", "Temp2", "chip_temp", "chip temp")),
                    hashrateGhs = hashGhs,
                    frequencyMhz = findDouble(dev, listOf("Chip Frequency", "Frequency", "Freq", "freq", "Frequency Avg", "frequency")),
                    effectiveChips = findInt(dev, listOf("Effective Chips", "Chip Num", "ChipNum", "Chips", "chip_num", "ASIC Num", "Chip Count", "EffectiveChips")),
                    status = findString(dev, listOf("Status", "Enabled", "status", "State"))
                )
            )
        }
        return list
    }

    private fun parseHashboardsV3(edevsArray: JSONArray?): List<HashboardInfo> {
        if (edevsArray == null) return emptyList()
        val list = mutableListOf<HashboardInfo>()
        for (i in 0 until edevsArray.length()) {
            val dev = edevsArray.optJSONObject(i) ?: continue
            val id = findInt(dev, listOf("id", "slot", "ID", "Slot")) ?: i
            // V3 uses TH/s directly: hash-average, hash-realtime, factory-hash are TH/s
            val hashTh = findDouble(dev, listOf("hash-average", "hash-realtime", "hash-1min", "factory-hash", "hash_average", "GHS 5s", "MHS 5s"))
            val hashGhs = hashTh?.let { it * 1000.0 }
            list.add(
                HashboardInfo(
                    id = id,
                    temperaturePcb = findDouble(dev, listOf("board-temperature", "chip-temp-avg", "Temperature", "Temp PCB")),
                    temperatureChip = findDouble(dev, listOf("chip-temp-avg", "chip-temp-max", "chip-temp-min", "Temperature Chip")),
                    hashrateGhs = hashGhs,
                    frequencyMhz = findDouble(dev, listOf("freq", "frequency", "Freq", "Chip Frequency")),
                    effectiveChips = findInt(dev, listOf("effective-chips", "effective_chips", "Effective Chips", "Chip Num")),
                    status = findString(dev, listOf("status", "Status")) ?: "Alive"
                )
            )
        }
        return list
    }

    // ========== Resolvers for the 7 critical fields with multi-endpoint fallback ==========

    private fun findDoubleWithSource(obj: JSONObject?, keys: List<String>): Pair<Double, String>? {
        if (obj == null) return null
        for (k in keys) if (obj.has(k) && !obj.isNull(k)) {
            val v = obj.opt(k)
            val d = when (v) {
                is Number -> v.toDouble()
                is String -> v.trim().toDoubleOrNullCompat()
                else -> null
            }
            if (d != null) return d to k
        }
        val lowerMap = mutableMapOf<String, String>()
        val normMap = mutableMapOf<String, String>()
        val kIter = obj.keys()
        while (kIter.hasNext()) { val kk = kIter.next(); lowerMap[kk.lowercase()] = kk; normMap[normalizeKey(kk)] = kk }
        for (k in keys) {
            val actual = lowerMap[k.lowercase()] ?: normMap[normalizeKey(k)]
            if (actual != null && !obj.isNull(actual)) {
                val v = obj.opt(actual)
                val d = when (v) {
                    is Number -> v.toDouble()
                    is String -> v.trim().toDoubleOrNullCompat()
                    else -> null
                }
                if (d != null) return d to actual
            }
        }
        return null
    }

    private fun findIntWithSource(obj: JSONObject?, keys: List<String>): Pair<Int, String>? {
        val p = findDoubleWithSource(obj, keys) ?: return null
        return p.first.toInt() to p.second
    }

    private fun findLongWithSource(obj: JSONObject?, keys: List<String>): Pair<Long, String>? {
        val p = findDoubleWithSource(obj, keys) ?: return null
        return p.first.toLong() to p.second
    }

    private fun findStringWithSource(obj: JSONObject?, keys: List<String>): Pair<String, String>? {
        if (obj == null) return null
        for (k in keys) if (obj.has(k) && !obj.isNull(k)) {
            val v = obj.optString(k)
            if (v.isNotBlank()) return v.trim() to k
        }
        val lowerMap = mutableMapOf<String, String>()
        val normMap = mutableMapOf<String, String>()
        val kIter = obj.keys()
        while (kIter.hasNext()) { val kk = kIter.next(); lowerMap[kk.lowercase()] = kk; normMap[normalizeKey(kk)] = kk }
        for (k in keys) {
            val actual = lowerMap[k.lowercase()] ?: normMap[normalizeKey(k)]
            if (actual != null && !obj.isNull(actual)) {
                val v = obj.optString(actual)
                if (v.isNotBlank()) return v.trim() to actual
            }
        }
        return null
    }

    private fun convertHashrateToGhs(rawValue: Double, original: String?, key: String): Double {
        val lowerOrig = (original ?: "").lowercase()
        val lowerKey = key.lowercase()
        // If original string explicitly contains unit, convert accordingly
        if (lowerOrig.contains("ph")) return rawValue * 1_000_000.0  // PH/s -> GH/s
        if (lowerOrig.contains("th")) return rawValue * 1000.0
        if (lowerOrig.contains("gh")) return rawValue
        if (lowerOrig.contains("mh")) return rawValue / 1000.0
        // Key-based hint
        if (lowerKey.contains("ths") || lowerKey.contains("th/s") || lowerKey.contains("th ")) return rawValue * 1000.0
        if (lowerKey.contains("ghs")) return rawValue
        if (lowerKey.contains("mhs")) return if (rawValue > 1_000_000) rawValue / 1000.0 else rawValue
        // Heuristic for numeric-only: >1M likely MH/s
        return if (rawValue > 1_000_000) rawValue / 1000.0 else rawValue
    }

    private fun resolveHashrate(
        ip: String,
        summaryObj: JSONObject?,
        statsObj: JSONObject?,
        v3Summary: JSONObject?,
        devsArray: JSONArray?,
        hashboards: List<HashboardInfo>
    ): Pair<Double, String>? {
        val keys = listOf(
            "MHS 5s", "MHS 5s (MHS)", "MHS 5s ", "GHS 5s", "GHS av", "MHS av",
            "GHS 5s", "HS 5s", "Hash Rate 5s", "mhs 5s", "ghs 5s", "MHS5s",
            "MHS 5m", "GHS 5m", "MHS 1m", "GHS 1m", "MHS 15m", "GHS 15m",
            "THS 5s", "THS av", "TH/s", "THS", "Hash Rate", "hashrate", "HT",
            "GHS", "MHS", "Hashrate", "MHSav", "GHSav",
            "hash-average", "hash-realtime", "hash-1min", "hash-15min", "factory-hash"
        )
        // 1) SUMMARY (old API)
        findDoubleWithSource(summaryObj, keys)?.let { (v, k) ->
            val rawStr = summaryObj?.optString(k)
            val gh = convertHashrateToGhs(v, rawStr, k)
            Log.d(TAG, "hashrate ip=$ip source=SUMMARY:$k raw=$v str='$rawStr' -> $gh GHS")
            return gh to "SUMMARY:$k"
        }
        // 2) STATS
        findDoubleWithSource(statsObj, keys)?.let { (v, k) ->
            val rawStr = statsObj?.optString(k)
            val gh = convertHashrateToGhs(v, rawStr, k)
            Log.d(TAG, "hashrate ip=$ip source=STATS:$k raw=$v str='$rawStr' -> $gh GHS")
            return gh to "STATS:$k"
        }
        // 2b) V3 summary (new API) - values are TH/s directly e.g., 101.847 TH/s
        findDoubleWithSource(v3Summary, keys)?.let { (v, k) ->
            val rawStr = v3Summary?.optString(k)
            // V3 hash-average is TH/s: convert TH->GHS
            val gh = if (k.contains("hash", ignoreCase = true) && !k.contains("mhs", ignoreCase = true) && !k.contains("ghs", ignoreCase = true)) {
                // plain hash-average without unit hint => assume TH/s for v3
                v * 1000.0
            } else convertHashrateToGhs(v, rawStr, k)
            Log.d(TAG, "hashrate ip=$ip source=V3-summary:$k raw=$v str='$rawStr' -> $gh GHS")
            return gh to "V3-summary:$k"
        }
        // 3) DEVS aggregated
        val sum = hashboards.mapNotNull { it.hashrateGhs }.takeIf { it.isNotEmpty() }?.sum()
        if (sum != null && sum > 0) {
            Log.d(TAG, "hashrate ip=$ip source=DEVS-aggregated sum=$sum GHS")
            return sum to "DEVS-aggregated"
        }
        // 4) DEVS direct sum of raw MHS values as fallback
        if (devsArray != null && devsArray.length() > 0) {
            var totalGhs = 0.0
            var found = false
            for (i in 0 until devsArray.length()) {
                val dev = devsArray.optJSONObject(i) ?: continue
                val r = findDoubleWithSource(dev, keys)
                if (r != null) {
                    val gh = convertHashrateToGhs(r.first, dev.optString(r.second), r.second)
                    totalGhs += gh; found = true
                }
            }
            if (found && totalGhs > 0) {
                Log.d(TAG, "hashrate ip=$ip source=DEVS-raw sum=$totalGhs GHS")
                return totalGhs to "DEVS-raw"
            }
        }
        Log.d(TAG, "hashrate ip=$ip MISSING")
        return null
    }

    private fun resolveGhsAverage(ip: String, summaryObj: JSONObject?, statsObj: JSONObject?, v3Summary: JSONObject?): Pair<Double, String>? {
        val keys = listOf("MHS av", "MHS Av", "GHS av", "GHS 5s", "MHS average", "mhs av", "ghs av", "THS av", "GHSav", "MHSav", "Hash Rate Avg", "GHS Avg", "MHS Avg", "hash-average", "hash-realtime", "hash-15min", "hash-1min")
        findDoubleWithSource(summaryObj, keys)?.let { (v, k) ->
            val gh = convertHashrateToGhs(v, summaryObj?.optString(k), k)
            Log.d(TAG, "ghsAv ip=$ip source=SUMMARY:$k raw=$v -> $gh GHS")
            return gh to "SUMMARY:$k"
        }
        findDoubleWithSource(statsObj, keys)?.let { (v, k) ->
            val gh = convertHashrateToGhs(v, statsObj?.optString(k), k)
            Log.d(TAG, "ghsAv ip=$ip source=STATS:$k raw=$v -> $gh GHS")
            return gh to "STATS:$k"
        }
        findDoubleWithSource(v3Summary, keys)?.let { (v, k) ->
            val gh = if (k.startsWith("hash", ignoreCase = true)) v * 1000.0 else convertHashrateToGhs(v, v3Summary?.optString(k), k)
            Log.d(TAG, "ghsAv ip=$ip source=V3-summary:$k raw=$v -> $gh GHS")
            return gh to "V3-summary:$k"
        }
        return null
    }

    private fun resolveElapsed(ip: String, summaryObj: JSONObject?, statsObj: JSONObject?, v3Summary: JSONObject?, versionMsg: JSONObject?): Pair<Long, String>? {
        val keys = listOf(
            "Elapsed", "elapsed", "ELAPSED", "Elapsed Time", "elapsed_time", "elapsedtime",
            "Uptime", "uptime", "UPTIME", "Uptime Seconds", "Running Time", "RunningTime",
            "When", "Total Elapsed", "ElapsedSeconds", "Time", "time", "up_time", "bootup-time"
        )
        // Helper to parse value that might be string "12:34:56" or numeric seconds
        fun parseElapsedValue(raw: Any?, key: String): Long? {
            if (raw == null) return null
            if (raw is Number) {
                val v = raw.toLong()
                // Some firmwares return "When" as unix timestamp of start -> convert to elapsed
                if (key.equals("When", ignoreCase = true) && v > 1_000_000_000L) {
                    val now = System.currentTimeMillis() / 1000
                    val elapsed = now - v
                    return if (elapsed in 1..315360000L) elapsed else null
                }
                return if (v in 0..315360000L) v else null // 0..10 years
            }
            val s = raw.toString().trim()
            s.toLongOrNull()?.let { v ->
                if (key.equals("When", ignoreCase = true) && v > 1_000_000_000L) {
                    val now = System.currentTimeMillis() / 1000
                    val elapsed = now - v
                    return if (elapsed in 1..315360000L) elapsed else v
                }
                return if (v in 0..315360000L) v else null
            }
            // Try "DD days HH:MM:SS" or "HH:MM:SS"
            if (":" in s) {
                try {
                    val timeMatch = Regex("""(\d+):(\d+):(\d+)""").find(s)
                    if (timeMatch != null) {
                        val h = timeMatch.groupValues[1].toLongOrNull() ?: 0
                        val m = timeMatch.groupValues[2].toLongOrNull() ?: 0
                        val s2 = timeMatch.groupValues[3].toLongOrNull() ?: 0
                        val days = Regex("""(\d+)\s*days?""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                        return days * 86400 + h * 3600 + m * 60 + s2
                    }
                } catch (_: Exception) {}
            }
            // Extract first number as seconds fallback
            Regex("""-?\d+""").find(s)?.value?.toLongOrNull()?.let { return it }
            return null
        }

        // Search SUMMARY
        for (k in keys) {
            val actual = findKeyCaseInsensitive(summaryObj, k) ?: continue
            val raw = summaryObj?.opt(actual)
            val parsed = parseElapsedValue(raw, actual)
            if (parsed != null) {
                Log.d(TAG, "elapsed ip=$ip source=SUMMARY:$actual raw=$raw -> $parsed s")
                return parsed to "SUMMARY:$actual"
            }
        }
        // STATS
        for (k in keys) {
            val actual = findKeyCaseInsensitive(statsObj, k) ?: continue
            val raw = statsObj?.opt(actual)
            val parsed = parseElapsedValue(raw, actual)
            if (parsed != null) {
                Log.d(TAG, "elapsed ip=$ip source=STATS:$actual raw=$raw -> $parsed s")
                return parsed to "STATS:$actual"
            }
        }
        // V3 summary (new API uses elapsed as seconds)
        for (k in keys) {
            val actual = findKeyCaseInsensitive(v3Summary, k) ?: continue
            val raw = v3Summary?.opt(actual)
            val parsed = parseElapsedValue(raw, actual)
            if (parsed != null) {
                Log.d(TAG, "elapsed ip=$ip source=V3-summary:$actual raw=$raw -> $parsed s")
                return parsed to "V3-summary:$actual"
            }
        }
        // Version or other? Some firmwares expose uptime elsewhere
        for (k in keys) {
            val actual = findKeyCaseInsensitive(versionMsg, k) ?: continue
            val raw = versionMsg?.opt(actual)
            val parsed = parseElapsedValue(raw, actual)
            if (parsed != null) {
                Log.d(TAG, "elapsed ip=$ip source=version:$actual raw=$raw -> $parsed s")
                return parsed to "version:$actual"
            }
        }
        Log.d(TAG, "elapsed ip=$ip MISSING keys tried=${keys.joinToString()} summaryKeys=${summaryObj?.keys()?.asSequence()?.toList()} v3Keys=${v3Summary?.keys()?.asSequence()?.toList()}")
        return null
    }

    private fun resolvePower(ip: String, summaryObj: JSONObject?, statsObj: JSONObject?, v3Summary: JSONObject?, psuMsg: JSONObject?, psuRoot: JSONObject?, v3PowerObj: JSONObject?): Pair<Int, String>? {
        val summaryKeys = listOf("Power", "Power Real", "Power Current", "Power Watt", "Watt", "power", "Power Consumption", "Power Draw", "Power Limit", "Wattage", "PowerUsage", "Power Value", "Current Power", "Power AC", "Power DC", "W", "watt", "power-realtime", "power-5min", "power-rate")
        val psuKeys = listOf("power", "Power", "power_limit", "Power Limit", "Current Power", "Watt", "watt", "Power Draw", "Power Value", "Power Real", "power_real", "Power AC", "Power Usage", "power_value", "pin")
        fun convertPower(raw: Double, str: String?, key: String): Int {
            val s = (str ?: "").lowercase()
            val lk = key.lowercase()
            if (s.contains("kw") || lk.contains("kw")) return (raw * 1000).toInt()
            // Heuristic: if value < 100 likely kW (e.g., 3.2 kW), convert
            if (raw in 0.1..100.0 && (s.contains("kw") || (!s.contains("w") && raw < 100))) {
                // ambiguous, but if raw < 100 and no explicit W, likely kW on some fw? Check key
                // Better to keep W if raw is e.g., 30 kW would be 30, but 30W is impossible for miner, so likely kW
                if (raw < 120) {
                    Log.d(TAG, "power heuristic ip=$ip raw=$raw assumed kW -> ${raw*1000}W")
                    return (raw * 1000).toInt()
                }
            }
            return raw.toInt()
        }
        findDoubleWithSource(summaryObj, summaryKeys)?.let { (v, k) ->
            val str = summaryObj?.optString(k)
            val w = convertPower(v, str, k)
            Log.d(TAG, "power ip=$ip source=SUMMARY:$k raw=$v str='$str' -> $w W")
            if (w in 100..20000) return w to "SUMMARY:$k"
            // Even if out of range, return but log warning
            Log.w(TAG, "power ip=$ip SUMMARY:$k value $w W out of expected range")
            return w to "SUMMARY:$k"
        }
        findDoubleWithSource(statsObj, summaryKeys)?.let { (v, k) ->
            val str = statsObj?.optString(k)
            val w = convertPower(v, str, k)
            Log.d(TAG, "power ip=$ip source=STATS:$k raw=$v str='$str' -> $w W")
            return w to "STATS:$k"
        }
        findDoubleWithSource(psuMsg, psuKeys)?.let { (v, k) ->
            val str = psuMsg?.optString(k)
            val w = convertPower(v, str, k)
            Log.d(TAG, "power ip=$ip source=PSU-Msg:$k raw=$v str='$str' -> $w W")
            return w to "PSU-Msg:$k"
        }
        findDoubleWithSource(psuRoot, psuKeys)?.let { (v, k) ->
            val str = psuRoot?.optString(k)
            val w = convertPower(v, str, k)
            Log.d(TAG, "power ip=$ip source=PSU-root:$k raw=$v -> $w W")
            return w to "PSU-root:$k"
        }
        // V3 power (new API): summary power-realtime/power-5min and device power.pin
        findDoubleWithSource(v3Summary, listOf("power-realtime", "power-5min", "power", "Power", "pin", "power_rate"))?.let { (v, k) ->
            val str = v3Summary?.optString(k)
            val w = convertPower(v, str, k)
            Log.d(TAG, "power ip=$ip source=V3-summary:$k raw=$v str='$str' -> $w W")
            // pin in deviceInfo is already Watts (3264), power-realtime also Watts
            return w to "V3-summary:$k"
        }
        findDoubleWithSource(v3PowerObj, psuKeys)?.let { (v, k) ->
            val str = v3PowerObj?.optString(k)
            val w = convertPower(v, str, k)
            Log.d(TAG, "power ip=$ip source=V3-power:$k raw=$v str='$str' -> $w W")
            return w to "V3-power:$k"
        }
        // Last resort: scan any numeric key containing "power" or "watt" in any object
        for (obj in listOf(summaryObj to "SUMMARY", statsObj to "STATS", psuMsg to "PSU", v3Summary to "V3-summary", v3PowerObj to "V3-power")) {
            val jo = obj.first ?: continue
            val keys = jo.keys()
            while (keys.hasNext()) {
                val kk = keys.next()
                if (kk.contains("power", ignoreCase = true) || kk.contains("watt", ignoreCase = true)) {
                    val raw = jo.opt(kk)
                    val d = when (raw) {
                        is Number -> raw.toDouble()
                        is String -> raw.trim().toDoubleOrNullCompat()
                        else -> null
                    }
                    if (d != null && d > 0) {
                        val w = convertPower(d, raw.toString(), kk)
                        if (w in 100..20000) {
                            Log.d(TAG, "power ip=$ip source=${obj.second}:$kk (fallback scan) -> $w W")
                            return w to "${obj.second}:$kk"
                        }
                    }
                }
            }
        }
        Log.d(TAG, "power ip=$ip MISSING")
        return null
    }

    private fun resolveAccepted(ip: String, summaryObj: JSONObject?, statsObj: JSONObject?, poolObj: JSONObject?, poolsArray: JSONArray?, v3PoolsArray: JSONArray?): Pair<Int, String>? {
        val keys = listOf("Accepted", "accepted", "ACCEPTED", "Accepted Shares", "AcceptedShares", "Accepted Count", "Accept", "accept", "Pool Accepted", "pool_accepted", "AcceptedCount", "Accepted_Count", "AcceptedSharesCount")
        findIntWithSource(summaryObj, keys)?.let { (v, k) -> Log.d(TAG, "accepted ip=$ip source=SUMMARY:$k -> $v"); return v to "SUMMARY:$k" }
        findIntWithSource(statsObj, keys)?.let { (v, k) -> Log.d(TAG, "accepted ip=$ip source=STATS:$k -> $v"); return v to "STATS:$k" }
        findIntWithSource(poolObj, keys)?.let { (v, k) -> Log.d(TAG, "accepted ip=$ip source=POOLS[0]:$k -> $v"); return v to "POOLS[0]:$k" }
        // Aggregate across all pools if poolsArray present
        if (poolsArray != null && poolsArray.length() > 0) {
            var sum = 0; var found = false
            for (i in 0 until poolsArray.length()) {
                val p = poolsArray.optJSONObject(i) ?: continue
                val r = findIntWithSource(p, keys)
                if (r != null) { sum += r.first; found = true }
            }
            if (found) {
                Log.d(TAG, "accepted ip=$ip source=POOLS-aggregated -> $sum")
                return sum to "POOLS-aggregated"
            }
        }
        // V3 pools (new API) currently has no Accepted count (only reject-rate), so will be MISSING - that's expected, not error.
        if (v3PoolsArray != null && v3PoolsArray.length() > 0) {
            var sum = 0; var found = false
            for (i in 0 until v3PoolsArray.length()) {
                val p = v3PoolsArray.optJSONObject(i) ?: continue
                val r = findIntWithSource(p, keys)
                if (r != null) { sum += r.first; found = true }
            }
            if (found) {
                Log.d(TAG, "accepted ip=$ip source=V3-POOLS-aggregated -> $sum")
                return sum to "V3-POOLS-aggregated"
            }
        }
        // Scan any object for accepted key as last resort
        for (obj in listOf(summaryObj to "SUMMARY", statsObj to "STATS")) {
            val jo = obj.first ?: continue
            val actual = findKeyCaseInsensitive(jo, "Accepted") ?: findKeyCaseInsensitive(jo, "accept") ?: continue
            val d = jo.opt(actual)
            val v = when (d) { is Number -> d.toInt(); is String -> d.trim().toIntOrNull(); else -> null }
            if (v != null) { Log.d(TAG, "accepted ip=$ip source=${obj.second}:$actual (fallback) -> $v"); return v to "${obj.second}:$actual" }
        }
        Log.d(TAG, "accepted ip=$ip MISSING (note: V3 new API intentionally has no Accepted count, expected MISSING for M50/M60 new firmware)")
        return null
    }

    private fun resolveRejected(ip: String, summaryObj: JSONObject?, statsObj: JSONObject?, poolObj: JSONObject?, poolsArray: JSONArray?, v3PoolsArray: JSONArray?): Pair<Int, String>? {
        val keys = listOf("Rejected", "rejected", "REJECTED", "Rejected Shares", "RejectedShares", "Rejected Count", "Reject", "reject", "Pool Rejected", "Stale", "Discarded", "RejectedCount", "Rejected_Count", "RejectedSharesCount", "HW", "Hardware Errors", "reject-rate", "reject_rate")
        findIntWithSource(summaryObj, keys)?.let { (v, k) -> Log.d(TAG, "rejected ip=$ip source=SUMMARY:$k -> $v"); return v to "SUMMARY:$k" }
        findIntWithSource(statsObj, keys)?.let { (v, k) -> Log.d(TAG, "rejected ip=$ip source=STATS:$k -> $v"); return v to "STATS:$k" }
        findIntWithSource(poolObj, keys)?.let { (v, k) -> Log.d(TAG, "rejected ip=$ip source=POOLS[0]:$k -> $v"); return v to "POOLS[0]:$k" }
        if (poolsArray != null && poolsArray.length() > 0) {
            var sum = 0; var found = false
            for (i in 0 until poolsArray.length()) {
                val p = poolsArray.optJSONObject(i) ?: continue
                val r = findIntWithSource(p, keys)
                if (r != null) { sum += r.first; found = true }
            }
            if (found) {
                Log.d(TAG, "rejected ip=$ip source=POOLS-aggregated -> $sum")
                return sum to "POOLS-aggregated"
            }
        }
        // V3: reject-rate is 0..1 fraction -> convert to count estimate if needed, but we treat as 0 for missing
        if (v3PoolsArray != null && v3PoolsArray.length() > 0) {
            for (i in 0 until v3PoolsArray.length()) {
                val p = v3PoolsArray.optJSONObject(i) ?: continue
                val r = findDoubleWithSource(p, listOf("reject-rate", "reject_rate", "Reject Rate"))
                if (r != null) {
                    val rate = r.first
                    // If rate is very small (<1) it's ratio, not count; treat 0 as notRejected, >0 as approx
                    val approx = if (rate < 1 && rate > 0) (rate * 100).toInt() else rate.toInt()
                    Log.d(TAG, "rejected ip=$ip source=V3-POOLS:reject-rate=$rate -> approx $approx")
                    return approx to "V3-POOLS:reject-rate"
                }
            }
        }
        Log.d(TAG, "rejected ip=$ip MISSING")
        return null
    }

    private fun resolveFanSpeed(ip: String, summaryObj: JSONObject?, statsObj: JSONObject?, v3Summary: JSONObject?, isInput: Boolean): Pair<Int, String>? {
        val inputKeys = listOf("Fan Speed In", "FanSpeedIn", "fan_speed_in", "Fan In", "FanIn", "Fan 1", "Fan1", "FAN_SPEED_IN", "Fan Input", "Intake Fan", "FanInSpeed", "Fan In Speed", "Cooling Fan In", "fan1", "fan_in", "FAN1", "FANIN", "Fan In RPM", "FAN IN", "fan-speed-in")
        val outputKeys = listOf("Fan Speed Out", "FanSpeedOut", "fan_speed_out", "Fan Out", "FanOut", "Fan 2", "Fan2", "FAN_SPEED_OUT", "Fan Output", "Exhaust Fan", "fan2", "fan_out", "FAN2", "FANOUT", "Fan Out RPM", "FAN OUT", "fan-speed-out")
        val keys = if (isInput) inputKeys else outputKeys

        // Direct key search - SUMMARY, STATS, V3
        findIntWithSource(summaryObj, keys)?.let { (v, k) -> if (v in 0..15000) { Log.d(TAG, "fan${if(isInput) "In" else "Out"} ip=$ip source=SUMMARY:$k -> $v"); return v to "SUMMARY:$k" } }
        findIntWithSource(statsObj, keys)?.let { (v, k) -> if (v in 0..15000) { Log.d(TAG, "fan${if(isInput) "In" else "Out"} ip=$ip source=STATS:$k -> $v"); return v to "STATS:$k" } }
        findIntWithSource(v3Summary, keys)?.let { (v, k) -> if (v in 0..15000) { Log.d(TAG, "fan${if(isInput) "In" else "Out"} ip=$ip source=V3-summary:$k -> $v"); return v to "V3-summary:$k" } }

        // Array-based: "Fans" or "Fan Speed" as JSONArray
        val arrayKeys = listOf("Fans", "fans", "FANS", "Fan Speed", "FanSpeed", "fan_speed", "Fan", "fan", "FAN", "Cooling Fans")
        for (obj in listOf(summaryObj to "SUMMARY", statsObj to "STATS", v3Summary to "V3-summary")) {
            val jo = obj.first ?: continue
            for (ak in arrayKeys) {
                val actual = findKeyCaseInsensitive(jo, ak) ?: continue
                val arr = jo.optJSONArray(actual)
                if (arr != null && arr.length() >= 1) {
                    // Heuristic: Fans[0]=In, Fans[1]=Out; sometimes more fans, take first two
                    val idx = if (isInput) 0 else 1
                    if (idx < arr.length()) {
                        val raw = arr.opt(idx)
                        val v = when (raw) { is Number -> raw.toInt(); is String -> raw.trim().toIntOrNullCompat()?.toInt(); else -> null }
                        if (v != null && v in 0..15000) {
                            Log.d(TAG, "fan${if(isInput) "In" else "Out"} ip=$ip source=${obj.second}:$actual[$idx] -> $v")
                            return v to "${obj.second}:$actual[$idx]"
                        }
                    }
                    // If array is of objects with Speed field?
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val f = findIntWithSource(item, listOf("Speed", "RPM", "rpm", "Fan Speed", "Value"))
                        if (f != null && f.first in 0..15000) {
                            // Map first object to In, second to Out if array length >=2
                            if ((isInput && i == 0) || (!isInput && i == 1) || arr.length() == 1) {
                                Log.d(TAG, "fan${if(isInput) "In" else "Out"} ip=$ip source=${obj.second}:$actual[$i].Speed -> ${f.first}")
                                return f.first to "${obj.second}:$actual[$i].Speed"
                            }
                        }
                    }
                }
            }
        }

        // Fallback scan: any key containing "fan" and "in"/"out" or numeric fan1/fan2
        for (obj in listOf(summaryObj to "SUMMARY", statsObj to "STATS", v3Summary to "V3-summary")) {
            val jo = obj.first ?: continue
            val iter = jo.keys()
            while (iter.hasNext()) {
                val kk = iter.next()
                val lk = kk.lowercase()
                val isInKey = lk.contains("fan") && (lk.contains("in") || lk.contains("1") || lk.contains("intake"))
                val isOutKey = lk.contains("fan") && (lk.contains("out") || lk.contains("2") || lk.contains("exhaust") || lk.contains("output"))
                val match = if (isInput) isInKey else isOutKey
                if (match) {
                    val raw = jo.opt(kk)
                    val v = when (raw) { is Number -> raw.toInt(); is String -> raw.trim().toIntOrNullCompat()?.toInt(); is JSONArray -> { if (raw.length()>0) raw.optInt(0) else null }; else -> null }
                    if (v != null && v in 0..15000) {
                        Log.d(TAG, "fan${if(isInput) "In" else "Out"} ip=$ip source=${obj.second}:$kk (fallback scan) -> $v")
                        return v to "${obj.second}:$kk"
                    }
                }
            }
            // Single "Fan Speed" key (some firmwares report one value for both fans)
            val single = findKeyCaseInsensitive(jo, "Fan Speed") ?: findKeyCaseInsensitive(jo, "FanSpeed")
            if (single != null) {
                val raw = jo.opt(single)
                val v = when (raw) { is Number -> raw.toInt(); is String -> raw.trim().toIntOrNullCompat()?.toInt(); else -> null }
                if (v != null && v in 0..15000) {
                    Log.d(TAG, "fan${if(isInput) "In" else "Out"} ip=$ip source=${obj.second}:$single (single fallback) -> $v")
                    return v to "${obj.second}:$single"
                }
            }
        }
        Log.d(TAG, "fan${if(isInput) "In" else "Out"} ip=$ip MISSING")
        return null
    }

    private fun String.toIntOrNullCompat(): Int? {
        return this.trim().toDoubleOrNullCompat()?.toInt()
    }

    // Helpers: case-insensitive and normalized key handling ------------------------

    private fun normalizeKey(k: String): String = k.lowercase().replace(Regex("[_\\s-]+"), "")

    private fun findKeyCaseInsensitive(obj: JSONObject?, target: String): String? {
        if (obj == null) return null
        val targetLower = target.lowercase()
        val targetNorm = normalizeKey(target)
        var fallbackNorm: String? = null
        val keys = obj.keys()
        while (keys.hasNext()) {
            val kk = keys.next()
            if (kk.equals(target, ignoreCase = true)) return kk
            if (normalizeKey(kk) == targetNorm && fallbackNorm == null) fallbackNorm = kk
            if (kk.lowercase() == targetLower && fallbackNorm == null) fallbackNorm = kk
        }
        return fallbackNorm
    }

    private fun getArrayCaseInsensitive(root: JSONObject?, key: String): JSONArray? {
        if (root == null) return null
        root.optJSONArray(key)?.let { return it }
        val actual = findKeyCaseInsensitive(root, key) ?: return null
        return root.optJSONArray(actual)
    }

    private fun findObjectCaseInsensitive(root: JSONObject?, key: String): JSONObject? {
        if (root == null) return null
        root.optJSONObject(key)?.let { return it }
        val actual = findKeyCaseInsensitive(root, key) ?: return null
        return root.optJSONObject(actual)
    }

    private fun findArrayCaseInsensitive(obj: JSONObject, key: String): JSONArray? {
        obj.optJSONArray(key)?.let { return it }
        val actual = findKeyCaseInsensitive(obj, key) ?: return null
        return obj.optJSONArray(actual)
    }

    private fun firstArrayObject(root: JSONObject?, key: String): JSONObject? {
        if (root == null) return null
        // Try exact, then case-insensitive, then normalized
        var arr = root.optJSONArray(key)
        if (arr == null) {
            val actual = findKeyCaseInsensitive(root, key)
            if (actual != null) arr = root.optJSONArray(actual)
        }
        if (arr == null) {
            // Try to find any key that normalizes to same
            val normTarget = normalizeKey(key)
            val keys = root.keys()
            while (keys.hasNext()) {
                val kk = keys.next()
                if (normalizeKey(kk) == normTarget) {
                    arr = root.optJSONArray(kk)
                    if (arr != null) break
                }
            }
        }
        if (arr == null) return null
        return if (arr.length() > 0) arr.optJSONObject(0) else null
    }

    private fun findDouble(obj: JSONObject?, keys: List<String>): Double? {
        if (obj == null) return null
        // 1) Exact case-sensitive
        for (k in keys) if (obj.has(k) && !obj.isNull(k)) {
            val v = obj.opt(k)
            val d = when (v) {
                is Number -> v.toDouble()
                is String -> v.trim().toDoubleOrNullCompat()
                else -> null
            }
            if (d != null) return d
        }
        // 2) Case-insensitive / normalized fallback: build map of normalized -> actual key
        val normMap = mutableMapOf<String, String>()
        val lowerMap = mutableMapOf<String, String>()
        val kIter = obj.keys()
        while (kIter.hasNext()) {
            val kk = kIter.next()
            lowerMap[kk.lowercase()] = kk
            normMap[normalizeKey(kk)] = kk
        }
        for (k in keys) {
            val lower = k.lowercase()
            val norm = normalizeKey(k)
            val actual = lowerMap[lower] ?: normMap[norm]
            if (actual != null && !obj.isNull(actual)) {
                val v = obj.opt(actual)
                val d = when (v) {
                    is Number -> v.toDouble()
                    is String -> v.trim().toDoubleOrNullCompat()
                    else -> null
                }
                if (d != null) return d
            }
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
            if (v.isNotBlank()) return v.trim()
        }
        // Case-insensitive fallback
        val lowerMap = mutableMapOf<String, String>()
        val normMap = mutableMapOf<String, String>()
        val kIter = obj.keys()
        while (kIter.hasNext()) {
            val kk = kIter.next()
            lowerMap[kk.lowercase()] = kk
            normMap[normalizeKey(kk)] = kk
        }
        for (k in keys) {
            val actual = lowerMap[k.lowercase()] ?: normMap[normalizeKey(k)]
            if (actual != null && !obj.isNull(actual)) {
                val v = obj.optString(actual)
                if (v.isNotBlank()) return v.trim()
            }
        }
        return null
    }

    private fun String.toDoubleOrNullCompat(): Double? = try {
        // Handle strings like "1234.5 TH/s" or "4500 RPM" - extract leading numeric part
        val trimmed = this.trim()
        // Try direct
        trimmed.toDoubleOrNull() ?: run {
            // Extract first numeric token (including negative and decimal)
            val m = Regex("""-?\d+(\.\d+)?""").find(trimmed)
            m?.value?.toDoubleOrNull()
        }
    } catch (e: NumberFormatException) {
        null
    }

    // ================= دستورهای ممتاز (Writable API) =================
    // طبق مستندات رسمی MicroBT (Whatsminer API v2.0.5) و پیاده‌سازی‌های واقعی شناخته‌شده
    // (کتابخانه‌های whatsminer-api و pyasic که روی دستگاه‌های واقعی استفاده می‌شوند):
    //
    // 1) {"cmd":"get_token"} -> {"Msg":{"time":"...","salt":"...","newsalt":"..."}}
    // 2) key  = crypt(admin_password, salt)   -- الگوریتم استاندارد یونیکس md5crypt (فرمت $1$salt$hash)
    //                                             معادل دستور شل: openssl passwd -1 -salt $salt "$password"
    //                                             (این یک هش MD5 ساده نیست؛ ۱۰۰۰ دور تکرار دارد)
    // 3) sign = crypt(key + time, newsalt)    -- با همان الگوریتم، رشتهٔ بعد از سومین $ در خروجی
    // 4) کلید AES = SHA-256(key) به‌صورت ۳۲ بایت خام (نه رشتهٔ hex)
    // 5) دستور نهایی (JSON شامل "token": sign) با null بایت تا مضربی از ۱۶ پد شده، با
    //    AES-256-ECB رمزنگاری و به‌صورت {"enc":1,"data":"<base64>"} ارسال می‌شود
    // 6) پاسخ هم رمزنگاری‌شده برمی‌گردد: {"enc":"<base64>"} که باید با همان کلید رمزگشایی شود
    //
    // نسخهٔ قبلی این فایل به اشتباه از یک MD5 ساده (بدون salt واقعی/تکرار) استفاده می‌کرد و دستور را
    // بدون رمزنگاری AES ارسال می‌کرد؛ در نتیجه صرف‌نظر از درستیِ رمز واقعی دستگاه، امضا هیچ‌وقت با
    // چیزی که دستگاه انتظار داشت مطابقت نمی‌کرد و همیشه خطای «رمز اشتباه» (کد ۴۵) برمی‌گشت.

    private const val ITOA64 = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    private fun md5(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        for (p in parts) md.update(p)
        return md.digest()
    }

    private fun to64(valueIn: Long, length: Int): String {
        var value = valueIn
        val sb = StringBuilder()
        repeat(length) {
            sb.append(ITOA64[(value and 0x3F).toInt()])
            value = value ushr 6
        }
        return sb.toString()
    }

    /**
     * پیاده‌سازی الگوریتم استاندارد یونیکس MD5-crypt (فرمت $1$salt$hash) که Whatsminer برای
     * تولید "key" و "sign" استفاده می‌کند. با passlib (پایتون) خط‌به‌خط تست و تأیید شده است.
     */
    private fun md5Crypt(password: String, saltIn: String): String {
        val salt = saltIn.substringBefore('$').take(8)
        val pw = password.toByteArray(Charsets.UTF_8)
        val saltBytes = salt.toByteArray(Charsets.UTF_8)

        var final = md5(pw, saltBytes, pw)

        val md = MessageDigest.getInstance("MD5")
        md.update(pw)
        md.update("$1$".toByteArray(Charsets.UTF_8))
        md.update(saltBytes)

        var pl = pw.size
        while (pl > 0) {
            val take = minOf(pl, 16)
            md.update(final, 0, take)
            pl -= 16
        }

        var i = pw.size
        while (i != 0) {
            if (i and 1 != 0) md.update(byteArrayOf(0)) else md.update(pw, 0, 1)
            i = i shr 1
        }

        final = md.digest()

        for (round in 0 until 1000) {
            val ctx1 = MessageDigest.getInstance("MD5")
            if (round and 1 != 0) ctx1.update(pw) else ctx1.update(final)
            if (round % 3 != 0) ctx1.update(saltBytes)
            if (round % 7 != 0) ctx1.update(pw)
            if (round and 1 != 0) ctx1.update(final) else ctx1.update(pw)
            final = ctx1.digest()
        }

        fun b(idx: Int): Long = (final[idx].toLong() and 0xFFL)

        val out = StringBuilder()
        out.append(to64((b(0) shl 16) or (b(6) shl 8) or b(12), 4))
        out.append(to64((b(1) shl 16) or (b(7) shl 8) or b(13), 4))
        out.append(to64((b(2) shl 16) or (b(8) shl 8) or b(14), 4))
        out.append(to64((b(3) shl 16) or (b(9) shl 8) or b(15), 4))
        out.append(to64((b(4) shl 16) or (b(10) shl 8) or b(5), 4))
        out.append(to64(b(11), 2))

        return "$1$$salt$$out"
    }

    /** فقط بخش hash را از خروجی md5Crypt (فرمت $1$salt$hash) برمی‌گرداند */
    private fun md5CryptHashPart(password: String, salt: String): String =
        md5Crypt(password, salt).split("$").getOrElse(3) { "" }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (byte in digest) sb.append(String.format("%02x", byte))
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (idx in out.indices) {
            out[idx] = ((Character.digit(hex[idx * 2], 16) shl 4) + Character.digit(hex[idx * 2 + 1], 16)).toByte()
        }
        return out
    }

    private fun padTo16(input: ByteArray): ByteArray {
        val remainder = input.size % 16
        if (remainder == 0) return input
        val padded = ByteArray(input.size + (16 - remainder))
        System.arraycopy(input, 0, padded, 0, input.size)
        return padded
    }

    private fun aesKeyFromPasswordHash(passwordHashPart: String): ByteArray =
        hexToBytes(sha256Hex(passwordHashPart))

    private fun aesEncryptEcb(plainJson: String, aesKey: ByteArray): String {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))
        val encrypted = cipher.doFinal(padTo16(plainJson.toByteArray(Charsets.UTF_8)))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun aesDecryptEcb(base64Cipher: String, aesKey: ByteArray): String {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"))
        val decrypted = cipher.doFinal(Base64.decode(base64Cipher, Base64.NO_WRAP))
        return decrypted.toString(Charsets.UTF_8).trimEnd('\u0000')
    }

    private suspend fun getToken(ip: String): TokenInfo? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"get_token"}""", retries = 1) ?: return null
        val json = parseJsonLenient(raw) ?: runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val msg = json.optJSONObject("Msg") ?: json.optJSONObject("msg") ?: findObjectCaseInsensitive(json, "Msg") ?: return null
        val time = findString(msg, listOf("time", "Time", "TIME")) ?: msg.optString("time")
        val salt = findString(msg, listOf("salt", "Salt", "SALT")) ?: msg.optString("salt")
        val newSalt = findString(msg, listOf("newsalt", "newSalt", "new_salt", "NewSalt")) ?: msg.optString("newsalt")
        if (time.isBlank() || salt.isBlank() || newSalt.isBlank()) {
            Log.d(TAG, "getToken missing fields ip=$ip time=$time salt=$salt newsalt=$newSalt")
            return null
        }
        return TokenInfo(time, salt, newSalt)
    }

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * یک دستور ممتاز را با گرفتن توکن تازه، امضا کردن با رمز عبور دستگاه، و رمزنگاری AES-256-ECB
     * کل دستور (طبق پروتکل رسمی) اجرا می‌کند. buildCommand باید JSON کامل شامل فیلد "token" را بسازد.
     */
    private suspend fun sendPrivileged(
        ip: String,
        password: String,
        buildCommand: (token: String) -> String
    ): PrivilegedResult {
        val token = getToken(ip)
            ?: return PrivilegedResult(false, "اتصال برای دریافت توکن از دستگاه ناموفق بود")

        val keyHash = md5CryptHashPart(password, token.salt)
        val sign = md5CryptHashPart(keyHash + token.time, token.newSalt)
        val aesKey = aesKeyFromPasswordHash(keyHash)

        val commandJson = buildCommand(sign)
        val encryptedPayload = """{"enc":1,"data":"${aesEncryptEcb(commandJson, aesKey)}"}"""

        val raw = sendRawCommand(ip, encryptedPayload)
            ?: return PrivilegedResult(false, "پاسخی از دستگاه دریافت نشد")
        val rawJson = parseJsonLenient(raw) ?: runCatching { JSONObject(raw) }.getOrNull()
            ?: return PrivilegedResult(false, "پاسخ نامعتبر از دستگاه: ${raw.take(200)}")

        // پاسخ طبق پروتکل رسمی رمزنگاری‌شده برمی‌گردد: {"enc":"<base64>"}
        // برای اطمینان، اگر فریمورِ خاصی پاسخ را رمزنگاری‌نشده برگرداند هم پشتیبانی می‌شود
        val encField = rawJson.opt("enc")
        val resultJson = if (encField is String && encField.isNotBlank()) {
            val decrypted = runCatching { aesDecryptEcb(encField, aesKey) }.getOrNull()
            decrypted?.let { runCatching { JSONObject(it) }.getOrNull() ?: parseJsonLenient(it) } ?: rawJson
        } else {
            rawJson
        }

        val code = resultJson.optInt("Code", -1)
        val status = resultJson.optString("STATUS")
        return when {
            code == 45 -> PrivilegedResult(false, "رمز عبور اشتباه است", wrongPassword = true)
            code == 131 || status.equals("S", ignoreCase = true) ->
                PrivilegedResult(true, "عملیات با موفقیت انجام شد")
            else -> PrivilegedResult(false, resultJson.optString("Msg").ifBlank { resultJson.optString("msg").ifBlank { "خطای نامشخص از دستگاه (کد $code)" } })
        }
    }

    suspend fun reboot(ip: String, password: String): PrivilegedResult =
        sendPrivileged(ip, password) { token -> """{"cmd":"reboot","token":"$token"}""" }

    /**
     * تعویض پول‌های ماینینگ دستگاه (حداکثر ۳ پول). عملیات بلافاصله پس از اجرا اعمال می‌شود.
     */
    suspend fun updatePools(ip: String, password: String, pools: List<PoolEntry>): PrivilegedResult {
        if (pools.isEmpty()) return PrivilegedResult(false, "هیچ پولی برای تنظیم مشخص نشده است")
        val padded = (0 until 3).map { idx -> pools.getOrNull(idx) ?: pools.last() }

        return sendPrivileged(ip, password) { token ->
            val sb = StringBuilder()
            sb.append("""{"cmd":"update_pools"""")
            padded.forEachIndexed { idx, pool ->
                val n = idx + 1
                sb.append(""","pool$n":"${jsonEscape(pool.url)}"""")
                sb.append(""","worker$n":"${jsonEscape(pool.worker)}"""")
                sb.append(""","passwd$n":"${jsonEscape(pool.pass)}"""")
            }
            sb.append(""","token":"$token"}""")
            sb.toString()
        }
    }
}
