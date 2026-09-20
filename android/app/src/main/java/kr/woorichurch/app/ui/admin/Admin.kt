package kr.woorichurch.app.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kr.woorichurch.app.data.*
import kr.woorichurch.app.ui.AppState
import kr.woorichurch.app.ui.common.*
import kr.woorichurch.app.ui.devotion.PickerBox
import kr.woorichurch.app.ui.theme.*
import kr.woorichurch.app.util.D
import kr.woorichurch.app.util.Launch

// ══════════════════════════════════════════════════════════════════════════
//  가입 신청 승인
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun ApprovalsScreen(state: AppState, onBack: () -> Unit) {
    val repo = state.repo
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var cells by remember { mutableStateOf<List<Cell>>(emptyList()) }
    var picked by remember { mutableStateOf<Map<Long, Long?>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        loading = true; error = ""
        runCatching { repo.members() }
            .onSuccess { members = it.members.filter { m -> m.status == "pending" } }
            .onFailure { error = it.message ?: "불러오지 못했습니다" }
        cells = runCatching { repo.cells() }.getOrDefault(emptyList())
        loading = false
    }

    Screen("가입 신청 승인", onBack = onBack) {
        Spacer(Modifier.height(8.dp))
        Message(error)
        if (loading) { Loading(); return@Screen }
        if (members.isEmpty()) { Empty("승인을 기다리는 신청이 없습니다."); return@Screen }

        Text(
            "승인할 때 셀을 함께 정해 두시면 바로 나눔을 할 수 있습니다.",
            style = MaterialTheme.typography.bodySmall, color = Muted,
        )
        Spacer(Modifier.height(16.dp))

        members.forEach { m ->
            Card {
                Text(m.name, style = MaterialTheme.typography.titleLarge)
                Text("신청 ${D.shortTime(m.createdAt)}", style = MaterialTheme.typography.bodySmall, color = Muted)
                Spacer(Modifier.height(12.dp))
                val opts = listOf("none") + cells.map { it.id.toString() }
                PickerBox(
                    "들어갈 셀", picked[m.id]?.toString() ?: "none", opts,
                    { k -> if (k == "none") "나중에 정하기" else cells.firstOrNull { it.id.toString() == k }?.name ?: k },
                ) { picked = picked + (m.id to if (it == "none") null else it.toLongOrNull()) }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton("승인", Modifier.weight(1f)) {
                        scope.launch {
                            runCatching { repo.approveMember(m.id, true, picked[m.id]) }
                                .onSuccess { reload++ }
                                .onFailure { error = it.message ?: "승인하지 못했습니다" }
                        }
                    }
                    BigButton("거절", Modifier.weight(1f), tone = ButtonTone.Danger) {
                        scope.launch {
                            runCatching { repo.approveMember(m.id, false) }
                                .onSuccess { reload++ }
                                .onFailure { error = it.message ?: "거절하지 못했습니다" }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  직분 · 셀 변경 기록
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun AuditScreen(state: AppState, onBack: () -> Unit) {
    val repo = state.repo
    var logs by remember { mutableStateOf<List<AuditEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching { repo.auditLog() }
            .onSuccess { logs = it }
            .onFailure { error = it.message ?: "불러오지 못했습니다" }
        loading = false
    }

    Screen("변경 기록", onBack = onBack) {
        Spacer(Modifier.height(8.dp))
        Text(
            "누가 언제 무엇을 바꿨는지 남겨 둡니다. 목사님과 관리 권한자만 볼 수 있습니다.",
            style = MaterialTheme.typography.bodySmall, color = Muted,
        )
        Spacer(Modifier.height(16.dp))
        Message(error)
        if (loading) { Loading(); return@Screen }
        if (logs.isEmpty()) { Empty("아직 기록이 없습니다."); return@Screen }

        logs.forEach { l ->
            Card(padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(l.action, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(D.shortTime(l.createdAt), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(l.actorName ?: "?")
                        l.targetName?.let { append(" → ").append(it) }
                        l.detail?.let { append("\n").append(it) }
                    },
                    style = MaterialTheme.typography.bodyMedium, color = Muted,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  셀장용 우리 셀 현황판
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun CellStatusScreen(state: AppState, onBack: () -> Unit) {
    val repo = state.repo
    val context = LocalContext.current
    var cells by remember { mutableStateOf<List<Cell>>(emptyList()) }
    var cellId by remember { mutableStateOf(state.me?.cellId) }
    var data by remember { mutableStateOf<CellStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (state.me?.canManage == true) cells = runCatching { repo.cells() }.getOrDefault(emptyList())
    }
    LaunchedEffect(cellId) {
        loading = true; error = ""
        runCatching { repo.cellStatus(cellId) }
            .onSuccess { data = it }
            .onFailure { error = it.message ?: "불러오지 못했습니다" }
        loading = false
    }

    Screen(data?.cellName ?: "우리 셀 현황", onBack = onBack) {
        Spacer(Modifier.height(8.dp))
        if (cells.size > 1) {
            PickerBox(
                "셀 고르기", cellId?.toString() ?: "none",
                cells.map { it.id.toString() },
                { k -> cells.firstOrNull { it.id.toString() == k }?.name ?: "셀" },
            ) { cellId = it.toLongOrNull() }
            Spacer(Modifier.height(16.dp))
        }
        Message(error)
        if (loading) { Loading(); return@Screen }
        val d = data ?: return@Screen

        Text(
            "${D.koreanFull(d.date)} 기준",
            style = MaterialTheme.typography.bodySmall, color = Muted,
        )
        Spacer(Modifier.height(16.dp))

        val notYet = d.people.filter { !it.wroteToday }
        Card {
            Text("오늘 묵상", style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(
                "${d.people.count { it.wroteToday }} / ${d.people.size}명",
                style = MaterialTheme.typography.headlineMedium, color = Green,
            )
            if (notYet.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                BigButton("아직 안 하신 분들 챙기기", tone = ButtonTone.Soft, subtitle = "메시지로 보내기") {
                    Launch.shareText(
                        context,
                        "${d.cellName.orEmpty()} 오늘 묵상 나눔 아직 안 하신 분: " +
                            notYet.joinToString(", ") { it.name } + "\n함께 은혜 나눠요.",
                        subject = "묵상 챙기기",
                    )
                }
            }
        }

        SectionTitle("셀원")
        d.people.forEach { p ->
            Card(padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${p.name} ${p.title}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "최근 7일 ${p.weekCount}회" +
                                (p.lastDate?.let { " · 마지막 ${D.korean(it)}" } ?: " · 아직 없음"),
                            style = MaterialTheme.typography.bodySmall, color = Muted,
                        )
                    }
                    Pill(if (p.wroteToday) "오늘 했어요" else "아직", if (p.wroteToday) Green else Muted)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  건의함
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun SuggestionsScreen(state: AppState, onBack: () -> Unit) {
    val repo = state.repo
    var list by remember { mutableStateOf<List<Suggestion>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching { repo.suggestions() }
            .onSuccess { list = it }
            .onFailure { error = it.message ?: "불러오지 못했습니다" }
        loading = false
    }

    Screen("건의함", onBack = onBack) {
        Spacer(Modifier.height(8.dp))
        Message(error)
        if (loading) { Loading(); return@Screen }
        if (list.isEmpty()) { Empty("아직 들어온 건의가 없습니다."); return@Screen }
        list.forEach { s ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name ?: "익명", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(D.shortTime(s.createdAt), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                Spacer(Modifier.height(8.dp))
                Text(s.body, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  교회 등록 신청 심사 (플랫폼 운영자)
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun PlatformScreen(state: AppState, onBack: () -> Unit) {
    val repo = state.repo
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var status by remember { mutableStateOf("pending") }
    var list by remember { mutableStateOf<List<ChurchApplication>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var rejecting by remember { mutableStateOf<ChurchApplication?>(null) }
    var reason by remember { mutableStateOf("") }

    LaunchedEffect(status, reload) {
        loading = true; error = ""
        runCatching { repo.churchApplications(status) }
            .onSuccess { list = it }
            .onFailure { error = it.message ?: "불러오지 못했습니다" }
        loading = false
    }

    Screen("교회 등록 심사", onBack = onBack) {
        Spacer(Modifier.height(8.dp))
        Toggle2("심사 대기", "승인된 교회", status == "approved") {
            status = if (it) "approved" else "pending"
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "교단 명부에서 교회와 담임목사 이름을 확인해 보세요. " +
                "확인이 안 되면 승인을 미루고 전화로 물어보는 편이 안전합니다.",
            style = MaterialTheme.typography.bodySmall, color = Muted,
        )
        Spacer(Modifier.height(16.dp))
        Message(error)
        if (loading) { Loading(); return@Screen }
        if (list.isEmpty()) { Empty("해당하는 교회가 없습니다."); return@Screen }

        list.forEach { c ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(8.dp))
                    c.invitedByName?.let { Pill("초대: $it") }
                }
                Spacer(Modifier.height(10.dp))
                listOf(
                    "주소" to c.address, "전화" to c.phone, "교단" to c.denomination,
                    "담임목사" to c.pastorName, "신학교/안수" to c.seminary,
                    "홈페이지" to c.homepage, "남긴 말" to c.note,
                ).forEach { (k, v) ->
                    if (!v.isNullOrBlank()) {
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text(k, style = MaterialTheme.typography.bodySmall, color = Muted,
                                modifier = Modifier.width(90.dp))
                            Text(v, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("신청 ${D.shortTime(c.createdAt)} · 등록 인원 ${c.memberCount}명",
                    style = MaterialTheme.typography.bodySmall, color = Muted)

                if (!c.homepage.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    BigButton("홈페이지 열어보기", tone = ButtonTone.Plain) {
                        Launch.web(context, c.homepage.let { if (it.startsWith("http")) it else "https://$it" })
                    }
                }

                Spacer(Modifier.height(12.dp))
                if (status == "pending") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigButton("승인", Modifier.weight(1f)) {
                            scope.launch {
                                runCatching { repo.reviewChurch(c.id, "approved") }
                                    .onSuccess { reload++ }
                                    .onFailure { error = it.message ?: "승인하지 못했습니다" }
                            }
                        }
                        BigButton("거절", Modifier.weight(1f), tone = ButtonTone.Danger) {
                            rejecting = c; reason = ""
                        }
                    }
                } else {
                    BigButton("사용 중지시키기", tone = ButtonTone.Danger) {
                        scope.launch {
                            runCatching { repo.reviewChurch(c.id, "suspended") }
                                .onSuccess { reload++ }
                                .onFailure { error = it.message ?: "바꾸지 못했습니다" }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        Spacer(Modifier.height(24.dp))
    }

    rejecting?.let { c ->
        AlertDialog(
            onDismissRequest = { rejecting = null },
            containerColor = Surface,
            title = { Text("${c.name} 거절", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Text(
                        "사유는 내부 기록으로만 남고 신청자에게는 알려지지 않습니다.",
                        style = MaterialTheme.typography.bodySmall, color = Muted,
                    )
                    Spacer(Modifier.height(10.dp))
                    Field(reason, { reason = it }, "내부 메모", singleLine = false, minLines = 3)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = c; rejecting = null
                    scope.launch {
                        runCatching { repo.reviewChurch(target.id, "rejected", reason.trim()) }
                            .onSuccess { reload++ }
                            .onFailure { error = it.message ?: "거절하지 못했습니다" }
                    }
                }) { Text("거절합니다", color = Danger, style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = { TextButton(onClick = { rejecting = null }) { Text("그만두기") } },
        )
    }
}
