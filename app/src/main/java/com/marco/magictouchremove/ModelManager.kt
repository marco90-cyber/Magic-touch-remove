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

enum class Kind { INPAINT, SELECT }

/** Un file ONNX: nome, dimensione attesa e indirizzi (il primo che risponde). */
class ModelFile(val name: String, val expected: Long, val minSize: Long, val urls: List<String>)

/** Un modello scegliibile dall'utente (può essere fatto di più file). */
class ModelOption(
    val id: String, val kind: Kind, val title: String, val tagline: String, val description: String,
    val chips: List<String>, val files: List<ModelFile>, val license: String,
    val sourceName: String, val sourceUrl: String, val release: String
) { val size: Long get() = files.sumOf { it.expected } }

/** Voce del file models.json pubblicato nel repository: l'ultima versione disponibile di un modello. */
class RemoteModel(
    val file: String, val version: Int, val size: Long, val sha256: String?,
    val minApp: Int, val notes: String?, val urls: List<String>
)

private const val REPO = "https://github.com/marco90-cyber/Magic-touch-remove/releases/download"
private const val MANIFEST_URL = "https://raw.githubusercontent.com/marco90-cyber/Magic-touch-remove/main/models.json"

val OPT_LAMA = ModelOption(
    "lama", Kind.INPAINT, "LaMa", "Il più preciso",
    "Ricostruisce lo sfondo con la massima qualità, anche su aree grandi e texture complesse. È il modello più pesante e un po' più lento.",
    listOf("Più preciso", "Più pesante", "Più lento"),
    listOf(ModelFile("lama_fp32.onnx", 208_044_816L, 150_000_000L,
        listOf("https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx", "$REPO/model-v1/lama_fp32.onnx"))),
    "Apache-2.0", "huggingface.co/Carve/LaMa-ONNX", "https://huggingface.co/Carve/LaMa-ONNX", "model-v1"
)
val OPT_MIGAN = ModelOption(
    "migan", Kind.INPAINT, "MI-GAN", "Leggero e veloce",
    "Rimozione rapida con un modello 7 volte più piccolo di LaMa. Ottimo per oggetti piccoli e medi, ideale su telefoni meno potenti o con poco spazio.",
    listOf("Più leggero", "Più veloce"),
    listOf(ModelFile("migan_pipeline_v2.onnx", 28_079_181L, 25_000_000L, listOf("$REPO/migan-v1/migan_pipeline_v2.onnx"))),
    "MIT", "huggingface.co/andraniksargsyan/migan", "https://huggingface.co/andraniksargsyan/migan", "migan-v1"
)
val OPT_MOBILESAM = ModelOption(
    "mobilesam", Kind.SELECT, "MobileSAM", "Leggero e veloce",
    "Selezione automatica istantanea e molto compatta. Funziona bene sulla maggior parte degli oggetti.",
    listOf("Più leggero", "Più veloce"),
    listOf(
        ModelFile("mobile_sam_image_encoder.onnx", 28_157_093L, 25_000_000L,
            listOf("https://huggingface.co/Acly/MobileSAM/resolve/main/mobile_sam_image_encoder.onnx", "$REPO/sam-v1/mobile_sam_image_encoder.onnx")),
        ModelFile("sam_mask_decoder_multi.onnx", 16_496_559L, 14_000_000L,
            listOf("https://huggingface.co/Acly/MobileSAM/resolve/main/sam_mask_decoder_multi.onnx", "$REPO/sam-v1/sam_mask_decoder_multi.onnx"))
    ),
    "MIT", "huggingface.co/Acly/MobileSAM", "https://huggingface.co/Acly/MobileSAM", "sam-v1"
)
val OPT_SAM2 = ModelOption(
    "sam2", Kind.SELECT, "SAM 2 Tiny", "Il più preciso",
    "Il Segment Anything 2 di Meta: bordi più netti e migliore con oggetti sottili o complessi. Più pesante e più lento nell'analisi della foto.",
    listOf("Più preciso", "Più pesante", "Più lento"),
    listOf(
        ModelFile("sam2_hiera_tiny.encoder.onnx", 134_261_315L, 120_000_000L, listOf("$REPO/sam2-v1/sam2_hiera_tiny.encoder.onnx")),
        ModelFile("sam2_hiera_tiny.decoder.onnx", 20_640_886L, 18_000_000L, listOf("$REPO/sam2-v1/sam2_hiera_tiny.decoder.onnx"))
    ),
    "Apache-2.0", "huggingface.co/vietanhdev/segment-anything-2-onnx-models", "https://huggingface.co/vietanhdev/segment-anything-2-onnx-models", "sam2-v1"
)
val ALL_OPTIONS = listOf(OPT_LAMA, OPT_MIGAN, OPT_MOBILESAM, OPT_SAM2)
private val ALL = ALL_OPTIONS.flatMap { it.files }

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

    private val _rev = MutableStateFlow(0)
    /** Cambia a ogni modifica dei modelli installati/scelti, per aggiornare la UI. */
    val rev: StateFlow<Int> = _rev
    private fun bump() { _rev.value = _rev.value + 1 }

    private fun fileOf(ctx: Context, m: ModelFile) = File(ctx.filesDir, "models/${m.name}")
    private fun versionFile(ctx: Context, m: ModelFile) = File(ctx.filesDir, "models/${m.name}.version")
    private fun has(ctx: Context, m: ModelFile) = fileOf(ctx, m).let { it.exists() && it.length() > m.minSize }
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("models", Context.MODE_PRIVATE)

    fun file(ctx: Context, f: ModelFile) = fileOf(ctx, f)
    fun installed(ctx: Context, o: ModelOption) = o.files.all { has(ctx, it) }
    fun installedSize(ctx: Context, o: ModelOption) = o.files.sumOf { fileOf(ctx, it).let { f -> if (f.exists()) f.length() else 0L } }
    fun options(kind: Kind) = ALL_OPTIONS.filter { it.kind == kind }

    /** Modello in uso: quello scelto se installato, altrimenti il primo installato (null se nessuno). */
    fun active(ctx: Context, kind: Kind): ModelOption? {
        val chosen = prefs(ctx).getString("active_$kind", null)
        val list = options(kind)
        return list.firstOrNull { it.id == chosen && installed(ctx, it) } ?: list.firstOrNull { installed(ctx, it) }
    }
    fun setActive(ctx: Context, o: ModelOption) {
        prefs(ctx).edit().putString("active_${o.kind}", o.id).apply()
        if (o.kind == Kind.INPAINT) Inpainter.release() else Segmenter.release()
        bump()
    }
    /** Il modello appena scaricato diventa quello in uso se non ce n'è già uno scelto e installato. */
    private fun autoActivate(ctx: Context) {
        for (k in Kind.values()) if (active(ctx, k) != null && prefs(ctx).getString("active_$k", null) == null)
            prefs(ctx).edit().putString("active_$k", active(ctx, k)!!.id).apply()
    }

    fun isReady(ctx: Context) = active(ctx, Kind.INPAINT) != null
    fun isSamReady(ctx: Context) = active(ctx, Kind.SELECT) != null
    private fun anyReady(ctx: Context) = isReady(ctx)

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
    fun updateAvailable(ctx: Context, o: ModelOption) = o.files.any { needsUpdate(ctx, it) }
    fun installedVersionOf(ctx: Context, o: ModelOption) = o.files.maxOf { installedVersion(ctx, it) }
    fun latestVersionOf(ctx: Context, o: ModelOption) = o.files.maxOf { usableRemote(ctx, it)?.version ?: 0 }
    fun notesOf(ctx: Context, o: ModelOption) = o.files.firstNotNullOfOrNull { usableRemote(ctx, it)?.notes }

    fun init(ctx: Context) {
        if (_state.value is ModelState.Downloading) return
        _state.value = if (anyReady(ctx)) ModelState.Ready else ModelState.Missing
        bump()
    }

    /** Elimina un modello. Ritorna false se è in corso un download. */
    fun delete(ctx: Context, o: ModelOption): Boolean {
        if (_state.value is ModelState.Downloading) return false
        Inpainter.release(); Segmenter.release()
        o.files.forEach { deleteFile(ctx, it) }
        _state.value = if (anyReady(ctx)) ModelState.Ready else ModelState.Missing
        bump()
        return true
    }

    private fun deleteFile(ctx: Context, m: ModelFile) {
        val f = fileOf(ctx, m)
        f.delete()
        File(f.path + ".part").delete()
        versionFile(ctx, m).delete()
    }

    /** Elimina tutti i modelli scaricati. Ritorna false se è in corso un download. */
    fun deleteAll(ctx: Context): Boolean {
        if (_state.value is ModelState.Downloading) return false
        Inpainter.release(); Segmenter.release()
        ALL.forEach { deleteFile(ctx, it) }
        prefs(ctx).edit().clear().apply()
        _state.value = ModelState.Missing
        bump()
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
                bump()
            } catch (e: Exception) {
                _checkFailed.value = true
            } finally {
                _checking.value = false
            }
        }
    }

    /** Scarica i modelli scelti (solo i file che mancano). */
    fun download(ctx: Context, chosen: Collection<ModelOption>) {
        if (_state.value is ModelState.Downloading) return
        val app = ctx.applicationContext
        val targets = chosen.flatMap { it.files }.distinct().filter { !has(app, it) }
        if (targets.isNotEmpty()) run(app, targets, replacing = false)
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
                    _state.value = if (anyReady(app)) ModelState.Ready else ModelState.Error(lastError)
                    autoActivate(app); bump()
                    return@launch
                }
                doneBefore += r?.size?.takeIf { it > 0 } ?: m.expected
            }
            _state.value = ModelState.Ready
            autoActivate(app); bump()
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
