package com.pickupcode.app.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pickupcode.app.R

/**
 * 主页顶部单行筛选工具栏（方案 1）：
 *  - 左侧：浅灰槽底 + 纯白圆角弹簧滑块分段控件（全部 / 取餐 / 取件 / 券码），无硬边框、无对勾跳宽
 *  - 右侧：紧凑纯白胶囊切换按钮（按时间 / 按地址）
 */
@Composable
fun HomeFilterBar(
    currentFilter: String,
    onFilterChange: (String) -> Unit,
    groupMode: String,
    onGroupModeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val filters = remember {
        listOf(
            "all" to "全部",
            "food" to "取餐",
            "parcel" to "取件",
            "coupon" to "券码"
        )
    }
    val selectedIndex = filters.indexOfFirst { it.first == currentFilter }.coerceAtLeast(0)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：分段滑块筛选条
        Box(
            modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                .padding(3.dp)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val segmentWidth = maxWidth / filters.size
                val indicatorOffset by animateDpAsState(
                    targetValue = segmentWidth * selectedIndex,
                    animationSpec = spring(
                        dampingRatio = 0.82f,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "filter_pill_offset"
                )

                // 滑动纯白胶囊指示器
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .shadow(
                            elevation = 1.dp,
                            shape = RoundedCornerShape(9.5.dp),
                            ambientColor = Color.Black.copy(alpha = 0.08f),
                            spotColor = Color.Black.copy(alpha = 0.08f)
                        )
                        .clip(RoundedCornerShape(9.5.dp))
                        .background(MaterialTheme.colorScheme.surface)
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    filters.forEach { (key, label) ->
                        val selected = currentFilter == key
                        val textColor by animateColorAsState(
                            targetValue = if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = tween(160),
                            label = "filter_text_color"
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(9.5.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onFilterChange(key) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = textColor,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        // 右侧：分组方式切换胶囊（点击在 按时间 / 按地址 间切换）
        val isAddressMode = groupMode == "address"
        val toggleTint by animateColorAsState(
            targetValue = if (isAddressMode) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            animationSpec = tween(160),
            label = "group_toggle_tint"
        )
        Surface(
            onClick = { onGroupModeChange(if (isAddressMode) "time" else "address") },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 0.5.dp,
            modifier = Modifier.height(38.dp)
        ) {
            AnimatedContent(
                targetState = isAddressMode,
                transitionSpec = {
                    (fadeIn(tween(150)) + slideInVertically(tween(150)) { it / 3 }) togetherWith
                        (fadeOut(tween(110)) + slideOutVertically(tween(110)) { -it / 3 })
                },
                label = "group_mode_content"
            ) { byAddress ->
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Image(
                        painter = painterResource(if (byAddress) R.drawable.ic_map_pin else R.drawable.ic_timer),
                        contentDescription = if (byAddress) "按地址分组" else "按时间分组",
                        colorFilter = ColorFilter.tint(toggleTint),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (byAddress) "地址" else "时间",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = toggleTint,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}
