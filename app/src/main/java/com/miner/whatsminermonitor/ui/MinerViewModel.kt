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

    // قیمت BTC به تومان (برای بازار ایران)
    private val _btcPriceToman = MutableStateFlow<Long?>(null)
    val btcPriceToman: StateFlow<Long?> = _btcPriceToman

    // قیمت BTC به دلار (USDT)
    private val _btcPriceUsdt = MutableStateFlow<Double?>(null)
    val btcPriceUsdt: StateFlow<Double?> = _btcPriceUsdt

    // نرخ دلار به تومان
    private val _usdToToman = MutableStateFlow<Long?>(null)
    val usdToToman: StateFlow<Long?> = _usdToToman

    private val listMutex = Mutex()

    init {
        fetchBitcoinPrice()
    }

    /**
     * دریافت قیمت بیتکوین از API عمومی
     * قیمت دلاری از CoinGecko و نرخ تبدیل تقریبی بازار آزاد ایران
     */
    private fun fetchBitcoinPrice() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // قیمت BTC به دلار از CoinGecko
                val url = "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd"
                val response = URL(url).readText()
                val json = JSONObject(response)
                val usdPrice = json.getJSONObject("bitcoin").getDouble("usd")
                _btcPriceUsdt.value = usdPrice

                // نرخ دلار به تومان از API ایرانی (navasan یا تخمینی)
                try {
                    val navaUrl = "https://api.navasan.tech/latest/?api=free&item=usd_buy"
                    val navaResp = URL(navaUrl).readText()
                    val navaJson = JSONObject(navaResp)
                    val rate = navaJson.optDouble("value", 0.0)
                    if (rate > 0) {
                        _usdToToman.value = rate.toLong()
                        _btcPriceToman.value = (usdPrice * rate).toLong()
                    } else {
                        // تخمین پیش‌فرض در صورت عدم دسترسی
                        val defaultRate = 86_000L
                        _usdToToman.value = defaultRate
                        _btcPriceToman.value = (usdPrice * defaultRate).toLong()
                    }
                } catch (e: Exception) {
                    // نرخ تخمینی در صورت عدم دسترسی به API ایرانی
                    val defaultRate = 86_000L
                    _usdToToman.value = defaultRate
                    _btcPriceToman.value = (usdPrice * defaultRate).toLong()
                }

            } catch (e: Exception) {
                // اگر اینترنت نبود مقادیر null می‌ماند
            }
        }
    }

    fun refreshBitcoinPrice() {
        fetchBitcoinPrice()
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
