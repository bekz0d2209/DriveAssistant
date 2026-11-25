package uz.bekzod.drive

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Bg = Color(0xFF121212)
private val Surface = Color(0xFF1E1E1E)
internal val Accent = Color(0xFF8AB4F8)
internal val Muted = Color(0xFF9AA0A6)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF0B1A33),
            background = Bg,
            surface = Surface,
            onBackground = Color.White,
            onSurface = Color.White,
        ),
        content = content,
    )
}

private sealed class DialogState {
    data class Name(val title: String, val initial: String, val onOk: (String) -> Unit) : DialogState()
    data class Confirm(val text: String, val onOk: () -> Unit) : DialogState()
    object Add : DialogState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: DriveViewModel) {
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<DialogState?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.upload(uris)
    }

    LaunchedEffect(vm.message) {
        val m = vm.message
        if (m != null) {
            vm.message = null
            snack.showSnackbar(m)
        }
    }

    BackHandler { if (!vm.back()) vm.screen = Screen.CHAT }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
                title = {
                    Text(vm.current.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = { if (!vm.back()) vm.screen = Screen.CHAT }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                    TextButton(onClick = { vm.screen = Screen.CHAT }) { Text("💬 Чат") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { dialog = DialogState.Add }) {
                Icon(Icons.Default.Add, contentDescription = "Создать")
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            Breadcrumbs(vm)

            vm.pending?.let { p ->
                PendingBar(
                    pending = p,
                    folderName = vm.current.name,
                    onOk = { vm.confirmPending() },
                    onCancel = { vm.pending = null },
                )
            }

            if (vm.files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (vm.loading) "Загрузка…" else "Папка пуста", color = Muted)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(vm.files, key = { it.id }) { f ->
                        FileRow(
                            f = f,
                            onClick = { if (f.isFolder) vm.open(f) else openLink(ctx, f) },
                            onOpen = { openLink(ctx, f) },
                            onRename = {
                                dialog = DialogState.Name("Переименовать", f.name) { vm.rename(f, it) }
                            },
                            onMove = { vm.startMove(f) },
                            onSend = { vm.shareFile(f) },
                            onDelete = {
                                dialog = DialogState.Confirm("Удалить «${f.name}»?\nФайл попадёт в корзину Google Диска.") {
                                    vm.trash(f)
                                }
                            },
                        )
                        HorizontalDivider(color = Color(0xFF2A2A2A))
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        is DialogState.Add -> AddDialog(
            onDismiss = { dialog = null },
            onFolder = { dialog = DialogState.Name("Новая папка", "Новая папка") { vm.create(it, DriveFile.FOLDER, "Папка создана") } },
            onSheet = { dialog = DialogState.Name("Новая таблица", "Таблица") { vm.create(it, DriveFile.SHEET, "Таблица создана") } },
            onDoc = { dialog = DialogState.Name("Новый документ", "Документ") { vm.create(it, DriveFile.DOC, "Документ создан") } },
            onUpload = {
                dialog = null
                picker.launch(arrayOf("*/*"))
            },
        )
        is DialogState.Name -> NameDialog(d.title, d.initial, onDismiss = { dialog = null }) {
            dialog = null
            d.onOk(it)
        }
        is DialogState.Confirm -> AlertDialog(
            onDismissRequest = { dialog = null },
            text = { Text(d.text) },
            confirmButton = {
                TextButton(onClick = { dialog = null; d.onOk() }) { Text("Удалить", color = Color(0xFFF28B82)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Отмена") } },
        )
        null -> Unit
    }
}

@Composable
private fun Breadcrumbs(vm: DriveViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        vm.path.forEachIndexed { i, c ->
            if (i > 0) Text("›", color = Muted)
            val last = i == vm.path.lastIndex
            Text(
                c.name,
                color = if (last) Accent else Muted,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(enabled = !last) { vm.goTo(i) }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun PendingBar(pending: Pending, folderName: String, onOk: () -> Unit, onCancel: () -> Unit) {
    val (text, okText) = when (pending) {
        is Pending.Upload -> "📎 Файлов к сохранению: ${pending.uris.size}. Откройте нужную папку." to "Сохранить в «$folderName»"
        is Pending.Move -> "↪ Перемещение «${pending.file.name}». Откройте нужную папку." to "Переместить в «$folderName»"
    }
    Card(
        Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2A3A)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(text)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onOk, modifier = Modifier.weight(1f)) {
                    Text(okText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onCancel) { Text("Отмена") }
            }
        }
    }
}

@Composable
private fun FileRow(
    f: DriveFile,
    onClick: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onSend: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(iconFor(f), fontSize = 26.sp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
            val sub = if (f.isFolder) "Папка" else listOfNotNull(f.size?.let(::formatSize), f.modified?.let(::formatDate)).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, color = Muted, fontSize = 13.sp)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Действия", tint = Muted)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Открыть") }, onClick = { menu = false; onOpen() })
                DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Переместить") }, onClick = { menu = false; onMove() })
                if (!f.isFolder) DropdownMenuItem(text = { Text("Отправить") }, onClick = { menu = false; onSend() })
                DropdownMenuItem(text = { Text("Удалить", color = Color(0xFFF28B82)) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun AddDialog(
    onDismiss: () -> Unit,
    onFolder: () -> Unit,
    onSheet: () -> Unit,
    onDoc: () -> Unit,
    onUpload: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Создать") },
        text = {
            Column {
                AddItem("📁  Новая папка", onFolder)
                AddItem("📊  Таблица (как Excel)", onSheet)
                AddItem("📝  Документ (как Word)", onDoc)
                AddItem("⬆️  Загрузить с телефона", onUpload)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun AddItem(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 17.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onOk(name.trim()) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

internal fun iconFor(f: DriveFile): String {
    val m = f.mimeType
    val n = f.name.lowercase()
    return when {
        f.isFolder -> "📁"
        m == DriveFile.SHEET || m.contains("spreadsheet") || m.contains("excel") || n.endsWith(".xlsx") || n.endsWith(".xls") || n.endsWith(".csv") -> "📊"
        m == DriveFile.DOC || m.contains("wordprocessing") || m.contains("msword") || n.endsWith(".docx") || n.endsWith(".doc") -> "📝"
        m == "application/pdf" -> "📕"
        m.startsWith("image/") -> "🖼️"
        m.startsWith("video/") -> "🎬"
        m.startsWith("audio/") -> "🎵"
        m.contains("presentation") -> "📽️"
        m.contains("zip") || m.contains("rar") || m.contains("compressed") -> "🗜️"
        else -> "📄"
    }
}

private fun formatSize(b: Long): String = when {
    b < 1024 -> "$b Б"
    b < 1024 * 1024 -> "${b / 1024} КБ"
    b < 1024L * 1024 * 1024 -> String.format("%.1f МБ", b / 1048576.0)
    else -> String.format("%.2f ГБ", b / 1073741824.0)
}

private fun formatDate(iso: String): String =
    if (iso.length >= 10) "${iso.substring(8, 10)}.${iso.substring(5, 7)}.${iso.substring(0, 4)}" else iso

internal fun openLink(ctx: Context, f: DriveFile) {
    val link = f.webLink ?: return
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
    } catch (e: ActivityNotFoundException) {
        // нет приложения для открытия
    }
}
