package kr.woorichurch.app.ui.auth

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
            subtitle = "교회 정보 네 가지만 적으면 됩니다",
        ) { onRegisterChurch() }
        Spacer(Modifier.height(24.dp))
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  2. 교회 새로 등록 — 한 칸씩 차례로
//   주소 찾기 → 교회 이름 → 교단 → 담임목사 성함 → 신청하시는 분 → 비밀번호
//   (주소를 맨 앞에 두면 그 뒤로는 키보드가 한 번도 닫히지 않습니다.
//    찾은 주소의 건물 이름이 '○○교회'면 교회 이름 칸에 미리 넣어 드립니다)
//   신청하신 분은 이 교회의 '관리자'가 되어 담임목사님과 같은 권한을 가집니다.
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun ChurchRegisterScreen(repo: Repo, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val values = remember { mutableStateMapOf<String, String>() }
    var building by remember { mutableStateOf("") }
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
                        "승인되면 ${values["applicant"].orEmpty()} 이름과 방금 정한 비밀번호로 들어오실 수 있습니다.\n\n" +
                        "신청하신 분은 이 교회의 관리자가 됩니다. 성도 가입 승인과 직분 지정을 맡아 주세요.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(24.dp))
            BigButton("처음 화면으로") { onBack() }
        }
        return
    }

    val steps = remember {
        listOf(
            Step(
                key = "address", name = "주소", question = "교회 주소를 찾아 주세요",
                custom = { v, set, next ->
                    AddressStep(v, set, next, onBuilding = { b ->
                        building = b
                        if (values["church"].isNullOrBlank() && b.contains("교회")) values["church"] = b
                    })
                },
            ),
            Step("church", "교회 이름", "교회 이름을 적어 주세요", hint = "예) 은혜교회"),
            Step("denom", "교단", "소속 교단을 적어 주세요", hint = "예) 예장합동, 예장통합, 기감, 기장, 기침 …"),
            Step("pastor", "담임목사", "담임목사님 성함을 적어 주세요", maxLen = 30),
            Step(
                "applicant", "신청하신 분", "신청하시는 분의 이름을 적어 주세요",
                hint = "이분이 교회 관리자가 됩니다. 목사님이 직접 하시면 목사님 성함 그대로 두세요.",
                maxLen = 30,
                prefill = { it["pastor"] },
            ),
            Step(
                "pin", "비밀번호", "들어올 때 쓸 숫자 6자리를 정해 주세요",
                numeric = true, password = true, maxLen = 6,
                validate = { v, _ -> if (v.length != 6) "숫자 6자리여야 합니다" else null },
            ),
            Step(
                "pin2", "비밀번호 확인", "같은 숫자 6자리를 한 번 더 적어 주세요",
                numeric = true, password = true, maxLen = 6,
                validate = { v, all -> if (v != all["pin"]) "위에서 정한 비밀번호와 다릅니다" else null },
            ),
        )
    }

    Screen("우리 교회 등록", onBack = onBack) {
        Spacer(Modifier.height(4.dp))
        Text(
            "교회 정보를 확인한 뒤 승인해 드립니다.",
            style = MaterialTheme.typography.bodyMedium, color = Muted,
        )
        Spacer(Modifier.height(16.dp))
        StepForm(
            steps = steps, values = values,
            finishLabel = "등록 신청하기", busy = busy, serverError = error,
        ) {
            error = ""
            busy = true
            scope.launch {
                runCatching {
                    repo.registerChurch(
                        churchName = values["church"].orEmpty().trim(),
                        address = values["address"].orEmpty().trim(),
                        denomination = values["denom"].orEmpty().trim(),
                        pastorName = values["pastor"].orEmpty().trim(),
                        applicantName = values["applicant"].orEmpty().trim(),
                        pinNumber = values["pin"].orEmpty(),
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
    var useEmail by remember { mutableStateOf(false) }
    val signupValues = remember { mutableStateMapOf<String, String>() }
    val signupSteps = remember {
        listOf(
            Step("name", "이름", "이름을 적어 주세요", hint = "교회에서 부르는 이름 그대로 적어 주세요", maxLen = 30),
            Step(
                "pin", "비밀번호", "들어올 때 쓸 숫자 6자리를 정해 주세요",
                numeric = true, password = true, maxLen = 6,
                validate = { v, _ -> if (v.length != 6) "숫자 6자리여야 합니다" else null },
            ),
            Step(
                "pin2", "비밀번호 확인", "같은 숫자 6자리를 한 번 더 적어 주세요",
                numeric = true, password = true, maxLen = 6,
                validate = { v, all -> if (v != all["pin"]) "위에서 정한 비밀번호와 다릅니다" else null },
            ),
        )
    }
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

        if (signUp) {
            // ── 가입 신청: 이름 → 비밀번호 → 비밀번호 확인, 한 칸씩 ──────────────
            StepForm(
                steps = signupSteps, values = signupValues,
                finishLabel = "가입 신청하기", busy = busy, serverError = error,
            ) {
                error = ""; busy = true
                scope.launch {
                    runCatching {
                        repo.signup(churchId, signupValues["name"].orEmpty().trim(), signupValues["pin"].orEmpty())
                    }.onSuccess { r ->
                        pendingMessage = r.message.ifBlank { "승인 후 이용할 수 있습니다." }
                        signupValues.clear()
                    }.onFailure { error = it.message ?: "신청하지 못했습니다" }
                    busy = false
                }
            }
            Spacer(Modifier.height(32.dp))
            return@Screen
        }

        if (useEmail) {
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
        }

        Spacer(Modifier.height(20.dp))
        Message(error)
        Spacer(Modifier.height(12.dp))

        BigButton(if (busy) "잠시만 기다려 주세요…" else "들어가기", enabled = !busy) {
            error = ""
            busy = true
            scope.launch {
                runCatching {
                    if (useEmail) { repo.loginByEmail(email.trim(), password); onLoggedIn() }
                    else {
                        if (pin.length != 6) throw ApiException("비밀번호는 숫자 6자리여야 합니다")
                        repo.login(churchId, name.trim(), pin); onLoggedIn()
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
                    if (useEmail) "이름과 숫자 비밀번호로 들어가기" else "이메일을 등록해 두신 분은 이메일로 들어가기",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "비밀번호를 잊으셨으면 목사님이나 교회 관리자께 말씀해 주세요.\n새 비밀번호를 만들어 드립니다.",
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
