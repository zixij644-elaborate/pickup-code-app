package com.pickupcode.app.ui.components

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pickupcode.app.R
import com.pickupcode.app.util.IdentityCodeLauncher

/**
 * 标题栏用的「身份码」跳转按钮组：**三家分开、各带自家单色图标**。
 *
 * 风格（2026-09-18 用户指定）：与主界面右上角那排 Material 动作图标**同风格** ——
 * 无容器底、24dp、同一个 IconButton 热区、单色描边 + 用 [LocalContentColor] 上色。
 * 图标出自同一套 arcticons 线性图标集（`ic_brand_taobao` / `ic_brand_cainiao` / `ic_brand_pinduoduo`），
 * 三家线宽一致；未安装的目标 App 只降透明度（单色图标无需再去饱和）。
 *
 * 点击 → [IdentityCodeLauncher] 三级降级跳转；任何失败都弹 Toast 明确告知，不静默失败。
 */
@Composable
fun IdentityCodeTopBarActions() {
    val context = LocalContext.current
    // 颜色比 TopAppBar 默认的 onSurfaceVariant 更深（用户 2026-09-18：「logo 颜色再深一点」）
    val tint = MaterialTheme.colorScheme.onSurface
    // 安装状态只在进入页面时读一次（需要 AndroidManifest 里的 <queries> 才查得到）
    val installed = remember {
        mapOf(
            IdentityCodeLauncher.TAOBAO to IdentityCodeLauncher.isInstalled(context, IdentityCodeLauncher.TAOBAO),
            IdentityCodeLauncher.CAINIAO to IdentityCodeLauncher.isInstalled(context, IdentityCodeLauncher.CAINIAO),
            IdentityCodeLauncher.PINDUODUO to IdentityCodeLauncher.isInstalled(context, IdentityCodeLauncher.PINDUODUO)
        )
    }

    fun open(block: (android.content.Context) -> IdentityCodeLauncher.Result) {
        IdentityCodeLauncher.resultMessage(block(context))?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    IdentityTopBarButton(R.drawable.ic_brand_taobao, "淘宝身份码", tint, installed[IdentityCodeLauncher.TAOBAO] == true) {
        open { IdentityCodeLauncher.openTaobao(it) }
    }
    IdentityTopBarButton(R.drawable.ic_brand_cainiao, "菜鸟出库码", tint, installed[IdentityCodeLauncher.CAINIAO] == true) {
        open { IdentityCodeLauncher.openCainiao(it) }
    }
    IdentityTopBarButton(R.drawable.ic_brand_pinduoduo, "拼多多身份码", tint, installed[IdentityCodeLauncher.PINDUODUO] == true) {
        open { IdentityCodeLauncher.openPinduoduo(it) }
    }
}

@Composable
private fun IdentityTopBarButton(
    @DrawableRes icon: Int,
    label: String,
    tint: Color,
    installed: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    IconButton(onClick = {
        if (installed) onClick() else Toast.makeText(context, "未安装该应用", Toast.LENGTH_SHORT).show()
    }) {
        Image(
            painter = painterResource(icon),
            contentDescription = label,
            colorFilter = ColorFilter.tint(tint),
            // 未安装：只降透明度，但仍可点（点了给明确提示，而不是直接禁用让人困惑）
            modifier = Modifier
                .size(24.dp)
                .alpha(if (installed) 1f else 0.38f)
        )
    }
}
