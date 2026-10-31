package kr.woorichurch.app.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import kr.woorichurch.app.ui.common.Button
import kr.woorichurch.app.ui.common.IconButton
import kr.woorichurch.app.ui.common.OutlinedButton
import kr.woorichurch.app.ui.common.Surface
import kr.woorichurch.app.ui.common.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kr.woorichurch.app.data.*
import kr.woorichurch.app.ui.AppState
import kr.woorichurch.app.ui.common.*
import kr.woorichurch.app.ui.devotion.PickerBox
import kr.woorichurch.app.ui.theme.*
import kr.woorichurch.app.util.D

/**
 * 교회 성도 / 셀 관리 — 버튼 하나로 들어와서 안에서 나뉩니다.
 *  · 직분(호칭)과 권한(앱에서 할 수 있는 일)은 따로입니다.
 *  · 권한 주기와 나눔 범위 바꾸기는 관리자(교회를 등록한 분 · 담임목사님)만 할 수 있습니다.
 */
@Composable
fun MembersScreen(state: AppState, onBack: () -> Unit, onHome: () -> Unit) {
    val repo = state.repo
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var cells by remember { mutableStateOf<List<Cell>>(emptyList()) }
    var canEdit by remember { mutableStateOf(false) }
    var canGrantRole by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Member?>(null) }
    var newCellName by remember { mutableStateOf("") }
    var editingCell by remember { mutableStateOf<Cell?>(null) }
    var confirmDeleteCell by remember { mutableStateOf<Cell?>(null) }
    var approveCells by remember { mutableStateOf<Map<Long, Set<Long>>>(emptyMap()) }
    var confirmAll by remember { mutableStateOf(false) }
    var busyAll by remember { mutableStateOf(false) }
    val myId = state.me?.id
    val iAmOwner = state.me?.isOwner == true

    LaunchedEffect(reload) {
        loading = true; error = ""
        runCatching { repo.members() }
            .onSuccess { members = it.members; canEdit = it.canEdit; canGrantRole = it.canGrantRole }
            .onFailure { error = it.message ?: "불러오지 못했습니다" }
        cells = runCatching { repo.cells() }.getOrDefault(emptyList())
        loading = false
    }

    Screen("교회 성도 / 셀 관리", onBack = onBack, onHome = onHome) {
        Spacer(Modifier.height(8.dp))
        Toggle2("성도", "셀", tab == 1) { tab = if (it) 1 else 0 }
        Spacer(Modifier.height(16.dp))
        Message(error)
        Message(notice, tone = Green)

        if (loading) { Loading(); return@Screen }

        if (tab == 0) {
            // ── 성도 ──────────────────────────────────────────────────────
            val pending = members.filter { it.status == "pending" }
            if (pending.isNotEmpty()) {
                SectionTitle("승인 기다리는 분 ${pending.size}명", top = 4.dp)
                if (pending.size > 1) {
                    BigButton(
                        if (busyAll) "승인하는 중…" else "${pending.size}명 모두 승인",
                        tone = ButtonTone.Primary, enabled = !busyAll,
                        subtitle = "각자 고른 셀로 들어갑니다",
                    ) { confirmAll = true }
                    Spacer(Modifier.height(12.dp))
                }
                pending.forEach { m ->
                    Card(padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name, style = MaterialTheme.typography.titleMedium)
                                Text("신청 ${D.shortTime(m.createdAt)}",
                                    style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        // 셀은 가로 한 줄로 — 옆으로 밀어서 고릅니다 (나중에 정해도 됩니다)
                        CellChips(
                            cells, approveCells[m.id] ?: emptySet(),
                            { approveCells = approveCells + (m.id to it) },
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        runCatching { repo.approveMember(m.id, false) }
                                            .onSuccess { reload++ }
                                            .onFailure { error = it.message ?: "거절하지 못했습니다" }
                                    }
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            ) { Text("거절", color = Danger, style = MaterialTheme.typography.labelLarge) }
                            Button(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            repo.approveMember(m.id, true, (approveCells[m.id] ?: emptySet()).toList())
                                        }.onSuccess { reload++ }
                                            .onFailure { error = it.message ?: "승인하지 못했습니다" }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Green),
                                modifier = Modifier.weight(2f).heightIn(min = 52.dp),
                            ) { Text("승인", style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }

            SectionTitle("성도 ${members.count { it.status == "active" }}명")
            members.filter { it.status != "pending" }.forEach { m ->
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${m.name} ${m.title}" + if (m.id == myId) " (나)" else "",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                if (m.status == "inactive") { Spacer(Modifier.width(8.dp)); Pill("중지", Muted) }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                buildString {
                                    append(m.cellName ?: "셀 없음")
                                    if (m.role != Roles.MEMBER) append(" · ").append(Roles.label(m.role))
                                    if (m.shareScope == Scopes.ALL_CELLS) append(" · 셀 선택 가능")
                                },
                                style = MaterialTheme.typography.bodySmall, color = Muted,
                            )
                        }
                        // 관리자 계정은 관리자만, 나머지는 관리 권한이 있으면 바꿀 수 있습니다 (본인 포함)
                        if (canEdit && (m.role != Roles.OWNER || iAmOwner)) {
                            TextButton(onClick = { editing = m }) {
                                Text("바꾸기", style = MaterialTheme.typography.labelMedium, color = Green)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        } else {
            // ── 셀 ────────────────────────────────────────────────────────
            if (canEdit) {
                Card {
                    Text("셀 새로 만들기", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    Field(newCellName, { newCellName = it }, "셀 이름")
                    Spacer(Modifier.height(10.dp))
                    BigButton("만들기", tone = ButtonTone.Soft) {
                        val n = newCellName.trim()
                        if (n.isBlank()) return@BigButton
                        scope.launch {
                            runCatching { repo.saveCell(null, n) }
                                .onSuccess { newCellName = ""; reload++ }
                                .onFailure { error = it.message ?: "만들지 못했습니다" }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (cells.isEmpty()) Empty("아직 만든 셀이 없습니다.")
            cells.forEach { c ->
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${c.memberCount}명" + (c.leaderName?.let { " · 셀장 $it" } ?: " · 셀장 없음"),
                                style = MaterialTheme.typography.bodySmall, color = Muted,
                            )
                        }
                        if (canEdit) {
                            TextButton(onClick = { editingCell = c }) {
                                Text("바꾸기", style = MaterialTheme.typography.labelMedium, color = Green)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    // ── 성도 바꾸기 창 ────────────────────────────────────────────────────
    editing?.let { m ->
        MemberEditDialog(
            member = m, cells = cells, canGrantRole = canGrantRole,
            onDismiss = { editing = null },
            onSave = { title, cellIds, role, scopeValue, status ->
                editing = null
                scope.launch {
                    runCatching {
                        repo.updateMember(m.id, title, cellIds, role, scopeValue, status)
                    }.onSuccess { reload++; notice = "${m.name}님 정보를 바꿨습니다." }
                        .onFailure { error = it.message ?: "바꾸지 못했습니다" }
                }
            },
            onResetPin = {
                editing = null
                scope.launch {
                    runCatching { repo.resetPin(m.id) }
                        .onSuccess { notice = "${m.name}님 임시 비밀번호: ${it.tempPin}\n본인에게 알려 주시고, 들어가면 새로 정하게 됩니다." }
                        .onFailure { error = it.message ?: "초기화하지 못했습니다" }
                }
            },
        )
    }

    // ── 셀 바꾸기 창 ──────────────────────────────────────────────────────
    editingCell?.let { c ->
        CellEditDialog(
            cell = c, members = members.filter { it.status == "active" },
            onDismiss = { editingCell = null },
            onSave = { name, memberId ->
                editingCell = null
                scope.launch {
                    runCatching {
                        if (name != c.name) repo.saveCell(c.id, name)
                        if (memberId != c.leaderId) repo.setCellLeader(c.id, memberId)
                    }.onSuccess { reload++ }
                        .onFailure { error = it.message ?: "바꾸지 못했습니다" }
                }
            },
            onDelete = { editingCell = null; confirmDeleteCell = c },
        )
    }

    Confirm(
        show = confirmAll,
        title = "모두 승인할까요?",
        message = "기다리는 ${members.count { it.status == "pending" }}명을 한 번에 승인합니다. " +
            "셀을 고른 분은 그 셀로, 안 고른 분은 셀 없이 들어갑니다.",
        confirmText = "모두 승인",
        onConfirm = {
            confirmAll = false
            busyAll = true
            val items = members.filter { it.status == "pending" }
                .map { it.id to (approveCells[it.id] ?: emptySet()).toList() }
            scope.launch {
                runCatching { repo.approveAll(items) }
                    .onSuccess { notice = "${it.count}명을 승인했습니다."; approveCells = emptyMap(); reload++ }
                    .onFailure { error = it.message ?: "승인하지 못했습니다" }
                busyAll = false
            }
        },
        onDismiss = { confirmAll = false },
    )

    Confirm(
        show = confirmDeleteCell != null,
        title = "${confirmDeleteCell?.name}을(를) 지울까요?",
        message = "이 셀에 속한 분들은 '셀 없음' 이 됩니다. 묵상 글은 그대로 남습니다.",
        confirmText = "지웁니다",
        onConfirm = {
            val c = confirmDeleteCell; confirmDeleteCell = null
            if (c != null) scope.launch {
                runCatching { repo.deleteCell(c.id) }.onSuccess { reload++ }
                    .onFailure { error = it.message ?: "지우지 못했습니다" }
            }
        },
        onDismiss = { confirmDeleteCell = null },
    )
}

@Composable
private fun MemberEditDialog(
    member: Member,
    cells: List<Cell>,
    canGrantRole: Boolean,
    onDismiss: () -> Unit,
    onSave: (String?, List<Long>?, String?, String?, String?) -> Unit,
    onResetPin: () -> Unit,
) {
    var title by remember { mutableStateOf(member.title) }
    var picked by remember { mutableStateOf(member.cellIds.toSet()) }
    var role by remember { mutableStateOf(member.role) }
    var shareScope by remember { mutableStateOf(member.shareScope) }
    var status by remember { mutableStateOf(member.status) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(member.name, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("직분", style = MaterialTheme.typography.bodySmall, color = Muted)
                Spacer(Modifier.height(6.dp))
                PickerBox("직분", title, Titles.ALL, { it }) { title = it }

                Spacer(Modifier.height(14.dp))
                Text("셀 (여러 개 고를 수 있습니다 · 옆으로 밀기)", style = MaterialTheme.typography.bodySmall, color = Muted)
                Spacer(Modifier.height(6.dp))
                CellChips(cells, picked, { picked = it })

                if (canGrantRole) {
                    Spacer(Modifier.height(14.dp))
                    Text("권한 (관리자만 바꿀 수 있습니다)", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Spacer(Modifier.height(6.dp))
                    PickerBox(
                        "권한", role, listOf(Roles.MEMBER, Roles.LEADER, Roles.ADMIN, Roles.OWNER), { Roles.label(it) },
                    ) { role = it }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (role) {
                            Roles.OWNER -> "관리자: 담임목사님과 같은 모든 권한 (직분·권한 주기, 가입 승인, 줌 링크)"
                            Roles.ADMIN -> "부관리자: 성도·셀 관리, 가입 승인, 공지·주보"
                            Roles.LEADER -> "셀장: 우리 셀 현황 보기"
                            else -> "성도: 나눔·기도제목·댓글"
                        },
                        style = MaterialTheme.typography.bodySmall, color = Muted,
                    )

                    Spacer(Modifier.height(14.dp))
                    Text("나눔 범위", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Spacer(Modifier.height(6.dp))
                    PickerBox(
                        "나눔 범위", shareScope, listOf(Scopes.OWN_CELL, Scopes.ALL_CELLS), { Scopes.label(it) },
                    ) { shareScope = it }
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = status == "inactive",
                        onCheckedChange = { status = if (it) "inactive" else "active" },
                        colors = CheckboxDefaults.colors(checkedColor = Danger),
                    )
                    Text("사용 중지 (이사 등)", style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onResetPin) {
                    Text("비밀번호 초기화", style = MaterialTheme.typography.labelMedium, color = Danger)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val before = member.cellIds.toSet()
                onSave(
                    title.takeIf { it != member.title },
                    picked.toList().takeIf { picked != before },
                    role.takeIf { it != member.role },
                    shareScope.takeIf { it != member.shareScope },
                    status.takeIf { it != member.status },
                )
            }) { Text("저장", style = MaterialTheme.typography.labelLarge, color = Green) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("그만두기") } },
    )
}

@Composable
private fun CellEditDialog(
    cell: Cell,
    members: List<Member>,
    onDismiss: () -> Unit,
    onSave: (String, Long?) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(cell.name) }
    var leaderId by remember { mutableStateOf(cell.leaderId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(cell.name, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Field(name, { name = it }, "셀 이름")
                Spacer(Modifier.height(14.dp))
                Text("셀장", style = MaterialTheme.typography.bodySmall, color = Muted)
                Spacer(Modifier.height(6.dp))
                val opts = listOf("none") + members.map { it.id.toString() }
                PickerBox(
                    "셀장", leaderId?.toString() ?: "none", opts,
                    { k -> if (k == "none") "셀장 없음" else members.firstOrNull { it.id.toString() == k }?.let { "${it.name} ${it.title}" } ?: k },
                ) { leaderId = if (it == "none") null else it.toLongOrNull() }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDelete) {
                    Text("이 셀 지우기", style = MaterialTheme.typography.labelMedium, color = Danger)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), leaderId) }) {
                Text("저장", style = MaterialTheme.typography.labelLarge, color = Green)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("그만두기") } },
    )
}
