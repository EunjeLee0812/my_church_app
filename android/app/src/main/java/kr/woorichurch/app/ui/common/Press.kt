package kr.woorichurch.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.OutlinedButton as M3OutlinedButton
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.TextButton as M3TextButton

// ══════════════════════════════════════════════════════════════════════════
//  버튼 누르는 맛
//   · 누르는 순간 지수(exponential) 곡선으로 '훅' 커지고
//   · 손을 떼면 천천히 부드럽게 원래 크기로 돌아옵니다.
//   · 큰 버튼은 조금, 작은 버튼은 더 크게 — 커지는 양을 화면 크기(dp)로 맞춥니다.
//   · 누른 채 손가락이 미끄러지면(스크롤) 바로 놓아 줍니다.
// ══════════════════════════════════════════════════════════════════════════

/** easeOutExpo — 처음에 확 커지고 끝에서 부드럽게 멈춤 */
private val ExpoOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

fun Modifier.pressBounce(enabled: Boolean = true, grow: Dp = 14.dp): Modifier =
    if (!enabled) this else composed {
        val p = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val growPx = with(LocalDensity.current) { grow.toPx() }
        this
            .graphicsLayer {
                val base = maxOf(size.width, size.height).coerceAtLeast(1f)
                val amount = (growPx / base).coerceIn(0.03f, 0.14f)
                val s = 1f + p.value * amount
                scaleX = s; scaleY = s
            }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    scope.launch { p.animateTo(1f, tween(280, easing = ExpoOut)) }
                    // 손을 떼거나, 스크롤로 미끄러지면 끝
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        if ((c.position - down.position).getDistance() > slop * 1.5f) break
                    }
                    scope.launch {
                        // 아주 짧게 '톡' 눌러도 커지는 맛은 나도록 한 번 끝까지 키운 뒤 놓습니다
                        if (p.value < 0.7f) p.animateTo(1f, tween(90, easing = ExpoOut))
                        p.animateTo(0f, tween(420, easing = FastOutSlowInEasing))   // 천천히 원래 크기로
                    }
                }
            }
    }

// ── 머티리얼 버튼을 같은 이름으로 감쌉니다 ─────────────────────────────────────
// 각 화면 파일은 이 함수들을 이름으로 직접 import 해서, 따로 고치지 않아도
// 모든 버튼에 누르는 맛이 붙습니다.

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = M3Button(
    onClick, modifier.pressBounce(enabled), enabled, shape, colors, elevation, border,
    contentPadding, interactionSource, content,
)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = M3OutlinedButton(
    onClick, modifier.pressBounce(enabled), enabled, shape, colors, elevation, border,
    contentPadding, interactionSource, content,
)

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = M3TextButton(
    onClick, modifier.pressBounce(enabled), enabled, shape, colors, elevation, border,
    contentPadding, interactionSource, content,
)

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) = M3IconButton(onClick, modifier.pressBounce(enabled), enabled, colors, interactionSource, content)

/** 눌리지 않는 Surface — 그대로 전달 */
@Composable
@NonRestartableComposable
fun Surface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) = M3Surface(modifier, shape, color, contentColor, tonalElevation, shadowElevation, border, content)

/** 눌리는 Surface (카드 버튼 등) — 누르는 맛을 붙입니다 */
@Composable
@NonRestartableComposable
fun Surface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) = M3Surface(
    onClick, modifier.pressBounce(enabled), enabled, shape, color, contentColor,
    tonalElevation, shadowElevation, border, interactionSource, content,
)
