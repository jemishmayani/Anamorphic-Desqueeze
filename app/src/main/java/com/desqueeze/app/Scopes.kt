package com.desqueeze.app

/** Exposure tools shown over the preview. */
enum class Scope(val label: String) { OFF("Off"), HISTOGRAM("Histogram"), WAVEFORM("Waveform"), FALSE_COLOR("False color") }

/**
 * Pure math for the exposure tools, computed from a small RGB frame of the picture as shown
 * (log, or with the LUT if it's on). Values are in IRE: 0 = black, 100 = white (full-range RGB).
 */
object ScopeMath {
    /** Rec.709 luma (0..1) from a packed RGB pixel. */
    fun luma(p: Int): Float = (0.2126f * (p shr 16 and 255) + 0.7152f * (p shr 8 and 255) + 0.0722f * (p and 255)) / 255f

    class Stats(
        /** 64 bins, normalized so the tallest is 1. */
        val histogram: FloatArray,
        /** Percent of pixels at or below 2 IRE (crushed) and at or above 98 IRE (clipped). */
        val crushedPct: Float, val clippedPct: Float,
        /** Luma below which 5% / 50% / 95% of the picture sits, in IRE. */
        val p5: Float, val p50: Float, val p95: Float,
    )

    fun stats(px: IntArray): Stats {
        val bins = IntArray(64); var crushed = 0; var clipped = 0
        val ire = IntArray(101)
        for (p in px) {
            val y = luma(p)
            bins[(y * 63.999f).toInt().coerceIn(0, 63)]++
            if (y <= 0.02f) crushed++
            if (y >= 0.98f) clipped++
            ire[(y * 100f + 0.5f).toInt().coerceIn(0, 100)]++
        }
        val max = (bins.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        fun pct(q: Float): Float {
            val target = px.size * q; var acc = 0
            for (i in 0..100) { acc += ire[i]; if (acc >= target) return i.toFloat() }
            return 100f
        }
        val n = px.size.coerceAtLeast(1).toFloat()
        return Stats(FloatArray(64) { bins[it] / max }, crushed * 100f / n, clipped * 100f / n, pct(0.05f), pct(0.5f), pct(0.95f))
    }

    /**
     * Luma waveform: for each of [cols] columns, how many pixels sit at each of [rows] levels
     * (row 0 = 100 IRE at the top). Values are 0..1 intensities for drawing.
     */
    fun waveform(px: IntArray, w: Int, h: Int, cols: Int = 128, rows: Int = 64): Array<FloatArray> {
        val grid = Array(cols) { FloatArray(rows) }
        for (y in 0 until h) for (x in 0 until w) {
            val c = x * cols / w
            val r = ((1f - luma(px[y * w + x])) * (rows - 1) + 0.5f).toInt().coerceIn(0, rows - 1)
            grid[c][r] += 1f
        }
        val perCol = h.toFloat() * w / cols
        for (col in grid) for (i in col.indices) col[i] = (col[i] / perCol * 6f).coerceAtMost(1f)
        return grid
    }

    /** False-color zones (IRE ranges) with their colors, as commonly used on monitors. */
    data class Zone(val from: Float, val to: Float, val argb: Int, val label: String)
    val ZONES = listOf(
        Zone(0f, 2.5f, 0xFF7A1FA2.toInt(), "Crushed"),
        Zone(2.5f, 10f, 0xFF1565C0.toInt(), "Near black"),
        Zone(10f, 38f, 0x00000000, ""),
        Zone(38f, 48f, 0xFF2E7D32.toInt(), "Mid grey"),
        Zone(48f, 58f, 0x00000000, ""),
        Zone(58f, 70f, 0xFFF48FB1.toInt(), "Skin"),
        Zone(70f, 90f, 0x00000000, ""),
        Zone(90f, 98f, 0xFFFFD54F.toInt(), "Bright"),
        Zone(98f, 101f, 0xFFD32F2F.toInt(), "Clipped"),
    )

    /** Pixels outside the coloured zones become a darkened greyscale; zone pixels get the zone color. */
    fun falseColor(px: IntArray): IntArray = IntArray(px.size) { i ->
        val y = luma(px[i]); val ire = y * 100f
        val z = ZONES.firstOrNull { ire >= it.from && ire < it.to }
        if (z != null && z.argb != 0) z.argb else {
            val g = (y * 180f).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
    }
}
