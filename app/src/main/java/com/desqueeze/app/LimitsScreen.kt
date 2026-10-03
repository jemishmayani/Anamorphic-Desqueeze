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
private data class Diagnostics(
    val summary: List<Check>, val device: List<Pair<String, String>>, val speeds: List<Pair<String, String>>,
    val checks: List<Check>, val squeezes: List<SqueezeCheck>, val codecs: List<CodecCap>,
)
private data class SqueezeCheck(val label: String, val status: Status, val detail: String)

/** Hardware diagnostics: what this phone can decode/encode, and what each squeeze means for Re-encode. */
@Composable
fun LimitsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    var showAll by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val data by produceState<Diagnostics?>(null) {
        value = withContext(Dispatchers.Default) {
            val all = runCatching { DeviceCaps.all() }.getOrDefault(emptyList())
            val exporter = Exporter(ctx, Settings(ctx), LutManager(ctx))
            Diagnostics(summaryChecks(ctx, all, exporter), deviceInfo(ctx), speedInfo(ctx, all), capabilityChecks(all), squeezeChecks(exporter), all)
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
                Group("What this means for you") {
                    DiagCard { d.summary.forEachIndexed { i, ch -> if (i > 0) HorizontalDivider(color = c.outlineVariant); CheckRow(ch) } }
                }
                Group("Your phone") { DiagCard { d.device.forEach { (k, v) -> KeyValue(k, v) } } }
                Group("Speed") {
                    DiagCard { d.speeds.forEach { (k, v) -> KeyValue(k, v) } }
                    Text("Measured speeds come from your own exports and update each time. Manufacturer figures are the codec's published ratings.",
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                }
                Group("Video hardware") {
                    DiagCard { d.checks.forEachIndexed { i, ch -> if (i > 0) HorizontalDivider(color = c.outlineVariant); CheckRow(ch) } }
                }
                Group("Squeeze check for Re-encode") {
                    DiagCard { d.squeezes.forEachIndexed { i, sq -> if (i > 0) HorizontalDivider(color = c.outlineVariant); SqueezeRow(sq) } }
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
                        StatusIcon(Status.OK, 16); Spacer(Modifier.width(8.dp))
                        Text("Lossless works at full resolution for every squeeze, because it doesn't use the encoder.",
                            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                }
                OutlinedButton(onClick = {
                    val text = buildString {
                        appendLine(Diag.header(ctx)); appendLine()
                        (d.summary + d.checks).forEach { appendLine("[${it.status}] ${it.label}${it.value?.let { v -> ": $v" } ?: ""}") }
                        appendLine(); d.device.forEach { (k, v) -> appendLine("$k: $v") }
                        d.speeds.forEach { (k, v) -> appendLine("$k: $v") }
                        appendLine(); d.squeezes.forEach { appendLine("[${it.status}] ${it.label}: ${it.detail}") }
                        appendLine(); d.codecs.forEach { appendLine("${it.name}: ${it.codecLabel} ${if (it.encoder) "enc" else "dec"} ${if (it.hardware) "hw" else "sw"} ${it.maxW}x${it.maxH} ${if (it.tenBit) "10-bit" else ""} ${it.measured4k ?: ""}") }
                    }
                    ctx.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("diagnostics", text))
                    copied = true
                }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
                    Text(if (copied) "Copied" else "Copy diagnostics")
                }
                Column {
                    TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Hide all codecs" else "Show all ${d.codecs.size} codecs") }
                    AnimatedVisibility(showAll) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            d.codecs.forEach { cap ->
                                DiagCard {
                                    Text(cap.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(listOfNotNull(cap.codecLabel, if (cap.encoder) "encoder" else "decoder",
                                        if (cap.hardware) "hardware" else "software", "${cap.maxW} × ${cap.maxH}",
                                        if (cap.tenBit) "10-bit" else null, if (cap.vendor) "vendor" else null,
                                        cap.measured4k?.let { "4K measured $it" }).joinToString(", "),
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
        Check("VP9 decode", if (DeviceCaps.canDecode("VP9", 8)) Status.OK else Status.NO, null, null),
        Check("Dolby Vision decode", if (DeviceCaps.infos.any { !it.isEncoder && it.supportedTypes.any { t -> t.equals("video/dolby-vision", true) } }) Status.OK else Status.NO, null,
            "Dolby Vision clips (e.g. from iPhones) play as HDR10/HLG when this is missing."),
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

@Composable
private fun KeyValue(k: String, v: String) = Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
    Text(k, Modifier.width(130.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(v, style = MaterialTheme.typography.bodySmall.merge(Mono))
}

private fun summaryChecks(ctx: android.content.Context, all: List<CodecCap>, exporter: Exporter): List<Check> {
    val hevc = MediaFormat.MIMETYPE_VIDEO_HEVC
    val enc = all.filter { it.encoder && it.hardware && it.mime == hevc }.ifEmpty { all.filter { it.encoder && it.hardware } }
    val mime = if (Exporter.hasEncoder("video/hevc")) "video/hevc" else "video/avc"
    fun maxFull(w: Int, h: Int): Float {
        var best = 1f; var s = 1.0f
        while (s <= 3.0f) { val ow = ((w * s).toInt() / 2) * 2; val (fw, _, _) = exporter.fitToEncoder(ow, h, mime, 30f); if (fw >= ow) best = s else break; s += 0.01f }
        return best
    }
    val m4k = maxFull(3840, 2160); val m1080 = maxFull(1920, 1080)
    val tenDec = all.any { !it.encoder && it.hardware && it.mime == hevc && it.tenBit }
    val tenEnc = enc.any { it.tenBit }
    val gles = (ctx.getSystemService(android.app.ActivityManager::class.java)).deviceConfigurationInfo.reqGlEsVersion
    return listOf(
        Check("Lossless: full resolution, any squeeze", Status.OK, null, "Your original pixels are kept; only the shape tag changes."),
        Check("Re-encode 4K at full size", if (m4k >= 1.33f) Status.OK else Status.WARN, "up to ${fmtSqueeze(m4k)}",
            if (m4k < 1.33f) "Bigger squeezes on 4K are scaled down to fit the encoder. Use Lossless for full 4K." else null),
        Check("Re-encode 1080p at full size", if (m1080 >= 1.5f) Status.OK else Status.WARN, "up to ${fmtSqueeze(m1080)}", null),
        Check("Preview 10-bit log / HDR", if (tenDec) Status.OK else Status.NO, null,
            if (!tenDec) "10-bit clips may not play in the preview. Lossless export still works." else null),
        Check("Keep HDR when re-encoding", if (tenEnc) Status.OK else Status.NO, null,
            if (tenEnc) "HLG/HDR10 clips stay 10-bit. Log clips are re-encoded in 8-bit; Lossless keeps them 10-bit."
            else "Re-encoded files are 8-bit on this phone."),
        Check("Live LUT preview", if (gles >= 0x30000) Status.OK else Status.WARN, null,
            if (gles >= 0x30000) "Uses the GPU (OpenGL ES ${gles shr 16}.${gles and 0xFFFF}). If it fails on a clip, Compare still works."
            else "This GPU may not support live effects; Compare still works."),
    )
}

private fun deviceInfo(ctx: android.content.Context): List<Pair<String, String>> {
    val am = ctx.getSystemService(android.app.ActivityManager::class.java)
    val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val stat = try { android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path) } catch (_: Exception) { null }
    val display = try { ctx.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY) } catch (_: Exception) { null }
    @Suppress("DEPRECATION")
    val hdrTypes = display?.hdrCapabilities?.supportedHdrTypes?.map {
        when (it) { 1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "type $it" } }.orEmpty()
    val gles = am.deviceConfigurationInfo.reqGlEsVersion
    fun gb(b: Long) = "%.1f GB".format(b / 1073741824.0)
    return listOfNotNull(
        "Model" to "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
        if (Build.VERSION.SDK_INT >= 31) "Chipset" to "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else null,
        "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}",
        "CPU" to "${Runtime.getRuntime().availableProcessors()} cores, ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}",
        "Memory" to "${gb(mem.totalMem)} total, ${gb(mem.availMem)} free",
        stat?.let { "Storage free" to gb(it.availableBytes) },
        display?.let { d -> "Display" to "${d.mode.physicalWidth} × ${d.mode.physicalHeight}, ${d.refreshRate.toInt()} Hz" },
        "Display HDR" to (hdrTypes.joinToString(", ").ifEmpty { "Not supported" }),
        "Graphics" to "OpenGL ES ${gles shr 16}.${gles and 0xFFFF}",
    )
}

private fun speedInfo(ctx: android.content.Context, all: List<CodecCap>): List<Pair<String, String>> {
    val enc = all.filter { it.encoder && it.hardware && it.mime == MediaFormat.MIMETYPE_VIDEO_HEVC }
    val manuf = enc.mapNotNull { it.measured4k }.firstOrNull()
    val learned = ctx.getSharedPreferences("speed", android.content.Context.MODE_PRIVATE)
    return listOfNotNull(
        "Re-encode (yours)" to (if (learned.contains("pps")) "≈ %.0f fps at 4K".format(Speed.encodePps(ctx) / (3840f * 2160f)) else "Not measured yet"),
        "Lossless copy (yours)" to (if (learned.contains("bps")) "≈ %.0f MB/s".format(Speed.copyBps(ctx) / 1048576f) else "Not measured yet"),
        "4K HEVC encode" to (manuf?.let { "$it (manufacturer)" } ?: "Not published"),
    )
}
