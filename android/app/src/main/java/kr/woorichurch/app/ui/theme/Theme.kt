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

// ── 색 (2026-09 개편) ──────────────────────────────────────────────────────
// 크림색 바탕 + 따뜻한 베이지 카드 + 딥 그린 포인트.
// (참고 이미지의 부드러운 크림·베이지 조합에 포인트 색만 딥 그린으로)
val Bg = Color(0xFFF8F5EF)          // 바탕 — 따뜻한 크림
val Surface = Color(0xFFFFFDFA)     // 카드 — 거의 흰색, 살짝 따뜻하게
val SurfaceWarm = Color(0xFFF2ECE3) // 강조 카드 — 연한 베이지
val Green = Color(0xFF1F4D3A)       // 포인트 — 딥 그린
val GreenLight = Color(0xFFE4ECE4)  // 연한 딥그린 (버튼 바탕·선택 막대)
val Ink = Color(0xFF2B2622)         // 글씨 — 따뜻한 먹색
val Muted = Color(0xFF857B71)       // 보조 글씨 — 따뜻한 회갈색
val Line = Color(0xFFE6DDD1)        // 테두리 — 베이지
val Danger = Color(0xFFB0503D)
val DotDone = Color(0xFF2F6B4F)
// 안 한 날 — 테두리만 있는 빈 동그라미
val DotMiss = Color(0xFFC4B9AC)

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
    surfaceVariant = SurfaceWarm,
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
