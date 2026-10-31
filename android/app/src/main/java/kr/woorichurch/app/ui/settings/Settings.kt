package kr.woorichurch.app.ui.settings

import androidx.compose.foundation.layout.*
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
import kr.woorichurch.app.data.Prefs
import kr.woorichurch.app.data.Repo
import kr.woorichurch.app.ui.AppState
import kr.woorichurch.app.ui.Route
import kr.woorichurch.app.ui.common.*
import kr.woorichurch.app.ui.theme.*

// ══════════════════════════════════════════════════════════════════════════
//  설정 — 첫 화면은 분류만 보여 주고, 누르면 그 분류의 설정으로 들어갑니다
//
//   화면 · 글씨      글씨 크기, 묵상 본문 링크
//   알림             알림 켜기/끄기, 받을 알림 고르기
//   내 정보          이름·비밀번호, 묵상 기록 내려받기
//   교회 관리        (관리 권한자) 성도/셀, 가입 승인, 변경 기록, 줌 링크
//   앱 운영자        (운영자) 교회 심사, 건의함
//   문의 · 신고
// ══════════════════════════════════════════════════════════════════════════
object SettingsSection {
    const val DISPLAY = "display"
    const val NOTIFY = "notify"
    const val ACCOUNT = "account"
    const val CHURCH = "church"
    const val PLATFORM = "platform"
    const val HELP = "help"

    fun title(s: String) = when (s) {
        DISPLAY -> "화면 · 글씨"
        NOTIFY -> "알림"
        ACCOUNT -> "내 정보"
        CHURCH -> "교회 관리"
        PLATFORM -> "앱 운영자"
        HELP -> "문의 · 신고"
        else -> "설정"
    }
}

@Composable
fun SettingsScreen(
    state: AppState,
    onBack: (() -> Unit)?,
    onHome: (() -> Unit)?,
    onNavigate: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val me = state.me
    Screen("설정", onBack = onBack, onHome = onHome) {
        if (me == null) { Loading(); return@Screen }

        Spacer(Modifier.height(8.dp))
        Card {
            Text("${me.name} ${me.title}", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(me.churchName)
                    me.cellName?.let { append(" · ").append(it) }
                    if (me.role != kr.woorichurch.app.data.Roles.MEMBER)
                        append(" · ").append(kr.woorichurch.app.data.Roles.label(me.role))
                },
                style = MaterialTheme.typography.bodyMedium, color = Muted,
            )
        }

        // ── 시니어 모드 ─────────────────────────────────────────────────────
        val scope = rememberCoroutineScope()
        val senior by state.repo.prefs.seniorMode.collectAsState(initial = false)
        Spacer(Modifier.height(12.dp))
        Surface(
            color = if (senior) GreenLight else Surface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Line),
            onClick = { scope.launch { state.repo.prefs.setSeniorMode(!senior) } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("시니어 모드", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (senior) "큰 글씨·큰 버튼 화면을 쓰고 있습니다"
                        else "켜면 큰 글씨·큰 버튼으로 단순하게 보입니다",
                        style = MaterialTheme.typography.bodySmall, color = Muted,
                    )
                }
                Switch(
                    checked = senior,
                    onCheckedChange = { on -> scope.launch { state.repo.prefs.setSeniorMode(on) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = Green),
                )
            }
        }

        SectionTitle("나에게 맞추기", top = 20.dp)
        MenuRow("화면 · 글씨", "글씨 크기, 묵상 본문 링크") { onNavigate(Route.settings(SettingsSection.DISPLAY)) }
        MenuRow("알림", "새 나눔·공지 알림 받기") { onNavigate(Route.settings(SettingsSection.NOTIFY)) }
        MenuRow("내 정보", "이름 · 비밀번호, 묵상 기록 내려받기") { onNavigate(Route.settings(SettingsSection.ACCOUNT)) }

        if (me.canManage || me.isPlatformAdmin) SectionTitle("관리")
        if (me.canManage) {
            val pending = state.home?.pendingApprovals ?: 0
            MenuRow(
                "교회 관리",
                (if (pending > 0) "가입 신청 ${pending}건 · " else "") + "성도 · 셀 · 직분" +
                    if (me.isOwner) " · 줌 링크" else "",
            ) { onNavigate(Route.settings(SettingsSection.CHURCH)) }
        }
        if (me.isPlatformAdmin) {
            MenuRow("앱 운영자", "교회 등록 심사, 건의함") { onNavigate(Route.settings(SettingsSection.PLATFORM)) }
        }

        SectionTitle("도움")
        MenuRow("문의 · 신고", "불편한 점, 부적절한 글 알리기") { onNavigate(Route.settings(SettingsSection.HELP)) }

        Spacer(Modifier.height(28.dp))
        BigButton("로그아웃", tone = ButtonTone.Danger) { onSignOut() }
        Spacer(Modifier.height(24.dp))
    }
}

/** 설정 첫 화면의 한 줄 — 왼쪽 제목·설명, 오른쪽 화살표 */
@Composable
private fun MenuRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Surface(
        color = Surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (!subtitle.isNullOrBlank())
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = Green)
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
fun SettingsSectionScreen(
    state: AppState,
    section: String,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onNavigate: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val repo = state.repo
    val scope = rememberCoroutineScope()
    val me = state.me
    var scale by remember { mutableStateOf(1.0f) }
    var suggestion by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var pushConfig by remember { mutableStateOf<kr.woorichurch.app.data.PushConfig?>(null) }
    var pushBusy by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(section) {
        scale = repo.prefs.fontScaleNow()
        if (section == SettingsSection.NOTIFY) pushConfig = runCatching { repo.pushConfig() }.getOrNull()
    }

    Screen(SettingsSection.title(section), onBack = onBack, onHome = onHome) {
        if (me == null) { Loading(); return@Screen }
        Spacer(Modifier.height(8.dp))

        when (section) {
            // ── 화면 · 글씨 ──────────────────────────────────────────────
            SettingsSection.DISPLAY -> {
                SectionTitle("글씨 크기", top = 4.dp)
                Card {
                    Text("이 글씨가 잘 보이시나요?", style = MaterialTheme.typography.bodyLarge)
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
                SectionTitle("묵상 본문")
                BigButton(
                    "묵상 본문 링크 변경", tone = ButtonTone.Plain,
                    subtitle = "이 기기에서만 바뀝니다",
                ) { onNavigate(Route.BIBLE_LINK) }
            }

            // ── 알림 ────────────────────────────────────────────────────
            SettingsSection.NOTIFY -> Card {
                val cfg = pushConfig
                when {
                    !kr.woorichurch.app.BuildConfig.FIREBASE_READY ->
                        Text(
                            "이 앱에는 아직 알림 설정이 들어 있지 않습니다.\n" +
                                "(Firebase 설정 파일을 넣고 다시 빌드하면 켜집니다)",
                            style = MaterialTheme.typography.bodyMedium, color = Muted,
                        )
                    cfg == null -> Loading("알림 설정을 읽는 중입니다")
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (cfg.registered) "알림이 켜져 있습니다" else "알림 받기",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    if (cfg.registered) "새 나눔·공지가 오면 알려 드립니다"
                                    else "이 기기에서 알림을 받습니다",
                                    style = MaterialTheme.typography.bodySmall, color = Muted,
                                )
                            }
                            Switch(
                                checked = cfg.registered,
                                enabled = !pushBusy,
                                onCheckedChange = { on ->
                                    pushBusy = true
                                    scope.launch {
                                        val app = context.applicationContext as kr.woorichurch.app.WooriApp
                                        val r = if (on) kr.woorichurch.app.push.PushRegistrar.register(app)
                                                else kr.woorichurch.app.push.PushRegistrar.unregister(app)
                                        r.onFailure { note = it.message ?: "바꾸지 못했습니다" }
                                        pushConfig = runCatching { repo.pushConfig() }.getOrNull()
                                        pushBusy = false
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = Green),
                            )
                        }
                        if (cfg.registered) {
                            HorizontalDivider(color = Line)
                            listOfNotNull(
                                Triple("묵상 나눔 · 기도제목 · 댓글", cfg.notifyShare, "share"),
                                Triple("공지사항 · 주보", cfg.notifyBoard, "board"),
                                Triple(
                                    if (me.canManage) "가입 신청 · 내 정보 변경" else "내 정보 변경",
                                    cfg.notifyAdmin, "admin",
                                ),
                            ).forEach { (label, on, key) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = on,
                                        onCheckedChange = { v ->
                                            scope.launch {
                                                runCatching {
                                                    when (key) {
                                                        "share" -> repo.pushPrefs(share = v)
                                                        "board" -> repo.pushPrefs(board = v)
                                                        else -> repo.pushPrefs(admin = v)
                                                    }
                                                }
                                                pushConfig = runCatching { repo.pushConfig() }.getOrNull()
                                            }
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = Green),
                                    )
                                    Text(label, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                        Message(note)
                    }
                }
            }

            // ── 내 정보 ─────────────────────────────────────────────────
            SettingsSection.ACCOUNT -> {
                BigButton("이름 · 비밀번호 바꾸기", tone = ButtonTone.Plain) { onNavigate(Route.PROFILE) }
                Spacer(Modifier.height(10.dp))
                BigButton(
                    "묵상 기록 내려받기", tone = ButtonTone.Plain,
                    subtitle = "글 파일로 저장합니다",
                ) { onNavigate(Route.EXPORT) }
                Spacer(Modifier.height(24.dp))
                Text(
                    "${me.churchName} · ${me.name} ${me.title}\n" +
                        "소속 셀: ${me.cellName ?: "없음"}\n" +
                        "권한: ${kr.woorichurch.app.data.Roles.label(me.role)} · " +
                        "나눔 범위: ${kr.woorichurch.app.data.Scopes.label(me.shareScope)}",
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                )
                Spacer(Modifier.height(24.dp))
                BigButton("로그아웃", tone = ButtonTone.Danger) { onSignOut() }
            }

            // ── 교회 관리 ───────────────────────────────────────────────
            SettingsSection.CHURCH -> {
                if (!me.canManage) { Empty("관리 권한이 있는 분만 볼 수 있습니다."); return@Screen }
                val pending = state.home?.pendingApprovals ?: 0
                SectionTitle("성도", top = 4.dp)
                BigButton(
                    "교회 성도 / 셀 관리", tone = if (pending > 0) ButtonTone.Soft else ButtonTone.Plain,
                    subtitle = if (pending > 0) "가입 신청 ${pending}건이 기다리고 있습니다"
                               else "가입 승인 · 직분 · 셀 · 권한",
                ) { onNavigate(Route.MEMBERS) }
                Spacer(Modifier.height(10.dp))
                BigButton("직분 · 셀 변경 기록", tone = ButtonTone.Plain) { onNavigate(Route.AUDIT) }

                if (me.isOwner) {
                    SectionTitle("교회 설정 (관리자)")
                    BigButton("교회 줌 링크 설정", tone = ButtonTone.Plain) { onNavigate(Route.ZOOM) }
                    Spacer(Modifier.height(10.dp))
                    BigButton(
                        "나눔 범위 · 권한 주기", tone = ButtonTone.Plain,
                        subtitle = "성도 목록에서 사람마다 바꿉니다",
                    ) { onNavigate(Route.MEMBERS) }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    "관리자는 담임목사님과 같은 모든 권한을 가집니다. " +
                        "목사님이 가입하시면 성도 목록에서 권한을 ‘관리자’로 바꿔 드리세요.",
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                )
            }

            // ── 앱 운영자 ───────────────────────────────────────────────
            SettingsSection.PLATFORM -> {
                if (!me.isPlatformAdmin) { Empty("앱 운영자만 볼 수 있습니다."); return@Screen }
                BigButton("교회 등록 신청 심사", tone = ButtonTone.Plain) { onNavigate(Route.PLATFORM) }
                Spacer(Modifier.height(10.dp))
                BigButton("건의함 보기", tone = ButtonTone.Plain) { onNavigate(Route.SUGGESTIONS) }
            }

            // ── 문의 · 신고 ─────────────────────────────────────────────
            else -> {
                Text(
                    "고쳤으면 하는 점, 불편한 점을 적어 주세요. 앱 만든 사람에게만 전달됩니다.",
                    style = MaterialTheme.typography.bodyMedium, color = Muted,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "부적절한 글·댓글, 아동 안전과 관련된 문제도 여기로 신고해 주시면 24시간 안에 확인합니다.",
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                )
                Spacer(Modifier.height(12.dp))
                Field(suggestion, { suggestion = it }, "적을 내용", singleLine = false, minLines = 5)
                Spacer(Modifier.height(10.dp))
                BigButton("보내기") {
                    val t = suggestion.trim()
                    if (t.isNotBlank()) scope.launch {
                        runCatching { repo.sendSuggestion(t) }
                            .onSuccess { suggestion = ""; note = "보내 주셔서 감사합니다." }
                            .onFailure { note = it.message ?: "보내지 못했습니다" }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Message(note, tone = Green)
            }
        }
        Spacer(Modifier.height(32.dp))
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
//  교회 줌 링크 (관리자만)
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
