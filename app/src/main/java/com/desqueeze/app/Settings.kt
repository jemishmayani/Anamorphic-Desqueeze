package com.desqueeze.app

import android.content.Context

enum class Quality(val label: String, val bitsPerPixel: Float) {
    MAX("Maximum Quality", 0.30f), HIGH("High Quality", 0.18f), BALANCED("Balanced", 0.10f), SMALL("Smaller File", 0.05f)
}
enum class Codec(val label: String) { HEVC("HEVC / H.265 (preferred)"), H264("H.264 (compatibility)") }
enum class ExportMode { LOSSLESS, REENCODE }
enum class ThemeMode(val label: String) { SYSTEM("System"), DARK("Dark"), LIGHT("Light") }

val PRESETS = listOf(1.2f, 1.33f, 1.5f, 1.55f, 1.6f, 1.8f, 2.0f)

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var defaultSqueeze: Float get() = p.getFloat("squeeze", 1.33f); set(v) = p.edit().putFloat("squeeze", v).apply()
    var quality: Quality get() = Quality.valueOf(p.getString("quality", Quality.HIGH.name)!!); set(v) = p.edit().putString("quality", v.name).apply()
    var codec: Codec get() = Codec.valueOf(p.getString("codec", Codec.HEVC.name)!!); set(v) = p.edit().putString("codec", v.name).apply()
    var keepHdr: Boolean get() = p.getBoolean("hdr", true); set(v) = p.edit().putBoolean("hdr", v).apply()
    var preserveMeta: Boolean get() = p.getBoolean("meta", true); set(v) = p.edit().putBoolean("meta", v).apply()
    var folder: String get() = p.getString("folder", "AnamorphicDesqueeze")!!; set(v) = p.edit().putString("folder", v).apply()
    var mode: ExportMode get() = ExportMode.valueOf(p.getString("mode", ExportMode.LOSSLESS.name)!!); set(v) = p.edit().putString("mode", v.name).apply()
    /** New clips use their own recommendation unless the user picks a fixed default method. */
    var followRecommendation: Boolean get() = p.getBoolean("followRec", true); set(v) = p.edit().putBoolean("followRec", v).apply()
    var theme: ThemeMode get() = ThemeMode.valueOf(p.getString("theme", ThemeMode.SYSTEM.name)!!); set(v) = p.edit().putString("theme", v.name).apply()
}

fun fmtSqueeze(f: Float): String = (if (f * 100 % 10 == 0f) "%.1f" else "%.2f").format(f) + "×"
