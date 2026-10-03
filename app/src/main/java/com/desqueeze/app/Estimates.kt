package com.desqueeze.app

import android.content.Context

/** Learns this phone's real speeds from finished exports, so estimates improve over time. */
object Speed {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("speed", Context.MODE_PRIVATE)
    /** Pixels per second through decode + GPU + encode. Default ≈ 4K30 in real time. */
    fun encodePps(ctx: Context) = prefs(ctx).getFloat("pps", 3840f * 2160f * 30f)
    /** Bytes per second for Lossless copies. Default 120 MB/s. */
    fun copyBps(ctx: Context) = prefs(ctx).getFloat("bps", 120f * 1024 * 1024)
    fun learned(ctx: Context) = prefs(ctx).contains("pps") || prefs(ctx).contains("bps")

    fun recordEncode(ctx: Context, pixels: Double, secs: Double) {
        if (secs < 2 || pixels <= 0) return
        val now = (pixels / secs).toFloat(); val old = prefs(ctx).getFloat("pps", now)
        prefs(ctx).edit().putFloat("pps", old * 0.4f + now * 0.6f).apply()
    }
    fun recordCopy(ctx: Context, bytes: Long, secs: Double) {
        if (secs < 1 || bytes <= 0) return
        val now = (bytes / secs).toFloat(); val old = prefs(ctx).getFloat("bps", now)
        prefs(ctx).edit().putFloat("bps", old * 0.4f + now * 0.6f).apply()
    }
}

data class Estimate(val bytes: Long, val seconds: Double, val outW: Int, val outH: Int, val scaledNote: String?)

fun estimate(ctx: Context, exporter: Exporter, settings: Settings, v: VideoInfo, g: Geometry, mode: ExportMode,
             lengthMs: Long = v.durationMs, format: OutFormat = OutFormat.ORIGINAL): Estimate {
    val part = if (v.durationMs > 0) lengthMs.toDouble() / v.durationMs else 1.0
    if (mode == ExportMode.LOSSLESS) {
        val bytes = (v.sizeBytes * part).toLong()
        return Estimate(bytes, bytes / Speed.copyBps(ctx).toDouble() + 1.0, g.outW, g.outH, null)
    }
    val mime = if (settings.codec == Codec.HEVC && Exporter.hasEncoder("video/hevc")) "video/hevc" else "video/avc"
    val (w, h, note) = exporter.targetSize(g, v.fps, mime, format)
    val secs = lengthMs / 1000.0
    val fps = if (v.fps > 0) v.fps else 30f
    val audioBps = if (v.hasAudio) 256_000L else 0L
    val bytes = ((exporter.bitrateFor(w, h, v.fps, mime) + audioBps) * secs / 8).toLong()
    val time = w.toDouble() * h * fps * secs / Speed.encodePps(ctx) * 1.1 + bytes / Speed.copyBps(ctx) + 2
    return Estimate(bytes, time, w, h, note)
}

fun fmtEta(s: Double): String = when {
    s < 60 -> "~${maxOf(5, (s / 5).toInt() * 5 + 5)} s"
    s < 3600 -> "~${(s / 60).toInt() + 1} min"
    else -> "~%.1f h".format(s / 3600)
}
