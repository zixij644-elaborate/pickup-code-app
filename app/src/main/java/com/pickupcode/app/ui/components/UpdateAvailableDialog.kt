package com.pickupcode.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pickupcode.app.util.UpdateChecker

/**
 * 发现可用更新时的提示弹窗。
 *
 * 展示当前版本与目标版本、Release 更新说明，并提供「前往更新」「稍后再说」以及可选的「忽略此版本」。
 */
@Composable
fun UpdateAvailableDialog(
    release: UpdateChecker.ReleaseInfo,
    currentVersion: String,
    onDismiss: () -> Unit,
    onIgnoreVersion: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "发现新版本 v${release.versionName}",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "当前版本 v$currentVersion → 最新版本 v${release.versionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                val notes = release.notes.ifBlank {
                    "有新版本可供下载更新，建议前往 GitHub Releases 获取最新版。"
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    try {
                        uriHandler.openUri(release.htmlUrl)
                    } catch (_: Exception) {
                        Toast.makeText(context, "无法打开浏览器，请前往 GitHub 手动下载", Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                }
            ) {
                Text("前往更新")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onIgnoreVersion != null) {
                    TextButton(
                        onClick = {
                            onIgnoreVersion(release.versionName)
                            onDismiss()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("忽略此版本")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("稍后再说")
                }
            }
        }
    )
}
