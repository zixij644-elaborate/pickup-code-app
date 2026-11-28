package com.pickupcode.app.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
        // 左侧：分段滑块筛选条（Q 弹弹簧位移 + 果冻拉伸回弹）
        val jelly = remember { Animatable(0f) }
        var prevIndex by remember { mutableIntStateOf(selectedIndex) }
        LaunchedEffect(selectedIndex) {
            if (selectedIndex != prevIndex) {
                prevIndex = selectedIndex
                jelly.snapTo(1f)
                jelly.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = 0.44f,
                        stiffness = 500f
                    )
                )
            }
        }

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
                val maxOffset = (maxWidth - segmentWidth).coerceAtLeast(0.dp)
                val rawOffset by animateDpAsState(
                    targetValue = segmentWidth * selectedIndex,
                    animationSpec = spring(
                        dampingRatio = 0.56f,
                        stiffness = 460f
                    ),
                    label = "filter_pill_offset"
                )
                // 严丝合缝锁在凹槽内：绝不越出 3dp 内边距；溢出的过冲量转成贴墙侧果冻挤压
                val clampedOffset = rawOffset.coerceIn(0.dp, maxOffset)
                val segDp = segmentWidth.value.coerceAtLeast(1f)
                val leftSquash = ((0.dp - rawOffset).coerceAtLeast(0.dp).value / segDp).coerceIn(0f, 0.24f)
                val rightSquash = ((rawOffset - maxOffset).coerceAtLeast(0.dp).value / segDp).coerceIn(0f, 0.24f)
                val wallSquash = maxOf(leftSquash, rightSquash)
                // 靠近左右端壁时收起外扩拉伸，防止形变本身越过 3dp 边界
                val edgeRoom = minOf(clampedOffset.value, (maxOffset - clampedOffset).value)
                val stretchFactor = (edgeRoom / 8f).coerceIn(0f, 1f)

                // 滑动纯白胶囊指示器：中段微拉伸，撞到左右槽壁时贴墙压缩再弹回
                Box(
                    modifier = Modifier
                        .offset(x = clampedOffset)
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .graphicsLayer {
                            if (wallSquash > 0.001f) {
                                transformOrigin = if (leftSquash > 0f) {
                                    TransformOrigin(0f, 0.5f)
                                } else {
                                    TransformOrigin(1f, 0.5f)
                                }
                                scaleX = 1f - wallSquash * 0.72f
                                scaleY = 1f + wallSquash * 0.24f
                            } else {
                                transformOrigin = TransformOrigin.Center
                                val j = jelly.value * stretchFactor
                                scaleX = 1f + 0.08f * j
                                scaleY = 1f - 0.06f * j
                            }
                        }
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

        // 右侧：分组方式切换胶囊（点击在 按时间 / 按地址 间切换，带 Q 弹缩放回弹）
        val isAddressMode = groupMode == "address"
        val toggleBounce = remember { Animatable(1f) }
        var prevMode by remember { mutableStateOf(groupMode) }
        LaunchedEffect(groupMode) {
            if (groupMode != prevMode) {
                prevMode = groupMode
                toggleBounce.snapTo(0.90f)
                toggleBounce.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = 0.45f,
                        stiffness = 520f
                    )
                )
            }
        }
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
            modifier = Modifier
                .height(38.dp)
                .graphicsLayer {
                    scaleX = toggleBounce.value
                    scaleY = toggleBounce.value
                }
        ) {
            AnimatedContent(
                targetState = isAddressMode,
                transitionSpec = {
                    (fadeIn(tween(150)) + slideInVertically(
                        animationSpec = spring(dampingRatio = 0.56f, stiffness = 480f)
                    ) { it / 2 }) togetherWith
                        (fadeOut(tween(110)) + slideOutVertically(tween(110)) { -it / 2 })
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
