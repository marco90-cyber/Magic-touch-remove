package com.marco.magictouchremove

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/** Notifiche dell'app: avanzamento dei download e delle elaborazioni lunghe, avvisi di fine lavoro. */
object Notifier {
    private const val CH_PROGRESS = "progress"
    private const val CH_DONE = "done"
    const val ID_PROGRESS = 1
    private const val ID_DONE = 2

    private fun prefs(c: Context) = c.getSharedPreferences("notify", Context.MODE_PRIVATE)
    fun downloadsOn(c: Context) = prefs(c).getBoolean("downloads", true)
    fun setDownloads(c: Context, on: Boolean) { prefs(c).edit().putBoolean("downloads", on).apply() }
    fun doneOn(c: Context) = prefs(c).getBoolean("done", true)
    fun setDone(c: Context, on: Boolean) { prefs(c).edit().putBoolean("done", on).apply() }
    fun asked(c: Context) = prefs(c).getBoolean("asked", false)
    fun markAsked(c: Context) { prefs(c).edit().putBoolean("asked", true).apply() }

    fun canPost(c: Context) = NotificationManagerCompat.from(c).areNotificationsEnabled()

    fun init(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Avanzamento", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Download dei modelli ed elaborazioni lunghe in corso"
        })
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Lavoro terminato", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Avviso quando un download o una modifica è finita"
        })
    }

    private fun open(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun progressNotification(ctx: Context, title: String, text: String, percent: Int): Notification =
        NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setContentIntent(open(ctx))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (percent in 0..100) setProgress(100, percent, false) else setProgress(0, 0, true) }
            .build()

    fun progress(ctx: Context, title: String, text: String, percent: Int) {
        if (!canPost(ctx)) return
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_PROGRESS, progressNotification(ctx, title, text, percent)) }
    }

    /** Avviso di fine lavoro (resta nell'elenco delle notifiche). */
    fun done(ctx: Context, title: String, text: String) {
        if (!canPost(ctx)) return
        runCatching {
            NotificationManagerCompat.from(ctx).notify(ID_DONE,
                NotificationCompat.Builder(ctx, CH_DONE).setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text)
                    .setAutoCancel(true).setContentIntent(open(ctx)).build())
        }
    }
}

/** Quante schermate dell'app sono visibili: serve a mandare l'avviso solo quando l'app è in secondo piano. */
object AppState {
    @Volatile var started = 0
    val foreground get() = started > 0
}

/** Tiene vivo il processo (servizio in primo piano con notifica) durante download e IA lunghe, anche se l'app viene chiusa dagli ultimi usati. */
object WorkKeeper {
    private val n = AtomicInteger(0)
    fun start(ctx: Context, text: String) {
        if (n.getAndIncrement() == 0) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, WorkService::class.java).putExtra("text", text)) }
                .onFailure { AppLog.log("APP", "servizio in primo piano non avviato: ${it.message}") }
        }
    }
    fun stop(ctx: Context) {
        if (n.decrementAndGet() <= 0) { n.set(0); stopWhenUp(ctx.applicationContext, 0) }
    }
    /** Fermare il servizio prima che abbia chiamato startForeground() manda in crash l'app: si aspetta che sia partito. */
    private fun stopWhenUp(ctx: Context, tries: Int) {
        if (n.get() > 0) return
        if (WorkService.up || tries >= 40) { runCatching { ctx.stopService(Intent(ctx, WorkService::class.java)) }; return }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stopWhenUp(ctx, tries + 1) }, 250)
    }
}

class WorkService : Service() {
    companion object { @Volatile var up = false }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.init(this)
        val n = Notifier.progressNotification(this, "Magic Touch Remove", intent?.getStringExtra("text") ?: "Lavoro in corso…", -1)
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(Notifier.ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(Notifier.ID_PROGRESS, n)
        }
        up = true
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        up = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
