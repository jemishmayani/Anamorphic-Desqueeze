package com.desqueeze.app

/** Exposure tools shown over the preview. */
enum class Scope(val label: String, val short: String) {
    OFF("Off", "Off"), HISTOGRAM("Histogram", "Histogram"), WAVEFORM("Waveform", "Waveform"),
    PARADE("RGB parade", "Parade"), VECTORSCOPE("Vectorscope", "Vector"), FALSE_COLOR("False color", "False color"),
}

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
    fun waveform(px: IntArray, w: Int, h: Int, cols: Int = 128, rows: Int = 64, channel: Int = -1): Array<FloatArray> {
        val grid = Array(cols) { FloatArray(rows) }
        for (y in 0 until h) for (x in 0 until w) {
            val c = x * cols / w
            val p = px[y * w + x]
            val v = when (channel) { 0 -> (p shr 16 and 255) / 255f; 1 -> (p shr 8 and 255) / 255f; 2 -> (p and 255) / 255f; else -> luma(p) }
            val r = ((1f - v) * (rows - 1) + 0.5f).toInt().coerceIn(0, rows - 1)
            grid[c][r] += 1f
        }
        val perCol = h.toFloat() * w / cols
        for (col in grid) for (i in col.indices) col[i] = (col[i] / perCol * 6f).coerceAtMost(1f)
        return grid
    }

    /** RGB parade: one waveform per channel (R, G, B), each [cols] wide. */
    fun parade(px: IntArray, w: Int, h: Int, cols: Int = 64, rows: Int = 64): Array<Array<FloatArray>> =
        Array(3) { ch -> waveform(px, w, h, cols, rows, ch) }

    /** R, G, B and luma histograms (64 bins each, in that order), scaled by the tallest bin of all four. */
    fun rgbHistogram(px: IntArray): Array<FloatArray> {
        val b = Array(4) { IntArray(64) }
        for (p in px) {
            b[0][(p shr 16 and 255) shr 2]++; b[1][(p shr 8 and 255) shr 2]++; b[2][(p and 255) shr 2]++
            b[3][(luma(p) * 63.999f).toInt().coerceIn(0, 63)]++
        }
        // Ignore the end bins when scaling, so a clipped sky or black border doesn't flatten everything else.
        val max = b.maxOf { ch -> (1 until 63).maxOf { ch[it] } }.coerceAtLeast(1).toFloat()
        return Array(4) { ch -> FloatArray(64) { (b[ch][it] / max).coerceAtMost(1f) } }
    }

    /** BT.709 colour difference of an RGB colour (each 0..1): Cb, Cr in -0.5..0.5. */
    fun chroma(r: Float, g: Float, b: Float): Pair<Float, Float> {
        val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return (b - y) / 1.8556f to (r - y) / 1.5748f
    }

    /**
     * Vectorscope: density of Cb (x, right = blue) and Cr (y, up = red) on a [size]×[size] grid,
     * centre = neutral grey. Values 0..1, log-scaled so faint colours stay visible. Row 0 is the top.
     */
    fun vectorscope(px: IntArray, size: Int = 96): FloatArray {
        val g = IntArray(size * size)
        for (p in px) {
            val (cb, cr) = chroma((p shr 16 and 255) / 255f, (p shr 8 and 255) / 255f, (p and 255) / 255f)
            val x = ((cb + 0.5f) * (size - 1) + 0.5f).toInt().coerceIn(0, size - 1)
            val y = ((0.5f - cr) * (size - 1) + 0.5f).toInt().coerceIn(0, size - 1)
            g[y * size + x]++
        }
        val max = (g.maxOrNull() ?: 0).coerceAtLeast(1)
        val ln = Math.log(1.0 + max)
        return FloatArray(g.size) { if (g[it] == 0) 0f else (Math.log(1.0 + g[it]) / ln).toFloat() }
    }

    /** 75% colour-bar targets for the vectorscope graticule: label to (Cb, Cr). */
    val TARGETS: List<Pair<String, Pair<Float, Float>>> = listOf(
        "R" to chroma(0.75f, 0f, 0f), "Mg" to chroma(0.75f, 0f, 0.75f), "B" to chroma(0f, 0f, 0.75f),
        "Cy" to chroma(0f, 0.75f, 0.75f), "G" to chroma(0f, 0.75f, 0f), "Yl" to chroma(0.75f, 0.75f, 0f),
    )

    /** Skin-tone line angle (degrees, counter-clockwise from +Cb), the classic ~123° "I-line". */
    const val SKIN_LINE_DEG = 123f

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
