package com.miner.whatsminermonitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miner.whatsminermonitor.model.MinerInfo
import com.miner.whatsminermonitor.network.NetworkScanner
import com.miner.whatsminermonitor.network.WhatsminerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MinerViewModel(application: Application) : AndroidViewModel(application) {

    private val _miners = MutableStateFlow<List<MinerInfo>>(emptyList())
    val miners: StateFlow<List<MinerInfo>> = _miners

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage

    private val _foundCount = MutableStateFlow(0)
    val foundCount: StateFlow<Int> = _foundCount

    private val _btcPriceToman = MutableStateFlow<Long?>(null)
    val btcPriceToman: StateFlow<Long?> = _btcPriceToman

    private val _btcPriceUsdt = MutableStateFlow<Double?>(null)
    val btcPriceUsdt: StateFlow<Double?> = _btcPriceUsdt

    private val _usdToToman = MutableStateFlow<Long?>(null)
    val usdToToman: StateFlow<Long?> = _usdToToman

    // منبع قیمت که الان استفاده می‌شه
    private val _priceSource = MutableStateFlow<String?>(null)
    val priceSource: StateFlow<String?> = _priceSource

    private val listMutex = Mutex()

    init {
        fetchPrices()
    }

    /**
     * دریافت قیمت‌ها با اولویت‌بندی:
     * 1. Nobitex (بهترین منبع برای قیمت ریال ایران - BTC/RLS و USDT/RLS)
     * 2. Wallex (صرافی ایرانی دیگر)
     * 3. Binance USDT قیمت + نرخ تخمینی بازار آزاد
     */
    private fun fetchPrices() {
        viewModelScope.launch(Dispatchers.IO) {
            var success = false

            // ===== روش ۱: Nobitex - دقیق‌ترین قیمت بازار ایران =====
            if (!success) {
                success = tryNobitex()
            }

            // ===== روش ۲: Wallex =====
            if (!success) {
                success = tryWallex()
            }

            // ===== روش ۳: Binance + تخمین نرخ دلار =====
            if (!success) {
                success = tryBinanceWithEstimate()
            }

            // ===== روش ۴: CoinGecko =====
            if (!success) {
                tryCoinGecko()
            }
        }
    }

    /**
     * Nobitex: قیمت BTC و USDT به ریال (RLS) - دقیق‌ترین نرخ بازار ایران
     * API عمومی و بدون نیاز به احراز هویت
     * endpoint: POST https://api.nobitex.ir/market/stats
     */
    private fun tryNobitex(): Boolean {
        return try {
            // دریافت قیمت USDT/RLS برای محاسبه نرخ دلار
            val usdtConn = URL("https://api.nobitex.ir/market/stats").openConnection() as HttpURLConnection
            usdtConn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
            }
            usdtConn.outputStream.write("""{"srcCurrency":"usdt","dstCurrency":"rls"}""".toByteArray())
            val usdtResponse = usdtConn.inputStream.bufferedReader().readText()
            val usdtJson = JSONObject(usdtResponse)

            // قیمت USDT به ریال (تقسیم بر ۱۰ برای تومان)
            val usdtStats = usdtJson.optJSONObject("stats")?.optJSONObject("usdt-rls")
            val usdtPriceRls = usdtStats?.optString("bestSell")?.toDoubleOrNull()
                ?: usdtStats?.optString("latest")?.toDoubleOrNull()
            val usdtPriceToman = usdtPriceRls?.div(10.0)  // ریال به تومان

            // دریافت قیمت BTC/RLS
            val btcConn = URL("https://api.nobitex.ir/market/stats").openConnection() as HttpURLConnection
            btcConn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
            }
            btcConn.outputStream.write("""{"srcCurrency":"btc","dstCurrency":"rls"}""".toByteArray())
            val btcResponse = btcConn.inputStream.bufferedReader().readText()
            val btcJson = JSONObject(btcResponse)

            val btcStats = btcJson.optJSONObject("stats")?.optJSONObject("btc-rls")
            val btcPriceRls = btcStats?.optString("bestSell")?.toDoubleOrNull()
                ?: btcStats?.optString("latest")?.toDoubleOrNull()
            val btcPriceToman = btcPriceRls?.div(10.0)

            if (usdtPriceToman != null && usdtPriceToman > 0 && btcPriceToman != null && btcPriceToman > 0) {
                _usdToToman.value = usdtPriceToman.toLong()
                _btcPriceToman.value = btcPriceToman.toLong()
                // قیمت BTC به USDT = قیمت BTC به تومان / قیمت USDT به تومان
                _btcPriceUsdt.value = btcPriceToman / usdtPriceToman
                _priceSource.value = "نوبیتکس"
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Wallex: صرافی معتبر ایرانی
     * endpoint: GET https://api.wallex.ir/v1/markets
     */
    private fun tryWallex(): Boolean {
        return try {
            val conn = URL("https://api.wallex.ir/v1/markets").openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                connectTimeout = 5000
                readTimeout = 5000
            }
            val response = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val result = json.optJSONObject("result")?.optJSONObject("symbols")

            // BTCTMN = BTC به تومان
            val btcTmn = result?.optJSONObject("BTCTMN")
            val btcPrice = btcTmn?.optString("stats")?.let { JSONObject(it) }?.optDouble("lastPrice")
                ?: btcTmn?.optDouble("lastPrice")

            // USDTTMN = USDT به تومان (نرخ دلار)
            val usdtTmn = result?.optJSONObject("USDTTMN")
            val usdtPrice = usdtTmn?.optString("stats")?.let { JSONObject(it) }?.optDouble("lastPrice")
                ?: usdtTmn?.optDouble("lastPrice")

            if (btcPrice != null && btcPrice > 0 && usdtPrice != null && usdtPrice > 0) {
                _btcPriceToman.value = btcPrice.toLong()
                _usdToToman.value = usdtPrice.toLong()
                _btcPriceUsdt.value = btcPrice / usdtPrice
                _priceSource.value = "والکس"
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Binance: قیمت BTC/USDT جهانی + نرخ تخمینی دلار آزاد
     * برای کاربرانی که به API ایرانی دسترسی ندارند
     */
    private fun tryBinanceWithEstimate(): Boolean {
        return try {
            val conn = URL("https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT").openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            val response = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val btcUsdt = json.optDouble("price")

            if (btcUsdt > 0) {
                _btcPriceUsdt.value = btcUsdt
                // نرخ دلار آزاد ایران: در صورت عدم دسترسی به منابع ایرانی
                // از یک مقدار پیش‌فرض استفاده می‌کنیم و به کاربر نشان می‌دهیم
                val estimatedRate = 90_000L  // تومان - نرخ تقریبی
                _usdToToman.value = estimatedRate
                _btcPriceToman.value = (btcUsdt * estimatedRate).toLong()
                _priceSource.value = "بایننس (نرخ دلار تخمینی)"
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * CoinGecko: آخرین fallback
     */
    private fun tryCoinGecko(): Boolean {
        return try {
            val conn = URL("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,tether&vs_currencies=usd").openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            val response = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val btcUsdt = json.optJSONObject("bitcoin")?.optDouble("usd")

            if (btcUsdt != null && btcUsdt > 0) {
                _btcPriceUsdt.value = btcUsdt
                val estimatedRate = 90_000L
                _usdToToman.value = estimatedRate
                _btcPriceToman.value = (btcUsdt * estimatedRate).toLong()
                _priceSource.value = "CoinGecko (نرخ دلار تخمینی)"
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    fun refreshBitcoinPrice() {
        _priceSource.value = null
        _btcPriceToman.value = null
        _btcPriceUsdt.value = null
        _usdToToman.value = null
        fetchPrices()
    }

    fun startScan() {
        if (_isScanning.value) return
        _isScanning.value = true
        _miners.value = emptyList()
        _foundCount.value = 0
        _statusMessage.value = "در حال تشخیص شبکه وای‌فای..."

        viewModelScope.launch(Dispatchers.IO) {
            val subnetInfo = NetworkScanner.getLocalIPv4AndPrefix(getApplication())
            if (subnetInfo == null) {
                _statusMessage.value = "به شبکه وای‌فای متصل نیستید یا دسترسی به آن ممکن نیست"
                _isScanning.value = false
                return@launch
            }

            val (address, prefix) = subnetInfo
            val hosts = NetworkScanner.buildHostListInSubnet(address, prefix)
            _statusMessage.value = "در حال اسکن ${hosts.size} آدرس در شبکه محلی..."

            NetworkScanner.scanForMiners(hosts) { ip ->
                val info = WhatsminerClient.queryMiner(ip)
                listMutex.withLock {
                    _miners.value = (_miners.value + info).sortedBy { it.ip }
                    _foundCount.value = _miners.value.size
                }
            }

            _statusMessage.value = if (_miners.value.isEmpty())
                "هیچ ماینری در شبکه پیدا نشد"
            else
                "اسکن کامل شد — ${_miners.value.size} دستگاه پیدا شد"
            _isScanning.value = false
        }
    }

    fun refreshMiner(ip: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val info = WhatsminerClient.queryMiner(ip)
            listMutex.withLock {
                _miners.value = _miners.value.map { if (it.ip == ip) info else it }
            }
        }
    }
}
