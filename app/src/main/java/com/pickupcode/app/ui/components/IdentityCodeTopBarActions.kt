package com.pickupcode.app.ui.components

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pickupcode.app.R
import com.pickupcode.app.util.IdentityCodeLauncher

/**
 * 标题栏用的「身份码」跳转按钮组：**三家分开、各带自家 logo**。
 *
 * 用在详情页标题栏右侧（用户要求）。点击 → [IdentityCodeLauncher] 三级降级跳转；
 * 未安装的目标 App 按钮会**统一灰化**（去饱和 + 半透明）；任何失败都弹 Toast 明确告知，不静默失败。
 *
 * 风格与主界面右上角那排动作图标一致（2026-09-17 用户选方案 C）：无容器底、24dp、同一个 IconButton 热区。
 * 品牌 logo 用 [Image]（而不是 [androidx.compose.material3.Icon]）——Icon 会给图片染色，
 * 品牌 logo 必须保留原色；灰化用 ColorMatrix 去饱和（不是直接 tint，避免彩色方块被糊成一整块纯色）。
 */
@Composable
fun IdentityCodeTopBarActions() {
    val context = LocalContext.current
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

    IdentityTopBarButton(R.drawable.logo_taobao, "淘宝身份码", installed[IdentityCodeLauncher.TAOBAO] == true) {
        open { IdentityCodeLauncher.openTaobao(it) }
    }
    IdentityTopBarButton(R.drawable.logo_cainiao, "菜鸟出库码", installed[IdentityCodeLauncher.CAINIAO] == true) {
        open { IdentityCodeLauncher.openCainiao(it) }
    }
    IdentityTopBarButton(R.drawable.logo_pinduoduo, "拼多多身份码", installed[IdentityCodeLauncher.PINDUODUO] == true) {
        open { IdentityCodeLauncher.openPinduoduo(it) }
    }
}

/** 未安装时的统一灰化：去饱和（保留字形轮廓）+ 半透明，和右上角单色图标同一视觉重量。 */
private val NotInstalledGray = ColorFilter.colorMatrix(
    ColorMatrix().apply { setToSaturation(0f) }
)

@Composable
private fun IdentityTopBarButton(
    @DrawableRes logo: Int,
    label: String,
    installed: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    IconButton(onClick = {
        if (installed) onClick() else Toast.makeText(context, "未安装该应用", Toast.LENGTH_SHORT).show()
    }) {
        Image(
            painter = painterResource(logo),
            contentDescription = label,
            // 未安装：变灰但**仍可点**（点了给明确提示，而不是直接禁用让人困惑）
            colorFilter = if (installed) null else NotInstalledGray,
            modifier = Modifier
                .size(24.dp)
                .alpha(if (installed) 1f else 0.45f)
        )
    }
}
