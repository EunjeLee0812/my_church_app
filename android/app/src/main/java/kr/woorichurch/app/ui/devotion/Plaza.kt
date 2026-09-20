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
import androidx.compose.runtime.*
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
                    modifier = if (onPerson != null)
                        Modifier.clickable { onPerson(entry.memberId) } else Modifier,
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
        Spacer(Modifier.height(8.dp))
        Text(entry.body, style = MaterialTheme.typography.bodyLarge)

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
                Launch.shareText(context, text)
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

        if (mine && onDeleted != null) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { confirmDelete = true }) {
                Text("이 글 지우기", style = MaterialTheme.typography.labelSmall, color = Danger)
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
    onBack: () -> Unit,
    onHome: () -> Unit,
    onPerson: (Long) -> Unit,
    onAttendance: () -> Unit,
    onSearch: () -> Unit,
) {
    val repo = state.repo
    var all by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(D.today()) }
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(all, date, reload) {
        loading = true; error = ""
        runCatching { repo.feed(date, if (all) "all" else "cell") }
            .onSuccess { entries = it }
            .onFailure { error = it.message ?: "불러오지 못했습니다" }
        loading = false
    }

    Screen("오늘의 나눔", onBack = onBack, onHome = onHome) {
        Spacer(Modifier.height(8.dp))
        Toggle2(
            state.me?.cellName?.let { "$it 나눔" } ?: "우리 셀 나눔",
            "전체 나눔 보기", all,
        ) { all = it }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { date = LocalDate.parse(date).minusDays(1).toString() },
                modifier = Modifier.weight(1f),
            ) { Text("앞날", style = MaterialTheme.typography.labelMedium) }
            Text(
                D.korean(date),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1.4f).align(Alignment.CenterVertically),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            OutlinedButton(
                onClick = {
                    val n = LocalDate.parse(date).plusDays(1)
                    if (!n.isAfter(LocalDate.now())) date = n.toString()
                },
                modifier = Modifier.weight(1f),
            ) { Text("다음날", style = MaterialTheme.typography.labelMedium) }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("묵상 출석표 보기", Modifier.weight(1f), tone = ButtonTone.Soft) { onAttendance() }
            BigButton("묵상 찾기", Modifier.weight(1f), tone = ButtonTone.Soft) { onSearch() }
        }

        Spacer(Modifier.height(20.dp))
        Message(error)
        if (loading) Loading()
        else if (entries.isEmpty()) Empty("아직 이 날 나눈 묵상이 없습니다.")
        else entries.forEach { e ->
            EntryCard(
                state = state, entry = e,
                churchName = state.me?.churchName.orEmpty(),
                onPerson = onPerson,
                onDeleted = { reload++ },
            )
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}
