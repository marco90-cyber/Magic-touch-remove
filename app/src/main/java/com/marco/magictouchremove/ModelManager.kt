package com.marco.magictouchremove

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

enum class Kind { INPAINT, SELECT, DEPTH }

val Kind.label: String get() = when (this) {
    Kind.INPAINT -> "Rimozione oggetti"
    Kind.SELECT -> "Selezione automatica"
    Kind.DEPTH -> "Profondità (sfocatura sfondo)"
}

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
    "sam21", Kind.SELECT, "SAM 2.1 Small", "Il più preciso",
    "Il Segment Anything 2.1 di Meta, versione Small: bordi più netti e risultati migliori con oggetti sottili, capelli o forme complesse. Più pesante di MobileSAM e un po' più lento nell'analisi della foto.",
    listOf("Più preciso", "Più pesante", "Più lento"),
    listOf(
        ModelFile("sam2.1_hiera_small.encoder.onnx", 138_018_779L, 125_000_000L, listOf("$REPO/sam21-v1/sam2.1_hiera_small.encoder.onnx")),
        ModelFile("sam2.1_hiera_small.decoder.onnx", 16_519_569L, 15_000_000L, listOf("$REPO/sam21-v1/sam2.1_hiera_small.decoder.onnx"))
    ),
    "Apache-2.0", "huggingface.co/vietanhdev/segment-anything-2.1-onnx-models", "https://huggingface.co/vietanhdev/segment-anything-2.1-onnx-models", "sam21-v1"
)
// --- IA generativa (Stable Diffusion 1.5 Inpainting): ricostruisce solo la selezione, nessun testo ---
private val SD_TE = ModelFile("sd15_text_encoder_fp16.onnx", 246_282_313L, 240_000_000L, listOf("$REPO/sd-v1/sd15_text_encoder_fp16.onnx"))
private val SD_VE = ModelFile("sd15_vae_encoder.onnx", 136_773_223L, 130_000_000L, listOf("$REPO/sd-v1/sd15_vae_encoder.onnx"))
private val SD_VD = ModelFile("sd15_vae_decoder.onnx", 265_178_078L, 250_000_000L, listOf("$REPO/sd-v1/sd15_vae_decoder.onnx"))
private val SD_VOCAB = ModelFile("clip_vocab.json", 1_059_962L, 1_000_000L, listOf("$REPO/sd-v1/clip_vocab.json"))
private val SD_MERGES = ModelFile("clip_merges.txt", 524_619L, 500_000L, listOf("$REPO/sd-v1/clip_merges.txt"))
private val SD_UNET = ModelFile("sd15_inpaint_unet_fp16.onnx", 1_720_761_519L, 1_700_000_000L, listOf("$REPO/sd-v1/sd15_inpaint_unet_fp16.onnx"))
private val SD_UNET_LCM = ModelFile("sd15_inpaint_lcm_unet_fp16.onnx", 1_720_783_260L, 1_700_000_000L, listOf("$REPO/sd-v1/sd15_inpaint_lcm_unet_fp16.onnx"))
private val SD_NSFW = ModelFile("nsfw_vit_fp32.onnx", 343_463_984L, 340_000_000L, listOf("$REPO/nsfw-v1/nsfw_vit_fp32.onnx"))
private val SD_SHARED = listOf(SD_TE, SD_VE, SD_VD, SD_VOCAB, SD_MERGES, SD_NSFW)

val OPT_SD = ModelOption(
    "sd15", Kind.INPAINT, "Stable Diffusion 1.5 · Generativo", "Il più creativo · sperimentale",
    "IA generativa a diffusione: ricostruisce lo sfondo con dettagli realistici in aree grandi o complesse (non si può scrivere un testo) e controlla che il risultato sia appropriato. " +
        "Molto pesante (~2,4 GB) e lenta: serve un telefono potente con molta RAM (almeno 8 GB). Non è il modello predefinito.",
    listOf("Generativo", "Sperimentale", "Molto pesante"),
    SD_SHARED + SD_UNET, "CreativeML OpenRAIL-M",
    "huggingface.co/stable-diffusion-v1-5/stable-diffusion-inpainting", "https://huggingface.co/stable-diffusion-v1-5/stable-diffusion-inpainting", "sd-v1"
)
val OPT_SDLCM = ModelOption(
    "sd15lcm", Kind.INPAINT, "Stable Diffusion 1.5 LCM · Generativo veloce", "Generativo più rapido · sperimentale",
    "Stessa IA generativa ma ottimizzata a 4 passaggi (LCM): molto più rapida di quella standard, con qualità leggermente inferiore. " +
        "Pesante (~2,4 GB): serve un telefono potente con molta RAM (almeno 8 GB). Non è il modello predefinito.",
    listOf("Generativo", "Sperimentale", "Più veloce del generativo standard"),
    SD_SHARED + SD_UNET_LCM, "CreativeML OpenRAIL-M + OpenRAIL++ (LCM-LoRA)",
    "huggingface.co/latent-consistency/lcm-lora-sdv1-5", "https://huggingface.co/latent-consistency/lcm-lora-sdv1-5", "sd-v1"
)
val OPT_DEPTH = ModelOption(
    "depth", Kind.DEPTH, "Depth Anything V2 Small", "Mappa di profondità",
    "Analizza la foto e capisce cosa è vicino e cosa è lontano: serve alla sfocatura dello sfondo, per sfocare in modo graduale come una vera fotocamera.",
    listOf("Sfocatura sfondo", "Leggero"),
    listOf(ModelFile("depth_anything_v2_small.onnx", 99_060_839L, 90_000_000L,
        listOf("https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model.onnx", "$REPO/depth-v1/depth_anything_v2_small.onnx"))),
    "Apache-2.0", "huggingface.co/depth-anything/Depth-Anything-V2-Small", "https://huggingface.co/depth-anything/Depth-Anything-V2-Small", "depth-v1"
)
val ALL_OPTIONS = listOf(OPT_LAMA, OPT_MIGAN, OPT_SD, OPT_SDLCM, OPT_MOBILESAM, OPT_SAM2, OPT_DEPTH)
private val ALL = ALL_OPTIONS.flatMap { it.files }.distinct()

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
    /** Spazio totale occupato (i file condivisi tra modelli contano una volta sola). */
    fun totalInstalledSize(ctx: Context) = ALL.sumOf { fileOf(ctx, it).let { f -> if (f.exists()) f.length() else 0L } }
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
        when (o.kind) { Kind.INPAINT -> Inpainter.release(); Kind.SELECT -> Segmenter.release(); Kind.DEPTH -> Depth.release() }
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

    /** Rimuove i file di modelli non più usati (SAM 2 Tiny) rimasti sul telefono. */
    private fun purgeLegacy(ctx: Context) {
        runCatching {
            File(ctx.filesDir, "models").listFiles()?.forEach { f ->
                val n = f.name
                if (n.startsWith("sam2_hiera_tiny")) { f.delete() }
            }
        }
    }

    fun init(ctx: Context) {
        purgeLegacy(ctx)
        if (_state.value is ModelState.Downloading) return
        _state.value = if (anyReady(ctx)) ModelState.Ready else ModelState.Missing
        bump()
    }

    /** Elimina un modello. Ritorna false se è in corso un download. */
    fun delete(ctx: Context, o: ModelOption): Boolean {
        if (_state.value is ModelState.Downloading) return false
        Inpainter.release(); Segmenter.release(); Depth.release()
        val keep = ALL_OPTIONS.filter { it !== o && installed(ctx, it) }.flatMap { it.files }.toSet()
        o.files.filter { it !in keep }.forEach { deleteFile(ctx, it) }
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
        Inpainter.release(); Segmenter.release(); Depth.release()
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

    /** Importa file di modello scaricati a mano (es. dalle Releases di GitHub). [onDone] riceve il riepilogo. */
    fun importFiles(ctx: Context, uris: List<android.net.Uri>, onDone: (String) -> Unit) {
        if (uris.isEmpty() || _state.value is ModelState.Downloading) return
        val app = ctx.applicationContext
        _state.value = ModelState.Downloading(0f)
        WorkKeeper.start(app, "Importazione dei modelli IA")
        scope.launch {
            val ok = mutableListOf<String>(); val bad = mutableListOf<String>()
            try {
                Inpainter.release(); Segmenter.release(); Depth.release()
                var total = 0L
                val items = uris.map { u ->
                    val (name, size) = app.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) (c.getString(0) ?: "") to c.getLong(1) else "" to 0L } ?: ("" to 0L)
                    total += size.coerceAtLeast(0)
                    Triple(u, name, size)
                }
                var doneBefore = 0L
                var lastPct = -1
                // copia un modello da un flusso: ritorna true se salvato
                fun store(m: ModelFile, inp: java.io.InputStream, label: String): Boolean {
                    val target = fileOf(app, m)
                    target.parentFile?.mkdirs()
                    val part = File(target.path + ".part")
                    try {
                        java.io.FileOutputStream(part).use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = inp.read(buf); if (n < 0) break
                                out.write(buf, 0, n); doneBefore += n
                                val f = (doneBefore.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 0.99f)
                                _state.value = ModelState.Downloading(f)
                                val pct = (f * 100).toInt()
                                if (pct != lastPct) { lastPct = pct; Notifier.progress(app, "Importazione dei modelli IA", "${m.name} · $pct%", pct) }
                            }
                        }
                        if (part.length() < m.minSize) { part.delete(); bad += "${m.name}: file incompleto"; return false }
                        target.delete()
                        File(target.path + ".part.url").delete()
                        if (!part.renameTo(target)) { bad += "${m.name}: impossibile salvare"; return false }
                        versionFile(app, m).writeText((usableRemote(app, m)?.version ?: 1).toString())
                        ok += m.name
                        return true
                    } catch (e: Exception) {
                        part.delete()
                        bad += "$label: ${e.message ?: e.javaClass.simpleName}"
                        return false
                    }
                }
                fun cleanName(n: String) = n.substringAfterLast('/').replace(Regex("\\s*\\(\\d+\\)(?=\\.[^.]+$)"), "")
                fun enoughSpace(size: Long) =
                    runCatching { android.os.StatFs(app.filesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE) >= size + 100_000_000L
                for ((u, rawName, size) in items) {
                    val clean = cleanName(rawName)
                    if (clean.endsWith(".zip", ignoreCase = true)) {
                        // pacchetto unico: si estraggono i file riconosciuti
                        try {
                            java.util.zip.ZipInputStream(app.contentResolver.openInputStream(u)!!.buffered(1 shl 20)).use { zin ->
                                while (true) {
                                    val e = zin.nextEntry ?: break
                                    if (e.isDirectory) continue
                                    val m = ALL.firstOrNull { it.name.equals(cleanName(e.name), ignoreCase = true) }
                                    if (m == null) { zin.closeEntry(); continue }
                                    if (!enoughSpace(m.expected)) { bad += "${m.name}: spazio insufficiente"; doneBefore += m.expected; continue }
                                    store(m, object : java.io.FilterInputStream(zin) { override fun close() {} }, m.name)
                                }
                            }
                        } catch (e: Exception) { bad += "$rawName: ${e.message ?: e.javaClass.simpleName}" }
                        continue
                    }
                    val m = ALL.firstOrNull { it.name.equals(clean, ignoreCase = true) }
                    if (m == null) {
                        bad += if (rawName.startsWith(".pending") || rawName.endsWith(".crdownload") || rawName.endsWith(".part") || rawName.endsWith(".tmp"))
                            "$rawName: download non completato, riscaricalo" else "$rawName: ignorato (non serve all'app)"
                        continue
                    }
                    if (size in 1 until m.minSize) { bad += "${m.name}: file incompleto"; doneBefore += size; continue }
                    if (!enoughSpace(size)) { bad += "${m.name}: spazio insufficiente"; doneBefore += size; continue }
                    runCatching { app.contentResolver.openInputStream(u)!!.use { store(m, it, m.name) } }
                        .onFailure { bad += "${m.name}: ${it.message ?: it.javaClass.simpleName}" }
                }
            } finally {
                _state.value = if (anyReady(app)) ModelState.Ready else ModelState.Missing
                autoActivate(app); bump()
                WorkKeeper.stop(app)
            }
            val missing = ALL_OPTIONS.filter { o -> !installed(app, o) && o.files.any { has(app, it) } }
                .map { o -> o.title + " incompleto, mancano: " + o.files.filter { !has(app, it) }.joinToString(", ") { it.name } }
            val msg = buildString {
                append(if (ok.isEmpty()) "Nessun modello importato." else "Importati ${ok.size} file: ${ok.joinToString(", ")}.")
                missing.forEach { append("\n$it") }
                if (bad.isNotEmpty()) append("\n" + bad.joinToString("\n"))
            }
            AppLog.log("IMPORT", msg.replace("\n", " | "))
            if (Notifier.downloadsOn(app) && !AppState.foreground) Notifier.done(app, "Importazione terminata", msg.lineSequence().first())
            withContext(Dispatchers.Main) { onDone(msg) }
        }
    }

    private fun run(app: Context, targets: List<ModelFile>, replacing: Boolean) {
        val total = targets.sumOf { m -> usableRemote(app, m)?.size?.takeIf { it > 0 } ?: m.expected }
        _updateError.value = null
        val need = targets.sumOf { it.expected }
        val free = runCatching { android.os.StatFs(app.filesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < need + 300_000_000L) {
            val msg = "spazio insufficiente: servono circa ${need / 1_000_000 + 300} MB liberi"
            _updateError.value = msg
            _state.value = if (anyReady(app)) ModelState.Ready else ModelState.Error(msg)
            return
        }
        _state.value = ModelState.Downloading(0f)
        WorkKeeper.start(app, "Download dei modelli IA")
        scope.launch {
          try {
            if (replacing) { Inpainter.release(); Segmenter.release(); Depth.release() }
            var lastPct = -1
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
                            val f = ((doneBefore + done).toFloat() / total).coerceIn(0f, 0.99f)
                            _state.value = ModelState.Downloading(f)
                            val pct = (f * 100).toInt()
                            if (pct != lastPct) { lastPct = pct; Notifier.progress(app, "Download dei modelli IA", "${m.name} · $pct%", pct) }
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
                    AppLog.log("DOWNLOAD", "non riuscito: $lastError")
                    if (Notifier.downloadsOn(app) && !AppState.foreground) Notifier.done(app, "Download non riuscito", lastError)
                    return@launch
                }
                doneBefore += r?.size?.takeIf { it > 0 } ?: m.expected
            }
            _state.value = ModelState.Ready
            autoActivate(app); bump()
            AppLog.log("DOWNLOAD", "completato")
            if (Notifier.downloadsOn(app) && !AppState.foreground) Notifier.done(app, "Download completato", "I modelli IA sono pronti.")
          } finally {
            WorkKeeper.stop(app)
          }
        }
    }

    private fun fetch(ctx: Context, m: ModelFile, url: String, minSize: Long, sha256: String?, onProgress: (Long) -> Unit) {
        val target = fileOf(ctx, m)
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        val marker = File(target.path + ".part.url")
        // si riprende un download interrotto solo se l'indirizzo è lo stesso
        if (part.exists() && (!marker.exists() || marker.readText() != url)) part.delete()
        marker.writeText(url)
        val resumeFrom = if (part.exists()) part.length() else 0L
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "MagicTouchRemove/1.0")
        if (resumeFrom > 0) conn.setRequestProperty("Range", "bytes=$resumeFrom-")
        var drop = false
        try {
            val code = conn.responseCode
            if (code == 416) { drop = true; error("riprova") }
            if (code !in 200..299) error("HTTP $code")
            val append = code == 206 && resumeFrom > 0
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastReport = 0L
            if (append) {
                part.inputStream().use { inp ->
                    val b = ByteArray(1 shl 20)
                    while (true) { val k = inp.read(b); if (k < 0) break; digest.update(b, 0, k) }
                }
                done = resumeFrom
            } else if (part.exists()) part.delete()
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { out ->
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
            if (part.length() < minSize) { drop = true; error("file incompleto") }
            if (sha256 != null) {
                val got = digest.digest().joinToString("") { "%02x".format(it) }
                if (!got.equals(sha256, ignoreCase = true)) { drop = true; error("controllo di integrità fallito") }
            }
            target.delete()
            if (!part.renameTo(target)) error("impossibile salvare il modello")
            marker.delete()
        } finally {
            conn.disconnect()
            // in caso di interruzione il file parziale resta, così il download riprende da dove era arrivato
            if (drop) { part.delete(); marker.delete() }
        }
    }
}
