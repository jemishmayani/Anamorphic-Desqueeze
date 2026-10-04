package com.desqueeze.app

/**
 * Gamma / log classification.
 *
 * Order of trust (first match wins):
 *  1. HDR from the video track itself: Dolby Vision boxes, PQ (transfer 16) or HLG (transfer 18) -> Confirmed.
 *  2. Log named in a structured camera field (e.g. Sony XML CaptureGammaEquation, a "gamma"/"color profile"
 *     metadata key) -> Confirmed log with that profile.
 *  3. Pixel analysis of several frames spread through the clip (never the intro, never one frame), plus
 *     log mentioned in free text (title/comment) -> at most Likely log. Pixels or free text can never
 *     produce Confirmed.
 *  4. Otherwise SDR (Likely when tagged BT.709/sRGB, since log cameras use that tag too), or Unknown.
 */
enum class GammaKind(val label: String) { LOG("Log"), SDR("SDR / Rec.709"), HLG("HLG"), PQ("PQ / HDR10"), UNKNOWN("Unknown") }
enum class Confidence(val label: String) { CONFIRMED("Confirmed"), LIKELY("Likely"), POSSIBLE("Possible"), UNKNOWN("Unknown") }

/** A log profile name found in the file, and whether it came from a structured camera field or free text. */
data class LogHint(val profile: String, val source: String, val structured: Boolean)

data class Gamma(
    val kind: GammaKind = GammaKind.UNKNOWN,
    val confidence: Confidence = Confidence.UNKNOWN,
    /** e.g. "D-Log M", "S-Log3", "Dolby Vision"; null when the profile isn't known. */
    val profile: String? = null,
    /** Plain-language explanation of every signal used, most important first. */
    val reasons: List<String> = emptyList(),
) {
    val isLog get() = kind == GammaKind.LOG
    /** Only metadata-confirmed log may drive automatic LUT advice. */
    val isConfirmedLog get() = kind == GammaKind.LOG && confidence == Confidence.CONFIRMED
    val isHdr get() = kind == GammaKind.HLG || kind == GammaKind.PQ

    /** Short label for badges and lists. */
    val title: String get() = when {
        kind == GammaKind.LOG && confidence == Confidence.CONFIRMED -> profile ?: "Log"
        kind == GammaKind.LOG && confidence == Confidence.LIKELY -> "Likely log"
        kind == GammaKind.LOG -> "Possible log"
        kind == GammaKind.PQ -> profile ?: "HDR10"
        kind == GammaKind.HLG -> "HLG"
        kind == GammaKind.SDR -> "SDR"
        else -> "Gamma unknown"
    }

    /** One-line diagnosis, e.g. "D-Log M: Confirmed by camera metadata (ColorProfile)". */
    val diagnosis: String get() = "${if (kind == GammaKind.LOG && profile != null && !isConfirmedLog) "Log ($profile?)" else title}: " +
        "${confidence.label}. ${reasons.firstOrNull() ?: ""}".trim()
}

object GammaClassifier {

    /** Camera brands whose 10-bit modes are commonly log; used only to raise Possible to Likely. */
    private val LOG_BRANDS = setOf("DJI", "SONY", "PANASONIC", "CANON", "FUJIFILM", "NIKON", "APPLE", "BLACKMAGIC",
        "Z CAM", "LEICA", "INSTA360", "GOPRO", "SAMSUNG", "OM DIGITAL", "OLYMPUS", "SIGMA", "RED", "ARRI")

    /* ---------------- frame sampling ---------------- */

    /**
     * Times to sample: [n] frames evenly spread through the clip, skipping the first
     * max(3 s, 10%) (intros, title cards, fade-ins) and the last 5% (fade-outs, end cards).
     */
    fun sampleTimes(durationMs: Long, n: Int = 5): List<Long> {
        if (durationMs <= 0) return emptyList()
        if (durationMs < 4000) return listOf(0.4, 0.6, 0.8).map { (durationMs * it).toLong() }
        val start = maxOf(3000L, durationMs / 10).coerceAtMost(durationMs / 2)
        val end = (durationMs * 0.95).toLong()
        return (0 until n).map { i -> start + (end - start) * (2 * i + 1) / (2 * n) }
    }

    enum class FrameVerdict(val why: String) {
        LOG_LIKE("flat like log"), NORMAL("normal contrast"),
        DARK("too dark to judge (black or fade)"), UNIFORM("near-uniform (gray/black card or fade)"),
        GRAPHIC("flat graphics (title card, animation or screen recording)"),
    }

    /** Judges one small ARGB frame. Uninformative frames are excluded instead of guessed at. */
    fun judge(px: IntArray): FrameVerdict {
        if (px.size < 16) return FrameVerdict.UNIFORM
        val n = px.size
        val lum = FloatArray(n); var sum = 0.0; var satSum = 0.0
        val bins = IntArray(64)
        val colors = HashSet<Int>()
        for (i in 0 until n) {
            val c = px[i]
            val r = (c shr 16 and 255) / 255f; val g = (c shr 8 and 255) / 255f; val b = (c and 255) / 255f
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            lum[i] = y; sum += y
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            satSum += if (mx > 0f) (mx - mn) / mx else 0f
            bins[(y * 63.999f).toInt().coerceIn(0, 63)]++
            if (colors.size <= 256) colors += (c shr 19 and 0x1F shl 10) or (c shr 11 and 0x1F shl 5) or (c shr 3 and 0x1F)
        }
        val mean = sum / n
        var varSum = 0.0; for (y in lum) varSum += (y - mean) * (y - mean)
        val std = Math.sqrt(varSum / n)
        if (mean < 0.06) return FrameVerdict.DARK
        if (std < 0.035) return FrameVerdict.UNIFORM
        val top3 = bins.sortedDescending().take(3).sum().toFloat() / n
        if (top3 > 0.75f || colors.size < 48) return FrameVerdict.GRAPHIC
        lum.sort()
        val p1 = lum[(n * 0.01).toInt()]; val p99 = lum[(n * 0.99).toInt().coerceAtMost(n - 1)]
        val sat = (satSum / n).toFloat()
        // Log never reaches true black (S-Log3 ≈ 0.09, V-Log ≈ 0.12, D-Log M ≈ 0.08 full range) and is low in
        // contrast or saturation. Real blacks (night scenes, normal footage) rule it out.
        val logLike = p1 > 0.075f && (p99 - p1 < 0.72f || sat < 0.20f)
        return if (logLike) FrameVerdict.LOG_LIKE else FrameVerdict.NORMAL
    }

    /* ---------------- classification ---------------- */

    fun classify(
        transfer: Int?, primaries: Int?, dolbyVision: Boolean, masteringInfo: Boolean,
        bitDepth: Int?, make: String?, hints: List<LogHint>, frames: List<IntArray>?,
    ): Gamma {
        val why = mutableListOf<String>()
        val structured = hints.firstOrNull { it.structured }
        val freeText = hints.firstOrNull { !it.structured }

        // 1) HDR from the video track: the most reliable signal there is.
        if (dolbyVision) return Gamma(GammaKind.PQ, Confidence.CONFIRMED, "Dolby Vision",
            listOf("Confirmed by a Dolby Vision configuration box in the video track."))
        if (transfer == 16) return Gamma(GammaKind.PQ, Confidence.CONFIRMED, "HDR10",
            listOfNotNull("Confirmed by transfer metadata: SMPTE ST 2084 (PQ).",
                if (masteringInfo) "Mastering-display / light-level metadata present." else null))
        if (transfer == 18) return Gamma(GammaKind.HLG, Confidence.CONFIRMED, "HLG",
            listOf("Confirmed by transfer metadata: ARIB STD-B67 (HLG)."))

        // 2) Log named by the camera in a structured field.
        if (structured != null) return Gamma(GammaKind.LOG, Confidence.CONFIRMED, structured.profile,
            listOf("Confirmed by camera metadata: ${structured.source}."))

        // 3) Pixels from several frames (never just the first) + free-text mentions.
        val verdicts = frames?.map { judge(it) } ?: emptyList()
        val usable = verdicts.filter { it == FrameVerdict.LOG_LIKE || it == FrameVerdict.NORMAL }
        val flat = usable.count { it == FrameVerdict.LOG_LIKE }
        val skipped = verdicts.size - usable.size
        val enough = usable.size >= 3
        val looksLog = enough && flat >= 0.7f * usable.size
        val pixelNote = when {
            frames == null -> null
            verdicts.isEmpty() -> "Picture not analyzed."
            !enough -> "Picture: only ${usable.size} of ${verdicts.size} sampled frames were usable" +
                (if (skipped > 0) " ($skipped skipped as black, uniform or graphics)" else "") + ", too few to judge."
            else -> "Picture: $flat of ${usable.size} usable frames look flat like log" +
                (if (skipped > 0) "; $skipped skipped as black, uniform or graphics" else "") + " (intro and ending excluded)."
        }
        val brandHint = make != null && (bitDepth ?: 8) >= 10 && LOG_BRANDS.any { make.uppercase().contains(it) }

        // Free text alone can suggest log only when the picture couldn't be judged; if enough frames clearly
        // look standard (e.g. an already-graded upload titled "shot in S-Log3"), the pixels win.
        if (looksLog || (freeText != null && !enough)) {
            val conf = when {
                looksLog && (freeText != null || brandHint) -> Confidence.LIKELY
                else -> Confidence.POSSIBLE
            }
            why += if (looksLog) "Heuristic pixel analysis; no log metadata found." else "No log metadata found."
            pixelNote?.let { why += it }
            freeText?.let { why += "The file's ${it.source} mentions \"${it.profile}\"; that's text, not camera data." }
            if (looksLog && brandHint) why += "10-bit ${make!!.trim()} footage, where log is common, but the camera didn't record the profile."
            if (transfer != null) why += "Tagged ${transferName(transfer)}; log cameras often use this tag too."
            return Gamma(GammaKind.LOG, conf, freeText?.profile, why)
        }

        // 4) Standard dynamic range, or unknown.
        return when {
            transfer in setOf(1, 6, 14, 15, 13) -> Gamma(GammaKind.SDR, Confidence.LIKELY, null, listOfNotNull(
                "Tagged ${transferName(transfer)} in the video's color metadata, and no log metadata found.",
                freeText?.let { "The file's ${it.source} mentions \"${it.profile}\", but the picture doesn't look like log (likely already graded)." },
                pixelNote?.let { if (enough) "$it Consistent with standard footage." else it },
                "Some log cameras also use this tag, so this isn't a guarantee."))
            enough -> Gamma(GammaKind.SDR, Confidence.POSSIBLE, null, listOfNotNull(
                "No color-transfer metadata; the picture looks like standard (non-log) footage.", pixelNote,
                freeText?.let { "The file's ${it.source} mentions \"${it.profile}\", but the picture doesn't look like log." }))
            else -> Gamma(GammaKind.UNKNOWN, Confidence.UNKNOWN, null, listOfNotNull(
                "No color-transfer metadata, no log metadata, and not enough usable frames to judge.", pixelNote))
        }
    }

    private fun transferName(t: Int?) = when (t) {
        1, 6, 14, 15 -> "BT.709 (SDR)"; 13 -> "sRGB"; 16 -> "PQ"; 18 -> "HLG"; else -> "transfer $t"
    }
}

/** Short gamma label for fact lines: profile for confirmed log/HDR, hedged wording otherwise; null for plain SDR. */
fun lookLabel(g: Gamma): String? = when {
    g.isConfirmedLog || g.isHdr -> g.title
    g.isLog -> g.title           // "Likely log" / "Possible log"
    else -> null
}
