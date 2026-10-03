package com.desqueeze.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MainScreen(act: MainActivity, st: AppState, settings: Settings, luts: LutManager) {
    val ctx = LocalContext.current
    val exporter = remember { Exporter(ctx, settings, luts) }
    val memory = remember { PlayheadMemory() }

    fun importClips(uris: List<android.net.Uri>, append: Boolean) {
        if (uris.isEmpty()) return
        act.lifecycleScope.launch {
            st.status = "Reading clips…"
            val errs = mutableListOf<String>()
            val found = withContext(Dispatchers.IO) {
                uris.mapNotNull { u -> try { VideoProbe.probe(ctx, u) } catch (e: Exception) { errs += (e.message ?: "Couldn't read this file"); null } }
            }
            val known = if (append) st.videos.map { it.uri }.toSet() else emptySet()
            st.videos = (if (append) st.videos else emptyList()) + found.filter { it.uri !in known }
            if (!append) { st.selected = 0; memory.positionMs = 0 }
            st.results = emptyList()
            // Keep the factor the user already chose; only fall back to the saved default if they haven't.
            if (!st.squeezeChosen) st.squeeze = settings.defaultSqueeze
            st.status = errs.joinToString("\n")
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importClips(it, append = false) }
    val adder = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importClips(it, append = true) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { StepBar(act, st, exporter) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(Modifier.size(36.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Anamorphic De-Squeeze", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { st.screen = Screen.Settings }, enabled = !st.busy) { Icon(Icons.Default.Settings, "Settings") }
                }
                Spacer(Modifier.height(10.dp))
                StepHeader(st)
                Spacer(Modifier.height(6.dp))
                FlareLine(alpha = 0.6f)
            }
            AnimatedContent(st.step, label = "step", transitionSpec = {
                val fwd = targetState.ordinal > initialState.ordinal
                (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { if (fwd) it / 10 else -it / 10 }) togetherWith fadeOut(tween(120))
            }) { step ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    when (step) {
                        Step.Clips -> ClipsStep(st, onPick = { picker.launch(arrayOf("video/*")) }, onAdd = { adder.launch(arrayOf("video/*")) })
                        Step.Frame -> FrameStep(st, memory)
                        Step.Look -> LookStep(st, luts, memory)
                        Step.Export -> ExportStep(st, settings, exporter)
                    }
                    if (st.status.isNotEmpty() && !st.busy)
                        Text(st.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ step header + bottom bar */

@Composable
fun StepHeader(st: AppState) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Step.entries.forEachIndexed { i, s ->
            val current = s == st.step
            val done = s.ordinal < st.step.ordinal
            val enabled = !st.busy && (s == Step.Clips || st.videos.isNotEmpty())
            Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled) { st.step = s }.padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(when { current -> c.primary; done -> c.primary.copy(alpha = 0.18f); else -> c.surfaceContainerHigh }),
                    contentAlignment = Alignment.Center) {
                    if (done) Icon(AppIcons.Check, null, Modifier.size(14.dp), tint = c.primary)
                    else Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = if (current) c.onPrimary else c.onSurfaceVariant)
                }
                Spacer(Modifier.width(6.dp))
                Text(s.label, style = MaterialTheme.typography.labelLarge, color = if (current) c.onSurface else c.onSurfaceVariant)
            }
            if (i < Step.entries.size - 1) Box(Modifier.weight(1f).height(1.dp).background(if (done) c.primary.copy(alpha = 0.5f) else c.outlineVariant))
        }
    }
}

@Composable
fun StepBar(act: MainActivity, st: AppState, exporter: Exporter) {
    val p by animateFloatAsState(st.progress, tween(300), label = "progress")
    val hasClips = st.videos.isNotEmpty()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
            if (st.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(st.status, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium.merge(Mono))
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { st.job?.cancel(); st.busy = false; st.status = "Export cancelled."; act.keepScreenOn(false) },
                    Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Cancel export") }
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (st.step != Step.Clips) OutlinedButton(onClick = { st.step = Step.entries[st.step.ordinal - 1] },
                    Modifier.height(56.dp), shape = RoundedCornerShape(16.dp)) { Text("Back") }
                val last = st.step == Step.Export
                Button(
                    onClick = { if (last) startExport(act, st, exporter) else st.step = Step.entries[st.step.ordinal + 1] },
                    enabled = hasClips, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(16.dp),
                ) {
                    Text(when {
                        !last -> "Next: ${Step.entries[st.step.ordinal + 1].label}"
                        st.videos.size > 1 -> "Export ${st.videos.size} videos"
                        else -> "Export de-squeezed video"
                    }, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ 1. Clips */

@Composable
fun ClipsStep(st: AppState, onPick: () -> Unit, onAdd: () -> Unit) {
    if (st.videos.isEmpty()) { EmptyPreview(onPick); return }
    val c = MaterialTheme.colorScheme
    Section(if (st.videos.size == 1) "Your clip" else "${st.videos.size} clips", trailing = {
        TextButton(onClick = onAdd, enabled = !st.busy) { Text("Add clips") }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            st.videos.forEachIndexed { i, v ->
                ClipRow(v, selected = i == st.selected, onClick = { st.selected = i },
                    onRemove = if (st.busy) null else ({
                        st.videos = st.videos.filterIndexed { j, _ -> j != i }
                        st.selected = st.selected.coerceAtMost((st.videos.size - 1).coerceAtLeast(0))
                    }))
            }
        }
        if (st.videos.size > 1) Text("Settings you choose next apply to every clip.", style = MaterialTheme.typography.bodySmall,
            color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
    st.videos.getOrNull(st.selected)?.let { FootageCard(st, it, enabled = !st.busy, onChange = onPick) }
}

@Composable
fun ClipRow(v: VideoInfo, selected: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)?) {
    val c = MaterialTheme.colorScheme
    val border = if (selected) c.primary else c.outlineVariant
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer)
        .border(if (selected) 1.5.dp else 1.dp, border, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            val img = remember(v.thumb) { v.thumb?.asImageBitmap() }
            if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(AppIcons.Fps, null, tint = Color.White.copy(alpha = 0.4f))
            Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 5.dp, vertical = 1.dp)) {
                Text(fmtDuration(v.durationMs), style = MaterialTheme.typography.labelSmall.merge(Mono), color = Color.White)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(clipFacts(v), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 2)
            v.footage.camera?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1) }
        }
        if (onRemove != null) IconButton(onClick = onRemove) { Icon(AppIcons.Cross, "Remove", Modifier.size(16.dp), tint = c.onSurfaceVariant) }
    }
}

fun resolutionName(w: Int, h: Int): String {
    val long = maxOf(w, h); val short = minOf(w, h)
    return when {
        long >= 7680 -> "8K"; long >= 5120 -> "${long / 1000}K"; long >= 3840 || short >= 2160 -> "4K"
        short >= 1440 -> "2.7K"; short >= 1080 -> "1080p"; short >= 720 -> "720p"; else -> "${w}×$h"
    }
}

fun clipFacts(v: VideoInfo) = listOfNotNull(
    resolutionName(v.displayW, v.displayH), "${v.bitDepth}-bit",
    v.footage.log?.let { if (v.footage.logEstimated) "Log?" else it } ?: v.footage.hdr,
    if (v.fps > 0) fmtFps(v.fps) else null,
).joinToString(" • ")

/* ------------------------------------------------------------------ 2. Frame */

@Composable
fun FrameStep(st: AppState, memory: PlayheadMemory) {
    val v = st.videos.getOrNull(st.selected) ?: return
    val g = geometry(v, st.squeeze, st.orientation, st.direction)
    if (st.busy) ExportingPlaceholder(g.outRatio) else PreviewPlayer(v, g, st.desqueezed, memory)
    ViewToggle(st.desqueezed) { st.desqueezed = it }

    Section("Squeeze factor", trailing = {
        Text(fmtSqueeze(st.squeeze), style = MaterialTheme.typography.titleLarge.merge(Mono), color = MaterialTheme.colorScheme.primary)
    }) {
        SqueezeChips(st)
        Spacer(Modifier.height(6.dp))
        Text("${g.dispW} × ${g.dispH}  displays as  ${g.outW} × ${g.outH}", style = MaterialTheme.typography.bodyMedium.merge(Mono))
        Text("${g.ratioLabel()}. Full frame kept, nothing cropped.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Section("Orientation") {
        Segmented(Orientation.entries.map { it.label }, st.orientation.ordinal, enabled = !st.busy) { st.orientation = Orientation.entries[it] }
    }
    Section("Desqueeze direction") {
        Segmented(Direction.entries.map { it.label }, st.direction.ordinal, enabled = !st.busy) { st.direction = Direction.entries[it] }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(AppIcons.Rotate, null, Modifier.size(16.dp).padding(top = 1.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(directionHint(v, g, st.orientation, st.direction) + " Use Vertical when the camera or phone was turned 90° with the lens attached.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Segmented(options: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(selected = i == selected, onClick = { onSelect(i) }, enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label, maxLines = 1) }
        }
    }
}

/* ------------------------------------------------------------------ 3. Look */

@Composable
fun LookStep(st: AppState, luts: LutManager, memory: PlayheadMemory) {
    val ctx = LocalContext.current
    val v = st.videos.getOrNull(st.selected) ?: return
    val g = geometry(v, st.squeeze, st.orientation, st.direction)
    val cube by produceState<LutManager.CubeLut?>(null, st.lutId) {
        value = st.lutId?.let { id -> withContext(Dispatchers.IO) { try { luts.load(id) } catch (_: Exception) { null } } }
    }
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) try {
            st.lutId = luts.import(u, displayName(ctx, u)).id; st.lutList = luts.list(); st.mode = ExportMode.REENCODE
        } catch (e: Exception) { st.status = "This LUT couldn't be loaded: ${e.message}" }
    }

    if (st.busy) ExportingPlaceholder(g.outRatio)
    else PreviewPlayer(v, g, st.desqueezed, memory, lut = cube, lutStrength = st.strength, lutOn = st.lutPreview)

    Section("LUT") {
        PickerRow(null, st.lutList.firstOrNull { it.id == st.lutId }?.name ?: "No LUT",
            listOf("No LUT") + st.lutList.map { it.name } + "Import a .cube file…", enabled = !st.busy) { i ->
            when {
                i == 0 -> st.lutId = null
                i <= st.lutList.size -> { st.lutId = st.lutList[i - 1].id; st.mode = ExportMode.REENCODE }
                else -> lutPicker.launch(arrayOf("*/*"))
            }
        }
        AnimatedVisibility(st.lutId != null) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Segmented(listOf("LUT off", "LUT on"), if (st.lutPreview) 1 else 0) { st.lutPreview = it == 1 }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Strength", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${(st.strength * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium.merge(Mono))
                }
                Slider(st.strength, { st.strength = it }, enabled = !st.busy)
                Text("The live preview uses a lighter ~720p proxy so it plays smoothly. Tap Compare on the video for a full-quality before/after still. " +
                    "LUTs are applied in Re-encode, so export switched to Re-encode.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (st.lutId == null) Text("Optional. Import your camera maker's official log-to-Rec.709 LUT, or any creative 3D .cube LUT.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

/* ------------------------------------------------------------------ 4. Export */

@Composable
fun ExportStep(st: AppState, settings: Settings, exporter: Exporter) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val v = st.videos.getOrNull(st.selected) ?: return
    val g = geometry(v, st.squeeze, st.orientation, st.direction)
    val mime = if (st.codec == Codec.HEVC && Exporter.hasEncoder("video/hevc")) "video/hevc" else "video/avc"

    val fit by produceState<Pair<Int, Int>?>(null, v, g, st.codec) {
        value = withContext(Dispatchers.Default) { exporter.targetSize(g, v.fps, mime).let { it.first to it.second } }
    }
    val rec = remember(v, g, st.squeeze, st.lutId, fit) { recommend(v, g, st.squeeze, st.lutId != null, fit) }
    val est by produceState<List<Estimate>?>(null, st.videos, st.squeeze, st.orientation, st.direction, st.mode, st.quality, st.codec) {
        value = withContext(Dispatchers.Default) {
            st.videos.map { clip -> estimate(ctx, exporter, settings, clip, geometry(clip, st.squeeze, st.orientation, st.direction), st.mode) }
        }
    }

    RecommendationCard(rec, st.mode, enabled = !st.busy) { st.mode = it; settings.mode = it }

    Section("Export method") {
        MethodToggle(st.mode, enabled = !st.busy) { st.mode = it; settings.mode = it }
        Spacer(Modifier.height(8.dp))
        Text(if (st.mode == ExportMode.LOSSLESS)
            "Copies your file untouched and tags its pixel aspect ratio, like setting it in DaVinci Resolve. Editors and players like Resolve, Premiere, Final Cut and VLC show it wide; a few apps and social sites ignore the tag." +
                if (st.lutId != null) " Your LUT won't be applied in Lossless." else ""
        else "Renders new pixels so every app shows it de-squeezed${if (st.lutId != null) ", with your LUT baked in" else ""}. Slower and re-compressed.",
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        AnimatedVisibility(st.mode == ExportMode.REENCODE) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PickerRow("Quality", st.quality.label, Quality.entries.map { it.label }, enabled = !st.busy) {
                    st.quality = Quality.entries[it]; settings.quality = st.quality }
                PickerRow("Codec", if (st.codec == Codec.HEVC) "HEVC" else "H.264", Codec.entries.map { it.label }, enabled = !st.busy) {
                    st.codec = Codec.entries[it]; settings.codec = st.codec }
            }
        }
    }

    Section("Estimate") {
        val list = est
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer)
            .border(1.dp, c.outlineVariant, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (list == null) Text("Calculating…", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            else {
                val e = list[st.selected.coerceIn(0, list.size - 1)]
                EstRow("Output", "${e.outW} × ${e.outH}")
                EstRow("Output size", "≈ " + fmtSize(list.sumOf { it.bytes }) + if (list.size > 1) " (all ${list.size})" else "")
                EstRow("Processing time", fmtEta(list.sumOf { it.seconds }))
                if (st.mode == ExportMode.REENCODE) {
                    val full = e.scaledNote == null
                    Row(verticalAlignment = Alignment.Top) {
                        StatusIcon(if (full) Status.OK else Status.WARN)
                        Spacer(Modifier.width(8.dp))
                        Text(if (full) "This phone's encoder handles the full size." else (e.scaledNote + " Lossless keeps full resolution."),
                            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                }
                Text("Estimates" + if (Speed.learned(ctx)) ", based on this phone's previous exports." else "; they get more accurate after your first export.",
                    style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            }
        }
    }
    AnimatedVisibility(st.results.isNotEmpty()) { Results(st.results, settings.folder) }
}

@Composable
private fun EstRow(k: String, v: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(v, style = MaterialTheme.typography.bodyMedium.merge(Mono))
}

@Composable
fun RecommendationCard(r: Recommendation, current: ExportMode, enabled: Boolean, onUse: (ExportMode) -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surfaceContainer)
        .border(1.dp, c.outlineVariant, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(r.facts.joinToString("\n"), style = MaterialTheme.typography.bodySmall.merge(Mono), color = c.onSurfaceVariant)
        HorizontalDivider(color = c.outlineVariant)
        Option(AppIcons.Spark, "Recommended", r.title, r.why, highlighted = true, chosen = current == r.mode, enabled = enabled) { onUse(r.mode) }
        if (r.alt != null) Option(null, "Alternative", r.altTitle!!, r.altWhy!!, highlighted = false, chosen = current == r.alt, enabled = enabled) { onUse(r.alt) }
    }
}

@Composable
private fun Option(icon: ImageVector?, kicker: String, title: String, why: String, highlighted: Boolean, chosen: Boolean, enabled: Boolean, onUse: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(if (chosen) c.primary.copy(alpha = 0.10f) else Color.Transparent)
        .border(1.dp, if (chosen) c.primary.copy(alpha = 0.6f) else c.outlineVariant, RoundedCornerShape(14.dp))
        .clickable(enabled = enabled, onClick = onUse).padding(12.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) { Icon(icon, null, Modifier.size(14.dp), tint = if (highlighted) c.primary else c.onSurfaceVariant); Spacer(Modifier.width(6.dp)) }
                Text(kicker, style = MaterialTheme.typography.labelMedium, color = if (highlighted) c.primary else c.onSurfaceVariant)
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(why, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        if (chosen) Icon(AppIcons.Check, "Selected", Modifier.size(20.dp), tint = c.primary)
        else Text("Use", style = MaterialTheme.typography.labelLarge, color = c.primary, modifier = Modifier.padding(top = 2.dp))
    }
}

enum class Status { OK, WARN, NO }

@Composable
fun StatusIcon(s: Status, size: Int = 18) {
    val (icon, color) = when (s) {
        Status.OK -> AppIcons.Check to Color(0xFF5BD68A)
        Status.WARN -> AppIcons.Warn to Warm
        Status.NO -> AppIcons.Cross to MaterialTheme.colorScheme.error
    }
    Icon(icon, s.name, Modifier.size(size.dp), tint = color)
}
