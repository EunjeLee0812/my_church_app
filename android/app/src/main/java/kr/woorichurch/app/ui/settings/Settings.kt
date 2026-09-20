package kr.woorichurch.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kr.woorichurch.app.data.Prefs
import kr.woorichurch.app.data.Repo
import kr.woorichurch.app.ui.AppState
import kr.woorichurch.app.ui.Route
import kr.woorichurch.app.ui.common.*
import kr.woorichurch.app.ui.theme.*

// ══════════════════════════════════════════════════════════════════════════
//  설정 (모아 놓은 화면)
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun SettingsScreen(
    state: AppState,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onNavigate: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val repo = state.repo
    val scope = rememberCoroutineScope()
    val me = state.me
    var scale by remember { mutableStateOf(1.0f) }
    var inviteCode by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        scale = repo.prefs.fontScaleNow()
        if (me?.isOwner == true) inviteCode = runCatching { repo.inviteCode() }.getOrDefault("")
    }

    Screen("설정", onBack = onBack, onHome = onHome) {
        if (me == null) { Loading(); return@Screen }

        // ── 글씨 크기 ─────────────────────────────────────────────────────
        SectionTitle("글씨 크기", top = 12.dp)
        Card {
            Text(
                "이 글씨가 잘 보이시나요?",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(12.dp))
            Slider(
                value = scale,
                onValueChange = { scale = it; scope.launch { repo.prefs.setFontScale(it) } },
                valueRange = 0.85f..1.6f,
                steps = 4,
                colors = SliderDefaults.colors(thumbColor = Green, activeTrackColor = Green),
            )
            Row {
                Text("작게", style = MaterialTheme.typography.bodySmall, color = Muted)
                Spacer(Modifier.weight(1f))
                Text("크게", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }

        // ── 모두에게 ──────────────────────────────────────────────────────
        SectionTitle("내 정보")
        BigButton("개인정보 변경", tone = ButtonTone.Plain, subtitle = "이름 · 비밀번호") {
            onNavigate(Route.PROFILE)
        }
        Spacer(Modifier.height(10.dp))
        BigButton(
            "묵상 본문 링크 변경", tone = ButtonTone.Plain,
            subtitle = "이 기기에서만 바뀝니다",
        ) { onNavigate(Route.BIBLE_LINK) }

        // ── 관리 권한자 ───────────────────────────────────────────────────
        if (me.canManage) {
            SectionTitle("교회 관리")
            BigButton("교회 성도 / 셀 관리", tone = ButtonTone.Plain) { onNavigate(Route.MEMBERS) }
            Spacer(Modifier.height(10.dp))
            BigButton("가입 신청 승인", tone = ButtonTone.Plain) { onNavigate(Route.APPROVALS) }
            Spacer(Modifier.height(10.dp))
            BigButton("공지사항 · 주보 올리기", tone = ButtonTone.Plain) { onNavigate(Route.NOTICE) }
            Spacer(Modifier.height(10.dp))
            BigButton("직분 · 셀 변경 기록", tone = ButtonTone.Plain) { onNavigate(Route.AUDIT) }
        }

        // ── 목사님만 ──────────────────────────────────────────────────────
        if (me.isOwner) {
            SectionTitle("목사님 전용")
            BigButton("교회 줌 링크 설정", tone = ButtonTone.Plain) { onNavigate(Route.ZOOM) }
            Spacer(Modifier.height(10.dp))
            BigButton(
                "나눔 범위 설정", tone = ButtonTone.Plain,
                subtitle = "누가 어느 셀에 나눌 수 있는지",
            ) { onNavigate(Route.MEMBERS) }
            if (inviteCode.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Card {
                    Text("다른 교회 초대 코드", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Spacer(Modifier.height(4.dp))
                    Text(inviteCode, style = MaterialTheme.typography.headlineSmall, color = Green)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "아는 교회에 이 코드를 알려 주시면 등록 심사가 빨라집니다.",
                        style = MaterialTheme.typography.bodySmall, color = Muted,
                    )
                }
            }
        }

        SectionTitle("계정")
        BigButton("로그아웃", tone = ButtonTone.Danger) { onSignOut() }

        Spacer(Modifier.height(32.dp))
        Text(
            "${me.churchName} · ${me.name} ${me.title}\n" +
                "권한: ${kr.woorichurch.app.data.Roles.label(me.role)} · " +
                "나눔 범위: ${kr.woorichurch.app.data.Scopes.label(me.shareScope)}",
            style = MaterialTheme.typography.bodySmall, color = Muted,
        )
        Spacer(Modifier.height(24.dp))
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  개인정보 변경
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun ProfileScreen(state: AppState, onBack: () -> Unit) {
    val repo = state.repo
    val scope = rememberCoroutineScope()
    val me = state.me
    var name by remember { mutableStateOf(me?.name.orEmpty()) }
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var newPin2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var done by remember { mutableStateOf("") }

    Screen("개인정보 변경", onBack = onBack) {
        SectionTitle("이름", top = 12.dp)
        Field(name, { name = it }, "이름")
        Spacer(Modifier.height(12.dp))
        BigButton("이름 바꾸기", tone = ButtonTone.Soft, enabled = !busy) {
            error = ""; done = ""
            if (name.isBlank()) { error = "이름을 적어 주세요"; return@BigButton }
            busy = true
            scope.launch {
                runCatching { repo.updateMe(name = name.trim()) }
                    .onSuccess { state.refreshMe(); done = "이름을 바꿨습니다." }
                    .onFailure { error = it.message ?: "바꾸지 못했습니다" }
                busy = false
            }
        }

        SectionTitle("비밀번호")
        Field(currentPin, { currentPin = it.filter(Char::isDigit).take(6) }, "지금 쓰는 비밀번호", numeric = true, password = true)
        Spacer(Modifier.height(10.dp))
        Field(newPin, { newPin = it.filter(Char::isDigit).take(6) }, "새 비밀번호 (숫자 6자리)", numeric = true, password = true)
        Spacer(Modifier.height(10.dp))
        Field(newPin2, { newPin2 = it.filter(Char::isDigit).take(6) }, "새 비밀번호 다시", numeric = true, password = true)
        Spacer(Modifier.height(12.dp))
        BigButton("비밀번호 바꾸기", tone = ButtonTone.Soft, enabled = !busy) {
            error = ""; done = ""
            if (newPin.length != 6) { error = "새 비밀번호는 숫자 6자리여야 합니다"; return@BigButton }
            if (newPin != newPin2) { error = "새 비밀번호 두 개가 서로 다릅니다"; return@BigButton }
            busy = true
            scope.launch {
                runCatching { repo.updateMe(currentPin = currentPin, newPin = newPin) }
                    .onSuccess { currentPin = ""; newPin = ""; newPin2 = ""; done = "비밀번호를 바꿨습니다." }
                    .onFailure { error = it.message ?: "바꾸지 못했습니다" }
                busy = false
            }
        }

        Spacer(Modifier.height(20.dp))
        Message(error)
        Message(done, tone = Green)
        Spacer(Modifier.height(32.dp))
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  묵상 본문 링크 (이 기기에만 적용)
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun BibleLinkScreen(repo: Repo, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var done by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { url = repo.prefs.bibleUrlNow() }

    Screen("묵상 본문 링크", onBack = onBack) {
        Spacer(Modifier.height(12.dp))
        Card {
            Text(
                "‘묵상 본문 보기’를 눌렀을 때 열리는 주소입니다.\n" +
                    "바꿔도 이 기기에서만 바뀌고, 교회 전체에는 영향을 주지 않습니다.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(Modifier.height(20.dp))
        Field(url, { url = it }, "주소", singleLine = false, minLines = 2)
        Spacer(Modifier.height(16.dp))
        BigButton("이 주소로 저장") {
            scope.launch { repo.prefs.setBibleUrl(url.trim()); done = "저장했습니다." }
        }
        Spacer(Modifier.height(10.dp))
        BigButton("기본 주소로 되돌리기", tone = ButtonTone.Plain) {
            url = Prefs.DEFAULT_BIBLE_URL
            scope.launch { repo.prefs.setBibleUrl(Prefs.DEFAULT_BIBLE_URL); done = "기본 주소로 되돌렸습니다." }
        }
        Spacer(Modifier.height(16.dp))
        Message(done, tone = Green)
        Spacer(Modifier.height(8.dp))
        Text(
            "기본값: 성서유니온 오늘의 말씀\n${Prefs.DEFAULT_BIBLE_URL}",
            style = MaterialTheme.typography.bodySmall, color = Muted,
        )
        Spacer(Modifier.height(32.dp))
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  교회 줌 링크 (목사님만)
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun ZoomSettingScreen(state: AppState, onBack: () -> Unit) {
    val repo = state.repo
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(state.me?.zoomUrl.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var done by remember { mutableStateOf("") }

    Screen("교회 줌 링크", onBack = onBack) {
        Spacer(Modifier.height(12.dp))
        Card {
            Text(
                "줌에서 ‘내 개인 회의실’ 또는 예약한 회의의 초대 링크를 그대로 붙여 넣어 주세요.\n\n" +
                    "예) https://us02web.zoom.us/j/1234567890",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(Modifier.height(20.dp))
        Field(url, { url = it }, "줌 링크", singleLine = false, minLines = 2)
        Spacer(Modifier.height(16.dp))
        Message(error)
        Message(done, tone = Green)
        Spacer(Modifier.height(12.dp))
        BigButton(if (busy) "저장하는 중…" else "저장하기", enabled = !busy) {
            error = ""; done = ""; busy = true
            scope.launch {
                runCatching { repo.setZoom(url.trim()) }
                    .onSuccess { state.refreshMe(); done = "저장했습니다. 이제 홈에서 바로 들어갈 수 있습니다." }
                    .onFailure { error = it.message ?: "저장하지 못했습니다" }
                busy = false
            }
        }
        Spacer(Modifier.height(10.dp))
        BigButton("링크 지우기", tone = ButtonTone.Danger, enabled = !busy) {
            busy = true
            scope.launch {
                runCatching { repo.setZoom("") }
                    .onSuccess { url = ""; state.refreshMe(); done = "지웠습니다." }
                    .onFailure { error = it.message ?: "지우지 못했습니다" }
                busy = false
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
