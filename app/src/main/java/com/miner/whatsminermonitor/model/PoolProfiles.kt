package com.miner.whatsminermonitor.model

/**
 * یک اسلات پول برای ارسال به دستور update_pools دستگاه
 */
data class PoolEntry(
    val url: String,
    val worker: String,
    val pass: String = "x"
)

/**
 * یک پروفایل پول از پیش تعریف‌شده با آدرس اصلی و آدرس‌های پشتیبان
 */
data class PoolProfile(
    val id: String,
    val displayName: String,
    val addresses: List<String> // اولین آدرس = اصلی، بقیه = پشتیبان
)

object PoolProfiles {
    val PECPOOL = PoolProfile(
        id = "pecpool",
        displayName = "PecPool",
        addresses = listOf(
            "stratum+tcp://btc-ir.pecpool.com:8443",
            "stratum+tcp://btc-ir.pecpool.com:443",
            "stratum+tcp://btc-ir.pecpool.com:25"
        )
    )

    val VIABTC = PoolProfile(
        id = "viabtc",
        displayName = "ViaBTC",
        addresses = listOf(
            "stratum+tcp://btc.viabtc.io:3333",
            "stratum+tcp://btc.viabtc.io:443"
        )
    )

    val F2POOL = PoolProfile(
        id = "f2pool",
        displayName = "F2Pool",
        addresses = listOf(
            "stratum+tcp://btc.f2pool.com:3333",
            "stratum+tcp://btc.f2pool.com:25"
        )
    )

    val all = listOf(PECPOOL, VIABTC, F2POOL)
}
