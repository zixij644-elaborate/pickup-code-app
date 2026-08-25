package com.pickupcode.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pickupcode.app.data.AppDatabase
import com.pickupcode.app.data.CodeHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DedupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = AppDatabase.getInstance(context)
    var groups by remember { mutableStateOf<Map<String, List<CodeHistory>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    fun reload() {
        scope.launch {
            val raw = withContext(Dispatchers.IO) { db.repository.getDuplicateEntries() }
            // 按 code+type 聚合：每个重复码一组，组内是全部重复记录
            val grouped = raw.groupBy { "${it.code}\u0000${it.type}" }
            groups = grouped
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("进行重复整理") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                }
            )
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("没有重复记录", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("所有取餐码/取件码都是唯一的。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(groups.entries.toList(), key = { it.key }) { (key, entries) ->
                    DuplicateGroupCard(entries = entries, onChanged = { reload() })
                }
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(entries: List<CodeHistory>, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = AppDatabase.getInstance(context)
    if (entries.isEmpty()) return
    val code = entries.first().code
    val type = entries.first().type

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            val icon = when (type) {
                "pickup_food" -> "🥤"
                "coupon" -> "🎟️"
                else -> "📦"
            }
            Text("$icon $code  (${entries.size} 条重复)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))

            entries.forEachIndexed { idx, e ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${e.source} · ${formatDedupTime(e.timestamp)}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            // 保留这一条，批量删除其他重复（一次性事务，避免逐条删中断残留）
                            val toDelete = entries.filterIndexed { i, _ -> i != idx }.map { it.id }
                            if (toDelete.isNotEmpty()) db.repository.deleteByIds(toDelete)
                            onChanged()
                        }
                    }) { Text("保留这条", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}

private val DEDUP_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private fun formatDedupTime(epochMillis: Long): String {
    return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DEDUP_TIME_FORMATTER)
}
