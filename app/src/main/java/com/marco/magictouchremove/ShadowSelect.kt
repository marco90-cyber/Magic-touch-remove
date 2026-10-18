package com.marco.magictouchremove

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Trova l'ombra di un oggetto selezionato: pixel vicini all'oggetto, più scuri del terreno circostante
 * ma con lo stesso colore (un'ombra scurisce senza cambiare tinta). Metodo classico, senza modelli: istantaneo e offline.
 */
object ShadowSelect {
    private const val WORK = 900   // lato massimo di lavoro: la foto viene ridotta per velocità

    /** [raw] = oggetto selezionato (true = selezionato). [strength] 0..1: più alto = ombra più ampia. Restituisce l'ombra a piena risoluzione. */
    fun grow(img: Bitmap, raw: BooleanArray, strength: Float): BooleanArray {
        val w = img.width; val h = img.height
        val out = BooleanArray(w * h)
        if (strength <= 0f) return out
        val f = min(1f, WORK.toFloat() / max(w, h))
        val sw = max(1, (w * f).toInt()); val sh = max(1, (h * f).toInt())
        val small = if (f < 1f) Bitmap.createScaledBitmap(img, sw, sh, true) else img
        val px = IntArray(sw * sh); small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== img) small.recycle()
        val n = sw * sh
        val m = BooleanArray(n)
        for (y in 0 until sh) {
            val fy = min(h - 1, (y / f).toInt())
            for (x in 0 until sw) m[y * sw + x] = raw[fy * w + min(w - 1, (x / f).toInt())]
        }

        // distanza dall'oggetto (BFS a 8 vicini), fino a 2R
        val reach = max(8, (max(sw, sh) * 0.15f).toInt())
        val dist = IntArray(n) { -1 }
        val q = IntArray(n); var qh = 0; var qt = 0
        for (i in 0 until n) if (m[i]) { dist[i] = 0; q[qt++] = i }
        if (qt == 0) return out
        while (qh < qt) {
            val i = q[qh++]; val d = dist[i]
            if (d >= 2 * reach) continue
            val x = i % sw; val y = i / sw
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val xx = x + dx; val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= sw || yy >= sh) continue
                val j = yy * sw + xx
                if (dist[j] < 0) { dist[j] = d + 1; q[qt++] = j }
            }
        }

        // luminosità e tinta del terreno: pixel tra 2 e 2R dall'oggetto, parte più chiara (il terreno illuminato)
        val lum = IntArray(n)
        for (i in 0 until n) { val p = px[i]; lum[i] = (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000 }
        val hist = IntArray(256); var cnt = 0
        for (i in 0 until n) if (!m[i] && dist[i] in 2..(2 * reach)) { hist[lum[i]]++; cnt++ }
        if (cnt < 80) return out
        var acc = 0; var lref = 0
        for (v in 0..255) { acc += hist[v]; if (acc >= cnt * 0.7f) { lref = v; break } }
        if (lref < 30) return out     // scena troppo scura: l'ombra non è distinguibile
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var c = 0
        for (i in 0 until n) if (!m[i] && dist[i] in 2..(2 * reach) && lum[i] >= lref * 0.9f) {
            val p = px[i]; val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            val t = r + g + b + 1.0
            sr += r / t; sg += g / t; sb += b / t; c++
        }
        if (c < 30) return out
        sr /= c; sg /= c; sb /= c

        val dark = 0.30f - 0.22f * strength          // quanto più scuro del terreno (30% … 8%)
        val tone = 0.03f + 0.05f * strength          // tolleranza sulla tinta
        val limit = lref * (1f - dark)
        fun isShadow(i: Int): Boolean {
            if (lum[i] > limit) return false
            val p = px[i]; val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            val t = r + g + b + 1.0
            val dr = r / t - sr; val dg = g / t - sg; val db = b / t - sb
            return sqrt(dr * dr + dg * dg + db * db) <= tone
        }

        // crescita della regione dall'oggetto, solo dentro R e solo su pixel «da ombra»
        val shadow = BooleanArray(n)
        qh = 0; qt = 0
        for (i in 0 until n) if (m[i]) q[qt++] = i
        while (qh < qt) {
            val i = q[qh++]
            val x = i % sw; val y = i / sw
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val xx = x + dx; val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= sw || yy >= sh) continue
                val j = yy * sw + xx
                if (m[j] || shadow[j] || dist[j] < 0 || dist[j] > reach) continue
                if (isShadow(j)) { shadow[j] = true; q[qt++] = j }
            }
        }
        if (qt == 0) return out

        // bordo morbido (penombra): allarga di 2 pixel
        var cur = shadow
        repeat(2) {
            val nx = cur.copyOf()
            for (y in 0 until sh) for (x in 0 until sw) {
                val i = y * sw + x
                if (nx[i] || m[i]) continue
                var hit = false
                loop@ for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= sw || yy >= sh) continue
                    if (cur[yy * sw + xx]) { hit = true; break@loop }
                }
                if (hit) nx[i] = true
            }
            cur = nx
        }
        for (y in 0 until h) {
            val sy = min(sh - 1, (y * f).toInt())
            for (x in 0 until w) out[y * w + x] = cur[sy * sw + min(sw - 1, (x * f).toInt())]
        }
        return out
    }
}
