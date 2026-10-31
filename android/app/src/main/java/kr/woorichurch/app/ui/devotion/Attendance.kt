package kr.woorichurch.app.ui.devotion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import kr.woorichurch.app.ui.common.Button
import kr.woorichurch.app.ui.common.IconButton
import kr.woorichurch.app.ui.common.OutlinedButton
import kr.woorichurch.app.ui.common.Surface
import kr.woorichurch.app.ui.common.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kr.woorichurch.app.data.Attendance
import kr.woorichurch.app.data.AttendancePerson
import kr.woorichurch.app.ui.AppState
import kr.woorichurch.app.ui.common.*
import kr.woorichurch.app.ui.theme.*

/**
 * 묵상 출석표 — 최근 1년.
 * 세로 7칸이 일~토, 주 단위로 왼쪽에서 오른쪽으로 흐르고 이번 달이 맨 오른쪽입니다.
 * 처음엔 이번 달이 보이고, 왼쪽으로 밀면 지난 달들이 나옵니다.
 */
@Composable
fun AttendanceScreen(
    state: AppState,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onPerson: (Long) -> Unit,
) {
    val repo = state.repo
    val me = state.me
    // 구역(셀) 하나씩 골라 봅니다. 내 구역이 앞에 오고, 맨 끝에 '전체'.
    val myIds = me?.cellIds.orEmpty()
    val ordered = remember(state.cells, myIds) {
        state.cells.sortedWith(compareBy({ !myIds.contains(it.id) }, { it.name }))
    }
    // 0 = 전체
    var cellId by rememberSaveable { mutableStateOf(myIds.firstOrNull() ?: 0L) }
    var data by remember { mutableStateOf<Attendance?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(cellId) {
        loading = true; error = ""
        runCatching { repo.attendanceYear(if (cellId == 0L) "all" else "cell", cellId.takeIf { it != 0L }) }
            .onSuccess { data = it }
            .onFailure { error = it.message ?: "불러오지 못했습니다" }
        loading = false
    }

    Screen("묵상 출석표", onBack = onBack, onHome = onHome) {
        Spacer(Modifier.height(8.dp))
        if (ordered.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ordered.forEach { c -> Chip(c.name, cellId == c.id) { cellId = c.id } }
                Chip("전체", cellId == 0L) { cellId = 0L }
            }
            Spacer(Modifier.height(12.dp))
        }

        Legend()
        Spacer(Modifier.height(16.dp))
        Message(error)
        if (loading) { Loading(); return@Screen }
        val d = data ?: return@Screen
        if (d.people.isEmpty()) { Empty("보여 드릴 사람이 없습니다."); return@Screen }

        d.people.forEach { p ->
            PersonGrass(p, onPerson)
            Spacer(Modifier.height(14.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 점 설명 — 꽉 찬 초록 = 나눈 날, 빈 동그라미 = 안 한 날 */
@Composable
private fun Legend() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(12.dp)) { drawCircle(DotDone) }
        Spacer(Modifier.width(6.dp))
        Text("나눈 날", style = MaterialTheme.typography.bodySmall, color = Muted)
        Spacer(Modifier.width(16.dp))
        Canvas(Modifier.size(12.dp)) {
            val w = 1.4.dp.toPx()
            drawCircle(DotMiss, radius = size.minDimension / 2 - w / 2, style = Stroke(w))
        }
        Spacer(Modifier.width(6.dp))
        Text("안 한 날", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "옆으로 밀면 지난 달이 나옵니다. 이름을 누르면 그분 묵상기록으로 갑니다.",
        style = MaterialTheme.typography.bodySmall, color = Muted,
    )
}

@Composable
private fun PersonGrass(person: AttendancePerson, onPerson: (Long) -> Unit) {
    val done = remember(person.dates) { person.dates.toSet() }
    Card(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${person.name} ${person.title}",
                style = MaterialTheme.typography.titleMedium,
                color = Green,
                maxLines = 1,
                modifier = Modifier.pressBounce().clickable { onPerson(person.id) },
            )
            Spacer(Modifier.width(8.dp))
            person.cellName?.let { Pill(it) }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text("1년 ${person.dates.size}회", style = MaterialTheme.typography.labelMedium, color = Green,
                maxLines = 1, softWrap = false)
        }
        Spacer(Modifier.height(12.dp))
        YearGrass(done)
    }
}
