package com.pickupcode.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pickupcode.app.extractor.CodeExtractor
import com.pickupcode.app.extractor.CodeValidator
import com.pickupcode.app.ui.theme.TypeFood
import com.pickupcode.app.ui.theme.TypeParcel

/**
 * 手动输入取餐码/取件码的对话框
 */
@Composable
fun ManualCodeDialog(
    onDismiss: () -> Unit,
    onConfirm: (code: String, type: String, source: String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var codeType by remember { mutableStateOf("pickup_food") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动录入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 类型选择
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = codeType == "pickup_food",
                        onClick = { codeType = "pickup_food" },
                        label = { Text("取餐码") },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = TypeFood, // 取餐蓝
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                    FilterChip(
                        selected = codeType == "pickup_parcel",
                        onClick = { codeType = "pickup_parcel" },
                        label = { Text("取件码") },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = TypeParcel, // 取件紫
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }

                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    label = { Text("来源（品牌/驿站）") },
                    placeholder = { Text("如：瑞幸、菜鸟驿站") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("取餐码/取件码") },
                    placeholder = { Text("如：A-356 或 10-2-7507") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            // 格式白名单：与 AI 路径对齐——取餐码允许 2-3 位纯数字（瑞幸/蜜雪），仍过内容噪声检查
            val valid = code.trim().let { CodeValidator.isValidManualCode(it, codeType) }
            TextButton(
                onClick = {
                    if (valid) {
                        val src = source.ifBlank {
                            if (codeType == "pickup_food") "手动录入·取餐" else "手动录入·取件"
                        }
                        onConfirm(code.trim(), codeType, src)
                        onDismiss()
                    }
                },
                enabled = valid
            ) {
                Text(if (code.isNotBlank() && !valid) "格式不符" else "确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
