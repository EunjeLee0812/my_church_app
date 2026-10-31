package kr.woorichurch.app.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import kr.woorichurch.app.ui.common.Button
import kr.woorichurch.app.ui.common.IconButton
import kr.woorichurch.app.ui.common.OutlinedButton
import kr.woorichurch.app.ui.common.Surface
import kr.woorichurch.app.ui.common.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kr.woorichurch.app.ui.AppState
import kr.woorichurch.app.ui.common.*
import kr.woorichurch.app.ui.theme.*
import kotlin.random.Random

// ══════════════════════════════════════════════════════════════════════════
//  돌림판 — 점심 메뉴 등 항목을 직접 적고 돌립니다.
//  만든 돌림판은 이름을 붙여 저장해 두고 다시 불러올 수 있습니다 (이 기기에만 저장).
// ══════════════════════════════════════════════════════════════════════════
@Serializable
data class Wheel(val name: String, val items: List<String>)

private val DEFAULT_WHEEL = Wheel("점심 메뉴", listOf("김치찌개", "된장찌개", "제육볶음", "칼국수", "돈까스", "비빔밥"))
private const val LAST = "__last__"   // 마지막으로 쓴 돌림판 (목록에는 안 보임)

private val SLICE_COLORS = listOf(
    Color(0xFF1F4D3A), Color(0xFFE8DCCB), Color(0xFF5E8C73),
    Color(0xFFF3ECE2), Color(0xFFA8675C), Color(0xFFD5E2D6),
)
private fun textOn(c: Color) = if ((c.red * 0.299f + c.green * 0.587f + c.blue * 0.114f) < 0.55f) Color.White else Ink

private val json = Json { ignoreUnknownKeys = true }

@Composable
fun RouletteScreen(state: AppState, onBack: () -> Unit) {
    val prefs = state.repo.prefs
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<List<Wheel>>(emptyList()) }
    var name by remember { mutableStateOf(DEFAULT_WHEEL.name) }
    val items = remember { mutableStateListOf<String>().apply { addAll(DEFAULT_WHEEL.items) } }
    var newItem by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    val rotation = remember { Animatable(0f) }
    var spinning by remember { mutableStateOf(false) }

    fun persist(list: List<Wheel>) {
        saved = list
        scope.launch { prefs.setWheelsJson(json.encodeToString(ListSerializer(Wheel.serializer()), list)) }
    }

    LaunchedEffect(Unit) {
        val all = runCatching {
            json.decodeFromString(ListSerializer(Wheel.serializer()), prefs.wheelsJson() ?: "[]")
        }.getOrDefault(emptyList())
        saved = all
        all.firstOrNull { it.name == LAST }?.let { w ->
            val real = w.items.firstOrNull()?.let { it } // 첫 줄에 원래 이름을 넣어 둡니다
            name = real ?: DEFAULT_WHEEL.name
            items.clear(); items.addAll(w.items.drop(1))
        }
    }

    fun spin() {
        if (spinning) return
        if (items.size < 2) { note = "항목을 두 개 이상 적어 주세요"; return }
        note = ""; result = null; spinning = true
        // 마지막으로 쓴 돌림판을 기억해 둡니다
        persist(saved.filterNot { it.name == LAST } + Wheel(LAST, listOf(name) + items.toList()))
        scope.launch {
            val target = rotation.value + 360f * (5 + Random.nextInt(3)) + Random.nextFloat() * 360f
            rotation.animateTo(target, tween(3800, easing = CubicBezierEasing(0.15f, 0.85f, 0.25f, 1f)))
            val seg = 360f / items.size
            // 바늘은 맨 위. 돌림판이 r 만큼 돌았으면 바늘 아래는 원래의 (360 - r) 위치
            val at = ((360f - (rotation.value % 360f)) % 360f)
            result = items.getOrNull((at / seg).toInt().coerceIn(0, items.size - 1))
            spinning = false
        }
    }

    Screen("돌림판", onBack = onBack) {
        Spacer(Modifier.height(8.dp))

        // ── 판 ───────────────────────────────────────────────────────────
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp), contentAlignment = Alignment.Center) {
            WheelCanvas(items.toList(), rotation.value)
            // 가운데 돌리기 단추
            Surface(
                color = Surface, shape = CircleShape, shadowElevation = 6.dp,
                border = BorderStroke(2.dp, Green),
                onClick = { spin() },
                modifier = Modifier.size(84.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(if (spinning) "…" else "돌리기", style = MaterialTheme.typography.titleMedium, color = Green)
                }
            }
            // 바늘 (맨 위)
            Canvas(Modifier.align(Alignment.TopCenter).size(30.dp, 26.dp)) {
                val p = Path().apply {
                    moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close()
                }
                drawPath(p, Danger)
            }
        }

        result?.let {
            Spacer(Modifier.height(8.dp))
            RoundCard(tint = GreenLight) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("오늘은", style = MaterialTheme.typography.bodyMedium, color = Muted)
                    Text(it, style = MaterialTheme.typography.headlineMedium, color = Green)
                }
            }
        }
        Message(note)

        // ── 항목 적기 ─────────────────────────────────────────────────────
        SectionTitle("항목")
        Field(name, { name = it.take(20) }, "돌림판 이름")
        Spacer(Modifier.height(10.dp))
        items.forEachIndexed { i, it ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                    .background(Surface).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(SLICE_COLORS[i % SLICE_COLORS.size]))
                Spacer(Modifier.width(10.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (!spinning) items.removeAt(i) }) {
                    Text("빼기", style = MaterialTheme.typography.labelSmall, color = Danger)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 적고 키보드의 완료를 누르면 바로 추가되고, 칸이 비워져 다음 항목을 이어서 적을 수 있습니다
            OutlinedTextField(
                value = newItem, onValueChange = { newItem = it.take(20) },
                label = { Text("새 항목", style = MaterialTheme.typography.bodyMedium) },
                singleLine = true, textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val t = newItem.trim(); if (t.isNotBlank() && items.size < 16) items.add(t); newItem = ""
                }),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Green, unfocusedBorderColor = Line,
                    focusedContainerColor = Surface, unfocusedContainerColor = Surface,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { val t = newItem.trim(); if (t.isNotBlank() && items.size < 16) items.add(t); newItem = "" },
                colors = ButtonDefaults.buttonColors(containerColor = Green),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(56.dp),
            ) { Text("추가") }
        }

        Spacer(Modifier.height(14.dp))
        BigButton("이 돌림판 저장하기", tone = ButtonTone.Soft) {
            val n = name.trim().ifBlank { "내 돌림판" }
            if (items.size < 2) { note = "항목을 두 개 이상 적어 주세요"; return@BigButton }
            persist(saved.filterNot { it.name == n } + Wheel(n, items.toList()))
            note = ""
        }

        // ── 저장한 돌림판 ──────────────────────────────────────────────────
        val list = saved.filterNot { it.name == LAST }
        SectionTitle("저장한 돌림판")
        if (list.isEmpty()) {
            Text("아직 저장한 돌림판이 없습니다.", style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        (listOf(DEFAULT_WHEEL).filter { d -> list.none { it.name == d.name } } + list).forEach { w ->
            Surface(
                color = Surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Line),
                onClick = {
                    if (!spinning) { name = w.name; items.clear(); items.addAll(w.items); result = null }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(w.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            w.items.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = Muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (list.any { it.name == w.name }) {
                        TextButton(onClick = { persist(saved.filterNot { it.name == w.name }) }) {
                            Text("지움", style = MaterialTheme.typography.labelSmall, color = Danger)
                        }
                    } else Spacer(Modifier.width(16.dp))
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun WheelCanvas(items: List<String>, rotation: Float) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize().rotate(rotation)) {
        val n = items.size.coerceAtLeast(1)
        val seg = 360f / n
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        if (items.isEmpty()) {
            drawCircle(SurfaceWarm, r, c); return@Canvas
        }
        items.forEachIndexed { i, label ->
            val color = SLICE_COLORS[i % SLICE_COLORS.size].let {
                // 항목 수가 색 수로 나눠떨어지면 마지막과 첫 칸 색이 겹치지 않게
                if (i == n - 1 && n % SLICE_COLORS.size == 1 && n > 1) SLICE_COLORS[2] else it
            }
            val start = -90f + i * seg
            drawArc(color, start, seg, useCenter = true, topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2))
            drawArc(Surface, start, seg, useCenter = true, topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2, r * 2), style = Stroke(width = 2.dp.toPx()))
            // 글씨는 칸 가운데 선을 따라 바깥쪽으로
            rotate(start + seg / 2 + 90f, pivot = c) {
                val layout = measurer.measure(
                    label.take(8),
                    TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textOn(color)),
                )
                translate(c.x - layout.size.width / 2f, c.y - r * 0.78f) { drawText(layout) }
            }
        }
        drawCircle(Green, r, c, style = Stroke(width = 4.dp.toPx()))
    }
}
