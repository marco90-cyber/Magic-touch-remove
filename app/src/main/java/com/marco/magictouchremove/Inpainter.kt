package com.marco.magictouchremove

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Rimozione oggetti con LaMa (Large Mask inpainting, Apache-2.0) via ONNX Runtime.
 *
 * Il modello lavora a 512x512: per mantenere la massima precisione si ritaglia solo la zona
 * attorno alla maschera (con contesto), si ricostruisce lì e si reincolla nell'immagine
 * originale a piena risoluzione, sostituendo SOLO i pixel mascherati (bordo sfumato).
 */
class Inpainter private constructor(modelPath: String) {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val imageInput: String
    private val maskInput: String

    init {
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(4)
        session = env.createSession(modelPath, opts)
        val names = session.inputNames.toList()
        imageInput = names.firstOrNull { it.contains("image", true) } ?: names[0]
        maskInput = names.firstOrNull { it.contains("mask", true) } ?: names[1]
    }

    /** @param mask bitmap ALPHA_8 della stessa dimensione di [image]. Ritorna null se la maschera è vuota. */
    fun inpaint(image: Bitmap, mask: Bitmap, expand: Int): Bitmap? {
        val w = image.width
        val h = image.height
        val mpx = IntArray(w * h)
        mask.getPixels(mpx, 0, w, 0, 0, w, h)

        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((mpx[row + x] ushr 24) > 127) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null

        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        val side0 = (max(bw, bh) * 1.8f + 64f).toInt()
        val left: Int; val top: Int; val cw: Int; val ch: Int
        if (side0 <= min(w, h)) {
            cw = side0; ch = side0
            left = ((minX + maxX) / 2 - side0 / 2).coerceIn(0, w - side0)
            top = ((minY + maxY) / 2 - side0 / 2).coerceIn(0, h - side0)
        } else { // maschera enorme: si usa l'intera immagine
            left = 0; top = 0; cw = w; ch = h
        }

        val n = SIZE
        // maschera a 512x512 (max-pooling per non perdere tratti sottili)
        var m512 = BooleanArray(n * n)
        for (my in 0 until n) {
            val y0 = my * ch / n
            val y1 = max(y0 + 1, (my + 1) * ch / n)
            for (mx in 0 until n) {
                val x0 = mx * cw / n
                val x1 = max(x0 + 1, (mx + 1) * cw / n)
                m512[my * n + mx] = anyMasked(mpx, w, left, top, x0, x1, y0, y1)
            }
        }
        m512 = dilate(m512, n, expand)

        val crop = Bitmap.createBitmap(image, left, top, cw, ch)
        val in512 = Bitmap.createScaledBitmap(crop, n, n, true)
        val ipx = IntArray(n * n)
        in512.getPixels(ipx, 0, n, 0, 0, n, n)

        val plane = n * n
        val imgArr = FloatArray(3 * plane)
        val maskArr = FloatArray(plane)
        for (i in 0 until plane) {
            val p = ipx[i]
            imgArr[i] = ((p shr 16) and 255) / 255f
            imgArr[plane + i] = ((p shr 8) and 255) / 255f
            imgArr[2 * plane + i] = (p and 255) / 255f
            maskArr[i] = if (m512[i]) 1f else 0f
        }

        val outPx = IntArray(plane)
        val shapeImg = longArrayOf(1, 3, n.toLong(), n.toLong())
        val shapeMask = longArrayOf(1, 1, n.toLong(), n.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(imgArr), shapeImg).use { ti ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(maskArr), shapeMask).use { tm ->
                session.run(mapOf(imageInput to ti, maskInput to tm)).use { res ->
                    @Suppress("UNCHECKED_CAST")
                    val out = res[0].value as Array<Array<Array<FloatArray>>>
                    var mx = 0f
                    for (c in 0 until 3) for (y in 0 until n) for (x in 0 until n) mx = max(mx, out[0][c][y][x])
                    val k = if (mx <= 2f) 255f else 1f
                    for (y in 0 until n) for (x in 0 until n) {
                        val r = (out[0][0][y][x] * k).toInt().coerceIn(0, 255)
                        val g = (out[0][1][y][x] * k).toInt().coerceIn(0, 255)
                        val b = (out[0][2][y][x] * k).toInt().coerceIn(0, 255)
                        outPx[y * n + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }

        // risultato e maschera morbida riportati alla risoluzione del ritaglio
        val outBmp512 = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        outBmp512.setPixels(outPx, 0, n, 0, 0, n, n)
        val outBmp = if (cw == n && ch == n) outBmp512 else Bitmap.createScaledBitmap(outBmp512, cw, ch, true)

        val aPx = IntArray(plane) { if (m512[it]) 0xFFFFFFFF.toInt() else 0 }
        val aBmp512 = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        aBmp512.setPixels(aPx, 0, n, 0, 0, n, n)
        val aBmp = if (cw == n && ch == n) aBmp512 else Bitmap.createScaledBitmap(aBmp512, cw, ch, true)

        val cp = IntArray(cw * ch)
        crop.getPixels(cp, 0, cw, 0, 0, cw, ch)
        val op = IntArray(cw * ch)
        outBmp.getPixels(op, 0, cw, 0, 0, cw, ch)
        val ap = IntArray(cw * ch)
        aBmp.getPixels(ap, 0, cw, 0, 0, cw, ch)

        for (i in cp.indices) {
            val a = (ap[i] ushr 24) / 255f
            if (a <= 0f) continue
            val o = op[i]; val c = cp[i]
            val r = (((o shr 16) and 255) * a + ((c shr 16) and 255) * (1 - a)).toInt()
            val g = (((o shr 8) and 255) * a + ((c shr 8) and 255) * (1 - a)).toInt()
            val b = ((o and 255) * a + (c and 255) * (1 - a)).toInt()
            cp[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }

        val result = image.copy(Bitmap.Config.ARGB_8888, true)
        result.setPixels(cp, 0, cw, left, top, cw, ch)
        return result
    }

    private fun anyMasked(mpx: IntArray, w: Int, left: Int, top: Int, x0: Int, x1: Int, y0: Int, y1: Int): Boolean {
        for (yy in y0 until y1) {
            val row = (top + yy) * w + left
            for (xx in x0 until x1) if ((mpx[row + xx] ushr 24) > 127) return true
        }
        return false
    }

    private fun dilate(m: BooleanArray, n: Int, r: Int): BooleanArray {
        if (r <= 0) return m
        val tmp = BooleanArray(n * n)
        val out = BooleanArray(n * n)
        for (y in 0 until n) for (x in 0 until n) if (m[y * n + x]) {
            for (k in max(0, x - r)..min(n - 1, x + r)) tmp[y * n + k] = true
        }
        for (y in 0 until n) for (x in 0 until n) if (tmp[y * n + x]) {
            for (k in max(0, y - r)..min(n - 1, y + r)) out[k * n + x] = true
        }
        return out
    }

    companion object {
        private const val SIZE = 512
        @Volatile private var instance: Inpainter? = null

        fun get(ctx: Context): Inpainter = instance ?: synchronized(this) {
            instance ?: Inpainter(ModelManager.file(ctx).absolutePath).also { instance = it }
        }
    }
}
