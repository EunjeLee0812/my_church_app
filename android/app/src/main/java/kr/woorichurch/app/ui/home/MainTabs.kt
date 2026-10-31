package kr.woorichurch.app.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import kr.woorichurch.app.ui.common.Button
import kr.woorichurch.app.ui.common.IconButton
import kr.woorichurch.app.ui.common.OutlinedButton
import kr.woorichurch.app.ui.common.Surface
import kr.woorichurch.app.ui.common.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kr.woorichurch.app.data.Prefs
import kr.woorichurch.app.data.Roles
import kr.woorichurch.app.ui.AppState
import kr.woorichurch.app.ui.Route
import kr.woorichurch.app.ui.board.BulletinScreen
import kr.woorichurch.app.ui.board.NoticeScreen
import kr.woorichurch.app.ui.common.*
import kr.woorichurch.app.ui.devotion.FeedScreen
import kr.woorichurch.app.ui.settings.SettingsScreen
import kr.woorichurch.app.ui.theme.*
import kr.woorichurch.app.util.D
import kr.woorichurch.app.util.Launch

// ══════════════════════════════════════════════════════════════════════════
//  새 화면 (시니어 모드가 꺼져 있을 때)
//  아래 탭 다섯 개: 더보기 · 알림 · 홈 · 나눔광장 · 설정
// ══════════════════════════════════════════════════════════════════════════
object Tab {
    const val MORE = "more"
    const val ALERTS = "alerts"
    const val HOME = "home"
    const val FEED = "feed"
    const val SETTINGS = "settings"
}

@Composable
fun MainTabs(
    state: AppState,
    onNavigate: (String) -> Unit,
    onSignOut: () -> Unit,
    startTab: String = Tab.HOME,
) {
    var tab by rememberSaveable { mutableStateOf(startTab) }
    val unread = state.home?.unreadNoticeCount ?: 0

    // 아래로 내려 읽는 중이면 탭 막대를 작게, 다시 올리면 크게 (인스타그램처럼)
    var compact by remember { mutableStateOf(false) }
    val scrollWatch = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y < -2f) compact = true          // 손가락을 위로 = 내용을 아래로 읽는 중
                else if (consumed.y > 2f) compact = false     // 다시 위로 올림
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(tab) { compact = false }

    LaunchedEffect(Unit) { state.refresh() }
    // 지금 탭에서 다음에 누를 만한 화면의 데이터를 미리 받아 둡니다
    LaunchedEffect(tab, state.me) { if (tab != Tab.FEED) state.prefetchAround(tab) }

    // 떠 있는 탭 막대 아래로 마지막 내용이 가려지지 않게 화면마다 아래 여백을 줍니다
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(Bg)) {
        Box(Modifier.fillMaxSize().nestedScroll(scrollWatch)) {
            CompositionLocalProvider(
                LocalInTabs provides true,
                LocalTabBarSpace provides (TabBarHeight + 24.dp + navInset),
            ) {
                when (tab) {
                    Tab.MORE -> MoreTab(state, onNavigate)
                    Tab.ALERTS -> AlertsTab(state)
                    Tab.FEED -> FeedScreen(
                        state = state, onBack = null, onHome = null,
                        onPerson = { onNavigate(Route.person(it)) },
                        onAttendance = { onNavigate(Route.ATTENDANCE) },
                        onSearch = { onNavigate(Route.SEARCH) },
                        onEdit = { onNavigate(Route.write(it)) },
                    )
                    Tab.SETTINGS -> SettingsScreen(
                        state = state, onBack = null, onHome = null,
                        onNavigate = onNavigate, onSignOut = onSignOut,
                    )
                    else -> ModernHome(state, onNavigate) { tab = it }
                }
            }
        }
        FloatingTabs(
            selected = tab, alertDot = unread > 0, compact = compact,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) { tab = it; compact = false }
    }
}

private val TabBarHeight = 70.dp
private val TabBarHeightSmall = 52.dp

// ── 아래 탭 막대 — 화면 위에 둥둥 떠 있는 알약 모양 ───────────────────────────
//   크게: 아이콘 + 글씨, 가운데 홈은 동그라미
//   작게: 아래로 읽는 중엔 폭·높이가 줄고 글씨는 숨김 → 화면을 덜 가립니다
@Composable
private fun FloatingTabs(
    selected: String, alertDot: Boolean, compact: Boolean, modifier: Modifier,
    onSelect: (String) -> Unit,
) {
    val t by animateFloatAsState(
        if (compact) 1f else 0f,
        spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
        label = "tabbar",
    )
    BoxWithConstraints(
        modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 10.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val full = (maxWidth - 24.dp).coerceAtMost(520.dp)
        val small = (maxWidth * 0.62f).coerceIn(240.dp, 320.dp).coerceAtMost(full)
        val width = lerp(full, small, t)
        val height = lerp(TabBarHeight, TabBarHeightSmall, t)
        Surface(
            color = Surface.copy(alpha = 0.97f),
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, Line),
            shadowElevation = 12.dp,
            modifier = Modifier.width(width).height(height),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = lerp(8.dp, 6.dp, t), vertical = lerp(7.dp, 5.dp, t)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabItem(Icons.Outlined.Apps, "더보기", selected == Tab.MORE, t, Modifier.weight(1f)) { onSelect(Tab.MORE) }
                TabItem(Icons.Outlined.Notifications, "알림", selected == Tab.ALERTS, t, Modifier.weight(1f), dot = alertDot) {
                    onSelect(Tab.ALERTS)
                }
                // 가운데 홈
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    val on = selected == Tab.HOME
                    val size = lerp(52.dp, 40.dp, t)
                    Box(
                        Modifier.pressBounce().size(size).clip(CircleShape)
                            .background(if (on) Green else Surface)
                            .border(1.5.dp, if (on) Green else Line, CircleShape)
                            .clickable { onSelect(Tab.HOME) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Home, "홈", tint = if (on) Color.White else Green,
                            modifier = Modifier.size(lerp(26.dp, 22.dp, t)),
                        )
                    }
                }
                TabItem(Icons.Outlined.Forum, "나눔광장", selected == Tab.FEED, t, Modifier.weight(1f)) { onSelect(Tab.FEED) }
                TabItem(Icons.Outlined.Menu, "설정", selected == Tab.SETTINGS, t, Modifier.weight(1f)) { onSelect(Tab.SETTINGS) }
            }
        }
    }
}

@Composable
private fun TabItem(
    icon: ImageVector, label: String, on: Boolean, t: Float, modifier: Modifier, dot: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier.fillMaxHeight().pressBounce().clip(RoundedCornerShape(50))
            .background(if (on) GreenLight else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                Icon(icon, label, tint = if (on) Green else Muted, modifier = Modifier.size(lerp(24.dp, 22.dp, t)))
                if (dot) Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-1).dp).size(8.dp)
                        .clip(CircleShape).background(Danger),
                )
            }
            // 작아질 때 글씨는 접어서 숨깁니다
            if (t < 0.98f) {
                Text(
                    label, style = MaterialTheme.typography.labelSmall,
                    color = if (on) Green else Muted,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(top = (2 * (1f - t)).dp)
                        .graphicsLayer { alpha = (1f - t * 1.6f).coerceIn(0f, 1f) }
                        .layout { m, c ->
                            val p = m.measure(c)
                            val h = (p.height * (1f - t)).toInt()
                            layout(p.width, h) { p.placeRelative(0, 0) }
                        },
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  홈
// ══════════════════════════════════════════════════════════════════════════
@Composable
private fun ModernHome(state: AppState, onNavigate: (String) -> Unit, onTab: (String) -> Unit) {
    val me = state.me
    val home = state.home
    val progress = state.progress

    Screen(me?.churchName ?: "모두의 교회") {
        if (me == null) { Loading(); return@Screen }

        // ── 인사 ────────────────────────────────────────────────────────
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(D.koreanFull(D.today()), style = MaterialTheme.typography.bodyMedium, color = Muted)
                Text("${me.name} ${me.title}", style = MaterialTheme.typography.headlineMedium, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    me.cellName?.let { Pill(it) }
                    if (me.role == Roles.OWNER || me.role == Roles.ADMIN) Pill(Roles.label(me.role), Muted)
                }
            }
            Box(
                Modifier.pressBounce().size(54.dp).clip(CircleShape).background(Green)
                    .clickable { onTab(Tab.SETTINGS) },
                contentAlignment = Alignment.Center,
            ) {
                Text(me.name.take(1), color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
        }

        // ── 알려 드릴 것 (새 공지 · 가입 신청) ─────────────────────────────
        home?.latestNotice?.takeIf { it.unread }?.let { n ->
            Spacer(Modifier.height(16.dp))
            SoftRow(Icons.Outlined.Campaign, "새 공지", n.title?.takeIf { it.isNotBlank() } ?: n.body.take(40)) {
                onTab(Tab.ALERTS)
            }
        }
        if ((home?.pendingApprovals ?: 0) > 0) {
            Spacer(Modifier.height(10.dp))
            SoftRow(Icons.Outlined.PersonAdd, "가입 신청", "${home?.pendingApprovals}건이 승인을 기다리고 있어요") {
                onNavigate(Route.MEMBERS)
            }
        }

        // ── 나의 묵상 ────────────────────────────────────────────────────
        // 분기·연간 % 는 기간 전체(3달 / 1년) 중 며칠 했는지 (서버 계산)
        progress?.let { p ->
            Spacer(Modifier.height(18.dp))
            val q = p.quarters.getOrNull(((D.parse(D.today())?.monthValue ?: 1) - 1) / 3)
            RoundCard(tint = SurfaceWarm) {
                CardHead(Icons.Outlined.WbSunny, "나의 묵상") {
                    Text(
                        "내 기록 ›", style = MaterialTheme.typography.labelMedium, color = Green,
                        modifier = Modifier.pressBounce().clip(RoundedCornerShape(8.dp))
                            .clickable { onNavigate(Route.person(me.id)) }.padding(6.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("이번 분기", q?.percent ?: 0, "${q?.done ?: 0} / ${q?.total ?: 0}일", Modifier.weight(1f))
                    Stat("올해", p.annual.percent, "${p.annual.done} / ${p.annual.total}일", Modifier.weight(1f))
                }
            }
        }

        // ── 오늘의 실천 — 묵상 나누기 · 기도제목 나누기 (제목도 카드 안에) ──────────
        Spacer(Modifier.height(16.dp))
        val wrote = home?.wroteToday == true
        RoundCard(tint = SurfaceWarm) {
            CardHead(Icons.Outlined.CheckCircle, "오늘의 실천")
            ActionBox(
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                title = if (wrote) "오늘 묵상을 나눴어요" else "오늘의 묵상을 남겨볼까요?",
                button = if (wrote) "오늘 묵상 고치기" else "묵상 나누기",
            ) { onNavigate(if (wrote) Route.write(D.today()) else Route.write()) }
            Spacer(Modifier.height(12.dp))
            ActionBox(
                icon = Icons.Outlined.VolunteerActivism,
                title = "함께 기도할 제목이 있나요?",
                button = "기도제목 나누기",
            ) { onNavigate(Route.PRAYER) }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** 카드 안 맨 위 제목 줄 — 나의 묵상 · 오늘의 실천 모두 같은 자리 */
@Composable
private fun CardHead(icon: ImageVector, text: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Green, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = Green, modifier = Modifier.weight(1f))
        trailing()
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Stat(label: String, percent: Int, sub: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Surface).padding(14.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Muted)
        Text("$percent%", style = MaterialTheme.typography.headlineMedium, color = Green)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { (percent.coerceIn(0, 100)) / 100f },
            color = Green, trackColor = GreenLight,
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.height(6.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

/** 둥근 큰 카드 — 참고 이미지처럼 부드러운 바탕 + 연한 테두리 */
@Composable
fun RoundCard(tint: Color = Surface, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = tint,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(18.dp), content = content) }
}

@Composable
private fun IconBubble(icon: ImageVector, size: Int = 48, tint: Color = SurfaceWarm) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 3).dp)).background(tint),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Green, modifier = Modifier.size((size / 2).dp)) }
}

@Composable
private fun SoftRow(icon: ImageVector, small: String, title: String, onClick: () -> Unit) {
    Surface(
        color = GreenLight, shape = RoundedCornerShape(16.dp), onClick = onClick, modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Green, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(small, style = MaterialTheme.typography.labelSmall, color = Green)
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Green)
        }
    }
}

/** 카드 안의 흰 상자 하나 — 동그란 아이콘 · 한 줄 질문 · 버튼 */
@Composable
private fun ActionBox(icon: ImageVector, title: String, button: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).padding(vertical = 18.dp, horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(SurfaceWarm), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Green, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.White),
            shape = RoundedCornerShape(28.dp),
            contentPadding = PaddingValues(horizontal = 26.dp, vertical = 14.dp),
        ) { Text("$button  ›", style = MaterialTheme.typography.labelLarge) }
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  알림 — 공지사항 · 주보
// ══════════════════════════════════════════════════════════════════════════
@Composable
private fun AlertsTab(state: AppState) {
    var sub by rememberSaveable { mutableStateOf("notice") }
    val header: @Composable ColumnScope.() -> Unit = {
        Spacer(Modifier.height(8.dp))
        Segments(listOf("notice" to "공지사항", "bulletin" to "주보"), sub) { sub = it }
        Spacer(Modifier.height(4.dp))
    }
    if (sub == "notice") NoticeScreen(state, onBack = null, onHome = null, title = "알림", header = header)
    else BulletinScreen(state, onBack = null, onHome = null, title = "알림", header = header)
}

// ══════════════════════════════════════════════════════════════════════════
//  더보기 — 기타 기능
// ══════════════════════════════════════════════════════════════════════════
@Composable
private fun MoreTab(state: AppState, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val me = state.me
    var bibleUrl by remember { mutableStateOf(Prefs.DEFAULT_BIBLE_URL) }
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { bibleUrl = state.repo.prefs.bibleUrlNow() }

    Screen("더보기") {
        Spacer(Modifier.height(8.dp))
        Text("추가 기능", style = MaterialTheme.typography.titleMedium, color = Muted)
        Spacer(Modifier.height(10.dp))
        ToolRow(
            ToolItem(Icons.Outlined.PieChart, "돌림판", "점심 메뉴 정하기") { onNavigate(Route.ROULETTE) },
            ToolItem(Icons.Outlined.Videocam, "교회 줌", "바로 입장") {
                val url = state.home?.zoomUrl ?: me?.zoomUrl
                notice = when {
                    url.isNullOrBlank() ->
                        if (me?.isOwner == true) "아직 줌 링크가 없습니다. 설정 → 교회 관리 → 교회 줌 링크에서 등록해 주세요."
                        else "아직 줌 링크가 등록되지 않았습니다. 교회 관리자께 말씀해 주세요."
                    !Launch.zoom(context, url) -> "줌을 열지 못했습니다."
                    else -> ""
                }
            },
        )
        if (notice.isNotBlank()) { Spacer(Modifier.height(10.dp)); Message(notice, tone = Green) }

        Spacer(Modifier.height(20.dp))
        Text("묵상 · 기도", style = MaterialTheme.typography.titleMedium, color = Muted)
        Spacer(Modifier.height(10.dp))
        ToolRow(
            ToolItem(Icons.Outlined.VolunteerActivism, "기도제목", "함께 기도하기") { onNavigate(Route.PRAYER) },
            ToolItem(Icons.Outlined.CalendarMonth, "묵상 출석표", "1년 잔디 보기") { onNavigate(Route.ATTENDANCE) },
        )
        Spacer(Modifier.height(10.dp))
        ToolRow(
            ToolItem(Icons.Outlined.Search, "묵상 찾기", "말씀으로 찾기") { onNavigate(Route.SEARCH) },
            ToolItem(Icons.AutoMirrored.Outlined.MenuBook, "묵상 본문", "오늘의 말씀") { Launch.web(context, bibleUrl) },
        )
        if (me?.role == Roles.LEADER || me?.canManage == true) {
            Spacer(Modifier.height(10.dp))
            ToolRow(
                ToolItem(Icons.Outlined.Groups, "우리 구역 현황", "오늘 누가 나눴는지") { onNavigate(Route.CELL_STATUS) },
                null,
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

private class ToolItem(val icon: ImageVector, val title: String, val sub: String, val onClick: () -> Unit)

@Composable
private fun ToolRow(a: ToolItem, b: ToolItem?) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ToolCard(a, Modifier.weight(1f))
        if (b != null) ToolCard(b, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ToolCard(t: ToolItem, modifier: Modifier) {
    Surface(
        color = Surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Line),
        onClick = t.onClick, modifier = modifier.heightIn(min = 120.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            IconBubble(t.icon, size = 44)
            Spacer(Modifier.height(12.dp))
            Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(t.sub, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1)
        }
    }
}
