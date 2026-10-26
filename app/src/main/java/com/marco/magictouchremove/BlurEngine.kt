package com.marco.magictouchremove

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Sfocatura dello sfondo a profondità variabile (effetto "apertura" di una fotocamera). */
object BlurEngine {
    private const val LEVELS = 6

    /** Apertura (numero f) da un'intensità 0..1: 0 = f/16 (quasi niente), 1 = f/1.4 (molto sfocato). */
    fun fnum(amount: Float): Float = 16f * (1.4f / 16f).pow(amount.coerceIn(0f, 1f))

    private fun maxRadius(w: Int, h: Int, fnum: Float) = max(w, h) * 0.045f * min(1.8f / fnum, 1.5f)

    /**
     * [src] ARGB, [depth] 0..1 (1 = vicino), [focus] profondità a fuoco, [tol] ampiezza della zona a fuoco (0..0.5),
     * [subject] soggetto scelto (resta sempre nitido, con bordo sfumato).
     */
    fun render(src: IntArray, w: Int, h: Int, depth: FloatArray, focus: Float, tol: Float, subject: BooleanArray?, fnum: Float): IntArray {
        val rMax = maxRadius(w, h, fnum)
        if (rMax < 0.8f) return src.copyOf()
        val sj = subject?.let { feather(it, w, h, max(1, (rMax * 0.12f).roundToInt())) }
        val coc = FloatArray(w * h)
        for (i in coc.indices) {
            val e = ((abs(depth[i] - focus) - tol) / (1f - tol)).coerceIn(0f, 1f).pow(0.8f)
            coc[i] = rMax * e
        }
        if (sj != null) for (i in coc.indices) coc[i] *= 1f - sj[i]
        val step = rMax / LEVELS
        val out = src.copyOf()
        // buffer riutilizzati tra i livelli: niente nuove allocazioni da 3 array grandi quanto la foto per ogni livello
        var prev = src.copyOf()
        var cur = IntArray(w * h)
        val a = IntArray(w * h); val t = IntArray(w * h)
        for (k in 1..LEVELS) {
            blur(src, w, h, step * k, cur, a, t)
            for (i in coc.indices) {
                val f = coc[i] / step
                if (f > k - 1 && (f <= k || k == LEVELS)) out[i] = mix(prev[i], cur[i], (f - (k - 1)).coerceIn(0f, 1f))
            }
            val sw = prev; prev = cur; cur = sw
        }
        return out
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val r = (((a shr 16) and 255) * (1 - t) + ((b shr 16) and 255) * t).toInt()
        val g = (((a shr 8) and 255) * (1 - t) + ((b shr 8) and 255) * t).toInt()
        val bl = ((a and 255) * (1 - t) + (b and 255) * t).toInt()
        return (255 shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun feather(m: BooleanArray, w: Int, h: Int, r: Int): FloatArray {
        val a = IntArray(w * h) { if (m[it]) 255 else 0 }
        val t = IntArray(w * h)
        repeat(2) { boxH(a, t, w, h, r); boxV(t, a, w, h, r) }
        return FloatArray(a.size) { a[it] / 255f }
    }

    /** Sfocatura quasi gaussiana (due passate di box blur separabile) con raggio [r] in pixel. */
    private fun blur(src: IntArray, w: Int, h: Int, r: Float, out: IntArray, a: IntArray, t: IntArray) {
        val b = max(1, (r * 0.6f).roundToInt())
        java.util.Arrays.fill(out, 0)
        for (c in 0..2) {
            val shift = 16 - 8 * c
            for (i in a.indices) a[i] = (src[i] shr shift) and 255
            repeat(2) { boxH(a, t, w, h, b); boxV(t, a, w, h, b) }
            for (i in a.indices) out[i] = out[i] or (a[i].coerceIn(0, 255) shl shift)
        }
        for (i in out.indices) out[i] = out[i] or (255 shl 24)
    }

    private fun boxH(s: IntArray, d: IntArray, w: Int, h: Int, b: Int) {
        val n = 2 * b + 1
        for (y in 0 until h) {
            val base = y * w
            var sum = 0
            for (k in -b..b) sum += s[base + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                d[base + x] = sum / n
                sum += s[base + min(x + b + 1, w - 1)] - s[base + max(x - b, 0)]
            }
        }
    }

    private fun boxV(s: IntArray, d: IntArray, w: Int, h: Int, b: Int) {
        val n = 2 * b + 1
        for (x in 0 until w) {
            var sum = 0
            for (k in -b..b) sum += s[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                d[y * w + x] = sum / n
                sum += s[min(y + b + 1, h - 1) * w + x] - s[max(y - b, 0) * w + x]
            }
        }
    }
}
