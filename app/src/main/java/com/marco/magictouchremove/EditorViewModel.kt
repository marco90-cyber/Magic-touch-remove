package com.marco.magictouchremove

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Tool { SELECT, BRUSH, LASSO, ERASER, RESTORE, BLUR }

/** Stato immutabile (copy-on-write) usato per undo/redo. */
class Snapshot(val image: Bitmap, val mask: Bitmap, val hasMask: Boolean)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    // ---- stato osservabile ----
    var image by mutableStateOf<Bitmap?>(null); private set
    var mask: Bitmap? = null; private set
    var version by mutableIntStateOf(0); private set      // forza il ridisegno
    var loadId by mutableIntStateOf(0); private set       // cambia a ogni nuova foto
    var tool by mutableStateOf(Tool.BRUSH)
    var brushDp by mutableFloatStateOf(36f)
    var hardness by mutableFloatStateOf(0.6f)
    var maskOpacity by mutableFloatStateOf(0.6f)
    var expand by mutableFloatStateOf(3f)
    var auto by mutableStateOf(false)
    var hasMask by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var busyText by mutableStateOf(""); private set
    var strokeActive by mutableStateOf(false); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var comparing by mutableStateOf(false)
    /** True quando un controllo di sicurezza ha bloccato il processo: l'app mostra la schermata nera di blocco. */
    /** Pixel in più rispetto al bordo della selezione automatica (0/5/10/15). */
    var selExtra by mutableIntStateOf(0); private set
    /** Durezza della selezione: 1 = bordo netto; meno = il riempimento si fonde in modo graduale con la foto. */
    var selSoft by mutableFloatStateOf(1f)
    /** Includi l'ombra dell'oggetto selezionato: 0 = no, fino a 1 = ombra più ampia. */
    var selShadow by mutableFloatStateOf(0f); private set
    private var refreshJob: kotlinx.coroutines.Job? = null
    private var selRaw: BooleanArray? = null
    private var selBase: Bitmap? = null
    private var selResult: Bitmap? = null
    val original: Bitmap? get() = source

    val lasso = Path()

    fun touch() { version++ }

    private val undo = ArrayDeque<Snapshot>()
    private val redo = ArrayDeque<Snapshot>()
    private var source: Bitmap? = null
    private var sourceUri: Uri? = null
    /** Dimensioni della foto originale (prima del ridimensionamento di lavoro), per l'esportazione. */
    var originalW by mutableIntStateOf(0); private set
    var originalH by mutableIntStateOf(0); private set
    private var drawCanvas: Canvas? = null
    private var strokePaint: Paint? = null
    private var lastX = 0f
    private var lastY = 0f
    private var lassoPoints = 0

    // ---- caricamento ----
    fun load(uri: Uri) {
        viewModelScope.launch {
            busy = true
            try {
                val bmp = withContext(Dispatchers.IO) { decode(uri) }
                val dims = withContext(Dispatchers.IO) { runCatching { fullSize(uri, bmp.width >= bmp.height) }.getOrNull() }
                originalW = dims?.first ?: bmp.width
                originalH = dims?.second ?: bmp.height
                undo.clear(); redo.clear()
                clearBlurCaches()
                restored = false
                source = bmp
                sourceUri = uri
                image = bmp
                mask = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ALPHA_8)
                hasMask = false
                lasso.reset()
                loadId++
                refreshHistoryFlags()
                version++
            } catch (e: Exception) {
                AppLog.log("ERRORE", "apertura immagine: ${e.stackTraceToString()}")
                message = "Impossibile aprire l'immagine: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    /** Dimensioni reali della foto (con l'orientamento giusto), senza decodificarla. */
    private fun fullSize(uri: Uri, landscape: Boolean): Pair<Int, Int> {
        var w = 0; var h = 0
        val src = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
        runCatching {
            ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                w = info.size.width; h = info.size.height
                dec.setTargetSize(1, 1)
            }
        }
        if (w == 0) error("dimensioni non lette")
        return if ((w >= h) == landscape) w to h else h to w
    }

    private fun decode(uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
        val decoded = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val m = max(info.size.width, info.size.height)
            if (m > MAX_SIDE) {
                val s = MAX_SIDE.toFloat() / m
                dec.setTargetSize(
                    (info.size.width * s).roundToInt().coerceAtLeast(1),
                    (info.size.height * s).roundToInt().coerceAtLeast(1)
                )
            }
        }
        return decoded.copy(Bitmap.Config.ARGB_8888, true)
    }

    private var restored = false

    /** Salva il lavoro in corso (chiamato quando l'app va in secondo piano). */
    fun saveProject() {
        val im = image ?: return
        val src = source ?: return
        val same = !hasChanges && im === src
        val snapImg = im.copy(Bitmap.Config.ARGB_8888, false) ?: return
        val snapSrc = if (same) snapImg else src
        val w = originalW; val h = originalH; val u = sourceUri?.toString()
        val ctx = getApplication<Application>()
        Thread { runCatching { ProjectStore.save(ctx, snapImg, snapSrc, w, h, u) } }.start()
    }

    /** Riprende il lavoro salvato. */
    fun resumeProject() {
        viewModelScope.launch {
            busy = true
            try {
                val sv = withContext(Dispatchers.IO) { ProjectStore.load(getApplication()) }
                if (sv == null) { message = "Lavoro non più disponibile"; ProjectStore.clear(getApplication()); return@launch }
                undo.clear(); redo.clear()
                originalW = sv.w; originalH = sv.h
                source = sv.source
                sourceUri = sv.uri?.let { Uri.parse(it) }
                image = sv.image
                mask = Bitmap.createBitmap(sv.image.width, sv.image.height, Bitmap.Config.ALPHA_8)
                hasMask = false
                lasso.reset()
                restored = true
                loadId++
                refreshHistoryFlags()
                version++
            } catch (e: Exception) {
                AppLog.log("ERRORE", "ripresa lavoro: ${e.stackTraceToString()}")
                message = "Impossibile riprendere il lavoro"
            } finally { busy = false }
        }
    }

    fun close() {
        clearBlurCaches()
        restored = false
        ProjectStore.clear(getApplication())
        image = null; mask = null; source = null; sourceUri = null
        undo.clear(); redo.clear()
        hasMask = false
        refreshHistoryFlags()
    }

    val hasChanges: Boolean get() = undo.isNotEmpty() || restored

    // ---- undo / redo ----
    private fun current() = Snapshot(image!!, mask!!, hasMask)

    private fun apply(s: Snapshot) {
        image = s.image; mask = s.mask; hasMask = s.hasMask
        version++
    }

    private fun pushUndo() {
        undo.addLast(current())
        if (undo.size > MAX_HISTORY) undo.removeFirst()
        redo.clear()
        refreshHistoryFlags()
    }

    fun undo() {
        if (busy || undo.isEmpty() || image == null) return
        redo.addLast(current())
        apply(undo.removeLast())
        refreshHistoryFlags()
    }

    fun redo() {
        if (busy || redo.isEmpty() || image == null) return
        undo.addLast(current())
        apply(redo.removeLast())
        refreshHistoryFlags()
    }

    private fun refreshHistoryFlags() {
        canUndo = undo.isNotEmpty(); canRedo = redo.isNotEmpty()
    }

    // ---- disegno ----
    private fun newPaint(radius: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = radius * 2f
        color = android.graphics.Color.BLACK
        if (hardness < 0.97f) maskFilter = BlurMaskFilter(radius * (1f - hardness) * 0.7f + 0.5f, BlurMaskFilter.Blur.NORMAL)
    }

    /** @param radius raggio del pennello in pixel dell'immagine */
    fun beginStroke(x: Float, y: Float, radius: Float) {
        val img = image ?: return
        val mk = mask ?: return
        if (busy) return
        strokeActive = true
        lastX = x; lastY = y
        when (tool) {
            Tool.BRUSH, Tool.ERASER -> {
                pushUndo()
                val copy = mk.copy(Bitmap.Config.ALPHA_8, true)
                mask = copy
                drawCanvas = Canvas(copy)
                strokePaint = newPaint(radius).apply {
                    if (tool == Tool.ERASER) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
                if (tool == Tool.BRUSH) hasMask = true
                drawCanvas!!.drawLine(x, y, x, y, strokePaint!!)
            }
            Tool.RESTORE -> {
                val src = source ?: return
                pushUndo()
                val copy = img.copy(Bitmap.Config.ARGB_8888, true)
                image = copy
                drawCanvas = Canvas(copy)
                strokePaint = newPaint(radius).apply {
                    shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    color = android.graphics.Color.WHITE
                }
                drawCanvas!!.drawLine(x, y, x, y, strokePaint!!)
            }
            Tool.LASSO -> {
                lasso.reset(); lasso.moveTo(x, y); lassoPoints = 1
            }
            Tool.SELECT, Tool.BLUR -> {}
        }
        version++
    }

    fun moveStroke(x: Float, y: Float) {
        if (!strokeActive) return
        when (tool) {
            Tool.LASSO -> { lasso.lineTo(x, y); lassoPoints++ }
            else -> drawCanvas?.drawLine(lastX, lastY, x, y, strokePaint!!)
        }
        lastX = x; lastY = y
        version++
    }

    /** Annulla il tratto in corso (es. è iniziato un pinch). */
    fun cancelStroke() {
        if (!strokeActive) return
        strokeActive = false
        when (tool) {
            Tool.LASSO -> lasso.reset()
            else -> if (undo.isNotEmpty()) { apply(undo.removeLast()); refreshHistoryFlags() }
        }
        drawCanvas = null; strokePaint = null
        version++
    }

    fun endStroke() {
        if (!strokeActive) return
        strokeActive = false
        val mk = mask
        if (tool == Tool.LASSO) {
            if (lassoPoints > 2 && mk != null) {
                pushUndo()
                val copy = mk.copy(Bitmap.Config.ALPHA_8, true)
                mask = copy
                lasso.close()
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = android.graphics.Color.BLACK }
                Canvas(copy).drawPath(lasso, p)
                hasMask = true
            }
            lasso.reset()
        }
        drawCanvas = null; strokePaint = null
        version++
        if (auto && hasMask && (tool == Tool.BRUSH || tool == Tool.LASSO)) runInpaint()
    }


    // ---- sfocatura dello sfondo ----
    /** Intensità 0..1 (0 = f/16, 1 = f/1.4). */
    var blurAmount by mutableFloatStateOf(0.6f)
    /** Ampiezza della zona a fuoco attorno al soggetto (0..0.5). */
    var blurTol by mutableFloatStateOf(0.12f)
    var focusPoint by mutableStateOf<PointF?>(null); private set
    var blurPreview by mutableStateOf<Bitmap?>(null); private set
    var blurPreviewFor: Bitmap? = null; private set
    private var depthMap: FloatArray? = null
    private var depthFor: Bitmap? = null
    private var subject: BooleanArray? = null
    private var focusDepth = 0.5f
    private var pvBase: IntArray? = null
    private var pvDepth: FloatArray? = null
    private var pvSubject: BooleanArray? = null
    private var pvW = 0
    private var pvH = 0
    private var blurJob: kotlinx.coroutines.Job? = null

    /** Entra nello strumento Sfocatura: analizza la profondità della foto (una volta sola per foto). */
    fun enterBlur() {
        val img = image ?: return
        val ctx = getApplication<Application>()
        if (ModelManager.active(ctx, Kind.DEPTH) == null) {
            message = "Scarica prima il modello «Profondità» da Impostazioni › Modelli IA"
            tool = Tool.BRUSH
            return
        }
        if (depthFor === img && depthMap != null) { buildPreviewBase(img); updateBlurPreview(); return }
        if (busy) return
        busy = true
        busyText = "Analisi della profondità…"
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) { runCatching { Depth.estimate(ctx, img) } }
            busy = false
            r.onSuccess { d ->
                if (image !== img) return@onSuccess
                depthMap = d; depthFor = img; subject = null
                val c = PointF(img.width / 2f, img.height / 2f)
                focusPoint = c
                focusDepth = depthAround(d, img.width, img.height, c)
                buildPreviewBase(img)
                updateBlurPreview()
            }.onFailure {
                AppLog.log("ERRORE", "profondità: ${it.stackTraceToString()}")
                message = "Errore analisi profondità: ${it.message ?: it.javaClass.simpleName}"
                tool = Tool.BRUSH
            }
        }
    }

    private fun depthAround(d: FloatArray, w: Int, h: Int, p: PointF): Float {
        val cx = p.x.toInt().coerceIn(0, w - 1); val cy = p.y.toInt().coerceIn(0, h - 1)
        val r = max(2, min(w, h) / 60)
        var sum = 0f; var n = 0
        for (y in max(0, cy - r)..min(h - 1, cy + r)) for (x in max(0, cx - r)..min(w - 1, cx + r)) { sum += d[y * w + x]; n++ }
        return if (n > 0) sum / n else 0.5f
    }

    private fun buildPreviewBase(img: Bitmap) {
        val s = 640f / max(img.width, img.height)
        val pw = (img.width * min(1f, s)).roundToInt().coerceAtLeast(1)
        val ph = (img.height * min(1f, s)).roundToInt().coerceAtLeast(1)
        val small = if (pw == img.width) img else Bitmap.createScaledBitmap(img, pw, ph, true)
        val px = IntArray(pw * ph)
        small.getPixels(px, 0, pw, 0, 0, pw, ph)
        if (small !== img) small.recycle()
        val d = depthMap ?: return
        pvW = pw; pvH = ph; pvBase = px
        pvDepth = FloatArray(pw * ph) { i -> d[(i / pw * img.height / ph) * img.width + (i % pw) * img.width / pw] }
        resampleSubject(img)
    }

    private fun resampleSubject(img: Bitmap) {
        val sj = subject
        pvSubject = if (sj == null) null else BooleanArray(pvW * pvH) { i -> sj[(i / pvW * img.height / pvH) * img.width + (i % pvW) * img.width / pvW] }
    }

    /** Tocco sul soggetto da tenere a fuoco: usa la selezione automatica (se c'è) e la profondità di quel punto. */
    fun setFocus(p: PointF) {
        val img = image ?: return
        val d = depthMap ?: return
        if (busy || depthFor !== img || p.x < 0 || p.y < 0 || p.x >= img.width || p.y >= img.height) return
        val ctx = getApplication<Application>()
        focusPoint = p
        busy = true
        busyText = "Selezione del soggetto…"
        viewModelScope.launch {
            val sj = if (ModelManager.isSamReady(ctx))
                withContext(Dispatchers.Default) { runCatching { Segmenter.get(ctx).select(img, p, null) }.getOrNull() } else null
            busy = false
            if (image !== img) return@launch
            subject = sj
            focusDepth = if (sj != null && sj.count { it } > 50) {
                val vals = ArrayList<Float>()
                for (i in sj.indices step 7) if (sj[i]) vals.add(d[i])
                vals.sort(); vals[vals.size / 2]
            } else depthAround(d, img.width, img.height, p)
            resampleSubject(img)
            updateBlurPreview()
        }
    }

    fun updateBlurPreview() {
        val base = pvBase ?: return
        val dp = pvDepth ?: return
        val img = image ?: return
        val pw = pvW; val ph = pvH
        val fd = focusDepth; val tol = blurTol; val fn = BlurEngine.fnum(blurAmount); val sj = pvSubject
        blurJob?.cancel()
        blurJob = viewModelScope.launch {
            val out = withContext(Dispatchers.Default) { runCatching { BlurEngine.render(base, pw, ph, dp, fd, tol, sj, fn) }.getOrNull() } ?: return@launch
            blurPreview = Bitmap.createBitmap(out, pw, ph, Bitmap.Config.ARGB_8888)
            blurPreviewFor = img
            version++
        }
    }

    /** Applica la sfocatura alla foto a piena risoluzione di lavoro. */
    fun applyBlur() {
        val img = image ?: return
        val d = depthMap ?: return
        if (busy || depthFor !== img) return
        busy = true
        busyText = "Applico la sfocatura…"
        val fd = focusDepth; val tol = blurTol; val fn = BlurEngine.fnum(blurAmount); val sj = subject
        viewModelScope.launch {
            val res = withContext(Dispatchers.Default) {
                runCatching {
                    val w = img.width; val h = img.height
                    val px = IntArray(w * h)
                    img.getPixels(px, 0, w, 0, 0, w, h)
                    Bitmap.createBitmap(BlurEngine.render(px, w, h, d, fd, tol, sj, fn), w, h, Bitmap.Config.ARGB_8888)
                }
            }
            busy = false
            res.onSuccess { out ->
                if (image === img) {
                    pushUndo()
                    image = out
                    mask = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ALPHA_8)
                    hasMask = false
                    depthFor = out
                    blurPreview = null; blurPreviewFor = null
                    tool = Tool.BRUSH
                    version++
                    message = "Sfocatura applicata"
                }
            }.onFailure {
                AppLog.log("ERRORE", "sfocatura: ${it.stackTraceToString()}")
                message = if (it is OutOfMemoryError) "Memoria insufficiente: chiudi le altre app e riprova" else "Errore sfocatura: ${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    fun leaveBlur() {
        blurJob?.cancel(); blurPreview = null; blurPreviewFor = null
        if (tool == Tool.BLUR) tool = Tool.BRUSH
        version++
    }

    private fun clearBlurCaches() {
        blurJob?.cancel()
        depthMap = null; depthFor = null; subject = null; pvBase = null; pvDepth = null; pvSubject = null
        blurPreview = null; blurPreviewFor = null; focusPoint = null
    }

    // ---- IA ----
    fun runInpaint() {
        val img = image ?: return
        val mk = mask ?: return
        if (busy) return
        val ctx = getApplication<Application>()
        if (!ModelManager.isReady(ctx)) {
            message = "Scarica prima il modello IA dalla schermata iniziale"
            return
        }
        busy = true
        busyText = "Rimozione in corso…"
        viewModelScope.launch {
            val res = withContext(Dispatchers.Default) {
                runCatching { Inpainter.get(ctx).inpaint(img, mk, expand.roundToInt(), ((1f - selSoft) * 10f).roundToInt()) }
            }
            res.onSuccess { out ->
                if (out == null) {
                    message = "Nessuna area selezionata"
                } else if (image === img) {
                    pushUndo()
                    image = out
                    mask = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ALPHA_8)
                    hasMask = false
                    version++
                }
            }.onFailure {
                AppLog.log("IA", "fine con errore: ${it.stackTraceToString()}")
                message = "Errore IA: ${it.message ?: it.javaClass.simpleName}"
            }
            if (Notifier.doneOn(ctx) && !AppState.foreground) {
                if (res.isSuccess && res.getOrNull() != null) Notifier.done(ctx, "Modifica completata", "La foto è pronta: tocca per aprirla.")
                else if (res.isFailure) Notifier.done(ctx, "Modifica non riuscita", "Qualcosa è andato storto: tocca per vedere.")
            }
            busy = false
            if (tool == Tool.SELECT && image != null) prepareSegmenter(quiet = true)
        }
    }

    // ---- selezione automatica ----
    private fun selectionBitmap(raw: BooleanArray, w: Int, h: Int, extra: Int, shadow: Float = 0f, img: Bitmap? = null): Bitmap {
        val eff = if (shadow > 0f && img != null) { val sh = ShadowSelect.grow(img, raw, shadow); BooleanArray(raw.size) { raw[it] || sh[it] } } else raw
        val d = dilate(eff, w, h, extra)
        val px = IntArray(w * h) { if (d[it]) 0xFF000000.toInt() else 0 }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Allarga la selezione di [r] pixel (forma ottagonale: passi alternati a croce e a quadrato). */
    private fun dilate(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        var cur = src
        repeat(r) { step ->
            val out = cur.copyOf()
            val square = step % 2 == 1
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    if (out[row + x]) continue
                    var hit = (x > 0 && cur[row + x - 1]) || (x < w - 1 && cur[row + x + 1]) ||
                        (y > 0 && cur[row - w + x]) || (y < h - 1 && cur[row + w + x])
                    if (!hit && square) hit = (y > 0 && ((x > 0 && cur[row - w + x - 1]) || (x < w - 1 && cur[row - w + x + 1]))) ||
                        (y < h - 1 && ((x > 0 && cur[row + w + x - 1]) || (x < w - 1 && cur[row + w + x + 1])))
                    if (hit) out[row + x] = true
                }
            }
            cur = out
        }
        return cur
    }

    /** Cambia di quanti pixel la selezione automatica sborda; aggiorna subito la selezione attuale. */
    fun changeSelExtra(px: Int) {
        if (px == selExtra) return
        selExtra = px
        refreshSelection()
    }

    /** Includi (o no) l'ombra dell'oggetto selezionato. */
    fun changeSelShadow(v: Float) {
        selShadow = v
        refreshSelection()
    }

    private fun refreshSelection() {
        val raw = selRaw ?: return
        val base = selBase ?: return
        val cur = mask ?: return
        if (cur !== selResult || busy) return
        val img = image ?: return
        val extra = selExtra; val shd = selShadow
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val sel = withContext(Dispatchers.Default) { selectionBitmap(raw, img.width, img.height, extra, shd, img) }
            if (mask === cur) {
                val copy = base.copy(Bitmap.Config.ALPHA_8, true)
                Canvas(copy).drawBitmap(sel, 0f, 0f, null)
                mask = copy; selResult = copy
                version++
            }
        }
    }

    // ---- selezione automatica (MobileSAM / SAM 2.1) ----
    /** Analizza in anticipo la foto corrente, così il tocco successivo è istantaneo. */
    fun prepareSegmenter(quiet: Boolean = false) {
        val img = image ?: return
        if (busy) return
        val ctx = getApplication<Application>()
        if (!ModelManager.isSamReady(ctx)) {
            if (!quiet) message = "Il modello di selezione non è ancora scaricato"
            return
        }
        busy = true
        busyText = "Analisi dell'immagine…"
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) { runCatching { Segmenter.get(ctx).ensureEncoded(img) } }
            busy = false
            r.onFailure { message = "Errore selezione: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    /** Seleziona l'oggetto sotto un tocco ([point]) o dentro un riquadro ([box]); coordinate in pixel immagine. */
    fun selectObject(point: PointF?, box: RectF?) {
        val img = image ?: return
        val mk = mask ?: return
        if (busy) return
        val ctx = getApplication<Application>()
        if (!ModelManager.isSamReady(ctx)) {
            message = "Il modello di selezione non è ancora scaricato"
            return
        }
        if (point != null && (point.x < 0 || point.y < 0 || point.x >= img.width || point.y >= img.height)) return
        busy = true
        busyText = "Selezione oggetto…"
        viewModelScope.launch {
            val res = withContext(Dispatchers.Default) {
                runCatching {
                    val raw = Segmenter.get(ctx).select(img, point, box) ?: return@runCatching null
                    raw to selectionBitmap(raw, img.width, img.height, selExtra, selShadow, img)
                }
            }
            busy = false
            res.onSuccess { pair ->
                val sel = pair?.second
                if (sel == null) {
                    message = "Nessun oggetto trovato qui"
                } else if (image === img) {
                    pushUndo()
                    val copy = mk.copy(Bitmap.Config.ALPHA_8, true)
                    Canvas(copy).drawBitmap(sel, 0f, 0f, null)
                    mask = copy
                    selRaw = pair!!.first; selBase = mk; selResult = copy
                    hasMask = true
                    version++
                    if (auto) runInpaint()
                }
            }.onFailure { message = "Errore selezione: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    // ---- export ----
    /** Foto alla risoluzione scelta: fuori dalle zone modificate usa i pixel della foto originale a piena risoluzione. */
    private fun renderFull(im: Bitmap, scalePct: Int): Bitmap {
        val src = source
        val uri = sourceUri
        var base: Bitmap = im
        if (src != null && uri != null && originalW > im.width) {
            val full = runCatching { decodeFull(uri) }.getOrNull()
            if (full != null && full.width.toLong() * full.height <= 60_000_000L &&
                kotlin.math.abs(full.width.toFloat() / full.height - im.width.toFloat() / im.height) < 0.01f
            ) {
                base = runCatching { compose(full, im, src) }.getOrDefault(im)
            }
        }
        if (scalePct >= 100) return base
        val w = (base.width * scalePct / 100).coerceAtLeast(1)
        val h = (base.height * scalePct / 100).coerceAtLeast(1)
        return Bitmap.createScaledBitmap(base, w, h, true)
    }

    private fun decodeFull(uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { dec, _, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            dec.isMutableRequired = true
            dec.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        }
    }

    /** Rimette sulla foto originale ([full]) solo le zone che l'utente ha modificato in [im] (rispetto a [src]). */
    private fun compose(full: Bitmap, im: Bitmap, src: Bitmap): Bitmap {
        val ww = im.width; val hh = im.height
        val W = full.width; val H = full.height
        val p1 = IntArray(ww * hh); val p2 = IntArray(ww * hh)
        im.getPixels(p1, 0, ww, 0, 0, ww, hh)
        src.getPixels(p2, 0, ww, 0, 0, ww, hh)
        var m = FloatArray(ww * hh)
        var any = false
        for (i in m.indices) {
            val a = p1[i]; val b = p2[i]
            val d = max(max(kotlin.math.abs(((a shr 16) and 255) - ((b shr 16) and 255)), kotlin.math.abs(((a shr 8) and 255) - ((b shr 8) and 255))),
                kotlin.math.abs((a and 255) - (b and 255)))
            if (d > 2) { m[i] = 1f; any = true }
        }
        if (!any) return full
        // sfumatura del bordo: due passaggi di media (orizzontale + verticale)
        val r = 4
        val tmp = FloatArray(ww * hh)
        for (y in 0 until hh) {
            var acc = 0f
            val row = y * ww
            for (x in -r..r) acc += m[row + x.coerceIn(0, ww - 1)]
            for (x in 0 until ww) {
                tmp[row + x] = acc / (2 * r + 1)
                acc += m[row + (x + r + 1).coerceIn(0, ww - 1)] - m[row + (x - r).coerceIn(0, ww - 1)]
            }
        }
        for (x in 0 until ww) {
            var acc = 0f
            for (y in -r..r) acc += tmp[y.coerceIn(0, hh - 1) * ww + x]
            for (y in 0 until hh) {
                m[y * ww + x] = min(1f, acc / (2 * r + 1) * 2.5f)
                acc += tmp[(y + r + 1).coerceIn(0, hh - 1) * ww + x] - tmp[(y - r).coerceIn(0, hh - 1) * ww + x]
            }
        }
        val edited = Bitmap.createScaledBitmap(im, W, H, true)
        val xi0 = IntArray(W); val xi1 = IntArray(W); val xf = FloatArray(W)
        for (x in 0 until W) {
            val fx = ((x + 0.5f) * ww / W - 0.5f).coerceIn(0f, ww - 1f)
            xi0[x] = fx.toInt(); xi1[x] = min(ww - 1, xi0[x] + 1); xf[x] = fx - xi0[x]
        }
        val a = FloatArray(W)
        val fr = IntArray(W); val er = IntArray(W)
        for (y in 0 until H) {
            val fy = ((y + 0.5f) * hh / H - 0.5f).coerceIn(0f, hh - 1f)
            val y0 = fy.toInt(); val y1 = min(hh - 1, y0 + 1); val ay = fy - y0
            var rowAny = false
            for (x in 0 until W) {
                val t = m[y0 * ww + xi0[x]] * (1 - xf[x]) + m[y0 * ww + xi1[x]] * xf[x]
                val b = m[y1 * ww + xi0[x]] * (1 - xf[x]) + m[y1 * ww + xi1[x]] * xf[x]
                val v = t * (1 - ay) + b * ay
                a[x] = v
                if (v > 0.001f) rowAny = true
            }
            if (!rowAny) continue
            full.getPixels(fr, 0, W, 0, y, W, 1)
            edited.getPixels(er, 0, W, 0, y, W, 1)
            for (x in 0 until W) {
                val k = a[x]
                if (k <= 0.001f) continue
                val f = fr[x]; val e = er[x]
                val rr = (((e shr 16) and 255) * k + ((f shr 16) and 255) * (1 - k)).toInt()
                val gg = (((e shr 8) and 255) * k + ((f shr 8) and 255) * (1 - k)).toInt()
                val bb = ((e and 255) * k + (f and 255) * (1 - k)).toInt()
                fr[x] = (255 shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
            full.setPixels(fr, 0, W, 0, y, W, 1)
        }
        return full
    }

    @Suppress("DEPRECATION")
    private fun write(bmp: Bitmap, o: ExportOptions, out: java.io.OutputStream) {
        val fmt = when (o.format) {
            1 -> Bitmap.CompressFormat.PNG
            2 -> if (android.os.Build.VERSION.SDK_INT >= 30)
                (if (o.quality >= 100) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY)
            else Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        if (!bmp.compress(fmt, o.quality, out)) error("compressione non riuscita")
    }

    /** Stima del peso del file: comprime un ritaglio centrale con le stesse opzioni e lo proporziona ai pixel finali. */
    suspend fun estimateSize(o: ExportOptions): Long = withContext(Dispatchers.Default) {
        val im = image ?: return@withContext 0L
        val bw = if (originalW > 0) originalW else im.width
        val bh = if (originalH > 0) originalH else im.height
        val outW = (bw * o.scale / 100).coerceAtLeast(1)
        val outH = (bh * o.scale / 100).coerceAtLeast(1)
        val f = min(1f, outW.toFloat() / im.width)
        val cw = min(im.width, 768); val ch = min(im.height, 768)
        var s = Bitmap.createBitmap(im, (im.width - cw) / 2, (im.height - ch) / 2, cw, ch)
        if (f < 1f) s = Bitmap.createScaledBitmap(s, (cw * f).toInt().coerceAtLeast(16), (ch * f).toInt().coerceAtLeast(16), true)
        val bos = java.io.ByteArrayOutputStream()
        write(s, o, bos)
        (bos.size().toDouble() / (s.width.toLong() * s.height) * outW * outH).toLong()
    }

    /**
     * Salva la foto con le opzioni scelte. [dest] = file scelto dall'utente; null = galleria (Immagini/MagicTouchRemove).
     * @param onSaved eseguita (sul thread principale) solo se il salvataggio è riuscito.
     */
    fun export(o: ExportOptions, dest: Uri?, onSaved: (() -> Unit)? = null) {
        val im = image ?: return
        if (busy) return
        val ctx = getApplication<Application>()
        busy = true
        busyText = "Salvataggio…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bmp = renderFull(im, o.scale)
                if (dest != null) {
                    ctx.contentResolver.openOutputStream(dest, "wt")!!.use { write(bmp, o, it) }
                    message = "Foto salvata (${bmp.width}×${bmp.height})"
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "MagicTouch_${System.currentTimeMillis()}.${o.ext}")
                        put(MediaStore.Images.Media.MIME_TYPE, o.mime)
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MagicTouchRemove")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val r = ctx.contentResolver
                    val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore")
                    r.openOutputStream(uri)!!.use { write(bmp, o, it) }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    r.update(uri, values, null, null)
                    message = "Salvata in Immagini/MagicTouchRemove (${bmp.width}×${bmp.height})"
                }
                if (onSaved != null) withContext(Dispatchers.Main) { onSaved() }
            } catch (e: Throwable) {
                AppLog.log("ERRORE", "salvataggio: ${e.stackTraceToString()}")
                message = "Errore salvataggio: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    fun share(onUri: (Uri) -> Unit) {
        val im = image ?: return
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
                    val f = File(dir, "MagicTouch_${System.currentTimeMillis()}.jpg")
                    val bmp = renderFull(im, 100)
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                }
                onUri(uri)
            } catch (e: Throwable) {
                message = "Errore condivisione: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    companion object {
        private const val MAX_SIDE = 2048
        private const val MAX_HISTORY = 8
    }
}
