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

class MinerViewModel(application: Application) : AndroidViewModel(application) {

    private val _miners = MutableStateFlow<List<MinerInfo>>(emptyList())
    val miners: StateFlow<List<MinerInfo>> = _miners

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage

    private val _foundCount = MutableStateFlow(0)
    val foundCount: StateFlow<Int> = _foundCount

    private val listMutex = Mutex()

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
