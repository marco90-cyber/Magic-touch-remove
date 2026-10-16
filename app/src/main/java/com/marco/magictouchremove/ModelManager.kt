package com.marco.magictouchremove

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val progress: Float) : ModelState
    data object Ready : ModelState
    data class Error(val message: String) : ModelState
}

/** Scarica e conserva il modello LaMa (ONNX, ~200 MB) nella memoria interna dell'app. */
object ModelManager {
    private const val MIN_SIZE = 150_000_000L
    private const val FALLBACK_SIZE = 208_000_000L
    private val URLS = listOf(
        "https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx",
        "https://github.com/marco90-cyber/Magic-touch-remove/releases/download/model-v1/lama_fp32.onnx"
    )

    private val _state = MutableStateFlow<ModelState>(ModelState.Missing)
    val state: StateFlow<ModelState> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun file(ctx: Context) = File(ctx.filesDir, "models/lama_fp32.onnx")
    fun isReady(ctx: Context) = file(ctx).let { it.exists() && it.length() > MIN_SIZE }

    fun init(ctx: Context) {
        if (_state.value is ModelState.Downloading) return
        _state.value = if (isReady(ctx)) ModelState.Ready else ModelState.Missing
    }

    fun download(ctx: Context) {
        if (_state.value is ModelState.Downloading || isReady(ctx)) return
        val app = ctx.applicationContext
        _state.value = ModelState.Downloading(0f)
        scope.launch {
            var lastError = "download non riuscito"
            for (url in URLS) {
                try {
                    fetch(app, url)
                    _state.value = ModelState.Ready
                    return@launch
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    _state.value = ModelState.Downloading(0f)
                }
            }
            _state.value = ModelState.Error(lastError)
        }
    }

    private fun fetch(ctx: Context, url: String) {
        val target = file(ctx)
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "MagicTouchRemove/1.0")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: FALLBACK_SIZE
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = (done * 100 / total).toInt().coerceIn(0, 99)
                        if (pct != lastPct) {
                            lastPct = pct
                            _state.value = ModelState.Downloading(pct / 100f)
                        }
                    }
                }
            }
            if (part.length() < MIN_SIZE) error("file incompleto")
            target.delete()
            if (!part.renameTo(target)) error("impossibile salvare il modello")
        } finally {
            conn.disconnect()
            if (part.exists() && !target.exists()) part.delete()
        }
    }
}
