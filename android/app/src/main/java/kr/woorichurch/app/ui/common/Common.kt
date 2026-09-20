package kr.woorichurch.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kr.woorichurch.app.ui.theme.*

/** 모든 화면의 기본 틀 — 위쪽에 제목, 뒤로가기, 홈으로 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(
    title: String,
    onBack: (() -> Unit)? = null,
    onHome: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로가기", modifier = Modifier.size(30.dp))
                    }
                },
                actions = {
                    actions()
                    if (onHome != null) IconButton(onClick = onHome, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.Home, "홈으로", modifier = Modifier.size(30.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = Ink),
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 32.dp),
            content = content,
        )
    }
}

/** 시니어용 큰 버튼 */
@Composable
fun BigButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: ButtonTone = ButtonTone.Primary,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val colors = when (tone) {
        ButtonTone.Primary -> ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.White)
        ButtonTone.Soft -> ButtonDefaults.buttonColors(containerColor = GreenLight, contentColor = Green)
        ButtonTone.Plain -> ButtonDefaults.buttonColors(containerColor = Surface, contentColor = Ink)
        ButtonTone.Danger -> ButtonDefaults.buttonColors(containerColor = Surface, contentColor = Danger)
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        shape = RoundedCornerShape(14.dp),
        border = if (tone == ButtonTone.Plain || tone == ButtonTone.Danger) BorderStroke(1.5.dp, Line) else null,
        contentPadding = PaddingValues(vertical = 18.dp, horizontal = 16.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 68.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
    }
}

enum class ButtonTone { Primary, Soft, Plain, Danger }

@Composable
fun Card(padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = Surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(padding), content = content) }
}

@Composable
fun SectionTitle(text: String, top: Dp = 24.dp) {
    Spacer(Modifier.height(top))
    Text(text, style = MaterialTheme.typography.titleMedium, color = Muted)
    Spacer(Modifier.height(8.dp))
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    numeric: Boolean = false,
    password: Boolean = false,
    minLines: Int = 1,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        singleLine = singleLine,
        minLines = minLines,
        textStyle = MaterialTheme.typography.bodyLarge,
        supportingText = supporting?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Text
        ),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Green, unfocusedBorderColor = Line,
            focusedContainerColor = Surface, unfocusedContainerColor = Surface,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun Loading(text: String = "불러오는 중입니다") {
    Column(
        Modifier.fillMaxWidth().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Green)
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

@Composable
fun Message(text: String, tone: Color = Danger) {
    if (text.isBlank()) return
    Surface(
        color = tone.copy(alpha = 0.08f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tone, modifier = Modifier.padding(14.dp))
    }
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Muted, textAlign = TextAlign.Center)
    }
}

/** 예/아니오 확인 창 */
@Composable
fun Confirm(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String = "예",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(message, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, style = MaterialTheme.typography.labelLarge, color = Danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("아니오", style = MaterialTheme.typography.labelLarge) }
        },
        containerColor = Surface,
    )
}

/** 두 칸짜리 선택 막대 (우리 셀 / 전체) */
@Composable
fun Toggle2(
    left: String, right: String, rightSelected: Boolean, onSelect: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(GreenLight, RoundedCornerShape(12.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(false to left, true to right).forEach { (isRight, label) ->
            val on = rightSelected == isRight
            Surface(
                color = if (on) Green else Color.Transparent,
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.weight(1f),
                onClick = { onSelect(isRight) },
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) Color.White else Green,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun Pill(text: String, color: Color = Green) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(20.dp)) {
        Text(
            text, style = MaterialTheme.typography.labelSmall, color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

typealias Dp = androidx.compose.ui.unit.Dp
