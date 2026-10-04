package com.desqueeze.app

import kotlin.math.roundToInt

/** How the clip should be shown. Auto = the file's own rotation metadata. */
enum class Orientation(val label: String) { AUTO("Auto"), HORIZONTAL("Landscape"), VERTICAL("Portrait") }

/** Which on-screen axis gets stretched. Auto = the lens/sensor's horizontal axis (follows rotation). */
enum class Direction(val label: String) { AUTO("Auto"), HORIZONTAL("Horizontal"), VERTICAL("Vertical") }

/**
 * Everything the preview, Lossless and Re-encode need to agree on.
 * "Stored" = pixels as encoded in the file; "display" = after rotation.
 */
data class Geometry(
    /** Effective clockwise display rotation (0/90/180/270). */
    val rotation: Int,
    /** Rotation to add on top of the file's metadata (0 when Orientation = Auto). */
    val extraRotation: Int,
    val dispW: Int, val dispH: Int,
    /** True when the de-squeeze stretches the displayed image vertically. */
    val vertical: Boolean,
    val outW: Int, val outH: Int,
    /** True when, in stored pixels, the stretch is along the stored width (pasp h > v). */
    val storedHorizontal: Boolean,
) {
    val rotated get() = rotation % 180 != 0
    val outRatio get() = outW.toFloat() / outH
    val inRatio get() = dispW.toFloat() / dispH
    fun ratioLabel(r: Float = outRatio) = if (r >= 1f) "%.2f : 1".format(r) else "1 : %.2f".format(1f / r)
}

fun geometry(v: VideoInfo, squeeze: Float, o: Orientation, d: Direction): Geometry {
    val meta = ((v.rotation % 360) + 360) % 360
    val storedLandscape = v.width >= v.height
    val rot = when (o) {
        Orientation.AUTO -> meta
        // Make the display landscape / portrait, keeping the file's own flip when it already matches.
        Orientation.HORIZONTAL -> if (storedLandscape) (if (meta == 180) 180 else 0) else (if (meta == 270) 270 else 90)
        Orientation.VERTICAL -> if (storedLandscape) (if (meta == 270) 270 else 90) else (if (meta == 180) 180 else 0)
    }
    val rotated = rot % 180 != 0
    val dw = if (rotated) v.height else v.width
    val dh = if (rotated) v.width else v.height
    // Auto: the anamorphic lens squeezes along the sensor's long/horizontal axis, which is the
    // display's vertical axis when the clip is shown rotated by 90°.
    val vertical = when (d) { Direction.AUTO -> rotated; Direction.HORIZONTAL -> false; Direction.VERTICAL -> true }
    val ow = if (vertical) dw else even(dw * squeeze)
    val oh = if (vertical) even(dh * squeeze) else dh
    return Geometry(rot, ((rot - meta) + 360) % 360, dw, dh, vertical, ow, oh, storedHorizontal = (vertical == rotated))
}

private fun even(f: Float) = (f.roundToInt() / 2) * 2

fun directionHint(v: VideoInfo, g: Geometry, o: Orientation, d: Direction): String {
    val meta = ((v.rotation % 360) + 360) % 360
    val orient = when (o) {
        Orientation.AUTO -> if (meta == 0) "Auto: the file isn't rotated, so it's shown as recorded." else "Auto: the file says it's rotated $meta°, so it's shown that way."
        else -> "Shown ${o.label.lowercase()}" + if (g.extraRotation != 0) " (rotated ${g.extraRotation}° from the file's own orientation)." else "."
    }
    val dir = when (d) {
        Direction.AUTO -> "Stretching ${if (g.vertical) "vertically" else "horizontally"}, along the lens's squeeze axis."
        else -> "Stretching ${d.label.lowercase()}ly, as you chose."
    }
    return "$orient $dir"
}

/** True when the clip is shown turned 90° AND its pixels are stretched: players disagree on which to apply first. */
fun rotatedStretch(g: Geometry): Boolean =
    g.rotated && (if (g.vertical) g.outH.toFloat() / g.dispH.coerceAtLeast(1) else g.outW.toFloat() / g.dispW.coerceAtLeast(1)) > 1.001f

/** The shape some players (e.g. phone galleries) show for a Lossless rotated + stretched clip: the stretch on the other axis. */
fun misreadRatio(g: Geometry): Float {
    val f = if (g.vertical) g.outH.toFloat() / g.dispH.coerceAtLeast(1) else g.outW.toFloat() / g.dispW.coerceAtLeast(1)
    return if (g.vertical) g.dispW * f / g.dispH else g.dispW / (g.dispH * f)
}
