package com.marco.magictouchremove

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Accelerazione hardware: prova l'NPU Qualcomm (QNN/HTP) e, se non c'è, non parte o dà errore,
 * ripiega sulla CPU senza che l'utente se ne accorga. Se l'NPU manda in crash l'app viene disattivata da sola.
 */
object Accel {
    private lateinit var app: Context
    /** Tiene lo schermo acceso mentre l'IA lavora. */
    var keepScreen by androidx.compose.runtime.mutableStateOf(true)
        private set
    fun updateKeepScreen(on: Boolean) { keepScreen = on; prefs.edit().putBoolean("keep_screen", on).apply() }
    private val prefs get() = app.getSharedPreferences("accel", Context.MODE_PRIVATE)
    private val marker get() = File(app.filesDir, "npu_try")

    /** Chiamare all'avvio, prima di creare sessioni. */
    fun init(ctx: Context) {
        app = ctx.applicationContext
        keepScreen = prefs.getBoolean("keep_screen", true)
        if (marker.exists()) {
            val what = runCatching { marker.readText() }.getOrDefault("?")
            marker.delete()
            prefs.edit().putBoolean("auto_off", true).apply()
            AppLog.log("NPU", "disattivata automaticamente: l'app si è chiusa mentre usava l'NPU ($what)")
        }
        AppLog.log("NPU", "chip: ${soc()} · ${if (supported()) "NPU Qualcomm rilevata" else "NPU non supportata"} · ${if (enabled()) "attiva" else "disattivata"}")
    }

    fun soc(): String = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
    /** L'NPU gestita dall'app è quella dei Qualcomm Snapdragon (Hexagon). */
    fun supported(): Boolean =
        Build.VERSION.SDK_INT >= 31 && Build.SOC_MANUFACTURER.equals("QTI", true) ||
            Build.HARDWARE.equals("qcom", true) || Build.BOARD.lowercase().let { it.startsWith("sm") || it.startsWith("sdm") }
    fun enabled(): Boolean = ::app.isInitialized && supported() && prefs.getBoolean("use_npu", false) && !prefs.getBoolean("auto_off", false)
    fun autoOff(): Boolean = ::app.isInitialized && prefs.getBoolean("auto_off", false)
    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("use_npu", on).putBoolean("auto_off", false).apply()
        releaseAll()
    }

    /** Opzioni comuni per la CPU. */
    fun cpuOptions(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
    }

    /** Crea una sessione: prima sull'NPU (se possibile), altrimenti sulla CPU. */
    fun session(env: OrtEnvironment, path: String): OrtSession {
        val name = File(path).name
        if (enabled()) {
            val t0 = System.currentTimeMillis()
            try {
                marker.writeText(name)
                val o = cpuOptions()
                o.addQnn(mapOf(
                    "backend_path" to "libQnnHtp.so",
                    "htp_performance_mode" to "burst",
                    "enable_htp_fp16_precision" to "1"
                ))
                val s = env.createSession(path, o)
                marker.delete()
                AppLog.log("NPU", "$name: sessione NPU creata in ${System.currentTimeMillis() - t0} ms")
                return s
            } catch (e: Throwable) {
                marker.delete()
                AppLog.log("NPU", "$name: NPU non usata (${e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName}), uso la CPU")
            }
        }
        return env.createSession(path, cpuOptions())
    }
}
