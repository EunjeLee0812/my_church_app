package kr.woorichurch.app.ui.devotion

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kr.woorichurch.app.data.Entry
import kr.woorichurch.app.data.PersonEntries
import kr.woorichurch.app.ui.AppState
import kr.woorichurch.app.ui.common.*
import kr.woorichurch.app.ui.theme.*

// ══════════════════════════════════════════════════════════════════════════
//  한 사람의 묵상기록
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun PersonScreen(
    state: AppState,
    memberId: Long,
    onBack: () -> Unit,
    onHome: () -> Unit,
) {
    val repo = state.repo
    var data by remember { mutableStateOf<PersonEntries?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var month by remember { mutableStateOf("all") }
    var day by remember { mutableStateOf("all") }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(memberId, month, day, reload) {
        loading = true; error = ""
        runCatching { repo.personEntries(memberId, month, day) }
            .onSuccess { data = it }
            .onFailure { error = it.message ?: "불러오지 못했습니다" }
        loading = false
    }

    val person = data?.person
    Screen(person?.let { "${it.name} ${it.title}" } ?: "묵상기록", onBack = onBack, onHome = onHome) {
        Spacer(Modifier.height(8.dp))
        data?.let {
            Card {
                Text("지금까지 나눈 묵상", style = MaterialTheme.typography.bodySmall, color = Muted)
                Text("${it.total}회", style = MaterialTheme.typography.headlineMedium, color = Green)
            }
        }

        SectionTitle("추려 보기")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PickerBox("월", month, listOf("all") + (1..12).map { it.toString() },
                { if (it == "all") "전체" else "${it}월" }, Modifier.weight(1f)) { month = it }
            PickerBox("일", day, listOf("all") + (1..31).map { it.toString() },
                { if (it == "all") "전체" else "${it}일" }, Modifier.weight(1f)) { day = it }
        }

        Spacer(Modifier.height(20.dp))
        Message(error)
        if (loading) Loading()
        else {
            val list = data?.entries.orEmpty()
            if (list.isEmpty()) Empty("해당하는 묵상이 없습니다.")
            else list.forEach { e ->
                EntryCard(
                    state = state, entry = e,
                    churchName = state.me?.churchName.orEmpty(),
                    showAuthor = false,
                    onDeleted = { reload++ },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerBox(
    label: String,
    value: String,
    options: List<String>,
    display: (String) -> String,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = display(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label, style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodyLarge,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Green, unfocusedBorderColor = Line,
                focusedContainerColor = Surface, unfocusedContainerColor = Surface,
            ),
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(display(o), style = MaterialTheme.typography.bodyLarge) },
                    onClick = { onPick(o); open = false },
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  묵상 찾기
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun SearchScreen(
    state: AppState,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onPerson: (Long) -> Unit,
) {
    val repo = state.repo
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }

    LaunchedEffect(query, all) {
        if (query.trim().length < 2) { results = emptyList(); searched = false; return@LaunchedEffect }
        delay(400)
        searching = true; error = ""
        runCatching { repo.search(query.trim(), if (all) "all" else "cell") }
            .onSuccess { results = it; searched = true }
            .onFailure { error = it.message ?: "찾지 못했습니다" }
        searching = false
    }

    Screen("묵상 찾기", onBack = onBack, onHome = onHome) {
        Spacer(Modifier.height(8.dp))
        Field(query, { query = it }, "찾을 말 (두 글자 이상)")
        Spacer(Modifier.height(12.dp))
        Toggle2(
            state.me?.cellName ?: "우리 셀", "전체에서 찾기", all,
        ) { all = it }

        Spacer(Modifier.height(20.dp))
        Message(error)
        if (searching) Loading("찾는 중입니다")
        else if (searched && results.isEmpty()) Empty("찾으시는 묵상이 없습니다.")
        else results.forEach { e ->
            EntryCard(
                state = state, entry = e,
                churchName = state.me?.churchName.orEmpty(),
                onPerson = onPerson,
            )
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}
