package kr.woorichurch.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 따뜻한 느낌의 연한 연두색 바탕 — 기존 웹앱과 같은 색감
val Bg = Color(0xFFF2F7EC)
val Surface = Color(0xFFFFFFFF)
val Green = Color(0xFF2E5E2E)
val GreenLight = Color(0xFFDCEBD0)
val Ink = Color(0xFF1B1B1B)
val Muted = Color(0xFF5A6152)
val Line = Color(0xFFCBD8BE)
val Danger = Color(0xFFA33A28)
val DotDone = Color(0xFF3A8A3A)
val DotMiss = Color(0xFF2B2B2B)

private val scheme = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = GreenLight,
    onPrimaryContainer = Green,
    secondary = Green,
    background = Bg,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
    surfaceVariant = GreenLight,
    onSurfaceVariant = Muted,
    outline = Line,
    error = Danger,
)

/**
 * 시니어가 많이 쓰기 때문에 기본 글씨가 큽니다.
 * [scale] 은 설정 화면의 '글씨 크기' 조절값 (0.85 ~ 1.6)
 */
private fun typographyFor(scale: Float): Typography {
    fun s(v: Int) = (v * scale).sp
    return Typography(
        displaySmall = TextStyle(fontSize = s(30), fontWeight = FontWeight.Bold, lineHeight = s(38)),
        headlineMedium = TextStyle(fontSize = s(26), fontWeight = FontWeight.Bold, lineHeight = s(34)),
        headlineSmall = TextStyle(fontSize = s(23), fontWeight = FontWeight.Bold, lineHeight = s(31)),
        titleLarge = TextStyle(fontSize = s(21), fontWeight = FontWeight.Bold, lineHeight = s(29)),
        titleMedium = TextStyle(fontSize = s(19), fontWeight = FontWeight.SemiBold, lineHeight = s(27)),
        bodyLarge = TextStyle(fontSize = s(19), lineHeight = s(29)),
        bodyMedium = TextStyle(fontSize = s(17), lineHeight = s(26)),
        bodySmall = TextStyle(fontSize = s(15), lineHeight = s(22)),
        labelLarge = TextStyle(fontSize = s(19), fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontSize = s(16), fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = s(14)),
    )
}

@Composable
fun WooriTheme(fontScale: Float = 1f, content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()  // 항상 밝은 화면으로 고정
    MaterialTheme(colorScheme = scheme, typography = typographyFor(fontScale), content = content)
}
