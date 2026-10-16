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
import kotlin.math.roundToInt

enum class Tool { BRUSH, LASSO, ERASER, RESTORE }

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
    var strokeActive by mutableStateOf(false); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var comparing by mutableStateOf(false)
    val original: Bitmap? get() = source

    val lasso = Path()

    fun touch() { version++ }

    private val undo = ArrayDeque<Snapshot>()
    private val redo = ArrayDeque<Snapshot>()
    private var source: Bitmap? = null
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
                undo.clear(); redo.clear()
                source = bmp
                image = bmp
                mask = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ALPHA_8)
                hasMask = false
                lasso.reset()
                loadId++
                refreshHistoryFlags()
                version++
            } catch (e: Exception) {
                message = "Impossibile aprire l'immagine: ${e.message}"
            } finally {
                busy = false
            }
        }
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

    fun close() {
        image = null; mask = null; source = null
        undo.clear(); redo.clear()
        hasMask = false
        refreshHistoryFlags()
    }

    val hasChanges: Boolean get() = undo.isNotEmpty()

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
        viewModelScope.launch {
            val res = withContext(Dispatchers.Default) {
                runCatching { Inpainter.get(ctx).inpaint(img, mk, expand.roundToInt()) }
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
            }.onFailure { message = "Errore IA: ${it.message ?: it.javaClass.simpleName}" }
            busy = false
        }
    }

    // ---- export ----
    fun saveToGallery() {
        val im = image ?: return
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "MagicTouch_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MagicTouchRemove")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val r = ctx.contentResolver
                val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore")
                r.openOutputStream(uri)!!.use { im.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                r.update(uri, values, null, null)
                message = "Salvata in Immagini/MagicTouchRemove"
            } catch (e: Exception) {
                message = "Errore salvataggio: ${e.message}"
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
                    f.outputStream().use { im.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                }
                onUri(uri)
            } catch (e: Exception) {
                message = "Errore condivisione: ${e.message}"
            }
        }
    }

    companion object {
        private const val MAX_SIDE = 2048
        private const val MAX_HISTORY = 8
    }
}
