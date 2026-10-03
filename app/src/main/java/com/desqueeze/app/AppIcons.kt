package com.desqueeze.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** A small, consistent line-icon set (24-unit grid, 1.8 stroke, round caps), drawn for this app. */
object AppIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.line(w: Float = 1.8f, p: PathBuilder.() -> Unit) = path(
        fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = w,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = p)

    private fun ImageVector.Builder.solid(p: PathBuilder.() -> Unit) = path(fill = SolidColor(Color.Black), pathBuilder = p)

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy); arcToRelative(r, r, 0f, true, true, 2 * r, 0f); arcToRelative(r, r, 0f, true, true, -2 * r, 0f); close()
    }

    /** Log gamma: a flat, rolled-off curve on axes. */
    val Log = icon("log") {
        line { moveTo(4f, 4f); lineTo(4f, 20f); lineTo(20f, 20f) }
        line(2.2f) { moveTo(4.5f, 16.5f); curveTo(8f, 15.5f, 9.5f, 8.5f, 19.5f, 7.5f) }
    }

    /** HDR: a bright sun. */
    val Hdr = icon("hdr") {
        line { circle(12f, 12f, 4f) }
        line {
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0); val c = Math.cos(a).toFloat(); val s = Math.sin(a).toFloat()
                moveTo(12f + c * 7f, 12f + s * 7f); lineTo(12f + c * 9.5f, 12f + s * 9.5f)
            }
        }
    }

    /** SDR: half-lit circle. */
    val Sdr = icon("sdr") {
        line { circle(12f, 12f, 8f) }
        solid { moveTo(12f, 4f); arcToRelative(8f, 8f, 0f, false, true, 0f, 16f); close() }
    }

    /** Bit depth: stacked layers. */
    val Bits = icon("bits") {
        line { moveTo(12f, 3.5f); lineTo(20.5f, 8f); lineTo(12f, 12.5f); lineTo(3.5f, 8f); close() }
        line { moveTo(3.5f, 12f); lineTo(12f, 16.5f); lineTo(20.5f, 12f) }
        line { moveTo(3.5f, 16f); lineTo(12f, 20.5f); lineTo(20.5f, 16f) }
    }

    /** Codec: a chip. */
    val Codec = icon("codec") {
        line { moveTo(7f, 6f); lineTo(17f, 6f); quadTo(18f, 6f, 18f, 7f); lineTo(18f, 17f); quadTo(18f, 18f, 17f, 18f)
            lineTo(7f, 18f); quadTo(6f, 18f, 6f, 17f); lineTo(6f, 7f); quadTo(6f, 6f, 7f, 6f); close() }
        line { for (x in listOf(9.5f, 14.5f)) { moveTo(x, 3f); lineTo(x, 6f); moveTo(x, 18f); lineTo(x, 21f) }
            for (y in listOf(9.5f, 14.5f)) { moveTo(3f, y); lineTo(6f, y); moveTo(18f, y); lineTo(21f, y) } }
        line { moveTo(10f, 10f); lineTo(14f, 10f); lineTo(14f, 14f); lineTo(10f, 14f); close() }
    }

    /** Frame rate: film strip. */
    val Fps = icon("fps") {
        line { moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close() }
        line { moveTo(8f, 5f); lineTo(8f, 19f); moveTo(16f, 5f); lineTo(16f, 19f) }
        line { moveTo(4f, 9.5f); lineTo(8f, 9.5f); moveTo(4f, 14.5f); lineTo(8f, 14.5f); moveTo(16f, 9.5f); lineTo(20f, 9.5f); moveTo(16f, 14.5f); lineTo(20f, 14.5f) }
    }

    /** Color gamut: triangle in a circle. */
    val Gamut = icon("gamut") {
        line { circle(12f, 12f, 8.5f) }
        line { moveTo(12f, 6f); lineTo(17.5f, 15.5f); lineTo(6.5f, 15.5f); close() }
    }

    /** Chroma subsampling: 2×2 grid, one cell filled. */
    val Chroma = icon("chroma") {
        line { moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close(); moveTo(12f, 4f); lineTo(12f, 20f); moveTo(4f, 12f); lineTo(20f, 12f) }
        solid { moveTo(4f, 4f); lineTo(12f, 4f); lineTo(12f, 12f); lineTo(4f, 12f); close() }
    }

    /** Audio: waveform. */
    val Audio = icon("audio") {
        line(2f) { moveTo(4f, 10f); lineTo(4f, 14f); moveTo(8f, 7f); lineTo(8f, 17f); moveTo(12f, 4f); lineTo(12f, 20f)
            moveTo(16f, 8f); lineTo(16f, 16f); moveTo(20f, 10.5f); lineTo(20f, 13.5f) }
    }

    /** Camera body with lens. */
    val Camera = icon("camera") {
        line { moveTo(4f, 8f); lineTo(8f, 8f); lineTo(9.5f, 5.5f); lineTo(14.5f, 5.5f); lineTo(16f, 8f); lineTo(20f, 8f)
            lineTo(20f, 19f); lineTo(4f, 19f); close() }
        line { circle(12f, 13f, 3.5f) }
    }

    /** Anamorphic / pixel aspect: horizontal oval with outward arrows. */
    val Aspect = icon("aspect") {
        line { moveTo(12f, 8.5f); curveTo(16f, 8.5f, 18f, 10f, 18f, 12f); curveTo(18f, 14f, 16f, 15.5f, 12f, 15.5f)
            curveTo(8f, 15.5f, 6f, 14f, 6f, 12f); curveTo(6f, 10f, 8f, 8.5f, 12f, 8.5f); close() }
        line { moveTo(2.5f, 12f); lineTo(4f, 12f); moveTo(20f, 12f); lineTo(21.5f, 12f) }
    }

    /** Dolby Vision-ish: two facing half-discs. */
    val Vision = icon("vision") {
        line { moveTo(11f, 5f); arcToRelative(7f, 7f, 0f, false, false, 0f, 14f); close() }
        line { moveTo(13f, 5f); arcToRelative(7f, 7f, 0f, false, true, 0f, 14f); close() }
    }

    val Play = icon("play") { solid { moveTo(8f, 5.5f); lineTo(19f, 12f); lineTo(8f, 18.5f); close() } }
    val Pause = icon("pause") { solid { moveTo(7f, 5f); lineTo(10.5f, 5f); lineTo(10.5f, 19f); lineTo(7f, 19f); close()
        moveTo(13.5f, 5f); lineTo(17f, 5f); lineTo(17f, 19f); lineTo(13.5f, 19f); close() } }
    val SoundOn = icon("sound_on") {
        solid { moveTo(4f, 9.5f); lineTo(8f, 9.5f); lineTo(12.5f, 5.5f); lineTo(12.5f, 18.5f); lineTo(8f, 14.5f); lineTo(4f, 14.5f); close() }
        line { moveTo(15.5f, 9f); quadTo(17.5f, 12f, 15.5f, 15f); moveTo(18f, 6.5f); quadTo(22f, 12f, 18f, 17.5f) }
    }
    val SoundOff = icon("sound_off") {
        solid { moveTo(4f, 9.5f); lineTo(8f, 9.5f); lineTo(12.5f, 5.5f); lineTo(12.5f, 18.5f); lineTo(8f, 14.5f); lineTo(4f, 14.5f); close() }
        line { moveTo(16f, 9.5f); lineTo(21f, 14.5f); moveTo(21f, 9.5f); lineTo(16f, 14.5f) }
    }
    val Info = icon("info") { line { circle(12f, 12f, 9f) }; line(2.2f) { moveTo(12f, 11f); lineTo(12f, 16.5f) }; solid { circle(12f, 7.6f, 1.2f) } }

    val Check = icon("check") { line(2.4f) { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) } }
    val Cross = icon("cross") { line(2.4f) { moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f); moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f) } }
    val Warn = icon("warn") {
        line { moveTo(12f, 3.5f); lineTo(21f, 19.5f); lineTo(3f, 19.5f); close() }
        line(2.2f) { moveTo(12f, 9.5f); lineTo(12f, 14f) }
        solid { circle(12f, 16.8f, 1.15f) }
    }
    val Rotate = icon("rotate") {
        line { moveTo(19f, 12f); arcToRelative(7f, 7f, 0f, true, true, -2.05f, -4.95f) }
        line { moveTo(17.5f, 3.5f); lineTo(17.5f, 7.5f); lineTo(13.5f, 7.5f) }
    }
    val Spark = icon("spark") {
        solid { moveTo(12f, 3f); quadTo(13f, 11f, 21f, 12f); quadTo(13f, 13f, 12f, 21f); quadTo(11f, 13f, 3f, 12f); quadTo(11f, 11f, 12f, 3f); close() }
    }

    val Folder = icon("folder") { line { moveTo(3.5f, 7f); quadTo(3.5f, 5.5f, 5f, 5.5f); lineTo(9.5f, 5.5f); lineTo(11.5f, 7.5f); lineTo(19f, 7.5f)
        quadTo(20.5f, 7.5f, 20.5f, 9f); lineTo(20.5f, 17.5f); quadTo(20.5f, 19f, 19f, 19f); lineTo(5f, 19f); quadTo(3.5f, 19f, 3.5f, 17.5f); close() } }
    val Palette = icon("palette") {
        line { moveTo(12f, 3.5f); curveTo(6.8f, 3.5f, 3.5f, 7.3f, 3.5f, 12f); curveTo(3.5f, 16.7f, 7.3f, 20.5f, 12f, 20.5f)
            curveTo(13.4f, 20.5f, 13.8f, 19.4f, 13.1f, 18.4f); curveTo(12.3f, 17.2f, 13.1f, 15.8f, 14.5f, 15.8f); lineTo(16.5f, 15.8f)
            curveTo(18.9f, 15.8f, 20.5f, 14.2f, 20.5f, 11.8f); curveTo(20.5f, 7.2f, 16.7f, 3.5f, 12f, 3.5f); close() }
        solid { circle(8f, 11f, 1.3f); circle(11f, 7.5f, 1.3f); circle(15.5f, 8.5f, 1.3f) }
    }
    val Moon = icon("moon") { line { moveTo(19.5f, 14.5f); arcToRelative(8f, 8f, 0f, true, true, -10f, -10f); arcToRelative(6.2f, 6.2f, 0f, false, false, 10f, 10f); close() } }
    val Pulse = icon("pulse") { line { moveTo(3f, 12f); lineTo(7f, 12f); lineTo(9.5f, 6f); lineTo(14f, 18f); lineTo(16.5f, 12f); lineTo(21f, 12f) } }
    val Update = icon("update") { line { moveTo(12f, 4f); lineTo(12f, 15f); moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f); moveTo(5f, 19.5f); lineTo(19f, 19.5f) } }
    val Coffee = icon("coffee") {
        line { moveTo(5f, 9f); lineTo(16f, 9f); lineTo(16f, 15f); quadTo(16f, 19.5f, 10.5f, 19.5f); quadTo(5f, 19.5f, 5f, 15f); close() }
        line { moveTo(16f, 10.5f); lineTo(17.5f, 10.5f); quadTo(19.8f, 10.5f, 19.8f, 12.8f); quadTo(19.8f, 15f, 16f, 15f) }
        line { moveTo(8.5f, 3.5f); quadTo(7.5f, 5f, 8.5f, 6.5f); moveTo(12f, 3.5f); quadTo(11f, 5f, 12f, 6.5f) }
    }
    val Code = icon("code") { line { moveTo(8.5f, 7f); lineTo(3.5f, 12f); lineTo(8.5f, 17f); moveTo(15.5f, 7f); lineTo(20.5f, 12f); lineTo(15.5f, 17f); moveTo(13.5f, 5f); lineTo(10.5f, 19f) } }
    val Shield = icon("shield") { line { moveTo(12f, 3.5f); lineTo(19.5f, 6.5f); lineTo(19.5f, 11.5f); quadTo(19.5f, 17.5f, 12f, 20.5f); quadTo(4.5f, 17.5f, 4.5f, 11.5f); lineTo(4.5f, 6.5f); close() } }
    val Doc = icon("doc") { line { moveTo(6f, 3.5f); lineTo(14f, 3.5f); lineTo(18.5f, 8f); lineTo(18.5f, 20.5f); lineTo(6f, 20.5f); close(); moveTo(14f, 3.5f); lineTo(14f, 8f); lineTo(18.5f, 8f)
        moveTo(9f, 12.5f); lineTo(15.5f, 12.5f); moveTo(9f, 16f); lineTo(15.5f, 16f) } }
    val Chevron = icon("chevron") { line(2f) { moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f) } }
    val Sliders = icon("sliders") { line { moveTo(4f, 7f); lineTo(20f, 7f); moveTo(4f, 17f); lineTo(20f, 17f) }; solid { circle(9f, 7f, 2.4f); circle(15f, 17f, 2.4f) } }
    val Heart = icon("heart") { solid { moveTo(12f, 20f); curveTo(5f, 15f, 3f, 12f, 3f, 8.8f); curveTo(3f, 6f, 5.2f, 4f, 7.8f, 4f); curveTo(9.6f, 4f, 11f, 5f, 12f, 6.5f)
        curveTo(13f, 5f, 14.4f, 4f, 16.2f, 4f); curveTo(18.8f, 4f, 21f, 6f, 21f, 8.8f); curveTo(21f, 12f, 19f, 15f, 12f, 20f); close() } }
}
