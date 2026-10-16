package com.desqueeze.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The combined look cube: LUT strength + colour controls, as used by preview and export. */
class LookTest {
    private fun px(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun ch(p: Int, shift: Int) = p shr shift and 255

    @Test fun nothingToDoGivesNoCube() {
        assertEquals(null, LookBuilder.build(null, 1f, ColorAdjust()))
    }

    @Test fun neutralAdjustKeepsColoursOnAPlainCube() {
        val cube = LookBuilder.build(null, 1f, ColorAdjust(exposure = 0.001f, temperature = 0.01f))!!
        val out = cube.applyTo(intArrayOf(px(128, 64, 200)), 1f)[0]
        assertTrue(kotlin.math.abs(ch(out, 16) - 129) <= 3)
        assertTrue(kotlin.math.abs(ch(out, 8) - 64) <= 2)
    }

    @Test fun exposureBrightensAndSaturationMinusOneIsGrey() {
        val brighter = LookBuilder.build(null, 1f, ColorAdjust(exposure = 1f))!!.applyTo(intArrayOf(px(100, 100, 100)), 1f)[0]
        assertTrue(ch(brighter, 16) > 125)                    // +1 stop ≈ ×2 light ≈ ×1.37 in gamma
        val grey = LookBuilder.build(null, 1f, ColorAdjust(saturation = -1f))!!.applyTo(intArrayOf(px(200, 50, 50)), 1f)[0]
        assertTrue(kotlin.math.abs(ch(grey, 16) - ch(grey, 8)) <= 2 && kotlin.math.abs(ch(grey, 8) - ch(grey, 0)) <= 2)
    }

    @Test fun warmerRaisesRedAndLowersBlue() {
        val out = LookBuilder.build(null, 1f, ColorAdjust(temperature = 1f))!!.applyTo(intArrayOf(px(128, 128, 128)), 1f)[0]
        assertTrue(ch(out, 16) > 128 && ch(out, 0) < 128)
    }

    @Test fun lutStrengthIsBakedIn() {
        // 2-point LUT that inverts colours; at 50% everything goes to mid grey.
        val n = 2
        val data = FloatArray(n * n * n * 3)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val i = (r + g * n + b * n * n) * 3
            data[i] = 1f - r; data[i + 1] = 1f - g; data[i + 2] = 1f - b
        }
        val inv = LutManager.CubeLut(n, data, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
        val half = LookBuilder.build(inv, 0.5f, ColorAdjust())!!.applyTo(intArrayOf(px(255, 0, 255)), 1f)[0]
        for (sh in listOf(16, 8, 0)) assertTrue(kotlin.math.abs(ch(half, sh) - 128) <= 2)
        assertEquals("0% LUT and no colour = no cube", null, LookBuilder.build(inv, 0f, ColorAdjust()))
    }
}
