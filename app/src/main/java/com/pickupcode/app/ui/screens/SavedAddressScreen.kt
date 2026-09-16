package com.pickupcode.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pickupcode.app.learner.CommonStationStore
import com.pickupcode.app.learner.SavedAddressStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「常用取件地址」（预存地址）管理页。
 *
 * 模型就两个字段（用户 2026-09-15 的想法）：
 *  - **完整名称**：命中后写进记录、在主页卡片显示的那个值
 *  - **关键词**（一个至多个）：唯一匹配依据；OCR 文本命中任一关键词 → 用完整名称
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedAddressScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var items by remember { mutableStateOf<List<SavedAddressStore.SavedAddress>>(emptyList()) }
    var editing by remember { mutableStateOf<SavedAddressStore.SavedAddress?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var importCandidates by remember { mutableStateOf<List<String>>(emptyList()) }

    suspend fun reload() {
        items = withContext(Dispatchers.IO) { SavedAddressStore.getAll(context) }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("常用取件地址") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editing = null
                        showEditor = true
                    }) { Icon(Icons.Default.Add, contentDescription = "新增") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "填「完整名称」+「关键词」：识别到任一关键词时，就用完整名称替换掉识别结果。" +
                    "关键词留空则用完整名称匹配。数据仅加密保存在本机。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val candidates = withContext(Dispatchers.IO) {
                            val stations = CommonStationStore.getCommonStations(context).map { it.name }
                            val points = CommonStationStore.getPickupPoints(context).map { it.name }
                            (points + stations).filter { it.isNotBlank() }.distinct().take(30)
                        }
                        if (candidates.isEmpty()) {
                            snackbar.showSnackbar("还没有可导入的历史记录")
                        } else {
                            importCandidates = candidates
                            showImport = true
                        }
                    }
                }) { Text("从历史导入") }

                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { SavedAddressStore.clear(context) }
                        reload()
                        snackbar.showSnackbar("已清除全部常用地址")
                    }
                }) { Text("全部清除") }
            }

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有预存地址\n点右上角 + 添加，或从历史导入", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(items, key = { it.id }) { item ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clickable {
                                            editing = item
                                            showEditor = true
                                        }
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            item.fullName,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (item.origin == "promoted") {
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "自动提升",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    // 关键词：命中的依据；为空说明用完整名称兜底
                                    val kw = if (item.keywords.isEmpty()) "（无关键词，用完整名称匹配）"
                                    else "关键词：" + item.keywords.joinToString("、")
                                    Text(
                                        kw,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = item.enabled,
                                    onCheckedChange = { on ->
                                        scope.launch {
                                            withContext(Dispatchers.IO) { SavedAddressStore.setEnabled(context, item.id, on) }
                                            reload()
                                        }
                                    }
                                )
                                IconButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { SavedAddressStore.delete(context, item.id) }
                                        reload()
                                        snackbar.showSnackbar("已删除（不会再被自动学回来）")
                                    }
                                }) { Icon(Icons.Default.Delete, contentDescription = "删除") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        SavedAddressEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onConfirm = { fullName, keywords ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        SavedAddressStore.upsert(
                            context,
                            (editing ?: SavedAddressStore.SavedAddress(fullName = fullName))
                                .copy(
                                    fullName = fullName,
                                    keywords = keywords.split(",", "，", "、").map { it.trim() }.filter { it.isNotBlank() }
                                )
                        )
                    }
                    showEditor = false
                    reload()
                }
            }
        )
    }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("从历史记录导入") },
            text = {
                Column {
                    Text(
                        "导入后「完整名称」与「关键词」都先填成该站名，建议再补一个更短的关键词。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(importCandidates) { name ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                SavedAddressStore.upsert(
                                                    context,
                                                    SavedAddressStore.SavedAddress(
                                                        fullName = name,
                                                        keywords = listOf(name),
                                                        origin = "promoted"
                                                    )
                                                )
                                            }
                                            showImport = false
                                            reload()
                                        }
                                    }
                                    .padding(vertical = 8.dp)
                            ) { Text(name, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            SavedAddressStore.upsertAll(
                                context,
                                importCandidates.map {
                                    SavedAddressStore.SavedAddress(
                                        fullName = it,
                                        keywords = listOf(it),
                                        origin = "promoted"
                                    )
                                }
                            )
                        }
                        showImport = false
                        reload()
                    }
                }) { Text("全部导入") }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SavedAddressEditorDialog(
    initial: SavedAddressStore.SavedAddress?,
    onDismiss: () -> Unit,
    onConfirm: (fullName: String, keywords: String) -> Unit
) {
    var fullName by remember { mutableStateOf(initial?.fullName ?: "") }
    var keywords by remember { mutableStateOf(initial?.keywords?.joinToString(",") ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增常用地址" else "编辑常用地址") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = { Text("完整名称（识别到后就用它）") },
                    placeholder = { Text("如：长兴路北段菜鸟驿站") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    label = { Text("关键词，逗号分隔（命中任一即触发）") },
                    placeholder = { Text("如：北段驿站,长兴路") },
                    singleLine = true
                )
                Text(
                    "关键词留空时用完整名称匹配。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(fullName.trim(), keywords) },
                enabled = fullName.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
