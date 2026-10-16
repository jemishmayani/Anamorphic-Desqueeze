package com.desqueeze.app

import kotlin.math.abs
import kotlin.math.pow

/**
 * Basic colour controls, applied after the LUT (on the picture as you see it).
 * Exposure and white balance work on approximately linear light; contrast pivots on mid grey; saturation keeps luma.
 */
data class ColorAdjust(
    /** Stops, -2..+2. */
    val exposure: Float = 0f,
    /** -1..+1 (−100..+100 in the UI). */
    val contrast: Float = 0f,
    /** -1..+1: −1 is black and white, +1 doubles saturation. */
    val saturation: Float = 0f,
    /** -1..+1: cooler (blue) to warmer (amber). */
    val temperature: Float = 0f,
    /** -1..+1: green to magenta. */
    val tint: Float = 0f,
) {
    val isNeutral get() = listOf(exposure, contrast, saturation, temperature, tint).all { abs(it) < 0.005f }

    /** Applies the adjustments to one colour (0..1, in place). */
    fun apply(c: FloatArray) {
        if (isNeutral) return
        // To ~linear light for exposure and white balance.
        val gain = 2f.pow(exposure)
        val wr = 1f + 0.20f * temperature; val wb = 1f - 0.20f * temperature; val wg = 1f - 0.15f * tint
        val w = floatArrayOf(wr * gain, wg * gain, wb * gain)
        for (i in 0..2) {
            val lin = c[i].coerceIn(0f, 1f).pow(2.2f) * w[i]
            c[i] = lin.coerceIn(0f, 1f).pow(1f / 2.2f)
        }
        if (abs(contrast) >= 0.005f) {
            val k = 1f + contrast * 0.6f
            for (i in 0..2) c[i] = ((c[i] - 0.5f) * k + 0.5f).coerceIn(0f, 1f)
        }
        if (abs(saturation) >= 0.005f) {
            val y = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]
            val s = 1f + saturation
            for (i in 0..2) c[i] = (y + (c[i] - y) * s).coerceIn(0f, 1f)
        }
    }

    /** Short summary for the tool bar, e.g. "+0.5 EV, warmer". */
    fun summary(): String? = listOfNotNull(
        if (abs(exposure) >= 0.05f) "%+.1f EV".format(exposure) else null,
        if (abs(contrast) >= 0.005f) "contrast ${pct(contrast)}" else null,
        if (abs(saturation) >= 0.005f) "saturation ${pct(saturation)}" else null,
        if (abs(temperature) >= 0.005f) (if (temperature > 0) "warmer" else "cooler") else null,
        if (abs(tint) >= 0.005f) (if (tint > 0) "magenta" else "green") else null,
    ).takeIf { it.isNotEmpty() }?.joinToString(", ")

    private fun pct(v: Float) = "%+d".format((v * 100).toInt())
}

/** Builds the one 3D cube that preview, Before / After and every export use: LUT at its strength, then colour. */
object LookBuilder {
    private const val PLAIN_SIZE = 33

    /** Null when the clip has neither a LUT (above 0%) nor colour adjustments. */
    fun build(lut: LutManager.CubeLut?, strength: Float, adjust: ColorAdjust): LutManager.CubeLut? {
        val s = strength.coerceIn(0f, 1f)
        val useLut = lut != null && s > 0f
        if (!useLut && adjust.isNeutral) return null
        val l = lut?.takeIf { useLut }
        val n = l?.size ?: PLAIN_SIZE
        val out = FloatArray(n * n * n * 3)
        val c = FloatArray(3)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val i = (r + g * n + b * n * n) * 3   // .cube order: red fastest
            val p = floatArrayOf(r / (n - 1f), g / (n - 1f), b / (n - 1f))
            for (k in 0..2) {
                c[k] = if (l != null) {
                    val v = (l.data[i + k] - l.domainMin[k]) / (l.domainMax[k] - l.domainMin[k])
                    p[k] + (v - p[k]) * s
                } else p[k]
            }
            adjust.apply(c)
            out[i] = c[0].coerceIn(0f, 1f); out[i + 1] = c[1].coerceIn(0f, 1f); out[i + 2] = c[2].coerceIn(0f, 1f)
        }
        return LutManager.CubeLut(n, out, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
    }
}
