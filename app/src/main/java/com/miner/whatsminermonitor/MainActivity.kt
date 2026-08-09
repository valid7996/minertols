package com.miner.whatsminermonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miner.whatsminermonitor.model.HashboardInfo
import com.miner.whatsminermonitor.model.MinerInfo
import com.miner.whatsminermonitor.ui.MinerViewModel
import com.miner.whatsminermonitor.ui.theme.WhatsminerMonitorTheme
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

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
    val btcPriceUsdt by viewModel.btcPriceUsdt.collectAsState()
    val btcPriceToman by viewModel.btcPriceToman.collectAsState()
    val usdToToman by viewModel.usdToToman.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("مانیتور ماینرهای Whatsminer") },
                actions = {
                    // نمایش قیمت BTC در تاپ‌بار
                    btcPriceUsdt?.let { price ->
                        Text(
                            text = "$${"%,.0f".format(price)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFF7931A),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    IconButton(onClick = { viewModel.refreshBitcoinPrice() }) {
                        Icon(Icons.Filled.CurrencyBitcoin, contentDescription = "بروزرسانی قیمت BTC")
                    }
                }
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
                            btcPriceUsdt = btcPriceUsdt,
                            btcPriceToman = btcPriceToman,
                            usdToToman = usdToToman,
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
            Icon(
                Icons.Filled.Router,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "برای پیدا کردن ماینرها روی «اسکن شبکه» بزنید",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "گوشی باید به همان وای‌فای ماینرها متصل باشد",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun MinerCard(
    miner: MinerInfo,
    btcPriceUsdt: Double?,
    btcPriceToman: Long?,
    usdToToman: Long?,
    onRefresh: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {

            // ===== هدر: IP + Uptime + دکمه‌ها =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Dns,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(miner.ip, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    if (!miner.isReachable) {
                        Text(
                            miner.errorMessage ?: "پاسخ دریافت نشد",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Timer,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                "روشن: ${miner.uptimeFormatted()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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

                // ===== ردیف اول: مدل + فریمور + کنترل‌برد =====
                if (miner.minerType != null || miner.firmwareVersion != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        miner.minerType?.let {
                            InfoBadge(label = "مدل", value = it, icon = Icons.Filled.Memory)
                        }
                        miner.firmwareVersion?.let {
                            InfoBadge(label = "فریمور", value = it.take(12), icon = Icons.Filled.Code)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(10.dp))

                // ===== بخش هشریت: GHS 5s + GHSav =====
                Text(
                    "⚡ هشریت",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatBox(
                        label = "لحظه‌ای (5s)",
                        value = miner.totalHashrateThs?.let { "%.2f".format(it) } ?: "—",
                        unit = "TH/s",
                        color = Color(0xFF2196F3)
                    )
                    StatBox(
                        label = "میانگین (av)",
                        value = miner.ghsAverageThs?.let { "%.2f".format(it) } ?: "—",
                        unit = "TH/s",
                        color = Color(0xFF4CAF50)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(10.dp))

                // ===== بخش وضعیت: اکسپت / رجکت / زمان پاسخ / توان =====
                Text(
                    "📊 وضعیت",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatChip(
                        label = "اکسپت‌ها",
                        value = miner.accepted?.let { formatNumber(it) } ?: "—",
                        color = Color(0xFF4CAF50)
                    )
                    StatChip(
                        label = "رجکت‌ها",
                        value = miner.rejected?.let { it.toString() } ?: "—",
                        color = if ((miner.rejected ?: 0) > 0) Color(0xFFF44336) else MaterialTheme.colorScheme.onSurface
                    )
                    StatChip(
                        label = "توان",
                        value = miner.powerWatt?.let { "$it W" } ?: "—",
                        color = Color(0xFFFF9800)
                    )
                    miner.poolResponseMs?.let {
                        StatChip(label = "پینگ", value = "$it ms", color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(10.dp))

                // ===== بخش دما و فن =====
                Text(
                    "🌡️ دما و فن",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatChip(
                        label = "دمای میانگین",
                        value = miner.averageTemperature?.let { "%.1f°C".format(it) } ?: "—",
                        color = tempColor(miner.averageTemperature)
                    )
                    StatChip(
                        label = "فن ورودی",
                        value = miner.fanSpeedIn?.let { "$it RPM" } ?: "—",
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    StatChip(
                        label = "فن خروجی",
                        value = miner.fanSpeedOut?.let { "$it RPM" } ?: "—",
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // ===== بخش جزئیات هش‌بردها (در صورت expand) =====
                AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Divider()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "🖥️ هش‌بردها",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        if (miner.hashboards.isEmpty()) {
                            Text("اطلاعاتی از هش‌بردها دریافت نشد", style = MaterialTheme.typography.bodySmall)
                        } else {
                            miner.hashboards.forEach { HashboardRow(it) }
                        }
                    }
                }

                // ===== بخش درآمد تخمینی =====
                Spacer(modifier = Modifier.height(10.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(10.dp))
                IncomeSection(
                    miner = miner,
                    btcPriceUsdt = btcPriceUsdt,
                    btcPriceToman = btcPriceToman,
                    usdToToman = usdToToman
                )
            }
        }
    }
}

@Composable
fun IncomeSection(
    miner: MinerInfo,
    btcPriceUsdt: Double?,
    btcPriceToman: Long?,
    usdToToman: Long?
) {
    val dailyBtc = miner.estimatedDailyBtc()
    val monthlyBtc = dailyBtc * 30

    val dailyUsdt = btcPriceUsdt?.let { dailyBtc * it }
    val monthlyUsdt = btcPriceUsdt?.let { monthlyBtc * it }

    val dailyToman = btcPriceToman?.let { (dailyBtc * it).roundToLong() }
    val monthlyToman = btcPriceToman?.let { (monthlyBtc * it).roundToLong() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF7931A).copy(alpha = 0.08f))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.MonetizationOn,
                contentDescription = null,
                tint = Color(0xFFF7931A),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "درآمد تخمینی",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF7931A)
            )
            Spacer(modifier = Modifier.weight(1f))
            if (btcPriceUsdt == null) {
                Text(
                    "قیمت BTC در حال بارگذاری...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // ستون روزانه
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "روزانه",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                IncomeRow(
                    label = "USDT",
                    value = dailyUsdt?.let { "${"%.2f".format(it)}" } ?: "—",
                    color = Color(0xFF4CAF50)
                )
                IncomeRow(
                    label = "تومان",
                    value = dailyToman?.let { formatToman(it) } ?: "—",
                    color = Color(0xFF2196F3)
                )
                IncomeRow(
                    label = "BTC",
                    value = "%.8f".format(dailyBtc),
                    color = Color(0xFFF7931A)
                )
            }

            Divider(
                modifier = Modifier
                    .width(1.dp)
                    .height(80.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // ستون ماهانه
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "ماهانه",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                IncomeRow(
                    label = "USDT",
                    value = monthlyUsdt?.let { "${"%.2f".format(it)}" } ?: "—",
                    color = Color(0xFF4CAF50)
                )
                IncomeRow(
                    label = "تومان",
                    value = monthlyToman?.let { formatToman(it) } ?: "—",
                    color = Color(0xFF2196F3)
                )
                IncomeRow(
                    label = "BTC",
                    value = "%.6f".format(monthlyBtc),
                    color = Color(0xFFF7931A)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "* بر اساس GHSav و قیمت لحظه‌ای BTC | بدون احتساب هزینه برق",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (usdToToman != null) {
            Text(
                "نرخ دلار: ${formatNumber(usdToToman.toInt())} تومان",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun IncomeRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
fun HashboardRow(board: HashboardInfo) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "برد ${board.id}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                board.hashrateGhs?.let { "%.1f GH/s".format(it) } ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF2196F3)
            )
            Text(
                board.temperaturePcb?.let { "%.0f°C".format(it) } ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = tempColor(board.temperaturePcb)
            )
            Text(
                board.effectiveChips?.let { "$it چیپ" } ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            board.status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (it.lowercase().contains("alive") || it == "1") Color(0xFF4CAF50) else Color(0xFFF44336)
                )
            }
        }
    }
}

@Composable
fun StatChip(label: String, value: String, color: Color = Color.Unspecified) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StatBox(label: String, value: String, unit: String, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(unit, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.7f))
    }
}

@Composable
fun InfoBadge(label: String, value: String, icon: ImageVector) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(4.dp))
        Text("$label: ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun tempColor(temp: Double?): Color {
    return when {
        temp == null -> Color.Unspecified
        temp >= 85 -> Color(0xFFF44336)   // قرمز - خطرناک
        temp >= 75 -> Color(0xFFFF9800)   // نارنجی - گرم
        temp >= 65 -> Color(0xFF8BC34A)   // سبز روشن - نرمال
        else -> Color(0xFF4CAF50)          // سبز - خوب
    }
}

fun formatNumber(n: Int): String =
    NumberFormat.getNumberInstance(Locale.US).format(n)

fun formatToman(n: Long): String {
    return when {
        n >= 1_000_000_000 -> "%.2f میلیارد".format(n / 1_000_000_000.0)
        n >= 1_000_000 -> "%.0f میلیون".format(n / 1_000_000.0)
        else -> NumberFormat.getNumberInstance(Locale.US).format(n)
    }
}
