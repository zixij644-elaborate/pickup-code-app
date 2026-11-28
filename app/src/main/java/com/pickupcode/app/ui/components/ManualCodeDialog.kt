package com.pickupcode.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pickupcode.app.extractor.CodeValidator
import com.pickupcode.app.learner.SavedAddressStore
import com.pickupcode.app.ui.theme.TypeFood
import com.pickupcode.app.ui.theme.TypeParcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 手动输入取餐码/取件码的对话框
 */
@Composable
fun ManualCodeDialog(
    onDismiss: () -> Unit,
    onConfirm: (code: String, type: String, source: String, pickupAddress: String) -> Unit
) {
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var pickupAddress by remember { mutableStateOf("") }
    var codeType by remember { mutableStateOf("pickup_food") }
    val savedAddresses by produceState<List<String>>(initialValue = emptyList()) {
        val loaded = withContext(Dispatchers.IO) {
            SavedAddressStore.getAll(context)
                .filter { it.enabled && it.fullName.isNotBlank() }
                .map { it.fullName }
        }
        value = loaded
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动录入") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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

                OutlinedTextField(
                    value = pickupAddress,
                    onValueChange = { pickupAddress = it },
                    label = {
                        Text(if (codeType == "pickup_food") "取餐地址（选填）" else "取件地址（选填）")
                    },
                    placeholder = {
                        Text(
                            if (codeType == "pickup_food") "如：万达广场1楼瑞幸店"
                            else "如：长兴路北段菜鸟驿站"
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (savedAddresses.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "常用地址",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(savedAddresses) { addr ->
                                val selected = pickupAddress.trim() == addr
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        pickupAddress = if (selected) "" else addr
                                    },
                                    label = {
                                        Text(
                                            text = addr,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
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
                        onConfirm(code.trim(), codeType, src, pickupAddress.trim())
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
