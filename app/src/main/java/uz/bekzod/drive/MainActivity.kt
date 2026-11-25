package uz.bekzod.drive

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.IOException

class MainActivity : ComponentActivity() {

    private val vm: DriveViewModel by viewModels()
    private var token: String? = null
    private var authWaiter: CompletableDeferred<String?>? = null

    private val authRequest: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
        .build()

    private val authLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val t = try {
            Identity.getAuthorizationClient(this)
                .getAuthorizationResultFromIntent(result.data).accessToken
        } catch (e: Exception) {
            null
        }
        authWaiter?.complete(t)
        authWaiter = null
    }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) vm.send(text, voice = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        vm.attach(DriveApi { force -> obtainToken(force) }, prefs, contentResolver, cacheDir)
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            AppTheme {
                AppRoot(vm, onMic = { startVoice() }, onShare = { req -> shareFile(req) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите…")
        try {
            speechLauncher.launch(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Нет распознавания речи. Установите приложение Google.", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareFile(req: ShareReq) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", req.file)
            val send = Intent(Intent.ACTION_SEND)
                .setType(req.mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Отправить ${req.file.name}"))
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось отправить: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun handleShare(i: Intent?) {
        if (i == null) return
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList() ?: emptyList()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            vm.screen = Screen.CHAT
            vm.addAttachments(uris)
        }
    }

    private suspend fun obtainToken(force: Boolean): String {
        if (!force) token?.let { return it }
        val client = Identity.getAuthorizationClient(this)
        val res = try {
            withContext(Dispatchers.Main) { client.authorize(authRequest).await() }
        } catch (e: Exception) {
            throw IOException("Ошибка входа в Google: ${e.message}. Проверьте настройки Google Cloud (SHA-1, Drive API, тестовый пользователь).")
        }
        val t: String? = if (res.hasResolution()) {
            val waiter = CompletableDeferred<String?>()
            withContext(Dispatchers.Main) {
                authWaiter = waiter
                authLauncher.launch(IntentSenderRequest.Builder(res.pendingIntent!!.intentSender).build())
            }
            waiter.await()
        } else {
            res.accessToken
        }
        val ok = t ?: throw IOException("Вход в Google не выполнен")
        token = ok
        return ok
    }
}
