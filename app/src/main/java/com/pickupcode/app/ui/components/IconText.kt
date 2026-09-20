package com.pickupcode.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 行内「图标 + 文本」。
 *
 * 背景（2026-09-18 用户要求）：界面里原先有几十处把 Emoji 直接写进字符串（📍 取件地址、📦 标记已取…），
 * 不同机型/字体下渲染不一致（例如 🚪 在 vivo 上渲染成一块棕色方块）。现统一换成
 * lucide.dev 的线性图标（见 `res/drawable/ic_*.xml`），用 [ColorFilter] 跟随文字颜色，风格与顶栏图标一致。
 *
 * 用法：`IconText(R.drawable.ic_map_pin, item.pickupAddress)`，可覆盖字号/颜色/图标尺寸。
 */
@Composable
fun IconText(
    @DrawableRes icon: Int,
    text: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = 15.dp,
    gap: Dp = 5.dp,
    style: TextStyle? = null,
    color: Color = Color.Unspecified,
    iconTint: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE
) {
    val tint = when {
        iconTint != Color.Unspecified -> iconTint
        color != Color.Unspecified -> color
        else -> LocalContentColor.current
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(iconSize)
        )
        Spacer(Modifier.width(gap))
        Text(
            text = text,
            style = style ?: LocalTextStyle.current,
            color = color,
            fontWeight = fontWeight,
            maxLines = maxLines
        )
    }
}
