package com.miner.whatsminermonitor.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * ابزار پیدا کردن رنج IP شبکه محلی (وای‌فای) دستگاه و اسکن آن رنج برای پیدا کردن
 * ماینرهایی که پورت API آن‌ها (4028) باز است.
 *
 * رفع باگ «پیدا نشدن / خیلی دیر پیدا شدن ماینرها و نیاز به خاموش/روشن کردن اسکنر»:
 * - همزمانی اسکن با Semaphore روی ۲۵ پروبِ همزمان محدود شد (بازهٔ مجاز ۲۰ تا ۳۰) تا
 *   صف سوکت سیستم‌عامل و جدول NAT روتر اشباع نشود؛ اشباع صف باعث drop بسته‌های SYN و
 *   جا افتادن ماینرها می‌شد.
 * - هر پروب با Socket().use { ... } باز و «قطعاً» بسته می‌شود؛ حتی وقتی connect خطا
 *   می‌دهد یا coroutine لغو می‌شود (توقف اسکن) سوکت بسته می‌شود و Socket Leak رخ
 *   نمی‌دهد؛ لکِ سوکت باعث می‌شد اسکن‌های بعدی تا ری‌استارت برنامه از کار بیفتند.
 * - تایم‌اوت اتصال/خواندن هر پروب ۹۰۰ms (بازهٔ مجاز ۸۵۰ تا ۱۰۰۰) برای پورت 4028؛
 *   مقدار قبلی (۸۰۰ms و کمتر) فریمورهای زیر بار را به‌اشتباه «غایب» گزارش می‌کرد.
 * - هر دو پورت API (4028 قدیمی و 4433 جدید M5x/M6x) بررسی می‌شوند؛ اول 4028 تا
 *   اکثر مدل‌ها سریع‌تر پیدا شوند.
 * - به محض باز بودن پورت هر IP، callback صدا زده می‌شود و نتیجه فوراً به لیست UI
 *   می‌رود؛ هیچ انتظاری برای اتمام کل بازهٔ IPها کشیده نمی‌شود.
 */
object NetworkScanner {

    // بازهٔ مجاز ۲۰ تا ۳۰ اتصال همزمان؛ ۲۵ نقطهٔ تعادل خوبی بین سرعت اسکن و
    // اشباع نشدن صف سوکت سیستم‌عامل / جدول NAT روترهای خانگی است
    private const val MAX_CONCURRENT_PROBES = 25

    // تایم‌اوت اتصال و خواندن برای پروب پورت 4028 (بازهٔ مجاز ۸۵۰ تا ۱۰۰۰ میلی‌ثانیه)
    private const val SCAN_TIMEOUT_MS = 900

    private const val TAG = "NetworkScanner"

    /**
     * آدرس IPv4 فعلی دستگاه روی شبکه‌ی متصل و طول prefix آن (مثلا 24 برای 255.255.255.0) را برمی‌گرداند.
     * اولویت با شبکه WiFi است؛ اگر activeNetwork WiFi نبود، تمام شبکه‌ها بررسی می‌شوند.
     */
    fun getLocalIPv4AndPrefix(context: Context): Pair<Inet4Address, Int>? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        // 1) Try activeNetwork first (fast path)
        cm.activeNetwork?.let { active ->
            getIPv4FromNetwork(cm, active)?.let { return it }
        }

        // 2) Fallback: iterate all networks and prefer WiFi transport
        val allNetworks = try { cm.allNetworks } catch (e: Exception) { emptyArray() }
        var fallback: Pair<Inet4Address, Int>? = null
        for (network in allNetworks) {
            val caps = try { cm.getNetworkCapabilities(network) } catch (e: Exception) { null }
            val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val result = getIPv4FromNetwork(cm, network) ?: continue
            if (isWifi) {
                Log.d(TAG, "found WiFi network ip=${result.first.hostAddress}/${result.second}")
                return result
            }
            if (fallback == null) fallback = result
        }
        if (fallback != null) {
            Log.d(TAG, "using fallback network ip=${fallback.first.hostAddress}/${fallback.second}")
        } else {
            Log.w(TAG, "no IPv4 address found on any network")
        }
        return fallback
    }

    private fun getIPv4FromNetwork(cm: ConnectivityManager, network: android.net.Network): Pair<Inet4Address, Int>? {
        val linkProperties = try { cm.getLinkProperties(network) } catch (e: Exception) { null } ?: return null
        for (linkAddress in linkProperties.linkAddresses) {
            val address = linkAddress.address
            // Filter: must be IPv4, not loopback, not link-local (169.254.x.x), and prefix sane
            if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                val prefix = linkAddress.prefixLength
                if (prefix in 8..30) {
                    return address to prefix
                }
                Log.d(TAG, "skipping address ${address.hostAddress}/$prefix (prefix out of range)")
            }
        }
        return null
    }

    /**
     * لیست تمام IPهای قابل میزبانی در همان ساب‌نت دستگاه را می‌سازد.
     * برای جلوگیری از اسکن‌های خیلی بزرگ (شبکه‌های /16 و بزرگ‌تر)، حداقل رنج /24 در نظر گرفته می‌شود.
     * اگر شبکه از /24 کوچک‌تر باشد (مثلا /25 یا /26)، همان رنج کوچک‌تر حفظ می‌شود تا ترافیک بیهوده تولید نشود.
     */
    fun buildHostListInSubnet(address: Inet4Address, prefixLength: Int): List<String> {
        // Keep small subnets as-is (e.g., /25 -> 126 hosts), cap large subnets to /24 (254 hosts)
        // maxOf(28,24)=28 => keeps /28 (14 hosts) - correct. maxOf(16,24)=24 => caps /16 to /24 - correct.
        val cappedPrefix = maxOf(prefixLength, 24)
        val ipInt = ByteBuffer.wrap(address.address).int
        val mask = if (cappedPrefix == 0) 0 else -1 shl (32 - cappedPrefix)
        val network = ipInt and mask
        val broadcast = network or mask.inv()

        val hosts = mutableListOf<String>()
        var current = network + 1
        while (current < broadcast) {
            hosts.add(intToIp(current))
            // Safety cap: never generate more than 512 hosts (protect against /16 miscalc)
            if (hosts.size >= 512) {
                Log.w(TAG, "host list capped at 512 for ${address.hostAddress}/$prefixLength (capped to /$cappedPrefix)")
                break
            }
            current++
        }
        Log.d(TAG, "buildHostListInSubnet ${address.hostAddress}/$prefixLength -> capped /$cappedPrefix -> ${hosts.size} hosts")
        return hosts
    }

    private fun intToIp(value: Int): String {
        return "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"
    }

    /**
     * پروب TCP باز بودن پورت با باز/بستن قطعی سوکت.
     * Socket().use حتی در صورت خطای connect یا لغو coroutine سوکت را می‌بندد (بدون Socket Leak).
     * soTimeout هم همان ۹۰۰ms تنظیم می‌شود؛ پروب فقط connect می‌کند ولی برای اطمینان از
     * عدم قفل شدن، تایم‌اوت خواندن هم ست می‌شود.
     */
    private fun probePort(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.soTimeout = SCAN_TIMEOUT_MS
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(ip, port), SCAN_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            Log.d(TAG, "probe failed ip=$ip port=$port err=${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * روی تمام هاست‌های داده شده به صورت موازی (با محدودیت دقیق همزمانی) چک می‌کند
     * پورت API باز است یا نه. برای هر IP که ماینر پیدا شد «بلافاصله» callback صدا زده
     * می‌شود تا UI به صورت زنده آپدیت شود.
     *
     * - سقف همزمانی روی «تعداد پروب‌های همزمان» اعمال می‌شود (Semaphore ۲۵)، پس هر لحظه
     *   حداکثر ۲۵ سوکت باز داریم؛ نه بیشتر. این همان کاری است که از اشباع صف سوکت
     *   سیستم‌عامل و جا افتادن ماینرها جلوگیری می‌کند.
     * - هر IP حداکثر یک‌بار گزارش می‌شود حتی اگر هر دو پورت (4028 و 4433) باز باشند.
     * - stopScan و لغو اسکن به‌صورت طبیعی همهٔ پروب‌های در انتظار را لغو می‌کند.
     */
    suspend fun scanForMiners(
        hosts: List<String>,
        onMinerFound: suspend (String) -> Unit
    ) = coroutineScope {
        val semaphore = Semaphore(MAX_CONCURRENT_PROBES)
        val reported = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        val startTime = System.currentTimeMillis()

        // اول همهٔ پروب‌های پورت 4028 (پورت اصلی اکثر مدل‌ها) و بعد پورت 4433 (API v3)
        // تا ماینرهای رایج در نخستین موج اسکن پیدا شوند
        val probes = hosts.map { it to WhatsminerClient.API_PORT } +
                hosts.map { it to WhatsminerClient.API_PORT_V3 }

        probes.map { (ip, port) ->
            async(Dispatchers.IO) {
                // سهمیهٔ همزمانی فقط برای «پروب پورت» رزرو می‌شود (حداکثر ۲۵ سوکت همزمان)؛
                // خواندن کامل اطلاعات دستگاه (queryMiner داخل callback) که چند ثانیه طول
                // می‌کشد «خارج از سهمیه» اجرا می‌شود تا اسکن بقیه IPها را بند نیاورد
                val opened = semaphore.withPermit { !reported.contains(ip) && probePort(ip, port) }
                // به‌محض اولین پاسخ مثبت، نتیجه به UI اعلام می‌شود؛ اگر بعداً
                // پورت دوم هم باز شد، به‌خاطر reported دوباره گزارش نمی‌شود
                if (opened && reported.add(ip)) {
                    Log.d(TAG, "miner port open detected ip=$ip port=$port")
                    onMinerFound(ip)
                }
            }
        }.awaitAll()

        Log.d(TAG, "scanForMiners finished ${hosts.size} hosts in ${System.currentTimeMillis() - startTime}ms, found=${reported.size}")
    }
}
