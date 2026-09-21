package com.pickupcode.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pickupcode.app.R
import com.pickupcode.app.extractor.CodeExtractor
import com.pickupcode.app.learner.PatternLearner
import com.pickupcode.app.ui.components.IconText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「识别规则」管理页。
 *
 * 这个页面回答两个问题：
 *  1. **现在有哪些正则在跑？** —— 内置模式（出厂定义）+ 我的规则（自动学习 + 手动添加）
 *  2. **改坏了怎么办？** —— 每条可单独还原，整页可一键还原默认
 *
 * 关键设计（用户要求：学习规则与手动添加不冲突、共用同一管线）：
 *  - 「我的规则」里自动学习和手动添加**混在同一个列表**，只是标注来源不同；它们在识别管线里完全等价。
 *  - 内置正则不进用户存储，而是「覆盖」：只记停用与改写，出厂定义始终在代码里
 *    （CodeExtractor.BUILTIN_RULES）→ 一键还原＝丢掉覆盖，天然可回退。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var builtins by remember { mutableStateOf<List<CodeExtractor.BuiltinRuleInfo>>(emptyList()) }
    var myRules by remember { mutableStateOf<List<PatternLearner.LearnedRule>>(emptyList()) }
    // 编辑弹窗状态：null=不显示；otherwise (编辑对象, 是内置模式吗)
    var editingBuiltin by remember { mutableStateOf<CodeExtractor.BuiltinRuleInfo?>(null) }
    var editingRule by remember { mutableStateOf<PatternLearner.LearnedRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    suspend fun reload() {
        val (b, r) = withContext(Dispatchers.IO) {
            CodeExtractor.builtinRuleInfos(context) to PatternLearner.getLearnedPatterns(context)
        }
        builtins = b
        myRules = r
    }

    LaunchedEffect(Unit) { reload() }

    fun toast(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("识别规则") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "客户端识别全靠下面这些正则。内置模式是出厂定义，你可以停用或改写；" +
                        "「我的规则」里自动学习和手动添加的规则走同一条管线，完全等价。" +
                        "改动立即生效（最多 2 秒缓存）。改坏了可以单条还原，或一键还原默认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---------------- 内置正则模式 ----------------
            item {
                IconText(
                    R.drawable.ic_wrench, "内置正则模式（${builtins.count { it.enabled }}/${builtins.size} 启用）",
                    iconSize = 18.dp, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
                )
            }
            items(builtins, key = { "b_" + it.id }, contentType = { "builtin" }) { info ->
                BuiltinRuleRow(
                    info = info,
                    onToggle = { enabled ->
                        scope.launch {
                            withContext(Dispatchers.IO) { PatternLearner.setBuiltinEnabled(context, info.id, enabled) }
                            reload()
                        }
                    },
                    onEdit = { editingBuiltin = info },
                    onReset = {
                        scope.launch {
                            withContext(Dispatchers.IO) { PatternLearner.resetBuiltin(context, info.id) }
                            reload()
                            toast("已还原「${info.label}」")
                        }
                    }
                )
            }

            // ---------------- 我的规则 ----------------
            item {
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconText(
                        R.drawable.ic_brain, "我的规则（${myRules.size}）",
                        iconSize = 18.dp, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { adding = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("添加规则")
                    }
                }
                Text(
                    "自动学习的规则和手动添加的规则都在这里，识别时一视同仁。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (myRules.isEmpty()) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(
                            "还没有自定义规则。可以点「添加规则」自己写一条，或继续使用 App 让它自动学习。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
            items(myRules, key = { "r_" + it.regex }, contentType = { "myrule" }) { rule ->
                MyRuleRow(
                    rule = rule,
                    onToggle = { enabled ->
                        scope.launch {
                            withContext(Dispatchers.IO) { PatternLearner.setRuleEnabled(context, rule.regex, enabled) }
                            reload()
                        }
                    },
                    onEdit = { editingRule = rule },
                    onDelete = {
                        scope.launch {
                            withContext(Dispatchers.IO) { PatternLearner.deleteRule(context, rule.regex) }
                            reload()
                            toast("已删除「${rule.label}」")
                        }
                    }
                )
            }

            // ---------------- 一键还原 ----------------
            item {
                Spacer(Modifier.height(4.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        IconText(
                            R.drawable.ic_refresh_cw, "还原默认规则",
                            iconSize = 18.dp,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "把内置正则的停用/改写全部撤回出厂状态，并清空「我的规则」（含自动学习与手动添加）。" +
                                "不会动预存地址、常用取件点、已学习的排除词和识别统计。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { confirmReset = true },
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("一键还原默认") }
                    }
                }
            }
        }
    }

    // ---------------- 弹窗 ----------------
    editingBuiltin?.let { info ->
        RegexEditDialog(
            title = "改写内置正则",
            subtitle = info.label,
            initialRegex = info.regex,
            initialLabel = null,
            initialType = null,
            showRuleFields = false,
            onDismiss = { editingBuiltin = null },
            onSave = { regex, _, _ ->
                scope.launch {
                    val check = PatternLearner.validateRegex(regex)
                    if (!check.ok) {
                        toast(check.error ?: "正则不合法")
                    } else {
                        withContext(Dispatchers.IO) { PatternLearner.setBuiltinRegex(context, info.id, regex) }
                        editingBuiltin = null
                        reload()
                        toast("已更新「${info.label}」")
                    }
                }
            }
        )
    }

    if (adding || editingRule != null) {
        val target = editingRule
        RegexEditDialog(
            title = if (target == null) "添加规则" else "编辑规则",
            subtitle = if (target == null) "整段匹配到的内容会被当作码值" else target.label,
            initialRegex = target?.regex ?: "",
            initialLabel = target?.label,
            initialType = target?.type,
            showRuleFields = true,
            onDismiss = { adding = false; editingRule = null },
            onSave = { regex, type, label ->
                scope.launch {
                    val check = PatternLearner.validateRegex(regex)
                    if (!check.ok) {
                        toast(check.error ?: "正则不合法")
                        return@launch
                    }
                    if (check.warning != null) toast(check.warning)
                    val result = withContext(Dispatchers.IO) {
                        if (target == null) PatternLearner.addUserRule(context, regex, type, label)
                        else PatternLearner.updateRule(context, target.regex, regex, type, label)
                    }
                    if (result.isSuccess) {
                        adding = false; editingRule = null
                        reload()
                        toast(if (target == null) "已添加规则" else "已保存修改")
                    } else {
                        toast(result.exceptionOrNull()?.message ?: "保存失败")
                    }
                }
            }
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("还原默认规则？") },
            text = {
                Text(
                    "将撤回 ${builtins.count { !it.enabled || it.overridden }} 项内置正则改动，" +
                        "并清空 ${myRules.size} 条自定义规则。\n\n" +
                        "此操作不可撤销（预存地址、常用取件点、排除词不受影响）。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val (rules, overrides) = withContext(Dispatchers.IO) {
                            PatternLearner.restoreDefaults(context)
                        }
                        confirmReset = false
                        reload()
                        toast("已还原默认：清掉 $rules 条自定义规则、$overrides 项内置改动")
                    }
                }) { Text("确认还原", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } }
        )
    }
}

/* ═══════════════════ 行 ═══════════════════ */

@Composable
private fun BuiltinRuleRow(
    info: CodeExtractor.BuiltinRuleInfo,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onReset: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (info.enabled) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            info.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (info.enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (info.overridden) {
                            Spacer(Modifier.size(6.dp))
                            AssistChip(
                                onClick = {},
                                label = { Text("已改写", style = MaterialTheme.typography.labelSmall) },
                                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                            )
                        }
                        if (!info.enabled) {
                            Spacer(Modifier.size(6.dp))
                            Text("已停用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text(
                        info.regex,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!info.editable) {
                        Text(
                            "该模式与识别逻辑绑定，只支持停用/还原",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TypeIcon(info.type)
                Switch(checked = info.enabled, onCheckedChange = onToggle)
            }
            if (info.editable || info.overridden || !info.enabled) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    if (info.editable) {
                        TextButton(onClick = onEdit) { Text("改写", style = MaterialTheme.typography.labelSmall) }
                    }
                    if (info.overridden || !info.enabled) {
                        TextButton(onClick = onReset) { Text("还原", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MyRuleRow(
    rule: PatternLearner.LearnedRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (rule.enabled) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(rule.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.size(6.dp))
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    if (rule.source == PatternLearner.SOURCE_USER) "手动" else "自动学习",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (rule.source == PatternLearner.SOURCE_USER)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                        if (rule.badCount >= 3) {
                            Spacer(Modifier.size(6.dp))
                            Text("已自动停用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        } else if (rule.decayed) {
                            Spacer(Modifier.size(6.dp))
                            Text("已衰减", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        rule.regex,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TypeIcon(rule.type)
                Switch(checked = rule.enabled && rule.badCount < 3, onCheckedChange = onToggle)
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onEdit) { Text("编辑", style = MaterialTheme.typography.labelSmall) }
                TextButton(
                    onClick = onDelete,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun TypeIcon(type: String) {
    Image(
        painter = painterResource(
            when (type) {
                "pickup_food" -> R.drawable.ic_cup_soda
                "coupon" -> R.drawable.ic_ticket
                else -> R.drawable.ic_package
            }
        ),
        contentDescription = null,
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.size(18.dp)
    )
}

/* ═══════════════════ 正则编辑弹窗（新增/编辑规则 & 改写内置） ═══════════════════ */

@Composable
private fun RegexEditDialog(
    title: String,
    subtitle: String,
    initialRegex: String,
    initialLabel: String?,
    initialType: String?,
    /** 是否显示「名称 / 类型」字段：新增或编辑自定义规则=true；改写内置正则=false（只有正则一个字段）。 */
    showRuleFields: Boolean,
    onDismiss: () -> Unit,
    onSave: (regex: String, type: String, label: String) -> Unit
) {
    var regex by remember { mutableStateOf(initialRegex) }
    var label by remember { mutableStateOf(initialLabel ?: "") }
    var type by remember { mutableStateOf(initialType ?: "pickup_parcel") }

    // 实时校验：让用户在按保存之前就看到问题（而不是保存后才弹错误）
    val check = remember(regex) {
        if (regex.isBlank()) null else PatternLearner.validateRegex(regex)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = regex,
                    onValueChange = { regex = it },
                    label = { Text("正则表达式") },
                    singleLine = false,
                    maxLines = 4,
                    isError = check?.ok == false,
                    supportingText = {
                        when {
                            check == null -> Text("整段匹配到的内容会被当作码值；全角符号（【】（）：等）会自动转半角",
                                style = MaterialTheme.typography.labelSmall)
                            check.ok.not() -> Text(check.error ?: "", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                            check.warning != null -> Text(check.warning, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary)
                            else -> Text("看起来没问题", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (showRuleFields) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("名称（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("类型", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("pickup_parcel" to "取件码", "pickup_food" to "取餐码", "coupon" to "券码").forEach { (v, t) ->
                            FilterChip(selected = type == v, onClick = { type = v }, label = { Text(t) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = regex.isNotBlank() && check?.ok != false,
                onClick = { onSave(regex, type, label) }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
