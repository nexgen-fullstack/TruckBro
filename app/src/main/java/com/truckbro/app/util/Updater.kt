package com.truckbro.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.truckbro.app.road.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Оновлення одним натисканням: завантажує новий APK з GitHub прямо в програму
 * і відкриває системне вікно «Оновити програму?». Без браузера й пошуку файлу в Завантаженнях.
 * Android завжди сам питає підтвердження — тихо встановити не можна, і це правильно.
 */
object Updater {
    sealed interface State {
        data object Idle : State
        data class Downloading(val progress: Float) : State
        /** Треба один раз дозволити TruckBro встановлювати оновлення. */
        data object NeedPermission : State
        /** Відкрили системне вікно — лишилось натиснути «Оновити». */
        data object Installing : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var ready: File? = null

    suspend fun start(ctx: Context, u: UpdateChecker.Update) {
        if (_state.value is State.Downloading) return
        val app = ctx.applicationContext
        val file = runCatching { download(app, u) }.getOrElse {
            Log.w("TruckBroUpdate", "download", it)
            _state.value = State.Failed("Не вдалося завантажити (${it.message}). Перевір інтернет і спробуй ще раз.")
            return
        }
        ready = file
        install(ctx)
    }

    /** Викликати, коли людина повернулась із системних налаштувань дозволу. */
    fun onResume(ctx: Context) {
        if (_state.value == State.NeedPermission && canInstall(ctx)) install(ctx)
    }

    fun reset() { if (_state.value !is State.Downloading) _state.value = State.Idle }

    fun canInstall(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun openPermissionSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** [ctx] — бажано екран програми: тоді вікно встановлення відкривається в нашій задачі, а не в старій «Встановлено». */
    private fun install(ctx: Context) {
        val f = ready ?: run { _state.value = State.Idle; return }
        if (!canInstall(ctx)) {
            _state.value = State.NeedPermission
            openPermissionSettings(ctx)
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        runCatching { ctx.startActivity(i); _state.value = State.Installing }
            .onFailure { _state.value = State.Failed("Не вдалося відкрити встановлення: ${it.message}") }
    }

    private suspend fun download(ctx: Context, u: UpdateChecker.Update): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "TruckBro-${u.version}.apk")
        // Старі завантаження прибираємо.
        dir.listFiles()?.filter { it.name != target.name }?.forEach { it.delete() }
        if (target.exists() && isValid(ctx, target, u.version)) return@withContext target
        if (!u.apkUrl.endsWith(".apk")) error("у версії немає файлу APK")

        _state.value = State.Downloading(0f)
        val part = File(dir, target.name + ".part")
        var url = URL(u.apkUrl)
        var c: HttpURLConnection
        // GitHub переадресовує на свій файловий сервер — ідемо за переадресаціями вручну (до 5).
        var hops = 0
        while (true) {
            c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", Http.UA)
            }
            val code = c.responseCode
            if (code in 300..399 && hops++ < 5) {
                url = URL(url, c.getHeaderField("Location")); c.disconnect(); continue
            }
            if (code !in 200..299) { c.disconnect(); error("HTTP $code") }
            break
        }
        try {
            val total = c.contentLengthLong
            var done = 0L
            c.inputStream.use { inp ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) _state.value = State.Downloading((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        if (!part.renameTo(target)) error("не вдалося зберегти файл")
        if (!isValid(ctx, target, u.version)) { target.delete(); error("файл пошкоджений") }
        target
    }

    /** Перевіряємо, що це справді TruckBro потрібної версії, а не обірване чи чуже завантаження. */
    private fun isValid(ctx: Context, f: File, version: String): Boolean {
        val info = runCatching { ctx.packageManager.getPackageArchiveInfo(f.path, 0) }.getOrNull() ?: return false
        return info.packageName == ctx.packageName && info.versionName == version
    }
}
