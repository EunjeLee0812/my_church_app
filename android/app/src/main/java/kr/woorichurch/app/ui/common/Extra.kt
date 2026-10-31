package kr.woorichurch.app.ui.common

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.woorichurch.app.BuildConfig
import kr.woorichurch.app.data.Cell
import kr.woorichurch.app.ui.theme.*
import java.time.LocalDate
import java.time.YearMonth

// ══════════════════════════════════════════════════════════════════════════
//  한 칸씩 차례로 적는 입력 틀
//
//  · 맨 위에는 지금 적을 칸 하나만 있습니다.
//  · 다 적고 '다음'(키보드의 완료·엔터)을 누르면 그 값은 아래로 내려가고
//    맨 위 칸이 다음 질문으로 바뀝니다.
//  · 입력칸은 처음부터 끝까지 '같은 칸'을 다시 쓰기 때문에 키보드가 닫혔다
//    열리지 않습니다. (칸을 새로 만들면 키보드가 깜빡입니다)
//  · 아래로 내려간 값을 누르면 그 질문으로 돌아가 고칠 수 있습니다.
// ══════════════════════════════════════════════════════════════════════════
class Step(
    val key: String,
    /** 아래 요약 줄에 쓰는 짧은 이름 */
    val name: String,
    /** 맨 위에 크게 보이는 질문 */
    val question: String,
    val hint: String? = null,
    val numeric: Boolean = false,
    val password: Boolean = false,
    val maxLen: Int = 60,
    val optional: Boolean = false,
    /** 잘못된 값이면 안내 문구를 돌려줍니다 */
    val validate: (String, Map<String, String>) -> String? = { _, _ -> null },
    /** 이 단계에 처음 들어올 때 비어 있으면 채워 줄 값 */
    val prefill: (Map<String, String>) -> String? = { null },
    /** 글자 입력 대신 따로 그리는 단계 (예: 주소 찾기). next() 를 부르면 다음으로 */
    val custom: (@Composable (value: String, setValue: (String) -> Unit, next: () -> Unit) -> Unit)? = null,
)

@Composable
fun StepForm(
    steps: List<Step>,
    values: SnapshotStateMap<String, String>,
    finishLabel: String,
    busy: Boolean,
    serverError: String,
    onFinish: () -> Unit,
) {
    var current by rememberSaveable { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val step = steps[current]
    val last = current == steps.lastIndex
    // 칸 하나를 같이 쓰므로, 단계가 바뀔 때 글자와 커서(맨 끝)를 함께 바꿔 줍니다
    var tfv by remember {
        mutableStateOf(values[steps[current].key].orEmpty().let { TextFieldValue(it, TextRange(it.length)) })
    }

    fun goTo(i: Int) {
        error = ""
        current = i
        val t = values[steps[i].key].orEmpty()
        tfv = TextFieldValue(t, TextRange(t.length))
    }

    fun advance() {
        val v = values[step.key].orEmpty().trim()
        if (!step.optional && v.isBlank()) { error = "${step.name}을(를) 적어 주세요"; return }
        step.validate(v, values)?.let { error = it; return }
        error = ""
        if (last) { onFinish(); return }
        val nx = steps[current + 1]
        if (values[nx.key].isNullOrBlank()) nx.prefill(values)?.let { values[nx.key] = it }
        goTo(current + 1)
    }

    // 글자 칸이 보이는 단계에서는 항상 그 칸에 초점을 둡니다 (키보드 유지)
    LaunchedEffect(current) {
        if (steps[current].custom == null) runCatching { focus.requestFocus() }
    }

    Column {
        Text(
            "${current + 1} / ${steps.size}",
            style = MaterialTheme.typography.bodySmall, color = Muted,
        )
        Spacer(Modifier.height(4.dp))
        Text(step.question, style = MaterialTheme.typography.headlineSmall)
        if (step.hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(step.hint, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        Spacer(Modifier.height(14.dp))

        val custom = step.custom
        if (custom != null) {
            custom(values[step.key].orEmpty(), { values[step.key] = it }, {
                // 주소를 고르자마자 넘어가도록 — 값이 막 들어온 뒤라 한 번 더 확인
                if (values[step.key].orEmpty().isNotBlank() || step.optional) advance()
            })
        } else {
            // ★ 모든 글자 단계가 이 칸 하나를 같이 씁니다
            OutlinedTextField(
                value = tfv,
                onValueChange = { nv ->
                    val v = (if (step.numeric) nv.text.filter(Char::isDigit) else nv.text).take(step.maxLen)
                    tfv = if (v == nv.text) nv
                          else TextFieldValue(v, TextRange(minOf(nv.selection.end, v.length)))
                    values[step.key] = v
                    if (error.isNotBlank()) error = ""
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall,
                visualTransformation = if (step.password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when {
                        step.numeric -> KeyboardType.NumberPassword
                        else -> KeyboardType.Text
                    },
                    imeAction = if (last) ImeAction.Done else ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { advance() },
                    onDone = { advance() },
                ),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Green, unfocusedBorderColor = Line,
                    focusedContainerColor = Surface, unfocusedContainerColor = Surface,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }

        Spacer(Modifier.height(10.dp))
        Message(error.ifBlank { serverError })
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (current > 0) {
                BigButton("이전", Modifier.weight(0.4f), tone = ButtonTone.Plain, enabled = !busy) {
                    goTo(current - 1)
                }
            }
            BigButton(
                when {
                    busy -> "잠시만요…"
                    last -> finishLabel
                    step.optional && values[step.key].isNullOrBlank() -> "건너뛰기"
                    else -> "다음"
                },
                Modifier.weight(1f), enabled = !busy,
            ) { advance() }
        }

        // ── 지금까지 적은 것들 (가장 최근 것이 위) ──────────────────────────
        if (current > 0) {
            Spacer(Modifier.height(24.dp))
            (current - 1 downTo 0).forEach { i ->
                val s = steps[i]
                val v = values[s.key].orEmpty()
                Surface(
                    color = Surface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Line),
                    onClick = { if (!busy) goTo(i) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, style = MaterialTheme.typography.bodySmall, color = Muted,
                            modifier = Modifier.width(96.dp))
                        Text(
                            when {
                                v.isBlank() -> "(비워 둠)"
                                s.password -> "●".repeat(v.length)
                                else -> v
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text("고치기", style = MaterialTheme.typography.labelSmall, color = Green)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  셀 고르기 — 가로로 한 줄, 옆으로 밀어서 봅니다
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun CellChips(
    cells: List<Cell>,
    picked: Set<Long>,
    onChange: (Set<Long>) -> Unit,
    showAll: Boolean = true,
) {
    if (cells.isEmpty()) {
        Text("아직 만든 셀이 없습니다", style = MaterialTheme.typography.bodyMedium, color = Muted)
        return
    }
    val allIds = cells.map { it.id }.toSet()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showAll && cells.size > 1) {
            val on = picked.containsAll(allIds)
            Chip("전체", on) { onChange(if (on) emptySet() else allIds) }
        }
        cells.forEach { c ->
            val on = picked.contains(c.id)
            Chip(c.name, on) { onChange(if (on) picked - c.id else picked + c.id) }
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Green else Surface,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.5.dp, if (selected) Green else Line),
        onClick = onClick,
        modifier = Modifier.heightIn(min = 44.dp),
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(
                (if (selected) "✓ " else "") + text,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) Color.White else Ink,
                maxLines = 1,
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  묵상 사진 보여 주기 — 한 장이면 크게, 여러 장이면 옆으로 밀어서
//  누르면 크게 봅니다
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun PhotoStrip(urls: List<String>) {
    if (urls.isEmpty()) return
    var open by remember { mutableStateOf<String?>(null) }
    if (urls.size == 1) {
        AsyncImage(
            model = urls[0], contentDescription = "사진",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                .pressBounce(grow = 8.dp).clip(RoundedCornerShape(12.dp)).clickable { open = urls[0] },
        )
    } else {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            urls.forEach { u ->
                AsyncImage(
                    model = u, contentDescription = "사진",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(180.dp).pressBounce().clip(RoundedCornerShape(12.dp)).clickable { open = u },
                )
            }
        }
    }
    open?.let { u ->
        Dialog(onDismissRequest = { open = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(color = Color.Black, modifier = Modifier.fillMaxSize().clickable { open = null }) {
                Box(contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = u, contentDescription = "사진 크게 보기",
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "화면을 누르면 닫힙니다", color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  주소 찾기 (카카오 우편번호 서비스 · 무료, 키 필요 없음)
//  서버의 /postcode.html 을 웹뷰로 띄우고, 고른 주소를 돌려받습니다.
// ══════════════════════════════════════════════════════════════════════════
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AddressSearchDialog(onPicked: (address: String, building: String) -> Unit, onDismiss: () -> Unit) {
    val main = remember { Handler(Looper.getMainLooper()) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = Surface, modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("교회 주소 찾기", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("닫기", style = MaterialTheme.typography.labelLarge) }
                }
                Text(
                    "도로명·건물 이름·지번으로 찾은 뒤 목록에서 눌러 주세요.",
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                // 웹뷰가 화면에 자리를 잡은(높이가 생긴) 뒤에 주소 검색 페이지를 엽니다.
                // 크기가 정해지기 전에 열면 검색창이 높이 0 으로 만들어져 하얀 화면만 보입니다.
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            // 카카오 검색창은 다른 주소의 iframe 안에서 돌아가 제3자 쿠키가 필요합니다
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = WebViewClient()
                            webChromeClient = WebChromeClient()
                            addJavascriptInterface(object {
                                @JavascriptInterface
                                fun onAddress(raw: String) {
                                    val o = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
                                        ?: return
                                    val addr = o["address"]?.jsonPrimitive?.content.orEmpty()
                                    val bld = o["building"]?.jsonPrimitive?.content.orEmpty()
                                    main.post { if (addr.isNotBlank()) onPicked(addr, bld) }
                                }
                            }, "WooriApp")
                            val url = BuildConfig.API_BASE.trimEnd('/') + "/postcode.html"
                            var loaded = false
                            fun loadOnce() { if (!loaded && height > 0) { loaded = true; loadUrl(url) } }
                            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> loadOnce() }
                            post { loadOnce() }
                        }
                    },
                )
            }
        }
    }
}

/** 주소 단계 — '주소 찾기' 버튼 + 찾은 주소 표시 + 검색이 안 될 때 직접 적기 */
@Composable
fun AddressStep(value: String, setValue: (String) -> Unit, next: () -> Unit, onBuilding: (String) -> Unit) {
    var open by remember { mutableStateOf(value.isBlank()) }
    var manual by remember { mutableStateOf(false) }
    if (value.isNotBlank() && !manual) {
        Card {
            Text("찾은 주소", style = MaterialTheme.typography.bodySmall, color = Muted)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(10.dp))
        BigButton("다시 찾기", tone = ButtonTone.Soft) { open = true }
    } else if (manual) {
        Field(value, setValue, "교회 주소")
    } else {
        BigButton("주소 찾기", tone = ButtonTone.Soft, subtitle = "도로명이나 건물 이름으로 찾습니다") { open = true }
    }
    Spacer(Modifier.height(6.dp))
    TextButton(onClick = { manual = !manual }, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (manual) "주소 찾기로 돌아가기" else "검색이 잘 안 되면 직접 적기",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        )
    }
    if (open) AddressSearchDialog(
        onPicked = { addr, bld -> open = false; setValue(addr); onBuilding(bld); next() },
        onDismiss = { open = false },
    )
}

// ══════════════════════════════════════════════════════════════════════════
//  묵상 출석표 잔디 — 최근 12개월, 이번 달이 맨 오른쪽
//   · 나눈 날: 초록으로 꽉 찬 동그라미
//   · 안 한 날: 테두리만 있는 빈 동그라미
//   · 앞으로 올 날: 그리지 않음
//  처음 열면 맨 오른쪽(이번 달)이 보이고, 왼쪽으로 밀면 지난 달들이 나옵니다.
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun YearGrass(done: Set<String>, months: Int = 12) {
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) { if (scroll.maxValue > 0) scroll.scrollTo(scroll.maxValue) }
    val now = YearMonth.now()
    val list = remember(now) { (months - 1 downTo 0).map { now.minusMonths(it.toLong()) } }
    val today = LocalDate.now()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        list.forEachIndexed { i, ym ->
            MonthDots(ym, done, today, showYear = i == 0 || ym.monthValue == 1)
        }
    }
}

@Composable
private fun MonthDots(ym: YearMonth, done: Set<String>, today: LocalDate, showYear: Boolean) {
    val dot = 11.dp
    val gap = 4.dp
    val first = ym.atDay(1)
    val length = ym.lengthOfMonth()
    val leading = first.dayOfWeek.value % 7          // 일요일 시작
    val weeks = (leading + length + 6) / 7
    Column {
        Text(
            if (showYear) "${ym.year % 100}년 ${ym.monthValue}월" else "${ym.monthValue}월",
            style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.size(width = dot * weeks + gap * (weeks - 1), height = dot * 7 + gap * 6)) {
            val d = dot.toPx(); val g = gap.toPx(); val r = d / 2
            val stroke = Stroke(width = 1.4.dp.toPx())
            for (day in 1..length) {
                val idx = leading + day - 1
                val w = idx / 7; val dow = idx % 7
                val date = ym.atDay(day)
                if (date.isAfter(today)) continue
                val c = Offset(w * (d + g) + r, dow * (d + g) + r)
                val ds = date.toString()
                if (done.contains(ds)) drawCircle(DotDone, radius = r, center = c)
                else drawCircle(DotMiss, radius = r - stroke.width / 2, center = c, style = stroke)
            }
        }
    }
}
