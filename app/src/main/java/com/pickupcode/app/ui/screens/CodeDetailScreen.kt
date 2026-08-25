package com.pickupcode.app.ui.screens

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import com.pickupcode.app.data.CodeHistory
import com.pickupcode.app.extractor.CodeExtractor
import com.pickupcode.app.extractor.CodeValidator
import com.pickupcode.app.learner.CommonStationStore
import com.pickupcode.app.learner.PatternLearner
import com.pickupcode.app.ui.components.BrandLogo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Medium-5: 确认/标记错误状态（含异步加载的初始值），避免组合期同步读 SharedPreferences。 */
private data class DetailConfirmState(
    val codeConfirmed: Boolean = false,
    val codeIncorrect: Boolean = false,
    val sourceConfirmed: Boolean = false,
    val sourceIncorrect: Boolean = false,
    val addrConfirmed: Boolean = false,
    val addrIncorrect: Boolean = false
)

/** 详情页可编辑字段（M20：编辑走定向 UPDATE，避免整行覆盖丢更新）。
 *  命名用 EditField，避免与下方同名 Composable 函数 EditableField 产生声明冲突。 */
enum class EditField { CODE, SOURCE, CABINET, ADDRESS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeDetailScreen(
    item: CodeHistory,
    onBack: () -> Unit,
    onUpdateField: (EditField, String) -> Unit,
    onMarkDone: ((Long) -> Unit)? = null
) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    // Medium-5: 6 个 PatternLearner 状态改用 produceState 在 IO 线程异步读取初始值
    var confirmState by remember { mutableStateOf(DetailConfirmState()) }
    val loadedConfirmState by produceState(initialValue = DetailConfirmState(), item.id) {
        // 先算后赋：lint ProduceStateDoesNotAssignValue 要求 value 赋值直接位于 producer 块内
        val loaded = withContext(Dispatchers.IO) {
            DetailConfirmState(
                codeConfirmed = PatternLearner.isCodeConfirmed(ctx, item.id),
                codeIncorrect = PatternLearner.isCodeIncorrect(ctx, item.id),
                sourceConfirmed = PatternLearner.isSourceConfirmed(ctx, item.id),
                sourceIncorrect = PatternLearner.isSourceIncorrect(ctx, item.id),
                addrConfirmed = PatternLearner.isAddrConfirmed(ctx, item.id),
                addrIncorrect = PatternLearner.isAddrIncorrect(ctx, item.id)
            )
        }
        value = loaded
    }
    LaunchedEffect(loadedConfirmState) {
        // 仅在用户尚未交互（confirmState 仍为默认全 false）时回填异步加载的初始值，
        // 避免加载完成后整份覆盖用户在慢设备上刚点的「确认/标记错误」。
        if (confirmState == DetailConfirmState()) {
            confirmState = loadedConfirmState
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("详情") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("类型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val label = when (item.type) {
                        "pickup_parcel" -> "取件码"
                        "coupon" -> "券码"
                        else -> "取餐码"
                    }
                    val icon = when (item.type) {
                        "pickup_parcel" -> "📦"
                        "coupon" -> "🎟️"
                        else -> "🥤"
                    }
                    Text("$icon $label", fontSize = 18.sp)
                }
            }

            EditableField(label = "码值", value = item.code, displayFontSize = 28.sp, displayFontWeight = FontWeight.Bold,
                onSave = { onUpdateField(EditField.CODE, it) })
            if (item.isActive) {
                InlineConfirm("码值正确", confirmed = confirmState.codeConfirmed, incorrect = confirmState.codeIncorrect,
                    onCorrect = {
                        confirmState = confirmState.copy(codeConfirmed = true)
                        PatternLearner.setCodeConfirmed(ctx, item.id, true)
                        scope.launch(Dispatchers.IO) {
                            PatternLearner.recordVerified(ctx, CodeValidator.getPatternId(item.code))
                        }
                    },
                    onIncorrect = {
                        confirmState = confirmState.copy(codeIncorrect = true)
                        PatternLearner.setCodeIncorrect(ctx, item.id, true)
                        scope.launch(Dispatchers.IO) {
                            PatternLearner.recordCodeIncorrect(ctx, CodeValidator.getPatternId(item.code))
                            // A3: 该码值加入可学习排除，之后识别不再把它当取件码。
                            // 注意：不把误报文本喂入学习池(unmatched_samples)——否则会学生出与"排除"矛盾的新规则。
                            PatternLearner.addExclude(ctx, item.code)
                            // 回喂已学规则：给匹配到该码的规则加 badCount，累积 ≥3 自动停用
                            PatternLearner.markLearnedRuleBad(ctx, item.code)
                        }
                    }
                )
            }

            EditableField(label = "来源", value = item.source, displayFontSize = 18.sp,
                onSave = { onUpdateField(EditField.SOURCE, it) },
                leadingIcon = {
                    // 品牌 logo（未收录的品牌不显示，仅文字）
                    val lr = BrandLogo.logoRes(item.source, item.shareSourceName, item.shareSourcePkg)
                    if (lr != null) {
                        Box(
                            modifier = Modifier.size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(painterResource(lr), contentDescription = item.source,
                                contentScale = ContentScale.Fit, modifier = Modifier.size(28.dp))
                        }
                    }
                },
                trailingAction = {
                    // 🚪 跳转来源 App（与取件地址卡的 📍 同布局：右端图标）。仅当有关分享来源显示。
                    if (item.shareSourcePkg.isNotBlank()) {
                        IconButton(onClick = { com.pickupcode.app.share.ShareReceiver.openApp(ctx, item.shareSourcePkg) }) {
                            Text("🚪", fontSize = 20.sp)
                        }
                    }
                })
            if (item.isActive) {
                InlineConfirm("来源正确", confirmed = confirmState.sourceConfirmed, incorrect = confirmState.sourceIncorrect,
                    onCorrect = {
                        confirmState = confirmState.copy(sourceConfirmed = true)
                        PatternLearner.setSourceConfirmed(ctx, item.id, true)
                        scope.launch(Dispatchers.IO) {
                            PatternLearner.recordSourceMatch(ctx, item.source)
                        }
                    },
                    onIncorrect = {
                        confirmState = confirmState.copy(sourceIncorrect = true)
                        PatternLearner.setSourceIncorrect(ctx, item.id, true)
                        scope.launch(Dispatchers.IO) {
                            PatternLearner.recordSourceIncorrect(ctx, item.source)
                        }
                    }
                )
            }

            if (item.cabinetNumber.isNotBlank()) {
                EditableField(label = "柜号", value = item.cabinetNumber, displayFontSize = 16.sp,
                    onSave = { onUpdateField(EditField.CABINET, it) })
            }

            if (item.pickupAddress.isNotBlank()) {
                EditableField(label = "取件地址", value = item.pickupAddress, displayFontSize = 16.sp,
                    onSave = { onUpdateField(EditField.ADDRESS, it) },
                    trailingAction = {
                        // 📍 唤起导航：用 geo: URI 让系统地图应用弹出选择（与分享来源卡片的 🚪 同布局：右端图标）
                        IconButton(onClick = { launchNavigation(ctx, item.pickupAddress) }) {
                            Text("📍", fontSize = 20.sp)
                        }
                    })

                // C2: 常用取件点提示（IO 线程查，避免组合期主线程解析 JSON）
                val freqPoint by produceState<CommonStationStore.PickupPoint?>(null, item.pickupAddress) {
                    val point = withContext(Dispatchers.IO) { CommonStationStore.isFrequentPickupPoint(ctx, item.pickupAddress) }
                    value = point
                }
                val freq = freqPoint  // 捕获局部值，便于智能转换
                if (freq != null) {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {},
                            label = { Text("🏠 常用取件点 · 已取 ${freq.count} 次", style = MaterialTheme.typography.labelSmall) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        )
                    }
                }

                // Show geo verification badge
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.geoVerified) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text("📍 地图已验证", style = MaterialTheme.typography.labelSmall)
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                        if (item.geoFormattedAddress.isNotBlank() && item.geoFormattedAddress != item.pickupAddress) {
                            Text(
                                item.geoFormattedAddress,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
                if (item.isActive) {
                    InlineConfirm("地址正确", confirmed = confirmState.addrConfirmed, incorrect = confirmState.addrIncorrect,
                        onCorrect = {
                            confirmState = confirmState.copy(addrConfirmed = true)
                            PatternLearner.setAddrConfirmed(ctx, item.id, true)
                            scope.launch(Dispatchers.IO) {
                                PatternLearner.recordAddressVerified(ctx, item.pickupAddress, 1.0f)
                            }
                        },
                        onIncorrect = {
                            confirmState = confirmState.copy(addrIncorrect = true)
                            PatternLearner.setAddrIncorrect(ctx, item.id, true)
                            scope.launch(Dispatchers.IO) {
                                PatternLearner.recordAddressIncorrect(ctx, item.pickupAddress)
                            }
                        }
                    )
                }
            }

            var showFullscreen by remember { mutableStateOf(false) }
            if (item.screenshotPath.isNotBlank() && File(item.screenshotPath).exists()) {
                var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                // M3: 大图降采样解码，避免 4K 截图 BitmapFactory.decodeFile 直接 OOM
                LaunchedEffect(item.screenshotPath) { bitmap = withContext(Dispatchers.IO) { decodeSampledBitmap(item.screenshotPath, 1600) } }
                // 不手动 recycle：Compose 的 Bitmap.asImageBitmap() 与状态共享受管理时，手动 recycle 可能造成
                // 「已回收位图仍在绘制」崩溃（Canvas 绘制期 native 已释放）。交由 GC/Compose 生命周期管理。
                Card(Modifier.fillMaxWidth().clickable { showFullscreen = true },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("📷 截屏（点击放大）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        bitmap?.let { bmp -> Image(bitmap = bmp.asImageBitmap(), contentDescription = "截屏",
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.FillWidth) }
                        Text("👆 点击放大查看", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                if (showFullscreen) {
                    // 无边框全屏预览：黑底 + 完整图片（含长宽比）居中，点任意处关闭
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { showFullscreen = false },
                        properties = androidx.compose.ui.window.DialogProperties(
                            dismissOnBackPress = true,
                            dismissOnClickOutside = true,
                            usePlatformDefaultWidth = false  // 铺满全屏，去掉 AlertDialog 的默认宽度限制与卡片边框
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                                .clickable { showFullscreen = false },
                            contentAlignment = Alignment.Center
                        ) {
                            bitmap?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "截屏全屏",
                                    modifier = Modifier.fillMaxSize().padding(12.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("OCR 原始文本", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(item.rawTextSnippet.ifBlank { "（无原始数据）" }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
                }
            }

            Text(formatTimestamp(item.timestamp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (item.isActive) {
                val confirmAll: () -> Unit = {
                    if (!confirmState.codeConfirmed && !confirmState.codeIncorrect) {
                        confirmState = confirmState.copy(codeConfirmed = true)
                        PatternLearner.setCodeConfirmed(ctx, item.id, true)
                        scope.launch(Dispatchers.IO) { PatternLearner.recordVerified(ctx, CodeValidator.getPatternId(item.code)) }
                    }
                    if (!confirmState.sourceConfirmed && !confirmState.sourceIncorrect) {
                        confirmState = confirmState.copy(sourceConfirmed = true)
                        PatternLearner.setSourceConfirmed(ctx, item.id, true)
                        scope.launch(Dispatchers.IO) { PatternLearner.recordSourceMatch(ctx, item.source) }
                    }
                    if (item.pickupAddress.isNotBlank() && !confirmState.addrConfirmed && !confirmState.addrIncorrect) {
                        confirmState = confirmState.copy(addrConfirmed = true)
                        PatternLearner.setAddrConfirmed(ctx, item.id, true)
                        scope.launch(Dispatchers.IO) { PatternLearner.recordAddressVerified(ctx, item.pickupAddress, 1.0f) }
                    }
                }
                if (onMarkDone != null) {
                    Button(onClick = {
                        confirmAll()
                        // C2: 标记已取时把取件地址登记为常用取件点（IO 线程写盘，避免主线程同步 IO）
                        if (item.pickupAddress.isNotBlank()) {
                            scope.launch(Dispatchers.IO) { CommonStationStore.registerPickupPoint(ctx, item.pickupAddress) }
                        }
                        onMarkDone(item.id)
                    }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8DC0E0), contentColor = Color.White)) {
                        Text("📦 标记已取")
                    }
                }
                // C3: 稍后提醒（1 小时后推通知）
                Row(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            com.pickupcode.app.notification.CodeNotificationManager.remindLater(
                                ctx, item.code,
                                when (item.type) { "pickup_parcel" -> com.pickupcode.app.extractor.CodeExtractor.CodeType.pickup_parcel; "coupon" -> com.pickupcode.app.extractor.CodeExtractor.CodeType.coupon; else -> com.pickupcode.app.extractor.CodeExtractor.CodeType.pickup_food },
                                item.source
                            )
                            android.widget.Toast.makeText(ctx, "已设置 1 小时后稍后提醒", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Text("⏰ 稍后提醒")
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineConfirm(label: String, confirmed: Boolean, incorrect: Boolean, onCorrect: () -> Unit, onIncorrect: () -> Unit) {
    if (confirmed || incorrect) {
        Text(
            if (confirmed) "$label ✓" else "已标记错误",
            style = MaterialTheme.typography.labelSmall,
            color = if (confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCorrect, modifier = Modifier.weight(1f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = onIncorrect, modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text("标记错误", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EditableField(label: String, value: String, displayFontSize: androidx.compose.ui.unit.TextUnit,
                          displayFontWeight: FontWeight? = null, onSave: (String) -> Unit,
                          leadingIcon: (@Composable () -> Unit)? = null,
                          trailingAction: (@Composable () -> Unit)? = null) {
    var editing by remember { mutableStateOf(false) }
    var editedValue by remember(value) { mutableStateOf(value) }
    val scope = rememberCoroutineScope()
    // 卡片背景与主页一致（surfaceVariant 浅灰），配合 Sleek 极简风
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 前置图标（如：来源行的品牌 logo），可空则不占位
            if (leadingIcon != null) {
                Box(Modifier.padding(end = 12.dp), contentAlignment = Alignment.Center) {
                    leadingIcon()
                }
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                if (editing) {
                    OutlinedTextField(editedValue, { editedValue = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { editing = false }) { Text("取消") }
                        TextButton(onClick = { editing = false; scope.launch { onSave(editedValue) } }) { Text("保存") } }
                } else {
                    // H9: 深色模式下黑字不可读，改用 onSurface
                    Text(value, fontSize = displayFontSize, fontWeight = displayFontWeight, color = MaterialTheme.colorScheme.onSurface)
                    TextButton(
                        onClick = { editing = true; editedValue = value },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF8DC0E0))
                    ) { Text("编辑") }
                }
            }
            // 右侧尾部动作（如：地址卡片的 📍 唤起导航），可空则只占左侧
            if (trailingAction != null) {
                trailingAction()
            }
        }
    }
}

private val DETAIL_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private fun formatTimestamp(epochMillis: Long): String {
    return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()).format(DETAIL_TIMESTAMP_FORMATTER)
}

/** 唤起系统导航：Geo URI → 弹出已装地图 App（高德/百度/腾讯等）匹配导航。 */
private fun launchNavigation(context: android.content.Context, address: String) {
    if (address.isBlank()) return
    try {
        val encoded = android.net.Uri.encode(address, ",，，。. ")
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=$encoded"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.w("CodeDetail", "唤起导航失败: ${e.message}")
        android.widget.Toast.makeText(context, "无法打开导航应用", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** M3: 大图降采样解码（目标最长边 maxDim px，取 2 的幂采样），避免位图 OOM。 */
private fun decodeSampledBitmap(path: String, maxDim: Int): android.graphics.Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / 2 >= maxDim || h / 2 >= maxDim) { w /= 2; h /= 2; sample *= 2 }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(path, opts)
    } catch (_: Exception) {
        null
    }
}
