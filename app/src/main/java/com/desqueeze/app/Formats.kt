package com.desqueeze.app

import kotlin.math.roundToInt

/** Social-ready output frames for Re-encode. The de-squeezed picture is fitted (black bars) or cropped (fill). */
enum class OutFormat(val label: String, val short: String, val aspect: Float, val maxShort: Int, val suffix: String, val where: String) {
    ORIGINAL("Original wide", "Wide", 0f, 0, "", "Keeps the full de-squeezed frame."),
    YOUTUBE("16:9", "16:9", 16f / 9f, 2160, "_16x9", "YouTube, TVs, laptops"),
    FEED("4:5", "4:5", 4f / 5f, 1080, "_4x5", "Instagram and Facebook feed"),
    VERTICAL("9:16", "9:16", 9f / 16f, 1080, "_9x16", "Reels, Shorts, TikTok, Stories"),
    SQUARE("1:1", "1:1", 1f, 1080, "_1x1", "Square posts"),
}

/** Pixel size of the frame for [f], or null for the original wide frame. Social sizes follow each platform's maximum. */
fun formatSize(f: OutFormat, g: Geometry): Pair<Int, Int>? {
    if (f == OutFormat.ORIGINAL) return null
    val short = minOf(g.dispW, g.dispH, f.maxShort)
    fun even(x: Float) = (x.roundToInt() / 2) * 2
    return if (f.aspect >= 1f) even(short * f.aspect) to even(short.toFloat()) else even(short.toFloat()) to even(short / f.aspect)
}

/** The size Re-encode aims for before encoder limits: the format frame, or the full de-squeezed frame. */
fun reencodeTarget(g: Geometry, f: OutFormat): Pair<Int, Int> = formatSize(f, g) ?: (g.outW to g.outH)

/** How much of the picture survives in "fill" mode (cropping), as a percentage of the width or height kept. */
fun fillKeepsPercent(g: Geometry, f: OutFormat): Int {
    if (f == OutFormat.ORIGINAL) return 100
    val pic = g.outRatio; val frame = f.aspect
    return ((if (pic > frame) frame / pic else pic / frame) * 100).roundToInt()
}
