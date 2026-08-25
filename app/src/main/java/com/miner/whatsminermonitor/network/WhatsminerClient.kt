package com.miner.whatsminermonitor.network

import android.util.Base64
import android.util.Log
import com.miner.whatsminermonitor.model.HashboardInfo
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
    private const val CONNECT_TIMEOUT_MS = 3500
    private const val READ_TIMEOUT_MS = 4000
    private const val TAG = "WhatsminerClient"

    suspend fun isPortOpen(ip: String, timeoutMs: Int = 800): Boolean = withContext(Dispatchers.IO) {
        // تلاش با تایم‌اوت کمی بیشتر؛ دستگاه‌های زیر بار ممکن است 400ms را پاسخ ندهند
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, API_PORT), timeoutMs)
                true
            }
        } catch (e: Exception) {
            Log.d(TAG, "isPortOpen failed ip=$ip timeout=${timeoutMs}ms err=${e.message}")
            false
        }
    }

    private suspend fun sendRawCommand(ip: String, command: String): String? = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, API_PORT), CONNECT_TIMEOUT_MS)
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
        delayMs: Long = 400
    ): String? {
        var result = sendRawCommand(ip, command)
        // Treat blank/empty as failure as well
        if (result != null && result.isBlank()) result = null
        var attemptsLeft = retries
        while ((result == null || result.isBlank()) && attemptsLeft > 0) {
            Log.d(TAG, "retry ip=$ip cmd=$command attemptsLeft=$attemptsLeft")
            delay(delayMs)
            result = sendRawCommand(ip, command)
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
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"summary"}""", retries = 1) ?: return null
        return parseJsonLenient(raw)
    }

    suspend fun fetchDevs(ip: String): JSONObject? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"devs"}""", retries = 1) ?: return null
        return parseJsonLenient(raw)
    }

    // نسخه فریمور و پلتفرم دستگاه
    suspend fun fetchVersion(ip: String): JSONObject? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"get_version"}""", retries = 1) ?: return null
        return parseJsonLenient(raw)
    }

    // نسخهٔ استاندارد دستور "version" (پروتکل پایهٔ cgminer که Whatsminer هم روی آن ساخته شده)؛
    // به‌عنوان پشتیبان وقتی get_version چیزی برنمی‌گرداند - بعضی فریمورها/مدل‌ها فقط به این
    // فرمت پاسخ می‌دهند و مدل/فریمور را زیر کلیدهای دیگری (VERSION[0].Type / .Miner) برمی‌گردانند
    suspend fun fetchVersionStandard(ip: String): JSONObject? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"version"}""", retries = 1) ?: return null
        return parseJsonLenient(raw)
    }

    // جزئیات هش‌برد؛ شامل مدل دقیق دستگاه (مثلاً M31S+VE40)
    suspend fun fetchDevDetails(ip: String): JSONObject? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"devdetails"}""", retries = 1) ?: return null
        return parseJsonLenient(raw)
    }

    // اطلاعات منبع تغذیه (پاور)
    suspend fun fetchPsu(ip: String): JSONObject? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"get_psu"}""", retries = 1) ?: return null
        return parseJsonLenient(raw)
    }

    // اطلاعات شبکه (از جمله MAC)
    suspend fun fetchMinerInfo(ip: String): JSONObject? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"get_miner_info","info":"mac,ip,hostname"}""", retries = 1) ?: return null
        return parseJsonLenient(raw)
    }

    suspend fun fetchPools(ip: String): JSONObject? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"pools"}""", retries = 1) ?: return null
        return parseJsonLenient(raw)
    }

    // فهرست کدهای خطای فعال دستگاه؛ چون این دستور معمولا آخرین دستور در چرخهٔ خواندن اطلاعات یک
    // دستگاه است (بعد از ۷ اتصال دیگر) و دستگاه‌های Whatsminer گاهی به اتصال‌های پشت‌سرهم سریع
    // به‌کندی/ناپایدار پاسخ می‌دهند، در صورت شکست یک‌بار دیگر با کمی مکث تلاش می‌شود
    suspend fun fetchErrorCode(ip: String): JSONObject? {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"get_error_code"}""", retries = 1) ?: return null
        return parseJsonLenient(raw)
    }

    suspend fun queryMiner(ip: String): MinerInfo {
        var summaryRoot = fetchSummary(ip)
        // Fallback: some firmwares (especially M20S early, or devices under heavy load) may miss first
        // summary but still respond to devs. Try one more quick fetch before marking unreachable.
        if (summaryRoot == null) {
            Log.w(TAG, "queryMiner: summary null ip=$ip, trying devs as fallback before marking unreachable")
            val devsFallback = fetchDevs(ip)
            if (devsFallback != null) {
                // Device is reachable, but summary failed. Create a synthetic summaryRoot so we can still show partial data.
                Log.w(TAG, "queryMiner: devs responded but summary did not ip=$ip -> partial reachable")
                summaryRoot = JSONObject().apply { put("SUMMARY", JSONArray().apply { put(JSONObject()) }) }
                // We will fill summaryObj from devsFallback-derived placeholder; hashboards will still be parsed below
            } else {
                Log.w(TAG, "queryMiner: both summary and devs null ip=$ip -> unreachable")
                return MinerInfo(ip = ip, isReachable = false, errorMessage = "پاسخی از دستگاه دریافت نشد")
            }
        }

        val summaryObj = firstArrayObject(summaryRoot, "SUMMARY")
            ?: findObjectCaseInsensitive(summaryRoot, "SUMMARY")
            ?: summaryRoot.optJSONObject("SUMMARY") // fallback if it's object not array
        if (summaryObj == null) {
            Log.w(TAG, "queryMiner: SUMMARY object not found ip=$ip keys=${summaryRoot.keys().asSequence().toList()}")
        }
        // بین اتصال‌های پشت‌سرهم TCP یک مکث کوتاه گذاشته می‌شود؛ چون دستگاه Whatsminer روی یک
        // پورت با ظرفیت محدود پاسخ می‌دهد، ارسال پشت‌سرهم و بدون فاصلهٔ ۸ دستور جدا می‌تواند باعث
        // شود آخرین دستورها (از جمله get_error_code) گاهی بی‌پاسخ بمانند
        delay(80)
        val devsRoot = fetchDevs(ip)
        val devsArray = getArrayCaseInsensitive(devsRoot, "DEVS")
        if (devsRoot != null && devsArray == null) Log.d(TAG, "DEVS array not found ip=$ip")
        delay(80)
        val versionRoot = fetchVersion(ip)
        val versionMsg = versionRoot?.optJSONObject("Msg") ?: versionRoot?.optJSONObject("msg") ?: findObjectCaseInsensitive(versionRoot, "Msg")
        if (versionRoot != null && versionMsg == null) Log.d(TAG, "version Msg not found ip=$ip rootKeys=${versionRoot.keys().asSequence().toList()}")
        delay(80)
        val devDetailsRoot = fetchDevDetails(ip)
        val devDetailsObj = firstArrayObject(devDetailsRoot, "DEVDETAILS")
            ?: firstArrayObject(devDetailsRoot, "DevDetails")
            ?: findObjectCaseInsensitive(devDetailsRoot, "DEVDETAILS")
        delay(80)
        val psuRoot = fetchPsu(ip)
        val psuMsg = psuRoot?.optJSONObject("Msg") ?: psuRoot?.optJSONObject("msg") ?: findObjectCaseInsensitive(psuRoot, "Msg")
        delay(80)
        val minerInfoRoot = fetchMinerInfo(ip)
        val minerInfoMsg = minerInfoRoot?.optJSONObject("Msg") ?: minerInfoRoot?.optJSONObject("msg") ?: findObjectCaseInsensitive(minerInfoRoot, "Msg")
        delay(80)
        val poolsRoot = fetchPools(ip)
        val poolObj = firstArrayObject(poolsRoot, "POOLS") ?: findObjectCaseInsensitive(poolsRoot, "POOLS")
        delay(80)
        val errorRoot = fetchErrorCode(ip)
        val errorCodes = parseErrorCodes(errorRoot)
        // اگر errorRoot عملا null باشد یعنی دستور get_error_code اصلا پاسخ نگرفته (نه اینکه دستگاه
        // واقعا خطایی نداشته)؛ این تفاوت را جدا نگه می‌داریم تا در UI به‌جای «سالم» به‌درستی «قابل بررسی نبود» نشان داده شود
        val errorCheckFailed = errorRoot == null

        val hashboards = parseHashboards(devsArray)
        val avgTemp = hashboards.mapNotNull { it.temperaturePcb ?: it.temperatureChip }
            .takeIf { it.isNotEmpty() }?.average()

        // GHS 5s (لحظه‌ای) - مقدار خام از دستگاه به MH/s است؛ برای تبدیل به GH/s بر ۱۰۰۰ تقسیم می‌شود
        // برخی فریمورها مقدار را به GH/s مستقیم یا با کلید متفاوت برمی‌گردانند
        val totalHashrate = findDouble(summaryObj, listOf("MHS 5s", "MHS 5s (MHS)", "MHS 5s ", "GHS 5s", "GHS av", "MHS av", "GHS 5s", "HS 5s", "Hash Rate 5s", "MHS 5s", "mhs 5s", "ghs 5s", "MHS5s"))
            ?.let { v ->
                // Heuristic: if value > 1_000_000 then it's likely MH/s -> divide 1000; if < 10000 and we suspect GH/s, keep as is?
                // Most Whatsminer return MH/s, but M50+ may return GH/s scaled differently. We detect: if original key was GHS, don't divide?
                // For simplicity, if value > 200_000, assume MH/s (since GH/s for modern miners 100-200 TH = 100k-200k GH). MH/s would be 100M-200M.
                if (v > 1_000_000) v / 1000.0 else v
            }
            ?: hashboards.mapNotNull { it.hashrateGhs }.takeIf { it.isNotEmpty() }?.sum()

        // GHS av (میانگین) - مشابه بالا
        val ghsAvRaw = findDouble(summaryObj, listOf("MHS av", "MHS Av", "GHS av", "GHS 5s", "MHS average", "mhs av", "ghs av"))
        val ghsAv = ghsAvRaw?.let { v -> if (v > 1_000_000) v / 1000.0 else v }

        // زمان پاسخ پول
        val poolResponseMs = poolObj?.let { findInt(it, listOf("Last Share Time", "LastShareTime", "last_share_time", "Pool Rejected%")) }

        // فریمور / کنترل‌برد / مدل: کلیدهای بیشتری امتحان می‌شوند چون نام آن‌ها بین مدل‌ها و
        // نسخه‌های فریمور مختلف Whatsminer (M2x/M3x/M5x/M6x و...) کمی فرق دارد. اگر get_version
        // چیزی برنگرداند، دستور استاندارد "version" هم به‌عنوان پشتیبان امتحان می‌شود
        var fwVer = findString(versionMsg, listOf("fw_ver", "FWVersion", "fwversion", "BTMiner Version", "miner_version", "Firmware Version", "Version", "fw version", "CompileTime", "BMMiner", "Miner"))
        var platform = findString(versionMsg, listOf("platform", "Platform", "control_board", "Board", "Control Board", "control board"))
        var modelFromVersion = findString(versionMsg, listOf("prod", "miner_type", "Model", "type", "Type", "Miner Type", "miner type", "Model Name"))
        if (fwVer == null || platform == null || modelFromVersion == null) {
            // Fetch once and reuse (previously called twice)
            val stdVersionRoot = fetchVersionStandard(ip)
            val stdVersionObj = firstArrayObject(stdVersionRoot, "VERSION")
                ?: firstArrayObject(stdVersionRoot, "Version")
                ?: findObjectCaseInsensitive(stdVersionRoot, "VERSION")
            if (stdVersionObj != null) {
                if (fwVer == null) fwVer = findString(stdVersionObj, listOf("Miner", "BMMiner", "CompileTime", "Version", "FWVersion", "fw_ver"))
                if (platform == null) platform = findString(stdVersionObj, listOf("Platform", "platform", "Board"))
                if (modelFromVersion == null) modelFromVersion = findString(stdVersionObj, listOf("Type", "Model", "model", "Miner Type"))
            }
            // Also try direct keys in versionRoot itself (some firmwares put them at root Msg level with different casing)
            if (fwVer == null && versionRoot != null) fwVer = findString(versionRoot, listOf("fw_ver", "FWVersion"))
            if (platform == null && versionRoot != null) platform = findString(versionRoot, listOf("platform", "Platform"))
        }

        val minerType = findString(devDetailsObj, listOf("Model", "model", "Type", "type", "prod"))
            ?: modelFromVersion
            ?: findString(summaryObj, listOf("Type", "Model", "model", "Miner Type", "Description"))

        return MinerInfo(
            ip = ip,
            isReachable = true,
            elapsedSeconds = findLong(summaryObj, listOf("Elapsed", "elapsed", "Uptime", "uptime")),
            fanSpeedIn = findInt(summaryObj, listOf("Fan Speed In", "Fan Speed In ", "FanSpeedIn", "fan_speed_in", "Fan In", "FanIn", "fan speed in")),
            fanSpeedOut = findInt(summaryObj, listOf("Fan Speed Out", "Fan Speed Out ", "FanSpeedOut", "fan_speed_out", "Fan Out", "FanOut", "fan speed out")),
            powerWatt = findInt(summaryObj, listOf("Power", "Power Current", "Power Real", "Power Watt", "Watt", "power", "Power Consumption")),
            averageTemperature = avgTemp ?: findDouble(summaryObj, listOf("Temperature", "Temp", "Avg Temp", "Average Temperature", "temperature", "temp", "Temp Avg")),
            totalHashrateGhs = totalHashrate,
            ghsAverage = ghsAv,
            firmwareVersion = fwVer,
            minerType = minerType,
            controlBoard = platform,
            accepted = findInt(summaryObj, listOf("Accepted", "accepted", "Accept", "accept")),
            rejected = findInt(summaryObj, listOf("Rejected", "rejected", "Reject", "reject")),
            poolResponseMs = poolResponseMs,
            hashboards = hashboards,
            macAddress = findString(minerInfoMsg, listOf("mac", "Mac", "MAC", "macaddr", "MacAddr", "MAC Addr", "mac_address")),
            powerSupplyModel = findString(psuMsg, listOf("name", "model", "Model", "psu_model", "PSU Model", "PSUmodel", "psu name")),
            poolWorkerName = findString(poolObj, listOf("User", "user", "Worker", "worker", "username")),
            poolUrl = findString(poolObj, listOf("URL", "Url", "url", "Pool URL", "pool_url", "Stratum URL")),
            errorCodes = errorCodes,
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
