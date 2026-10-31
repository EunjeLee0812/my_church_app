package kr.woorichurch.app.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kr.woorichurch.app.data.ExportData
import kr.woorichurch.app.ui.AppState
import kr.woorichurch.app.ui.common.*
import kr.woorichurch.app.ui.theme.Muted
import kr.woorichurch.app.ui.theme.Green
import kr.woorichurch.app.util.D
import java.io.File

/** 묵상 기록을 글 파일로 만들어 저장하거나 다른 앱으로 보냅니다. */
@Composable
fun ExportScreen(state: AppState, onBack: () -> Unit) {
    val repo = state.repo
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val me = state.me
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var done by remember { mutableStateOf("") }

    fun run(which: String) {
        busy = true; error = ""; done = ""
        scope.launch {
            runCatching { repo.exportEntries(which) }
                .onSuccess { d ->
                    val text = buildText(d)
                    val name = "묵상기록-${if (which == "church") d.churchName else d.name}-${D.today()}.txt"
                    val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
                    val f = File(dir, name)
                    f.writeText(text)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", f)
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, name)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "묵상 기록 저장 / 보내기",
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    done = "${d.entries.size}편을 파일로 만들었습니다."
                }
                .onFailure { error = it.message ?: "내려받지 못했습니다" }
            busy = false
        }
    }

    Screen("묵상 기록 내려받기", onBack = onBack) {
        Spacer(Modifier.height(12.dp))
        Card {
            Text(
                "묵상 기록을 글 파일(.txt)로 만들어 드립니다.\n" +
                    "만든 뒤에는 카카오톡으로 보내거나 파일 앱에 저장할 수 있습니다.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(Modifier.height(20.dp))
        BigButton(if (busy) "만드는 중…" else "내 묵상 기록 전체", enabled = !busy) { run("mine") }
        if (me?.canManage == true) {
            Spacer(Modifier.height(10.dp))
            BigButton(
                "교회 전체 묵상 기록", tone = ButtonTone.Plain, enabled = !busy,
                subtitle = "함께 나눈 글만 모읍니다",
            ) { run("church") }
        }
        Spacer(Modifier.height(20.dp))
        Message(error)
        Message(done, tone = Green)
        Spacer(Modifier.height(12.dp))
        Text(
            "'나만 보기'로 적은 글은 본인 기록에만 들어갑니다.",
            style = MaterialTheme.typography.bodySmall, color = Muted,
        )
        Spacer(Modifier.height(32.dp))
    }
}

private fun buildText(d: ExportData): String = buildString {
    appendLine(if (d.scope == "church") "${d.churchName} 묵상 기록" else "${d.name} 묵상 기록")
    appendLine("내려받은 날: ${D.koreanFull(D.today())}")
    appendLine("모두 ${d.entries.size}편")
    appendLine(); appendLine("─".repeat(30)); appendLine()
    d.entries.forEach { e ->
        append(D.koreanFull(e.date))
        if (!e.memberName.isNullOrBlank()) append(" · ${e.memberName} ${e.memberTitle.orEmpty()}")
        appendLine()
        if (!e.title.isNullOrBlank()) appendLine("[${e.title}]")
        appendLine(e.body)
        appendLine(); appendLine("─".repeat(30)); appendLine()
    }
}
