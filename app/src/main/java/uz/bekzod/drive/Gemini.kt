package uz.bekzod.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

const val DEFAULT_MODEL = "gemini-flash-latest"

/** Клиент Gemini API (бесплатный ключ из aistudio.google.com). */
class Gemini(private val key: () -> String, private val model: () -> String) {

    private val client = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    /** Прочитать PDF или фото: вернуть текст и ключевые данные. */
    suspend fun extract(bytes: ByteArray, mime: String, rules: String): String {
        val data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val parts = JSONArray()
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", mime).put("data", data)))
            .put(JSONObject().put("text", "Прочитай этот документ. Перепиши весь важный текст и отдельно перечисли ключевые данные: номера (вагонов, документов, накладных), даты, станции, отправителя, получателя, груз, вес, суммы. Пиши по-русски.\n$rules"))
        val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", parts))
        val c = generate("Ты читаешь документы и точно извлекаешь из них данные. Не выдумывай: если чего-то нет — так и скажи.", contents, null)
        val p = c.optJSONArray("parts") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until p.length()) {
            val o = p.getJSONObject(i)
            if (o.has("text") && !o.optBoolean("thought", false)) sb.append(o.getString("text"))
        }
        return sb.toString()
    }

    /** Модель, которая последней ответила успешно (чтобы не переключаться туда-сюда). */
    @Volatile private var working: String? = null

    private class Busy(msg: String) : IOException(msg)
    private class NoModel(msg: String) : IOException(msg)

    suspend fun generate(system: String, contents: JSONArray, tools: JSONArray?): JSONObject {
        val k = key().trim()
        if (k.isEmpty()) throw IOException("Не указан ключ Gemini. Нажмите ⚙ вверху и вставьте ключ.")
        val mine = model().trim().ifEmpty { DEFAULT_MODEL }
        val order = LinkedHashSet<String>()
        working?.let { order.add(it) }
        order.add(mine)
        order.addAll(FALLBACKS)
        var last: IOException? = null
        for (m in order) {
            for (attempt in 0 until 2) {
                try {
                    val r = once(k, m, system, contents, tools)
                    working = m
                    return r
                } catch (e: Busy) {
                    last = e
                    kotlinx.coroutines.delay(if (attempt == 0) 1500L else 3000L)
                } catch (e: NoModel) {
                    last = e
                    break
                }
            }
        }
        throw IOException("Серверы Gemini сейчас перегружены. Подождите минуту и повторите.\n(${last?.message})")
    }

    private suspend fun once(k: String, m: String, system: String, contents: JSONArray, tools: JSONArray?): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", contents)
            if (tools != null) body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools)))
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                .header("x-goog-api-key", k)
                .post(body.toString().toRequestBody(json))
                .build()
            client.newCall(req).execute().use { r ->
                val s = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    val msg = try {
                        JSONObject(s).getJSONObject("error").getString("message")
                    } catch (e: Exception) {
                        s.take(200)
                    }
                    when (r.code) {
                        429, 500, 503, 504 -> throw Busy("$m: ${r.code}")
                        404 -> throw NoModel("$m не найдена")
                        400, 403 -> throw IOException("Gemini: $msg")
                        else -> throw IOException("Gemini ${r.code}: $msg")
                    }
                }
                val content = JSONObject(s).optJSONArray("candidates")
                    ?.optJSONObject(0)?.optJSONObject("content")
                    ?: throw IOException("Gemini не дал ответа, попробуйте сказать иначе.")
                content.put("role", "model")
                if (!content.has("parts")) content.put("parts", JSONArray())
                content
            }
        }

    companion object {
        val FALLBACKS = listOf("gemini-flash-lite-latest", "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-2.5-flash")
    }
}

object Tools {
    fun system(today: String, rules: String) = """
Ты — ИИ-помощник Бекзода по документам. Все файлы лежат на его Google Диске.
Отвечай коротко и по делу, на языке пользователя (русский или узбекский).
Действуй сам через функции, не спрашивай лишнего. Сегодня $today.

Правила:
- Пути папок пиши через «/», от корня «Мой диск». Пустой путь = корень. Пример: "Договоры/2026".
- Если пользователь называет папку неточно, сначала посмотри list_folder("") и выбери подходящую.
- Если нужной папки нет — создай её (create_folder), если пользователь явно хочет туда что-то положить.
- Когда пользователь прислал файлы и говорит, куда их положить, вызови save_attachments.
- Для поиска файла по названию используй search_files, затем работай с file_id.
- Таблицы создавай через create_spreadsheet с понятными колонками. Документы — через create_document.
- Для удаления просто вызови trash_file: приложение само спросит подтверждение у пользователя.
- Чтобы отправить файл (Telegram, почта и т.п.), вызови share_file: откроется меню «Поделиться».
- Ты умеешь читать PDF и фото. Прикреплённые файлы ты видишь прямо в сообщении. Файлы на Диске читай через read_file.
- Номер вагона обычно 8 цифр. Если пользователь просит переименовать по данным из документа — сначала прочитай его.
- После действий кратко скажи, что сделано.
""".trimIndent() + (if (rules.isNotBlank()) "\n\nПравила пользователя (выполняй всегда):\n$rules" else "")

    val declarations: JSONArray get() = JSONArray(DECL)

    private const val DECL = """
[
 {"name":"list_folder","description":"Показать содержимое папки на Google Диске.",
  "parameters":{"type":"OBJECT","properties":{"path":{"type":"STRING","description":"Путь папки, например 'Договоры/2026'. Пустая строка — корень."}}}},
 {"name":"search_files","description":"Найти файлы и папки по части названия.",
  "parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING","description":"Часть названия"}},"required":["query"]}},
 {"name":"create_folder","description":"Создать папку (и недостающие родительские).",
  "parameters":{"type":"OBJECT","properties":{"path":{"type":"STRING","description":"Полный путь новой папки, например 'Счета/2026'"}},"required":["path"]}},
 {"name":"create_spreadsheet","description":"Создать Google Таблицу (аналог Excel) с колонками и строками.",
  "parameters":{"type":"OBJECT","properties":{
    "name":{"type":"STRING"},
    "folder":{"type":"STRING","description":"Путь папки. Пусто — корень."},
    "columns":{"type":"ARRAY","items":{"type":"STRING"}},
    "rows":{"type":"ARRAY","items":{"type":"ARRAY","items":{"type":"STRING"}}}},
   "required":["name","columns"]}},
 {"name":"append_rows","description":"Добавить строки в конец существующей Google Таблицы.",
  "parameters":{"type":"OBJECT","properties":{
    "file_id":{"type":"STRING"},
    "rows":{"type":"ARRAY","items":{"type":"ARRAY","items":{"type":"STRING"}}}},
   "required":["file_id","rows"]}},
 {"name":"create_document","description":"Создать Google Документ (аналог Word) с текстом.",
  "parameters":{"type":"OBJECT","properties":{
    "name":{"type":"STRING"},
    "folder":{"type":"STRING","description":"Путь папки. Пусто — корень."},
    "text":{"type":"STRING","description":"Текст. Строки, начинающиеся с '# ', станут заголовками."}},
   "required":["name"]}},
 {"name":"read_file","description":"Прочитать содержимое файла на Диске: Google Таблицы, Документы, PDF, фото/сканы, текст.",
  "parameters":{"type":"OBJECT","properties":{"file_id":{"type":"STRING"}},"required":["file_id"]}},
 {"name":"rename_file","description":"Переименовать файл или папку.",
  "parameters":{"type":"OBJECT","properties":{"file_id":{"type":"STRING"},"new_name":{"type":"STRING"}},"required":["file_id","new_name"]}},
 {"name":"move_file","description":"Переместить файл или папку в другую папку.",
  "parameters":{"type":"OBJECT","properties":{"file_id":{"type":"STRING"},"folder":{"type":"STRING","description":"Путь папки назначения"}},"required":["file_id","folder"]}},
 {"name":"trash_file","description":"Удалить файл или папку в корзину (приложение спросит подтверждение).",
  "parameters":{"type":"OBJECT","properties":{"file_id":{"type":"STRING"}},"required":["file_id"]}},
 {"name":"save_attachments","description":"Сохранить файлы, которые пользователь прикрепил в чат, в папку на Диске.",
  "parameters":{"type":"OBJECT","properties":{
    "folder":{"type":"STRING","description":"Путь папки. Если её нет, она будет создана."},
    "names":{"type":"ARRAY","items":{"type":"STRING"},"description":"Новые имена файлов по порядку (необязательно)."}},
   "required":["folder"]}},
 {"name":"share_file","description":"Отправить файл: открыть меню «Поделиться» (Telegram, почта и т.д.). Таблицы уходят как .xlsx, документы как .docx.",
  "parameters":{"type":"OBJECT","properties":{"file_id":{"type":"STRING"}},"required":["file_id"]}}
]
"""
}
