package kr.woorichurch.app.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import kr.woorichurch.app.ui.common.Button
import kr.woorichurch.app.ui.common.IconButton
import kr.woorichurch.app.ui.common.OutlinedButton
import kr.woorichurch.app.ui.common.Surface
import kr.woorichurch.app.ui.common.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kr.woorichurch.app.data.Prefs
import kr.woorichurch.app.data.Roles
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
    val home = state.home
    val progress = state.progress

    var bibleUrl by remember { mutableStateOf(Prefs.DEFAULT_BIBLE_URL) }
    var notice by remember { mutableStateOf("") }

    // 캐시로 이미 그려진 뒤라면 여기서는 조용히 새로 받기만 합니다
    LaunchedEffect(Unit) {
        bibleUrl = repo.prefs.bibleUrlNow()
        state.refresh()
    }
    // 시니어 홈에서도 다음에 누를 화면(나눔광장·공지·내 기록 등)을 미리 받아 둡니다
    LaunchedEffect(state.me) { state.prefetchAround("home") }

    Screen(
        title = me?.churchName ?: "모두의 교회",
        actions = {
            IconButton(onClick = { onNavigate(Route.SETTINGS) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Settings, "설정", modifier = Modifier.size(30.dp))
            }
        },
    ) {
        if (me == null) { Loading(); return@Screen }

        Spacer(Modifier.height(4.dp))
        // 이름은 한 줄로, 구역·권한 표시는 그 아래에 (구역이 많아도 이름이 깨지지 않게)
        Text("${me.name} ${me.title}", style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            me.cellName?.let { Pill(it) }
            if (me.role == Roles.OWNER || me.role == Roles.ADMIN) Pill(Roles.label(me.role), Muted)
            Text(D.koreanFull(D.today()), style = MaterialTheme.typography.bodyMedium, color = Muted, maxLines = 1)
        }

        // ── 새 공지 배너 ──────────────────────────────────────────────────
        home?.latestNotice?.let { n ->
            if (n.unread) {
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = GreenLight,
                    shape = RoundedCornerShape(14.dp),
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
                Spacer(Modifier.height(12.dp))
                BigButton("내 묵상기록 보기", tone = ButtonTone.Soft) {
                    onNavigate(Route.person(me.id))
                }
                if (home?.wroteToday == false) {
                    Spacer(Modifier.height(12.dp))
                    Text("오늘 묵상은 아직 올리지 않으셨어요.", style = MaterialTheme.typography.bodyMedium, color = Muted)
                }
            }
        }

        // ── 큰 버튼들 ─────────────────────────────────────────────────────
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("묵상 본문 보기", Modifier.weight(1f), tone = ButtonTone.Soft) {
                Launch.web(context, bibleUrl)
            }
            BigButton("교회 줌 접속", Modifier.weight(1f), tone = ButtonTone.Soft) {
                val url = home?.zoomUrl ?: me.zoomUrl
                if (url.isNullOrBlank()) {
                    notice = if (me.isOwner)
                        "아직 줌 링크가 없습니다. 설정 → 교회 관리 → 교회 줌 링크에서 등록해 주세요."
                    else "아직 줌 링크가 등록되지 않았습니다. 교회 관리자께 말씀해 주세요."
                } else {
                    notice = ""
                    if (!Launch.zoom(context, url)) notice = "줌을 열지 못했습니다."
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("묵상 나눔하기", Modifier.weight(1f)) { onNavigate(Route.write()) }
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("기도제목 나눔", Modifier.weight(1f), tone = ButtonTone.Plain) { onNavigate(Route.PRAYER) }
            BigButton("돌림판", Modifier.weight(1f), tone = ButtonTone.Plain) { onNavigate(Route.ROULETTE) }
        }

        if (me.role == Roles.LEADER || me.canManage) {
            Spacer(Modifier.height(12.dp))
            BigButton("우리 셀 현황 보기", tone = ButtonTone.Plain) { onNavigate(Route.CELL_STATUS) }
        }
        if ((home?.pendingApprovals ?: 0) > 0) {
            Spacer(Modifier.height(12.dp))
            BigButton(
                "가입 신청 ${home?.pendingApprovals}건",
                tone = ButtonTone.Soft, subtitle = "승인을 기다리고 있습니다",
            ) { onNavigate(Route.MEMBERS) }
        }

        Spacer(Modifier.height(16.dp))
        Message(notice, tone = Green)
        Spacer(Modifier.height(24.dp))
    }
}
