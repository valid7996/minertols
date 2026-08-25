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
import java.nio.ByteBuffer

/**
 * ابزار پیدا کردن رنج IP شبکه محلی (وای‌فای) دستگاه و اسکن آن رنج برای پیدا کردن
 * ماینرهایی که پورت API آن‌ها (4028) باز است.
 *
 * بهبودهای سازگاری:
 * - جستجوی WiFi در بین تمام شبکه‌ها (نه فقط activeNetwork) برای زمانی که گوشی هم‌زمان
 *   به داده موبایل و WiFi متصل است یا activeNetwork موقتا cellular است
 * - تایم‌اوت پورت‌اسکن افزایش یافته (400ms -> 800ms) چون بعضی مدل‌ها/فریمورها زیر بار
 *   با تاخیر بیشتری به SYN پاسخ می‌دهند و با 400ms به‌اشتباه «پورت بسته» تشخیص داده می‌شدند
 * - قابلیت تشخیص ساب‌نت‌های غیر /24 با محدودسازی هوشمند (از /24 بزرگ‌تر نشود اما کوچک‌تر حفظ شود)
 * - لاگ‌های تشخیصی برای عیب‌یابی دستگاه‌های پیدانشده
 */
object NetworkScanner {

    private const val MAX_CONCURRENT_SCANS = 32
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
        // For very small networks like /28, use original prefix (28) not 24, to avoid scanning outside subnet
        // Wait: maxOf(28,24)=28 => keeps /28 (14 hosts) - correct. maxOf(16,24)=24 => caps /16 to /24 - correct.
        // So use cappedPrefix.
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
     * روی تمام هاست‌های داده شده به صورت موازی (با محدودیت همزمانی) چک می‌کند پورت API باز است یا نه.
     * برای هر IP که ماینر پیدا شد بلافاصله callback صدا زده می‌شود تا UI به صورت زنده آپدیت شود.
     * بهبود: همزمانی کمتر (32 به‌جای 48) تا روترهای خانگی/سوییچ‌های کوچک زیر بار نماند و
     * دستگاه‌های کندتر هم فرصت پاسخ داشته باشند.
     */
    suspend fun scanForMiners(
        hosts: List<String>,
        onMinerFound: suspend (String) -> Unit
    ) = coroutineScope {
        val semaphore = Semaphore(MAX_CONCURRENT_SCANS)
        val jobs = hosts.map { ip ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    // Use more generous timeout for congested networks
                    if (WhatsminerClient.isPortOpen(ip, timeoutMs = 800)) {
                        Log.d(TAG, "miner port open detected ip=$ip")
                        onMinerFound(ip)
                    }
                }
            }
        }
        jobs.awaitAll()
    }
}
