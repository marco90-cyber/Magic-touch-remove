package com.marco.magictouchremove

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Selezione automatica degli oggetti con MobileSAM (Segment Anything, ONNX).
 * L'encoder analizza la foto una sola volta; il decoder, rapidissimo, produce la maschera
 * a partire da un tocco o da un riquadro.
 */
abstract class Segmenter {
    abstract fun ensureEncoded(img: Bitmap)
    abstract fun select(img: Bitmap, point: PointF?, box: RectF?): BooleanArray?
    abstract fun close()

    companion object {
        @Volatile private var instance: Segmenter? = null
        @Volatile private var instanceId: String? = null

        fun release() = synchronized(this) { instance?.close(); instance = null; instanceId = null }

        fun get(ctx: Context): Segmenter = synchronized(this) {
            val opt = ModelManager.active(ctx, Kind.SELECT) ?: error("Nessun modello di selezione scaricato")
            if (instance == null || instanceId != opt.id) {
                instance?.close()
                val e = ModelManager.file(ctx, opt.files[0]).absolutePath
                val d = ModelManager.file(ctx, opt.files[1]).absolutePath
                instance = if (opt.id.startsWith("sam2")) Sam2Segmenter(e, d) else MobileSamSegmenter(e, d)
                instanceId = opt.id
            }
            instance!!
        }
    }
}

private const val TARGET = 1024

class MobileSamSegmenter(encoderPath: String, decoderPath: String) : Segmenter() {
    private val env = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val decoder: OrtSession
    private val encoderInput: String

    private var embeddedFor: Bitmap? = null
    private var embedding: FloatArray? = null

    init {
        encoder = Accel.session(env, encoderPath)
        decoder = Accel.session(env, decoderPath)
        encoderInput = encoder.inputNames.first()
    }

    /** Analizza la foto (una volta sola per bitmap). L'encoder vuole la foto con il lato lungo = 1024. */
    @Synchronized
    override fun ensureEncoded(img: Bitmap) {
        if (embeddedFor === img && embedding != null) return
        val w = img.width
        val h = img.height
        val s = TARGET.toFloat() / max(w, h)
        val rw = (w * s).roundToInt().coerceAtLeast(1)
        val rh = (h * s).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(img, rw, rh, true)
        val px = IntArray(rw * rh)
        scaled.getPixels(px, 0, rw, 0, 0, rw, rh)
        val arr = FloatArray(rw * rh * 3)
        for (i in px.indices) {
            val p = px[i]
            arr[3 * i] = ((p shr 16) and 255).toFloat()
            arr[3 * i + 1] = ((p shr 8) and 255).toFloat()
            arr[3 * i + 2] = (p and 255).toFloat()
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(arr), longArrayOf(rh.toLong(), rw.toLong(), 3)).use { t ->
            encoder.run(mapOf(encoderInput to t)).use { res ->
                val fb = (res[0] as OnnxTensor).floatBuffer
                val e = FloatArray(fb.remaining())
                fb.get(e)
                embedding = e
            }
        }
        embeddedFor = img
    }

    /**
     * @return maschera booleana w*h dell'oggetto, oppure null se non trovato.
     * Un tocco ([point]) oppure un riquadro ([box]), in coordinate dell'immagine.
     */
    @Synchronized
    override fun select(img: Bitmap, point: PointF?, box: RectF?): BooleanArray? {
        ensureEncoded(img)
        val emb = embedding ?: return null
        val w = img.width
        val h = img.height
        val s = TARGET.toFloat() / max(w, h)

        val coords: FloatArray
        val labels: FloatArray
        if (box != null) {
            coords = floatArrayOf(box.left * s, box.top * s, box.right * s, box.bottom * s)
            labels = floatArrayOf(2f, 3f)
        } else if (point != null) {
            coords = floatArrayOf(point.x * s, point.y * s, 0f, 0f)   // secondo punto = riempimento
            labels = floatArrayOf(1f, -1f)
        } else return null
        val n = labels.size.toLong()

        return OnnxTensor.createTensor(env, FloatBuffer.wrap(emb), longArrayOf(1, 256, 64, 64)).use { te ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(coords), longArrayOf(1, n, 2)).use { tc ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(labels), longArrayOf(1, n)).use { tl ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(256 * 256)), longArrayOf(1, 1, 256, 256)).use { tm ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1)).use { th ->
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(h.toFloat(), w.toFloat())), longArrayOf(2)).use { to ->
                                val inputs = mapOf(
                                    "image_embeddings" to te, "point_coords" to tc, "point_labels" to tl,
                                    "mask_input" to tm, "has_mask_input" to th, "orig_im_size" to to
                                )
                                decoder.run(inputs).use { res ->
                                    val masks = (res[0] as OnnxTensor).floatBuffer
                                    val iou = (res[1] as OnnxTensor).floatBuffer
                                    val count = iou.remaining()
                                    // riquadro: maschera 0; singolo tocco: la migliore tra le maschere 1..n
                                    var idx = 0
                                    if (box == null && count > 1) {
                                        var best = -1f
                                        for (i in 1 until count) if (iou.get(i) > best) { best = iou.get(i); idx = i }
                                    }
                                    val off = idx * w * h
                                    val out = BooleanArray(w * h)
                                    var any = false
                                    for (i in out.indices) if (masks.get(off + i) > 0f) { out[i] = true; any = true }
                                    if (any) out else null
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        runCatching { encoder.close() }
        runCatching { decoder.close() }
        embedding = null; embeddedFor = null
    }
}

/** SAM 2.1 Small (Meta): foto 1024×1024 normalizzata; le maschere escono a 256×256 e vengono ingrandite in modo morbido. */
class Sam2Segmenter(encoderPath: String, decoderPath: String) : Segmenter() {
    private val env = OrtEnvironment.getEnvironment()
    private val encoder = Accel.session(env, encoderPath)
    private val decoder = Accel.session(env, decoderPath)
    private var embeddedFor: Bitmap? = null
    private var embed: FloatArray? = null
    private var feat0: FloatArray? = null
    private var feat1: FloatArray? = null

    @Synchronized
    override fun ensureEncoded(img: Bitmap) {
        if (embeddedFor === img && embed != null) return
        val scaled = Bitmap.createScaledBitmap(img, TARGET, TARGET, true)
        val px = IntArray(TARGET * TARGET)
        scaled.getPixels(px, 0, TARGET, 0, 0, TARGET, TARGET)
        val plane = TARGET * TARGET
        val arr = FloatArray(3 * plane)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        for (i in 0 until plane) {
            val p = px[i]
            arr[i] = (((p shr 16) and 255) / 255f - mean[0]) / std[0]
            arr[plane + i] = (((p shr 8) and 255) / 255f - mean[1]) / std[1]
            arr[2 * plane + i] = ((p and 255) / 255f - mean[2]) / std[2]
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(arr), longArrayOf(1, 3, TARGET.toLong(), TARGET.toLong())).use { t ->
            encoder.run(mapOf(encoder.inputNames.first() to t)).use { res ->
                fun grab(name: String): FloatArray {
                    val fb = (res.get(name).get() as OnnxTensor).floatBuffer
                    return FloatArray(fb.remaining()).also { fb.get(it) }
                }
                feat0 = grab("high_res_feats_0")
                feat1 = grab("high_res_feats_1")
                embed = grab("image_embed")
            }
        }
        embeddedFor = img
    }

    @Synchronized
    override fun select(img: Bitmap, point: PointF?, box: RectF?): BooleanArray? {
        ensureEncoded(img)
        val e = embed ?: return null
        val f0 = feat0 ?: return null
        val f1 = feat1 ?: return null
        val w = img.width
        val h = img.height
        val sx = TARGET.toFloat() / w
        val sy = TARGET.toFloat() / h
        val coords: FloatArray
        val labels: FloatArray
        if (box != null) {
            coords = floatArrayOf(box.left * sx, box.top * sy, box.right * sx, box.bottom * sy)
            labels = floatArrayOf(2f, 3f)
        } else if (point != null) {
            coords = floatArrayOf(point.x * sx, point.y * sy)
            labels = floatArrayOf(1f)
        } else return null
        val n = labels.size.toLong()

        return OnnxTensor.createTensor(env, FloatBuffer.wrap(e), longArrayOf(1, 256, 64, 64)).use { te ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(f0), longArrayOf(1, 32, 256, 256)).use { t0 ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(f1), longArrayOf(1, 64, 128, 128)).use { t1 ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(coords), longArrayOf(1, n, 2)).use { tc ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(labels), longArrayOf(1, n)).use { tl ->
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(256 * 256)), longArrayOf(1, 1, 256, 256)).use { tm ->
                                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1)).use { th ->
                                    val inputs = mapOf(
                                        "image_embed" to te, "high_res_feats_0" to t0, "high_res_feats_1" to t1,
                                        "point_coords" to tc, "point_labels" to tl, "mask_input" to tm, "has_mask_input" to th
                                    )
                                    decoder.run(inputs).use { res ->
                                        val masks = (res[0] as OnnxTensor).floatBuffer
                                        val iou = (res[1] as OnnxTensor).floatBuffer
                                        val count = iou.remaining().coerceAtMost(3)
                                        var idx = 0
                                        var best = -1e9f
                                        for (i in 0 until count) if (iou.get(i) > best) { best = iou.get(i); idx = i }
                                        val off = idx * 256 * 256
                                        upsample(masks, off, w, h)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Ingrandimento bilineare dei valori (logit) 256×256 a w×h, poi soglia a 0. */
    private fun upsample(m: java.nio.FloatBuffer, off: Int, w: Int, h: Int): BooleanArray? {
        val out = BooleanArray(w * h)
        var any = false
        val xi0 = IntArray(w); val xi1 = IntArray(w); val xf = FloatArray(w)
        for (x in 0 until w) {
            val fx = ((x + 0.5f) * 256f / w - 0.5f).coerceIn(0f, 255f)
            xi0[x] = fx.toInt(); xi1[x] = minOf(255, xi0[x] + 1); xf[x] = fx - xi0[x]
        }
        for (y in 0 until h) {
            val fy = ((y + 0.5f) * 256f / h - 0.5f).coerceIn(0f, 255f)
            val y0 = fy.toInt(); val y1 = minOf(255, y0 + 1); val ay = fy - y0
            val r0 = off + y0 * 256; val r1 = off + y1 * 256
            for (x in 0 until w) {
                val a = xf[x]
                val top = m.get(r0 + xi0[x]) * (1 - a) + m.get(r0 + xi1[x]) * a
                val bot = m.get(r1 + xi0[x]) * (1 - a) + m.get(r1 + xi1[x]) * a
                if (top * (1 - ay) + bot * ay > 0f) { out[y * w + x] = true; any = true }
            }
        }
        return if (any) out else null
    }

    @Synchronized
    override fun close() {
        runCatching { encoder.close() }
        runCatching { decoder.close() }
        embed = null; feat0 = null; feat1 = null; embeddedFor = null
    }
}
