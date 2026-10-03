package com.desqueeze.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Delivery aspect ratios offered as frame lines. */
val GUIDE_RATIOS = listOf(1.85f, 2.00f, 2.20f, 2.35f, 2.39f, 2.40f, 2.76f)

/**
 * Viewfinder-style overlays. Purely visual: they never crop the export.
 * Safe areas follow SMPTE ST 2046-1: action safe 93%, title safe 90%.
 */
data class Guides(
    val ratio: Float? = null,
    val mask: Boolean = true,
    val actionSafe: Boolean = false,
    val titleSafe: Boolean = false,
    val thirds: Boolean = false,
    val centerMarker: Boolean = false,
    val crosshair: Boolean = false,
) {
    val any get() = ratio != null || actionSafe || titleSafe || thirds || centerMarker || crosshair

    fun encode() = listOf(ratio?.toString() ?: "", mask, actionSafe, titleSafe, thirds, centerMarker, crosshair).joinToString("|")

    companion object {
        fun decode(s: String?): Guides = try {
            val p = s!!.split("|")
            Guides(p[0].toFloatOrNull(), p[1].toBoolean(), p[2].toBoolean(), p[3].toBoolean(), p[4].toBoolean(), p[5].toBoolean(), p[6].toBoolean())
        } catch (_: Exception) { Guides() }
    }
}

fun fmtRatio(r: Float) = "%.2f".format(r)

/** Draws the guides over the video area (same size and position as the picture). */
@Composable
fun GuideOverlay(g: Guides, modifier: Modifier = Modifier) {
    if (!g.any) return
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp, fontFeatureSettings = "tnum")
    Canvas(modifier) {
        val full = Rect(Offset.Zero, size)
        // Frame-line rectangle for the chosen delivery ratio (letterbox or pillarbox inside the picture).
        val frame = g.ratio?.let { r ->
            val a = size.width / size.height
            if (r >= a) { val h = size.width / r; Rect(0f, (size.height - h) / 2, size.width, (size.height + h) / 2) }
            else { val w = size.height * r; Rect((size.width - w) / 2, 0f, (size.width + w) / 2, size.height) }
        } ?: full
        val line = 1.dp.toPx()

        if (g.ratio != null) {
            if (g.mask) {
                val shade = Color.Black.copy(alpha = 0.55f)
                drawRect(shade, Offset(0f, 0f), Size(size.width, frame.top))
                drawRect(shade, Offset(0f, frame.bottom), Size(size.width, size.height - frame.bottom))
                drawRect(shade, Offset(0f, frame.top), Size(frame.left, frame.height))
                drawRect(shade, Offset(frame.right, frame.top), Size(size.width - frame.right, frame.height))
            }
            drawRect(Color.White.copy(alpha = 0.9f), frame.topLeft, frame.size, style = Stroke(line))
            drawText(measurer, "${fmtRatio(g.ratio)} : 1", Offset(frame.left + 6.dp.toPx(), frame.top + 4.dp.toPx()), label)
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        fun inset(p: Float): Rect {
            val dx = frame.width * (1 - p) / 2; val dy = frame.height * (1 - p) / 2
            return Rect(frame.left + dx, frame.top + dy, frame.right - dx, frame.bottom - dy)
        }
        if (g.actionSafe) safeRect(inset(0.93f), Color.White.copy(alpha = 0.6f), line, dash)
        if (g.titleSafe) safeRect(inset(0.90f), Color(0xFFFFD27A).copy(alpha = 0.8f), line, dash)
        if (g.thirds) {
            val c = Color.White.copy(alpha = 0.35f)
            for (i in 1..2) {
                val x = frame.left + frame.width * i / 3; val y = frame.top + frame.height * i / 3
                drawLine(c, Offset(x, frame.top), Offset(x, frame.bottom), line)
                drawLine(c, Offset(frame.left, y), Offset(frame.right, y), line)
            }
        }
        val center = frame.center
        if (g.crosshair) {
            val c = Color.White.copy(alpha = 0.4f)
            drawLine(c, Offset(center.x, frame.top), Offset(center.x, frame.bottom), line)
            drawLine(c, Offset(frame.left, center.y), Offset(frame.right, center.y), line)
        }
        if (g.centerMarker) {
            val r = 9.dp.toPx(); val w = 1.5.dp.toPx()
            drawLine(Color.White, Offset(center.x - r, center.y), Offset(center.x + r, center.y), w)
            drawLine(Color.White, Offset(center.x, center.y - r), Offset(center.x, center.y + r), w)
        }
    }
}

private fun DrawScope.safeRect(r: Rect, c: Color, w: Float, dash: PathEffect) =
    drawRect(c, r.topLeft, r.size, style = Stroke(w, pathEffect = dash))

/** Controls for the guides, shown under the preview. */
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuidesPanel(g: Guides, enabled: Boolean, onChange: (Guides) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(g.ratio == null, { onChange(g.copy(ratio = null)) }, { Text("No frame lines") }, enabled = enabled) }
            items(GUIDE_RATIOS) { r ->
                FilterChip(g.ratio == r, { onChange(g.copy(ratio = r)) }, { Text(fmtRatio(r), style = LocalTextStyle.current.merge(Mono)) }, enabled = enabled)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (g.ratio != null) FilterChip(g.mask, { onChange(g.copy(mask = !g.mask)) }, { Text("Mask outside") }, enabled = enabled)
            FilterChip(g.actionSafe, { onChange(g.copy(actionSafe = !g.actionSafe)) }, { Text("Action safe 93%") }, enabled = enabled)
            FilterChip(g.titleSafe, { onChange(g.copy(titleSafe = !g.titleSafe)) }, { Text("Title safe 90%") }, enabled = enabled)
            FilterChip(g.thirds, { onChange(g.copy(thirds = !g.thirds)) }, { Text("Thirds") }, enabled = enabled)
            FilterChip(g.centerMarker, { onChange(g.copy(centerMarker = !g.centerMarker)) }, { Text("Center marker") }, enabled = enabled)
            FilterChip(g.crosshair, { onChange(g.copy(crosshair = !g.crosshair)) }, { Text("Crosshair") }, enabled = enabled)
        }
        Text("Guides are for framing only and never crop your export. Safe areas sit inside the frame lines when they're on.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
