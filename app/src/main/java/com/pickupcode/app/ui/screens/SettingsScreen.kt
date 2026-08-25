package com.pickupcode.app.ui.screens

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pickupcode.app.BuildConfig
import com.pickupcode.app.learner.PatternLearner
import com.pickupcode.app.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val TAG = "SettingsScreen"

// 柔和雾蓝：开关激活 / 滑块 / 统计按钮强调（沿用原设置页强调色）
private val ValBlue = Color(0xFF8DC0E0)
private val ValBlueLight = Color(0xFFBBD8EC)

/** 各 section 共享的上下文，避免每个私有 Composable 传一堆参数 */
private data class SettingsCtx(
    val ctx: Context,
    val scope: CoroutineScope,
    val s: AppPreferences.Settings,
    val apiUrl: String,
    val apiKey: String,
    val apiModel: String,
    val amapApiKey: String,
    val kuaidi100Key: String,
    val onApiUrlChange: (String) -> Unit,
    val onApiKeyChange: (String) -> Unit,
    val onApiModelChange: (String) -> Unit,
    val onAmapApiKeyChange: (String) -> Unit,
    val onKuaidi100KeyChange: (String) -> Unit,
    val onSmsEnable: (Boolean) -> Unit
) {
    /** 开关保存：把 Boolean 回调包进 IO 协程，消除每个 SettingsSwitch 的 launch 样板 */
    fun save(block: suspend (Boolean) -> Unit): (Boolean) -> Unit = { value ->
        saveRun { block(value) }
    }

    /** 一次性保存：无参 suspend 块 + IO 调度（Slider/按钮/防抖字段用）；捕获写异常防崩溃 */
    fun saveRun(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            runCatching { block() }
                .onFailure { Log.w(TAG, "设置写入失败", it) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onStatsClick: () -> Unit = {}) {
    val ctx = LocalContext.current
    // 进程级 scope：防抖落盘/权限回调等 fire-and-forget 写不随页面销毁取消（否则 400ms 内返回会丢 Key/URL）
    val scope = com.pickupcode.app.App.appScope
    val settingsFlow = remember { AppPreferences.observe(ctx) }
    val s by settingsFlow.collectAsState(initial = AppPreferences.Settings())

    var apiUrl by remember { mutableStateOf(s.apiBaseUrl) }
    var apiKey by remember { mutableStateOf(s.apiKey) }
    var apiModel by remember { mutableStateOf(s.apiModel) }
    var amapApiKey by remember { mutableStateOf(s.amapApiKey) }
    var kuaidi100Key by remember { mutableStateOf(s.kuaidi100Key) }

    // 短信权限启动器：拒绝后开关自动回退
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch(Dispatchers.IO) { AppPreferences.setEnableSmsReceive(ctx, true) }
        } else {
            Toast.makeText(ctx, "短信权限被拒绝，无法自动识别取件码", Toast.LENGTH_SHORT).show()
        }
    }

    // DataStore 异步加载真实值后回填一次，避免已配置的 Key 在重启后显示为空（M1）
    LaunchedEffect(Unit) {
        AppPreferences.observe(ctx).first().let {
            apiUrl = it.apiBaseUrl; apiKey = it.apiKey; apiModel = it.apiModel
            amapApiKey = it.amapApiKey; kuaidi100Key = it.kuaidi100Key
        }
    }

    // remember 缓存：SettingsCtx 含函数字段（lambda），每次重建会导致 equals 恒不等，
    // 使全部 section 每次重组（滚动/动画卡顿的根源）。键用实际可变状态，状态未变时引用稳定。
    val sc = remember(s, apiUrl, apiKey, apiModel, amapApiKey, kuaidi100Key) {
        SettingsCtx(
            ctx = ctx, scope = scope, s = s,
            apiUrl = apiUrl, apiKey = apiKey, apiModel = apiModel,
            amapApiKey = amapApiKey, kuaidi100Key = kuaidi100Key,
            onApiUrlChange = { apiUrl = it },
            onApiKeyChange = { apiKey = it },
            onApiModelChange = { apiModel = it },
            onAmapApiKeyChange = { amapApiKey = it },
            onKuaidi100KeyChange = { kuaidi100Key = it },
            onSmsEnable = { enable ->
                if (enable) {
                    // 开启时先检查权限，未授权则发起请求；已授权直接保存
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        scope.launch(Dispatchers.IO) {
                            runCatching { AppPreferences.setEnableSmsReceive(ctx, true) }
                                .onFailure { Log.w(TAG, "短信开关保存失败", it) }
                        }
                    } else {
                        smsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
                    }
                } else {
                    scope.launch(Dispatchers.IO) {
                        runCatching { AppPreferences.setEnableSmsReceive(ctx, false) }
                            .onFailure { Log.w(TAG, "短信开关保存失败", it) }
                    }
                }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background, // 与主页背景一致（跟随主题）
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { pad ->
        // 只有 6 个固定卡片：LazyColumn 的懒加载/测量管理是多余开销，改用 Column + verticalScroll
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RecognitionSettingsSection(sc)
            InputMethodsSection(sc)
            NotificationStatusCard(sc)
            VerifyServicesSection(sc)
            LearningStatsSection(sc, onStatsClick)
            AppearanceSection(sc)
            AboutSection(sc)
        }
    }
}

/* ═══════════════════ 公共组件（视觉重构：分组卡片化） ═══════════════════ */

@Composable
private fun SettingsSectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        // 滚动列表里的阴影每帧重绘是卡顿源之一：降为 0，用浅描边保持分组视觉
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(tween(180)), // 高度补间（tween 无回弹，比 spring 丝滑）
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 标题行：点击收起/展开
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (expanded) "▾" else "▸",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
            // 高度交给外层 animateContentSize 补间（避免 expand/shrink 每帧重测复杂内容导致末段卡顿）；
            // AnimatedVisibility 只做 alpha 淡入淡出（GPU 合成，开销小）
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(180))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(2.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun SettingsSwitch(
    title: String,
    sub: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            sub?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ValBlue,
                checkedTrackColor = ValBlueLight,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

/* ═══════════════════ 各分组 Section ═══════════════════ */

@Composable
private fun SettingsSubHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

/** ① 识别设置：灵敏度 + 识别类型 */
@Composable
private fun RecognitionSettingsSection(sc: SettingsCtx) {
    SettingsSectionCard(title = "识别设置", subtitle = "识别哪些码、匹配多严格") {
        SettingsSubHeader("识别灵敏度")
        var confDraft by remember(sc.s.confidenceThreshold) { mutableStateOf(sc.s.confidenceThreshold) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = confDraft,
                onValueChange = { confDraft = it },
                onValueChangeFinished = { sc.saveRun { AppPreferences.setConfidenceThreshold(sc.ctx, confDraft) } },
                valueRange = 0.1f..0.8f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = ValBlue,
                    activeTrackColor = ValBlueLight,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
            Text("${(confDraft * 100).roundToInt()}%", modifier = Modifier.padding(start = 8.dp))
        }
        SettingsSubHeader("识别类型")
        SettingsSwitch("🥤 取餐码", checked = sc.s.enableFoodCodes, onChange = sc.save { AppPreferences.setEnableFood(sc.ctx, it) })
        SettingsSwitch("📦 取件码", checked = sc.s.enableParcelCodes, onChange = sc.save { AppPreferences.setEnableParcel(sc.ctx, it) })
        SettingsSwitch(
            "🎫 券码",
            sub = "识别屏幕/图片中的二维码（解码内容为码值；识别到则只标券码，不叠加取餐/取件码）",
            checked = sc.s.enableCouponCodes,
            onChange = sc.save { AppPreferences.setEnableCoupon(sc.ctx, it) }
        )
    }
}

/** ② 输入方式：无障碍 + 外部接收 + 短信 */
@Composable
private fun InputMethodsSection(sc: SettingsCtx) {
    SettingsSectionCard(title = "输入方式", subtitle = "取件码从哪里来") {
        SettingsSubHeader("无障碍服务")
        OutlinedButton(
            onClick = { sc.saveRun { AppPreferences.setHideAccessibilityCard(sc.ctx, false) } },
            enabled = sc.s.hideAccessibilityCard,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
        ) { Text("在主页重新显示无障碍提示") }
        SettingsSubHeader("外部接收")
        SettingsSwitch("🔗 Intent 接收", sub = "接收来自其他App的分享（文本/图片）", checked = sc.s.enableIntentReceive, onChange = sc.save { AppPreferences.setEnableIntentReceive(sc.ctx, it) })
        SettingsSwitch("📤 分享识别", sub = "文本选择菜单/拖放直达时自动识别取餐取件码", checked = sc.s.enableShareDetection, onChange = sc.save { AppPreferences.setEnableShareDetection(sc.ctx, it) })
        SettingsSubHeader("短信识别")
        SettingsSwitch(
            "📨 短信取件码自动识别",
            sub = if (sc.s.enableSmsReceive) "已开启" else "已关闭",
            checked = sc.s.enableSmsReceive,
            onChange = sc.onSmsEnable
        )
        SettingsSubHeader("⏳ 到期提醒")
        SettingsSwitch(
            "快递取件码到期提醒",
            sub = if (sc.s.enableExpiryRemind) "存放 3 天或文本时限到达时自动提醒" else "已关闭",
            checked = sc.s.enableExpiryRemind,
            onChange = { v -> sc.save { AppPreferences.setEnableExpiryRemind(sc.ctx, v) } }
        )
    }
}

/* ═══════════════════ 通知状态（各 ROM 通知管理适配） ═══════════════════ */

private enum class NotifStatus { OK, NEED_PERMISSION, DISABLED, CHANNEL_SILENT }

/**
 * 三态检测：① Android 13+ 通知权限 ② 系统「通知管理」总开关（vivo/小米等 ROM 独立于 13 权限）
 * ③ 各渠道是否被单独静默（importance == NONE）。
 * 返回 (状态, 说明文案)。正常时返回 OK。
 */
private fun computeNotificationStatus(ctx: Context): Pair<NotifStatus, String> {
    val nm = ctx.getSystemService(NotificationManager::class.java)
        ?: return NotifStatus.OK to ""
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return NotifStatus.NEED_PERMISSION to "Android 13+ 需要「通知」权限，未授权时所有通知都不会显示"
    }
    // areNotificationsEnabled() 反映系统通知管理总开关（含 ROM 层），不只 Android 13 权限
    if (!nm.areNotificationsEnabled()) {
        return NotifStatus.DISABLED to "系统「通知管理」里本 App 的通知被整体关闭，识别结果不会提醒"
    }
    val silentChannels = listOf("pickup_food", "pickup_parcel", "pickup_coupon")
        .mapNotNull { nm.getNotificationChannel(it) }
        .filter { it.importance == NotificationManager.IMPORTANCE_NONE }
    if (silentChannels.isNotEmpty()) {
        return NotifStatus.CHANNEL_SILENT to
            "以下渠道被设为静默，通知可能不显示：${silentChannels.joinToString("、") { it.name }}"
    }
    return NotifStatus.OK to ""
}

private fun openNotificationSettings(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        )
    } catch (_: Exception) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            )
        } catch (_: Exception) {
            Toast.makeText(ctx, "无法打开通知设置", Toast.LENGTH_SHORT).show()
        }
    }
}

/** 通知状态卡片：有问题才显示（正常时不占版面）；从系统设置返回时自动刷新。 */
@Composable
private fun NotificationStatusCard(sc: SettingsCtx) {
    val ctx = sc.ctx
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }
    // 从系统「通知设置」页返回后重算（用户可能刚去开了开关）
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val (status, desc) = remember(refreshTick) { computeNotificationStatus(ctx) }
    if (status == NotifStatus.OK) return

    val isError = status == NotifStatus.DISABLED
    val container = if (isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.tertiaryContainer
    val onContainer = if (isError) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onTertiaryContainer
    val title = when (status) {
        NotifStatus.NEED_PERMISSION -> "🔧 需要通知权限"
        NotifStatus.DISABLED -> "⚠️ 系统通知被关闭"
        else -> "⚠️ 部分通知渠道被静默"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = onContainer)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { openNotificationSettings(ctx) }) { Text("去开启") }
        }
    }
}

@Composable
private fun LearningStatsSection(sc: SettingsCtx, onStatsClick: () -> Unit) {
    SettingsSectionCard(title = "数据统计") {
        OutlinedButton(
            onClick = onStatsClick,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, ValBlue),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ValBlue)
        ) { Text("查看详细统计 →") }
        LearningStatsPanel(sc.ctx, sc.scope)
    }
}

@Composable
private fun VerifyServicesSection(sc: SettingsCtx) {
    SettingsSectionCard(title = "辅助验证", subtitle = "第三方服务验证 OCR 结果（需联网，可选）") {
        SettingsSubHeader("🗺️ 地图验证")
        SettingsSwitch(
            "启用地图验证",
            sub = if (sc.s.enableMapVerify) "已启用" else "已关闭（隐私优先）",
            checked = sc.s.enableMapVerify,
            onChange = sc.save { AppPreferences.setEnableMapVerify(sc.ctx, it) }
        )
        if (sc.s.enableMapVerify) {
            Text("Android 地理编码器优先使用，无需配置。如需更高精度可填高德 API Key：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            DebouncedKeyField(
                value = sc.amapApiKey, label = "高德 API Key（可选）",
                onCommit = { sc.saveRun { AppPreferences.setAmapApiKey(sc.ctx, it) } },
                onChange = sc.onAmapApiKeyChange
            )
            AmapHelpSection()
        }
        SettingsSubHeader("📮 快递100验证")
        SettingsSwitch(
            "启用快递100验证",
            sub = if (sc.s.enableKuaidi100) "已启用" else "已关闭",
            checked = sc.s.enableKuaidi100,
            onChange = sc.save { AppPreferences.setEnableKuaidi100(sc.ctx, it) }
        )
        if (sc.s.enableKuaidi100) {
            DebouncedKeyField(
                value = sc.kuaidi100Key, label = "快递100 API Key",
                onCommit = { sc.saveRun { AppPreferences.setKuaidi100Key(sc.ctx, it) } },
                onChange = sc.onKuaidi100KeyChange
            )
            Kuaidi100HelpSection()
        }
        SettingsSubHeader("🤖 AI 识别")
        SettingsSwitch(
            "启用 AI 识别",
            sub = if (sc.s.enableAI) {
                if (sc.s.apiKey.isNotBlank()) "已开启" else "未配置 API Key"
            } else "已关闭",
            checked = sc.s.enableAI,
            onChange = sc.save { AppPreferences.setEnableAI(sc.ctx, it) }
        )
        if (sc.s.enableAI) {
            DebouncedKeyField(
                value = sc.apiUrl, label = "API 地址",
                onCommit = { sc.saveRun { AppPreferences.setApiBaseUrl(sc.ctx, it) } },
                onChange = sc.onApiUrlChange
            )
            DebouncedKeyField(
                value = sc.apiKey, label = "API Key", isPassword = true,
                onCommit = { sc.saveRun { AppPreferences.setApiKey(sc.ctx, it) } },
                onChange = sc.onApiKeyChange
            )
            DebouncedKeyField(
                value = sc.apiModel, label = "模型名称",
                onCommit = { sc.saveRun { AppPreferences.setApiModel(sc.ctx, it) } },
                onChange = sc.onApiModelChange
            )
        }
    }
}

@Composable
private fun AppearanceSection(sc: SettingsCtx) {
    SettingsSectionCard(title = "外观") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEachIndexed { i, (v, l) ->
                SegmentedButton(
                    selected = sc.s.darkMode == v,
                    onClick = { sc.saveRun { AppPreferences.setDarkMode(sc.ctx, v) } },
                    shape = SegmentedButtonDefaults.itemShape(i, 3),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = ValBlue,
                        activeContentColor = MaterialTheme.colorScheme.onSurface,
                        inactiveContainerColor = MaterialTheme.colorScheme.surface,
                        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text(l) }
            }
        }
    }
}

@Composable
private fun AboutSection(sc: SettingsCtx) {
    SettingsSectionCard(title = "关于") {
        Column {
            Text("码上闪记 v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
            Text("基于 ML Kit OCR · 数据仅存储在本地",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            val uriHandler = LocalUriHandler.current
            Text(
                "GitHub: https://github.com/zixij644-elaborate/pickup-code-app",
                style = MaterialTheme.typography.bodySmall,
                color = ValBlue,
                modifier = Modifier
                    .clickable { uriHandler.openUri("https://github.com/zixij644-elaborate/pickup-code-app") }
                    .padding(vertical = 2.dp)
            )
            // 识别调试面板：仅 DEBUG 构建显示（生产裁剪）
            if (BuildConfig.DEBUG) {
                var showDebug by remember { mutableStateOf(false) }
                Text(
                    "🔍 识别调试",
                    style = MaterialTheme.typography.bodySmall,
                    color = ValBlue,
                    modifier = Modifier
                        .clickable { showDebug = true }
                        .padding(vertical = 2.dp)
                )
                if (showDebug) {
                    RecognitionDebugDialog(onDismiss = { showDebug = false })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        var upStatus by remember { mutableStateOf<String?>(null) }
        var checking by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = {
                checking = true
                sc.scope.launch {
                    val result = checkUpdate()
                    upStatus = result
                    if (result.startsWith("检查失败")) Toast.makeText(sc.ctx, result, Toast.LENGTH_SHORT).show()
                    checking = false
                }
            },
            enabled = !checking,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
        ) { Text(if (checking) "检查中..." else "检查更新") }
        upStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/* ═══════════════════ 原辅助组件（保留） ═══════════════════ */

@Composable
private fun LearningStatsPanel(ctx: Context, scope: CoroutineScope) {
    var stats by remember { mutableStateOf<PatternLearner.PatternStats?>(null) }
    var addrStats by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var suggestions by remember { mutableStateOf<List<PatternLearner.PatternSuggestion>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            stats = PatternLearner.getStats(ctx)
            addrStats = PatternLearner.getAddressStats(ctx)
            suggestions = PatternLearner.getSuggestions(ctx)
        }
    }

    val s = stats
    if (s == null || s.totalScans == 0) {
        Text("暂无识别数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    val hitRate = if (s.attempts > 0) (s.attempts.toFloat() / s.totalScans * 100).roundToInt() else 0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("扫描 ${s.totalScans} 次 · 命中 ${s.attempts} 次（${hitRate}%）· 漏检 ${s.misses} 次", style = MaterialTheme.typography.bodyMedium)
        if (s.verified > 0) {
            Text("已确认 ${s.verified} 次", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (s.perPattern.isNotEmpty()) {
            Text("格式命中：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for ((p, n) in s.perPattern.entries.sortedByDescending { it.value }) {
                Text("  $p : $n 次", style = MaterialTheme.typography.bodySmall)
            }
        }

        val (addrOk, addrTotal) = addrStats ?: (0 to 0)
        if (addrTotal > 0) {
            val addrRate = (addrOk.toFloat() / addrTotal * 100).roundToInt()
            Text("地址验证：$addrOk / $addrTotal（${addrRate}%）", style = MaterialTheme.typography.bodySmall)
        }

        if (suggestions.isNotEmpty()) {
            Text("候选模式：", style = MaterialTheme.typography.labelMedium, color = ValBlue)
            for (sg in suggestions.take(3)) {
                Text("  ${sg.label} — ${sg.count} 条未匹配", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Text("  样例: ${sg.sampleCodes.joinToString("，")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("  建议: ${sg.proposedRegex}", style = MaterialTheme.typography.bodySmall, color = ValBlue)
            }
        }

        TextButton(
            onClick = {
                scope.launch(Dispatchers.IO) {
                    PatternLearner.clearUnmatched(ctx)
                    suggestions = PatternLearner.getSuggestions(ctx)
                }
            },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
        ) { Text("清除未匹配样本", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun DebouncedKeyField(
    value: String,
    label: String,
    onCommit: (String) -> Unit,
    onChange: (String) -> Unit,
    isPassword: Boolean = false,
    debounceMs: Long = 400
) {
    // 以 value 作 key：外部回填（如 DataStore 异步加载后）变化时重置内部 text，
    // 否则已配置的 Key/URL 在重启后显示为空/默认值（M1 回归）。
    var text by remember(value) { mutableStateOf(value) }
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 用 remember 持有 Job，避免重组时重置为 null 导致防抖失效（H5）
    val saveJob = remember { mutableStateOf<Job?>(null) }
    // 离开页面时若仍有未提交的防抖输入，立即落盘，避免 400ms 内返回导致 Key/URL 静默丢失
    val latestText = remember { mutableStateOf(text) }
    latestText.value = text
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            saveJob.value?.cancel()
            // 仅当与外部 value 不同才提交，避免无改动的多余写
            if (latestText.value != value) {
                onCommit(latestText.value)
            }
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
            saveJob.value?.cancel()
            saveJob.value = scope.launch {
                delay(debounceMs)
                onCommit(text)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        visualTransformation = if (isPassword && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (isPassword) {
            {
                TextButton(onClick = { visible = !visible },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                    Text(if (visible) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall)
                }
            }
        } else null
    )
}

@Composable
private fun AmapHelpSection() {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起说明 ▲" else "如何获取高德 API Key？▼", style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), elevation = CardDefaults.cardElevation(0.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("1. 打开浏览器访问", style = MaterialTheme.typography.bodySmall)
                    Text("   https://console.amap.com/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text("2. 注册/登录高德开放平台账号", style = MaterialTheme.typography.bodySmall)
                    Text("3. 进入「应用管理 → 我的应用」→ 创建应用", style = MaterialTheme.typography.bodySmall)
                    Text("4. 为应用添加 Key，服务平台选择「Web服务」", style = MaterialTheme.typography.bodySmall)
                    Text("5. 复制生成的 Key 粘贴到上方输入框", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("免费额度：每日 3000 次地理编码，个人使用完全够用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Kuaidi100HelpSection() {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起说明 ▲" else "如何获取快递100 API Key？▼", style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), elevation = CardDefaults.cardElevation(0.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("1. 打开浏览器访问", style = MaterialTheme.typography.bodySmall)
                    Text("   https://api.kuaidi100.com/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text("2. 注册/登录快递100开放平台", style = MaterialTheme.typography.bodySmall)
                    Text("3. 进入「企业管理 → 我的授权Key」", style = MaterialTheme.typography.bodySmall)
                    Text("4. 复制 Customer Key 粘贴到上方输入框", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("用途：OCR 提取到单号后，通过API反向查取件码和地址作为标准答案", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("用于验证 OCR 提取结果是否正确，辅助自学习", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private suspend fun checkUpdate(): String = withContext(Dispatchers.IO) {
    var resp: java.net.HttpURLConnection? = null
    try {
        resp = java.net.URL("https://api.github.com/repos/zixij644-elaborate/pickup-code-app/releases/latest")
            .openConnection() as java.net.HttpURLConnection
        resp.requestMethod = "GET"; resp.setRequestProperty("Accept", "application/vnd.github.v3+json")
        resp.setRequestProperty("User-Agent", "pickup-code-app-checkupdate")
        resp.connectTimeout = 10000; resp.readTimeout = 10000
        if (resp.responseCode != 200) return@withContext "检查失败"
        val latest = org.json.JSONObject(resp.inputStream.bufferedReader().use { it.readText() }).getString("tag_name").removePrefix("v")
        if (latest == BuildConfig.VERSION_NAME) "当前已是最新版本" else "发现新版本 v$latest"
    } catch (e: Exception) { "检查失败: ${e.message}" }
    finally { resp?.disconnect() }
}
