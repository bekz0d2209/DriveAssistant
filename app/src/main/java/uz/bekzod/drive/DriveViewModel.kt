package uz.bekzod.drive

import android.content.ContentResolver
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Crumb(val id: String, val name: String)

data class Attachment(val uri: Uri, val name: String)

data class ChatMsg(
    val fromUser: Boolean,
    val text: String,
    val voice: Boolean = false,
    val files: List<DriveFile> = emptyList(),
    val attachNames: List<String> = emptyList(),
    val error: Boolean = false,
)

data class ConfirmReq(val text: String, val result: CompletableDeferred<Boolean>)

data class ShareReq(val file: File, val mime: String)

enum class Screen { CHAT, FILES }

sealed class Pending {
    data class Upload(val uris: List<Uri>) : Pending()
    data class Move(val file: DriveFile, val fromId: String) : Pending()
}

class DriveViewModel : ViewModel() {
    private var api: DriveApi? = null
    private var prefs: SharedPreferences? = null
    private var resolver: ContentResolver? = null
    private var cacheDir: File? = null
    private val gemini = Gemini(
        key = { prefs?.getString("gemini_key", "") ?: "" },
        model = { prefs?.getString("gemini_model", DEFAULT_MODEL) ?: DEFAULT_MODEL },
    )

    var screen by mutableStateOf(Screen.CHAT)

    // ---------- Чат ----------
    val messages = mutableStateListOf<ChatMsg>()
    val attachments = mutableStateListOf<Attachment>()
    var thinking by mutableStateOf(false)
        private set
    var confirm by mutableStateOf<ConfirmReq?>(null)
    var share by mutableStateOf<ShareReq?>(null)
    var showSettings by mutableStateOf(false)
    private val history = mutableListOf<JSONObject>()

    // ---------- Файлы ----------
    val path = mutableStateListOf(Crumb("root", "Мой диск"))
    var files by mutableStateOf<List<DriveFile>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var pending by mutableStateOf<Pending?>(null)
    var message by mutableStateOf<String?>(null)
    private var filesStale = true

    val current: Crumb get() = path.last()

    fun attach(api: DriveApi, prefs: SharedPreferences, resolver: ContentResolver, cacheDir: File) {
        this.api = api
        this.prefs = prefs
        this.resolver = resolver
        this.cacheDir = cacheDir
        if (messages.isEmpty()) {
            messages.add(
                ChatMsg(
                    false,
                    "Салам! Я ваш помощник по документам на Google Диске.\n" +
                        "Пришлите файл и скажите, куда его положить, или попросите создать таблицу, найти или отправить документ.",
                )
            )
            if (geminiKey().isBlank()) showSettings = true
        }
    }

    fun geminiKey() = prefs?.getString("gemini_key", "") ?: ""
    fun geminiModel() = prefs?.getString("gemini_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun rules() = prefs?.getString("rules", "") ?: ""
    var checkResult by mutableStateOf<String?>(null)
    var checking by mutableStateOf(false)
        private set

    fun checkKey(key: String, onModel: (String) -> Unit) {
        viewModelScope.launch {
            checking = true
            checkResult = null
            val (_, msg, best) = gemini.check(key)
            checkResult = msg
            if (best != null) onModel(best)
            checking = false
        }
    }
    fun saveSettings(key: String, model: String, rules: String) {
        prefs?.edit()?.putString("gemini_key", key.replace(Regex("[\\s\"'«»]"), ""))
            ?.putString("gemini_model", model.trim().ifEmpty { DEFAULT_MODEL })
            ?.putString("rules", rules.trim())?.apply()
        showSettings = false
    }

    // ================= ЧАТ =================

    fun addAttachments(uris: List<Uri>) {
        val r = resolver ?: return
        val added = uris.map { Attachment(it, displayName(r, it)) }
        attachments.addAll(added)
        messages.add(ChatMsg(true, "", attachNames = added.map { it.name }))
        messages.add(ChatMsg(false, "Получил ${if (added.size == 1) "файл" else "файлы (${added.size})"}. Куда положить? Скажите или напишите название папки."))
    }

    fun removeAttachment(a: Attachment) {
        attachments.remove(a)
    }

    fun send(text: String, voice: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty() || thinking) return
        messages.add(ChatMsg(true, t, voice))
        val userText = if (attachments.isNotEmpty()) {
            "$t\n[Прикреплённые файлы, ещё не сохранены: ${attachments.joinToString { it.name }}]"
        } else t
        val before = history.size
        val toShow = attachments.toList()
        viewModelScope.launch {
            thinking = true
            try {
                val parts = JSONArray()
                val r = resolver
                if (r != null) for (att in toShow) {
                    val mime = r.getType(att.uri) ?: ""
                    if (mime == "application/pdf" || mime.startsWith("image/")) {
                        val bytes = withContext(Dispatchers.IO) { r.openInputStream(att.uri)?.use { it.readBytes() } }
                        if (bytes != null && bytes.size < 15_000_000) {
                            parts.put(JSONObject().put("text", "Файл «${att.name}»:"))
                            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", mime)
                                .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))))
                        }
                    }
                }
                parts.put(JSONObject().put("text", userText))
                history.add(JSONObject().put("role", "user").put("parts", parts))
                runAgent()
                stripInline()
            } catch (e: Exception) {
                while (history.size > before) history.removeAt(history.lastIndex)
                messages.add(ChatMsg(false, e.message ?: e.toString(), error = true))
            } finally {
                thinking = false
                filesStale = true
            }
        }
    }

    /** Убираем тяжёлые файлы из истории после ответа, чтобы не тратить лимит. */
    private fun stripInline() {
        for (msg in history) {
            val p = msg.optJSONArray("parts") ?: continue
            for (i in 0 until p.length()) {
                val o = p.getJSONObject(i)
                if (o.has("inlineData")) {
                    o.remove("inlineData")
                    o.put("text", "[содержимое файла уже было показано выше]")
                }
            }
        }
    }

    private fun isUserText(o: JSONObject): Boolean {
        if (o.optString("role") != "user") return false
        val p = o.optJSONArray("parts") ?: return false
        return p.length() > 0 && !p.getJSONObject(0).has("functionResponse")
    }

    private fun trimHistory() {
        while (history.size > 40) {
            history.removeAt(0)
            while (history.isNotEmpty() && !isUserText(history[0])) history.removeAt(0)
        }
    }

    private suspend fun runAgent() {
        val touched = linkedMapOf<String, DriveFile>()
        val today = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
        repeat(10) {
            trimHistory()
            val content = gemini.generate(Tools.system(today, rules()), JSONArray(history), Tools.declarations)
            history.add(content)
            val parts = content.optJSONArray("parts") ?: JSONArray()
            val calls = mutableListOf<JSONObject>()
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                p.optJSONObject("functionCall")?.let { calls.add(it) }
                if (p.has("text") && !p.optBoolean("thought", false)) sb.append(p.getString("text"))
            }
            if (calls.isEmpty()) {
                messages.add(ChatMsg(false, sb.toString().trim().ifEmpty { "Готово." }, files = touched.values.toList()))
                return
            }
            val responses = JSONArray()
            for (c in calls) {
                val name = c.optString("name")
                val result = exec(name, c.optJSONObject("args") ?: JSONObject(), touched)
                val fr = JSONObject().put("name", name).put("response", result)
                if (c.has("id")) fr.put("id", c.getString("id"))
                responses.put(JSONObject().put("functionResponse", fr))
            }
            history.add(JSONObject().put("role", "user").put("parts", responses))
        }
        messages.add(ChatMsg(false, "Получилось слишком много шагов. Попробуйте сформулировать проще.", files = touched.values.toList()))
    }

    private fun fileJson(f: DriveFile) = JSONObject()
        .put("file_id", f.id).put("name", f.name).put("type", f.kind)

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    private fun rowsOf(a: JSONArray?): List<List<String>> =
        if (a == null) emptyList() else (0 until a.length()).map { strings(a.optJSONArray(it)) }

    private fun csv(rows: List<List<String>>): String = rows.joinToString("\n") { r ->
        r.joinToString(",") { v -> "\"" + v.replace("\"", "\"\"") + "\"" }
    }

    private fun html(text: String): String {
        val sb = StringBuilder("<html><head><meta charset=\"utf-8\"></head><body>")
        for (line in text.lines()) {
            val e = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            when {
                e.startsWith("# ") -> sb.append("<h1>").append(e.removePrefix("# ")).append("</h1>")
                e.startsWith("## ") -> sb.append("<h2>").append(e.removePrefix("## ")).append("</h2>")
                e.isBlank() -> sb.append("<p></p>")
                else -> sb.append("<p>").append(e).append("</p>")
            }
        }
        return sb.append("</body></html>").toString()
    }

    private suspend fun exec(name: String, a: JSONObject, touched: MutableMap<String, DriveFile>): JSONObject {
        val api = this.api ?: return JSONObject().put("error", "Диск не подключён")
        return try {
            when (name) {
                "list_folder" -> {
                    val (id, p) = api.resolvePath(a.optString("path"), false)
                    val list = api.list(id)
                    JSONObject().put("folder", p).put("count", list.size)
                        .put("items", JSONArray(list.take(100).map { fileJson(it) }))
                }
                "search_files" -> {
                    val list = api.search(a.optString("query"))
                    JSONObject().put("items", JSONArray(list.map { fileJson(it) }))
                }
                "create_folder" -> {
                    val (id, p) = api.resolvePath(a.optString("path"), true)
                    touched[id] = api.get(id)
                    JSONObject().put("result", "ok").put("folder", p).put("file_id", id)
                }
                "create_spreadsheet" -> {
                    val (pid, p) = api.resolvePath(a.optString("folder"), true)
                    val rows = listOf(strings(a.optJSONArray("columns"))) + rowsOf(a.optJSONArray("rows"))
                    val f = api.upload(
                        a.optString("name", "Таблица"), "text/csv; charset=utf-8",
                        csv(rows).toByteArray(Charsets.UTF_8), pid, DriveFile.SHEET,
                    )
                    touched[f.id] = f
                    JSONObject().put("result", "ok").put("folder", p).put("file", fileJson(f))
                }
                "append_rows" -> {
                    val id = a.optString("file_id")
                    api.appendRows(id, rowsOf(a.optJSONArray("rows")))
                    touched[id] = api.get(id)
                    JSONObject().put("result", "ok")
                }
                "create_document" -> {
                    val (pid, p) = api.resolvePath(a.optString("folder"), true)
                    val f = api.upload(
                        a.optString("name", "Документ"), "text/html; charset=utf-8",
                        html(a.optString("text")).toByteArray(Charsets.UTF_8), pid, DriveFile.DOC,
                    )
                    touched[f.id] = f
                    JSONObject().put("result", "ok").put("folder", p).put("file", fileJson(f))
                }
                "read_file" -> {
                    val f = api.get(a.optString("file_id"))
                    val text = when (f.mimeType) {
                        DriveFile.SHEET -> String(api.export(f.id, "text/csv"), Charsets.UTF_8)
                        DriveFile.DOC -> String(api.export(f.id, "text/plain"), Charsets.UTF_8)
                        DriveFile.XLSX -> "Это файл Excel (.xlsx). Прочитать напрямую не могу — предложи пользователю открыть его как Google Таблицу."
                        else -> when {
                            f.mimeType.startsWith("text/") || f.mimeType.contains("csv") ->
                                String(api.download(f.id), Charsets.UTF_8)
                            f.mimeType == "application/pdf" || f.mimeType.startsWith("image/") -> {
                                val bytes = api.download(f.id)
                                if (bytes.size > 15_000_000) return JSONObject().put("error", "Файл больше 15 МБ, слишком большой для чтения")
                                gemini.extract(bytes, f.mimeType, rules())
                            }
                            else -> return JSONObject().put("error", "Этот тип файла читать не умею: ${f.mimeType}")
                        }
                    }
                    touched[f.id] = f
                    JSONObject().put("name", f.name).put("content", text.take(12000))
                }
                "rename_file" -> {
                    val id = a.optString("file_id")
                    api.rename(id, a.optString("new_name"))
                    touched[id] = api.get(id)
                    JSONObject().put("result", "ok")
                }
                "move_file" -> {
                    val id = a.optString("file_id")
                    val (pid, p) = api.resolvePath(a.optString("folder"), true)
                    api.move(id, pid)
                    touched[id] = api.get(id)
                    JSONObject().put("result", "ok").put("folder", p)
                }
                "trash_file" -> {
                    val f = api.get(a.optString("file_id"))
                    val d = CompletableDeferred<Boolean>()
                    confirm = ConfirmReq("Удалить «${f.name}»?\nОн попадёт в корзину Google Диска, его можно восстановить.", d)
                    val ok = d.await()
                    confirm = null
                    if (ok) {
                        api.trash(f.id)
                        touched.remove(f.id)
                        JSONObject().put("result", "удалено в корзину")
                    } else JSONObject().put("result", "пользователь отменил удаление")
                }
                "save_attachments" -> {
                    if (attachments.isEmpty()) return JSONObject().put("error", "Нет прикреплённых файлов")
                    val r = resolver ?: return JSONObject().put("error", "Нет доступа к файлам")
                    val (pid, p) = api.resolvePath(a.optString("folder"), true)
                    val names = strings(a.optJSONArray("names"))
                    val saved = JSONArray()
                    for ((i, att) in attachments.toList().withIndex()) {
                        val (_, mime, bytes) = readUri(r, att.uri)
                        var n = names.getOrNull(i)?.takeIf { it.isNotBlank() } ?: att.name
                        val ext = att.name.substringAfterLast('.', "")
                        if (ext.isNotEmpty() && !n.contains('.')) n = "$n.$ext"
                        val f = api.upload(n, mime, bytes, pid)
                        touched[f.id] = f
                        saved.put(fileJson(f))
                        attachments.remove(att)
                    }
                    JSONObject().put("result", "ok").put("folder", p).put("saved", saved)
                }
                "share_file" -> {
                    val f = api.get(a.optString("file_id"))
                    val (bytes, mime, ext) = when {
                        f.mimeType == DriveFile.SHEET -> Triple(api.export(f.id, DriveFile.XLSX), DriveFile.XLSX, ".xlsx")
                        f.mimeType == DriveFile.DOC -> Triple(api.export(f.id, DriveFile.DOCX), DriveFile.DOCX, ".docx")
                        f.mimeType.startsWith("application/vnd.google-apps") ->
                            Triple(api.export(f.id, "application/pdf"), "application/pdf", ".pdf")
                        f.isFolder -> return JSONObject().put("error", "Папку отправить нельзя, только файл")
                        else -> Triple(api.download(f.id), f.mimeType, "")
                    }
                    val dir = File(cacheDir, "share").apply { mkdirs() }
                    val safe = f.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val out = File(dir, if (ext.isNotEmpty() && !safe.endsWith(ext)) safe + ext else safe)
                    withContext(Dispatchers.IO) { out.writeBytes(bytes) }
                    share = ShareReq(out, mime)
                    JSONObject().put("result", "открыто меню «Поделиться»")
                }
                else -> JSONObject().put("error", "Неизвестная функция $name")
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: e.toString())
        }
    }

    // ================= ФАЙЛЫ =================

    private fun op(success: String? = null, block: suspend (DriveApi) -> Unit) {
        val a = api ?: return
        viewModelScope.launch {
            loading = true
            try {
                block(a)
                if (success != null) message = success
            } catch (e: Exception) {
                message = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }
    }

    private suspend fun reload(a: DriveApi) {
        files = a.list(current.id)
        filesStale = false
    }

    fun openFiles() {
        screen = Screen.FILES
        if (filesStale) refresh()
    }

    fun refresh() = op { reload(it) }

    fun open(f: DriveFile) {
        path.add(Crumb(f.id, f.name))
        files = emptyList()
        refresh()
    }

    fun back(): Boolean {
        if (path.size <= 1) return false
        path.removeAt(path.lastIndex)
        files = emptyList()
        refresh()
        return true
    }

    fun goTo(index: Int) {
        if (index >= path.lastIndex) return
        while (path.size > index + 1) path.removeAt(path.lastIndex)
        files = emptyList()
        refresh()
    }

    fun create(name: String, mime: String, success: String) = op(success) { a ->
        a.create(name, mime, current.id)
        reload(a)
    }

    fun rename(f: DriveFile, name: String) = op("Переименовано") { a ->
        a.rename(f.id, name)
        reload(a)
    }

    fun trash(f: DriveFile) = op("«${f.name}» в корзине") { a ->
        a.trash(f.id)
        reload(a)
    }

    fun shareFile(f: DriveFile) = op { a ->
        exec("share_file", JSONObject().put("file_id", f.id), mutableMapOf()).optString("error").takeIf { it.isNotEmpty() }
            ?.let { message = it }
    }

    fun startMove(f: DriveFile) {
        pending = Pending.Move(f, current.id)
    }

    fun upload(uris: List<Uri>) = op("Загружено файлов: ${uris.size}") { a ->
        val r = resolver ?: throw IOException("Нет доступа к файлам")
        for (u in uris) {
            val (name, mime, bytes) = readUri(r, u)
            a.upload(name, mime, bytes, current.id)
        }
        reload(a)
    }

    fun confirmPending() {
        when (val p = pending) {
            is Pending.Upload -> {
                pending = null
                upload(p.uris)
            }
            is Pending.Move -> {
                if (p.fromId == current.id) {
                    message = "Файл уже в этой папке"
                    return
                }
                pending = null
                op("Перемещено в «${current.name}»") { a ->
                    a.move(p.file.id, current.id)
                    reload(a)
                }
            }
            null -> Unit
        }
    }

    private fun displayName(r: ContentResolver, u: Uri): String {
        var name = u.lastPathSegment ?: "file"
        try {
            r.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        } catch (e: Exception) {
            // оставляем имя из пути
        }
        return name
    }

    private suspend fun readUri(r: ContentResolver, u: Uri): Triple<String, String, ByteArray> =
        withContext(Dispatchers.IO) {
            val name = displayName(r, u)
            val mime = r.getType(u) ?: "application/octet-stream"
            val bytes = r.openInputStream(u)?.use { it.readBytes() }
                ?: throw IOException("Не удалось прочитать файл $name")
            Triple(name, mime, bytes)
        }
}
