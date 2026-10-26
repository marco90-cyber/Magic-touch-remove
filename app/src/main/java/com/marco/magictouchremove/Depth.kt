package com.marco.magictouchremove

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** Stima della profondità con Depth Anything V2 Small (Apache-2.0): serve alla sfocatura dello sfondo. */
object Depth {
    private val env: OrtEnvironment get() = OrtEnvironment.getEnvironment()
    @Volatile private var session: OrtSession? = null

    fun release() = synchronized(this) { session?.close(); session = null }

    private fun get(ctx: Context): OrtSession = synchronized(this) {
        session ?: run {
            val opt = ModelManager.active(ctx, Kind.DEPTH) ?: error("Nessun modello di profondità scaricato")
            Accel.session(env, ModelManager.file(ctx, opt.files[0]).absolutePath).also { session = it }
        }
    }

    /** Mappa di profondità 0..1 (1 = vicino) con le stesse dimensioni di [bmp]. */
    fun estimate(ctx: Context, bmp: Bitmap): FloatArray = synchronized(this) { estimateLocked(ctx, bmp) }

    private fun estimateLocked(ctx: Context, bmp: Bitmap): FloatArray {
        val s = get(ctx)
        val sc = 518f / max(bmp.width, bmp.height)
        val tw = (bmp.width * sc / 14f).roundToInt().coerceAtLeast(1) * 14
        val th = (bmp.height * sc / 14f).roundToInt().coerceAtLeast(1) * 14
        val small = Bitmap.createScaledBitmap(bmp, tw, th, true)
        val px = IntArray(tw * th)
        small.getPixels(px, 0, tw, 0, 0, tw, th)
        if (small !== bmp) small.recycle()
        val plane = tw * th
        val f = FloatArray(3 * plane)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        for (i in 0 until plane) {
            f[i] = (((px[i] shr 16) and 255) / 255f - mean[0]) / std[0]
            f[plane + i] = (((px[i] shr 8) and 255) / 255f - mean[1]) / std[1]
            f[2 * plane + i] = ((px[i] and 255) / 255f - mean[2]) / std[2]
        }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(f), longArrayOf(1, 3, th.toLong(), tw.toLong())).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { r ->
                val out = r[0] as OnnxTensor
                val shp = out.info.shape
                val oh = shp[shp.size - 2].toInt(); val ow = shp[shp.size - 1].toInt()
                val fb = out.floatBuffer
                val arr = FloatArray(fb.remaining()); fb.get(arr)
                resize(normalize(arr), ow, oh, bmp.width, bmp.height)
            }
        }
    }

    /** Porta i valori tra 0 e 1 ignorando gli estremi (2% e 98%), più robusto con zone anomale. */
    private fun normalize(a: FloatArray): FloatArray {
        val sorted = a.copyOf(); sorted.sort()
        val lo = sorted[(sorted.size * 0.02f).toInt()]
        val hi = sorted[((sorted.size - 1) * 0.98f).toInt()]
        val d = (hi - lo).coerceAtLeast(1e-6f)
        return FloatArray(a.size) { ((a[it] - lo) / d).coerceIn(0f, 1f) }
    }

    private fun resize(a: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        if (sw == dw && sh == dh) return a
        val out = FloatArray(dw * dh)
        val xs = (sw - 1).toFloat() / max(1, dw - 1)
        val ys = (sh - 1).toFloat() / max(1, dh - 1)
        for (y in 0 until dh) {
            val fy = y * ys; val y0 = fy.toInt(); val y1 = minOf(y0 + 1, sh - 1); val wy = fy - y0
            for (x in 0 until dw) {
                val fx = x * xs; val x0 = fx.toInt(); val x1 = minOf(x0 + 1, sw - 1); val wx = fx - x0
                val top = a[y0 * sw + x0] * (1 - wx) + a[y0 * sw + x1] * wx
                val bot = a[y1 * sw + x0] * (1 - wx) + a[y1 * sw + x1] * wx
                out[y * dw + x] = top * (1 - wy) + bot * wy
            }
        }
        return out
    }
}
