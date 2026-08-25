package com.pickupcode.app.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.util.Log
import androidx.compose.ui.unit.sp
import com.pickupcode.app.data.AppDatabase
import com.pickupcode.app.data.CodeHistory
import com.pickupcode.app.ui.theme.TypeCoupon
import com.pickupcode.app.ui.theme.TypeFood
import com.pickupcode.app.ui.theme.TypeParcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    hasNotificationPermission: Boolean,
    isAccessibilityEnabled: Boolean,
    accessibilityEnabledInSettings: Boolean,
    hideAccessibilityCard: Boolean,
    hideGuideCard: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onHideAccessibilityCard: () -> Unit,
    onHideGuideCard: () -> Unit,
    onSettingsClick: () -> Unit,
    onItemClick: (Long) -> Unit,
    onFabClick: () -> Unit,
    onTrashClick: () -> Unit,
    onStatsClick: () -> Unit,
    onDedupClick: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    // 注册到 ViewModelStore，使 viewModelScope 随 Activity/导航正确 onCleared（勿用 remember 假 VM）
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(db.repository))
    val activeHistory by vm.activeHistory.collectAsState()
    val trashHistory by vm.trashHistory.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val dedupCount by vm::dedupCount
    var typeFilter by remember { mutableStateOf("all") }
    var guideExpanded by remember { mutableStateOf(false) }
    // 分组方式：time=按时间 / address=按地址聚合（rememberSaveable：旋转屏幕保持）
    var groupMode by rememberSaveable { mutableStateOf("time") }

    val filteredHistory = remember(activeHistory, typeFilter) {
        activeHistory.filter { h ->
            when (typeFilter) {
                "food" -> h.type == "pickup_food"
                "parcel" -> h.type == "pickup_parcel"
                "coupon" -> h.type == "coupon"
                else -> true
            }
        }
    }

    // 时间分组（Medium-4: remember 键含日期，跨午夜后重组能重算 todayStart）
    val today = LocalDate.now()
    val todayStart = remember(today) {
        today.atStartOfDay(ZoneId.systemDefault()).toEpochSecond() * 1000
    }
    val yesterdayStart = todayStart - 24 * 60 * 60 * 1000

    val grouped: Map<String, List<CodeHistory>> = remember(filteredHistory) {
        filteredHistory.groupBy { item ->
            when {
                item.timestamp >= todayStart -> "今天"
                item.timestamp >= yesterdayStart -> "昨天"
                else -> "更早"
            }
        }
    }
    val groupOrder = listOf("今天", "昨天", "更早").filter { it in grouped }

    // 地址聚合分组（空地址归「未填地址」，码最多的地址排前）
    val addressGroups: List<Pair<String, List<CodeHistory>>> =
        remember(filteredHistory) { HomeGrouping.byAddress(filteredHistory) }

            LaunchedEffect(Unit) { vm.cleanExpired() }

            // 共享操作：标记已取/删除 → 移入回收站 → snackbar 撤销
            fun markAsDone(item: CodeHistory) {
                vm.markAsDone(item,
                    onSuccess = {
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "已移至回收站，24小时后自动删除",
                                actionLabel = "撤销",
                                duration = SnackbarDuration.Short
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                vm.undoDone(item, trashHistory)
                            }
                        }
                    },
                    onError = { msg ->
                        scope.launch { snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short) }
                    }
                )
            }

    LaunchedEffect(activeHistory) { vm.refreshDedupCount() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("码上闪记", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                },
                actions = {
                    IconButton(onClick = onStatsClick) {
                        Icon(Icons.Default.Info, "统计")
                    }
                    IconButton(onClick = onTrashClick) {
                        Icon(Icons.Default.RestoreFromTrash, "回收站")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.AutoMirrored.Filled.List, "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onFabClick,
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, "手动输入")
            }
        }
    ) { padding ->
        // 顶部提示层：snackbar（"已移至回收站"/错误提示）固定显示在界面上方（顶部栏之下），
        // 不再默认贴屏幕底部（用户要求：弹窗放到界面上方）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
            // FilterChips
            item {
                FilterChipRow(currentFilter = typeFilter, onFilterChange = { typeFilter = it })
            }

            // 分组方式切换（按时间 / 按地址聚合）
            item {
                GroupModeToggle(
                    mode = groupMode,
                    onModeChange = { groupMode = it },
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                )
            }

            // 通知权限
            if (!hasNotificationPermission) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("🔔 需要通知权限", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text("开启后才能在锁屏/通知栏显示取餐取件码",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onRequestNotificationPermission) { Text("授权") }
                        }
                    }
                }
            }

            // 无障碍服务卡片
            if (!hideAccessibilityCard) {
                item {
                    // 三态：开启且运行 / 设置里开了但服务未运行（被系统杀，vivo 常见）/ 未开启
                    val containerColor = when {
                        isAccessibilityEnabled -> MaterialTheme.colorScheme.primaryContainer
                        accessibilityEnabledInSettings -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.tertiaryContainer
                    }
                    val title = when {
                        isAccessibilityEnabled -> "✅ 无障碍服务已开启"
                        accessibilityEnabledInSettings -> "⚠️ 无障碍服务未在运行"
                        else -> "🔧 需要开启无障碍服务"
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = containerColor),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(title, style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f))
                                IconButton(onClick = onHideAccessibilityCard, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "隐藏",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (isAccessibilityEnabled) {
                                Text("打开控制面板 → 点✏️编辑 → 找到「码上闪记」→ 拖到面板",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                Text("点磁贴后滑出控制面板，在取件码界面稍候，自动识别并通知结果",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium)
                            } else {
                                Text(
                                    if (accessibilityEnabledInSettings)
                                        "系统设置里已开启，但服务实际未在运行——大概率被系统省电策略关闭了。点下方按钮到设置页把「码上闪记」关掉再打开一次。"
                                    else "开启后点快捷设置磁贴即可自动识别屏幕上的取餐码/取件码",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = onEnableAccessibility) { Text(if (accessibilityEnabledInSettings) "去重新开启" else "去开启") }
                            }
                        }
                    }
                }
            }

            // 引导卡片（可折叠）
            if (!hideGuideCard) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { guideExpanded = !guideExpanded }
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                RoundedCornerShape(14.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📥 怎么添加取餐码/取件码/券码?",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f))
                            Text(if (guideExpanded) "▴" else "▾",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AnimatedVisibility(visible = guideExpanded) {
                            Column {
                                Spacer(Modifier.height(6.dp))
                                Text("·从短信/聊天 App 分享", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("（在短信/聊天里长按选中文字或点分享 → 选「码上闪记」）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("·点右下角 ➕ 手动粘贴", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("·通过无障碍服务调用OCR识别", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = onHideGuideCard,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("我知道了")
                                }
                            }
                        }
                    }
                }
            }

            // 回收站提示
            if (trashHistory.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable(onClick = onTrashClick),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🗑️ 回收站有 ${trashHistory.size} 条记录，24小时后自动删除",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 去重入口：有重复时才显示（用户要求：无重复时不显示）。
            // 注意：H6 起保存走 saveOrUpdate（同码+同类型自动合并成一行），新识别不产生重复行，
            // 只有历史遗留的重复组（或手动产生的）才会让入口出现；清理完后入口自动隐藏。
            if (dedupCount > 0) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable(onClick = onDedupClick),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🔄 发现 ${dedupCount} 组重复记录，点击查看 →",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            // 空状态
            if (filteredHistory.isEmpty()) {
                item {
                    if (typeFilter != "all") {
                        // 筛选后空状态：提示 + 清除筛选
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("没有符合筛选条件的记录",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(12.dp))
                                TextButton(onClick = {
                                    typeFilter = "all"
                                }) {
                                    Text("清除筛选条件")
                                }
                            }
                        }
                    } else {
                        // 无记录占位
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("暂无记录",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 列表：按地址聚合 / 按时间分组
            if (groupMode == "address") {
                addressGroups.forEach { (addr, groupItems) ->
                    item(key = "addr_header_$addr") {
                        AddressGroupHeader(address = addr.ifBlank { "未填地址" }, count = groupItems.size)
                    }
                    items(groupItems, key = { it.id }) { cardItem ->
                        CodeHistoryCard(
                            item = cardItem,
                            onClick = { onItemClick(cardItem.id) },
                            onDone = { markAsDone(cardItem) },
                            onDelete = { markAsDone(cardItem) }
                        )
                    }
                }
            } else {
                groupOrder.forEach { groupLabel ->
                    item(key = "header_$groupLabel") {
                        TimeGroupHeader(label = groupLabel)
                    }
                    val groupItems = grouped[groupLabel] ?: emptyList()
                    items(groupItems, key = { it.id }) { item ->
                        CodeHistoryCard(
                            item = item,
                            onClick = { onItemClick(item.id) },
                            onDone = { markAsDone(item) },
                            onDelete = { markAsDone(item) }
                        )
                    }
                }
            }
            }  // LazyColumn 结束
            // 顶部提示层：画在列表之上，固定在界面上方（顶部栏之下）
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }  // Box 结束
    }
}
