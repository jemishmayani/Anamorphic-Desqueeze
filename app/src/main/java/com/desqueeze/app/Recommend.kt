package com.desqueeze.app

data class Recommendation(
    val facts: List<String>,
    val mode: ExportMode, val title: String, val why: String,
    val alt: ExportMode?, val altTitle: String?, val altWhy: String?,
)

/** Plain-language advice for the selected clip, so nobody has to know what a pixel aspect tag is. */
fun recommend(v: VideoInfo, g: Geometry, squeeze: Float, lutSelected: Boolean, fit: Pair<Int, Int>?,
              format: OutFormat = OutFormat.ORIGINAL, fill: Boolean = false): Recommendation {
    val f = v.footage
    val gm = f.gamma
    // Only metadata-confirmed log or HDR is named as a fact or changes the advice.
    val look = if (gm.isConfirmedLog || gm.isHdr) gm.title else null
    val facts = listOfNotNull(
        f.camera,
        "${v.displayW} × ${v.displayH}",
        lookLabel(gm),
        "${v.bitDepth}-bit ${v.codec}",
        fmtSqueeze(squeeze) + if (g.vertical) " vertical" else "",
        if (format != OutFormat.ORIGINAL) "Output ${format.short} (${if (fill) "fill" else "fit"})" else null,
    )
    val decodable = DeviceCaps.canDecode(v.codec, v.bitDepth)
    val target = reencodeTarget(g, format)
    val scaled = fit != null && (fit.first < target.first || fit.second < target.second)
    val scaledText = if (scaled) " This phone's encoder will scale it to ${fit!!.first}×${fit.second}." else ""
    val precious = look != null || v.bitDepth >= 10
    val desc = listOfNotNull("${v.bitDepth}-bit", look).joinToString(" ")
    return when {
        decodable && format != OutFormat.ORIGINAL -> Recommendation(facts, ExportMode.REENCODE, "Re-encode for ${format.short}",
            "Only Re-encode can reframe into ${format.short} for ${format.where}: the wide picture is " +
                (if (fill) "cropped to fill the frame." else "fitted with black bars, nothing cut off.") + scaledText,
            ExportMode.LOSSLESS, "Lossless, original wide frame", "Keeps every original pixel, but no ${format.short} reframing.")
        !decodable -> Recommendation(facts, ExportMode.LOSSLESS, "Lossless Desqueeze",
            "This phone can't decode ${v.bitDepth}-bit ${v.codec}, so Re-encode isn't possible. Lossless doesn't need to decode, and keeps every pixel.",
            null, null, null)
        lutSelected -> Recommendation(facts, ExportMode.REENCODE, "Re-encode with your LUT",
            "Only Re-encode can bake your LUT into the picture.$scaledText",
            ExportMode.LOSSLESS, "Lossless for grading later", "Keeps the original $desc pixels; apply the LUT in your editor instead.")
        precious -> Recommendation(facts, ExportMode.LOSSLESS, "Lossless Desqueeze",
            "Preserves your original $desc footage exactly, at full ${g.outW}×${g.outH}, in seconds.",
            ExportMode.REENCODE, "Re-encode for sharing",
            "Makes a file every app and website shows wide." +
                (if (gm.isConfirmedLog) " The picture stays ${gm.title}-flat unless you add your camera's official LUT." else "") + scaledText)
        else -> Recommendation(facts, ExportMode.REENCODE, "Re-encode for sharing",
            "Standard 8-bit footage loses very little, and every app and website will show it wide.$scaledText",
            ExportMode.LOSSLESS, "Lossless for editing", "Instant and untouched. Editors like Resolve and players like VLC show it wide.")
    }
}

/** Recommendation for a clip using the current shared settings (encoder fit is cached, so this is cheap). */
fun recommendFor(st: AppState, exporter: Exporter, v: VideoInfo): Recommendation {
    val g = geometry(v, st.effectiveSqueeze(v), st.orientation, st.direction)
    val mime = if (st.codec == Codec.HEVC && Exporter.hasEncoder("video/hevc")) "video/hevc" else "video/avc"
    val fit = exporter.targetSize(g, v.fps, mime, st.formatOf(v)).let { it.first to it.second }
    return recommend(v, g, st.effectiveSqueeze(v), st.lutId != null, fit, st.formatOf(v), st.fillOf(v))
}

/** The method a clip will actually be exported with. */
fun modeFor(st: AppState, exporter: Exporter, v: VideoInfo): ExportMode =
    st.clipModes[v.uri.toString()] ?: if (st.followRecommendation) recommendFor(st, exporter, v).mode else st.mode
