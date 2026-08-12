package com.miner.whatsminermonitor.network

import android.util.Base64
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

    // برخی دستگاه‌ها وقتی چند اتصال TCP پشت‌سرهم و سریع باز می‌شود (مثلا در انتهای چرخهٔ خواندن
    // اطلاعات یک دستگاه) گاهی یکی از دستورها را جواب نمی‌دهند؛ برای دستورهای حساس (مثل کد خطا)
    // در صورت شکست اولیه، یک تلاش دوم بعد از کمی مکث انجام می‌شود
    private suspend fun sendRawCommandWithRetry(
        ip: String,
        command: String,
        retries: Int = 1,
        delayMs: Long = 350
    ): String? {
        var result = sendRawCommand(ip, command)
        var attemptsLeft = retries
        while (result == null && attemptsLeft > 0) {
            delay(delayMs)
            result = sendRawCommand(ip, command)
            attemptsLeft--
        }
        return result
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

    // فهرست کدهای خطای فعال دستگاه؛ چون این دستور معمولا آخرین دستور در چرخهٔ خواندن اطلاعات یک
    // دستگاه است (بعد از ۷ اتصال دیگر) و دستگاه‌های Whatsminer گاهی به اتصال‌های پشت‌سرهم سریع
    // به‌کندی/ناپایدار پاسخ می‌دهند، در صورت شکست یک‌بار دیگر با کمی مکث تلاش می‌شود.
    // متن خام پاسخ هم برگردانده می‌شود (حتی اگر parse نشود) تا در صورت نیاز برای اشکال‌زدایی نشان داده شود
    suspend fun fetchErrorCode(ip: String): Pair<JSONObject?, String?> {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"get_error_code"}""", retries = 1) ?: return null to null
        return runCatching { JSONObject(raw) }.getOrNull() to raw
    }

    suspend fun queryMiner(ip: String): MinerInfo {
        val summaryRoot = fetchSummary(ip)
        if (summaryRoot == null) {
            return MinerInfo(ip = ip, isReachable = false, errorMessage = "پاسخی از دستگاه دریافت نشد")
        }

        val summaryObj = firstArrayObject(summaryRoot, "SUMMARY")
        // بین اتصال‌های پشت‌سرهم TCP یک مکث کوتاه گذاشته می‌شود؛ چون دستگاه Whatsminer روی یک
        // پورت با ظرفیت محدود پاسخ می‌دهد، ارسال پشت‌سرهم و بدون فاصلهٔ ۸ دستور جدا می‌تواند باعث
        // شود آخرین دستورها (از جمله get_error_code) گاهی بی‌پاسخ بمانند
        delay(60)
        val devsRoot = fetchDevs(ip)
        val devsArray = devsRoot?.optJSONArray("DEVS")
        delay(60)
        val versionRoot = fetchVersion(ip)
        val versionMsg = versionRoot?.optJSONObject("Msg")
        delay(60)
        val devDetailsRoot = fetchDevDetails(ip)
        val devDetailsObj = firstArrayObject(devDetailsRoot, "DEVDETAILS")
        delay(60)
        val psuRoot = fetchPsu(ip)
        val psuMsg = psuRoot?.optJSONObject("Msg")
        delay(60)
        val minerInfoRoot = fetchMinerInfo(ip)
        val minerInfoMsg = minerInfoRoot?.optJSONObject("Msg")
        delay(60)
        val poolsRoot = fetchPools(ip)
        val poolObj = firstArrayObject(poolsRoot, "POOLS")
        delay(60)
        val errorRoot = fetchErrorCode(ip)
        val errorCodes = parseErrorCodes(errorRoot.first)
        // اگر errorRoot عملا null باشد یعنی دستور get_error_code اصلا پاسخ نگرفته (نه اینکه دستگاه
        // واقعا خطایی نداشته)؛ این تفاوت را جدا نگه می‌داریم تا در UI به‌جای «سالم» به‌درستی «قابل بررسی نبود» نشان داده شود
        val errorCheckFailed = errorRoot.first == null

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
            errorCodes = errorCodes,
            errorCheckFailed = errorCheckFailed,
            errorRawResponse = errorRoot.second
        )
    }

    // یک رشتهٔ کد خطا را به عدد تبدیل می‌کند؛ هم فرمت اعشاری معمولی (مثلا "203") و هم فرمت
    // هگزادسیمال (مثلا "0x0800" یا حتی بدون پیشوند مثل "0800" برای کدهای پاور که در کاتالوگ
    // هم به‌صورت هگز تعریف شده‌اند) را پشتیبانی می‌کند. قبلاً فقط toIntOrNull ساده استفاده می‌شد
    // که باعث می‌شد کدهای هگزادسیمال بی‌صدا نادیده گرفته شوند و در نتیجه با وجود خطای واقعی روی
    // دستگاه، برنامه پیام «سالم» نشان دهد
    private fun parseCodeToken(raw: String): Int? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return it }
        if (s.startsWith("0x", ignoreCase = true)) {
            return s.substring(2).toIntOrNull(16)
        }
        return null
    }

    // پارس کردن پاسخ get_error_code به فهرستی از کدهای عددی خطای فعال
    // طبق مستندات رسمی، ساختار پاسخ یک شیء است: {"error_code": {"<code>": "<timestamp>", ...}}
    // اما برای اطمینان، حالت آرایه‌ای قدیمی و همچنین نام‌های احتمالاً متفاوت در فریمورهای دیگر هم پشتیبان دارد.
    // نکته مهم: اگر کلید error_code پیدا شد ولی هیچ‌کدام از مقادیرش قابل‌تبدیل به عدد نبودند (مثلا
    // به‌خاطر فرمت هگز)، دیگر بلافاصله با لیست خالی return نمی‌کنیم؛ به‌جای آن به روش‌های
    // جایگزین (fallback) هم سر می‌زنیم تا کد خطا به‌اشتباه گم نشود
    private fun parseErrorCodes(root: JSONObject?): List<Int> {
        if (root == null) return emptyList()
        val msg = root.optJSONObject("Msg") ?: root
        val result = sortedSetOf<Int>()

        val obj = msg.optJSONObject("error_code")
        if (obj != null) {
            val keys = obj.keys()
            while (keys.hasNext()) {
                parseCodeToken(keys.next())?.let { if (it != 0) result.add(it) }
            }
        }

        if (result.isEmpty()) {
            val arr = msg.optJSONArray("error_code")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)
                    val code: Int? = when (item) {
                        is JSONObject -> listOf(
                            item.optString("error_code"),
                            item.optString("code"),
                            item.optString("ErrCode")
                        ).firstOrNull { it.isNotBlank() }?.let(::parseCodeToken)
                        is Number -> item.toInt()
                        is String -> parseCodeToken(item)
                        else -> null
                    }
                    if (code != null && code != 0) result.add(code)
                }
            }
        }

        if (result.isNotEmpty()) return result.toList()

        // یافت نشد؛ به‌جای فرض «بدون خطا»، به‌صورت پشتیبان دنبال هر کلیدی می‌گردیم که
        // شامل «error» باشد (برخی فریمورها ممکن است از نام دیگری به‌جای error_code استفاده کنند)
        val fallbackKeys = msg.keys().asSequence().filter { it.contains("error", ignoreCase = true) }
        for (k in fallbackKeys) {
            when (val v = msg.opt(k)) {
                is JSONObject -> {
                    val ks = v.keys()
                    while (ks.hasNext()) parseCodeToken(ks.next())?.let { if (it != 0) result.add(it) }
                }
                is JSONArray -> for (i in 0 until v.length()) {
                    val code = when (val item = v.opt(i)) {
                        is Number -> item.toInt()
                        is String -> parseCodeToken(item)
                        else -> null
                    }
                    if (code != null && code != 0) result.add(code)
                }
                is Number -> if (v.toInt() != 0) result.add(v.toInt())
                is String -> parseCodeToken(v)?.let { if (it != 0) result.add(it) }
                else -> {}
            }
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

    // نسخه تشخیصی get_token: به‌جای null ساده، متن خام پاسخ دستگاه را هم برمی‌گرداند تا در صورت
    // شکست بشود دقیقاً دید دستگاه چه چیزی پس داده (مثلاً یک فیلد با نام دیگر یا یک ساختار متفاوت)
    private suspend fun getTokenDebug(ip: String): Pair<TokenInfo?, String?> {
        val raw = sendRawCommandWithRetry(ip, """{"cmd":"get_token"}""", retries = 1)
            ?: return null to null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null to raw
        val msg = json.optJSONObject("Msg") ?: return null to raw
        val time = msg.optString("time")
        val salt = msg.optString("salt")
        val newSalt = msg.optString("newsalt")
        if (time.isBlank() || salt.isBlank() || newSalt.isBlank()) return null to raw
        return TokenInfo(time, salt, newSalt) to raw
    }

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * یک دستور ممتاز را با گرفتن توکن تازه، امضا کردن با رمز عبور دستگاه، و رمزنگاری AES-256-ECB
     * کل دستور (طبق پروتکل رسمی) اجرا می‌کند. buildCommand باید JSON کامل شامل فیلد "token" را بسازد.
     *
     * اگر عملیات شکست بخورد، پیام خطا شامل جزئیات خام دستگاه (کد/پیام واقعی برگشتی و نسخه فریمور)
     * هم می‌شود؛ چون این پروتکل بین نسخه‌های مختلف فریمور Whatsminer فرق می‌کند و بدون دیدن دادهٔ
     * خام دستگاه نمی‌شود مطمئن شد مشکل از رمز اشتباه است یا از عدم تطابق نسخهٔ پروتکل
     */
    private suspend fun sendPrivileged(
        ip: String,
        password: String,
        buildCommand: (token: String) -> String
    ): PrivilegedResult {
        val (token, tokenRaw) = getTokenDebug(ip)
        if (token == null) {
            val hint = tokenRaw?.take(200) ?: "بدون پاسخ"
            return PrivilegedResult(false, "دریافت توکن ناموفق بود. پاسخ خام دستگاه: $hint")
        }

        val keyHash = md5CryptHashPart(password, token.salt)
        val sign = md5CryptHashPart(keyHash + token.time, token.newSalt)
        val aesKey = aesKeyFromPasswordHash(keyHash)

        val commandJson = buildCommand(sign)
        val encryptedPayload = """{"enc":1,"data":"${aesEncryptEcb(commandJson, aesKey)}"}"""

        val raw = sendRawCommand(ip, encryptedPayload)
            ?: return PrivilegedResult(false, "پاسخی از دستگاه دریافت نشد (بعد از دریافت توکن موفق)")
        val rawJson = runCatching { JSONObject(raw) }.getOrNull()
            ?: return PrivilegedResult(false, "پاسخ نامعتبر از دستگاه: ${raw.take(200)}")

        // پاسخ طبق پروتکل رسمی رمزنگاری‌شده برمی‌گردد: {"enc":"<base64>"}
        // برای اطمینان، اگر فریمورِ خاصی پاسخ را رمزنگاری‌نشده برگرداند هم پشتیبانی می‌شود
        val encField = rawJson.opt("enc")
        var decryptFailed = false
        val resultJson = if (encField is String && encField.isNotBlank()) {
            val decrypted = runCatching { aesDecryptEcb(encField, aesKey) }.getOrNull()
            val parsed = decrypted?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (parsed == null) decryptFailed = true
            parsed ?: rawJson
        } else {
            rawJson
        }

        val code = resultJson.optInt("Code", -1)
        val status = resultJson.optString("STATUS")
        val deviceMsg = resultJson.optString("Msg")
        return when {
            code == 45 && deviceMsg.contains("write", ignoreCase = true) -> PrivilegedResult(
                false,
                "دسترسی نوشتن (Write) API روی این دستگاه غیرفعال است — این ربطی به رمز عبور ندارد. " +
                    "باید مستقیماً روی خود دستگاه فعالش کنید: وارد صفحه وب دستگاه (http://${ip}) بشوید و از " +
                    "مسیر Settings → Remote Ctrl → Miner API Switch گزینه Enable را بزنید (یا اگر آن گزینه در " +
                    "صفحه وب نبود، از نرم‌افزار رسمی WhatsMinerTool همین کار را انجام دهید). پاسخ کامل دستگاه: $resultJson"
            )
            code == 45 -> PrivilegedResult(
                false,
                if (decryptFailed)
                    "کد ۴۵ (permission denied) گزارش شد، ولی رمزگشایی پاسخ هم ناموفق بود — احتمالاً نسخه پروتکل دستگاه با این کد یکی نیست (کد خام: ${raw.take(150)})"
                else
                    "دسترسی رد شد (کد ۴۵ از دستگاه). ممکن است رمز عبور اشتباه باشد یا API Write روی دستگاه غیرفعال باشد. پاسخ کامل: $resultJson",
                wrongPassword = true
            )
            code == 131 || status.equals("S", ignoreCase = true) ->
                PrivilegedResult(true, "عملیات با موفقیت انجام شد")
            else -> PrivilegedResult(
                false,
                "خطای دستگاه — کد: $code، پیام: ${deviceMsg.ifBlank { "نامشخص" }}، پاسخ کامل: $resultJson"
            )
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
