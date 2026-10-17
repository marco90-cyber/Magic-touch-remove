package com.marco.magictouchremove

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Una versione dell'app pubblicata nelle Releases di GitHub. */
class AppRelease(val code: Int, val name: String, val url: String, val size: Long, val notes: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    data class Ready(val release: AppRelease, val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * Aggiornamento dell'app stessa: legge l'ultima Release del repository, scarica l'APK e avvia
 * l'installazione di Android sopra l'app esistente (stessa chiave di firma → i dati e i modelli restano).
 */
object AppUpdater {
    private const val API = "https://api.github.com/repos/marco90-cyber/Magic-touch-remove/releases/latest"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** Codice della versione per cui l'utente ha scelto «Più tardi» (vale solo finché l'app resta aperta). */
    private val _postponed = MutableStateFlow(0)
    val postponed: StateFlow<Int> = _postponed
    fun postpone(code: Int) { _postponed.value = code }
    fun clearPostponed() { _postponed.value = 0 }

    private var autoChecked = false
    private var lastRelease: AppRelease? = null

    fun currentCode(ctx: Context): Int =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt() }.getOrDefault(0)

    fun currentName(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    /** Controllo silenzioso all'apertura dell'app (una volta sola). */
    fun checkOnLaunch(ctx: Context) {
        if (autoChecked) return
        autoChecked = true
        check(ctx, silent = true)
    }

    fun check(ctx: Context, silent: Boolean = false) {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Ready) return
        val app = ctx.applicationContext
        if (!silent) _state.value = UpdateState.Checking
        scope.launch {
            try {
                val conn = URL(API).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val text = try {
                    if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally { conn.disconnect() }
                val o = JSONObject(text)
                val tag = o.getString("tag_name").removePrefix("v")            // es. "1.12"
                val code = tag.split(".").getOrNull(1)?.toIntOrNull() ?: error("versione non riconosciuta")
                val assets = o.getJSONArray("assets")
                var url: String? = null
                var size = 0L
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) {
                        url = a.getString("browser_download_url"); size = a.optLong("size", 0L); break
                    }
                }
                val rel = url?.let { AppRelease(code, tag, it, size, o.optString("body").trim()) }
                _state.value = if (rel != null && code > currentCode(app)) {
                    lastRelease = rel
                    UpdateState.Available(rel)
                } else UpdateState.UpToDate
            } catch (e: Exception) {
                _state.value = if (silent) UpdateState.Idle else UpdateState.Error("Controllo non riuscito (sei offline?)")
            }
        }
    }

    /** Scarica l'APK della versione disponibile; a fine download tenta subito l'installazione. */
    fun download(ctx: Context) {
        val app = ctx.applicationContext
        val rel = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.Error -> lastRelease
            else -> null
        } ?: return
        _state.value = UpdateState.Downloading(rel, 0f)
        scope.launch {
            val dir = File(app.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "MagicTouchRemove-v${rel.name}.apk")
            try {
                val conn = URL(rel.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                try {
                    if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: rel.size
                    var done = 0L
                    var last = 0L
                    conn.inputStream.use { input ->
                        target.outputStream().use { out ->
                            val buf = ByteArray(128 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0 && done - last > 256 * 1024) {
                                    last = done
                                    _state.value = UpdateState.Downloading(rel, (done.toFloat() / total).coerceIn(0f, 0.99f))
                                }
                            }
                        }
                    }
                } finally { conn.disconnect() }
                if (rel.size > 0 && target.length() != rel.size) error("file incompleto")
                _state.value = UpdateState.Ready(rel, target)
                if (canInstall(app)) install(app)
            } catch (e: Exception) {
                target.delete()
                _state.value = UpdateState.Error("Download non riuscito: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Apre l'impostazione Android «Installa app sconosciute» per questa app. */
    fun openInstallPermissionSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Avvia l'installazione dell'APK scaricato (Android chiede conferma all'utente). */
    fun install(ctx: Context) {
        val s = _state.value as? UpdateState.Ready ?: return
        if (!canInstall(ctx)) { openInstallPermissionSettings(ctx); return }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", s.file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun dismissError() { if (_state.value is UpdateState.Error) _state.value = lastRelease?.let { UpdateState.Available(it) } ?: UpdateState.Idle }
}
