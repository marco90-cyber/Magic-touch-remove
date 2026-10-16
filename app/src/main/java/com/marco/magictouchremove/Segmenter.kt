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
class Segmenter private constructor(encoderPath: String, decoderPath: String) {
    private val env = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val decoder: OrtSession
    private val encoderInput: String

    private var embeddedFor: Bitmap? = null
    private var embedding: FloatArray? = null

    init {
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(4)
        encoder = env.createSession(encoderPath, opts)
        decoder = env.createSession(decoderPath, OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) })
        encoderInput = encoder.inputNames.first()
    }

    /** Analizza la foto (una volta sola per bitmap). L'encoder vuole la foto con il lato lungo = 1024. */
    @Synchronized
    fun ensureEncoded(img: Bitmap) {
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
    fun select(img: Bitmap, point: PointF?, box: RectF?): BooleanArray? {
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

    companion object {
        private const val TARGET = 1024
        @Volatile private var instance: Segmenter? = null

        fun get(ctx: Context): Segmenter = instance ?: synchronized(this) {
            instance ?: Segmenter(
                ModelManager.samEncoder(ctx).absolutePath,
                ModelManager.samDecoder(ctx).absolutePath
            ).also { instance = it }
        }
    }
}
