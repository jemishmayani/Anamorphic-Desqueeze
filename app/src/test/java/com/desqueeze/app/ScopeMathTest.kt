package com.desqueeze.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exposure scopes: values checked against known images and the BT.709 formulas. */
class ScopeMathTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun grey(v: Int) = rgb(v, v, v)
    private fun peak(v: FloatArray) = v.indices.maxBy { v[it] }

    @Test fun rampStats() {
        val ramp = IntArray(256 * 10) { grey(it % 256) }
        val s = ScopeMath.stats(ramp)
        assertEquals(5f, s.p5, 1f); assertEquals(50f, s.p50, 1f); assertEquals(95f, s.p95, 1f)
        assertEquals(2.3f, s.crushedPct, 0.3f); assertEquals(2.3f, s.clippedPct, 0.3f)
    }

    @Test fun blackAndWhite() {
        assertEquals(100f, ScopeMath.stats(IntArray(100) { grey(0) }).crushedPct, 0.01f)
        assertEquals(100f, ScopeMath.stats(IntArray(100) { grey(255) }).clippedPct, 0.01f)
    }

    @Test fun falseColorZones() {
        val fc = ScopeMath.falseColor(intArrayOf(grey(0), grey(117), grey(255)))
        assertEquals(ScopeMath.ZONES.first { it.label == "Crushed" }.argb, fc[0])
        assertEquals(ScopeMath.ZONES.first { it.label == "Mid grey" }.argb, fc[1])
        assertEquals(ScopeMath.ZONES.first { it.label == "Clipped" }.argb, fc[2])
    }

    @Test fun rgbHistogramSeparatesChannels() {
        val h = ScopeMath.rgbHistogram(IntArray(400) { rgb(255, 0, 0) })
        assertEquals(63, peak(h[0])); assertEquals(0, peak(h[1])); assertEquals(0, peak(h[2]))
    }

    @Test fun waveformRisesWithBrightness() {
        val ramp = IntArray(256 * 10) { grey(it % 256) }
        val wf = ScopeMath.waveform(ramp, 256, 10, cols = 4, rows = 10)
        val rows = wf.map { peak(it) }
        assertTrue("brighter columns sit higher: $rows", rows.zipWithNext().all { (a, b) -> b < a })
    }

    @Test fun paradeShowsEachChannel() {
        val p = ScopeMath.parade(IntArray(400) { rgb(255, 0, 0) }, 20, 20, cols = 4, rows = 10)
        assertEquals(0, peak(p[0][0]))  // R at 100 IRE (top)
        assertEquals(9, peak(p[1][0]))  // G at 0 IRE (bottom)
        assertEquals(9, peak(p[2][0]))  // B at 0 IRE
    }

    @Test fun vectorscopeGeometry() {
        val n = 96
        assertEquals(48 * n + 48, peak(ScopeMath.vectorscope(IntArray(400) { grey(128) }, n)))  // neutral = centre
        val (cb, cr) = ScopeMath.chroma(1f, 0f, 0f)
        assertEquals(-0.1146f, cb, 0.002f); assertEquals(0.5f, cr, 0.002f)                      // BT.709 red
        val angle = Math.toDegrees(Math.atan2(cr.toDouble(), cb.toDouble()))
        assertEquals(103.0, angle, 1.0)                                                        // upper left
        val p = peak(ScopeMath.vectorscope(IntArray(400) { rgb(255, 0, 0) }, n))
        assertTrue("red plots upper-left", p % n < n / 2 && p / n < n / 2)
    }

    @Test fun waveformCountsMatchPixelCountAndPlacement() {
        val w = 64; val h = 4
        val ramp = IntArray(w * h) { i -> val v = (i % w) * 255 / (w - 1); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val c = ScopeMath.waveformCounts(ramp, w, h, cols = 8, rows = 16)
        assertEquals(w * h, c.sum())
        // leftmost column is dark (bottom rows), rightmost bright (top rows)
        val first = (0 until 16).maxByOrNull { c[0 * 16 + it] }!!; val last = (0 until 16).maxByOrNull { c[7 * 16 + it] }!!
        assertTrue("dark column near bottom", first >= 13); assertTrue("bright column near top", last <= 2)
    }

    @Test fun phosphorKeepsSparseDetailVisibleAndNeverExceedsOne() {
        val counts = IntArray(100).also { it[0] = 10_000; it[1] = 1; it[2] = 50 }
        val p = ScopeMath.phosphor(counts)
        assertEquals(0f, p[3], 0f)                  // empty stays empty
        assertTrue("single sample visible", p[1] >= 0.18f)
        assertTrue("monotonic", p[1] < p[2] && p[2] <= p[0])
        assertTrue(p.all { it in 0f..1f })
    }

    @Test fun fineHistogramHas256BinsAndPeaksAtTheRightLevel() {
        val px = IntArray(1000) { val v = 128; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val h = ScopeMath.rgbHistogramFine(px)
        assertEquals(4, h.size); assertEquals(256, h[3].size)
        assertEquals(128, h[3].indices.maxByOrNull { h[3][it] })
    }
}
