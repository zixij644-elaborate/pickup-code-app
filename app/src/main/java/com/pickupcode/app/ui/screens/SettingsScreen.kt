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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pickupcode.app.BuildConfig
import com.pickupcode.app.R
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

/** 设置页内部层级：主页做极简目录卡片（参考 FlClash），详细开关/Key/统计进二级子页 */
private enum class SettingsPage(val title: String) {
    MAIN("设置"),
    RECOGNITION("识别范围"),
    SOURCE_REMIND("来源与提醒"),
    AI_CONFIG("AI 识别与接口"),
    VERIFY_CONFIG("第三方验证"),
    STATS("识别统计与自学习")
}

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
fun SettingsScreen(
    onBack: () -> Unit,
    onStatsClick: () -> Unit = {},
    onSavedAddressClick: () -> Unit = {},
    onRulesClick: () -> Unit = {}
) {
    val ctx = LocalContext.current
    // 进程级 scope：防抖落盘/权限回调等 fire-and-forget 写不随页面销毁取消（否则 400ms 内返回会丢 Key/URL）
    val scope = com.pickupcode.app.App.appScope
    val settingsFlow = remember { AppPreferences.observe(ctx) }
    val s by settingsFlow.collectAsState(initial = AppPreferences.Settings())

    var currentPage by remember { mutableStateOf(SettingsPage.MAIN) }
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
    // 使全部 section 每次重组。键用实际可变状态，状态未变时引用稳定。
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

    BackHandler(enabled = currentPage != SettingsPage.MAIN) {
        currentPage = SettingsPage.MAIN
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = currentPage.title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentPage == SettingsPage.MAIN) onBack()
                        else currentPage = SettingsPage.MAIN
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { pad ->
        AnimatedContent(
            targetState = currentPage,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(120)) },
            label = "settings_page",
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
        ) { page ->
            when (page) {
                SettingsPage.MAIN -> SettingsMainContent(
                    sc = sc,
                    onRulesClick = onRulesClick,
                    onSavedAddressClick = onSavedAddressClick,
                    onOpenRecognition = { currentPage = SettingsPage.RECOGNITION },
                    onOpenSourceRemind = { currentPage = SettingsPage.SOURCE_REMIND },
                    onOpenAiConfig = { currentPage = SettingsPage.AI_CONFIG },
                    onOpenVerifyConfig = { currentPage = SettingsPage.VERIFY_CONFIG },
                    onOpenStats = { currentPage = SettingsPage.STATS }
                )
                SettingsPage.RECOGNITION -> RecognitionSubPage(sc)
                SettingsPage.SOURCE_REMIND -> SourceRemindSubPage(sc)
                SettingsPage.AI_CONFIG -> AiConfigSubPage(sc)
                SettingsPage.VERIFY_CONFIG -> VerifyConfigSubPage(sc)
                SettingsPage.STATS -> StatsSubPage(sc, onStatsClick)
            }
        }
    }
}

/* ═══════════════════ 主设置页（极简大圆角分组卡片） ═══════════════════ */

@Composable
private fun SettingsMainContent(
    sc: SettingsCtx,
    onRulesClick: () -> Unit,
    onSavedAddressClick: () -> Unit,
    onOpenRecognition: () -> Unit,
    onOpenSourceRemind: () -> Unit,
    onOpenAiConfig: () -> Unit,
    onOpenVerifyConfig: () -> Unit,
    onOpenStats: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    var upStatus by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }

    val aiSummary = remember(sc.s.enableAI, sc.s.apiKey) {
        when {
            !sc.s.enableAI && sc.s.apiKey.isNotBlank() -> "未启用"
            !sc.s.enableAI -> "未启用"
            sc.s.apiKey.isBlank() -> "未填 Key"
            else -> "已开启"
        }
    }
    val verifyCount = (if (sc.s.enableMapVerify) 1 else 0) + (if (sc.s.enableKuaidi100) 1 else 0)
    val verifySummary = if (verifyCount == 0) "未启用" else "已启用 $verifyCount 项"
    val recognitionSummary = remember(sc.s.enableFoodCodes, sc.s.enableParcelCodes, sc.s.enableCouponCodes) {
        buildList {
            if (sc.s.enableFoodCodes) add("取餐")
            if (sc.s.enableParcelCodes) add("取件")
            if (sc.s.enableCouponCodes) add("券码")
        }.ifEmpty { listOf("已关闭") }.joinToString(" · ")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 仅在权限/渠道异常时置顶提示
        NotificationStatusCard(sc)

        // 1. 常用管理（无组标题置顶卡，快速直达规则、地址与统计）
        SettingsListCard {
            SettingsNavRow(
                title = "识别规则",
                icon = R.drawable.ic_wrench,
                onClick = onRulesClick
            )
            SettingsListDivider()
            SettingsNavRow(
                title = "常用取件地址",
                icon = R.drawable.ic_map_pin,
                onClick = onSavedAddressClick
            )
            SettingsListDivider()
            SettingsNavRow(
                title = "识别统计与自学习",
                icon = R.drawable.ic_bar_chart_3,
                onClick = onOpenStats
            )
        }

        // 2. 设置
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsGroupHeader("设置")
            SettingsListCard {
                ThemePickerRow(sc)
                SettingsListDivider()
                SettingsNavRow(
                    title = "识别范围",
                    icon = R.drawable.ic_search,
                    trailingText = recognitionSummary,
                    onClick = onOpenRecognition
                )
                SettingsListDivider()
                Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                    ConfidenceSliderRow(sc)
                }
                SettingsListDivider()
                SettingsNavRow(
                    title = "来源与提醒",
                    icon = R.drawable.ic_bell,
                    onClick = onOpenSourceRemind
                )
                SettingsListDivider()
                SettingsNavRow(
                    title = "AI 识别与接口",
                    icon = R.drawable.ic_bot,
                    trailingText = aiSummary,
                    onClick = onOpenAiConfig
                )
                SettingsListDivider()
                SettingsNavRow(
                    title = "第三方验证",
                    icon = R.drawable.ic_map,
                    trailingText = verifySummary,
                    onClick = onOpenVerifyConfig
                )
            }
        }

        // 3. 其他
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsGroupHeader("其他")
            SettingsListCard {
                SettingsNavRow(
                    title = "检查更新",
                    icon = R.drawable.ic_refresh_cw,
                    trailingText = when {
                        checking -> "检查中…"
                        upStatus != null -> upStatus
                        else -> "v${BuildConfig.VERSION_NAME}"
                    },
                    enabled = !checking,
                    onClick = {
                        checking = true
                        sc.scope.launch {
                            val result = checkUpdate()
                            upStatus = result
                            if (result.startsWith("检查失败")) {
                                Toast.makeText(sc.ctx, result, Toast.LENGTH_SHORT).show()
                            }
                            checking = false
                        }
                    }
                )
                if (BuildConfig.DEBUG) {
                    SettingsListDivider()
                    SettingsNavRow(
                        title = "识别调试",
                        icon = R.drawable.ic_action_info,
                        onClick = { showDebug = true }
                    )
                }
            }
        }

        // 底部版本说明与开源链接
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "码上闪记 v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "基于 ML Kit OCR · 数据仅存储在本地",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "https://github.com/zixij644-elaborate/pickup-code-app",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { uriHandler.openUri("https://github.com/zixij644-elaborate/pickup-code-app") }
                    .padding(vertical = 2.dp)
            )
        }

        Spacer(Modifier.height(12.dp))
    }

    if (showDebug) {
        RecognitionDebugDialog(onDismiss = { showDebug = false })
    }
}

/* ═══════════════════ 二级子页 ═══════════════════ */

@Composable
private fun RecognitionSubPage(sc: SettingsCtx) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsGroupHeader("识别类型")
            SettingsGroupCard {
                SettingsSwitch(
                    "取餐码",
                    icon = R.drawable.ic_cup_soda,
                    checked = sc.s.enableFoodCodes,
                    onChange = sc.save { AppPreferences.setEnableFood(sc.ctx, it) }
                )
                SettingsDivider()
                SettingsSwitch(
                    "取件码",
                    icon = R.drawable.ic_package,
                    checked = sc.s.enableParcelCodes,
                    onChange = sc.save { AppPreferences.setEnableParcel(sc.ctx, it) }
                )
                SettingsDivider()
                SettingsSwitch(
                    "券码",
                    icon = R.drawable.ic_ticket,
                    sub = "识别屏幕/图片中的二维码（识别到则只标券码）",
                    checked = sc.s.enableCouponCodes,
                    onChange = sc.save { AppPreferences.setEnableCoupon(sc.ctx, it) }
                )
            }
        }
    }
}

@Composable
private fun SourceRemindSubPage(sc: SettingsCtx) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsGroupHeader("自动获取")
            SettingsGroupCard {
                SettingsSwitch(
                    "Intent 接收",
                    icon = R.drawable.ic_link,
                    sub = "接收来自其他 App 的分享（文本/图片）",
                    checked = sc.s.enableIntentReceive,
                    onChange = sc.save { AppPreferences.setEnableIntentReceive(sc.ctx, it) }
                )
                SettingsDivider()
                SettingsSwitch(
                    "分享识别",
                    icon = R.drawable.ic_upload,
                    sub = "文本选择菜单/拖放直达时自动识别",
                    checked = sc.s.enableShareDetection,
                    onChange = sc.save { AppPreferences.setEnableShareDetection(sc.ctx, it) }
                )
                SettingsDivider()
                SettingsSwitch(
                    "短信取件码自动识别",
                    icon = R.drawable.ic_send,
                    sub = if (sc.s.enableSmsReceive) "已开启" else "已关闭",
                    checked = sc.s.enableSmsReceive,
                    onChange = sc.onSmsEnable
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsGroupHeader("提醒与引导")
            SettingsGroupCard {
                SettingsSwitch(
                    "快递取件码到期提醒",
                    icon = R.drawable.ic_hourglass,
                    sub = if (sc.s.enableExpiryRemind) "存放 3 天或文本时限到达时提醒" else "已关闭",
                    checked = sc.s.enableExpiryRemind,
                    onChange = sc.save { AppPreferences.setEnableExpiryRemind(sc.ctx, it) }
                )
                SettingsDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = sc.s.hideAccessibilityCard) {
                            sc.saveRun { AppPreferences.setHideAccessibilityCard(sc.ctx, false) }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "在主页重新显示无障碍提示",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (sc.s.hideAccessibilityCard) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        )
                        Text(
                            if (sc.s.hideAccessibilityCard) "点击恢复主页顶部的无障碍引导卡片" else "主页当前已显示无障碍提示",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiConfigSubPage(sc: SettingsCtx) {
    var probing by remember { mutableStateOf(false) }
    var probeText by remember { mutableStateOf<String?>(null) }
    var probeOk by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsGroupCard {
            SettingsSwitch(
                "启用 AI 识别",
                icon = R.drawable.ic_bot,
                sub = when {
                    !sc.s.enableAI -> "已关闭（仅使用本地 OCR 与正则）"
                    sc.s.apiKey.isNotBlank() -> "已开启"
                    else -> "已开启，但尚未配置 API Key"
                },
                checked = sc.s.enableAI,
                onChange = sc.save { AppPreferences.setEnableAI(sc.ctx, it) }
            )
            if (!sc.s.enableAI && sc.s.apiKey.isNotBlank()) {
                Text(
                    "已保存 API Key，但开关处于关闭状态 —— 打开开关后才会调用 AI。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            SettingsDivider()
            DebouncedKeyField(
                value = sc.apiUrl,
                label = "API 地址",
                onCommit = { sc.saveRun { AppPreferences.setApiBaseUrl(sc.ctx, it) } },
                onChange = sc.onApiUrlChange
            )
            DebouncedKeyField(
                value = sc.apiKey,
                label = "API Key",
                isPassword = true,
                onCommit = { sc.saveRun { AppPreferences.setApiKey(sc.ctx, it) } },
                onChange = sc.onApiKeyChange
            )
            DebouncedKeyField(
                value = sc.apiModel,
                label = "模型名称",
                onCommit = { sc.saveRun { AppPreferences.setApiModel(sc.ctx, it) } },
                onChange = sc.onApiModelChange
            )
            SettingsDivider()
            SettingsSwitch(
                "AI 读取图片",
                sub = if (sc.s.enableAiImage) "已开启：整张截图压缩后发给 AI（可直接读出地址）"
                else "已关闭：只把本地 OCR 文本发给 AI",
                checked = sc.s.enableAiImage,
                onChange = sc.save { AppPreferences.setEnableAiImage(sc.ctx, it) }
            )
            if (sc.s.enableAiImage) {
                Text(
                    "注意：开启后整张截图会上传到 AI 服务（包含屏幕上的手机号/姓名）。模型需支持视觉（如 deepseek-flash）；纯文本模型会自动回退为文本识别。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        SettingsGroupCard {
            OutlinedButton(
                enabled = !probing,
                onClick = {
                    probing = true
                    probeText = null
                    uiScope.launch {
                        val t = com.pickupcode.app.extractor.AIExtractor.probe(sc.apiKey, sc.apiUrl, sc.apiModel)
                        val sb = StringBuilder()
                        sb.append(
                            if (t.ok) "文本通道：成功 · ${t.latencyMs}ms · 回复「${t.reply.ifBlank { "(空)" }}」"
                            else "文本通道：失败（${com.pickupcode.app.extractor.AIExtractor.categorizeError(t.error)}）"
                        )
                        var imgOk = true
                        if (sc.s.enableAiImage) {
                            val probeImg = com.pickupcode.app.util.ImageUtils.makeProbeImageBase64()
                            val img = probeImg?.let {
                                com.pickupcode.app.extractor.AIExtractor.probe(sc.apiKey, sc.apiUrl, sc.apiModel, imageBase64 = it)
                            }
                            imgOk = img?.ok == true
                            sb.append("\n").append(
                                when {
                                    img == null -> "图片通道：自检图生成失败"
                                    img.ok -> "图片通道：成功 · ${img.latencyMs}ms · 识别到「${img.reply.ifBlank { "(空)" }}」（应含 CP-1234）"
                                    else -> "图片通道：失败（${com.pickupcode.app.extractor.AIExtractor.categorizeError(img.error)}）"
                                }
                            )
                        }
                        sb.append("\n请求地址：${t.endpoint}")
                        probing = false
                        probeOk = t.ok && imgOk
                        probeText = sb.toString()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
            ) { Text(if (probing) "测试中…（最多 40 秒）" else "测试 AI 连接") }

            probeText?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (probeOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }

            SettingsDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showHelp = !showHelp }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("使用说明与 DeepSeek 示例", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(if (showHelp) "▲" else "▼", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            AnimatedVisibility(visible = showHelp) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "• AI 只做补充：正则已识别到的码不会重复入库；拿一张「正则未识别出码」的截图去分享验证最直观。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "• DeepSeek 示例：地址填 https://api.deepseek.com（或 /v1），模型填 deepseek-flash（旧版 deepseek-chat / deepseek-reasoner 已停用）。本应用会自动关闭思考模式以降低延迟。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun VerifyConfigSubPage(sc: SettingsCtx) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsGroupHeader("地图验证")
        SettingsGroupCard {
            SettingsSwitch(
                "启用地图验证",
                icon = R.drawable.ic_map,
                sub = if (sc.s.enableMapVerify) "已启用" else "已关闭（隐私优先）",
                checked = sc.s.enableMapVerify,
                onChange = sc.save { AppPreferences.setEnableMapVerify(sc.ctx, it) }
            )
            if (sc.s.enableMapVerify) {
                Text(
                    "Android 地理编码器优先使用，无需配置。如需更高精度可填高德 API Key：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DebouncedKeyField(
                    value = sc.amapApiKey,
                    label = "高德 API Key（可选）",
                    onCommit = { sc.saveRun { AppPreferences.setAmapApiKey(sc.ctx, it) } },
                    onChange = sc.onAmapApiKeyChange
                )
                AmapHelpSection()
            }
        }

        SettingsGroupHeader("快递100验证")
        SettingsGroupCard {
            SettingsSwitch(
                "启用快递100验证",
                icon = R.drawable.ic_mailbox,
                sub = if (sc.s.enableKuaidi100) "已启用" else "已关闭",
                checked = sc.s.enableKuaidi100,
                onChange = sc.save { AppPreferences.setEnableKuaidi100(sc.ctx, it) }
            )
            if (sc.s.enableKuaidi100) {
                DebouncedKeyField(
                    value = sc.kuaidi100Key,
                    label = "快递100 API Key",
                    onCommit = { sc.saveRun { AppPreferences.setKuaidi100Key(sc.ctx, it) } },
                    onChange = sc.onKuaidi100KeyChange
                )
                Kuaidi100HelpSection()
            }
        }
    }
}

@Composable
private fun StatsSubPage(sc: SettingsCtx, onStatsClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsGroupCard {
            OutlinedButton(
                onClick = onStatsClick,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
            ) { Text("查看详细统计 →") }
            LearningStatsPanel(sc.ctx, sc.scope)
        }
    }
}

/* ═══════════════════ 公共组件（FlClash 极简大圆角分组卡片） ═══════════════════ */

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp)
    )
}

/** 列表目录卡片：无内边距与描边，点击涟漪铺满整行，靠浅灰底与纯白表面自然分层 */
@Composable
private fun SettingsListCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/** 表单/开关分组卡片（二级子页使用，保持统一大圆角与纯白无描边风格） */
@Composable
private fun SettingsGroupCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun SettingsListDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 18.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    )
}

@Composable
private fun SettingsNavRow(
    title: String,
    @DrawableRes icon: Int? = null,
    trailingText: String? = null,
    enabled: Boolean = true,
    showArrow: Boolean = true,
    onClick: () -> Unit
) {
    val titleColor = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (icon != null) {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = title,
            fontSize = 16.sp,
            color = titleColor,
            modifier = Modifier.weight(1f)
        )
        if (trailingText != null) {
            Text(
                text = trailingText,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showArrow) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** FlClash 风格单行选择器：右侧显示当前值 + 上下箭头，点击弹出轻量菜单 */
@Composable
private fun ThemePickerRow(sc: SettingsCtx) {
    var expanded by remember { mutableStateOf(false) }
    val options = remember {
        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")
    }
    val currentLabel = options.firstOrNull { it.first == sc.s.darkMode }?.second ?: "跟随系统"

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_lightbulb),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "外观主题",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = currentLabel,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.Rounded.UnfoldMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            fontWeight = if (sc.s.darkMode == value) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (sc.s.darkMode == value) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        expanded = false
                        sc.saveRun { AppPreferences.setDarkMode(sc.ctx, value) }
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitch(
    title: String,
    sub: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    @DrawableRes icon: Int? = null
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            if (icon != null) {
                com.pickupcode.app.ui.components.IconText(
                    icon = icon,
                    text = title,
                    iconSize = 17.dp,
                    style = MaterialTheme.typography.bodyLarge
                )
            } else {
                Text(title, style = MaterialTheme.typography.bodyLarge)
            }
            sub?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

/* ═══════════════════ 主页内嵌区块 ═══════════════════ */

@Composable
private fun ConfidenceSliderRow(sc: SettingsCtx) {
    var confDraft by remember(sc.s.confidenceThreshold) { mutableStateOf(sc.s.confidenceThreshold) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("识别灵敏度", style = MaterialTheme.typography.bodyLarge)
            Text(
                "${(confDraft * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = confDraft,
            onValueChange = { confDraft = it },
            onValueChangeFinished = { sc.saveRun { AppPreferences.setConfidenceThreshold(sc.ctx, confDraft) } },
            valueRange = 0.1f..0.8f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
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

    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED
    ) {
        return NotifStatus.NEED_PERMISSION to "Android 13+ 需要授予通知权限才能弹出取件码卡片"
    }

    if (!nm.areNotificationsEnabled()) {
        return NotifStatus.DISABLED to "系统「通知管理」里本应用的通知开关处于关闭状态，请在系统设置中打开"
    }

    val silent = nm.notificationChannels
        .filter { it.importance == NotificationManager.IMPORTANCE_NONE }
        .map { it.name.toString() }
    if (silent.isNotEmpty()) {
        return NotifStatus.CHANNEL_SILENT to "以下通知渠道被设为静默，无法弹出提醒：${silent.joinToString("、")}"
    }

    return NotifStatus.OK to ""
}

private fun openNotificationSettings(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    } catch (_: Exception) {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", ctx.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}

@Composable
private fun NotificationStatusCard(sc: SettingsCtx) {
    val ctx = sc.ctx
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }
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
        NotifStatus.NEED_PERMISSION -> "需要通知权限"
        NotifStatus.DISABLED -> "系统通知被关闭"
        else -> "部分通知渠道被静默"
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

/* ═══════════════════ 统计、防抖输入框与帮助折叠组件 ═══════════════════ */

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
            Text("候选模式：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            for (sg in suggestions.take(3)) {
                Text("  ${sg.label} — ${sg.count} 条未匹配", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Text("  样例: ${sg.sampleCodes.joinToString("，")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("  建议: ${sg.proposedRegex}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
    var text by remember(value) { mutableStateOf(value) }
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val saveJob = remember { mutableStateOf<Job?>(null) }
    val latestText = remember { mutableStateOf(text) }
    latestText.value = text
    DisposableEffect(Unit) {
        onDispose {
            saveJob.value?.cancel()
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
                TextButton(
                    onClick = { visible = !visible },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                ) {
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
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
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
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
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
