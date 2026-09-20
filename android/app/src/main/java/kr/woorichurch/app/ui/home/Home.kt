package kr.woorichurch.app.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kr.woorichurch.app.data.HomeData
import kr.woorichurch.app.data.Progress
import kr.woorichurch.app.ui.AppState
import kr.woorichurch.app.ui.Route
import kr.woorichurch.app.ui.common.*
import kr.woorichurch.app.ui.theme.*
import kr.woorichurch.app.util.D
import kr.woorichurch.app.util.Launch

@Composable
fun HomeScreen(
    state: AppState,
    onNavigate: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val repo = state.repo
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val me = state.me

    var home by remember { mutableStateOf<HomeData?>(null) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    var bibleUrl by remember { mutableStateOf(kr.woorichurch.app.data.Prefs.DEFAULT_BIBLE_URL) }
    var notice by remember { mutableStateOf("") }
    var suggestion by remember { mutableStateOf("") }
    var showSuggest by remember { mutableStateOf(false) }

    suspend fun load() {
        state.refreshMe()
        home = runCatching { repo.home() }.getOrNull()
        progress = runCatching { repo.progress() }.getOrNull()
        bibleUrl = repo.prefs.bibleUrlNow()
    }

    LaunchedEffect(Unit) { load() }

    Screen(
        title = me?.churchName ?: "우리교회",
        actions = {
            IconButton(onClick = { onNavigate(Route.SETTINGS) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Settings, "설정", modifier = Modifier.size(30.dp))
            }
        },
    ) {
        if (me == null) { Loading(); return@Screen }

        // ── 인사 ─────────────────────────────────────────────────────────
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("${me.name} ${me.title}", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(10.dp))
            me.cellName?.let { Pill(it) }
        }
        Spacer(Modifier.height(4.dp))
        Text(D.koreanFull(D.today()), style = MaterialTheme.typography.bodyMedium, color = Muted)

        // ── 새 공지 배너 ──────────────────────────────────────────────────
        home?.latestNotice?.let { n ->
            if (n.unread) {
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = GreenLight,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    onClick = { onNavigate(Route.NOTICE) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("새 공지", style = MaterialTheme.typography.labelSmall, color = Green)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            n.title?.takeIf { it.isNotBlank() } ?: n.body.take(40),
                            style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 2,
                        )
                    }
                }
            }
        }

        // ── 나의 묵상 (본인만 보임) ────────────────────────────────────────
        progress?.let { p ->
            Spacer(Modifier.height(20.dp))
            Card {
                Text("나의 묵상", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                val q = p.quarters.getOrNull(((D.parse(D.today())?.monthValue ?: 1) - 1) / 3)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("이번 분기", style = MaterialTheme.typography.bodySmall, color = Muted)
                        Text("${q?.percent ?: 0}%", style = MaterialTheme.typography.headlineMedium, color = Green)
                        Text("${q?.done ?: 0} / ${q?.total ?: 0}일", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("올해", style = MaterialTheme.typography.bodySmall, color = Muted)
                        Text("${p.annual.percent}%", style = MaterialTheme.typography.headlineMedium, color = Green)
                        Text("${p.annual.done} / ${p.annual.total}일", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
                if (home?.wroteToday == false) {
                    Spacer(Modifier.height(12.dp))
                    Text("오늘 묵상은 아직 올리지 않으셨어요.", style = MaterialTheme.typography.bodyMedium, color = Muted)
                }
            }
        }

        // ── 큰 버튼 6개 ───────────────────────────────────────────────────
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("묵상 본문 보기", Modifier.weight(1f), tone = ButtonTone.Soft) {
                Launch.web(context, bibleUrl)
            }
            BigButton("교회 줌 접속", Modifier.weight(1f), tone = ButtonTone.Soft) {
                val url = home?.zoomUrl ?: me.zoomUrl
                if (url.isNullOrBlank()) {
                    notice = if (me.isOwner)
                        "아직 줌 링크가 없습니다. 설정 → 교회 줌 링크에서 등록해 주세요."
                    else "아직 줌 링크가 등록되지 않았습니다. 목사님께 말씀해 주세요."
                } else {
                    notice = ""
                    if (!Launch.zoom(context, url)) notice = "줌을 열지 못했습니다."
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("묵상 나눔하기", Modifier.weight(1f)) { onNavigate(Route.WRITE) }
            BigButton("묵상 나눔광장", Modifier.weight(1f)) { onNavigate(Route.FEED) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton(
                "공지사항", Modifier.weight(1f), tone = ButtonTone.Plain,
                subtitle = (home?.unreadNoticeCount ?: 0).takeIf { it > 0 }?.let { "새 글 ${it}개" },
            ) { onNavigate(Route.NOTICE) }
            BigButton(
                "이번 주 주보", Modifier.weight(1f), tone = ButtonTone.Plain,
                subtitle = home?.latestBulletin?.weekOf?.let { D.korean(it) },
            ) { onNavigate(Route.BULLETIN) }
        }
        Spacer(Modifier.height(12.dp))
        BigButton("기도제목 나눔", tone = ButtonTone.Plain) { onNavigate(Route.PRAYER) }

        Spacer(Modifier.height(16.dp))
        Message(notice, tone = Green)

        // ── 관리자용 ──────────────────────────────────────────────────────
        if (me.canManage) {
            SectionTitle("관리")
            if ((home?.pendingApprovals ?: 0) > 0) {
                BigButton(
                    "가입 신청 ${home?.pendingApprovals}건",
                    tone = ButtonTone.Soft, subtitle = "승인을 기다리고 있습니다",
                ) { onNavigate(Route.APPROVALS) }
                Spacer(Modifier.height(10.dp))
            }
            BigButton("교회 성도 / 셀 관리", tone = ButtonTone.Plain) { onNavigate(Route.MEMBERS) }
            Spacer(Modifier.height(10.dp))
            BigButton("건의함 보기", tone = ButtonTone.Plain) { onNavigate(Route.SUGGESTIONS) }
        }
        if (me.role == kr.woorichurch.app.data.Roles.LEADER || me.canManage) {
            Spacer(Modifier.height(10.dp))
            BigButton("우리 셀 현황 보기", tone = ButtonTone.Plain) { onNavigate(Route.CELL_STATUS) }
        }
        if (me.isPlatformAdmin) {
            Spacer(Modifier.height(10.dp))
            BigButton("교회 등록 신청 심사", tone = ButtonTone.Plain) { onNavigate(Route.PLATFORM) }
        }

        // ── 건의함 ────────────────────────────────────────────────────────
        SectionTitle("앱에 바라는 점")
        if (!showSuggest) {
            BigButton("건의 남기기", tone = ButtonTone.Plain) { showSuggest = true }
        } else {
            Field(suggestion, { suggestion = it }, "고쳤으면 하는 점을 적어 주세요", singleLine = false, minLines = 3)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("보내기", Modifier.weight(1f)) {
                    val text = suggestion.trim()
                    if (text.isNotBlank()) scope.launch {
                        runCatching { repo.sendSuggestion(text) }
                            .onSuccess { suggestion = ""; showSuggest = false; notice = "보내 주셔서 감사합니다." }
                            .onFailure { notice = it.message ?: "보내지 못했습니다" }
                    }
                }
                BigButton("그만두기", Modifier.weight(1f), tone = ButtonTone.Plain) {
                    showSuggest = false; suggestion = ""
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("로그아웃", style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        Spacer(Modifier.height(24.dp))
    }
}
