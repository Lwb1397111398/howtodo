package com.howtodo.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val Green = Color(0xFF1F6F5C)
private val GreenDark = Color(0xFF0E3B30)
private val GreenLight = Color(0xFF7FD3BB)
private val Amber = Color(0xFFB45309)
private val AmberLight = Color(0xFFF3C88B)
private val Red = Color(0xFFB3261E)
private val RedLight = Color(0xFFFFB4AB)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8EBDD),
    onPrimaryContainer = Color(0xFF05201A),
    secondary = Amber,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE0B2),
    onSecondaryContainer = Color(0xFF3A1B00),
    tertiary = Color(0xFF3F6375),
    background = Color(0xFFFAFAF7),
    onBackground = Color(0xFF1A1C1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1B),
    surfaceVariant = Color(0xFFEDF1EE),
    onSurfaceVariant = Color(0xFF414845),
    error = Red,
    onError = Color.White,
    outline = Color(0xFFBFC9C4),
    outlineVariant = Color(0xFFDCE5E0)
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF005143),
    onPrimaryContainer = Color(0xFFB8EBDD),
    secondary = AmberLight,
    onSecondary = Color(0xFF4A2800),
    secondaryContainer = Color(0xFF6B3D00),
    onSecondaryContainer = Color(0xFFFFE0B2),
    background = Color(0xFF101413),
    onBackground = Color(0xFFE1E3E1),
    surface = Color(0xFF171B1A),
    onSurface = Color(0xFFE1E3E1),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C4),
    error = RedLight,
    onError = Color(0xFF690005),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4945)
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

/** 语义色：逾期红、临期橙、正常绿 —— 全应用统一，避免各页面自己拍脑袋 */
object Semantic {
    val overdue: Color @Composable get() = MaterialTheme.colorScheme.error
    val soon: Color @Composable get() = MaterialTheme.colorScheme.secondary
    val normal: Color @Composable get() = MaterialTheme.colorScheme.primary
    val confirm: Color @Composable get() = Color(0xFF6A4C93)
}

@Composable
fun HowTodoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = Color.Transparent.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
