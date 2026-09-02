package com.miner.whatsminermonitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miner.whatsminermonitor.model.MinerInfo
import com.miner.whatsminermonitor.network.NetworkScanner
import com.miner.whatsminermonitor.network.WhatsminerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
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

    // هشریت کل شبکه بیت‌کوین (اگزاهش بر ثانیه) - برای محاسبهٔ درآمد تخمینی؛ به‌صورت زنده گرفته می‌شود
    private val _networkHashrateEh = MutableStateFlow<Double?>(null)
    val networkHashrateEh: StateFlow<Double?> = _networkHashrateEh

    // آخرین باری که قیمت‌ها با موفقیت به‌روزرسانی شدند
    private val _lastPriceUpdate = MutableStateFlow<Long?>(null)
    val lastPriceUpdate: StateFlow<Long?> = _lastPriceUpdate

    private val listMutex = Mutex()

    // نگه‌داری Job اسکن پیوسته تا بشه با دکمه توقف، لغوش کرد
    private var scanJob: Job? = null

    companion object {
        // فاصلهٔ رفرش خودکار قیمت‌ها و هشریت شبکه، برای «زنده» بودن مانیتورینگ - همان فاصلهٔ
        // ۳۰ ثانیه‌ای که خود سرور MinerTools هم برای تازه بودن قیمت استفاده می‌کند
        private const val PRICE_REFRESH_INTERVAL_MS = 30_000L

        // فقط اگر هیچ‌کدام از منابع زنده (mempool.space / blockchain.info) در دسترس نبود استفاده می‌شود؛
        // آخرین‌بار در آگوست ۲۰۲۶ به‌روزرسانی شده (هشریت واقعی شبکه معمولاً کمی بیشتر از این است)
        private const val FALLBACK_NETWORK_HASHRATE_EH = 994.68

        // فاصلهٔ بین هر دور کامل اسکن شبکه در حالت اسکنر پیوسته (برای پیدا کردن دستگاه‌های جدید)
        private const val SCAN_LOOP_INTERVAL_MS = 20_000L

        // فاصلهٔ رفرش خودکار آمار زندهٔ دستگاه‌های از قبل پیداشده (فن، دما، هش‌ریت و ...)
        // این جدا از اسکن شبکه‌ست، پس حتی وقتی اسکنر خاموشه هم آمار دستگاه‌ها خودکار به‌روز می‌مونه
        private const val MINER_LIVE_REFRESH_INTERVAL_MS = 12_000L
    }

    init {
        // رفرش دوره‌ای قیمت‌ها و هشریت شبکه به‌جای فقط یک‌بار موقع باز شدن برنامه؛ این یعنی
        // مقادیر دلار/بیت‌کوین/درآمد تخمینی بدون نیاز به بستن و باز کردن برنامه به‌روز می‌مانند
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                fetchPricesOnce()
                fetchNetworkHashrateOnce()
                _lastPriceUpdate.value = System.currentTimeMillis()
                delay(PRICE_REFRESH_INTERVAL_MS)
            }
        }

        // رفرش دوره‌ای آمار زندهٔ دستگاه‌های پیداشده (فن، دما، هش‌ریت) تا کاربر مجبور نباشه
        // دستی روی هر دستگاه دکمهٔ رفرش بزنه؛ مستقل از روشن یا خاموش بودن اسکنر اجرا می‌شه
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(MINER_LIVE_REFRESH_INTERVAL_MS)
                val ips = _miners.value.map { it.ip }
                for (ip in ips) {
                    try {
                        val info = WhatsminerClient.queryMiner(ip)
                        listMutex.withLock {
                            _miners.value = _miners.value.map { if (it.ip == ip) info else it }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MinerViewModel", "live refresh exception for $ip", e)
                    }
                }
            }
        }
    }

    /**
     * هشریت کل شبکه بیت‌کوین را از منابع عمومی می‌گیرد (برای محاسبهٔ دقیق‌تر درآمد تخمینی).
     * چون این مقدار دائم در حال تغییره، هاردکد کردنش باعث می‌شه محاسبهٔ درآمد بعد از چند ماه غلط بشه؛
     * به همین دلیل به‌جای عدد ثابت، زنده گرفته می‌شود (و هر ۶۰ ثانیه هم رفرش می‌شود)
     */
    private fun fetchNetworkHashrateOnce() {
        if (tryNetworkHashrateMempool()) return
        if (tryNetworkHashrateBlockchainInfo()) return
        // اگر هر دو منبع در دسترس نبودند، مقدار قبلی (در صورت وجود) نگه داشته می‌شود؛
        // فقط اگر تا حالا هیچ مقداری نگرفتیم، از یک تخمین ثابت به‌عنوان آخرین راه‌حل استفاده می‌شود
        if (_networkHashrateEh.value == null) {
            _networkHashrateEh.value = FALLBACK_NETWORK_HASHRATE_EH
        }
    }

    private fun tryNetworkHashrateMempool(): Boolean = try {
        val conn = URL("https://mempool.space/api/v1/mining/hashrate/3d").openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
        }
        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        val hashesPerSecond = json.optDouble("currentHashrate")
        if (hashesPerSecond > 0) {
            _networkHashrateEh.value = hashesPerSecond / 1e18  // H/s -> EH/s
            true
        } else false
    } catch (e: Exception) {
        false
    }

    private fun tryNetworkHashrateBlockchainInfo(): Boolean = try {
        val conn = URL("https://blockchain.info/q/hashrate").openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
        }
        val ghs = conn.inputStream.bufferedReader().readText().trim().toDoubleOrNull()
        if (ghs != null && ghs > 0) {
            _networkHashrateEh.value = ghs / 1e9  // GH/s -> EH/s
            true
        } else false
    } catch (e: Exception) {
        false
    }

    /**
     * دریافت قیمت‌ها با اولویت‌بندی:
     * 1. MinerTools (همان API که اپ مشابه مستقیم استفاده می‌کند - هم قیمت دلاری و هم تومانی
     *    چند ارز از جمله BTC و USDT در یک درخواست؛ دقیق‌ترین و سریع‌ترین منبع)
     * 2. Nobitex (صرافی ایرانی، اگر منبع اول در دسترس نبود)
     * 3. Wallex (صرافی ایرانی دیگر)
     * 4. Binance USDT قیمت + نرخ تخمینی بازار آزاد
     * 5. CoinGecko
     * این تابع به‌صورت مستقیم (نه با launch جدید) فراخوانی می‌شود چون همیشه از داخل حلقهٔ رفرش
     * دوره‌ای که خودش روی Dispatchers.IO اجرا می‌شود صدا زده می‌شود
     */
    private fun fetchPricesOnce() {
        var success = tryMinerToolsMarket()
        if (!success) success = tryNobitex()
        if (!success) success = tryWallex()
        if (!success) success = tryBinanceWithEstimate()
        if (!success) tryCoinGecko()
    }

    /**
     * MinerTools: endpoint عمومی و بدون نیاز به کلید که یک اپ مشابه مستقیم از سرور خودش می‌گیرد.
     * در یک درخواست هم قیمت دلاری و هم قیمت تومانی چند ارز (از جمله BTC و USDT) را برمی‌گرداند؛
     * نرخ دلار هم از روی قیمت تتر (که تقریباً برابر نرخ بازار آزاد دلار است) به‌دست می‌آید.
     * endpoint: GET https://minertools.org/wp-json/minertools/v1/crypto-market
     */
    private fun tryMinerToolsMarket(): Boolean {
        return try {
            val conn = URL("https://minertools.org/wp-json/minertools/v1/crypto-market").openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "WhatsminerMonitor-Android/1.0")
                connectTimeout = 6000
                readTimeout = 8000
            }
            val response = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val coins = json.optJSONArray("coins") ?: return false

            var btcUsd: Double? = null
            var btcToman: Double? = null
            var usdtToman: Double? = null

            for (i in 0 until coins.length()) {
                val coin = coins.optJSONObject(i) ?: continue
                val symbol = coin.optString("symbol").uppercase()
                val priceUsd = coin.optDouble("priceUsd", Double.NaN)
                val priceToman = coin.optDouble("priceToman", Double.NaN)
                if (symbol == "BTC" && !priceUsd.isNaN() && !priceToman.isNaN() && priceUsd > 0 && priceToman > 0) {
                    btcUsd = priceUsd
                    btcToman = priceToman
                }
                if (symbol == "USDT" && !priceToman.isNaN() && priceToman > 0) {
                    usdtToman = priceToman
                }
            }

            if (btcUsd != null && btcToman != null) {
                _btcPriceUsdt.value = btcUsd
                _btcPriceToman.value = btcToman.toLong()
                // نرخ دلار: ترجیحاً از قیمت تتر (نزدیک‌ترین معادل نرخ آزاد دلار)، وگرنه از تقسیم قیمت بیت‌کوین
                _usdToToman.value = (usdtToman ?: (btcToman / btcUsd)).toLong()
                _priceSource.value = "MinerTools"
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
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
        viewModelScope.launch(Dispatchers.IO) {
            fetchPricesOnce()
            fetchNetworkHashrateOnce()
            _lastPriceUpdate.value = System.currentTimeMillis()
        }
    }

    /**
     * اسکن پیوسته: با یک بار زدن، اسکنر روشن می‌مونه و هر چند ثانیه یک‌بار دوباره شبکه رو
     * برای پیدا کردن دستگاه‌های جدید می‌گرده — تا وقتی که خود کاربر با stopScan() متوقفش کنه.
     *
     * نکتهٔ مهم دربارهٔ سرعت نمایش: callback اسکنر به‌محض باز بودن پورت هر IP صدا زده می‌شود؛
     * اول یک ردیف موقت برای همان IP به لیست UI اضافه می‌شود (کاربر همان لحظه دستگاه را می‌بیند)
     * و بعد queryMiner آماره‌های کامل را می‌خواند و جایگزین می‌کند. قبلاً UI تا پایان کامل
     * queryMiner منتظر می‌ماند و به همین دلیل پیدا شدن ماینرها خیلی دیر دیده می‌شد.
     */
    fun startScan() {
        if (_isScanning.value) return
        _isScanning.value = true
        _miners.value = emptyList()
        _foundCount.value = 0
        _statusMessage.value = "در حال تشخیص شبکه وای‌فای..."

        scanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var firstPass = true
                while (isActive) {
                    val subnetInfo = NetworkScanner.getLocalIPv4AndPrefix(getApplication())
                    if (subnetInfo == null) {
                        _statusMessage.value = "به شبکه وای‌فای متصل نیستید یا دسترسی به آن ممکن نیست"
                        return@launch
                    }

                    val (address, prefix) = subnetInfo
                    val hosts = NetworkScanner.buildHostListInSubnet(address, prefix)
                    _statusMessage.value = if (firstPass)
                        "در حال اسکن ${hosts.size} آدرس در شبکه محلی..."
                    else
                        "اسکنر روشن است — در حال بررسی دوباره شبکه برای دستگاه‌های جدید..."

                    NetworkScanner.scanForMiners(hosts) { ip ->
                        // ۱) به‌محض باز بودن پورت این IP، نتیجه فوراً به لیست UI می‌رود:
                        //    یک ردیف موقت اضافه می‌شود (فقط اگر قبلاً در لیست نباشد) تا کاربر
                        //    بدون معطلی دستگاه را ببیند؛ در دفعات بعدی اسکن ردیف موجود حفظ
                        //    می‌شود تا دادهٔ قبلی پرش نکند
                        listMutex.withLock {
                            val existing = _miners.value.toMutableList()
                            if (existing.indexOfFirst { it.ip == ip } < 0) {
                                existing.add(MinerInfo(ip = ip))
                                _miners.value = existing.sortedBy { it.ip }
                                _foundCount.value = _miners.value.size
                            }
                        }
                        // ۲) آماره‌های کامل خوانده و ردیف موقت جایگزین می‌شود
                        try {
                            val info = WhatsminerClient.queryMiner(ip)
                            listMutex.withLock {
                                val existing = _miners.value.toMutableList()
                                val idx = existing.indexOfFirst { it.ip == ip }
                                if (idx >= 0) existing[idx] = info else existing.add(info)
                                _miners.value = existing.sortedBy { it.ip }
                                _foundCount.value = _miners.value.size
                            }
                            if (!info.isReachable) {
                                android.util.Log.w("MinerViewModel", "miner at $ip reachable but query returned isReachable=false: ${info.errorMessage}")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("MinerViewModel", "queryMiner exception for $ip", e)
                            // دستگاه پیدا شده ولی خواندن اطلاعات شکست خورد؛ ردیف موقت با حالت
                            // خطا جایگزین می‌شود و در دوره‌های بعدی رفرش زنده دوباره تلاش می‌شود
                            val fallback = com.miner.whatsminermonitor.model.MinerInfo(ip = ip, isReachable = false, errorMessage = "خطا در خواندن اطلاعات: ${e.message}")
                            listMutex.withLock {
                                val existing = _miners.value.toMutableList()
                                val idx = existing.indexOfFirst { it.ip == ip }
                                if (idx >= 0) existing[idx] = fallback else existing.add(fallback)
                                _miners.value = existing.sortedBy { it.ip }
                                _foundCount.value = _miners.value.size
                            }
                        }
                    }

                    firstPass = false
                    if (!isActive) break
                    _statusMessage.value = if (_miners.value.isEmpty())
                        "اسکنر روشن است — هیچ ماینری هنوز پیدا نشد، هر ${SCAN_LOOP_INTERVAL_MS / 1000} ثانیه دوباره بررسی می‌شود"
                    else
                        "اسکنر روشن است — ${_miners.value.size} دستگاه پیدا شد"

                    delay(SCAN_LOOP_INTERVAL_MS)
                }
            } finally {
                _isScanning.value = false
            }
        }
    }

    /** توقف اسکن پیوسته با دکمه — دستگاه‌های پیداشده در لیست باقی می‌مونن. */
    fun stopScan() {
        val currentCount = _miners.value.size
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
        _statusMessage.value = if (currentCount == 0)
            "اسکن متوقف شد"
        else
            "اسکن متوقف شد — $currentCount دستگاه پیدا شد"
    }

    fun refreshMiner(ip: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = WhatsminerClient.queryMiner(ip)
                listMutex.withLock {
                    _miners.value = _miners.value.map { if (it.ip == ip) info else it }
                }
            } catch (e: Exception) {
                android.util.Log.e("MinerViewModel", "refreshMiner exception for $ip", e)
            }
        }
    }
}