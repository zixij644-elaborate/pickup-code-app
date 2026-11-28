package com.pickupcode.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// 清爽蓝色系（浅色）
private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    error = Error,
    onError = OnError,
    background = Background,
    onBackground = OnSurface
)

// 灰蓝调深色（与 7E9EB5 主色统一）
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF0B1220),
    primaryContainer = Color(0xFF1E3A5F),
    onPrimaryContainer = Color(0xFF93C5FD),
    secondary = Color(0xFFA78BFA),
    onSecondary = Color(0xFF170F29),
    secondaryContainer = Color(0xFF3B2A63),
    onSecondaryContainer = Color(0xFFE9E4FC),
    surface = Color(0xFF1F2937),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF374151),
    onSurfaceVariant = Color(0xFF9CA3AF),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF3F0D0D),
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFE5E7EB)
)

// Sleek 小圆角（sm=4dp, md=8dp）——利落方正
private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(8.dp)
)

@Composable
fun PickupCodeTheme(
    darkMode: String = "system",
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (darkMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val act = view.context as? Activity ?: return@SideEffect
            val window = act.window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        content = content
    )
}
