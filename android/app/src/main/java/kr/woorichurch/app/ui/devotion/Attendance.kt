package kr.woorichurch.app.ui.devotion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.woorichurch.app.data.Attendance
import kr.woorichurch.app.data.AttendancePerson
import kr.woorichurch.app.ui.AppState
import kr.woorichurch.app.ui.common.*
import kr.woorichurch.app.ui.theme.*
import java.time.LocalDate

/**
 * 묵상 출석표 — 분기(3개월) 단위.
 * 세로 7칸이 일~토, 주 단위로 왼쪽에서 오른쪽으로 흐릅니다.
 * 달마다 블록을 나눠 1일부터 말일까지 모두 그 달 안에 들어갑니다.
 */
@Composable
fun AttendanceScreen(
    state: AppState,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onPerson: (Long) -> Unit,
) {
    val repo = state.repo
    var all by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf<Attendance?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val nowYear = LocalDate.now().year
    val nowQuarter = (LocalDate.now().monthValue - 1) / 3 + 1
    var year by remember { mutableStateOf(nowYear) }
    var quarter by remember { mutableStateOf(nowQuarter) }

    LaunchedEffect(all, year, quarter) {
        loading = true; error = ""
        runCatching { repo.attendance(if (all) "all" else "cell", year, quarter) }
            .onSuccess { data = it }
            .onFailure { error = it.message ?: "불러오지 못했습니다" }
        loading = false
    }

    Screen("묵상 출석표", onBack = onBack, onHome = onHome) {
        Spacer(Modifier.height(8.dp))
        Toggle2(
            state.me?.cellName?.let { "$it" } ?: "우리 셀",
            "전체 보기", all,
        ) { all = it }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    if (quarter == 1) { quarter = 4; year -= 1 } else quarter -= 1
                }, modifier = Modifier.weight(1f),
            ) { Text("지난 분기", style = MaterialTheme.typography.labelSmall) }
            Text(
                "${year}년 ${quarter}분기",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1.2f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            OutlinedButton(
                onClick = {
                    val isLatest = year == nowYear && quarter == nowQuarter
                    if (!isLatest) { if (quarter == 4) { quarter = 1; year += 1 } else quarter += 1 }
                }, modifier = Modifier.weight(1f),
            ) { Text("다음 분기", style = MaterialTheme.typography.labelSmall) }
        }

        Spacer(Modifier.height(16.dp))
        Message(error)
        if (loading) { Loading(); return@Screen }
        val d = data ?: return@Screen
        if (d.people.isEmpty()) { Empty("보여 드릴 사람이 없습니다."); return@Screen }

        Text(
            "초록 점은 그날 묵상을 나눈 날입니다. 이름을 누르면 그분 묵상기록으로 갑니다.",
            style = MaterialTheme.typography.bodySmall, color = Muted,
        )
        Spacer(Modifier.height(16.dp))

        val startMonth = (d.quarter - 1) * 3 + 1
        d.people.forEach { p ->
            PersonGrass(p, d.year, startMonth, onPerson)
            Spacer(Modifier.height(18.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PersonGrass(
    person: AttendancePerson,
    year: Int,
    startMonth: Int,
    onPerson: (Long) -> Unit,
) {
    val done = person.dates.toSet()
    Card(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${person.name} ${person.title}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable { onPerson(person.id) },
            )
            Spacer(Modifier.width(8.dp))
            person.cellName?.let { Pill(it) }
            Spacer(Modifier.weight(1f))
            Text("${person.dates.size}회", style = MaterialTheme.typography.labelMedium, color = Green)
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            (0 until 3).forEach { offset ->
                val month = startMonth + offset
                MonthBlock(year, month, done)
            }
        }
    }
}

@Composable
private fun MonthBlock(year: Int, month: Int, done: Set<String>) {
    val first = LocalDate.of(year, month, 1)
    val length = first.lengthOfMonth()
    // 일요일 시작. LocalDate 의 dayOfWeek 는 월=1 … 일=7
    val leading = first.dayOfWeek.value % 7
    val cells = leading + length
    val weeks = (cells + 6) / 7
    val todayStr = LocalDate.now().toString()

    Column {
        Text("${month}월", style = MaterialTheme.typography.labelSmall, color = Muted)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (0 until weeks).forEach { w ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0 until 7).forEach { dow ->
                        val index = w * 7 + dow
                        val day = index - leading + 1
                        if (day < 1 || day > length) {
                            Spacer(Modifier.size(11.dp))
                        } else {
                            val ds = String.format("%04d-%02d-%02d", year, month, day)
                            val future = ds > todayStr
                            Box(
                                Modifier.size(11.dp).background(
                                    color = when {
                                        done.contains(ds) -> DotDone
                                        future -> Bg
                                        else -> DotMiss
                                    },
                                    shape = CircleShape,
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
