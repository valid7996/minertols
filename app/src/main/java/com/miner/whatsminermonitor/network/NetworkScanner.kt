package com.miner.whatsminermonitor.network

import android.content.Context
import android.net.ConnectivityManager
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
 */
object NetworkScanner {

    private const val MAX_CONCURRENT_SCANS = 48

    /**
     * آدرس IPv4 فعلی دستگاه روی شبکه‌ی متصل و طول prefix آن (مثلا 24 برای 255.255.255.0) را برمی‌گرداند.
     */
    fun getLocalIPv4AndPrefix(context: Context): Pair<Inet4Address, Int>? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = cm.activeNetwork ?: return null
        val linkProperties = cm.getLinkProperties(network) ?: return null

        for (linkAddress in linkProperties.linkAddresses) {
            val address = linkAddress.address
            if (address is Inet4Address && !address.isLoopbackAddress) {
                return address to linkAddress.prefixLength
            }
        }
        return null
    }

    /**
     * لیست تمام IPهای قابل میزبانی در همان ساب‌نت دستگاه را می‌سازد.
     * برای جلوگیری از اسکن‌های خیلی بزرگ (شبکه‌های /16 و بزرگ‌تر)، حداقل رنج /24 در نظر گرفته می‌شود.
     */
    fun buildHostListInSubnet(address: Inet4Address, prefixLength: Int): List<String> {
        val effectivePrefix = maxOf(prefixLength, 24) // حداکثر بزرگی رنج: یک /24 (۲۵۴ هاست)
        val ipInt = ByteBuffer.wrap(address.address).int
        val mask = if (effectivePrefix == 0) 0 else -1 shl (32 - effectivePrefix)
        val network = ipInt and mask
        val broadcast = network or mask.inv()

        val hosts = mutableListOf<String>()
        var current = network + 1
        while (current < broadcast) {
            hosts.add(intToIp(current))
            current++
        }
        return hosts
    }

    private fun intToIp(value: Int): String {
        return "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"
    }

    /**
     * روی تمام هاست‌های داده شده به صورت موازی (با محدودیت همزمانی) چک می‌کند پورت API باز است یا نه.
     * برای هر IP که ماینر پیدا شد بلافاصله callback صدا زده می‌شود تا UI به صورت زنده آپدیت شود.
     */
    suspend fun scanForMiners(
        hosts: List<String>,
        onMinerFound: suspend (String) -> Unit
    ) = coroutineScope {
        val semaphore = Semaphore(MAX_CONCURRENT_SCANS)
        val jobs = hosts.map { ip ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (WhatsminerClient.isPortOpen(ip)) {
                        onMinerFound(ip)
                    }
                }
            }
        }
        jobs.awaitAll()
    }
}
