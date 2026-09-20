package kr.woorichurch.app.ui.prayer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kr.woorichurch.app.data.Cell
import kr.woorichurch.app.data.Prayer
import kr.woorichurch.app.ui.AppState
import kr.woorichurch.app.ui.common.*
import kr.woorichurch.app.ui.theme.*
import kr.woorichurch.app.util.D

@Composable
fun PrayerScreen(state: AppState, onBack: () -> Unit, onHome: () -> Unit) {
    val repo = state.repo
    val scope = rememberCoroutineScope()
    val me = state.me
    var all by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf<List<Prayer>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var writing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var cells by remember { mutableStateOf<List<Cell>>(emptyList()) }
    var picked by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var allCells by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(all, reload) {
        loading = true; error = ""
        runCatching { repo.prayers(if (all) "all" else "cell") }
            .onSuccess { list = it }
            .onFailure { error = it.message ?: "불러오지 못했습니다" }
        loading = false
    }
    LaunchedEffect(Unit) {
        if (me?.canPickCells == true) cells = runCatching { repo.cells() }.getOrDefault(emptyList())
        me?.cellId?.let { picked = setOf(it) }
    }

    Screen("기도제목", onBack = onBack, onHome = onHome) {
        Spacer(Modifier.height(8.dp))
        Toggle2(me?.cellName ?: "우리 셀", "전체 보기", all) { all = it }

        Spacer(Modifier.height(16.dp))
        if (!writing) {
            BigButton("기도제목 올리기") { writing = true }
        } else {
            Card {
                Field(text, { text = it }, "함께 기도할 제목", singleLine = false, minLines = 4)
                if (me?.canPickCells == true && cells.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(allCells, { allCells = it }, colors = CheckboxDefaults.colors(checkedColor = Green))
                        Text("모든 셀에 나누기", style = MaterialTheme.typography.bodyLarge)
                    }
                    if (!allCells) cells.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                picked.contains(c.id),
                                { picked = if (it) picked + c.id else picked - c.id },
                                colors = CheckboxDefaults.colors(checkedColor = Green),
                            )
                            Text(c.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton("올리기", Modifier.weight(1f)) {
                        val t = text.trim()
                        if (t.isBlank()) return@BigButton
                        scope.launch {
                            runCatching {
                                repo.savePrayer(
                                    t,
                                    if (me?.canPickCells == true && !allCells) picked.toList() else null,
                                    me?.canPickCells == true && allCells,
                                )
                            }.onSuccess { text = ""; writing = false; reload++ }
                                .onFailure { error = it.message ?: "올리지 못했습니다" }
                        }
                    }
                    BigButton("그만두기", Modifier.weight(1f), tone = ButtonTone.Plain) {
                        writing = false; text = ""
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Message(error)
        if (loading) Loading()
        else if (list.isEmpty()) Empty("아직 올라온 기도제목이 없습니다.")
        else list.forEach { p ->
            PrayerCard(
                state = state, prayer = p,
                onChanged = { reload++ },
                onDelete = { confirmDelete = p.id },
            )
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(24.dp))
    }

    Confirm(
        show = confirmDelete != null,
        title = "기도제목을 지울까요?",
        message = "지우면 되돌릴 수 없습니다.",
        confirmText = "지웁니다",
        onConfirm = {
            val id = confirmDelete; confirmDelete = null
            if (id != null) scope.launch { runCatching { repo.deletePrayer(id) }.onSuccess { reload++ } }
        },
        onDismiss = { confirmDelete = null },
    )
}

@Composable
private fun PrayerCard(
    state: AppState,
    prayer: Prayer,
    onChanged: () -> Unit,
    onDelete: () -> Unit,
) {
    val repo = state.repo
    val scope = rememberCoroutineScope()
    var amened by remember(prayer.id) { mutableStateOf(prayer.amened) }
    var count by remember(prayer.id) { mutableStateOf(prayer.amenCount) }
    val mine = state.me?.id == prayer.memberId

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${prayer.memberName} ${prayer.memberTitle}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            if (prayer.answered) Pill("응답받음", Green)
            Spacer(Modifier.weight(1f))
            Text(D.shortTime(prayer.createdAt), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Spacer(Modifier.height(10.dp))
        Text(prayer.body, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = Line)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                scope.launch {
                    runCatching { repo.amen(prayer.id) }.onSuccess { amened = it.liked; count = it.count }
                }
            }) {
                Text(
                    if (amened) "기도했어요 ✓" else "기도했어요",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (amened) Green else Muted,
                )
                Spacer(Modifier.width(6.dp))
                Text("$count", style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            Spacer(Modifier.weight(1f))
            if (mine) {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { repo.markAnswered(prayer.id, !prayer.answered) }.onSuccess { onChanged() }
                    }
                }) {
                    Text(
                        if (prayer.answered) "응답 표시 지우기" else "응답받았어요",
                        style = MaterialTheme.typography.labelSmall, color = Green,
                    )
                }
                TextButton(onClick = onDelete) {
                    Text("지움", style = MaterialTheme.typography.labelSmall, color = Danger)
                }
            }
        }
    }
}
