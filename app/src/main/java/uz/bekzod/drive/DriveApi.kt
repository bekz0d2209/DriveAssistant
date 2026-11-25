package uz.bekzod.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long?,
    val modified: String?,
    val webLink: String?,
    val parents: List<String> = emptyList(),
) {
    val isFolder: Boolean get() = mimeType == FOLDER

    val kind: String
        get() = when {
            isFolder -> "папка"
            mimeType == SHEET -> "таблица Google"
            mimeType == DOC -> "документ Google"
            else -> "файл"
        }

    companion object {
        const val FOLDER = "application/vnd.google-apps.folder"
        const val SHEET = "application/vnd.google-apps.spreadsheet"
        const val DOC = "application/vnd.google-apps.document"
        const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}

/** Обёртка над Google Drive REST API v3 (+ немного Sheets API). */
class DriveApi(private val tokenProvider: suspend (force: Boolean) -> String) {

    private val client = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()
    private val base = "https://www.googleapis.com/drive/v3/files"
    private val upload = "https://www.googleapis.com/upload/drive/v3/files"
    private val fields = "id,name,mimeType,size,modifiedTime,webViewLink,parents"

    private suspend fun raw(build: () -> Request.Builder): ByteArray {
        var token = tokenProvider(false)
        return withContext(Dispatchers.IO) {
            var resp = client.newCall(build().header("Authorization", "Bearer $token").build()).execute()
            if (resp.code == 401) {
                resp.close()
                token = tokenProvider(true)
                resp = client.newCall(build().header("Authorization", "Bearer $token").build()).execute()
            }
            resp.use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                if (!r.isSuccessful) {
                    throw IOException("Ошибка Google ${r.code}: ${parseError(String(bytes, Charsets.UTF_8))}")
                }
                bytes
            }
        }
    }

    private suspend fun call(build: () -> Request.Builder): String = String(raw(build), Charsets.UTF_8)

    private fun parseError(body: String): String = try {
        JSONObject(body).getJSONObject("error").getString("message")
    } catch (e: Exception) {
        body.take(200)
    }

    private fun parseFile(o: JSONObject): DriveFile {
        val p = o.optJSONArray("parents")
        val parents = if (p == null) emptyList() else (0 until p.length()).map { p.getString(it) }
        return DriveFile(
            id = o.getString("id"),
            name = o.optString("name"),
            mimeType = o.optString("mimeType"),
            size = o.optString("size").toLongOrNull(),
            modified = o.optString("modifiedTime").ifEmpty { null },
            webLink = o.optString("webViewLink").ifEmpty { null },
            parents = parents,
        )
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    private suspend fun query(q: String, orderBy: String = "folder,name", max: Int = 1000): List<DriveFile> {
        val result = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url = base.toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("fields", "nextPageToken,files($fields)")
                .addQueryParameter("orderBy", orderBy)
                .addQueryParameter("pageSize", minOf(max, 1000).toString())
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val obj = JSONObject(call { Request.Builder().url(url).get() })
            val arr = obj.optJSONArray("files") ?: JSONArray()
            for (i in 0 until arr.length()) result.add(parseFile(arr.getJSONObject(i)))
            pageToken = obj.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null && result.size < max)
        return result
    }

    suspend fun list(folderId: String): List<DriveFile> =
        query("'${esc(folderId)}' in parents and trashed=false")

    suspend fun search(text: String): List<DriveFile> =
        query("name contains '${esc(text)}' and trashed=false", orderBy = "modifiedTime desc", max = 25)

    suspend fun get(id: String): DriveFile =
        parseFile(JSONObject(call { Request.Builder().url("$base/$id?fields=$fields").get() }))

    private suspend fun childFolder(parentId: String, name: String): DriveFile? {
        val folders = query("'${esc(parentId)}' in parents and mimeType='${DriveFile.FOLDER}' and trashed=false")
        return folders.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: folders.firstOrNull { it.name.contains(name, true) || name.contains(it.name, true) }
    }

    /** Путь вида "Договоры/2026" → id папки. Пустой путь = корень. */
    suspend fun resolvePath(path: String?, create: Boolean): Pair<String, String> {
        var id = "root"
        val names = mutableListOf<String>()
        val segments = (path ?: "").split("/").map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("Мой диск", true) && !it.equals("root", true) }
        for (seg in segments) {
            val f = childFolder(id, seg)
                ?: if (create) create(seg, DriveFile.FOLDER, id)
                else throw IOException("Папка «$seg» не найдена")
            id = f.id
            names.add(f.name)
        }
        return id to (if (names.isEmpty()) "Мой диск" else names.joinToString("/"))
    }

    suspend fun create(name: String, mimeType: String, parentId: String): DriveFile {
        val meta = JSONObject()
            .put("name", name)
            .put("mimeType", mimeType)
            .put("parents", JSONArray().put(parentId))
            .toString().toRequestBody(json)
        return parseFile(JSONObject(call { Request.Builder().url("$base?fields=$fields").post(meta) }))
    }

    suspend fun rename(id: String, newName: String) {
        val b = JSONObject().put("name", newName).toString().toRequestBody(json)
        call { Request.Builder().url("$base/$id").patch(b) }
    }

    suspend fun trash(id: String) {
        val b = JSONObject().put("trashed", true).toString().toRequestBody(json)
        call { Request.Builder().url("$base/$id").patch(b) }
    }

    suspend fun move(id: String, toId: String) {
        val f = get(id)
        val url = "$base/$id".toHttpUrl().newBuilder()
            .addQueryParameter("addParents", toId)
            .addQueryParameter("removeParents", f.parents.joinToString(","))
            .addQueryParameter("fields", fields)
            .build()
        val b = "{}".toRequestBody(json)
        call { Request.Builder().url(url).patch(b) }
    }

    /** Загрузка файла. targetMime != null → Google сконвертирует (CSV → Таблица, HTML → Документ). */
    suspend fun upload(
        name: String,
        mimeType: String,
        bytes: ByteArray,
        parentId: String,
        targetMime: String? = null,
    ): DriveFile {
        val metaObj = JSONObject().put("name", name).put("parents", JSONArray().put(parentId))
        if (targetMime != null) metaObj.put("mimeType", targetMime)
        val meta = metaObj.toString().toRequestBody(json)
        val media = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
        val multipart = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(meta)
            .addPart(media)
            .build()
        val body = call {
            Request.Builder().url("$upload?uploadType=multipart&fields=$fields").post(multipart)
        }
        return parseFile(JSONObject(body))
    }

    suspend fun download(id: String): ByteArray =
        raw { Request.Builder().url("$base/$id?alt=media").get() }

    suspend fun export(id: String, mime: String): ByteArray {
        val url = "$base/$id/export".toHttpUrl().newBuilder().addQueryParameter("mimeType", mime).build()
        return raw { Request.Builder().url(url).get() }
    }

    /** Добавить строки в конец Google Таблицы (нужен включённый Google Sheets API). */
    suspend fun appendRows(spreadsheetId: String, rows: List<List<String>>) {
        val values = JSONArray()
        rows.forEach { r -> values.put(JSONArray(r)) }
        val b = JSONObject().put("values", values).toString().toRequestBody(json)
        val url = "https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId/values/A1:append".toHttpUrl()
            .newBuilder()
            .addQueryParameter("valueInputOption", "USER_ENTERED")
            .addQueryParameter("insertDataOption", "INSERT_ROWS")
            .build()
        call { Request.Builder().url(url).post(b) }
    }
}
