package com.desqueeze.app

import android.media.MediaFormat
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/** What this phone can actually export, read from its hardware codecs. */
@Composable
fun LimitsScreen(onBack: () -> Unit) {
    val all = remember { runCatching { DeviceCaps.all() }.getOrDefault(emptyList()) }
    var showAll by remember { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        TopBar("Export limits", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)

            val hevcDec = all.filter { !it.encoder && it.hardware && it.mime == MediaFormat.MIMETYPE_VIDEO_HEVC }
            Group("Playback on this phone") {
                InfoCard {
                    Stat("Plays 10-bit HEVC in hardware", yesNo(hevcDec.any { it.tenBit }))
                    Stat("Largest HEVC it can decode", hevcDec.maxByOrNull { it.maxW }?.let { "${it.maxW} × ${it.maxH}" } ?: "Not supported")
                }
            }
            listOf(MediaFormat.MIMETYPE_VIDEO_HEVC to "HEVC export", MediaFormat.MIMETYPE_VIDEO_AVC to "H.264 export").forEach { (mime, title) ->
                val enc = all.filter { it.encoder && it.mime == mime }
                val hw = enc.filter { it.hardware }.ifEmpty { enc }
                Group(title) {
                    if (hw.isEmpty()) InfoCard { Stat("Available", "No") } else InfoCard {
                        val w2160 = hw.mapNotNull { it.maxW2160 }.maxOrNull()
                        val w1080 = hw.mapNotNull { it.maxW1080 }.maxOrNull()
                        Stat("Hardware encoder", yesNo(enc.any { it.hardware }))
                        Stat("10-bit output", yesNo(hw.any { it.tenBit }))
                        Stat("Widest frame at 2160 tall", w2160?.let { "$it px" } ?: "4K not supported")
                        Stat("Max squeeze for 3840 × 2160", maxSqueeze(w2160, 3840), highlight = true)
                        Stat("Max squeeze for 1920 × 1080", maxSqueeze(w1080, 1920), highlight = true)
                        Stat("Max frame rate at 4K", hw.mapNotNull { it.fps4k }.maxOrNull()?.let { "$it fps" } ?: "—")
                        Stat("Max frame rate at 1080p", hw.mapNotNull { it.fps1080 }.maxOrNull()?.let { "$it fps" } ?: "—")
                        Stat("Max bitrate", "${hw.maxOf { it.maxBitrateMbps }} Mbps")
                    }
                }
            }
            Text("If a squeeze needs a wider frame than the encoder allows, the app shrinks the whole frame evenly so the shape stays correct, and tells you when it does.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))

            Column {
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Hide all codecs" else "Show all ${all.size} codecs") }
                AnimatedVisibility(showAll) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        all.forEach { cap ->
                            InfoCard {
                                Text(cap.name, style = MaterialTheme.typography.bodyMedium)
                                Text(listOfNotNull(cap.codecLabel, if (cap.encoder) "encoder" else "decoder",
                                    if (cap.hardware) "hardware" else "software", "${cap.maxW} × ${cap.maxH}",
                                    if (cap.tenBit) "10-bit" else null).joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall.merge(Mono), color = c.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun yesNo(b: Boolean) = if (b) "Yes" else "No"

private fun maxSqueeze(maxW: Int?, srcW: Int): String {
    if (maxW == null || maxW < srcW) return "Will be scaled down"
    val f = floor(maxW.toFloat() / srcW * 100) / 100
    return if (f >= 3f) "3.0× or more" else fmtSqueeze(f)
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceContainer)
        .border(1.dp, c.outlineVariant, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
private fun Stat(label: String, value: String, highlight: Boolean = false) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(Mono),
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
