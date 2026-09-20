package kr.woorichurch.app.ui.devotion

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.woorichurch.app.data.Cell
import kr.woorichurch.app.data.Scopes
import kr.woorichurch.app.ui.AppState
import kr.woorichurch.app.ui.common.*
import kr.woorichurch.app.ui.theme.*
import kr.woorichurch.app.util.D
import java.time.LocalDate

@Composable
fun WriteScreen(state: AppState, onBack: () -> Unit, onSaved: () -> Unit) {
    val repo = state.repo
    val scope = rememberCoroutineScope()
    val me = state.me

    var date by remember { mutableStateOf(D.today()) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(true) }   // 기본은 공개
    var cells by remember { mutableStateOf<List<Cell>>(emptyList()) }
    var picked by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var allCells by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var draftNotice by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    val canPick = me?.canPickCells == true

    // 그 날짜에 이미 쓴 글이 있으면 불러와서 고칠 수 있게
    LaunchedEffect(date) {
        loaded = false
        val existing = runCatching { repo.myEntries(date, date) }.getOrNull()?.firstOrNull()
        if (existing != null) {
            title = existing.title.orEmpty(); body = existing.body; isPublic = existing.isPublic
            draftNotice = "이 날짜에 쓰신 글을 불러왔습니다. 고치면 그대로 바뀝니다."
        } else {
            val draft = repo.prefs.loadDraft(date)
            if (draft != null) {
                title = draft.first; body = draft.second
                draftNotice = "쓰시다 만 글이 있어 그대로 불러왔습니다."
            } else {
                title = ""; body = ""; draftNotice = ""
            }
        }
        loaded = true
    }

    LaunchedEffect(Unit) {
        if (canPick) cells = runCatching { repo.cells() }.getOrDefault(emptyList())
        me?.cellId?.let { picked = setOf(it) }
    }

    // 자동 임시저장 — 손을 멈추면 1.5초 뒤 저장
    LaunchedEffect(title, body, date, loaded) {
        if (!loaded) return@LaunchedEffect
        delay(1500)
        if (body.isNotBlank()) repo.prefs.saveDraft(date, title, body)
    }

    Screen("묵상 나눔하기", onBack = onBack) {
        if (me == null) { Loading(); return@Screen }

        // ── 날짜 ─────────────────────────────────────────────────────────
        Spacer(Modifier.height(8.dp))
        Card {
            Text("날짜", style = MaterialTheme.typography.bodySmall, color = Muted)
            Spacer(Modifier.height(6.dp))
            Text(D.koreanFull(date), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { date = LocalDate.parse(date).minusDays(1).toString() },
                    modifier = Modifier.weight(1f),
                ) { Text("하루 앞", style = MaterialTheme.typography.labelMedium) }
                OutlinedButton(
                    onClick = { date = D.today() },
                    modifier = Modifier.weight(1f),
                ) { Text("오늘", style = MaterialTheme.typography.labelMedium) }
                OutlinedButton(
                    onClick = {
                        val next = LocalDate.parse(date).plusDays(1)
                        if (!next.isAfter(LocalDate.now())) date = next.toString()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("하루 뒤", style = MaterialTheme.typography.labelMedium) }
            }
        }

        if (draftNotice.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Message(draftNotice, tone = Green)
        }

        // ── 내용 ─────────────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        Field(title, { title = it }, "제목 (없어도 됩니다)")
        Spacer(Modifier.height(12.dp))
        Field(body, { body = it }, "오늘 묵상한 내용", singleLine = false, minLines = 8)

        // ── 공개 여부 ─────────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (isPublic) "함께 나누기" else "나만 보기", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (isPublic) "나눔광장에 올라갑니다" else "출석표에는 표시되지만 글은 나만 봅니다",
                        style = MaterialTheme.typography.bodySmall, color = Muted,
                    )
                }
                Switch(
                    checked = isPublic, onCheckedChange = { isPublic = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = Green),
                )
            }
        }

        // ── 나눌 셀 고르기 (목사·사모 등 나눔 범위가 열린 사람만) ──────────────
        if (canPick && cells.isNotEmpty()) {
            SectionTitle("어느 셀에 나눌까요")
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = allCells,
                        onCheckedChange = { allCells = it },
                        colors = CheckboxDefaults.colors(checkedColor = Green),
                    )
                    Text("모든 셀에 나누기", style = MaterialTheme.typography.titleMedium)
                }
                if (!allCells) {
                    HorizontalDivider(color = Line)
                    cells.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = picked.contains(c.id),
                                onCheckedChange = {
                                    picked = if (it) picked + c.id else picked - c.id
                                },
                                colors = CheckboxDefaults.colors(checkedColor = Green),
                            )
                            Text(c.name, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.weight(1f))
                            Text("${c.memberCount}명", style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    }
                }
            }
        } else if (me.cellName != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                "${me.cellName} 안에서 나눠집니다.",
                style = MaterialTheme.typography.bodyMedium, color = Muted,
            )
        }

        Spacer(Modifier.height(20.dp))
        Message(error)
        Spacer(Modifier.height(12.dp))

        BigButton(if (busy) "올리는 중…" else "올리기", enabled = !busy) {
            error = ""
            if (body.isBlank()) { error = "묵상한 내용을 적어 주세요"; return@BigButton }
            busy = true
            scope.launch {
                runCatching {
                    repo.saveEntry(
                        date = date, title = title.trim(), body = body.trim(), isPublic = isPublic,
                        cellIds = if (canPick && !allCells) picked.toList() else null,
                        allCells = canPick && allCells,
                    )
                }.onSuccess {
                    repo.prefs.clearDraft()
                    onSaved()
                }.onFailure { error = it.message ?: "올리지 못했습니다" }
                busy = false
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
