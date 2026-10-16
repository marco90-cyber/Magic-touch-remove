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

/** Un modello ONNX da scaricare: nome file, dimensione attesa e indirizzi (il primo che risponde). */
private class ModelFile(val name: String, val expected: Long, val minSize: Long, val urls: List<String>)

private const val REPO = "https://github.com/marco90-cyber/Magic-touch-remove/releases/download"

private val LAMA = ModelFile(
    "lama_fp32.onnx", 208_044_816L, 150_000_000L,
    listOf("https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx", "$REPO/model-v1/lama_fp32.onnx")
)
private val SAM_ENCODER = ModelFile(
    "mobile_sam_image_encoder.onnx", 28_157_093L, 25_000_000L,
    listOf("https://huggingface.co/Acly/MobileSAM/resolve/main/mobile_sam_image_encoder.onnx", "$REPO/sam-v1/mobile_sam_image_encoder.onnx")
)
private val SAM_DECODER = ModelFile(
    "sam_mask_decoder_multi.onnx", 16_496_559L, 14_000_000L,
    listOf("https://huggingface.co/Acly/MobileSAM/resolve/main/sam_mask_decoder_multi.onnx", "$REPO/sam-v1/sam_mask_decoder_multi.onnx")
)
private val ALL = listOf(LAMA, SAM_ENCODER, SAM_DECODER)

/**
 * Scarica e conserva nella memoria interna i modelli IA:
 * LaMa (rimozione oggetti, ~208 MB) e MobileSAM (selezione automatica, ~45 MB).
 */
object ModelManager {
    private val _state = MutableStateFlow<ModelState>(ModelState.Missing)
    val state: StateFlow<ModelState> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun fileOf(ctx: Context, m: ModelFile) = File(ctx.filesDir, "models/${m.name}")
    private fun has(ctx: Context, m: ModelFile) = fileOf(ctx, m).let { it.exists() && it.length() > m.minSize }

    fun file(ctx: Context) = fileOf(ctx, LAMA)
    fun samEncoder(ctx: Context) = fileOf(ctx, SAM_ENCODER)
    fun samDecoder(ctx: Context) = fileOf(ctx, SAM_DECODER)

    fun isReady(ctx: Context) = has(ctx, LAMA)
    fun isSamReady(ctx: Context) = has(ctx, SAM_ENCODER) && has(ctx, SAM_DECODER)
    private fun allReady(ctx: Context) = ALL.all { has(ctx, it) }

    fun init(ctx: Context) {
        if (_state.value is ModelState.Downloading) return
        _state.value = if (allReady(ctx)) ModelState.Ready else ModelState.Missing
    }

    /** Scarica solo i modelli che mancano. */
    fun download(ctx: Context) {
        if (_state.value is ModelState.Downloading || allReady(ctx)) return
        val app = ctx.applicationContext
        val missing = ALL.filter { !has(app, it) }
        val total = missing.sumOf { it.expected }
        _state.value = ModelState.Downloading(0f)
        scope.launch {
            var doneBefore = 0L
            for (m in missing) {
                var ok = false
                var lastError = "download non riuscito"
                for (url in m.urls) {
                    try {
                        fetch(app, m, url) { done -> _state.value = ModelState.Downloading(((doneBefore + done).toFloat() / total).coerceIn(0f, 0.99f)) }
                        ok = true
                        break
                    } catch (e: Exception) {
                        lastError = "${m.name}: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                if (!ok) { _state.value = ModelState.Error(lastError); return@launch }
                doneBefore += m.expected
            }
            _state.value = ModelState.Ready
        }
    }

    private fun fetch(ctx: Context, m: ModelFile, url: String, onProgress: (Long) -> Unit) {
        val target = fileOf(ctx, m)
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "MagicTouchRemove/1.0")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            var done = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 512 * 1024) { lastReport = done; onProgress(done) }
                    }
                }
            }
            if (part.length() < m.minSize) error("file incompleto")
            target.delete()
            if (!part.renameTo(target)) error("impossibile salvare il modello")
        } finally {
            conn.disconnect()
            if (part.exists() && !target.exists()) part.delete()
        }
    }
}
