package com.desqueeze.app

import kotlin.math.roundToInt

/**
 * Output frames for Re-encode. The de-squeezed picture is fitted (black bars) or cropped (fill).
 * [defaultShort] is the "Auto" resolution: the platform's own maximum for social formats, 4K for cinema/YouTube.
 */
enum class OutFormat(val label: String, val short: String, val aspect: Float, val defaultShort: Int, val suffix: String, val where: String) {
    ORIGINAL("Original wide", "Wide", 0f, 0, "", "Keeps the full de-squeezed frame."),
    SCOPE("2.39:1 Scope", "2.39", 2.39f, 2160, "_239", "cinema scope delivery and widescreen edits"),
    UNIVISIUM("2.00:1", "2.00", 2.0f, 2160, "_200", "2:1 streaming-style widescreen"),
    FLAT("1.85:1 Flat", "1.85", 1.85f, 2160, "_185", "cinema flat delivery"),
    YOUTUBE("16:9", "16:9", 16f / 9f, 2160, "_16x9", "YouTube, TVs, laptops"),
    FEED("4:5", "4:5", 4f / 5f, 1080, "_4x5", "Instagram and Facebook feed"),
    VERTICAL("9:16", "9:16", 9f / 16f, 1080, "_9x16", "Reels, Shorts, TikTok, Stories"),
    SQUARE("1:1", "1:1", 1f, 1080, "_1x1", "Square posts");

    val cinema get() = this == SCOPE || this == UNIVISIUM || this == FLAT
}

/** Output resolution tier for a format; Auto uses the format's default (see [OutFormat.defaultShort]). */
enum class OutRes(val label: String, val px: Int) { AUTO("Auto", 0), P1080("1080p", 1080), P1440("1440p", 1440), P2160("4K", 2160) }

/**
 * Pixel size of the frame for [f] at [res], or null for the original wide frame.
 * The tier is the short side of a 16:9-or-narrower frame (1080 / 1440 / 2160); wider cinema frames use the
 * matching 16:9 width (1920 / 2560 / 3840), so 4K 2.39 is the standard UHD scope 3840 × 1606.
 * Never above the source's short side, so nothing is upscaled.
 */
fun formatSize(f: OutFormat, g: Geometry, res: OutRes = OutRes.AUTO): Pair<Int, Int>? {
    if (f == OutFormat.ORIGINAL) return null
    val tier = minOf(g.dispW, g.dispH, if (res == OutRes.AUTO) f.defaultShort else res.px)
    fun even(x: Float) = (x.roundToInt() / 2) * 2
    return when {
        f.aspect > 16f / 9f -> { val w = even(tier * 16f / 9f); w to even(w / f.aspect) }
        f.aspect >= 1f -> even(tier * f.aspect) to even(tier.toFloat())
        else -> even(tier.toFloat()) to even(tier / f.aspect)
    }
}

/** The resolution "Auto" resolves to for this clip, for labels like "Auto (4K)". */
fun autoResLabel(f: OutFormat, g: Geometry): String {
    val s = formatSize(f, g, OutRes.AUTO) ?: return ""
    val short = minOf(s.first, s.second); val long = maxOf(s.first, s.second)
    return when { short >= 2000 || long >= 3800 -> "4K"; short >= 1400 -> "1440p"; else -> "${short}p" }
}

/** The size Re-encode aims for before encoder limits: the format frame, or the full de-squeezed frame. */
fun reencodeTarget(g: Geometry, f: OutFormat, res: OutRes = OutRes.AUTO): Pair<Int, Int> = formatSize(f, g, res) ?: (g.outW to g.outH)

/** How much of the picture survives in "fill" mode (cropping), as a percentage of the width or height kept. */
fun fillKeepsPercent(g: Geometry, f: OutFormat): Int {
    if (f == OutFormat.ORIGINAL) return 100
    val pic = g.outRatio; val frame = f.aspect
    return ((if (pic > frame) frame / pic else pic / frame) * 100).roundToInt()
}
