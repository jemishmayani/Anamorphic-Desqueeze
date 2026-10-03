package com.desqueeze.app

/** One clip's pre-export check: what goes in, what comes out, and anything worth knowing first. */
data class CompatReport(
    val rows: List<Pair<String, String>>,
    val issues: List<Pair<Status, String>>,
) {
    val worst: Status get() = when {
        issues.any { it.first == Status.NO } -> Status.NO
        issues.any { it.first == Status.WARN } -> Status.WARN
        else -> Status.OK
    }
    val result: String get() = issues.firstOrNull { it.first == worst }?.second ?: "Ready. No problems found."
}

/** Builds the check from the same data export uses (geometry, encoder fit, decoder support). */
fun compatFor(st: AppState, exporter: Exporter, v: VideoInfo, mode: ExportMode): CompatReport {
    val g = geometry(v, st.effectiveSqueeze(v), st.orientation, st.direction)
    val f = v.footage
    val look = when { f.log != null && !f.logEstimated -> f.log; f.log != null -> "log"; else -> f.hdr }
    val input = listOfNotNull(resolutionName(v.displayW, v.displayH), "${v.bitDepth}-bit", look, v.codec).joinToString(" ")
    val tag = existingTag(v); val policy = st.tagPolicy[st.keyOf(v)]
    val picked = st.squeezeFor(v)
    val desq = buildString {
        append(fmtSqueeze(st.effectiveSqueeze(v))); append(if (g.vertical) " vertical" else " horizontal")
        if (tag != null) append(when (policy) {
            TagPolicy.KEEP -> " (file's own tag kept)"
            TagPolicy.FORCE -> " (${fmtSqueeze(tag)} tag × ${fmtSqueeze(picked)})"
            TagPolicy.REPLACE -> " (replaces ${fmtSqueeze(tag)} tag)"
            null -> " (file already tagged ${fmtSqueeze(tag)})"
        })
    }
    val rows = mutableListOf("Input" to input, "Desqueeze" to desq)
    val issues = mutableListOf<Pair<Status, String>>()

    if (tag != null && policy == null)
        issues += Status.WARN to "This video already contains a ${fmtSqueeze(tag)} desqueeze tag. Choose Keep, Replace or Force in the Frame step."
    if (policy == TagPolicy.FORCE)
        issues += Status.WARN to "Double desqueeze: ${fmtSqueeze(tag!!)} × ${fmtSqueeze(picked)} = ${fmtSqueeze(tag * picked)}. Only right if the footage really was squeezed twice."

    if (mode == ExportMode.LOSSLESS) {
        rows += "Output" to "${g.outW} × ${g.outH} on screen (pixels unchanged)"
        rows += "Encoder" to "Not used (Lossless)"
        if (st.lutId != null) issues += Status.WARN to "Your LUT isn't applied in Lossless. Switch this clip to Re-encode to bake it in."
    } else {
        val mime = if (st.codec == Codec.HEVC && Exporter.hasEncoder("video/hevc")) "video/hevc" else "video/avc"
        val (w, h, _) = exporter.targetSize(g, v.fps, mime)
        val maxW = DeviceCaps.maxWidthAt(mime, g.outH)
        rows += "Output" to "${g.outW} × ${g.outH}"
        rows += "Your encoder" to (maxW?.let { "Max width $it at ${g.outH} tall (${if (mime == "video/hevc") "HEVC" else "H.264"})" } ?: "Can't encode ${g.outH} px tall")
        if (!DeviceCaps.canDecode(v.codec, v.bitDepth))
            issues += Status.NO to "This phone can't decode ${v.bitDepth}-bit ${v.codec}, so Re-encode will fail. Use Lossless."
        if (w < g.outW || h < g.outH)
            issues += (if (w.toFloat() / g.outW >= 0.75f) Status.WARN else Status.NO) to
                "Output will be scaled to $w × $h (${w * 100 / g.outW}% size). Lossless keeps full resolution."
        if (v.bitDepth >= 10 && v.footage.hdr == null)
            issues += Status.WARN to "10-bit becomes 8-bit: Android re-encodes standard/log video in 8-bit. Lossless keeps 10-bit."
        if (v.footage.hdr != null && !st.keepHdrSetting)
            issues += Status.WARN to "HDR will be converted to SDR (Keep HDR is off in Settings)."
    }
    rows += "Result" to (when (issues.maxOfOrNull { it.first.ordinal } ?: 0) { 0 -> "✓ Ready"; 1 -> "⚠ Check before export"; else -> "✗ Won't work as set" })
    return CompatReport(rows, issues)
}
