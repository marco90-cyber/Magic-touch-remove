package com.marco.magictouchremove

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File

/** Lavoro in corso salvato quando si esce dall'app, per riprenderlo dalla home ("Continua"). */
object ProjectStore {
    /** Cambia a ogni salvataggio/cancellazione: la home lo osserva per aggiornarsi. */
    var rev by mutableIntStateOf(0); private set

    class Saved(val image: Bitmap, val source: Bitmap, val w: Int, val h: Int, val uri: String?)

    private fun dir(ctx: Context) = File(ctx.filesDir, "project")

    fun exists(ctx: Context) = File(dir(ctx), "meta.json").exists() && File(dir(ctx), "image.png").exists()

    @Synchronized
    fun save(ctx: Context, image: Bitmap, source: Bitmap, w: Int, h: Int, uri: String?) {
        val d = dir(ctx); d.mkdirs()
        File(d, "meta.json").delete()
        write(image, File(d, "image.png"))
        val same = image === source
        if (!same) write(source, File(d, "source.png")) else File(d, "source.png").delete()
        File(d, "meta.json").writeText(JSONObject().put("w", w).put("h", h).put("uri", uri ?: "").put("same", same).toString())
        rev++
    }

    private fun write(b: Bitmap, f: File) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(f)
    }

    @Synchronized
    fun clear(ctx: Context) {
        dir(ctx).deleteRecursively()
        rev++
    }

    /** Miniatura piccola per la card della home. */
    fun thumb(ctx: Context): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inSampleSize = 16 }
        BitmapFactory.decodeFile(File(dir(ctx), "image.png").path, o)
    }.getOrNull()

    @Synchronized
    fun load(ctx: Context): Saved? = runCatching {
        val d = dir(ctx)
        val m = JSONObject(File(d, "meta.json").readText())
        val opt = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val img = BitmapFactory.decodeFile(File(d, "image.png").path, opt) ?: return null
        val src = if (m.optBoolean("same")) img.copy(Bitmap.Config.ARGB_8888, true)
        else BitmapFactory.decodeFile(File(d, "source.png").path, opt) ?: return null
        Saved(img, src, m.getInt("w"), m.getInt("h"), m.optString("uri").ifEmpty { null })
    }.getOrNull()
}
