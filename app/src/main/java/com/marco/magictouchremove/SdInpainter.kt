package com.marco.magictouchremove

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.EnumSet
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Tokenizzatore CLIP (BPE), identico a quello di Stable Diffusion.
 * Verificato in CI contro il tokenizzatore originale (tools/sd/sdref.py).
 */
class ClipTokenizer(vocabJson: String, mergesText: String) {
    private val enc = HashMap<String, Int>()
    private val ranks = HashMap<String, Int>()
    private val b2u = HashMap<Int, String>()
    private val cache = HashMap<String, List<String>>()
    private val pat = Regex(
        "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",
        RegexOption.IGNORE_CASE
    )

    init {
        val o = org.json.JSONObject(vocabJson)
        for (k in o.keys()) enc[k] = o.getInt(k)
        val lines = mergesText.split("\n")
        for (i in 1..(49152 - 256 - 2)) if (i < lines.size) ranks[lines[i]] = i - 1
        val bs = ArrayList<Int>()
        for (c in '!'.code..'~'.code) bs.add(c)
        for (c in 0xA1..0xAC) bs.add(c)
        for (c in 0xAE..0xFF) bs.add(c)
        var n = 0
        for (b in 0 until 256) {
            if (b in bs) b2u[b] = String(Character.toChars(b))
            else { b2u[b] = String(Character.toChars(256 + n)); n++ }
        }
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        // simboli = caratteri Unicode, l'ultimo con "</w>"
        val cps = token.codePoints().toArray()
        val word = ArrayList<String>()
        for (i in cps.indices) {
            val s = String(Character.toChars(cps[i]))
            word.add(if (i == cps.size - 1) "$s</w>" else s)
        }
        while (word.size > 1) {
            var best = Int.MAX_VALUE
            var bi = -1
            for (i in 0 until word.size - 1) {
                val r = ranks[word[i] + " " + word[i + 1]]
                if (r != null && r < best) { best = r; bi = i }
            }
            if (bi < 0) break
            word[bi] = word[bi] + word[bi + 1]
            word.removeAt(bi + 1)
        }
        cache[token] = word
        return word
    }

    fun encode(text: String, n: Int = 77): IntArray {
        val clean = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").lowercase()
        val ids = ArrayList<Int>()
        ids.add(49406)
        for (m in pat.findAll(clean)) {
            val sb = StringBuilder()
            for (b in m.value.toByteArray(Charsets.UTF_8)) sb.append(b2u[b.toInt() and 255])
            for (p in bpe(sb.toString())) enc[p]?.let { ids.add(it) }
        }
        val out = IntArray(n) { 49407 }
        val len = minOf(ids.size, n - 1)
        for (i in 0 until len) out[i] = ids[i]
        out[len] = 49407
        return out
    }
}

/**
 * Inpainting generativo con Stable Diffusion 1.5 Inpainting (ONNX fp16) — sperimentale.
 * Standard: 20 passi DDIM con guida del testo (7,5). LCM: 4 passi senza guida.
 * Ogni sottomodello viene caricato solo quando serve e subito rilasciato, per contenere la RAM.
 */
class SdInpainter(private val paths: Map<String, String>, private val lcm: Boolean, private val npu: Boolean, private val lamaPath: String? = null) : Inpainter() {

    private fun path(name: String) = paths[name] ?: error("file mancante: $name")
    private val unetName = if (lcm) "sd15_inpaint_lcm_unet_fp16.onnx" else "sd15_inpaint_unet_fp16.onnx"

    private fun session(name: String): OrtSession {
        SdDiag.mark("carico $name")
        val o = OrtSession.SessionOptions()
        o.setMemoryPatternOptimization(false)
        o.setCPUArenaAllocator(false)
        o.setIntraOpNumThreads(4)
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        if (npu) runCatching { o.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16)) }
        return env.createSession(path(name), o).also { SdDiag.mark("caricato $name") }
    }

    private fun progress(s: String) { SdDiag.mark(s); Inpainter.onProgress?.invoke(s) }
    private fun checkCancel() { if (Inpainter.cancelRequested) throw CancellationException("annullato") }

    private fun embed(s: OrtSession, tok: ClipTokenizer, text: String): FloatArray {
        val ids = tok.encode(text)
        return OnnxTensor.createTensor(env, IntBuffer.wrap(ids), longArrayOf(1, 77)).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { r ->
                val fb = (r[0] as OnnxTensor).floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
    }

    override fun run512(px: IntArray, hole: BooleanArray, n: Int): IntArray =
        try { runSd(px, hole, n) } finally { SdDiag.clear() }

    private fun runSd(px: IntArray, hole: BooleanArray, n: Int): IntArray {
        val plane = n * n
        val lat = n / 8
        val lplane = lat * lat
        checkCancel()
        progress("IA generativa · preparazione del testo…")
        val tok = ClipTokenizer(File(path("clip_vocab.json")).readText(), File(path("clip_merges.txt")).readText())
        // testo vuoto = «ricostruisci lo sfondo»: si parte dal riempimento di LaMa e si rifinisce; con un testo si genera da zero
        val userText = Inpainter.prompt.trim()
        val rebuild = userText.isEmpty()
        val text = if (rebuild) "empty background, seamless natural continuation of the surrounding scene, no objects" else userText
        val (cond, unc) = session("sd15_text_encoder_fp16.onnx").use { s ->
            val c = embed(s, tok, text)
            c to (if (!lcm) embed(s, tok, "object, person, animal, text, logo, watermark, blurry") else null)
        }

        // immagine con il buco azzerato → latente
        checkCancel()
        progress("IA generativa · analisi della foto…")
        val img = FloatArray(3 * plane)
        for (i in 0 until plane) {
            if (hole[i]) continue
            val p = px[i]
            img[i] = ((p shr 16) and 255) / 127.5f - 1f
            img[plane + i] = ((p shr 8) and 255) / 127.5f - 1f
            img[2 * plane + i] = (p and 255) / 127.5f - 1f
        }
        // base: riempimento di LaMa (solo se si ricostruisce lo sfondo e LaMa è scaricato)
        var base: IntArray? = null
        if (rebuild && lamaPath != null) {
            progress("IA generativa · sfondo di base…")
            base = runCatching {
                val l = LamaInpainter(lamaPath)
                try { l.run512(px, hole, n) } finally { l.close() }
            }.onFailure { AppLog.log("IA", "base LaMa non riuscita: ${it.message}") }.getOrNull()
        }
        fun encode(s: OrtSession, data: FloatArray): FloatArray =
            OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, n.toLong(), n.toLong())).use { t ->
                s.run(mapOf(s.inputNames.first() to t)).use { r ->
                    val fb = (r[0] as OnnxTensor).floatBuffer
                    FloatArray(fb.remaining()).also { fb.get(it) }
                }
            }
        var z0: FloatArray? = null
        val ml = session("sd15_vae_encoder.onnx").use { s ->
            val m = encode(s, img)
            if (base != null) {
                val b = base!!
                val full = FloatArray(3 * plane)
                for (i in 0 until plane) {
                    val p = if (hole[i]) b[i] else px[i]
                    full[i] = ((p shr 16) and 255) / 127.5f - 1f
                    full[plane + i] = ((p shr 8) and 255) / 127.5f - 1f
                    full[2 * plane + i] = (p and 255) / 127.5f - 1f
                }
                z0 = encode(s, full)
            }
            m
        }
        // maschera a risoluzione dei latenti (max-pooling 8×8)
        val m64 = FloatArray(lplane)
        for (y in 0 until lat) for (x in 0 until lat) {
            var any = false
            loop@ for (yy in 0 until 8) for (xx in 0 until 8) if (hole[(y * 8 + yy) * n + x * 8 + xx]) { any = true; break@loop }
            if (any) m64[y * lat + x] = 1f
        }

        // schedulazione
        val betas = DoubleArray(1000) { val s0 = sqrt(0.00085); val s1 = sqrt(0.012); val v = s0 + (s1 - s0) * it / 999.0; v * v }
        val acp = DoubleArray(1000)
        var run = 1.0
        for (i in 0 until 1000) { run *= (1.0 - betas[i]); acp[i] = run }
        val steps = if (lcm) 4 else 20
        val timesteps: IntArray = if (lcm) {
            val origin = IntArray(50) { (it + 1) * 20 - 1 }.reversedArray()
            IntArray(steps) { origin[floor(it * 50.0 / steps).toInt()] }
        } else {
            val st = 1000 / steps
            IntArray(steps) { (steps - 1 - it) * st + 1 }
        }
        val rng = Random(System.nanoTime())
        var x = FloatArray(4 * lplane) { rng.nextGaussian().toFloat() }
        // con la base di LaMa si salta la parte iniziale: l'IA aggiunge solo dettaglio e texture
        val start = if (z0 == null) 0 else if (lcm) 2 else 8
        if (z0 != null) {
            val aS = acp[timesteps[start]]
            val zz = z0!!
            x = FloatArray(x.size) { (sqrt(aS) * zz[it] + sqrt(1 - aS) * x[it]).toFloat() }
        }
        val guidance = 7.5f

        session(unetName).use { unet ->
            for ((i, t) in timesteps.withIndex()) {
                if (i < start) continue
                checkCancel()
                progress("IA generativa · passo ${i - start + 1} di ${steps - start}")
                // un solo campione per volta (batch 1): metà della RAM rispetto a calcolare insieme testo e «vuoto»
                fun predict(ehs: FloatArray): FloatArray {
                    val inp = FloatArray(9 * lplane)
                    System.arraycopy(x, 0, inp, 0, 4 * lplane)
                    System.arraycopy(m64, 0, inp, 4 * lplane, lplane)
                    System.arraycopy(ml, 0, inp, 5 * lplane, 4 * lplane)
                    return OnnxTensor.createTensor(env, FloatBuffer.wrap(inp), longArrayOf(1, 9, lat.toLong(), lat.toLong())).use { ts ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(t.toFloat())), longArrayOf(1)).use { tt ->
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(ehs), longArrayOf(1, 77, 768)).use { te ->
                                unet.run(mapOf("sample" to ts, "timestep" to tt, "encoder_hidden_states" to te)).use { r ->
                                    val fb = (r[0] as OnnxTensor).floatBuffer
                                    FloatArray(fb.remaining()).also { fb.get(it) }
                                }
                            }
                        }
                    }
                }
                val eps = if (lcm) predict(cond) else {
                    val eu = predict(unc!!)
                    checkCancel()
                    val ec = predict(cond)
                    FloatArray(4 * lplane) { eu[it] + guidance * (ec[it] - eu[it]) }
                }
                val aT = acp[t]
                val x0 = DoubleArray(eps.size) { (x[it] - sqrt(1 - aT) * eps[it]) / sqrt(aT) }
                if (lcm) {
                    val last = i == steps - 1
                    val st = t * 10.0
                    val cSkip = 0.25 / (st * st + 0.25)
                    val cOut = st / sqrt(st * st + 0.25)
                    val nxt = if (!last) timesteps[i + 1] else -1
                    val aP = if (nxt >= 0) acp[nxt] else 1.0
                    x = FloatArray(x.size) {
                        val den = cOut * x0[it] + cSkip * x[it]
                        if (last) den.toFloat() else (sqrt(aP) * den + sqrt(1 - aP) * rng.nextGaussian()).toFloat()
                    }
                } else {
                    val prev = t - 1000 / steps
                    val aP = if (prev >= 0) acp[prev] else acp[0]
                    x = FloatArray(x.size) { (sqrt(aP) * x0[it] + sqrt(1 - aP) * eps[it]).toFloat() }
                }
            }
        }

        checkCancel()
        progress("IA generativa · ricostruzione dell'immagine…")
        val out = IntArray(plane)
        session("sd15_vae_decoder.onnx").use { s ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 4, lat.toLong(), lat.toLong())).use { t ->
                s.run(mapOf(s.inputNames.first() to t)).use { r ->
                    val fb = (r[0] as OnnxTensor).floatBuffer
                    val a = FloatArray(fb.remaining()); fb.get(a)
                    for (i in 0 until plane) {
                        val rr = ((a[i] + 1f) * 127.5f).toInt().coerceIn(0, 255)
                        val gg = ((a[plane + i] + 1f) * 127.5f).toInt().coerceIn(0, 255)
                        val bb = ((a[2 * plane + i] + 1f) * 127.5f).toInt().coerceIn(0, 255)
                        out[i] = (255 shl 24) or (rr shl 16) or (gg shl 8) or bb
                    }
                }
            }
        }
        return out
    }

    override fun close() {}
}

/** Segna a che punto è l'IA generativa: se l'app viene chiusa dal sistema, al riavvio sappiamo dove. */
object SdDiag {
    var file: File? = null
    var avail: (() -> String)? = null
    fun mark(stage: String) {
        val a = avail?.invoke() ?: ""
        runCatching { file?.writeText("$stage\n$a") }
        AppLog.log("IA", "$stage · $a")
    }
    fun clear() { runCatching { file?.delete() } }
    /** Restituisce il rapporto dell'ultima chiusura anomala (e lo cancella). */
    fun takeCrash(): String? = runCatching {
        val f = file ?: return null
        if (!f.exists()) null else f.readText().also { f.delete() }
    }.getOrNull()
}
