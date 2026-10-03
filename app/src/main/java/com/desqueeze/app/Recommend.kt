package com.desqueeze.app

data class Recommendation(
    val facts: List<String>,
    val mode: ExportMode, val title: String, val why: String,
    val alt: ExportMode?, val altTitle: String?, val altWhy: String?,
)

/** Plain-language advice for the selected clip, so nobody has to know what a pixel aspect tag is. */
fun recommend(v: VideoInfo, g: Geometry, squeeze: Float, lutSelected: Boolean, fit: Pair<Int, Int>?): Recommendation {
    val f = v.footage
    val look = when { f.log != null && !f.logEstimated -> f.log; f.log != null -> "log"; f.hdr != null -> f.hdr; else -> null }
    val facts = listOfNotNull(
        f.camera,
        "${v.displayW} × ${v.displayH}",
        look?.let { if (f.logEstimated) "Log (estimated)" else it },
        "${v.bitDepth}-bit ${v.codec}",
        fmtSqueeze(squeeze) + if (g.vertical) " vertical" else "",
    )
    val decodable = DeviceCaps.canDecode(v.codec, v.bitDepth)
    val scaled = fit != null && (fit.first < g.outW || fit.second < g.outH)
    val scaledText = if (scaled) " This phone's encoder will scale it to ${fit!!.first}×${fit.second}." else ""
    val precious = look != null || v.bitDepth >= 10
    val desc = listOfNotNull("${v.bitDepth}-bit", look).joinToString(" ")
    return when {
        !decodable -> Recommendation(facts, ExportMode.LOSSLESS, "Lossless Desqueeze",
            "This phone can't decode ${v.bitDepth}-bit ${v.codec}, so Re-encode isn't possible. Lossless doesn't need to decode, and keeps every pixel.",
            null, null, null)
        lutSelected -> Recommendation(facts, ExportMode.REENCODE, "Re-encode with your LUT",
            "Only Re-encode can bake your LUT into the picture.$scaledText",
            ExportMode.LOSSLESS, "Lossless for grading later", "Keeps the original $desc pixels; apply the LUT in your editor instead.")
        precious -> Recommendation(facts, ExportMode.LOSSLESS, "Lossless Desqueeze",
            "Preserves your original $desc footage exactly, at full ${g.outW}×${g.outH}, in seconds.",
            ExportMode.REENCODE, "Re-encode for sharing",
            "Makes a file every app and website shows wide." + (if (look != null) " The picture stays $look-flat unless you add a LUT." else "") + scaledText)
        else -> Recommendation(facts, ExportMode.REENCODE, "Re-encode for sharing",
            "Standard 8-bit footage loses very little, and every app and website will show it wide.$scaledText",
            ExportMode.LOSSLESS, "Lossless for editing", "Instant and untouched. Editors like Resolve and players like VLC show it wide.")
    }
}

/** Recommendation for a clip using the current shared settings (encoder fit is cached, so this is cheap). */
fun recommendFor(st: AppState, exporter: Exporter, v: VideoInfo): Recommendation {
    val g = geometry(v, st.effectiveSqueeze(v), st.orientation, st.direction)
    val mime = if (st.codec == Codec.HEVC && Exporter.hasEncoder("video/hevc")) "video/hevc" else "video/avc"
    val fit = exporter.targetSize(g, v.fps, mime).let { it.first to it.second }
    return recommend(v, g, st.effectiveSqueeze(v), st.lutId != null, fit)
}

/** The method a clip will actually be exported with. */
fun modeFor(st: AppState, exporter: Exporter, v: VideoInfo): ExportMode =
    st.clipModes[v.uri.toString()] ?: if (st.followRecommendation) recommendFor(st, exporter, v).mode else st.mode
