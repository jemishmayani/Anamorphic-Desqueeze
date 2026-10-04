package com.desqueeze.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Warm amber: used only for picture-brightness traits (log, HDR). Blue (primary) marks precision traits. */
val Warm = Color(0xFFFFB454)

enum class Tone { Accent, Warm, Neutral }
data class Badge(val icon: ImageVector, val label: String, val tone: Tone, val dashed: Boolean = false)

fun badgesFor(v: VideoInfo): List<Badge> {
    val f = v.footage
    return buildList {
        val gm = f.gamma
        when (gm.kind) {
            // Confirmed log is solid; Likely/Possible log is dashed, so a guess never looks like a fact.
            GammaKind.LOG -> add(Badge(AppIcons.Log, gm.title, Tone.Warm, dashed = !gm.isConfirmedLog))
            GammaKind.HLG, GammaKind.PQ -> add(Badge(if (f.dolbyVision) AppIcons.Vision else AppIcons.Hdr, gm.title, Tone.Warm))
            GammaKind.SDR -> add(Badge(AppIcons.Sdr, if (gm.confidence == Confidence.POSSIBLE) "SDR?" else "SDR", Tone.Neutral))
            GammaKind.UNKNOWN -> add(Badge(AppIcons.Sdr, "Gamma unknown", Tone.Neutral, dashed = true))
        }
        add(Badge(AppIcons.Bits, "${v.bitDepth}-bit", if (v.bitDepth >= 10) Tone.Accent else Tone.Neutral))
        f.chroma?.let { add(Badge(AppIcons.Chroma, it, if (it != "4:2:0") Tone.Accent else Tone.Neutral)) }
        add(Badge(AppIcons.Codec, v.codec, Tone.Neutral))
        if (v.fps > 0) add(Badge(AppIcons.Fps, fmtFps(v.fps), Tone.Neutral))
        f.primariesName?.let { add(Badge(AppIcons.Gamut, it, if (it == "BT.709") Tone.Neutral else Tone.Accent)) }
        f.pixelAspect?.let { (h, vv) -> if (h != vv) add(Badge(AppIcons.Aspect, "Tagged ${fmtSqueeze(h.toFloat() / vv)}", Tone.Accent)) }
        add(Badge(AppIcons.Audio, v.audio ?: "No audio", Tone.Neutral))
    }
}

fun fmtFps(f: Float) = "%.3f".format(f).trimEnd('0').trimEnd('.') + " fps"
fun fmtDuration(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
fun fmtSize(b: Long) = if (b >= 1L shl 30) "%.2f GB".format(b / 1073741824.0) else "%.0f MB".format(b / 1048576.0)

@Composable
fun BadgeChip(b: Badge) {
    val c = MaterialTheme.colorScheme
    val (bg, fg) = when (b.tone) {
        Tone.Warm -> Warm.copy(alpha = 0.14f) to Warm
        Tone.Accent -> c.primary.copy(alpha = 0.13f) to c.primary
        Tone.Neutral -> c.surfaceContainerHighest to c.onSurfaceVariant
    }
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(if (b.dashed) Color.Transparent else bg)
            .then(if (b.dashed) Modifier.border(BorderStroke(1.dp, fg.copy(alpha = 0.6f)), RoundedCornerShape(10.dp)) else Modifier)
            .padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(b.icon, null, Modifier.size(16.dp), tint = fg)
        Spacer(Modifier.width(6.dp))
        Text(b.label, style = MaterialTheme.typography.labelMedium, color = if (b.tone == Tone.Neutral) c.onSurface else fg, maxLines = 1)
    }
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
fun FootageCard(st: AppState, v: VideoInfo, enabled: Boolean, onChange: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val f = v.footage
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surfaceContainer)
            .border(1.dp, c.outlineVariant, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(v.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull("${v.displayW} × ${v.displayH}", v.durationMs.takeIf { it > 0 }?.let(::fmtDuration),
                    v.sizeBytes.takeIf { it > 0 }?.let(::fmtSize)).joinToString("   "),
                    style = MaterialTheme.typography.bodySmall.merge(Mono), color = c.onSurfaceVariant)
            }
            TextButton(onClick = onChange, enabled = enabled) { Text(if (st.videos.size > 1) "Change clips" else "Change") }
        }
        f.camera?.let { cam ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Camera, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(cam, style = MaterialTheme.typography.bodyMedium)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            badgesFor(v).forEach { BadgeChip(it) }
        }
        footageHint(v)?.let { hint ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(AppIcons.Info, null, Modifier.size(16.dp).padding(top = 1.dp), tint = c.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(hint, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }

        val arrow by animateFloatAsState(if (open) 180f else 0f, label = "arrow")
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (open) "Hide details" else "All details", style = MaterialTheme.typography.labelLarge, color = c.primary, modifier = Modifier.weight(1f))
            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.rotate(arrow), tint = c.primary)
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { detailsFor(v).forEach { (k, value) -> DetailRow(k, value) } }
        }

        if (st.videos.size > 1) {
            HorizontalDivider(color = c.outlineVariant)
            Text("${st.videos.size} clips. The same settings apply to all.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(st.videos) { i, clip ->
                    FilterChip(selected = i == st.selected, onClick = { st.selected = i },
                        label = { Text(clip.name.substringBeforeLast('.'), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp)) })
                }
            }
        }
    }
}

@Composable
private fun DetailRow(k: String, v: String) = Row {
    Text(k, Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(v, style = MaterialTheme.typography.bodySmall.merge(Mono))
}

private fun footageHint(v: VideoInfo): String? {
    val f = v.footage
    return when {
        f.gamma.isConfirmedLog -> "${f.gamma.profile ?: "Log"}, confirmed by camera metadata. Lossless keeps it untouched for grading; in Re-encode you can apply your camera's official LUT."
        f.gamma.isLog -> "${f.gamma.title}: ${f.gamma.reasons.firstOrNull() ?: ""} Lossless keeps it untouched either way. Check the footage before applying a log LUT."
        f.gamma.isHdr -> "${f.gamma.title} clip (${f.gamma.reasons.firstOrNull()?.removeSuffix(".")?.lowercase() ?: "from metadata"}). Lossless keeps it exactly; Re-encode keeps HDR when “Keep HDR” is on in Settings."
        else -> null
    }
}

private fun detailsFor(v: VideoInfo): List<Pair<String, String>> {
    val f = v.footage
    val kbps = if (v.durationMs > 0 && v.sizeBytes > 0) (v.sizeBytes * 8 / v.durationMs) else 0
    return listOfNotNull(
        f.camera?.let { "Camera" to it },
        "Codec" to listOfNotNull(v.codec, f.profile, f.fourcc?.let { "($it)" }).joinToString(" "),
        "Resolution" to "${v.displayW} × ${v.displayH}" + if (v.rotation != 0) " (rotated ${v.rotation}°)" else "",
        if (v.fps > 0) "Frame rate" to fmtFps(v.fps) else null,
        "Bit depth" to "${v.bitDepth}-bit" + (f.chroma?.let { ", $it" } ?: ""),
        "Gamma" to "${f.gamma.title} (${f.gamma.confidence.label.lowercase()})",
        f.primariesName?.let { "Primaries" to it },
        f.transferName?.let { "Transfer tag" to it },
        f.matrixName?.let { "Matrix" to it },
        f.fullRange?.let { "Range" to if (it) "Full" else "Limited (video)" },
        if (f.masteringInfo) "HDR metadata" to "Mastering display / light level present" else null,
        "Pixel aspect" to (f.pixelAspect?.let { (h, vv) -> if (h == vv) "Square (1:1)" else "$h:$vv (${fmtSqueeze(h.toFloat() / vv)})" } ?: "Not tagged (square)"),
        "Audio" to (v.audio ?: "None"),
        if (v.durationMs > 0) "Duration" to fmtDuration(v.durationMs) else null,
        if (v.sizeBytes > 0) "File size" to fmtSize(v.sizeBytes) else null,
        if (kbps > 0) "Avg. bitrate" to "${(kbps / 1000.0).roundToInt()} Mbps" else null,
    ) + f.gamma.reasons.mapIndexed { i, r -> (if (i == 0) "Why" else "") to r }
}
