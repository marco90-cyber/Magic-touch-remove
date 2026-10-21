package com.marco.magictouchremove

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro eventi dell'app: sopravvive alle chiusure anomale e si può salvare dalle Impostazioni. */
object AppLog {
    private var file: File? = null
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss.SSS", Locale.ITALY)
    private const val MAX = 400_000L

    fun init(ctx: Context) {
        if (file != null) { log("APP", "schermata ricreata (stesso processo)"); return }   // onCreate richiamato senza nuovo avvio
        file = File(ctx.filesDir, "app_log.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            log("CRASH", "eccezione non gestita su ${t.name}: ${e.stackTraceToString()}")
            prev?.uncaughtException(t, e)
        }
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val v = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        log("AVVIO", "app $v · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "SoC ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "?"} · ABI ${Build.SUPPORTED_ABIS.firstOrNull()} · " +
            "RAM ${mi.totalMem shr 20} MB (libera ${mi.availMem shr 20} MB) · limite heap ${am.memoryClass}/${am.largeMemoryClass} MB · NPU ${if (Accel.enabled()) "sì (${Accel.soc()})" else "no"}")
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5).forEach { r ->
                log("USCITA", "${fmt.format(Date(r.timestamp))} · ${reason(r.reason)} · ${r.description ?: ""} · RSS ${r.rss shr 10} MB · PSS ${r.pss shr 10} MB")
            }
        }
    }

    private fun reason(r: Int) = when (r) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "memoria insufficiente (LOW_MEMORY)"
        ApplicationExitInfo.REASON_CRASH -> "crash Java"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash nativo"
        ApplicationExitInfo.REASON_SIGNALED -> "segnale del sistema"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "uso eccessivo di risorse"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "chiusa dall'utente"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_EXIT_SELF -> "uscita normale"
        else -> "codice $r"
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX) f.writeText(f.readText().takeLast((MAX / 2).toInt()))
            f.appendText("${fmt.format(Date())} [$tag] $msg\n")
        }
    }

    fun text(): String = runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()
    fun clear() { runCatching { file?.delete() } }
}
