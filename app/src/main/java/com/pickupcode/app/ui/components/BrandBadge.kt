package com.pickupcode.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 品牌徽标（统一容器）：**同一圆角、同一底色（surfaceVariant）、同一内边距**。
 *
 * 背景：详情页来源卡原来是 `surface.copy(alpha=0.6f)`、主页卡是 `surfaceVariant.copy(alpha=0.55f)`、
 * 标题栏是裸图 —— 三处不一。2026-09-17 按用户选的方案 C 收敛：卡片类的 logo 统一走这里，
 * 只保留尺寸差异（主页卡 28dp / 详情来源卡 32dp），底色与圆角全局一致。
 *
 * 注意：品牌 logo 依然用 [Image]（不是 [androidx.compose.material3.Icon]），**保留品牌原色** ——
 * 绝大多数 logo 是不透明的彩色圆角方块，染色会把图形糊成一块纯色（详见 IdentityCodeTopBarActions）。
 */
@Composable
fun BrandBadge(
    @DrawableRes res: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    boxSize: Dp = 28.dp,
    radius: Dp = 8.dp,
    innerPadding: Dp = 2.dp
) {
    Box(
        modifier = modifier
            .size(boxSize)
            .clip(RoundedCornerShape(radius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(res),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(boxSize - innerPadding * 2)
        )
    }
}
