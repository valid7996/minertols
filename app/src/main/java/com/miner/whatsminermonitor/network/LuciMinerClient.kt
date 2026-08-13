package com.miner.whatsminermonitor.network

import com.miner.whatsminermonitor.model.PoolEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * کلاینت کنترل ماینر از طریق پنل وب LuCI (که دستگاه‌های Whatsminer روی HTTPS ارائه می‌دهند).
 *
 * پروتکل خام TCP:4028 (که در WhatsminerClient.kt پیاده شده) برای خواندن اطلاعات عالی کار می‌کند،
 * اما در عمل دستورهای نوشتنی آن (reboot / update_pools) روی خیلی از فریمورها به‌درستی جواب
 * نمی‌دهند یا نیازمند حالت رمزنگاری‌شده‌ای هستند که بین مدل‌ها فرق می‌کند. راه واقعی و قابل‌اطمینانی
 * که دستگاه از آن پشتیبانی می‌کند، همان پنل مدیریت وبی است که با مرورگر هم می‌شود باز کرد:
 * صفحهٔ لاگین LuCI (چارچوب وب OpenWrt که فریمور Whatsminer روی آن ساخته شده).
 *
 * جریان کار (دقیقا مطابق چیزی که پنل وب انجام می‌دهد):
 *  1) GET  /cgi-bin/luci                                   -> کوکی اولیه
 *  2) POST /cgi-bin/luci  (luci_username + luci_password)   -> ورود؛ کوکی نشست + احتمالا ریدایرکت
 *  3) GET  /cgi-bin/luci/admin/network/btminer              -> صفحهٔ تنظیمات پول‌ها (شامل توکن CSRF)
 *  4) POST همان صفحه با فیلدهای جدید pool1..3 url/user/pw    -> اعمال تغییرات
 *  5) (اختیاری) GET /cgi-bin/luci/admin/status/btminerstatus/restart -> ری‌استارت دستگاه
 *
 * پورت‌های HTTPS رایج روی دستگاه‌های Whatsminer 443 و 4433 هستند؛ هر دو امتحان می‌شوند.
 * نام کاربری/رمز پیش‌فرض کارخانه‌ای هر دو "admin" هستند.
 */
object LuciMinerClient {

    data class LuciResult(val success: Boolean, val message: String, val wrongPassword: Boolean = false)

    private data class HttpResult(val code: Int, val body: String, val cookies: List<String>, val location: String?)

    private data class Session(val baseUrl: String, val cookieHeader: String, val html: String)

    private val CANDIDATE_PORTS = listOf(443, 4433)

    // فقط برای اتصال‌های محلی به پنل ماینر استفاده می‌شود (گواهی self-signed رایج روی این دستگاه‌هاست)؛
    // per-connection تنظیم می‌شود نه به‌صورت پیش‌فرض کل برنامه، تا روی اتصال‌های HTTPS دیگر برنامه
    // (مثلا برای گرفتن قیمت دلار) اثر نگذارد
    private val permissiveSocketFactory: SSLSocketFactory by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(trustAll), SecureRandom())
        ctx.socketFactory
    }

    private val permissiveHostnameVerifier = HostnameVerifier { _, _ -> true }

    private fun request(
        url: String,
        method: String,
        cookieHeader: String,
        formBody: Map<String, String>? = null,
        timeoutMs: Int = 8000,
        followRedirects: Boolean = false
    ): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = permissiveSocketFactory
            conn.hostnameVerifier = permissiveHostnameVerifier
        }
        conn.requestMethod = method
        conn.connectTimeout = 4000
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = followRedirects
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (cookieHeader.isNotBlank()) conn.setRequestProperty("Cookie", cookieHeader)

        if (formBody != null) {
            val encoded = formBody.entries.joinToString("&") { (k, v) ->
                java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
            }
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val bytes = encoded.toByteArray(Charsets.UTF_8)
            conn.setRequestProperty("Content-Length", bytes.size.toString())
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(encoded) }
        }

        return try {
            val code = conn.responseCode
            val body = readBody(conn)
            val cookies = conn.headerFields["Set-Cookie"] ?: emptyList()
            val location = conn.getHeaderField("Location")
            HttpResult(code, body, cookies, location)
        } finally {
            conn.disconnect()
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = try {
            conn.inputStream
        } catch (e: Exception) {
            conn.errorStream
        } ?: return ""
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText().take(1_000_000) }
    }

    // کوکی‌های جدید را با کوکی‌های قبلی ادغام می‌کند (کوکی هم‌نام جایگزین می‌شود، مثل مرورگر واقعی)
    private fun mergeCookies(existing: String, newSetCookies: List<String>): String {
        if (newSetCookies.isEmpty()) return existing
        val jar = LinkedHashMap<String, String>()
        existing.split(";").map { it.trim() }.filter { it.contains("=") }.forEach {
            val idx = it.indexOf('=')
            jar[it.substring(0, idx)] = it.substring(idx + 1)
        }
        newSetCookies.forEach { setCookie ->
            val firstPart = setCookie.split(";").firstOrNull()?.trim().orEmpty()
            val idx = firstPart.indexOf('=')
            if (idx > 0) jar[firstPart.substring(0, idx)] = firstPart.substring(idx + 1)
        }
        return jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun resolveRedirect(baseUrl: String, location: String): String {
        return when {
            location.startsWith("http://", true) || location.startsWith("https://", true) -> location
            location.startsWith("/") -> {
                val u = URL(baseUrl)
                "${u.protocol}://${u.host}:${if (u.port > 0) u.port else 443}$location"
            }
            else -> baseUrl.trimEnd('/') + "/" + location
        }
    }

    private fun looksLikeLoginPage(html: String): Boolean =
        html.contains("luci_username", true) || html.contains("luci_password", true)

    private sealed class LoginOutcome {
        data class Success(val session: Session) : LoginOutcome()
        object WrongPassword : LoginOutcome()
        object Unreachable : LoginOutcome()
    }

    private fun login(ip: String, port: Int, username: String, password: String): LoginOutcome {
        val base = "https://$ip:$port/cgi-bin/luci"
        val step1 = runCatching { request(base, "GET", "", timeoutMs = 6000) }.getOrNull()
            ?: return LoginOutcome.Unreachable
        var cookies = mergeCookies("", step1.cookies)

        val step2 = runCatching {
            request(base, "POST", cookies, mapOf("luci_username" to username, "luci_password" to password), timeoutMs = 8000)
        }.getOrNull() ?: return LoginOutcome.Unreachable
        cookies = mergeCookies(cookies, step2.cookies)

        val location = step2.location
        if (!location.isNullOrBlank()) {
            val redirectUrl = resolveRedirect(base, location)
            val step2b = runCatching { request(redirectUrl, "GET", cookies, timeoutMs = 8000) }.getOrNull()
            if (step2b != null) cookies = mergeCookies(cookies, step2b.cookies)
        }

        val panelUrl = "$base/admin/network/btminer"
        val step3 = runCatching { request(panelUrl, "GET", cookies, timeoutMs = 8000, followRedirects = true) }.getOrNull()
            ?: return LoginOutcome.Unreachable
        // به این مرحله رسیدیم یعنی دستگاه در دسترس است و به HTTPS جواب می‌دهد؛ حالا فقط باید
        // ببینیم صفحهٔ واقعی تنظیمات را گرفتیم یا هنوز صفحهٔ ورود را نشان می‌دهد (یعنی رمز اشتباه است)
        cookies = mergeCookies(cookies, step3.cookies)

        return if (looksLikeLoginPage(step3.body)) {
            LoginOutcome.WrongPassword
        } else {
            LoginOutcome.Success(Session(base, cookies, step3.body))
        }
    }

    private fun extractToken(html: String): String? {
        Regex("""name=["']token["'][^>]*value=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.let { return it.groupValues[1] }
        Regex("""value=["']([^"']+)["'][^>]*name=["']token["']""", RegexOption.IGNORE_CASE)
            .find(html)?.let { return it.groupValues[1] }
        Regex("""(?:token|csrf_token|csrfToken)\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .find(html)?.let { return it.groupValues[1] }
        Regex("""[?&;]stok=([^/'"&?#]+)""", RegexOption.IGNORE_CASE)
            .find(html)?.let { return it.groupValues[1] }
        return null
    }

    private fun extractInputValue(html: String, fieldName: String): String {
        val quoted = Regex.escape(fieldName)
        Regex("""<input\b[^>]*\bname=["']$quoted["'][^>]*\bvalue=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.let { return unescapeHtml(it.groupValues[1]) }
        Regex("""<input\b[^>]*\bvalue=["']([^"']*)["'][^>]*\bname=["']$quoted["']""", RegexOption.IGNORE_CASE)
            .find(html)?.let { return unescapeHtml(it.groupValues[1]) }
        return ""
    }

    private fun extractSelectedCoinType(html: String): String {
        val selectMatch = Regex(
            """<select\b[^>]*\bname=["']cbid\.pools\.default\.coin_type["'][^>]*>(.*?)</select>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html) ?: return "BTC"
        val optionsHtml = selectMatch.groupValues[1]
        val selected = Regex("""<option\b[^>]*\bselected\b[^>]*>""", RegexOption.IGNORE_CASE).find(optionsHtml)
            ?: return "BTC"
        val value = Regex("""\bvalue=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(selected.value)
        val coin = value?.groupValues?.get(1)?.let { unescapeHtml(it) }
        return if (coin.isNullOrBlank()) "BTC" else coin
    }

    private fun unescapeHtml(s: String): String = s
        .replace("&quot;", "\"").replace("&#34;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&amp;", "&")

    /**
     * با هر دو پورت رایج (۴۴۳ و ۴۴۳۳) وارد پنل می‌شود؛ اولین موردی که موفق شد استفاده می‌شود.
     * اگر هیچ پورتی اصلاً پاسخ نداد Unreachable برمی‌گردد (که نباید باعث درخواست رمز جدید از
     * کاربر شود)؛ فقط وقتی واقعاً صفحهٔ ورود را دیدیم WrongPassword برمی‌گردد.
     */
    private fun loginAnyPort(ip: String, username: String, password: String): LoginOutcome {
        var sawWrongPassword = false
        for (port in CANDIDATE_PORTS) {
            when (val outcome = login(ip, port, username, password)) {
                is LoginOutcome.Success -> return outcome
                LoginOutcome.WrongPassword -> sawWrongPassword = true
                LoginOutcome.Unreachable -> {}
            }
        }
        return if (sawWrongPassword) LoginOutcome.WrongPassword else LoginOutcome.Unreachable
    }

    /**
     * تشخیص می‌دهد که آیا اصلاً پنل وب (LuCI) روی این IP در دسترس هست یا نه (صرف‌نظر از رمز) -
     * برای رد کردن سریع دستگاه‌هایی که اصلا این پنل را ندارند، بدون نیاز به امتحان چند پسورد
     */
    suspend fun isPanelReachable(ip: String): Boolean = withContext(Dispatchers.IO) {
        CANDIDATE_PORTS.any { port ->
            runCatching {
                val r = request("https://$ip:$port/cgi-bin/luci", "GET", "", timeoutMs = 3000)
                r.code in 200..499
            }.getOrDefault(false)
        }
    }

    suspend fun updatePools(ip: String, username: String, password: String, pools: List<PoolEntry>): LuciResult =
        withContext(Dispatchers.IO) {
            if (pools.isEmpty()) return@withContext LuciResult(false, "هیچ پولی برای تنظیم مشخص نشده است")
            val padded = (0 until 3).map { idx -> pools.getOrNull(idx) ?: pools.last() }

            val loginOutcome = loginAnyPort(ip, username, password)
            val session = when (loginOutcome) {
                is LoginOutcome.Success -> loginOutcome.session
                LoginOutcome.WrongPassword -> return@withContext LuciResult(false, "رمز عبور اشتباه است", wrongPassword = true)
                LoginOutcome.Unreachable -> return@withContext LuciResult(false, "پنل مدیریت دستگاه در دسترس نبود")
            }

            val token = extractToken(session.html)
            val coinType = extractSelectedCoinType(session.html)
            val panelUrl = "${session.baseUrl}/admin/network/btminer"

            val form = LinkedHashMap<String, String>()
            if (!token.isNullOrBlank()) form["token"] = token
            form["cbi.submit"] = "1"
            form["cbid.pools.default.coin_type"] = coinType
            padded.forEachIndexed { idx, pool ->
                val n = idx + 1
                form["cbid.pools.default.pool${n}url"] = pool.url
                form["cbid.pools.default.pool${n}user"] = pool.worker
                form["cbid.pools.default.pool${n}pw"] = pool.pass
            }
            form["cbi.apply"] = "Save & Apply"

            val applyResult = runCatching {
                request(panelUrl, "POST", session.cookieHeader, form, timeoutMs = 10000, followRedirects = true)
            }.getOrNull() ?: return@withContext LuciResult(false, "ارسال تنظیمات جدید پول به دستگاه ناموفق بود")

            if (applyResult.code !in 200..399) {
                return@withContext LuciResult(false, "دستگاه درخواست را نپذیرفت (کد HTTP ${applyResult.code})")
            }

            // بعد از ذخیره، دستگاه معمولا برای اعمال کامل تنظیمات پول نیاز به ری‌استارت دارد
            kotlinx.coroutines.delay(2000)
            runCatching {
                request(
                    "${session.baseUrl}/admin/status/btminerstatus/restart",
                    "GET", session.cookieHeader, timeoutMs = 15000, followRedirects = false
                )
            }

            LuciResult(true, "پول‌های ماینینگ با موفقیت به‌روزرسانی شد و دستگاه در حال ری‌استارت است")
        }

    suspend fun reboot(ip: String, username: String, password: String): LuciResult = withContext(Dispatchers.IO) {
        val loginOutcome = loginAnyPort(ip, username, password)
        val session = when (loginOutcome) {
            is LoginOutcome.Success -> loginOutcome.session
            LoginOutcome.WrongPassword -> return@withContext LuciResult(false, "رمز عبور اشتباه است", wrongPassword = true)
            LoginOutcome.Unreachable -> return@withContext LuciResult(false, "پنل مدیریت دستگاه در دسترس نبود")
        }

        val result = runCatching {
            request(
                "${session.baseUrl}/admin/status/btminerstatus/restart",
                "GET", session.cookieHeader, timeoutMs = 15000, followRedirects = false
            )
        }.getOrNull() ?: return@withContext LuciResult(false, "درخواست ری‌استارت به دستگاه ارسال نشد")

        if (result.code !in 200..399) {
            return@withContext LuciResult(false, "دستگاه درخواست ری‌استارت را نپذیرفت (کد HTTP ${result.code})")
        }
        LuciResult(true, "دستور ری‌استارت با موفقیت ارسال شد")
    }
}
