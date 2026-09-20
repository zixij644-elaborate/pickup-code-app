package com.pickupcode.app.ui.screens

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ColorFilter
import com.pickupcode.app.ui.components.IconText
import com.pickupcode.app.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pickupcode.app.learner.DailyStats
import com.pickupcode.app.learner.PatternLearner
import com.pickupcode.app.learner.PatternLearner.PatternSuggestion
import com.pickupcode.app.learner.PatternLearner.PatternStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<PatternStats?>(null) }
    var suggestions by remember { mutableStateOf<List<PatternSuggestion>>(emptyList()) }
    var learnedRules by remember { mutableStateOf<List<PatternLearner.LearnedRule>>(emptyList()) }
    var dailyStats by remember { mutableStateOf<List<DailyStats.DayStat>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    fun reloadLearned() {
        scope.launch {
            learnedRules = withContext(Dispatchers.IO) { PatternLearner.getLearnedPatterns(context) }
        }
    }

    fun reloadDaily() {
        scope.launch {
            dailyStats = withContext(Dispatchers.IO) { DailyStats.getDailyStats(context) }
        }
    }

    LaunchedEffect(Unit) {
        try {
            stats = withContext(Dispatchers.IO) { PatternLearner.getStats(context) }
            suggestions = withContext(Dispatchers.IO) { PatternLearner.getSuggestions(context) }
            learnedRules = withContext(Dispatchers.IO) { PatternLearner.getLearnedPatterns(context) }
            dailyStats = withContext(Dispatchers.IO) { DailyStats.getDailyStats(context) }
            // Trigger auto-apply on view
            withContext(Dispatchers.IO) { PatternLearner.autoApply(context) }
        } catch (e: Exception) {
            stats = null
            Log.e("StatsScreen", "加载统计失败", e)
        } finally {
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { IconText(R.drawable.ic_bar_chart_3, "识别统计", iconSize = 20.dp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    // C4: 成绩卡分享（生成图片海报）
                    if (stats != null) {
                        IconButton(onClick = {
                            val s = stats ?: return@IconButton
                            scope.launch(Dispatchers.IO) {
                                com.pickupcode.app.share.ShareStatsCard.share(context, s)
                            }
                        }) {
                            Icon(Icons.Default.Share, "分享成绩卡")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                stats?.let { s ->
                    OverviewCard(s)
                    PatternBreakdownCard(s)
                } ?: EmptyStateMessage()

                if (dailyStats.isNotEmpty()) {
                    HitRateCard(dailyStats)
                }

                SuggestionsCard(suggestions) { cleared ->
                    if (cleared) {
                        scope.launch {
                            suggestions = withContext(Dispatchers.IO) {
                                PatternLearner.getSuggestions(context)
                            }
                        }
                    }
                }

                LearnedRulesCard(learnedRules, onChanged = { reloadLearned() })
            }
        }
    }
}

@Composable
private fun OverviewCard(stats: PatternStats) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("总览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem("总扫描", stats.totalScans.toString())
                StatItem("命中", stats.attempts.toString(), MaterialTheme.colorScheme.primary)
                StatItem("未识别", stats.misses.toString(), MaterialTheme.colorScheme.error)
                StatItem("已确认", stats.verified.toString(), MaterialTheme.colorScheme.tertiary)
            }
            if (stats.totalScans > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { stats.attempts.toFloat() / stats.totalScans },
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    "命中率 ${(stats.attempts * 100f / stats.totalScans).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PatternBreakdownCard(stats: PatternStats) {
    val patterns = stats.perPattern.entries.sortedByDescending { it.value }
    if (patterns.isEmpty()) return

    val maxCount = patterns.maxOf { it.value }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("模式分布", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            patterns.forEach { (id, count) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            patternLabel(id),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "$count 次",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LinearProgressIndicator(
                        progress = { count.toFloat() / maxCount },
                        modifier = Modifier.width(80.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionsCard(suggestions: List<PatternSuggestion>, onCleared: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showClearDialog by remember { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconText(R.drawable.ic_lightbulb, "模式建议", iconSize = 18.dp, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (suggestions.isNotEmpty()) {
                    TextButton(onClick = { showClearDialog = true }) {
                        Text("清除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (suggestions.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "暂无建议。继续使用 App，系统会从未识别的文本中自动发现新模式。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.height(8.dp))
                suggestions.forEach { sug ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(sug.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    "${(sug.confidence * 100).toInt()}% · ${sug.count}次",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "样本: ${sug.sampleCodes.take(3).joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                sug.proposedRegex,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除未匹配样本？") },
            text = { Text("这会删除所有未识别的 OCR 文本记录，系统将重新从零开始学习。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) { PatternLearner.clearUnmatched(context) }
                    showClearDialog = false
                    onCleared(true)
                }) { Text("确认清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun EmptyStateMessage() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("暂无统计数据", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "使用 App 识别取餐码/取件码后，统计数据会在这里展示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** B2: 命中率曲线卡片（Compose Canvas 手写折线图，不引第三方图表库）。 */
@Composable
private fun HitRateCard(stats: List<DailyStats.DayStat>) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            IconText(R.drawable.ic_trending_up, "命中率趋势", iconSize = 18.dp, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("近 ${stats.size} 天识别命中率变化", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            if (stats.size < 2) {
                Text("累计样本不足，继续使用后展示曲线。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val lineColor = MaterialTheme.colorScheme.primary
                val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                    val w = size.width
                    val h = size.height
                    val padB = 18.dp.toPx()
                    val padT = 8.dp.toPx()
                    val plotH = h - padB - padT
                    val maxRate = stats.mapNotNull { if (it.total > 0) it.hits.toFloat() / it.total else null }
                        .maxOrNull()?.coerceAtLeast(0.01f) ?: 0.01f
                    val n = stats.size
                    // 画水平网格线 + 基线
                    drawLine(gridColor, androidx.compose.ui.geometry.Offset(0f, h - padB), androidx.compose.ui.geometry.Offset(w, h - padB), strokeWidth = 1.dp.toPx())
                    // 逐点画命中率折线（y = 每日命中率，相对最高命中率归一化）
                    val points = stats.mapIndexed { i, s ->
                        val x = if (n == 1) w / 2f else w * i / (n - 1).toFloat()
                        val rate = if (s.total > 0) s.hits.toFloat() / s.total else 0f
                        val normalized = (rate / maxRate)
                        androidx.compose.ui.geometry.Offset(x, h - padB - plotH * normalized)
                    }
                    for (i in 1 until points.size) {
                        drawLine(lineColor, points[i - 1], points[i], strokeWidth = 2.5.dp.toPx())
                    }
                    for (p in points) {
                        drawCircle(lineColor, radius = 3.dp.toPx(), center = p)
                    }
                    // 每点下方标日期（只标最多 7 个避免拥挤）
                    // 文字用 drawContext 太复杂，这里简化为只画曲线，日期标签放到下方描述
                }
                // 命中率描述
                val recent = stats.takeLast(1).first()
                val avgRate = stats.filter { it.total > 0 }.let { list ->
                    val sum = list.sumOf { it.hits }
                    val tot = list.sumOf { it.total }
                    if (tot > 0) (sum * 100 / tot) else 0
                }
                Text(
                    "近${stats.size}天命中率约 $avgRate% · 最近(${recent.date}) ${if (recent.total > 0) recent.hits * 100 / recent.total else 0}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stats.joinToString("  ") { s -> "${s.date.takeLast(5)}:${s.hits}/${s.total}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun patternLabel(id: String): String = when (id) {
    "PREFIXED_CODE" -> "前缀匹配（取件码: XXX）"
    "THREE_SEGMENT_PARCEL" -> "三段式取件码（1-2-3456）"
    "FOUR_SEGMENT_PARCEL" -> "四段式取件码（A1-2-3-45）"
    "LETTER_TWO_SEGMENT_PARCEL" -> "两段式字母（A-1-234）"
    "LETTER_DASH_FIVE_PARCEL" -> "字母-数字（D-06003）"
    "LONG_NUMBER_PARCEL" -> "长数字（6-8位）"
    else -> id
}

@Composable
private fun LearnedRulesCard(rules: List<PatternLearner.LearnedRule>, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            IconText(R.drawable.ic_brain, "已学习规则", iconSize = 18.dp, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("系统自动从未识别样本中学习并应用的新正则。可停用/删除误学报废的规则。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (rules.isEmpty()) {
                Text(
                    "暂无已学习规则。继续使用 App，系统会从未识别的文本中自动学习并应用新正则。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }
            rules.forEach { rule ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                rule.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (!rule.enabled || rule.decayed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                            )
                            // A2: 质量标签（样本数 + 置信度）
                            Text(
                                " · 样本${rule.sampleCount} · ${(rule.confidence * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (rule.decayed) {
                                AssistChip(onClick = {}, label = { Text("已衰减", style = MaterialTheme.typography.labelSmall) },
                                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface))
                            }
                        }
                        Text(
                            rule.regex,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        if (!rule.enabled) {
                            Text("已停用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    // A1: 停用/启用 + 删除 按钮 + 类型图标（Emoji 换成 Lucide 线性图标）
                    Image(
                        painter = painterResource(
                            when (rule.type) {
                                "pickup_food" -> R.drawable.ic_cup_soda
                                "coupon" -> R.drawable.ic_ticket
                                else -> R.drawable.ic_package
                            }
                        ),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.size(18.dp)
                    )
                    TextButton(
                        onClick = {
                            // 写 JSON 到 IO 线程，避免主线程同步 IO
                            scope.launch(Dispatchers.IO) {
                                PatternLearner.setRuleEnabled(context, rule.regex, !rule.enabled)
                                withContext(Dispatchers.Main) { onChanged() }
                            }
                        }
                    ) { Text(if (rule.enabled) "停用" else "启用", style = MaterialTheme.typography.labelSmall) }
                    TextButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                PatternLearner.deleteRule(context, rule.regex)
                                withContext(Dispatchers.Main) { onChanged() }
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("删除", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}
