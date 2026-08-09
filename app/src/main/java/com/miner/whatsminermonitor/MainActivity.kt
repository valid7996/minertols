package com.miner.whatsminermonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miner.whatsminermonitor.model.HashboardInfo
import com.miner.whatsminermonitor.model.MinerInfo
import com.miner.whatsminermonitor.ui.MinerViewModel
import com.miner.whatsminermonitor.ui.theme.WhatsminerMonitorTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WhatsminerMonitorTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MinerScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinerScreen(viewModel: MinerViewModel = viewModel()) {
    val miners by viewModel.miners.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val status by viewModel.statusMessage.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("مانیتور ماینرهای Whatsminer") }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.startScan() },
                icon = {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    }
                },
                text = { Text(if (isScanning) "در حال اسکن..." else "اسکن شبکه") }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (miners.isEmpty() && !isScanning) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(miners, key = { it.ip }) { miner ->
                        MinerCard(
                            miner = miner,
                            onRefresh = { viewModel.refreshMiner(miner.ip) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(72.dp)) }
                }
            }
        }
    }
}

@Composable
fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "برای پیدا کردن ماینرها روی «اسکن شبکه» بزنید",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "گوشی باید به همان وای‌فای ماینرها متصل باشد",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun MinerCard(miner: MinerInfo, onRefresh: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(miner.ip, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (!miner.isReachable) {
                        Text(
                            miner.errorMessage ?: "پاسخ دریافت نشد",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            "روشن: ${miner.uptimeFormatted()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "بروزرسانی")
                    }
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null
                    )
                }
            }

            if (miner.isReachable) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatChip("هشریت", miner.totalHashrateThs?.let { String.format(Locale.US, "%.1f TH/s", it) } ?: "—")
                    StatChip("دما", miner.averageTemperature?.let { String.format(Locale.US, "%.1f°C", it) } ?: "—")
                    StatChip("توان", miner.powerWatt?.let { "$it W" } ?: "—")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatChip("فن ورودی", miner.fanSpeedIn?.let { "$it RPM" } ?: "—")
                    StatChip("فن خروجی", miner.fanSpeedOut?.let { "$it RPM" } ?: "—")
                    StatChip("فریمور", miner.firmwareVersion ?: "—")
                }

                AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Divider()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("هش‌بردها", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        if (miner.hashboards.isEmpty()) {
                            Text("اطلاعاتی از هش‌بردها دریافت نشد", style = MaterialTheme.typography.bodySmall)
                        } else {
                            miner.hashboards.forEach { HashboardRow(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HashboardRow(board: HashboardInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("برد ${board.id}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            board.hashrateGhs?.let { String.format(Locale.US, "%.0f GH/s", it) } ?: "—",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            board.temperaturePcb?.let { String.format(Locale.US, "%.1f°C", it) } ?: "—",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            board.effectiveChips?.let { "$it چیپ" } ?: "—",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
