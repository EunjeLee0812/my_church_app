package kr.woorichurch.app.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.woorichurch.app.data.ApiException
import kr.woorichurch.app.data.Church
import kr.woorichurch.app.data.Repo
import kr.woorichurch.app.ui.common.*
import kr.woorichurch.app.ui.theme.*

// ══════════════════════════════════════════════════════════════════════════
//  1. 교회 고르기
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun ChurchPickScreen(
    repo: Repo,
    onPicked: (Long, String) -> Unit,
    onRegisterChurch: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Church>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var last by remember { mutableStateOf<Pair<Long, String>?>(null) }

    LaunchedEffect(Unit) { last = repo.prefs.lastChurch() }

    // 입력이 멈추면 자동으로 찾습니다
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        searching = true; error = ""
        runCatching { repo.searchChurches(query.trim()) }
            .onSuccess { results = it }
            .onFailure { error = it.message ?: "교회를 찾지 못했습니다" }
        searching = false
    }

    Screen("우리 교회 찾기") {
        Spacer(Modifier.height(8.dp))
        Text(
            "다니시는 교회를 찾아 주세요.",
            style = MaterialTheme.typography.bodyLarge, color = Muted,
        )
        Spacer(Modifier.height(16.dp))

        last?.let { (id, name) ->
            if (name.isNotBlank()) {
                Card {
                    Text("지난번에 쓰신 교회", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Spacer(Modifier.height(6.dp))
                    Text(name, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    BigButton("이 교회로 들어가기") { onPicked(id, name) }
                }
                Spacer(Modifier.height(20.dp))
            }
        }

        Field(query, { query = it }, "교회 이름 또는 주소")
        Spacer(Modifier.height(12.dp))
        Message(error)

        if (searching) Loading("찾는 중입니다")

        results.forEach { ch ->
            Spacer(Modifier.height(10.dp))
            Card {
                Text(ch.name, style = MaterialTheme.typography.titleLarge)
                if (!ch.address.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(ch.address, style = MaterialTheme.typography.bodyMedium, color = Muted)
                }
                if (!ch.denomination.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(ch.denomination, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                Spacer(Modifier.height(12.dp))
                BigButton("이 교회 선택") { onPicked(ch.id, ch.name) }
            }
        }

        if (query.isNotBlank() && !searching && results.isEmpty() && error.isBlank()) {
            Empty("찾으시는 교회가 없습니다.\n아래에서 교회를 새로 등록할 수 있습니다.")
        }

        SectionTitle("교회가 목록에 없나요?", top = 32.dp)
        BigButton(
            "우리 교회 가입하기",
            tone = ButtonTone.Soft,
            subtitle = "담임목사님이 교회를 새로 등록합니다",
        ) { onRegisterChurch() }
        Spacer(Modifier.height(24.dp))
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  2. 교회 새로 등록 (담임목사)
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun ChurchRegisterScreen(repo: Repo, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var churchName by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var denomination by remember { mutableStateOf("") }
    var pastorName by remember { mutableStateOf("") }
    var seminary by remember { mutableStateOf("") }
    var homepage by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }

    if (done) {
        Screen("신청 완료", onBack = onBack) {
            Spacer(Modifier.height(40.dp))
            Card {
                Text("등록 신청이 접수되었습니다", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    "교회 정보를 확인한 뒤 승인해 드립니다. 보통 1~2일 걸립니다.\n" +
                        "승인되면 입력하신 이름과 비밀번호로 로그인할 수 있습니다.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(24.dp))
            BigButton("처음 화면으로") { onBack() }
        }
        return
    }

    Screen("우리 교회 등록", onBack = onBack) {
        Spacer(Modifier.height(4.dp))
        Text(
            "담임목사님이 직접 신청해 주세요. 아래 정보를 확인한 뒤 승인해 드립니다.",
            style = MaterialTheme.typography.bodyMedium, color = Muted,
        )

        SectionTitle("교회 정보")
        Field(churchName, { churchName = it }, "교회 이름 *")
        Spacer(Modifier.height(10.dp))
        Field(address, { address = it }, "교회 주소 *")
        Spacer(Modifier.height(10.dp))
        Field(phone, { phone = it }, "교회 대표전화 *")
        Spacer(Modifier.height(10.dp))
        Field(
            denomination, { denomination = it }, "소속 교단 *",
            supporting = "예) 예장합동, 예장통합, 기감, 기장, 기침 …",
        )
        Spacer(Modifier.height(10.dp))
        Field(
            homepage, { homepage = it }, "교회 홈페이지 또는 교단 교회검색 주소",
            supporting = "교단 명부에서 확인할 수 있으면 승인이 빨라집니다",
        )

        SectionTitle("담임목사님 정보")
        Field(pastorName, { pastorName = it }, "담임목사 성함 *")
        Spacer(Modifier.height(10.dp))
        Field(seminary, { seminary = it }, "졸업 신학교 / 안수 교단")

        SectionTitle("로그인에 쓸 비밀번호")
        Field(pin, { pin = it.filter(Char::isDigit).take(6) }, "숫자 6자리 *", numeric = true, password = true)
        Spacer(Modifier.height(10.dp))
        Field(pin2, { pin2 = it.filter(Char::isDigit).take(6) }, "숫자 6자리 다시 입력 *", numeric = true, password = true)

        SectionTitle("관리자 로그인 (권장)")
        Text(
            "목사님 계정은 성도 명단 전체를 볼 수 있어 더 안전하게 지켜야 합니다.\n" +
                "이메일과 비밀번호를 함께 등록해 두시면 그쪽으로도 로그인할 수 있습니다.",
            style = MaterialTheme.typography.bodySmall, color = Muted,
        )
        Spacer(Modifier.height(10.dp))
        Field(email, { email = it }, "이메일")
        Spacer(Modifier.height(10.dp))
        Field(password, { password = it }, "비밀번호 (8자 이상)", password = true)

        SectionTitle("초대 코드 (있는 경우)")
        Field(inviteCode, { inviteCode = it.uppercase() }, "초대 코드", supporting = "이미 쓰고 계신 교회에서 받은 코드")

        Spacer(Modifier.height(10.dp))
        Field(note, { note = it }, "남기실 말씀", singleLine = false, minLines = 3)

        Spacer(Modifier.height(20.dp))
        Message(error)
        Spacer(Modifier.height(12.dp))
        BigButton(if (busy) "신청하는 중…" else "등록 신청하기", enabled = !busy) {
            error = ""
            if (pin.length != 6) { error = "비밀번호는 숫자 6자리여야 합니다"; return@BigButton }
            if (pin != pin2) { error = "비밀번호 두 개가 서로 다릅니다"; return@BigButton }
            if (email.isNotBlank() && password.length < 8) {
                error = "관리자 비밀번호는 8자 이상이어야 합니다"; return@BigButton
            }
            busy = true
            scope.launch {
                runCatching {
                    repo.registerChurch(
                        churchName.trim(), address.trim(), phone.trim(), denomination.trim(),
                        pastorName.trim(), seminary.trim(), homepage.trim(), note.trim(),
                        pin, email.trim(), password, inviteCode.trim(),
                    )
                }.onSuccess { done = true }
                    .onFailure { error = (it as? ApiException)?.message ?: "신청하지 못했습니다" }
                busy = false
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  3. 로그인 / 성도 가입
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun LoginScreen(
    repo: Repo,
    churchId: Long,
    churchName: String,
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var signUp by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var useEmail by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var pendingMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        name = repo.prefs.lastName()
        if (churchId > 0 && churchName.isNotBlank()) repo.prefs.rememberChurch(churchId, churchName)
    }

    Screen(if (churchName.isBlank()) "로그인" else churchName, onBack = onBack) {
        Spacer(Modifier.height(8.dp))

        if (pendingMessage.isNotBlank()) {
            Card {
                Text("가입 신청 완료", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(pendingMessage, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(20.dp))
            BigButton("확인") { pendingMessage = ""; signUp = false }
            return@Screen
        }

        Toggle2("로그인", "처음 왔어요", signUp) { signUp = it; error = "" }
        Spacer(Modifier.height(24.dp))

        if (!signUp && useEmail) {
            Field(email, { email = it }, "이메일")
            Spacer(Modifier.height(12.dp))
            Field(password, { password = it }, "비밀번호", password = true)
        } else {
            Field(name, { name = it }, "이름")
            Spacer(Modifier.height(12.dp))
            Field(
                pin, { pin = it.filter(Char::isDigit).take(6) }, "숫자 6자리 비밀번호",
                numeric = true, password = true,
            )
            if (signUp) {
                Spacer(Modifier.height(12.dp))
                Field(
                    pin2, { pin2 = it.filter(Char::isDigit).take(6) }, "비밀번호 다시 입력",
                    numeric = true, password = true,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Message(error)
        Spacer(Modifier.height(12.dp))

        BigButton(
            when {
                busy -> "잠시만 기다려 주세요…"
                signUp -> "가입 신청하기"
                else -> "들어가기"
            },
            enabled = !busy,
        ) {
            error = ""
            busy = true
            scope.launch {
                runCatching {
                    when {
                        signUp -> {
                            if (pin.length != 6) throw ApiException("비밀번호는 숫자 6자리여야 합니다")
                            if (pin != pin2) throw ApiException("비밀번호 두 개가 서로 다릅니다")
                            val r = repo.signup(churchId, name.trim(), pin)
                            pendingMessage = r.message.ifBlank { "목사님 승인 후 이용할 수 있습니다." }
                        }
                        useEmail -> { repo.loginByEmail(email.trim(), password); onLoggedIn() }
                        else -> {
                            if (pin.length != 6) throw ApiException("비밀번호는 숫자 6자리여야 합니다")
                            repo.login(churchId, name.trim(), pin); onLoggedIn()
                        }
                    }
                }.onFailure { error = it.message ?: "들어가지 못했습니다" }
                busy = false
            }
        }

        if (!signUp) {
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = { useEmail = !useEmail; error = "" },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (useEmail) "이름과 숫자 비밀번호로 들어가기" else "목사님·관리자는 이메일로 들어가기",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "비밀번호를 잊으셨으면 목사님께 말씀해 주세요.\n새 비밀번호를 만들어 드립니다.",
                style = MaterialTheme.typography.bodySmall, color = Muted,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  4. 초기화된 비밀번호 바꾸기
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun ChangePinScreen(repo: Repo, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    Screen("새 비밀번호 만들기") {
        Spacer(Modifier.height(24.dp))
        Card {
            Text(
                "임시 비밀번호로 들어오셨습니다.\n앞으로 쓰실 숫자 6자리를 새로 정해 주세요.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(Modifier.height(24.dp))
        Field(pin, { pin = it.filter(Char::isDigit).take(6) }, "새 비밀번호 (숫자 6자리)", numeric = true, password = true)
        Spacer(Modifier.height(12.dp))
        Field(pin2, { pin2 = it.filter(Char::isDigit).take(6) }, "다시 한 번", numeric = true, password = true)
        Spacer(Modifier.height(20.dp))
        Message(error)
        Spacer(Modifier.height(12.dp))
        BigButton(if (busy) "바꾸는 중…" else "이 비밀번호로 정하기", enabled = !busy) {
            error = ""
            if (pin.length != 6) { error = "숫자 6자리여야 합니다"; return@BigButton }
            if (pin != pin2) { error = "두 개가 서로 다릅니다"; return@BigButton }
            busy = true
            scope.launch {
                runCatching { repo.updateMe(newPin = pin) }
                    .onSuccess { onDone() }
                    .onFailure { error = it.message ?: "바꾸지 못했습니다" }
                busy = false
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
