package com.marco.magictouchremove

import android.content.Context
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
import java.security.MessageDigest

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val progress: Float) : ModelState
    data object Ready : ModelState
    data class Error(val message: String) : ModelState
}

/** Un modello ONNX da scaricare: nome file, dimensione attesa e indirizzi della versione 1 (il primo che risponde). */
private class ModelFile(
    val name: String, val expected: Long, val minSize: Long, val urls: List<String>,
    val title: String, val role: String, val version: String, val license: String
)

/** Voce del file models.json pubblicato nel repository: l'ultima versione disponibile di un modello. */
class RemoteModel(
    val file: String, val version: Int, val size: Long, val sha256: String?,
    val minApp: Int, val notes: String?, val urls: List<String>
)

/** Dati mostrati nella schermata Informazioni. */
class ModelInfo(
    val title: String, val role: String, val version: String, val license: String, val sizeBytes: Long, val present: Boolean,
    val fileName: String, val source: String, val sourceUrl: String, val mirror: String,
    val installed: Int, val latest: Int, val update: Boolean, val notes: String?
)

private const val REPO = "https://github.com/marco90-cyber/Magic-touch-remove/releases/download"
private const val MANIFEST_URL = "https://raw.githubusercontent.com/marco90-cyber/Magic-touch-remove/main/models.json"

private val LAMA = ModelFile(
    "lama_fp32.onnx", 208_044_816L, 150_000_000L,
    listOf("https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx", "$REPO/model-v1/lama_fp32.onnx"),
    "LaMa (big-lama)", "Rimozione oggetti", "ONNX fp32 · release model-v1", "Apache-2.0"
)
private val SAM_ENCODER = ModelFile(
    "mobile_sam_image_encoder.onnx", 28_157_093L, 25_000_000L,
    listOf("https://huggingface.co/Acly/MobileSAM/resolve/main/mobile_sam_image_encoder.onnx", "$REPO/sam-v1/mobile_sam_image_encoder.onnx"),
    "MobileSAM · encoder", "Selezione automatica", "ONNX · release sam-v1", "MIT"
)
private val SAM_DECODER = ModelFile(
    "sam_mask_decoder_multi.onnx", 16_496_559L, 14_000_000L,
    listOf("https://huggingface.co/Acly/MobileSAM/resolve/main/sam_mask_decoder_multi.onnx", "$REPO/sam-v1/sam_mask_decoder_multi.onnx"),
    "MobileSAM · decoder", "Selezione automatica", "ONNX multi-maschera · release sam-v1", "MIT"
)
private val ALL = listOf(LAMA, SAM_ENCODER, SAM_DECODER)

/**
 * Scarica, conserva e aggiorna nella memoria interna i modelli IA:
 * LaMa (rimozione oggetti, ~208 MB) e MobileSAM (selezione automatica, ~45 MB).
 *
 * Le versioni disponibili sono elencate in models.json nel repository (controllo all'avvio e dalle Informazioni).
 * Un aggiornamento viene proposto solo se l'app è abbastanza recente (campo minApp) e viene verificato con SHA-256.
 */
object ModelManager {
    private val _state = MutableStateFlow<ModelState>(ModelState.Missing)
    val state: StateFlow<ModelState> = _state
    private val _remote = MutableStateFlow<Map<String, RemoteModel>>(emptyMap())
    val remote: StateFlow<Map<String, RemoteModel>> = _remote
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking
    private val _checkFailed = MutableStateFlow(false)
    val checkFailed: StateFlow<Boolean> = _checkFailed
    private val _updateError = MutableStateFlow<String?>(null)
    val updateError: StateFlow<String?> = _updateError
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun fileOf(ctx: Context, m: ModelFile) = File(ctx.filesDir, "models/${m.name}")
    private fun versionFile(ctx: Context, m: ModelFile) = File(ctx.filesDir, "models/${m.name}.version")
    private fun has(ctx: Context, m: ModelFile) = fileOf(ctx, m).let { it.exists() && it.length() > m.minSize }

    fun file(ctx: Context) = fileOf(ctx, LAMA)
    fun samEncoder(ctx: Context) = fileOf(ctx, SAM_ENCODER)
    fun samDecoder(ctx: Context) = fileOf(ctx, SAM_DECODER)

    fun isReady(ctx: Context) = has(ctx, LAMA)
    fun isSamReady(ctx: Context) = has(ctx, SAM_ENCODER) && has(ctx, SAM_DECODER)
    private fun allReady(ctx: Context) = ALL.all { has(ctx, it) }

    private fun appCode(ctx: Context): Int =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt() }.getOrDefault(1)

    /** 0 = non installato; i modelli scaricati prima del controllo versioni sono la v1. */
    private fun installedVersion(ctx: Context, m: ModelFile): Int =
        if (!has(ctx, m)) 0 else runCatching { versionFile(ctx, m).readText().trim().toInt() }.getOrDefault(1)

    private fun usableRemote(ctx: Context, m: ModelFile): RemoteModel? =
        _remote.value[m.name]?.takeIf { it.minApp <= appCode(ctx) }

    private fun needsUpdate(ctx: Context, m: ModelFile) =
        has(ctx, m) && (usableRemote(ctx, m)?.version ?: 0) > installedVersion(ctx, m)

    fun updateAvailable(ctx: Context) = ALL.any { needsUpdate(ctx, it) }

    fun init(ctx: Context) {
        if (_state.value is ModelState.Downloading) return
        _state.value = if (allReady(ctx)) ModelState.Ready else ModelState.Missing
    }

    fun infos(ctx: Context): List<ModelInfo> = ALL.map {
        val f = fileOf(ctx, it)
        val r = usableRemote(ctx, it)
        ModelInfo(
            it.title, it.role, it.version, it.license, if (f.exists()) f.length() else 0L, has(ctx, it),
            it.name,
            it.urls.first().substringAfter("https://").substringBefore("/resolve"),
            it.urls.first().substringBefore("/resolve"),
            "github.com/marco90-cyber/Magic-touch-remove › Releases › " + it.urls.last().substringAfter("/download/").substringBefore("/"),
            installedVersion(ctx, it), r?.version ?: 0, needsUpdate(ctx, it), r?.notes
        )
    }

    /** Elimina tutti i modelli scaricati. Ritorna false se è in corso un download. */
    fun deleteAll(ctx: Context): Boolean {
        if (_state.value is ModelState.Downloading) return false
        Inpainter.release()
        Segmenter.release()
        ALL.forEach {
            val f = fileOf(ctx, it)
            f.delete()
            File(f.path + ".part").delete()
            versionFile(ctx, it).delete()
        }
        _state.value = ModelState.Missing
        return true
    }

    /** Legge models.json dal repository e ricorda le ultime versioni disponibili. */
    fun checkUpdates(ctx: Context) {
        if (_checking.value) return
        _checking.value = true
        _checkFailed.value = false
        scope.launch {
            try {
                val conn = URL("$MANIFEST_URL?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Cache-Control", "no-cache")
                val text = try {
                    if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally { conn.disconnect() }
                val arr = JSONObject(text).getJSONArray("models")
                val map = HashMap<String, RemoteModel>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val u = o.getJSONArray("urls")
                    val file = o.getString("file")
                    map[file] = RemoteModel(
                        file, o.getInt("version"), o.optLong("size", 0L),
                        o.optString("sha256").ifBlank { null }, o.optInt("minApp", 1),
                        o.optString("notes").ifBlank { null }, List(u.length()) { u.getString(it) }
                    )
                }
                _remote.value = map
            } catch (e: Exception) {
                _checkFailed.value = true
            } finally {
                _checking.value = false
            }
        }
    }

    /** Scarica i modelli che mancano. */
    fun download(ctx: Context) {
        if (_state.value is ModelState.Downloading || allReady(ctx)) return
        val app = ctx.applicationContext
        run(app, ALL.filter { !has(app, it) }, replacing = false)
    }

    /** Aggiorna i modelli per cui esiste una versione più recente. */
    fun update(ctx: Context) {
        if (_state.value is ModelState.Downloading) return
        val app = ctx.applicationContext
        val targets = ALL.filter { needsUpdate(app, it) }
        if (targets.isNotEmpty()) run(app, targets, replacing = true)
    }

    private fun run(app: Context, targets: List<ModelFile>, replacing: Boolean) {
        val total = targets.sumOf { m -> usableRemote(app, m)?.size?.takeIf { it > 0 } ?: m.expected }
        _updateError.value = null
        _state.value = ModelState.Downloading(0f)
        scope.launch {
            if (replacing) { Inpainter.release(); Segmenter.release() }
            var doneBefore = 0L
            for (m in targets) {
                val r = usableRemote(app, m)
                // prima la versione più recente del repository (verificata con SHA-256), poi la v1 di riserva
                val attempts = buildList {
                    r?.urls?.forEach { add(Triple(it, r.version, r.sha256)) }
                    m.urls.forEach { add(Triple(it, 1, null as String?)) }
                }
                val minSize = if (r != null && r.size > 0) minOf(m.minSize, (r.size * 0.9).toLong()) else m.minSize
                var ok = false
                var lastError = "download non riuscito"
                for ((url, ver, sha) in attempts) {
                    try {
                        fetch(app, m, url, minSize, sha) { done ->
                            _state.value = ModelState.Downloading(((doneBefore + done).toFloat() / total).coerceIn(0f, 0.99f))
                        }
                        versionFile(app, m).writeText(ver.toString())
                        ok = true
                        break
                    } catch (e: Exception) {
                        lastError = "${m.name}: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                if (!ok) {
                    _updateError.value = lastError
                    _state.value = if (allReady(app)) ModelState.Ready else ModelState.Error(lastError)
                    return@launch
                }
                doneBefore += r?.size?.takeIf { it > 0 } ?: m.expected
            }
            _state.value = ModelState.Ready
        }
    }

    private fun fetch(ctx: Context, m: ModelFile, url: String, minSize: Long, sha256: String?, onProgress: (Long) -> Unit) {
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
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (done - lastReport > 512 * 1024) { lastReport = done; onProgress(done) }
                    }
                }
            }
            if (part.length() < minSize) error("file incompleto")
            if (sha256 != null) {
                val got = digest.digest().joinToString("") { "%02x".format(it) }
                if (!got.equals(sha256, ignoreCase = true)) error("controllo di integrità fallito")
            }
            target.delete()
            if (!part.renameTo(target)) error("impossibile salvare il modello")
        } finally {
            conn.disconnect()
            if (part.exists()) part.delete()
        }
    }
}
