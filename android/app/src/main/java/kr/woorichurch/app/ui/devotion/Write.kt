package kr.woorichurch.app.ui.devotion

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import kr.woorichurch.app.ui.common.Button
import kr.woorichurch.app.ui.common.IconButton
import kr.woorichurch.app.ui.common.OutlinedButton
import kr.woorichurch.app.ui.common.Surface
import kr.woorichurch.app.ui.common.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.woorichurch.app.data.Cell
import kr.woorichurch.app.data.EntryImage
import kr.woorichurch.app.ui.AppState
import kr.woorichurch.app.ui.common.*
import kr.woorichurch.app.ui.theme.*
import kr.woorichurch.app.util.D
import kr.woorichurch.app.util.ImageShrink
import java.time.LocalDate

/** 한 편에 붙일 수 있는 사진 수 (서버와 같게) */
private const val MAX_PHOTOS = 4

/**
 * 묵상 나눔하기 / 고치기
 * [editDate] 가 있으면 그 날 쓴 글을 불러와 고치는 화면이 됩니다.
 */
@Composable
fun WriteScreen(state: AppState, editDate: String? = null, onBack: () -> Unit, onSaved: () -> Unit) {
    val repo = state.repo
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val me = state.me
    val isEdit = editDate != null

    var date by remember { mutableStateOf(editDate ?: D.today()) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(true) }   // 기본은 공개
    var cells by remember { mutableStateOf<List<Cell>>(emptyList()) }
    var picked by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var photos by remember { mutableStateOf<List<EntryImage>>(emptyList()) }
    var uploading by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var draftNotice by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var existing by remember { mutableStateOf(false) }

    val canPick = me?.canPickCells == true

    LaunchedEffect(Unit) {
        if (canPick) cells = runCatching { repo.cells() }.getOrDefault(emptyList())
    }

    // 그 날짜에 이미 쓴 글이 있으면 불러와서 고칠 수 있게
    LaunchedEffect(date) {
        loaded = false
        val found = runCatching { repo.myEntries(date, date) }.getOrNull()?.firstOrNull()
        existing = found != null
        if (found != null) {
            title = found.title.orEmpty(); body = found.body; isPublic = found.isPublic
            photos = found.images
            picked = found.cellIds.toSet().ifEmpty { me?.cellIds?.toSet() ?: emptySet() }
            draftNotice = if (isEdit) "" else "이 날짜에 쓰신 글을 불러왔습니다. 고치면 그대로 바뀝니다."
        } else {
            photos = emptyList()
            picked = me?.cellId?.let { setOf(it) } ?: emptySet()
            val draft = repo.prefs.loadDraft(date)
            if (draft != null) {
                title = draft.first; body = draft.second
                draftNotice = "쓰시다 만 글이 있어 그대로 불러왔습니다."
            } else {
                title = ""; body = ""; draftNotice = ""
            }
        }
        loaded = true
    }

    // 자동 임시저장 — 손을 멈추면 1.5초 뒤 저장 (이미 올린 글을 고칠 때는 하지 않음)
    LaunchedEffect(title, body, date, loaded) {
        if (!loaded || existing) return@LaunchedEffect
        delay(1500)
        if (body.isNotBlank()) repo.prefs.saveDraft(date, title, body)
    }

    // 갤러리에서 사진 고르기 → 줄여서 → 바로 올려 둡니다
    val left = (MAX_PHOTOS - photos.size).coerceAtLeast(1)
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = maxOf(2, left)),
    ) { uris ->
        val take = uris.take(MAX_PHOTOS - photos.size)
        if (uris.size > take.size) error = "사진은 ${MAX_PHOTOS}장까지 넣을 수 있습니다"
        take.forEach { uri ->
            uploading++
            scope.launch {
                val prepared = withContext(Dispatchers.IO) {
                    runCatching { ImageShrink.forPost(context, uri) }.getOrNull()
                }
                if (prepared == null) {
                    error = "사진을 읽지 못했습니다"
                } else {
                    runCatching { repo.uploadEntryImage(prepared, "image/jpeg") }
                        .onSuccess { r ->
                            if (photos.size < MAX_PHOTOS) photos = photos + EntryImage(r.id, r.url)
                        }
                        .onFailure { error = it.message ?: "사진을 올리지 못했습니다" }
                }
                uploading--
            }
        }
    }

    Screen(if (isEdit) "묵상 고치기" else "묵상 나눔하기", onBack = onBack) {
        if (me == null) { Loading(); return@Screen }

        // ── 날짜 ─────────────────────────────────────────────────────────
        Spacer(Modifier.height(8.dp))
        Card {
            Text("날짜", style = MaterialTheme.typography.bodySmall, color = Muted)
            Spacer(Modifier.height(6.dp))
            Text(D.koreanFull(date), style = MaterialTheme.typography.titleLarge)
            if (!isEdit) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { date = LocalDate.parse(date).minusDays(1).toString() },
                        modifier = Modifier.weight(1f),
                    ) { Text("하루 앞", style = MaterialTheme.typography.labelMedium) }
                    OutlinedButton(
                        onClick = { date = D.today() },
                        modifier = Modifier.weight(1f),
                    ) { Text("오늘", style = MaterialTheme.typography.labelMedium) }
                    OutlinedButton(
                        onClick = {
                            val next = LocalDate.parse(date).plusDays(1)
                            if (!next.isAfter(LocalDate.now())) date = next.toString()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("하루 뒤", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }

        if (draftNotice.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Message(draftNotice, tone = Green)
        }

        // ── 내용 ─────────────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        Field(title, { title = it }, "제목 (없어도 됩니다)")
        Spacer(Modifier.height(12.dp))
        Field(
            body, { body = it }, "오늘 묵상한 내용", singleLine = false, minLines = 8,
            supporting = "사진만 올려도 됩니다",
        )

        // ── 사진 ─────────────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("사진", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${photos.size} / $MAX_PHOTOS", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            if (photos.isNotEmpty() || uploading > 0) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    photos.forEach { p ->
                        Box {
                            AsyncImage(
                                model = repo.imageUrl(p.url), contentDescription = "넣은 사진",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(110.dp).clip(RoundedCornerShape(10.dp)),
                            )
                            Surface(
                                color = Color.Black.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(bottomStart = 10.dp, topEnd = 10.dp),
                                onClick = { photos = photos.filterNot { it.id == p.id } },
                                modifier = Modifier.align(Alignment.TopEnd),
                            ) {
                                Text(
                                    "빼기", color = Color.White, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    repeat(uploading) {
                        Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Green, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            BigButton(
                "갤러리에서 사진 넣기", tone = ButtonTone.Soft,
                enabled = photos.size + uploading < MAX_PHOTOS,
                subtitle = "자동으로 줄여서 올립니다",
            ) {
                error = ""
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }

        // ── 공개 여부 ─────────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (isPublic) "함께 나누기" else "나만 보기", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (isPublic) "나눔광장에 올라갑니다" else "출석표에는 표시되지만 글은 나만 봅니다",
                        style = MaterialTheme.typography.bodySmall, color = Muted,
                    )
                }
                Switch(
                    checked = isPublic, onCheckedChange = { isPublic = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = Green),
                )
            }
        }

        // ── 나눌 셀 고르기 (나눔 범위가 열린 사람만) ─────────────────────────
        if (canPick && cells.isNotEmpty()) {
            SectionTitle("어느 셀에 나눌까요")
            CellChips(cells, picked, { picked = it })
            Spacer(Modifier.height(4.dp))
            Text(
                if (picked.isEmpty()) "고르지 않으면 내가 속한 셀에 나눠집니다"
                else if (picked.size == cells.size) "모든 셀에 나눠집니다"
                else "${picked.size}개 셀에 나눠집니다",
                style = MaterialTheme.typography.bodySmall, color = Muted,
            )
        } else if (me.cellName != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                "${me.cellName} 안에서 나눠집니다.",
                style = MaterialTheme.typography.bodyMedium, color = Muted,
            )
        }

        Spacer(Modifier.height(20.dp))
        Message(error)
        Spacer(Modifier.height(12.dp))

        BigButton(
            when {
                busy -> "올리는 중…"
                uploading > 0 -> "사진 올리는 중…"
                isEdit || existing -> "고친 내용 저장"
                else -> "올리기"
            },
            enabled = !busy && uploading == 0,
        ) {
            error = ""
            // 사진을 넣었으면 글은 없어도 됩니다
            if (body.isBlank() && photos.isEmpty()) { error = "묵상한 내용을 적거나 사진을 넣어 주세요"; return@BigButton }
            busy = true
            scope.launch {
                val all = canPick && cells.isNotEmpty() && picked.size == cells.size
                runCatching {
                    repo.saveEntry(
                        date = date, title = title.trim(), body = body.trim(), isPublic = isPublic,
                        cellIds = if (canPick && !all && picked.isNotEmpty()) picked.toList() else null,
                        allCells = all,
                        imageIds = photos.map { it.id },
                    )
                }.onSuccess {
                    repo.prefs.clearDraft()
                    onSaved()
                }.onFailure { error = it.message ?: "올리지 못했습니다" }
                busy = false
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
