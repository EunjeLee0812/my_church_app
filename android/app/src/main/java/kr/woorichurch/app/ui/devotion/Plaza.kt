package kr.woorichurch.app.ui.devotion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kr.woorichurch.app.data.Comment
import kr.woorichurch.app.data.Entry
import kr.woorichurch.app.ui.AppState
import kr.woorichurch.app.ui.common.*
import kr.woorichurch.app.ui.theme.*
import kr.woorichurch.app.util.D
import kr.woorichurch.app.util.Launch
import java.time.LocalDate

// ══════════════════════════════════════════════════════════════════════════
//  나눔 글 한 칸 — 좋아요 · 댓글 · 공유
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun EntryCard(
    state: AppState,
    entry: Entry,
    churchName: String,
    showAuthor: Boolean = true,
    onPerson: ((Long) -> Unit)? = null,
    onDeleted: (() -> Unit)? = null,
    /** 내 글이면 '고치기' 를 누를 때 그 날짜로 */
    onEdit: ((String) -> Unit)? = null,
) {
    val repo = state.repo
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var liked by remember(entry.id) { mutableStateOf(entry.liked) }
    var likeCount by remember(entry.id) { mutableStateOf(entry.likeCount) }
    var commentCount by remember(entry.id) { mutableStateOf(entry.commentCount) }
    var openComments by remember(entry.id) { mutableStateOf(false) }
    var comments by remember(entry.id) { mutableStateOf<List<Comment>>(emptyList()) }
    var myId by remember(entry.id) { mutableStateOf(0L) }
    var newComment by remember(entry.id) { mutableStateOf("") }
    var confirmDelete by remember(entry.id) { mutableStateOf(false) }
    val mine = state.me?.id == entry.memberId

    Card {
        if (showAuthor) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${entry.memberName} ${entry.memberTitle}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false).then(
                        if (onPerson != null) Modifier.pressBounce().clickable { onPerson(entry.memberId) } else Modifier),
                )
                Spacer(Modifier.width(8.dp))
                entry.cellName?.let { Pill(it) }
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(D.korean(entry.date), style = MaterialTheme.typography.bodySmall, color = Muted)
            if (!entry.isPublic) { Spacer(Modifier.width(8.dp)); Pill("나만 보기", Muted) }
        }

        if (!entry.title.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(entry.title, style = MaterialTheme.typography.titleLarge)
        }
        if (entry.body.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(entry.body, style = MaterialTheme.typography.bodyLarge)
        }

        if (entry.images.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            PhotoStrip(entry.images.map { repo.imageUrl(it.url) })
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = Line)
        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                scope.launch {
                    runCatching { repo.toggleLike(entry.id) }.onSuccess {
                        liked = it.liked; likeCount = it.count
                    }
                }
            }) {
                Icon(
                    if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    "좋아요", tint = if (liked) Green else Muted, modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("$likeCount", style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            TextButton(onClick = {
                openComments = !openComments
                if (openComments) scope.launch {
                    runCatching { repo.comments(entry.id) }.onSuccess {
                        comments = it.comments; myId = it.myId; commentCount = it.comments.size
                    }
                }
            }) {
                Text("댓글 $commentCount", style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                val text = buildString {
                    append(churchName).append(" 묵상 나눔\n")
                    append(D.koreanFull(entry.date)).append(" · ").append(entry.memberName).append("\n\n")
                    if (!entry.title.isNullOrBlank()) append(entry.title).append("\n\n")
                    append(entry.body)
                }
                // 내가 쓴 공개 글은 '링크'로 공유 — 교회 밖 사람도 사진까지 그 글 하나만 볼 수 있습니다.
                // 다른 분의 글이나 '나만 보기' 글은 예전처럼 글자만 보냅니다.
                if (mine && entry.isPublic) scope.launch {
                    runCatching { repo.shareLink(entry.id) }
                        .onSuccess { url ->
                            val head = entry.title?.takeIf { it.isNotBlank() } ?: "${entry.memberName}님의 묵상"
                            Launch.shareText(context, "$churchName 묵상 나눔 · $head\n$url")
                        }
                        .onFailure { Launch.shareText(context, text) }
                } else Launch.shareText(context, text)
            }) {
                Icon(Icons.Filled.Share, "공유하기", tint = Muted, modifier = Modifier.size(22.dp))
            }
        }

        if (openComments) {
            Spacer(Modifier.height(4.dp))
            comments.forEach { c ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${c.memberName} ${c.memberTitle}",
                            style = MaterialTheme.typography.labelMedium, color = Green,
                        )
                        Text(c.body, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (c.memberId == myId || state.me?.canManage == true) {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { repo.deleteComment(c.id) }.onSuccess {
                                    comments = comments.filterNot { x -> x.id == c.id }
                                    commentCount = comments.size
                                }
                            }
                        }) { Text("지움", style = MaterialTheme.typography.labelSmall, color = Muted) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(newComment, { newComment = it }, "댓글 달기", modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    val t = newComment.trim()
                    if (t.isBlank()) return@TextButton
                    scope.launch {
                        runCatching { repo.addComment(entry.id, t) }.onSuccess {
                            newComment = ""
                            runCatching { repo.comments(entry.id) }.onSuccess { r ->
                                comments = r.comments; myId = r.myId; commentCount = r.comments.size
                            }
                        }
                    }
                }) { Text("남기기", style = MaterialTheme.typography.labelMedium, color = Green) }
            }
        }

        if (mine && (onDeleted != null || onEdit != null)) {
            Spacer(Modifier.height(6.dp))
            Row {
                if (onEdit != null) {
                    TextButton(onClick = { onEdit(entry.date) }) {
                        Text("이 글 고치기", style = MaterialTheme.typography.labelMedium, color = Green)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (onDeleted != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("이 글 지우기", style = MaterialTheme.typography.labelSmall, color = Danger)
                    }
                }
            }
        }
    }

    Confirm(
        show = confirmDelete,
        title = "이 글을 지울까요?",
        message = "지우면 출석표의 초록 점도 함께 사라집니다.",
        confirmText = "지웁니다",
        onConfirm = {
            confirmDelete = false
            scope.launch { runCatching { repo.deleteEntry(entry.id) }.onSuccess { onDeleted?.invoke() } }
        },
        onDismiss = { confirmDelete = false },
    )
}

// ══════════════════════════════════════════════════════════════════════════
//  오늘의 나눔
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun FeedScreen(
    state: AppState,
    onBack: (() -> Unit)?,
    onHome: (() -> Unit)?,
    onPerson: (Long) -> Unit,
    onAttendance: () -> Unit,
    onSearch: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val repo = state.repo
    // 모든 셀에 속한 분은 '우리 구역'이 곧 '전체'라 나누지 않습니다
    val merged = state.inAllCells
    // cell = 우리 구역 나눔 / all = 전체 나눔 / mine = 나의 나눔 모아보기
    var tab by rememberSaveable { mutableStateOf(if (merged) "all" else "cell") }
    var date by remember { mutableStateOf(D.today()) }
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var myTotal by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(tab, date, reload) {
        loading = true; error = ""
        runCatching {
            if (tab == "mine") {
                val r = repo.personEntries(state.me?.id ?: 0)
                myTotal = r.total
                r.entries
            } else repo.feed(date, tab)
        }
            .onSuccess { entries = it }
            .onFailure { error = it.message ?: "불러오지 못했습니다" }
        loading = false
        // 이 화면을 다 띄운 뒤, 다음에 누를 만한 것(출석표·나의 나눔·다른 구역·어제)을 미리 받아 둡니다
        state.prefetchAround("feed")
    }

    Screen(if (tab == "mine") "나의 나눔" else "오늘의 나눔", onBack = onBack, onHome = onHome) {
        Spacer(Modifier.height(8.dp))
        Segments(
            buildList {
                if (!merged) add("cell" to (state.me?.cellName?.let { "$it" } ?: "우리 구역"))
                add("all" to "전체 나눔")
                add("mine" to "나의 나눔")
            },
            tab,
        ) { tab = it }
        Spacer(Modifier.height(12.dp))

        if (tab != "mine") {
            ArrowNav(
                label = D.korean(date),
                onPrev = { date = LocalDate.parse(date).minusDays(1).toString() },
                onNext = {
                    val n = LocalDate.parse(date).plusDays(1)
                    if (!n.isAfter(LocalDate.now())) date = n.toString()
                },
                nextEnabled = LocalDate.parse(date).isBefore(LocalDate.now()),
                prevLabel = "앞날", nextLabel = "다음날",
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("묵상 출석표 보기", Modifier.weight(1f), tone = ButtonTone.Soft) { onAttendance() }
                BigButton("묵상 찾기", Modifier.weight(1f), tone = ButtonTone.Soft) { onSearch() }
            }
        } else if (!loading) {
            Card {
                Text("지금까지 나눈 묵상", style = MaterialTheme.typography.bodySmall, color = Muted)
                Text("${myTotal}회", style = MaterialTheme.typography.headlineMedium, color = Green)
            }
        }

        Spacer(Modifier.height(20.dp))
        Message(error)
        if (loading) Loading()
        else if (entries.isEmpty()) Empty(if (tab == "mine") "아직 나눈 묵상이 없습니다." else "아직 이 날 나눈 묵상이 없습니다.")
        else entries.forEach { e ->
            EntryCard(
                state = state, entry = e,
                churchName = state.me?.churchName.orEmpty(),
                showAuthor = tab != "mine",
                onPerson = onPerson,
                onDeleted = { reload++ },
                onEdit = onEdit,
            )
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}
