package com.pickupcode.app.ui.screens

import com.pickupcode.app.ui.components.IconText
import com.pickupcode.app.R
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.pickupcode.app.extractor.RecognitionDebugStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 识别调试面板（DEBUG 专用，蓝本 BiuLand DebugReport）：展示最近一次识别的 OCR 行/候选/最终结果/地址。
 * 「导出语料」把本次识别导出成 corpus 回归用例文本（见 RecognitionDebugStore.exportFixture）。
 */
@Composable
fun RecognitionDebugDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val snap = RecognitionDebugStore.latest()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                IconText(R.drawable.ic_search, "识别调试", iconSize = 20.dp, style = MaterialTheme.typography.titleLarge)
                Row {
                    IconText(R.drawable.ic_upload, "导出语料", iconSize = 18.dp, style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { exportCorpus(ctx) }
                            .padding(4.dp))
                    Text("关闭", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onDismiss)
                            .padding(4.dp))
                }
            }
            if (snap == null) {
                Text("暂无快照。先触发一次识别（截图/分享/磁贴），再打开此面板。",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 24.dp))
                return@Column
            }
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            Text("来源=${snap.source}  时间=${fmt.format(Date(snap.timeMs))}  OCR行=${snap.lines.size} 候选=${snap.candidates.size} 结果=${snap.finalResults.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.padding(top = 8.dp)) {
                item {
                    Text("── 地址结果 ──", style = MaterialTheme.typography.titleSmall)
                    val a = snap.address
                    Text(if (a != null) "地址=[${a.fullAddress}] 站点=[${a.station}] 柜号=[${a.cabinet}] 来源=${a.from}" else "（无地址结果）",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("── 最终结果（实际入库）──", style = MaterialTheme.typography.titleSmall)
                }
                items(snap.finalResults) { c ->
                    Text("${c.score.toString().padEnd(6)} ${c.type.padEnd(12)} ${c.code}  src=${c.source}  line=${c.lineIndex}",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("── 候选（按分数降序）──", style = MaterialTheme.typography.titleSmall)
                }
                items(snap.candidates) { c ->
                    Text("${c.score.toString().padEnd(6)} ${c.type.padEnd(12)} ${c.code}  src=${c.source}  line=${c.lineIndex}",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text("    ctx: ${c.context.take(60)}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("── OCR 行 ──", style = MaterialTheme.typography.titleSmall)
                }
                items(snap.lines) { l ->
                    Text("${l.index.toString().padStart(2)} ${l.box} conf=${l.confidence}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                    Text("    ${l.text.take(80)}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

/** 导出最近一次识别为 corpus 语料并走分享面板（文本直接附在 EXTRA_TEXT，便于粘贴进仓库）。 */
private fun exportCorpus(context: Context) {
    val file = RecognitionDebugStore.exportFixtureFile(context)
    if (file == null) {
        Toast.makeText(context, "暂无快照可导出", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(Intent.EXTRA_TEXT, file.readText())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "导出识别语料").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
    }
}
