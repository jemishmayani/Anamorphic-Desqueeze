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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Check(val label: String, val status: Status, val value: String?, val why: String?)
private data class SqueezeCheck(val label: String, val status: Status, val detail: String)

/** Hardware diagnostics: what this phone can decode/encode, and what each squeeze means for Re-encode. */
@Composable
fun LimitsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    var showAll by remember { mutableStateOf(false) }
    val data by produceState<Triple<List<Check>, List<SqueezeCheck>, List<CodecCap>>?>(null) {
        value = withContext(Dispatchers.Default) {
            val all = runCatching { DeviceCaps.all() }.getOrDefault(emptyList())
            Triple(capabilityChecks(all), squeezeChecks(Exporter(ctx, Settings(ctx), LutManager(ctx))), all)
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Device diagnostics", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            val d = data
            if (d == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
                Group("Device capability") {
                    DiagCard { d.first.forEachIndexed { i, ch -> if (i > 0) HorizontalDivider(color = c.outlineVariant); CheckRow(ch) } }
                }
                Group("Squeeze check for Re-encode") {
                    DiagCard { d.second.forEachIndexed { i, s -> if (i > 0) HorizontalDivider(color = c.outlineVariant); SqueezeRow(s) } }
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
                        StatusIcon(Status.OK, 16); Spacer(Modifier.width(8.dp))
                        Text("Lossless works at full resolution for every squeeze, because it doesn't use the encoder.",
                            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                }
                Column {
                    TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Hide all codecs" else "Show all ${d.third.size} codecs") }
                    AnimatedVisibility(showAll) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            d.third.forEach { cap ->
                                DiagCard {
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
}

private fun capabilityChecks(all: List<CodecCap>): List<Check> {
    val hevc = MediaFormat.MIMETYPE_VIDEO_HEVC; val avc = MediaFormat.MIMETYPE_VIDEO_AVC
    fun hw(enc: Boolean, mime: String) = all.filter { it.encoder == enc && it.mime == mime && it.hardware }
    fun any(enc: Boolean, mime: String) = all.filter { it.encoder == enc && it.mime == mime }
    val hevcDec = hw(false, hevc); val hevcEnc = hw(true, hevc); val avcEnc = hw(true, avc); val avcDec = hw(false, avc)
    val maxW2160 = hevcEnc.mapNotNull { it.maxW2160 }.maxOrNull() ?: avcEnc.mapNotNull { it.maxW2160 }.maxOrNull()
    val fps4k = (hevcEnc + avcEnc).mapNotNull { it.fps4k }.maxOrNull()
    val maxBr = (hevcEnc + avcEnc).maxOfOrNull { it.maxBitrateMbps }
    val av1 = DeviceCaps.canDecode("AV1", 8)
    return listOf(
        Check("HEVC decode", st(hevcDec.isNotEmpty(), any(false, hevc).isNotEmpty()), null,
            if (hevcDec.isEmpty()) "No hardware HEVC decoder; HEVC clips may preview slowly or not at all." else null),
        Check("10-bit HEVC decode", if (hevcDec.any { it.tenBit }) Status.OK else Status.NO, null,
            if (hevcDec.none { it.tenBit }) "10-bit log/HDR clips can't be previewed or re-encoded here. Lossless still works." else null),
        Check("H.264 decode", st(avcDec.isNotEmpty(), any(false, avc).isNotEmpty()), null, null),
        Check("AV1 decode", if (av1) Status.OK else Status.NO, null, if (!av1) "Only matters for AV1 clips. Lossless still tags them." else null),
        Check("HEVC encode", st(hevcEnc.isNotEmpty(), any(true, hevc).isNotEmpty()), null,
            if (hevcEnc.isEmpty()) "Re-encode will use H.264, which needs a higher bitrate for the same quality." else null),
        Check("10-bit encode", if (hevcEnc.any { it.tenBit }) Status.OK else Status.NO, null,
            if (hevcEnc.any { it.tenBit }) "Used for HLG/HDR10 clips. Log clips are tagged as standard video, so Android re-encodes them in 8-bit; use Lossless to keep 10-bit."
            else "Re-encoded files are always 8-bit on this phone. Use Lossless to keep 10-bit."),
        Check("H.264 encode", st(avcEnc.isNotEmpty(), any(true, avc).isNotEmpty()), null, null),
        Check("Max frame width", if ((maxW2160 ?: 0) >= 3840) Status.OK else Status.WARN, maxW2160?.let { "$it px" } ?: "—",
            "The widest frame the encoder accepts at 2160 px tall. A wider de-squeeze is scaled down evenly in Re-encode."),
        Check("Max 4K frame rate", if ((fps4k ?: 0) >= 30) Status.OK else Status.WARN, fps4k?.let { "$it fps" } ?: "—", null),
        Check("Max bitrate", Status.OK, maxBr?.let { "$it Mbps" } ?: "—", null),
    )
}

private fun st(hw: Boolean, any: Boolean) = when { hw -> Status.OK; any -> Status.WARN; else -> Status.NO }

private fun squeezeChecks(exporter: Exporter): List<SqueezeCheck> {
    val mime = if (Exporter.hasEncoder("video/hevc")) "video/hevc" else "video/avc"
    val out = mutableListOf<SqueezeCheck>()
    for ((name, w, h) in listOf(Triple("4K", 3840, 2160), Triple("1080p", 1920, 1080))) {
        for (s in listOf(1.33f, 1.5f, 1.8f, 2.0f)) {
            val ow = ((w * s).toInt() / 2) * 2
            val (fw, fh, _) = exporter.fitToEncoder(ow, h, mime, 30f)
            val keep = fw.toFloat() / ow
            val status = when { fw >= ow && fh >= h -> Status.OK; keep >= 0.75f -> Status.WARN; else -> Status.NO }
            val detail = when (status) {
                Status.OK -> "Full $ow × $h."
                Status.WARN -> "Needs $ow px wide; the encoder's limit means $fw × $fh (${(keep * 100).toInt()}% size)."
                Status.NO -> "Needs $ow px wide; Re-encode can only make $fw × $fh (${(keep * 100).toInt()}% size), losing a lot of detail. Use Lossless."
            }
            out += SqueezeCheck("$name ${fmtSqueeze(s)}", status, detail)
        }
    }
    return out
}

@Composable
private fun CheckRow(ch: Check) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ch.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            ch.value?.let { Text(it, style = MaterialTheme.typography.bodyMedium.merge(Mono)); Spacer(Modifier.width(8.dp)) }
            StatusIcon(ch.status)
        }
        ch.why?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, end = 26.dp)) }
    }
}

@Composable
private fun SqueezeRow(s: SqueezeCheck) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.merge(Mono))
            StatusIcon(s.status)
        }
        Text(s.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DiagCard(content: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceContainer)
        .border(1.dp, c.outlineVariant, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}
