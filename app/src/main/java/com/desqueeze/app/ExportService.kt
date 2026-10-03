package com.desqueeze.app

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
import android.os.PowerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Process-wide home for the app state and running exports, so an export keeps going when the
 * screen is off or you switch apps, and the UI finds it again when you come back.
 */
object ExportController {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var state: AppState? = null
    var exporter: Exporter? = null

    fun start(ctx: Context, st: AppState, exporter: Exporter) {
        val app = ctx.applicationContext
        val list = st.videos
        st.busy = true; st.progress = 0f; st.results = emptyList(); st.status = ""
        Diag.start()
        st.job = scope.launch {
            val log = mutableListOf<String>()
            list.forEachIndexed { i, vid ->
                val m = modeFor(st, exporter, vid)
                st.status = (if (m == ExportMode.LOSSLESS) "Copying" else "Exporting") + if (list.size > 1) " ${i + 1} of ${list.size}" else " ${vid.name}"
                try {
                    val j = st.jobFor(vid)
                    val main = android.os.Handler(android.os.Looper.getMainLooper())
                    val prog: (Int) -> Unit = { p -> main.post { st.progress = (i + p / 100f) / list.size } }
                    Diag.step("Clip ${i + 1}/${list.size}: ${specLine(vid)}, ${vid.sizeBytes / 1_048_576} MB, mode=$m, squeeze=${st.effectiveSqueeze(vid)}, " +
                        "trim=${j.trim}, format=${j.format}${if (j.fill) " fill" else ""}, lut=${st.lutId != null}")
                    val r = if (m == ExportMode.LOSSLESS) exporter.exportLossless(j, prog) else exporter.export(j, prog)
                    log += "✓  ${r.name}\n    ${r.width} × ${r.height}" + (r.note?.let { "\n    $it" } ?: "")
                } catch (e: CancellationException) { throw e
                } catch (e: Throwable) {
                    Diag.step("FAILED: ${e.javaClass.simpleName}: ${e.message}")
                    log += "✗  ${vid.name}\n    ${e.message ?: e.javaClass.simpleName}"
                }
            }
            st.results = log; st.status = ""; st.busy = false
        }
        // Keeps the process alive with a progress notification while the job runs.
        try {
            val i = Intent(app, ExportService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        } catch (e: Exception) {
            Diag.step("Background service not started: $e") // export still runs while the app is open
        }
    }

    fun cancel(st: AppState) {
        st.job?.cancel(); st.busy = false; st.status = "Export cancelled."
    }
}

class ExportService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val st = ExportController.state
        if (intent?.action == ACTION_CANCEL) { st?.let { ExportController.cancel(it) }; finish(cancelled = true); return START_NOT_STICKY }
        channel()
        val type = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, progress(st), type) else startForeground(ID, progress(st))
        } catch (e: Exception) { Diag.step("startForeground failed: $e"); stopSelf(); return START_NOT_STICKY }
        if (wake == null) wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "desqueeze:export").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        watcher?.cancel()
        watcher = ExportController.scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            while (st != null && st.busy) { nm.notify(ID, progress(st)); delay(1000) }
            finish(cancelled = false)
        }
        return START_NOT_STICKY
    }

    /** Android 15+: the system stops long media-processing jobs after several hours. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ExportController.state?.let { ExportController.cancel(it); it.status = "Android stopped the export after its time limit." }
        finish(cancelled = true)
    }

    private fun finish(cancelled: Boolean) {
        watcher?.cancel()
        val st = ExportController.state
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        if (!cancelled && st != null && st.results.isNotEmpty()) {
            val ok = st.results.count { it.startsWith("✓") }; val bad = st.results.size - ok
            nm.notify(ID_DONE, base().setContentTitle(if (bad == 0) "Export finished" else "Export finished with problems")
                .setContentText(if (bad == 0) "$ok saved to Movies" else "$ok saved, $bad failed. Tap for details.")
                .setSmallIcon(android.R.drawable.stat_sys_download_done).setAutoCancel(true).build())
        }
        wake?.let { if (it.isHeld) it.release() }; wake = null
        stopSelf()
    }

    override fun onDestroy() { watcher?.cancel(); wake?.let { if (it.isHeld) it.release() }; super.onDestroy() }

    private fun channel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Exports", NotificationManager.IMPORTANCE_LOW).apply { description = "Progress of de-squeeze exports" })
    }

    private fun base(): Notification.Builder {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setContentIntent(open)
    }

    private fun progress(st: AppState?): Notification {
        val cancel = PendingIntent.getService(this, 1, Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pct = ((st?.progress ?: 0f) * 100).toInt()
        return base().setContentTitle(st?.status?.ifBlank { null } ?: "Exporting")
            .setContentText("$pct%  ·  you can keep using your phone")
            .setSmallIcon(android.R.drawable.stat_sys_upload).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, pct, false)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Cancel", cancel).build())
            .build()
    }

    companion object {
        const val CHANNEL = "exports"; const val ID = 42; const val ID_DONE = 43
        const val ACTION_CANCEL = "com.desqueeze.app.CANCEL_EXPORT"
    }
}
