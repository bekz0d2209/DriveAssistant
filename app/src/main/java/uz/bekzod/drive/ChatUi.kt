package uz.bekzod.drive

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BubbleMe = Color(0xFF1A73E8)
private val BubbleAi = Color(0xFF26282C)
private val CardBg = Color(0xFF1B1C1F)

private val SUGGESTIONS = listOf(
    "Что лежит в корне диска?",
    "Создай папку «Счета поставщиков»",
    "Сделай таблицу прихода вагонов",
    "Найди договор",
)

@Composable
fun AppRoot(vm: DriveViewModel, onMic: () -> Unit, onShare: (ShareReq) -> Unit) {
    LaunchedEffect(vm.share) {
        vm.share?.let {
            vm.share = null
            onShare(it)
        }
    }

    when (vm.screen) {
        Screen.CHAT -> ChatScreen(vm, onMic)
        Screen.FILES -> FilesScreen(vm)
    }

    vm.confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { c.result.complete(false) },
            text = { Text(c.text) },
            confirmButton = {
                TextButton(onClick = { c.result.complete(true) }) { Text("Удалить", color = Color(0xFFF28B82)) }
            },
            dismissButton = { TextButton(onClick = { c.result.complete(false) }) { Text("Отмена") } },
        )
    }

    if (vm.showSettings) SettingsDialog(vm)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(vm: DriveViewModel, onMic: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addAttachments(uris)
    }

    LaunchedEffect(vm.messages.size, vm.thinking) {
        val n = vm.messages.size + (if (vm.thinking) 1 else 0)
        if (n > 0) list.animateScrollToItem(n - 1)
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(BubbleMe, Accent))),
                            contentAlignment = Alignment.Center,
                        ) { Text("✦", fontSize = 18.sp) }
                        Spacer(Modifier.size(10.dp))
                        Column {
                            Text("Ассистент", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (vm.thinking) "думает…" else "Google Диск",
                                fontSize = 12.sp,
                                color = Color(0xFF81C995),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.openFiles() }) { Text("📁", fontSize = 22.sp) }
                    IconButton(onClick = { vm.showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки", tint = Muted)
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            LazyColumn(
                state = list,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(vm.messages) { _, m -> Bubble(m) }
                if (vm.thinking) item { Typing() }
            }

            if (vm.attachments.isNotEmpty()) {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(vm.attachments) { a ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF1F2A3A))
                                .padding(start = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("📎 " + a.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                            IconButton(onClick = { vm.removeAttachment(a) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Убрать", tint = Muted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            if (!vm.thinking) {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(SUGGESTIONS) { s ->
                        Text(
                            s,
                            fontSize = 13.sp,
                            color = Color(0xFFD2E3FC),
                            modifier = Modifier
                                .clip(RoundedCornerShape(17.dp))
                                .border(1.dp, Color(0xFF3C4043), RoundedCornerShape(17.dp))
                                .clickable { vm.send(s) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 10.dp, top = 2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(BubbleAi)
                        .heightIn(min = 52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("📎", fontSize = 20.sp) }
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Напишите или скажите…", color = Muted) },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                }
                Spacer(Modifier.size(6.dp))
                val hasText = input.isNotBlank()
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(if (vm.thinking) Color(0xFF3C4043) else Accent)
                        .clickable(enabled = !vm.thinking) {
                            if (hasText) {
                                vm.send(input)
                                input = ""
                            } else onMic()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (hasText) "➤" else "🎤", fontSize = 22.sp, color = Color(0xFF0B1A33))
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMsg) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 310.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp, topEnd = 18.dp,
                        bottomStart = if (m.fromUser) 18.dp else 6.dp,
                        bottomEnd = if (m.fromUser) 6.dp else 18.dp,
                    )
                )
                .background(
                    when {
                        m.error -> Color(0xFF5C2B29)
                        m.fromUser -> BubbleMe
                        else -> BubbleAi
                    }
                )
                .padding(horizontal = 13.dp, vertical = 9.dp)
        ) {
            if (m.voice) Text("🎤 голосовое", fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f))
            m.attachNames.forEach { n ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("📄", fontSize = 22.sp)
                    Spacer(Modifier.size(8.dp))
                    Text(n, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (m.text.isNotEmpty()) Text(m.text, fontSize = 15.sp, lineHeight = 21.sp)
            if (m.files.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardBg)
                        .border(1.dp, Color(0xFF33363B), RoundedCornerShape(12.dp))
                ) {
                    m.files.forEach { f ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { openLink(ctx, f) }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(iconFor(f), fontSize = 20.sp)
                            Spacer(Modifier.size(10.dp))
                            Text(f.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("›", color = Muted, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Typing() {
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(BubbleAi)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) { Text("• • •", color = Muted, fontSize = 16.sp) }
}

@Composable
private fun SettingsDialog(vm: DriveViewModel) {
    var key by remember { mutableStateOf(vm.geminiKey()) }
    var model by remember { mutableStateOf(vm.geminiModel()) }
    var rules by remember { mutableStateOf(vm.rules()) }
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = { vm.showSettings = false },
        title = { Text("Настройки ИИ") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Бесплатный ключ Gemini возьмите на aistudio.google.com → Get API key.",
                    color = Muted, fontSize = 14.sp,
                )
                TextButton(onClick = {
                    try { uri.openUri("https://aistudio.google.com/apikey") } catch (e: Exception) { }
                }) { Text("Открыть aistudio.google.com") }
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("Ключ Gemini API") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text("Модель") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Обычно менять не нужно. Если Gemini пишет, что модель не найдена, укажите другую из списка в AI Studio.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = rules, onValueChange = { rules = it },
                    label = { Text("Мои правила") },
                    placeholder = { Text("Например: файлы СМГС называй «СМГС + номер вагона» и клади в папку СМГС/месяц", fontSize = 13.sp) },
                    minLines = 3, maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { vm.saveSettings(key, model, rules) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { vm.showSettings = false }) { Text("Закрыть") } },
    )
}
