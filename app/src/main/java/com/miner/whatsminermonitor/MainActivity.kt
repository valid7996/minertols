package com.miner.whatsminermonitor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.miner.whatsminermonitor.model.HashboardInfo
import com.miner.whatsminermonitor.model.MinerInfo
import com.miner.whatsminermonitor.model.MinerDiagnostics
import com.miner.whatsminermonitor.model.PoolEntry
import com.miner.whatsminermonitor.model.PoolProfile
import com.miner.whatsminermonitor.model.PoolProfiles
import com.miner.whatsminermonitor.model.WhatsminerErrorDetail
import com.miner.whatsminermonitor.network.LuciMinerClient
import com.miner.whatsminermonitor.network.PrivilegedResult
import com.miner.whatsminermonitor.network.WhatsminerClient
import com.miner.whatsminermonitor.ui.CredentialsStore
import com.miner.whatsminermonitor.ui.MinerViewModel
import com.miner.whatsminermonitor.ui.theme.WhatsminerMonitorTheme
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WhatsminerMonitorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    // پس‌زمینهٔ شفق متحرک پشت همهٔ صفحه‌ها
                    AnimatedAuroraBackground()
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Transparent
                    ) {
                        AppNavHost()
                    }
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
// کیت بصری مشترک
// ==================================================================================

/** پس‌زمینهٔ متحرک: هاله‌های نورانی رنگی که بسیار آرام روی زمینه حرکت می‌کنند */
@Composable
fun AnimatedAuroraBackground(modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val baseTop = if (dark) Color(0xFF0A0F16) else Color(0xFFF4F7FB)
    val baseBottom = if (dark) Color(0xFF0D1420) else Color(0xFFECF1F8)
    val orbAlpha = if (dark) 0.14f else 0.20f
    val cAmber = Color(0xFFFFB350)
    val cCyan = Color(0xFF38BDF8)
    val cViolet = Color(0xFF8B5CF6)

    val transition = rememberInfiniteTransition(label = "aurora")
    val a1 by transition.animateFloat(
        0f, (2f * PI).toFloat(),
        infiniteRepeatable(tween(26000, easing = LinearEasing)), label = "a1"
    )
    val a2 by transition.animateFloat(
        0f, (2f * PI).toFloat(),
        infiniteRepeatable(tween(34000, easing = LinearEasing)), label = "a2"
    )
    val a3 by transition.animateFloat(
        0f, (2f * PI).toFloat(),
        infiniteRepeatable(tween(41000, easing = LinearEasing)), label = "a3"
    )

    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(baseTop, baseBottom)))
        // هالهٔ کهربایی بالا-چپ
        val c1 = Offset(size.width * (0.22f + 0.10f * sin(a1)), size.height * (0.12f + 0.07f * cos(a1)))
        drawCircle(
            brush = Brush.radialGradient(listOf(cAmber.copy(alpha = orbAlpha), Color.Transparent), center = c1, radius = size.width * 0.55f),
            radius = size.width * 0.55f,
            center = c1
        )
        // هالهٔ فیروزه‌ای راست
        val c2 = Offset(size.width * (0.85f + 0.08f * cos(a2)), size.height * (0.30f + 0.10f * sin(a2)))
        drawCircle(
            brush = Brush.radialGradient(listOf(cCyan.copy(alpha = orbAlpha * 0.8f), Color.Transparent), center = c2, radius = size.width * 0.50f),
            radius = size.width * 0.50f,
            center = c2
        )
        // هالهٔ بنفش پایین
        val c3 = Offset(size.width * (0.45f + 0.12f * sin(a3)), size.height * (0.92f + 0.05f * cos(a3)))
        drawCircle(
            brush = Brush.radialGradient(listOf(cViolet.copy(alpha = orbAlpha * 0.7f), Color.Transparent), center = c3, radius = size.width * 0.60f),
            radius = size.width * 0.60f,
            center = c3
        )
    }
}

/** کارت با پس‌زمینهٔ نیمه‌شفاف و حاشیهٔ گرادیانی محو (حس شیشه‌ای روی پس‌زمینهٔ متحرک) */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    container: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.border(
            width = 1.dp,
            brush = Brush.linearGradient(
                listOf(borderColor, borderColor.copy(alpha = 0.10f), borderColor),
                start = Offset.Zero,
                end = Offset.Infinite
            ),
            shape = shape
        ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

/** نقطهٔ وضعیت تپنده (هالهٔ بیرونی نفس می‌کشد) */
@Composable
fun PulsingDot(color: Color, modifier: Modifier = Modifier, pulsing: Boolean = true, dotSize: Dp = 13.dp) {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "p")
    Box(modifier = modifier.size(dotSize), contentAlignment = Alignment.Center) {
        if (pulsing) {
            Box(
                Modifier
                    .size(dotSize)
                    .scale(1f + p * 1.4f)
                    .alpha((1f - p) * 0.55f)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Box(Modifier.size(dotSize * 0.62f).clip(CircleShape).background(color))
    }
}

/** درخشش رد شونده برای جای‌نگهدارِ بارگذاری */
@Composable
fun Modifier.shimmer(): Modifier {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "x")
    val base = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val hi = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    return drawBehind {
        drawRect(base)
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, hi, Color.Transparent),
                start = Offset(size.width * x, 0f),
                end = Offset(size.width * (x + 1f), size.height)
            )
        )
    }
}

/** تیتر بخش با نوار رنگی عمودی */
@Composable
fun SectionHeader(text: String, tint: Color = MaterialTheme.colorScheme.primary) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Box(
            Modifier
                .size(width = 4.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.verticalGradient(listOf(tint, tint.copy(alpha = 0.30f))))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = tint)
    }
}

// ==================================================================================
// صفحه اصلی: خلاصه (درآمد روزانه / گیج هشریت کل / تعداد ماینرها) + لیست دستگاه‌ها
// بازطراحی: هدر گرادیانی، پس‌زمینهٔ شفق متحرک، کارت‌های شیشه‌ای، FAB گرادیانی
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
    val networkEh = networkHashrateEh ?: 994.68
    val totalDailyUsdt = btcPriceUsdt?.let { price ->
        reachableMiners.sumOf { it.estimatedDailyBtc(networkEh) * price }
    }

    var showCalculator by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "مانیتور ماینرهای Whatsminer",
                        style = MaterialTheme.typography.titleLarge.copy(
                            brush = Brush.linearGradient(
                                listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
                                start = Offset.Zero,
                                end = Offset.Infinite
                            )
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "داشبورد پایش زندهٔ مزرعه",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // دکمهٔ محاسبه‌گر در قاب شیشه‌ای
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), CircleShape)
                ) {
                    IconButton(onClick = { showCalculator = true }) {
                        Icon(
                            Icons.Filled.Calculate,
                            contentDescription = "محاسبه‌گر سود استخراج",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            // FAB گرادیانی با درخشش
            val gradBrush = if (isScanning) {
                Brush.linearGradient(listOf(Color(0xFFFF7043), Color(0xFFE53935)))
            } else {
                Brush.linearGradient(listOf(Color(0xFFFFB350), Color(0xFFF57C00)))
            }
            Box(
                modifier = Modifier
                    .shadow(16.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(gradBrush)
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                    .clickable { if (isScanning) viewModel.stopScan() else viewModel.startScan() }
                    .padding(horizontal = 22.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isScanning) Icons.Filled.Stop else Icons.Filled.Search,
                        contentDescription = null,
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (isScanning) "توقف اسکن" else "اسکن شبکه",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // نوار وضعیت اسکن (با نشانگر چرخان در حالت اسکن زنده)
            ScanStatusBanner(status = status, isScanning = isScanning)

            SummaryHeader(
                dailyUsdt = totalDailyUsdt,
                totalThs = totalThs,
                minerCount = miners.size,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
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
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(miners, key = { it.ip }) { miner ->
                        MinerListItem(miner = miner, btcPriceUsdt = btcPriceUsdt, networkHashrateEh = networkEh, onOpen = { onOpenDetail(miner.ip) })
                    }
                    item { Spacer(modifier = Modifier.height(84.dp)) }
                }
            }
        }
    }

    if (showCalculator) {
        ProfitCalculatorDialog(
            liveBtcPriceUsdt = btcPriceUsdt,
            liveUsdToToman = viewModel.usdToToman.collectAsState().value,
            liveNetworkHashrateEh = networkEh,
            onDismiss = { showCalculator = false }
        )
    }
}

/**
 * نوار وضعیت اسکن: پیام وضعیت در یک کپسول ملایم رنگی؛ در حالت اسکن فعال یک
 * نشانگر چرخان کوچک کنار متن نشان داده می‌شود تا کاربر بفهمد اسکنر زنده است
 */
@Composable
fun ScanStatusBanner(status: String?, isScanning: Boolean) {
    AnimatedVisibility(
        visible = status != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        if (status != null) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isScanning) MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    )
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// ==================================================================================
// محاسبه‌گر سود استخراج (مستقل از دستگاه‌های اسکن‌شده) - هش‌ریت و کارمزد استخر دلخواه
// را وارد کنید تا سود تخمینی با همان قیمت زندهٔ برنامه محاسبه شود.
// فرمول (شامل کسر کارمزد استخر) از بررسی دقیق یک اپ مشابه استخراج و با چند مقدار تست عددی
// تأیید شده: dailyBtc = (1 - fee/100) * (hashrate_THs / (networkHashrateEh * 1e6)) * 144 * 3.125
// ==================================================================================
@Composable
fun ProfitCalculatorDialog(
    liveBtcPriceUsdt: Double?,
    liveUsdToToman: Long?,
    liveNetworkHashrateEh: Double,
    onDismiss: () -> Unit
) {
    var hashrateInput by remember { mutableStateOf("120") }
    var feeInput by remember { mutableStateOf("2") }
    var priceInput by remember { mutableStateOf(liveBtcPriceUsdt?.let { "%.0f".format(it) } ?: "") }

    val hashrateThs = hashrateInput.toDoubleOrNull()?.takeIf { it > 0.0 }
    val feePercent = (feeInput.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 100.0)
    val priceUsdt = priceInput.toDoubleOrNull()?.takeIf { it > 0.0 } ?: liveBtcPriceUsdt

    val dailyBtc = hashrateThs?.let { ths ->
        val gross = (ths / (liveNetworkHashrateEh * 1_000_000.0)) * 144.0 * 3.125
        gross * (1.0 - feePercent / 100.0)
    }
    val dailyUsdt = dailyBtc?.let { btc -> priceUsdt?.let { price -> btc * price } }
    val dailyToman = dailyUsdt?.let { usd -> liveUsdToToman?.let { rate -> (usd * rate).roundToLong() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("محاسبه‌گر سود استخراج") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "هش‌ریت و کارمزد استخر خودتان را وارد کنید؛ سود با قیمت زندهٔ فعلی محاسبه می‌شود (بدون احتساب هزینه برق)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = hashrateInput,
                    onValueChange = { hashrateInput = it },
                    label = { Text("هش‌ریت (TH/s)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = feeInput,
                    onValueChange = { feeInput = it },
                    label = { Text("کارمزد استخر (٪)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = priceInput,
                    onValueChange = { priceInput = it },
                    label = { Text("قیمت بیت‌کوین (USD)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text("پیش‌فرض از قیمت زندهٔ فعلی؛ قابل ویرایش دستی هم هست", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.fillMaxWidth()
                )

                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                if (dailyBtc != null) {
                    Text("سود روزانه", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    IncomeRow("بیت‌کوین", "%.8f".format(dailyBtc), Color(0xFFF7931A))
                    dailyUsdt?.let { IncomeRow("دلار", "$%.2f".format(it), Color(0xFF4CAF50)) }
                    dailyToman?.let { IncomeRow("تومان", formatToman(it) + " ت", Color(0xFF2196F3)) }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("سود ماهانه (۳۰ روز)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    IncomeRow("بیت‌کوین", "%.8f".format(dailyBtc * 30), Color(0xFFF7931A))
                    dailyUsdt?.let { IncomeRow("دلار", "$%.2f".format(it * 30), Color(0xFF4CAF50)) }
                    dailyToman?.let { IncomeRow("تومان", formatToman(it * 30) + " ت", Color(0xFF2196F3)) }
                } else {
                    Text(
                        "یک هش‌ریت معتبر (بزرگ‌تر از صفر) وارد کنید",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Text(
                    "* بدون احتساب هزینه برق. فرمول و مقدار پیش‌فرض کارمزد (۲٪) از یک محاسبه‌گر مشابه گرفته شده.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("بستن") }
        }
    )
}

@Composable
fun EmptyState() {
    // حلقه‌های رادار که مثل جستجوی شبکه بیرون می‌پرند
    val t = rememberInfiniteTransition(label = "radar")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "pulse")
    val ringColor = MaterialTheme.colorScheme.primary

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(300.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            for (i in 0..2) {
                val phase = (pulse + i / 3f) % 1f
                drawCircle(
                    color = ringColor.copy(alpha = (1f - phase) * 0.22f),
                    radius = size.minDimension * (0.12f + 0.38f * phase),
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_wifi_wait),
                    contentDescription = null,
                    modifier = Modifier.size(46.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "برای پیدا کردن ماینرها روی «اسکن شبکه» بزنید",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
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
// بازطراحی: کارت هیرو با گرادیان تیره، کاشی‌های شیشه‌ای، گیج درخشان متحرک
// ==================================================================================
@Composable
fun SummaryHeader(
    dailyUsdt: Double?,
    totalThs: Double,
    minerCount: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(26.dp))
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF151E2C), Color(0xFF18203A), Color(0xFF241B33)),
                    start = Offset.Zero,
                    end = Offset.Infinite
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(26.dp))
    ) {
        // واترمارک بیت‌کوین گوشهٔ کارت
        Icon(
            Icons.Filled.CurrencyBitcoin,
            contentDescription = null,
            tint = Color(0xFFFFB350).copy(alpha = 0.06f),
            modifier = Modifier.align(Alignment.TopEnd).size(110.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // درآمد روزانه
            HeroTile(
                icon = Icons.Filled.AttachMoney,
                tint = Color(0xFF4ADE80),
                value = dailyUsdt?.let { "$${"%.2f".format(it)}" } ?: "—",
                label = "درآمد روزانه",
                modifier = Modifier.weight(0.85f)
            )

            // گیج هشریت کل
            Column(
                modifier = Modifier.weight(1.3f),
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
                    color = Color(0xFFA9B4C6),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // تعداد کل ماینرها
            HeroTile(
                icon = Icons.Filled.Hub,
                tint = Color(0xFF38BDF8),
                value = "$minerCount",
                label = "کل ماینرها",
                modifier = Modifier.weight(0.85f)
            )
        }
    }
}

/** کاشی شیشه‌ای داخل کارت هیرو: آیکون داخل دایرهٔ رنگی + مقدار + برچسب */
@Composable
fun HeroTile(
    icon: ImageVector,
    tint: Color,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFFF4F7FC))
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFA9B4C6),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * گیج نیم‌دایره‌ای شبیه سرعت‌سنج که هشریت کل (TH/s) را نمایش می‌دهد
 * بازطراحی: عقربه با انیمیشن نرم، کمان گرادیانی، تیک‌های مقیاس، هالهٔ نورانی مرکز
 */
@Composable
fun SpeedGauge(valueThs: Double, modifier: Modifier = Modifier) {
    // مقیاس گیج را متناسب با مقدار فعلی، کمی بزرگ‌تر از مقدار تنظیم می‌کنیم
    val niceSteps = listOf(50.0, 100.0, 150.0, 200.0, 300.0, 400.0, 600.0, 800.0, 1000.0, 1500.0, 2000.0, 3000.0)
    val maxScale = niceSteps.firstOrNull { it >= valueThs * 1.25 } ?: (valueThs * 1.3).coerceAtLeast(50.0)
    val fraction = (valueThs / maxScale).coerceIn(0.0, 1.0)

    // انیمیشن نرم عقربه و عدد هنگام تغییر مقدار
    val animFraction by animateFloatAsState(
        targetValue = fraction.toFloat(),
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "gaugeFraction"
    )
    val animValue by animateFloatAsState(
        targetValue = valueThs.toFloat(),
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "gaugeValue"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(modifier = modifier) {
            val strokeWidth = size.height * 0.14f
            val radius = minOf(size.width / 2f, size.height) - strokeWidth / 2f
            val center = Offset(size.width / 2f, size.height)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val boxSize = Size(radius * 2f, radius * 2f)

            // هالهٔ نورانی پشت مرکز
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFFFFB350).copy(alpha = 0.28f), Color.Transparent),
                    center = center,
                    radius = radius * 0.55f
                ),
                radius = radius * 0.55f,
                center = center
            )

            // کمان پس‌زمینه (ریل خاکستری)
            drawArc(
                color = Color.White.copy(alpha = 0.10f),
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = topLeft,
                size = boxSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // تیک‌های مقیاس هر ۳۰ درجه
            for (i in 0..6) {
                val ang = Math.toRadians((180.0 + i * 30.0))
                val cosA = cos(ang).toFloat()
                val sinA = sin(ang).toFloat()
                val tick = strokeWidth * 0.42f
                drawLine(
                    color = Color.White.copy(alpha = 0.14f),
                    start = Offset(center.x + radius * cosA, center.y + radius * sinA),
                    end = Offset(center.x + (radius - tick) * cosA, center.y + (radius - tick) * sinA),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round
                )
            }

            // کمان مقدار: با چرخش ۱۸۰ درجه گرادیان sweep دقیقاً از ابتدای کمان شروع می‌شود
            rotate(degrees = 180f, pivot = center) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Color(0xFF38BDF8),
                        0.45f to Color(0xFF4ADE80),
                        0.75f to Color(0xFFFFB350),
                        1f to Color(0xFFF43F5E),
                        center = Offset(radius, radius)
                    ),
                    startAngle = 0f,
                    sweepAngle = animFraction * 180f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = boxSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            // عقربه
            val needleAngleDeg = 180.0 + animFraction * 180.0
            val needleAngleRad = Math.toRadians(needleAngleDeg)
            val needleLength = radius - strokeWidth
            val needleEnd = Offset(
                x = center.x + (needleLength * cos(needleAngleRad)).toFloat(),
                y = center.y + (needleLength * sin(needleAngleRad)).toFloat()
            )
            drawLine(
                color = Color(0xFFFFB350),
                start = center,
                end = needleEnd,
                strokeWidth = strokeWidth * 0.3f,
                cap = StrokeCap.Round
            )
            // مرکز عقربه
            drawCircle(color = Color(0xFFFFB350), radius = strokeWidth * 0.55f, center = center)
            drawCircle(color = Color(0xFF101826), radius = strokeWidth * 0.30f, center = center)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "%.1f".format(animValue),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF4F7FC)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                "TH/s",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFFFB350),
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
// کارت مدرن ماینر در لیست اصلی + دکمه باز کردن جزئیات
// بازطراحی: افکت فشرده‌شدن هنگام لمس، حاشیهٔ گرادیانی هم‌رنگ وضعیت، نقطهٔ تپنده،
// شیمر بارگذاری، چیپ‌های آماری نرم‌تر
// (ردیف موقت بین «پورت باز شد» و «اطلاعات کامل خوانده شد» با نشانگر «در حال خواندن» نمایش داده می‌شود)
// ==================================================================================
@Composable
fun MinerListItem(miner: MinerInfo, btcPriceUsdt: Double?, networkHashrateEh: Double = 994.68, onOpen: () -> Unit) {
    val dailyUsdt = btcPriceUsdt?.let { miner.estimatedDailyBtc(networkHashrateEh) * it }
    // دستگاهی که تازه پورتش باز شده ولی هنوز queryMiner کامل نشده (ردیف موقت اسکنر)
    val isLoading = miner.isReachable && miner.minerType == null && miner.poolWorkerName == null

    val accentColor = when {
        !miner.isReachable -> MaterialTheme.colorScheme.error
        isLoading -> Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.primary
    }

    // افکت فشرده شدن هنگام لمس
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressAnim by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")

    val borderColor = when {
        !miner.isReachable -> MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
        isLoading -> Color(0xFFFF9800).copy(alpha = 0.45f)
        miner.isHealthy -> MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)
        else -> MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(pressAnim)
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(borderColor, borderColor.copy(alpha = 0.12f), borderColor),
                    start = Offset.Zero,
                    end = Offset.Infinite
                ),
                RoundedCornerShape(20.dp)
            )
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = miner.isReachable && !isLoading
            ) { onOpen() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // کادر رنگی آیکون دستگاه + نقطهٔ تپندهٔ وضعیت گوشهٔ آن
            Box(modifier = Modifier.size(56.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(accentColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    MinerDeviceIcon(
                        modifier = Modifier.size(36.dp),
                        tint = accentColor
                    )
                }
                PulsingDot(
                    color = when {
                        !miner.isReachable -> Color(0xFFF44336)
                        isLoading -> Color(0xFFFF9800)
                        miner.isHealthy -> Color(0xFF4CAF50)
                        else -> Color(0xFFF44336)
                    },
                    pulsing = miner.isReachable && !isLoading,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!miner.isReachable) {
                    Text(
                        miner.errorMessage ?: "پاسخ دریافت نشد",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2
                    )
                    Text(
                        miner.ip,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                } else if (isLoading) {
                    Text(
                        miner.ip,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = Color(0xFFFF9800)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "در حال خواندن اطلاعات از دستگاه...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // نوار شیمر جای‌نگهدار اطلاعات در حال خواندن
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.65f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .shimmer()
                    )
                } else {
                    Text(
                        miner.minerType ?: "WhatsMiner",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        miner.poolWorkerName ?: "Worker: —",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MetricChip(
                            icon = Icons.Filled.Speed,
                            text = miner.ghsAverageThs?.let { "%.1f TH/s".format(it) } ?: "—",
                            tint = Color(0xFF2196F3)
                        )
                        miner.averageTemperature?.let { temp ->
                            MetricChip(
                                icon = Icons.Filled.DeviceThermostat,
                                text = "%.1f°C".format(temp),
                                tint = tempColor(temp)
                            )
                        }
                    }
                    Text(
                        "IP: ${miner.ip}  •  MAC: ${miner.macAddress ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            if (miner.isReachable && !isLoading) {
                Spacer(modifier = Modifier.width(8.dp))
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        dailyUsdt?.let { "$${"%.2f".format(it)}" } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
                    )
                    Text(
                        "درآمد روزانه",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HealthBadge(miner = miner, compact = true)
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "باز کردن",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** چیپ آماری کوچک (هش‌ریت/دما/...) با پس‌زمینه ملایم هم‌رنگ */
@Composable
fun MetricChip(icon: ImageVector, text: String, tint: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(tint.copy(alpha = 0.13f))
            .border(1.dp, tint.copy(alpha = 0.22f), RoundedCornerShape(9.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(11.dp), tint = tint)
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1
        )
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
 * نشان سلامت دستگاه به‌صورت کپسول رنگی: اگر کد خطای فعالی نداشته باشد «عالی»
 * و در غیر این صورت «اخطار (تعداد)»
 */
@Composable
fun HealthBadge(miner: MinerInfo, compact: Boolean = false) {
    val healthy = miner.isHealthy
    val color = if (healthy) Color(0xFF4CAF50) else Color(0xFFF44336)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (healthy) Icons.Filled.Favorite else Icons.Filled.Warning,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(if (compact) 11.dp else 13.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            if (healthy) "عالی" else "اخطار (${miner.errorCodes.size})",
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

// ==================================================================================
// صفحه جزئیات دستگاه
// بازطراحی: هدر هیروی گرادیانی، کارت‌های شیشه‌ای، تیترهای نوار رنگی،
// نوار پیشرفت هش‌برد و فن، تایل راندمان (J/TH) و نمایش آدرس استخر
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
    // مرحله اول بعد از انتخاب پروفایل پول: گرفتن Worker و رمز پول از کاربر
    var poolCredentialsPending by remember { mutableStateOf<PoolProfile?>(null) }
    // مرحله دوم: تایید نهایی با اطلاعاتی که کاربر وارد کرده
    var poolPendingConfirm by remember { mutableStateOf<PendingPoolSwitch?>(null) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<PendingPrivilegedAction?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    suspend fun runPrivileged(action: PendingPrivilegedAction, ipAddr: String) {
        isBusy = true
        val password = CredentialsStore.getPassword(context, ipAddr)
        val username = CredentialsStore.DEFAULT_USERNAME

        // Worker و رمز پول همانی است که کاربر در دیالوگ «اطلاعات پول» وارد کرده (پسورد خالی -> "123")
        fun poolEntries(switchAction: PendingPrivilegedAction.SwitchPool): List<PoolEntry> =
            switchAction.profile.addresses.map { PoolEntry(url = it, worker = switchAction.workerName, pass = switchAction.poolPassword) }

        // روش اول: پنل وب مدیریت دستگاه (LuCI روی HTTPS) - طبق بررسی یک اپ مشابه که واقعاً روی
        // دستگاه‌های واقعی کار می‌کند، این همان راهی است که ریبوت/تغییر پول واقعاً از آن انجام می‌شود
        val luciResult: LuciMinerClient.LuciResult = when (action) {
            is PendingPrivilegedAction.Reboot -> LuciMinerClient.reboot(ipAddr, username, password)
            is PendingPrivilegedAction.SwitchPool -> LuciMinerClient.updatePools(ipAddr, username, password, poolEntries(action))
        }

        var success = luciResult.success
        var message = luciResult.message
        var wrongPassword = luciResult.wrongPassword

        // روش دوم (پشتیبان): اگر پنل وب در دسترس نبود (نه به‌خاطر رمز)، از API خام دستگاه امتحان می‌شود
        if (!success && !wrongPassword) {
            val tcpResult: PrivilegedResult = when (action) {
                is PendingPrivilegedAction.Reboot -> WhatsminerClient.reboot(ipAddr, password)
                is PendingPrivilegedAction.SwitchPool -> WhatsminerClient.updatePools(ipAddr, password, poolEntries(action))
            }
            if (tcpResult.success) {
                success = true
                message = tcpResult.message
            } else if (tcpResult.wrongPassword) {
                wrongPassword = true
                message = tcpResult.message
            }
            // اگر روش دوم هم شکست خورد ولی نه به‌خاطر رمز، همان پیام روش اول (LuCI) نگه داشته می‌شود
        }

        isBusy = false
        if (wrongPassword && !success) {
            pendingAction = action
            showPasswordDialog = true
            snackbarHostState.showSnackbar("رمز عبور اشتباه است. رمز صحیح دستگاه را وارد کنید.")
        } else {
            snackbarHostState.showSnackbar(message)
            if (success) viewModel.refreshMiner(ipAddr)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(miner?.poolWorkerName ?: miner?.minerType ?: "جزئیات دستگاه", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                },
                actions = {
                    if (miner != null) {
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
                .padding(16.dp)
        ) {
            // ===== هدر هیرو: آیکون دستگاه + نام Worker/مدل + نشان سلامت =====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(14.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF151E2C), Color(0xFF1A2340), Color(0xFF231B33)),
                            start = Offset.Zero,
                            end = Offset.Infinite
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(24.dp))
            ) {
                Icon(
                    Icons.Filled.CurrencyBitcoin,
                    contentDescription = null,
                    tint = Color(0xFFFFB350).copy(alpha = 0.05f),
                    modifier = Modifier.align(Alignment.BottomEnd).size(96.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        MinerDeviceIcon(modifier = Modifier.size(40.dp), tint = Color(0xFFFFB350))
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            miner.poolWorkerName ?: "Worker: —",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color(0xFFF4F7FC),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            miner.minerType ?: "WhatsMiner",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFA9B4C6)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        HealthBadge(miner = miner)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ===== کارت شبکه: IP و MAC و استخر =====
            GlassCard {
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
                    // آدرس استخر متصل (اگر دستگاه گزارش کرده باشد)
                    miner.poolUrl?.let { poolUrl ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "استخر: $poolUrl",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ===== اطلاعات دستگاه: فریمور / کنترل‌برد / پاور / مدل =====
            SectionHeader("اطلاعات دستگاه")
            GlassCard(shape = RoundedCornerShape(16.dp), container = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    DetailField(label = "فریمور", value = miner.firmwareVersion?.take(14) ?: "—", modifier = Modifier.weight(1f))
                    DetailField(label = "کنترل‌برد", value = miner.controlBoard ?: "—", modifier = Modifier.weight(1f))
                    DetailField(label = "پاور", value = miner.powerSupplyModel ?: "—", modifier = Modifier.weight(1f))
                    DetailField(label = "مدل", value = miner.minerType ?: "—", modifier = Modifier.weight(1f))
                }
            }

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

            // ===== اکسپت‌ها / رجکت‌ها / توان / راندمان =====
            SectionHeader("وضعیت استخراج")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatChip(label = "اکسپت‌ها", value = miner.accepted?.let { formatNumber(it) } ?: "—", color = Color(0xFF4CAF50))
                StatChip(
                    label = "رجکت‌ها",
                    value = miner.rejected?.let { formatNumber(it) } ?: "—",
                    color = if ((miner.rejected ?: 0) > 0) MaterialTheme.colorScheme.error else Color.Unspecified
                )
                StatChip(label = "توان", value = miner.powerWatt?.let { "$it W" } ?: "—", color = Color(0xFFFF9800), iconVec = Icons.Filled.FlashOn)
                // راندمان انرژی: وات به ازای هر TH/s (هرچه کمتر بهتر)
                StatChip(
                    label = "راندمان",
                    value = miner.efficiencyJPerThs?.let { "%.1f J/TH".format(it) } ?: "—",
                    color = Color(0xFF38BDF8),
                    iconVec = Icons.Filled.Bolt
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ===== دما و فن =====
            SectionHeader("دما و فن")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatChip(
                    label = "دمای میانگین",
                    value = miner.averageTemperature?.let { "%.1f°C".format(it) } ?: "—",
                    color = tempColor(miner.averageTemperature)
                )
                StatChip(
                    label = "فن جلو (ورودی)",
                    value = miner.fanSpeedIn?.let { "$it RPM" } ?: "—",
                    iconVec = Icons.Filled.Air,
                    color = Color(0xFF38BDF8)
                )
                StatChip(
                    label = "فن عقب (خروجی)",
                    value = miner.fanSpeedOut?.let { "$it RPM" } ?: "—",
                    iconVec = Icons.Filled.Air,
                    color = Color(0xFFB69DFF)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ===== عملیات دستگاه: ریبوت / تغییر پول =====
            SectionHeader("عملیات دستگاه")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { showRebootConfirm = true },
                    enabled = !isBusy,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ریبوت دستگاه")
                }
                OutlinedButton(
                    onClick = { showPoolPicker = true },
                    enabled = !isBusy,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تغییر پول")
                }
            }
            if (isBusy) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            Text(
                "رمز دستگاه پیش‌فرض «admin» در نظر گرفته می‌شود؛ اگر تغییر کرده باشد به‌صورت خودکار برای وارد کردن رمز صحیح از شما سؤال می‌شود.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )

            if (miner.hashboards.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                SectionHeader("هش‌بردها")
                val maxBoardGhs = miner.hashboards.mapNotNull { it.hashrateGhs }.maxOrNull()
                miner.hashboards.forEach { HashboardRow(it, maxBoardGhs) }
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
                networkHashrateEh = networkHashrateEh ?: 994.68
            )

            Spacer(modifier = Modifier.height(14.dp))
            DiagnosticsSection(minerIp = miner.ip)

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

    // ===== دیالوگ انتخاب پروفایل پول (قسمت اول) =====
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
                                poolCredentialsPending = profile
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

    // ===== دیالوگ Worker و رمز پول (قسمت دوم - بعد از انتخاب پول) =====
    poolCredentialsPending?.let { profile ->
        var workerInput by remember(profile) { mutableStateOf(miner.poolWorkerName ?: "") }
        var passwordInput by remember(profile) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { poolCredentialsPending = null },
            title = { Text("اطلاعات پول «${profile.displayName}»") },
            text = {
                Column {
                    Text(
                        "Worker و رمز پول را وارد کنید:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = workerInput,
                        onValueChange = { workerInput = it },
                        label = { Text("Pool1 Worker") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Pool1 Password") },
                        placeholder = { Text("خالی = پیش‌فرض 123") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "اگر رمز پول را خالی بگذارید، به‌صورت پیش‌فرض «123» تنظیم می‌شود.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val worker = workerInput.trim()
                        val pass = passwordInput.trim().ifBlank { "123" }
                        poolCredentialsPending = null
                        poolPendingConfirm = PendingPoolSwitch(profile = profile, workerName = worker, poolPassword = pass)
                    },
                    enabled = workerInput.isNotBlank()
                ) { Text("بعدی") }
            },
            dismissButton = {
                TextButton(onClick = { poolCredentialsPending = null }) { Text("انصراف") }
            }
        )
    }

    // ===== دیالوگ تایید تغییر پول (هشدار نهایی) =====
    poolPendingConfirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { poolPendingConfirm = null },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("توجه: تغییر پول ماینینگ") },
            text = {
                Column {
                    Text(
                        "با تایید این عملیات، پول ماینینگ این دستگاه فوراً به «${pending.profile.displayName}» تغییر می‌کند و ماینینگ فعلی قطع و به پول جدید متصل می‌شود."
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Worker: ${pending.workerName}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Text("Password: ${pending.poolPassword}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = pending
                    poolPendingConfirm = null
                    scope.launch {
                        runPrivileged(
                            PendingPrivilegedAction.SwitchPool(
                                profile = p.profile,
                                workerName = p.workerName,
                                poolPassword = p.poolPassword
                            ),
                            miner.ip
                        )
                    }
                }) { Text("بله، تغییر بده", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { poolPendingConfirm = null }) { Text("انصراف") }
            }
        )
    }

    // ===== دیالوگ وارد کردن رمز عبور (وقتی رمز پیش‌فرض admin کار نکند) =====
    if (showPasswordDialog) {
        var passwordInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            title = { Text("رمز عبور دستگاه") },
            text = {
                Column {
                    Text("رمز فعلی روی این دستگاه کار نکرد. رمز صحیح ادمین دستگاه را وارد کنید؛ همین رمز روی گوشی ذخیره می‌شود و برای همهٔ ماینرهای دیگر هم امتحان خواهد شد، پس لازم نیست دوباره برای هر دستگاه وارد کنید:")
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("رمز عبور") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        CredentialsStore.setPassword(context, miner.ip, passwordInput)
                        showPasswordDialog = false
                        val action = pendingAction
                        pendingAction = null
                        if (action != null) {
                            scope.launch { runPrivileged(action, miner.ip) }
                        }
                    },
                    enabled = passwordInput.isNotBlank()
                ) { Text("تایید و تلاش مجدد") }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = false; pendingAction = null }) { Text("انصراف") }
            }
        )
    }
}

private sealed class PendingPrivilegedAction {
    object Reboot : PendingPrivilegedAction()
    data class SwitchPool(val profile: PoolProfile, val workerName: String, val poolPassword: String) : PendingPrivilegedAction()
}

/** اطلاعاتی که کاربر در دیالوگ «Pool1 Worker / Pool1 Password» وارد کرده، تا دیالوگ تایید نهایی نشانش دهد */
private data class PendingPoolSwitch(val profile: PoolProfile, val workerName: String, val poolPassword: String)

@Composable
fun DetailField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(horizontal = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFF9800).copy(alpha = 0.10f)),
            border = BorderStroke(1.dp, Color(0xFFFF9800).copy(alpha = 0.30f))
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
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50).copy(alpha = 0.10f)),
            border = BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.30f))
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
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF44336).copy(alpha = 0.07f)),
        border = BorderStroke(1.dp, Color(0xFFF44336).copy(alpha = 0.25f))
    ) {
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
    networkHashrateEh: Double = 994.68
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
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFF7931A).copy(alpha = 0.08f))
            .border(1.dp, Color(0xFFF7931A).copy(alpha = 0.25f), RoundedCornerShape(18.dp))
            .padding(12.dp)
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

        // شفافیت محاسبه: دقیقاً همان چیزی که در محاسبه استفاده شده نشان داده می‌شود (قیمت،
        // هشریت شبکه، پاداش بلاک، هشریت خود دستگاه) تا مقدار قابل بررسی و اعتماد باشد
        val usedHashrateThs = miner.ghsAverageThs ?: miner.totalHashrateThs
        var showDetails by remember { mutableStateOf(false) }
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDetails = !showDetails },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (showDetails) "پنهان کردن جزئیات محاسبه ▲" else "جزئیات محاسبه ▼",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFF7931A).copy(alpha = 0.8f)
            )
        }
        if (showDetails) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                CalcDetailRow("هشریت این دستگاه", usedHashrateThs?.let { "%.2f TH/s".format(it) } ?: "—")
                CalcDetailRow("هشریت کل شبکه", "%.2f EH/s".format(networkHashrateEh))
                CalcDetailRow("پاداش هر بلاک", "3.125 BTC")
                CalcDetailRow("قیمت محاسبه", btcPriceUsdt?.let { "$%.2f".format(it) } ?: "—")
                CalcDetailRow("منبع قیمت", priceSource ?: "—")
            }
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

// یک ردیف در پنل «جزئیات محاسبه»: نشان می‌دهد دقیقاً کدام مقادیر برای محاسبهٔ درآمد استفاده شدند
@Composable
fun CalcDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
    }
}

@Composable
fun HashboardRow(board: HashboardInfo, maxGhs: Double? = null) {
    // تصاویر هش‌برد که کاربر ارسال کرد فقط برای شماره‌های ۱ تا ۳ برچسب دارند؛ اگر دستگاهی بیشتر از
    // ۳ هش‌برد داشت (مدل‌های بزرگ‌تر)، تصاویر به‌صورت چرخشی دوباره استفاده می‌شوند
    val displayNumber = (board.id % 3) + 1
    val imageRes = when (displayNumber) {
        1 -> R.drawable.hashboard_1
        2 -> R.drawable.hashboard_2
        else -> R.drawable.hashboard_3
    }
    // نسبت هشریت این برد به پرکارترین برد، برای نوار پیشرفت
    val fraction = if (maxGhs != null && maxGhs > 0.0 && board.hashrateGhs != null) {
        ((board.hashrateGhs!! / maxGhs).coerceIn(0.0, 1.0)).toFloat()
    } else 0f

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = imageRes),
                        contentDescription = null,
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("برد $displayNumber", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                board.status?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulsingDot(
                            color = if (it.lowercase().contains("alive") || it == "1") Color(0xFF4CAF50) else Color(0xFFF44336),
                            pulsing = it.lowercase().contains("alive") || it == "1",
                            dotSize = 9.dp
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (it.lowercase().contains("alive") || it == "1") Color(0xFF4CAF50) else Color(0xFFF44336)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // نوار پیشرفت هشریت برد
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                            )
                        )
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(board.hashrateGhs?.let { "%.1f GH/s".format(it) } ?: "—", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2196F3), fontWeight = FontWeight.SemiBold)
                Text(board.temperaturePcb?.let { "%.0f°C".format(it) } ?: "—", style = MaterialTheme.typography.bodySmall, color = tempColor(board.temperaturePcb))
                Text(board.effectiveChips?.let { "$it چیپ" } ?: "—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * تایل آماری شیشه‌ای (اکسپت/رجکت/توان/دما/فن/...)
 */
@Composable
fun StatChip(
    label: String,
    value: String,
    color: Color = Color.Unspecified,
    icon: Int? = null,
    iconVec: ImageVector? = null
) {
    val valueColor = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface
    val iconTint = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
        } else if (iconVec != null) {
            Icon(
                iconVec,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
        }
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor
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

@Composable
fun DiagnosticsSection(minerIp: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showRawDialog by remember { mutableStateOf(false) }
    var rawText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.BugReport, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Diagnostics", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "If values are missing, copy raw API responses and send to support for parser fix.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        rawText = MinerDiagnostics.getLastRawForShare(minerIp)
                        showRawDialog = true
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Show raw", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        val txt = MinerDiagnostics.getLastRawForShare(minerIp)
                        clipboard.setText(AnnotatedString(txt))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy raw", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            val resolutions = remember(minerIp) { MinerDiagnostics.getFieldResolutions(minerIp) }
            if (resolutions.isNotEmpty()) {
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(6.dp))
                Text("Field report (IP -> endpoint -> key -> raw -> parsed -> final):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                for (r in resolutions.take(20)) {
                    Text(
                        r.logLine(),
                        style = MaterialTheme.typography.labelSmall,
                        color = when (r.status) {
                            MinerDiagnostics.FieldResolution.Status.OK, MinerDiagnostics.FieldResolution.Status.ZERO -> Color(0xFF4CAF50)
                            MinerDiagnostics.FieldResolution.Status.MISSING -> Color(0xFF9E9E9E)
                            else -> Color(0xFFF44336)
                        },
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
    if (showRawDialog) {
        AlertDialog(
            onDismissRequest = { showRawDialog = false },
            title = { Text("Raw API responses") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(rawText, style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(rawText))
                    showRawDialog = false
                }) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = { showRawDialog = false }) { Text("Close") }
            }
        )
    }
}

fun formatNumber(n: Int): String =
    NumberFormat.getNumberInstance(Locale.US).format(n)

fun formatToman(n: Long): String {
    return when {
        n >= 1_000_000_000 -> "%.2f B".format(n / 1_000_000_000.0)
        n >= 1_000_000 -> "%.0f M".format(n / 1_000_000.0)
        else -> NumberFormat.getNumberInstance(Locale.US).format(n)
    }
}
