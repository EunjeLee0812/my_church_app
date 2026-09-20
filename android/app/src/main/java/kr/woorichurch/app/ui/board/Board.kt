package kr.woorichurch.app.ui.board

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.woorichurch.app.data.Bulletin
import kr.woorichurch.app.data.Notice
import kr.woorichurch.app.ui.AppState
import kr.woorichurch.app.ui.common.*
import kr.woorichurch.app.ui.theme.*
import kr.woorichurch.app.util.D
import kr.woorichurch.app.util.ImageShrink
import kr.woorichurch.app.util.Launch

// ══════════════════════════════════════════════════════════════════════════
//  공지사항
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun NoticeScreen(state: AppState, onBack: () -> Unit, onHome: () -> Unit) {
    val repo = state.repo
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Notice>>(emptyList()) }
    var canEdit by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var writing by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<Long?>(null) }
    var title by remember { mutableStateOf("") }
    var bodyText by remember { mutableStateOf("") }
    var pinned by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(reload) {
        loading = true; error = ""
        runCatching { repo.notices() }
            .onSuccess { list = it.notices; canEdit = it.canEdit }
            .onFailure { error = it.message ?: "불러오지 못했습니다" }
        loading = false
        // 화면에 들어온 것만으로 읽음 처리
        list.filter { it.unread }.forEach { runCatching { repo.readNotice(it.id) } }
    }

    Screen("공지사항", onBack = onBack, onHome = onHome) {
        Spacer(Modifier.height(8.dp))
        if (canEdit) {
            if (!writing) {
                BigButton("공지 올리기") { writing = true; editId = null; title = ""; bodyText = ""; pinned = false }
            } else {
                Card {
                    Text(if (editId == null) "새 공지" else "공지 고치기", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    Field(title, { title = it }, "제목")
                    Spacer(Modifier.height(10.dp))
                    Field(bodyText, { bodyText = it }, "내용", singleLine = false, minLines = 5)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(pinned, { pinned = it }, colors = CheckboxDefaults.colors(checkedColor = Green))
                        Text("맨 위에 고정", style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigButton("올리기", Modifier.weight(1f)) {
                            if (bodyText.isBlank()) { error = "내용을 적어 주세요"; return@BigButton }
                            scope.launch {
                                runCatching { repo.saveNotice(editId, title.trim(), bodyText.trim(), pinned) }
                                    .onSuccess { writing = false; reload++ }
                                    .onFailure { error = it.message ?: "올리지 못했습니다" }
                            }
                        }
                        BigButton("그만두기", Modifier.weight(1f), tone = ButtonTone.Plain) { writing = false }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Message(error)
        if (loading) Loading()
        else if (list.isEmpty()) Empty("아직 올라온 공지가 없습니다.")
        else list.forEach { n ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (n.pinned == 1) { Pill("고정"); Spacer(Modifier.width(8.dp)) }
                    if (n.unread) { Pill("새 글", Danger); Spacer(Modifier.width(8.dp)) }
                    Spacer(Modifier.weight(1f))
                    Text(D.shortTime(n.createdAt), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                if (!n.title.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(n.title, style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(8.dp))
                Text(n.body, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${n.authorName.orEmpty()} · ${n.readCount}명 읽음",
                        style = MaterialTheme.typography.bodySmall, color = Muted,
                    )
                    Spacer(Modifier.weight(1f))
                    if (canEdit) {
                        TextButton(onClick = {
                            writing = true; editId = n.id
                            title = n.title.orEmpty(); bodyText = n.body; pinned = n.pinned == 1
                        }) { Text("고치기", style = MaterialTheme.typography.labelSmall) }
                        TextButton(onClick = { confirmDelete = n.id }) {
                            Text("지움", style = MaterialTheme.typography.labelSmall, color = Danger)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(24.dp))
    }

    Confirm(
        show = confirmDelete != null,
        title = "공지를 지울까요?", message = "지우면 되돌릴 수 없습니다.", confirmText = "지웁니다",
        onConfirm = {
            val id = confirmDelete; confirmDelete = null
            if (id != null) scope.launch { runCatching { repo.deleteNotice(id) }.onSuccess { reload++ } }
        },
        onDismiss = { confirmDelete = null },
    )
}

// ══════════════════════════════════════════════════════════════════════════
//  주보 — 올리기(자동 축소) · 보기 · 지난 주보 보관함
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun BulletinScreen(state: AppState, onBack: () -> Unit, onHome: () -> Unit) {
    val repo = state.repo
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var list by remember { mutableStateOf<List<Bulletin>>(emptyList()) }
    var canEdit by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var uploading by remember { mutableStateOf(false) }
    var openId by remember { mutableStateOf<Long?>(null) }
    var openBytes by remember { mutableStateOf<ByteArray?>(null) }
    var confirmDelete by remember { mutableStateOf<Long?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        if (mime !in listOf("image/png", "image/jpeg", "image/webp", "application/pdf")) {
            error = "png, jpg, webp, pdf 파일만 올릴 수 있습니다"
            return@rememberLauncherForActivityResult
        }
        uploading = true; error = ""; notice = ""
        scope.launch {
            val prepared = withContext(Dispatchers.IO) { ImageShrink.shrink(context, uri, mime) }
            if (prepared == null) { error = "파일을 읽지 못했습니다"; uploading = false; return@launch }
            val (bytes, finalMime) = prepared
            if (bytes.size > 10 * 1024 * 1024) {
                error = "파일이 너무 큽니다 (${ImageShrink.humanSize(bytes.size.toLong())}). 10MB 이하로 줄여 주세요."
                uploading = false; return@launch
            }
            runCatching {
                repo.uploadBulletin(bytes, finalMime, D.thisSunday() + " 주보", D.thisSunday())
            }.onSuccess {
                notice = "주보를 올렸습니다. (${ImageShrink.humanSize(bytes.size.toLong())})"
                reload++
            }.onFailure { error = it.message ?: "올리지 못했습니다" }
            uploading = false
        }
    }

    LaunchedEffect(reload) {
        loading = true; error = ""
        runCatching { repo.bulletins() }
            .onSuccess { list = it.bulletins; canEdit = it.canEdit }
            .onFailure { error = it.message ?: "불러오지 못했습니다" }
        loading = false
    }

    LaunchedEffect(openId) {
        val id = openId ?: return@LaunchedEffect
        openBytes = null
        runCatching { repo.bulletinBytes(id) }
            .onSuccess { openBytes = it }
            .onFailure { error = it.message ?: "주보를 불러오지 못했습니다"; openId = null }
    }

    Screen("주보", onBack = onBack, onHome = onHome) {
        Spacer(Modifier.height(8.dp))
        if (canEdit) {
            BigButton(if (uploading) "올리는 중…" else "주보 올리기", enabled = !uploading,
                subtitle = "사진은 자동으로 줄여서 올립니다 (최대 10MB)") {
                picker.launch("*/*")
            }
            Spacer(Modifier.height(16.dp))
        }
        Message(error)
        Message(notice, tone = Green)

        if (loading) { Loading(); return@Screen }
        if (list.isEmpty()) { Empty("아직 올라온 주보가 없습니다."); return@Screen }

        val latest = list.first()
        SectionTitle("이번 주 주보", top = 8.dp)
        BulletinRow(latest, canEdit, onOpen = { openId = latest.id }, onDelete = { confirmDelete = latest.id })

        if (list.size > 1) {
            SectionTitle("지난 주보 보관함")
            list.drop(1).forEach { b ->
                BulletinRow(b, canEdit, onOpen = { openId = b.id }, onDelete = { confirmDelete = b.id })
                Spacer(Modifier.height(10.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    // 주보 보기
    val id = openId
    if (id != null) {
        val b = list.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { openId = null; openBytes = null },
            confirmButton = {
                TextButton(onClick = {
                    val bytes = openBytes
                    if (bytes != null && b != null) {
                        val ext = if (b.isPdf) "pdf" else "jpg"
                        Launch.openFile(context, bytes, "주보-${b.weekOf}.$ext", b.mime)
                    }
                }) { Text("다른 앱으로 열기", style = MaterialTheme.typography.labelMedium) }
            },
            dismissButton = {
                TextButton(onClick = { openId = null; openBytes = null }) { Text("닫기") }
            },
            title = { Text(b?.weekOf?.let { D.koreanFull(it) } ?: "주보", style = MaterialTheme.typography.titleLarge) },
            text = {
                val bytes = openBytes
                when {
                    bytes == null -> Loading("주보를 여는 중입니다")
                    b?.isPdf == true -> Text(
                        "PDF 주보입니다. 아래 ‘다른 앱으로 열기’를 눌러 주세요.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    else -> {
                        val bmp = remember(bytes) {
                            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                        }
                        if (bmp != null) Image(
                            bmp.asImageBitmap(), "주보",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth(),
                        ) else Text("그림을 열지 못했습니다", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            },
            containerColor = Surface,
        )
    }

    Confirm(
        show = confirmDelete != null,
        title = "주보를 지울까요?", message = "파일도 함께 지워집니다.", confirmText = "지웁니다",
        onConfirm = {
            val d = confirmDelete; confirmDelete = null
            if (d != null) scope.launch { runCatching { repo.deleteBulletin(d) }.onSuccess { reload++ } }
        },
        onDismiss = { confirmDelete = null },
    )
}

@Composable
private fun BulletinRow(b: Bulletin, canEdit: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(D.koreanFull(b.weekOf), style = MaterialTheme.typography.titleMedium)
                Text(
                    "${if (b.isPdf) "PDF" else "사진"} · ${ImageShrink.humanSize(b.size)}" +
                        (b.uploader?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                )
            }
            if (canEdit) TextButton(onClick = onDelete) {
                Text("지움", style = MaterialTheme.typography.labelSmall, color = Danger)
            }
        }
        Spacer(Modifier.height(10.dp))
        BigButton("주보 보기", tone = ButtonTone.Soft) { onOpen() }
    }
}
