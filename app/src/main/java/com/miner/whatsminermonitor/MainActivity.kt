package com.miner.whatsminermonitor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.miner.whatsminermonitor.model.HashboardInfo
import com.miner.whatsminermonitor.model.MinerInfo
import com.miner.whatsminermonitor.model.PoolEntry
import com.miner.whatsminermonitor.model.PoolProfile
import com.miner.whatsminermonitor.model.PoolProfiles
import com.miner.whatsminermonitor.model.WhatsminerErrorDetail
import com.miner.whatsminermonitor.network.PrivilegedResult
import com.miner.whatsminermonitor.network.WhatsminerClient
import com.miner.whatsminermonitor.ui.CredentialsStore
import com.miner.whatsminermonitor.ui.MinerViewModel
import com.miner.whatsminermonitor.ui.theme.WhatsminerMonitorTheme
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WhatsminerMonitorTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavHost()
                }
            }
        }
    }
}

@Composable
fun AppNavHost(viewModel: MinerViewModel = viewModel()) {
    val navController: NavHostController = rememberNavController()
    NavHost(navController = navController, startDestination = "list") {
        composable("list") {
            MinerListScreen(
                viewModel = viewModel,
                onOpenDetail = { ip ->
                    navController.navigate("detail/${URLEncoder.encode(ip, "UTF-8")}")
                }
            )
        }
        composable("detail/{ip}") { backStackEntry ->
            val ip = backStackEntry.arguments?.getString("ip")
            MinerDetailScreen(
                ip = ip,
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

// ==================================================================================
// صفحه اصلی: خلاصه (درآمد روزانه / گیج هشریت کل / تعداد ماینرها) + لیست دستگاه‌ها
// ==================================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinerListScreen(viewModel: MinerViewModel, onOpenDetail: (String) -> Unit) {
    val miners by viewModel.miners.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val status by viewModel.statusMessage.collectAsState()
    val btcPriceUsdt by viewModel.btcPriceUsdt.collectAsState()
    val networkHashrateEh by viewModel.networkHashrateEh.collectAsState()
    val lastPriceUpdate by viewModel.lastPriceUpdate.collectAsState()

    val reachableMiners = miners.filter { it.isReachable }
    val totalThs = reachableMiners.sumOf { it.ghsAverageThs ?: it.totalHashrateThs ?: 0.0 }
    val networkEh = networkHashrateEh ?: 930.0
    val totalDailyUsdt = btcPriceUsdt?.let { price ->
        reachableMiners.sumOf { it.estimatedDailyBtc(networkEh) * price }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("مانیتور ماینرهای Whatsminer") })
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            SummaryHeader(
                dailyUsdt = totalDailyUsdt,
                totalThs = totalThs,
                minerCount = miners.size,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )

            lastPriceUpdate?.let { updatedAt ->
                val timeText = remember(updatedAt) {
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(updatedAt))
                }
                Text(
                    "قیمت دلار/بیت‌کوین زنده است — آخرین به‌روزرسانی: $timeText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                )
            }

            if (miners.isEmpty() && !isScanning) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(miners, key = { it.ip }) { miner ->
                        MinerListItem(miner = miner, btcPriceUsdt = btcPriceUsdt, networkHashrateEh = networkEh, onOpen = { onOpenDetail(miner.ip) })
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
                painter = painterResource(id = R.drawable.ic_wifi_wait),
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

// ==================================================================================
// کارت خلاصه بالای صفحه: درآمد روزانه | گیج هشریت کل | تعداد کل ماینرها
// ==================================================================================
@Composable
fun SummaryHeader(
    dailyUsdt: Double?,
    totalThs: Double,
    minerCount: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // درآمد روزانه
            Column(
                modifier = Modifier
                    .weight(0.8f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF4CAF50).copy(alpha = 0.10f))
                    .padding(vertical = 14.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.AttachMoney,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    dailyUsdt?.let { "$${"%.2f".format(it)}" } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "درآمد روزانه",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // گیج هشریت کل
            Column(
                modifier = Modifier.weight(1.2f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SpeedGauge(
                    valueThs = totalThs,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                )
                Text(
                    "مجموع هشریت ماینرها",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // تعداد کل ماینرها
            Column(
                modifier = Modifier
                    .weight(0.8f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF2196F3).copy(alpha = 0.10f))
                    .padding(vertical = 14.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.Hub,
                    contentDescription = null,
                    tint = Color(0xFF2196F3),
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "$minerCount",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "کل ماینرها",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * گیج نیم‌دایره‌ای شبیه سرعت‌سنج که هشریت کل (TH/s) را نمایش می‌دهد
 */
@Composable
fun SpeedGauge(valueThs: Double, modifier: Modifier = Modifier) {
    // مقیاس گیج را متناسب با مقدار فعلی، کمی بزرگ‌تر از مقدار تنظیم می‌کنیم
    val niceSteps = listOf(50.0, 100.0, 150.0, 200.0, 300.0, 400.0, 600.0, 800.0, 1000.0, 1500.0, 2000.0, 3000.0)
    val maxScale = niceSteps.firstOrNull { it >= valueThs * 1.25 } ?: (valueThs * 1.3).coerceAtLeast(50.0)
    val fraction = (valueThs / maxScale).coerceIn(0.0, 1.0)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(modifier = modifier) {
            val strokeWidth = size.height * 0.14f
            val radius = minOf(size.width / 2f, size.height) - strokeWidth / 2f
            val center = Offset(size.width / 2f, size.height)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val boxSize = Size(radius * 2f, radius * 2f)

            // کمان رنگی پس‌زمینه (آبی -> بنفش -> نارنجی -> قرمز)
            drawArc(
                brush = Brush.horizontalGradient(
                    listOf(
                        Color(0xFF2196F3),
                        Color(0xFF9C27B0),
                        Color(0xFFFF9800),
                        Color(0xFFF44336)
                    ),
                    startX = topLeft.x,
                    endX = topLeft.x + boxSize.width
                ),
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = topLeft,
                size = boxSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // عقربه
            val needleAngleDeg = 180.0 + fraction * 180.0
            val needleAngleRad = Math.toRadians(needleAngleDeg)
            val needleLength = radius - strokeWidth
            val needleEnd = Offset(
                x = center.x + (needleLength * cos(needleAngleRad)).toFloat(),
                y = center.y + (needleLength * sin(needleAngleRad)).toFloat()
            )
            drawLine(
                color = Color(0xFFFF9800),
                start = center,
                end = needleEnd,
                strokeWidth = strokeWidth * 0.3f,
                cap = StrokeCap.Round
            )
            drawCircle(color = Color(0xFF2C3E50), radius = strokeWidth * 0.55f, center = center)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "%.1f".format(valueThs),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                "TH/s",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
    }
}

/**
 * آیکون گرافیکی دستگاه ماینر - از تصویر miner-device.svg که کاربر ارسال کرد
 */
@Composable
fun MinerDeviceIcon(modifier: Modifier = Modifier, tint: Color = Color(0xFF3A3A3A)) {
    Icon(
        painter = painterResource(id = R.drawable.ic_miner_device),
        contentDescription = null,
        tint = tint,
        modifier = modifier
    )
}

// ==================================================================================
// آیتم فشرده لیست ماینرها روی صفحه اصلی + دکمه باز کردن جزئیات
// ==================================================================================
@Composable
fun MinerListItem(miner: MinerInfo, btcPriceUsdt: Double?, networkHashrateEh: Double = 930.0, onOpen: () -> Unit) {
    val dailyUsdt = btcPriceUsdt?.let { miner.estimatedDailyBtc(networkHashrateEh) * it }

    Column {
        Text(
            miner.minerType ?: "WhatsMiner",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = miner.isReachable) { onOpen() },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MinerDeviceIcon(
                    modifier = Modifier.size(64.dp),
                    tint = if (miner.isReachable) Color(0xFF3A3A3A) else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    if (!miner.isReachable) {
                        Text(
                            miner.errorMessage ?: "پاسخ دریافت نشد",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(miner.ip, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        MiniInfoLine(Icons.Filled.Shield, miner.poolWorkerName ?: miner.ip, bold = true)
                        MiniInfoLine(Icons.Filled.Speed, miner.ghsAverageThs?.let { "%.1f TH/s".format(it) } ?: "—")
                        MiniInfoLine(Icons.Filled.SettingsEthernet, "MAC: ${miner.macAddress ?: "—"}")
                        MiniInfoLine(Icons.Filled.Dns, "IP: ${miner.ip}")
                    }
                }

                if (miner.isReachable) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            dailyUsdt?.let { "$${"%.2f".format(it)}" } ?: "—",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            miner.averageTemperature?.let { "%.1f°C".format(it) } ?: "—",
                            style = MaterialTheme.typography.bodyMedium,
                            color = tempColor(miner.averageTemperature)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        HealthBadge(miner = miner, compact = true)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = onOpen,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = "باز کردن", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun MiniInfoLine(icon: ImageVector, text: String, bold: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/**
 * نشان سلامت دستگاه: اگر کد خطای فعالی نداشته باشد «عالی» و در غیر این صورت «اخطار (تعداد)»
 */
@Composable
fun HealthBadge(miner: MinerInfo, compact: Boolean = false) {
    val healthy = miner.isHealthy
    val color = if (healthy) Color(0xFF4CAF50) else Color(0xFFF44336)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (healthy) Icons.Filled.Favorite else Icons.Filled.Warning,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            if (healthy) "عالی" else "اخطار (${miner.errorCodes.size})",
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

// ==================================================================================
// صفحه جزئیات دستگاه
// ==================================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinerDetailScreen(ip: String?, viewModel: MinerViewModel, onBack: () -> Unit) {
    val miners by viewModel.miners.collectAsState()
    val btcPriceUsdt by viewModel.btcPriceUsdt.collectAsState()
    val btcPriceToman by viewModel.btcPriceToman.collectAsState()
    val usdToToman by viewModel.usdToToman.collectAsState()
    val priceSource by viewModel.priceSource.collectAsState()
    val networkHashrateEh by viewModel.networkHashrateEh.collectAsState()
    val miner = miners.firstOrNull { it.ip == ip }

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showRebootConfirm by remember { mutableStateOf(false) }
    var showPoolPicker by remember { mutableStateOf(false) }
    var poolPendingConfirm by remember { mutableStateOf<PoolProfile?>(null) }
    var showSetPasswordDialog by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }

    suspend fun runPrivileged(action: PendingPrivilegedAction, ipAddr: String) {
        isBusy = true
        val password = CredentialsStore.getPassword(context, ipAddr)
        val result: PrivilegedResult = when (action) {
            is PendingPrivilegedAction.Reboot -> WhatsminerClient.reboot(ipAddr, password)
            is PendingPrivilegedAction.SwitchPool -> {
                val workerName = miner?.poolWorkerName?.takeIf { it.isNotBlank() } ?: "worker1"
                val entries = action.profile.addresses.map { PoolEntry(url = it, worker = workerName) }
                WhatsminerClient.updatePools(ipAddr, password, entries)
            }
        }
        isBusy = false
        if (result.wrongPassword) {
            snackbarHostState.showSnackbar("رمز فعلی اشتباه است. از دکمه 🔑 بالای صفحه رمز واقعی دستگاه را تنظیم کنید.")
        } else {
            snackbarHostState.showSnackbar(result.message)
            if (result.success) viewModel.refreshMiner(ipAddr)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(miner?.poolWorkerName ?: miner?.minerType ?: "جزئیات دستگاه") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                },
                actions = {
                    if (miner != null) {
                        IconButton(onClick = { showSetPasswordDialog = true }) {
                            Icon(Icons.Filled.Key, contentDescription = "تنظیم رمز دستگاه")
                        }
                        IconButton(onClick = { viewModel.refreshMiner(miner.ip) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "بروزرسانی")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (miner == null) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("دستگاه یافت نشد", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp)
        ) {
            // ===== هدر: آیکون دستگاه + نام Worker/مدل =====
            Row(verticalAlignment = Alignment.CenterVertically) {
                MinerDeviceIcon(modifier = Modifier.size(72.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        miner.poolWorkerName ?: "Worker: —",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        miner.minerType ?: "WhatsMiner",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    HealthBadge(miner = miner)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ===== کارت شبکه: IP و MAC =====
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Dns, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("IP: ${miner.ip}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        Row {
                            IconButton(onClick = { clipboard.setText(AnnotatedString(miner.ip)) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "کپی IP", modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(2.dp))
                            IconButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://${miner.ip}"))
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Filled.OpenInNew, contentDescription = "باز کردن در مرورگر", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.SettingsEthernet, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("MAC: ${miner.macAddress ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ===== اطلاعات دستگاه: فریمور / کنترل‌برد / پاور / مدل =====
            Text("اطلاعات دستگاه", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DetailField(label = "فریمور", value = miner.firmwareVersion?.take(14) ?: "—", modifier = Modifier.weight(1f))
                DetailField(label = "کنترل‌برد", value = miner.controlBoard ?: "—", modifier = Modifier.weight(1f))
                DetailField(label = "پاور", value = miner.powerSupplyModel ?: "—", modifier = Modifier.weight(1f))
                DetailField(label = "مدل", value = miner.minerType ?: "—", modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(14.dp))

            // ===== وضعیت: زمان فعالیت / تراهش / خطاها =====
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                UptimeChip(miner = miner)
                StatChip(
                    label = "تراهش",
                    value = miner.ghsAverageThs?.let { "%.1f TH/s".format(it) } ?: "—",
                    color = Color(0xFF2196F3)
                )
                StatChip(
                    label = "خطاها",
                    value = "${miner.errorCodes.size}",
                    color = if (miner.errorCodes.isNotEmpty()) MaterialTheme.colorScheme.error else Color(0xFF4CAF50)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(10.dp))

            // ===== اکسپت‌ها / رجکت‌ها / توان =====
            Text("📊 وضعیت استخراج", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatChip(label = "اکسپت‌ها", value = miner.accepted?.let { formatNumber(it) } ?: "—", color = Color(0xFF4CAF50))
                StatChip(
                    label = "رجکت‌ها",
                    value = miner.rejected?.let { formatNumber(it) } ?: "—",
                    color = if ((miner.rejected ?: 0) > 0) MaterialTheme.colorScheme.error else Color.Unspecified
                )
                StatChip(label = "توان", value = miner.powerWatt?.let { "$it W" } ?: "—", color = Color(0xFFFF9800))
            }

            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(10.dp))

            // ===== دما و فن =====
            Text("🌡️ دما و فن", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatChip(
                    label = "دمای میانگین",
                    value = miner.averageTemperature?.let { "%.1f°C".format(it) } ?: "—",
                    color = tempColor(miner.averageTemperature)
                )
                StatChip(
                    label = "فن جلو (ورودی)",
                    value = miner.fanSpeedIn?.let { "$it RPM" } ?: "—",
                    icon = R.drawable.ic_fan_speed
                )
                StatChip(
                    label = "فن عقب (خروجی)",
                    value = miner.fanSpeedOut?.let { "$it RPM" } ?: "—",
                    icon = R.drawable.ic_fan_speed
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(10.dp))

            // ===== عملیات دستگاه: ریبوت / تغییر پول =====
            Text("⚙️ عملیات دستگاه", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { showRebootConfirm = true },
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ریبوت دستگاه")
                }
                OutlinedButton(
                    onClick = { showPoolPicker = true },
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تغییر پول")
                }
            }
            if (isBusy) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                "رمز دستگاه پیش‌فرض «admin» در نظر گرفته می‌شود؛ اگر تغییر کرده باشد به‌صورت خودکار برای وارد کردن رمز صحیح از شما سؤال می‌شود.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )

            if (miner.hashboards.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(10.dp))
                Text("🖥️ هش‌بردها", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(6.dp))
                miner.hashboards.forEach { HashboardRow(it) }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ===== وضعیت سلامت / خطاهای فعال =====
            ErrorsSection(miner, onRetryCheck = { viewModel.refreshMiner(miner.ip) })

            Spacer(modifier = Modifier.height(14.dp))

            IncomeSection(
                miner = miner,
                btcPriceUsdt = btcPriceUsdt,
                btcPriceToman = btcPriceToman,
                usdToToman = usdToToman,
                priceSource = priceSource,
                networkHashrateEh = networkHashrateEh ?: 930.0
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (miner == null) return

    // ===== دیالوگ تایید ریبوت =====
    if (showRebootConfirm) {
        AlertDialog(
            onDismissRequest = { showRebootConfirm = false },
            icon = { Icon(Icons.Filled.RestartAlt, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("ریبوت دستگاه") },
            text = { Text("آیا مطمئن هستید که می‌خواهید این دستگاه ریبوت شود؟ ماینینگ برای چند دقیقه متوقف خواهد شد.") },
            confirmButton = {
                TextButton(onClick = {
                    showRebootConfirm = false
                    scope.launch { runPrivileged(PendingPrivilegedAction.Reboot, miner.ip) }
                }) { Text("بله، ریبوت کن", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRebootConfirm = false }) { Text("انصراف") }
            }
        )
    }

    // ===== دیالوگ انتخاب پروفایل پول =====
    if (showPoolPicker) {
        AlertDialog(
            onDismissRequest = { showPoolPicker = false },
            title = { Text("انتخاب پول ماینینگ") },
            text = {
                Column {
                    PoolProfiles.all.forEach { profile ->
                        TextButton(
                            onClick = {
                                showPoolPicker = false
                                poolPendingConfirm = profile
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(profile.displayName, fontWeight = FontWeight.Bold)
                                Text(
                                    profile.addresses.first().removePrefix("stratum+tcp://"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPoolPicker = false }) { Text("انصراف") }
            }
        )
    }

    // ===== دیالوگ تایید تغییر پول (هشدار) =====
    poolPendingConfirm?.let { profile ->
        AlertDialog(
            onDismissRequest = { poolPendingConfirm = null },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("توجه: تغییر پول ماینینگ") },
            text = {
                Text(
                    "با تایید این عملیات، پول ماینینگ این دستگاه فوراً به «${profile.displayName}» تغییر می‌کند و ماینینگ فعلی قطع و به پول جدید متصل می‌شود. آیا موافقید؟"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = profile
                    poolPendingConfirm = null
                    scope.launch { runPrivileged(PendingPrivilegedAction.SwitchPool(p), miner.ip) }
                }) { Text("بله، تغییر بده", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { poolPendingConfirm = null }) { Text("انصراف") }
            }
        )
    }

    // ===== دیالوگ تنظیم رمز دستگاه (اختیاری، با دکمه 🔑 بالای صفحه باز می‌شود) =====
    if (showSetPasswordDialog) {
        var passwordInput by remember { mutableStateOf(CredentialsStore.getPassword(context, miner.ip)) }
        AlertDialog(
            onDismissRequest = { showSetPasswordDialog = false },
            icon = { Icon(Icons.Filled.Key, contentDescription = null) },
            title = { Text("رمز عبور ادمین دستگاه") },
            text = {
                Column {
                    Text("رمز واقعی ادمین این دستگاه را وارد کنید تا ریبوت و تغییر پول بدون وقفه انجام شود:")
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("رمز عبور") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        CredentialsStore.setPassword(context, miner.ip, passwordInput)
                        showSetPasswordDialog = false
                    },
                    enabled = passwordInput.isNotBlank()
                ) { Text("ذخیره") }
            },
            dismissButton = {
                TextButton(onClick = { showSetPasswordDialog = false }) { Text("انصراف") }
            }
        )
    }

}

private sealed class PendingPrivilegedAction {
    object Reboot : PendingPrivilegedAction()
    data class SwitchPool(val profile: PoolProfile) : PendingPrivilegedAction()
}

@Composable
fun DetailField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/**
 * بخش خطاها: اگر خطای فعالی نباشد، پیام سلامت «عالی» و در غیر این صورت فهرست خطاها
 * به همراه علت و راه‌حل هرکدام نمایش داده می‌شود
 */
@Composable
fun ErrorsSection(miner: MinerInfo, onRetryCheck: (() -> Unit)? = null) {
    if (!miner.isReachable) return

    if (miner.errorCodes.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(id = R.drawable.ic_notif_bell),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "خطاهای فعال",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            miner.errorDetails.forEach { ErrorDetailCard(it) }
        }
    } else if (miner.errorCheckFailed) {
        // این حالت با «سالم» فرق دارد: یعنی نتوانستیم از دستگاه کد خطا بگیریم، نه اینکه واقعا خطایی نیست
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFF9800).copy(alpha = 0.10f))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.HelpOutline, contentDescription = null, tint = Color(0xFFFF9800))
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("بررسی کد خطا ناموفق بود", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color(0xFFFF9800))
                    Text("دستگاه به درخواست کد خطا پاسخ نداد؛ این به معنی سالم بودن قطعی نیست", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onRetryCheck != null) {
                    IconButton(onClick = onRetryCheck) {
                        Icon(Icons.Filled.Refresh, contentDescription = "تلاش دوباره", tint = Color(0xFFFF9800))
                    }
                }
            }
        }
    } else {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50).copy(alpha = 0.10f))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50))
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("سلامت دستگاه: عالی", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                    Text("هیچ کد خطای فعالی گزارش نشده است", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun ErrorDetailCard(detail: WhatsminerErrorDetail) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF44336).copy(alpha = 0.07f))) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Cancel, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "${detail.code}  ${detail.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text("راه‌حل:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                    Text(detail.solution, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ==================================================================================
// بخش درآمد تخمینی (مشترک بین کارت‌ها)
// ==================================================================================
@Composable
fun IncomeSection(
    miner: MinerInfo,
    btcPriceUsdt: Double?,
    btcPriceToman: Long?,
    usdToToman: Long?,
    priceSource: String?,
    networkHashrateEh: Double = 930.0
) {
    val dailyBtc = miner.estimatedDailyBtc(networkHashrateEh)
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
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("روزانه", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                IncomeRow(label = "USDT", value = dailyUsdt?.let { "%.2f".format(it) } ?: "—", color = Color(0xFF4CAF50))
                IncomeRow(label = "تومان", value = dailyToman?.let { formatToman(it) } ?: "—", color = Color(0xFF2196F3))
                IncomeRow(label = "BTC", value = "%.8f".format(dailyBtc), color = Color(0xFFF7931A))
            }

            Divider(modifier = Modifier.width(1.dp).height(80.dp), color = MaterialTheme.colorScheme.outlineVariant)

            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ماهانه", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                IncomeRow(label = "USDT", value = monthlyUsdt?.let { "%.2f".format(it) } ?: "—", color = Color(0xFF4CAF50))
                IncomeRow(label = "تومان", value = monthlyToman?.let { formatToman(it) } ?: "—", color = Color(0xFF2196F3))
                IncomeRow(label = "BTC", value = "%.6f".format(monthlyBtc), color = Color(0xFFF7931A))
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Divider(color = Color(0xFFF7931A).copy(alpha = 0.2f))
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (usdToToman != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AttachMoney, contentDescription = null, modifier = Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    Text(" دلار: ${formatNumber(usdToToman.toInt())} ت", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
            priceSource?.let {
                Text("منبع: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 9.sp)
            }
        }
        Text(
            "* بر اساس GHSav | بدون احتساب هزینه برق",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
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
    // تصاویر هش‌برد که کاربر ارسال کرد فقط برای شماره‌های ۱ تا ۳ برچسب دارند؛ اگر دستگاهی بیشتر از
    // ۳ هش‌برد داشت (مدل‌های بزرگ‌تر)، تصاویر به‌صورت چرخشی دوباره استفاده می‌شوند
    val displayNumber = (board.id % 3) + 1
    val imageRes = when (displayNumber) {
        1 -> R.drawable.hashboard_1
        2 -> R.drawable.hashboard_2
        else -> R.drawable.hashboard_3
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = imageRes),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("برد $displayNumber", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            Text(board.hashrateGhs?.let { "%.1f GH/s".format(it) } ?: "—", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2196F3))
            Text(board.temperaturePcb?.let { "%.0f°C".format(it) } ?: "—", style = MaterialTheme.typography.bodySmall, color = tempColor(board.temperaturePcb))
            Text(board.effectiveChips?.let { "$it چیپ" } ?: "—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            board.status?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = if (it.lowercase().contains("alive") || it == "1") Color(0xFF4CAF50) else Color(0xFFF44336))
            }
        }
    }
}

@Composable
fun StatChip(label: String, value: String, color: Color = Color.Unspecified, icon: Int? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                tint = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
        }
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * زمان فعالیت (uptime) را به‌صورت چند بخش جداگانه (روز/ساعت/دقیقه) نمایش می‌دهد
 * تا اعداد لاتین و کلمات فارسی در یک رشته با هم قاطی نشوند و به‌هم نریزند (مشکل بایدای RTL/LTR)
 */
@Composable
fun UptimeChip(miner: MinerInfo) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val secs = miner.elapsedSeconds
        if (secs == null) {
            Text("—", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        } else {
            val days = secs / 86400
            val hours = (secs % 86400) / 3600
            val minutes = (secs % 3600) / 60
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (days > 0) {
                    Text("$days", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("روز", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 2.dp))
                }
                Text("$hours", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("ساعت", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 2.dp))
                Text("$minutes", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("دقیقه", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 2.dp))
            }
        }
        Text("زمان فعالیت", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun tempColor(temp: Double?): Color {
    return when {
        temp == null -> Color.Unspecified
        temp >= 85 -> Color(0xFFF44336)
        temp >= 75 -> Color(0xFFFF9800)
        temp >= 65 -> Color(0xFF8BC34A)
        else -> Color(0xFF4CAF50)
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
