package com.desqueeze.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostics so a crash can be fixed from one copied report:
 * - export breadcrumbs (which step was running),
 * - Java crashes (caught by MainActivity),
 * - native crashes, freezes (ANR) and low-memory kills, read back from Android itself.
 */
object Diag {
    private lateinit var dir: File
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    fun init(ctx: Context) { dir = ctx.filesDir }
    private val steps get() = File(dir, "steps.txt")

    fun start() { try { steps.writeText("") } catch (_: Throwable) {} }
    fun step(msg: String) { try { steps.appendText("${fmt.format(Date())}  $msg\n") } catch (_: Throwable) {} }
    fun lastSteps(): String = try { steps.readLines().takeLast(25).joinToString("\n") } catch (_: Throwable) { "" }

    fun header(ctx: Context) = "Version ${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName}, " +
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, $socModel"

    /** Report for the previous run if Android says it died abnormally (once per event). */
    fun previousExit(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val info = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() ?: return null
            val prefs = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE)
            if (info.timestamp <= prefs.getLong("seen", 0)) return null
            prefs.edit().putLong("seen", info.timestamp).apply()
            val reason = when (info.reason) {
                ApplicationExitInfo.REASON_CRASH -> "Java crash"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash (system/codec level)"
                ApplicationExitInfo.REASON_ANR -> "App froze (ANR)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "Killed for low memory"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Killed for excessive resource use"
                ApplicationExitInfo.REASON_SIGNALED -> "Killed by signal ${info.status}"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization failure"
                else -> return null // normal exits, user swipe-away, updates…
            }
            val trace = if (info.reason == ApplicationExitInfo.REASON_ANR) try {
                info.traceInputStream?.bufferedReader()?.use { r ->
                    val t = r.readText(); val i = t.indexOf("\"main\"")
                    if (i >= 0) t.substring(i, minOf(t.length, i + 3500)) else t.take(3500)
                }
            } catch (_: Throwable) { null } else null
            buildString {
                appendLine(header(ctx)); appendLine()
                appendLine("Exit: $reason"); info.description?.let { appendLine("Detail: $it") }
                appendLine("Memory at exit: ${info.pss / 1024} MB"); appendLine()
                appendLine("Last export steps:"); appendLine(lastSteps().ifEmpty { "(none)" })
                trace?.let { appendLine(); appendLine("Main thread:"); appendLine(it) }
            }
        } catch (_: Throwable) { null }
    }
}

private val socModel: String get() = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "SoC unknown"
