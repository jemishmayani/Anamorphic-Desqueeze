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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
    val exporter = remember { ExportController.exporter ?: Exporter(ctx.applicationContext, settings, luts).also { ExportController.exporter = it } }
    val memory = st.memory

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importClips(ctx, st, settings, it, append = false) }
    val adder = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importClips(ctx, st, settings, it, append = true) }

    // Background export shows a progress notification; ask once (Android 13+), then export either way.
    var pendingExport by remember { mutableStateOf(false) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        settings.notifAsked = true
        if (pendingExport) { pendingExport = false; startExport(ctx, st, exporter) }
    }
    val begin: () -> Unit = {
        val needs = android.os.Build.VERSION.SDK_INT >= 33 && !settings.notifAsked &&
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needs) { pendingExport = true; notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        else startExport(ctx, st, exporter)
    }

    PreflightDialog(st) { begin() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { StepBar(st, exporter, begin) },
    ) { pad ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
            // Two panes on tablets, foldables and phones in landscape.
            val wide = maxWidth >= 700.dp || (maxWidth > maxHeight && maxWidth >= 560.dp)
            val previewMax = if (wide) (maxHeight - 150.dp).coerceAtLeast(160.dp) else (maxHeight * 0.34f).coerceAtLeast(150.dp)
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(Modifier.size(36.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Anamorphic De-Squeeze", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (wide) Box(Modifier.widthIn(max = 520.dp).weight(1.4f)) { StepHeader(st) }
                        IconButton(onClick = { st.screen = Screen.Settings }) { Icon(Icons.Default.Settings, "Settings") }
                    }
                    if (!wide) { Spacer(Modifier.height(10.dp)); StepHeader(st) }
                    Spacer(Modifier.height(6.dp))
                    FlareLine(alpha = 0.6f)
                }
                AnimatedContent(st.step, label = "step", transitionSpec = {
                    val fwd = targetState.ordinal > initialState.ordinal
                    (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { if (fwd) it / 10 else -it / 10 }) togetherWith fadeOut(tween(120))
                }) { step ->
                    val panes = Panes(wide)
                    when (step) {
                        Step.Clips -> ClipsStep(st, panes, onPick = { picker.launch(arrayOf("video/*")) }, onAdd = { adder.launch(arrayOf("video/*")) })
                        Step.Frame -> FrameStep(st, memory, settings, panes, previewMax)
                        Step.Look -> LookStep(st, luts, memory, panes, previewMax)
                        Step.Export -> ExportStep(st, settings, exporter, panes)
                    }
                }
            }
        }
    }
}

/** Lays a step out as one scrolling column (phones) or two independently scrolling panes (tablets, landscape). */
class Panes(val wide: Boolean)

@Composable
fun TwoPane(panes: Panes, left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    val spacing = Arrangement.spacedBy(20.dp)
    if (panes.wide) Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = spacing, content = left)
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = spacing, content = right)
    } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = spacing) {
        left(); right(); Spacer(Modifier.height(4.dp))
    }
}

/** One tool tab: an icon, a label, its current value (shown on the tab), and its controls. */
class Tool(val label: String, val icon: ImageVector, val value: String?, val active: Boolean = false,
           val content: @Composable ColumnScope.() -> Unit)

/**
 * Preview pinned in place with the tools as tabs. Changing a setting never scrolls the preview
 * away: on phones the preview stays on top and only the selected tool's controls scroll; on wide
 * screens the preview sits on the left and the tools on the right.
 */
@Composable
fun PreviewWithTools(panes: Panes, tools: List<Tool>, selected: Int, onSelect: (Int) -> Unit, preview: @Composable ColumnScope.() -> Unit) {
    val sel = selected.coerceIn(0, tools.size - 1)
    if (panes.wide) Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = preview)
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 16.dp)) {
            ToolTabs(tools, sel, onSelect)
            key(sel) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp), content = tools[sel].content)
            }
        }
    } else Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = preview)
        Box(Modifier.padding(horizontal = 12.dp)) { ToolTabs(tools, sel, onSelect) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 8.dp))
        key(sel) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp), content = tools[sel].content)
        }
    }
}

/**
 * Icon-only tool bar: equal square buttons spread across the width. A dot marks a tool that's changed
 * from its default; long-press shows the name. The panel below carries the full title and value.
 */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolTabs(tools: List<Tool>, selected: Int, onSelect: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        tools.forEachIndexed { i, t ->
            val on = i == selected
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(t.label + (t.value?.let { ": $it" } ?: "")) } },
                state = rememberTooltipState(),
            ) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (on) c.primary.copy(alpha = 0.18f) else c.surfaceContainer)
                        .border(1.dp, if (on) c.primary.copy(alpha = 0.75f) else c.outlineVariant, RoundedCornerShape(16.dp))
                        .clickable(onClickLabel = t.label) { onSelect(i) }
                        .semantics { contentDescription = t.label + (t.value?.let { ", $it" } ?: ""); this.selected = on },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(t.icon, null, Modifier.size(24.dp), tint = if (on) c.primary else c.onSurfaceVariant)
                    if (t.active) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(7.dp).clip(CircleShape)
                        .background(if (on) c.primary else c.primary.copy(alpha = 0.8f)))
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
fun StepBar(st: AppState, exporter: Exporter, begin: () -> Unit) {
    val p by animateFloatAsState(st.progress, tween(300), label = "progress")
    val hasClips = st.videos.isNotEmpty()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
            if (st.status.isNotEmpty() && !st.busy) {
                Text(st.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3)
                Spacer(Modifier.height(8.dp))
            }
            if (st.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(st.status, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium.merge(Mono))
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                Spacer(Modifier.height(10.dp))
                Text("Keeps going in the background; you can lock the phone or use other apps.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { ExportController.cancel(st) },
                    Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Cancel export") }
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (st.step != Step.Clips) OutlinedButton(onClick = { st.step = Step.entries[st.step.ordinal - 1] },
                    Modifier.height(56.dp), shape = RoundedCornerShape(16.dp)) { Text("Back") }
                val last = st.step == Step.Export
                Button(
                    onClick = {
                        if (!last) st.step = Step.entries[st.step.ordinal + 1]
                        else ExportController.scope.launch {
                            val issues = withContext(Dispatchers.Default) {
                                st.videos.map { it.name to compatFor(st, exporter, it, modeFor(st, exporter, it)) }.filter { it.second.worst != Status.OK }
                            }
                            if (issues.isEmpty()) begin() else st.preflight = issues
                        }
                    },
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
fun ClipsStep(st: AppState, panes: Panes, onPick: () -> Unit, onAdd: () -> Unit) {
    val c = MaterialTheme.colorScheme
    if (st.videos.isEmpty()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)
            .then(if (panes.wide) Modifier.widthIn(max = 720.dp) else Modifier), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (st.importing != null) ImportProgress(st) else EmptyPreview(onPick)
            Row(verticalAlignment = Alignment.Top) {
                Icon(AppIcons.Info, null, Modifier.size(16.dp).padding(top = 1.dp), tint = c.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("Tip: in your gallery, select clips, tap Share and choose De-Squeeze to open them here directly.",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
        return
    }
    TwoPane(panes, left = {
        ImportProgress(st)
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
            if (st.videos.size > 1) Text("Each clip can have its own squeeze, trim and export method in the next steps.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }, right = {
        st.videos.getOrNull(st.selected)?.let { FootageCard(st, it, enabled = !st.busy, onChange = onPick) }
    })
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
    lookLabel(v.footage.gamma),
    if (v.fps > 0) fmtFps(v.fps) else null,
).joinToString(" • ")

/** Thumbnail strip to switch the clip being previewed/inspected. Optional caption under each (e.g. its export method). */
@Composable
fun ClipSwitcher(st: AppState, caption: ((VideoInfo) -> String?)? = null) {
    if (st.videos.size < 2) return
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Clip ${st.selected + 1} of ${st.videos.size}. Tap to switch.", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(st.videos.size) { i ->
                val v = st.videos[i]; val sel = i == st.selected
                Column(Modifier.width(96.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = !st.busy) { st.selected = i }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black)
                        .border(if (sel) 2.dp else 1.dp, if (sel) c.primary else c.outlineVariant, RoundedCornerShape(12.dp))) {
                        val img = remember(v.thumb) { v.thumb?.asImageBitmap() }
                        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.align(Alignment.TopStart).padding(4.dp).size(18.dp).clip(CircleShape)
                            .background(if (sel) c.primary else Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = if (sel) c.onPrimary else Color.White)
                        }
                    }
                    Text(caption?.invoke(v) ?: v.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (sel) c.onSurface else c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ 2. Frame */

@Composable
fun FrameStep(st: AppState, memory: PlayheadMemory, settings: Settings, panes: Panes, previewMax: androidx.compose.ui.unit.Dp?) {
    val v = st.videos.getOrNull(st.selected) ?: return
    val g = geometry(v, st.effectiveSqueeze(v), st.orientation, st.direction)
    val c = MaterialTheme.colorScheme
    val trim = st.trimFor(v)
    val tools = listOf(
        Tool("Squeeze", AppIcons.Aspect, fmtSqueeze(st.squeeze)) {
            Section(if (st.videos.size > 1) "Squeeze factor for clip ${st.selected + 1}" else "Squeeze factor", trailing = {
                Text(fmtSqueeze(st.squeeze), style = MaterialTheme.typography.titleLarge.merge(Mono), color = c.primary)
            }) {
                SqueezeChips(st)
                if (st.videos.size > 1) {
                    val allSame = st.videos.all { kotlin.math.abs(st.squeezeFor(it) - st.squeeze) < 0.001f }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Text(if (allSame) "All clips use ${fmtSqueeze(st.squeeze)}." else "Clips use different factors, e.g. for different adapters.",
                            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                        if (!allSame) TextButton(onClick = { val f = st.squeeze; st.videos.forEach { st.clipSqueeze[st.keyOf(it)] = f } }, enabled = !st.busy) {
                            Text("Apply ${fmtSqueeze(st.squeeze)} to all")
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("${g.dispW} × ${g.dispH}  displays as  ${g.outW} × ${g.outH}", style = MaterialTheme.typography.bodyMedium.merge(Mono))
                Text("${g.ratioLabel()}. Full frame kept, nothing cropped.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            existingTag(v)?.let { tag -> TagProtectionCard(st, v, tag) }
        },
        Tool("Trim", AppIcons.Scissors, trim?.let { (a, b) -> fmtDuration(b - a) } ?: "Full", active = trim != null) { TrimSection(st, v, memory) },
        Tool("Guides", AppIcons.FrameLines, guidesSummary(st.guides), active = guidesSummary(st.guides) != null) {
            Section("Guides") { GuidesPanel(st.guides, enabled = !st.busy) { st.guides = it; settings.guides = it } }
        },
        Tool("Orientation", AppIcons.Rotate, if (st.orientation == Orientation.AUTO && st.direction == Direction.AUTO) "Auto"
                else "${st.orientation.label} · ${if (g.vertical) "↕" else "↔"}",
            active = st.orientation != Orientation.AUTO || st.direction != Direction.AUTO) {
            if (st.videos.size > 1) Text("Orientation and direction apply to all ${st.videos.size} clips.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            Section("Orientation") {
                Segmented(Orientation.entries.map { it.label }, st.orientation.ordinal, enabled = !st.busy) { st.orientation = Orientation.entries[it] }
            }
            Section("Desqueeze direction") {
                Segmented(Direction.entries.map { it.label }, st.direction.ordinal, enabled = !st.busy) { st.direction = Direction.entries[it] }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(AppIcons.Rotate, null, Modifier.size(16.dp).padding(top = 1.dp), tint = c.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(directionHint(v, g, st.orientation, st.direction) + " Use Vertical when the camera or phone was turned 90° with the lens attached.",
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }
        },
        Tool("Exposure", AppIcons.Histogram, st.scope.label, active = st.scope != Scope.OFF) { ExposureSection(st) },
    )
    PreviewWithTools(panes, tools, st.frameTool, { st.frameTool = it }) {
        ClipSwitcher(st) { clip -> fmtSqueeze(st.squeezeFor(clip)) + (if (st.trimFor(clip) != null) "  · trimmed" else "") + if (existingTag(clip) != null) "  · tagged" else "" }
        key(v.uri) {
            if (st.busy) ExportingPlaceholder(g.outRatio)
            else PreviewPlayer(v, g, st.desqueezed, memory, guides = st.guides, trim = trim, exposure = st.scope, scopeLarge = st.scopeLarge,
                onScopeLarge = { st.scopeLarge = it }, scopePos = st.scopePos, onScopePos = { st.scopePos = it }, onScopeClose = { st.scope = Scope.OFF }, onStats = { st.scopeStats = it }, maxHeight = previewMax)
        }
        ViewToggle(st.desqueezed) { st.desqueezed = it }
    }
}

/** Trim before export: in and out points per clip. */
@Composable
fun TrimSection(st: AppState, v: VideoInfo, memory: PlayheadMemory) {
    val c = MaterialTheme.colorScheme
    val dur = v.durationMs.coerceAtLeast(1)
    val cur = st.clipTrim[st.keyOf(v)] ?: (0L to dur)
    fun set(a: Long, b: Long) {
        val lo = a.coerceIn(0, dur - 500); val hi = b.coerceIn(lo + 500, dur)
        st.clipTrim[st.keyOf(v)] = lo to hi
    }
    Section("Trim", trailing = {
        Text("${fmtDuration(cur.second - cur.first)} of ${fmtDuration(dur)}", style = MaterialTheme.typography.titleSmall.merge(Mono),
            color = if (st.trimFor(v) != null) Warm else c.onSurfaceVariant)
    }) {
        RangeSlider(
            value = cur.first.toFloat()..cur.second.toFloat(),
            onValueChange = { r -> set(r.start.toLong(), r.endInclusive.toLong()) },
            valueRange = 0f..dur.toFloat(), enabled = !st.busy,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmtDuration(cur.first), style = MaterialTheme.typography.labelMedium.merge(Mono))
            Spacer(Modifier.weight(1f))
            Text(fmtDuration(cur.second), style = MaterialTheme.typography.labelMedium.merge(Mono))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = { set(memory.positionMs, cur.second) }, enabled = !st.busy, shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.weight(1f)) { Text("Start here", maxLines = 1) }
            OutlinedButton(onClick = { set(cur.first, memory.positionMs) }, enabled = !st.busy, shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.weight(1f)) { Text("End here", maxLines = 1) }
            TextButton(onClick = { st.clipTrim.remove(st.keyOf(v)) }, enabled = !st.busy && st.trimFor(v) != null) { Text("Reset") }
        }
        Text("Play to the moment you want and tap Start here / End here. Re-encode cuts exactly; Lossless starts on the nearest keyframe, usually under a second earlier.",
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Exposure tools for judging log footage on the phone. */
@Composable
fun ExposureSection(st: AppState) {
    val c = MaterialTheme.colorScheme
    val icons = mapOf(Scope.OFF to AppIcons.Cross, Scope.HISTOGRAM to AppIcons.Histogram, Scope.WAVEFORM to AppIcons.Waveform,
        Scope.PARADE to AppIcons.Parade, Scope.VECTORSCOPE to AppIcons.Vectorscope, Scope.FALSE_COLOR to AppIcons.FalseColor)
    Section("Exposure") {
        // 3 × 2 grid of equal tiles: every scope one tap away, nothing squeezed.
        Scope.entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { sc ->
                    val on = st.scope == sc
                    Column(Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (on) c.primary.copy(alpha = 0.16f) else c.surfaceContainer)
                        .border(1.dp, if (on) c.primary.copy(alpha = 0.7f) else c.outlineVariant, RoundedCornerShape(14.dp))
                        .clickable { st.scope = sc }, horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Icon(icons.getValue(sc), null, Modifier.size(20.dp), tint = if (on) c.primary else c.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(sc.short, style = MaterialTheme.typography.labelMedium, color = if (on) c.onSurface else c.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
        if (st.scope == Scope.OFF) {
            Text("Scopes appear in the corner of the video, so the picture stays in view while you adjust. Tap a scope to enlarge it; × turns it off.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            return@Section
        }
        if (st.scope != Scope.FALSE_COLOR) {
            Segmented(listOf("Small", "Large"), if (st.scopeLarge) 1 else 0) { st.scopeLarge = it == 1 }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Drag the scope to move it anywhere on the video.", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { st.scopePos = androidx.compose.ui.geometry.Offset(1f, 0f) },
                    enabled = st.scopePos != androidx.compose.ui.geometry.Offset(1f, 0f)) { Text("Reset position") }
            }
            Spacer(Modifier.height(10.dp))
        }
        val s = st.scopeStats
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceContainer)
            .border(1.dp, c.outlineVariant, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s == null) Text("Reading the picture…", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            else {
                Reading("Shadows (5%)", "${s.p5.toInt()} IRE")
                Reading("Median", "${s.p50.toInt()} IRE")
                Reading("Highlights (95%)", "${s.p95.toInt()} IRE")
                Reading("Clipped (≥ 98 IRE)", "%.1f%%".format(s.clippedPct), warn = s.clippedPct > 1f)
                Reading("Crushed (≤ 2 IRE)", "%.1f%%".format(s.crushedPct), warn = s.crushedPct > 1f)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (st.scope == Scope.FALSE_COLOR) {
            ScopeMath.ZONES.filter { it.argb != 0 }.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    pair.forEach { z ->
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(Color(z.argb)))
                            Spacer(Modifier.width(8.dp))
                            Text("${z.label}  ${z.from.toInt()}–${minOf(100, z.to.toInt())} IRE", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Text(when (st.scope) {
            Scope.HISTOGRAM -> "How much of the picture sits at each brightness, black on the left, white on the right. Red, green and blue are drawn separately; the white outline is overall brightness."
            Scope.WAVEFORM -> "Brightness across the frame, left to right as in the picture: 0 IRE at the bottom, 100 at the top. Lines at 25, 50 and 75."
            Scope.PARADE -> "The waveform of red, green and blue side by side. Matching levels in a white or grey area mean a neutral white balance."
            Scope.VECTORSCOPE -> "Colour: angle is hue, distance from the centre is saturation. Squares mark 75% colour bars; skin tones fall near the beige line."
            Scope.FALSE_COLOR -> "Brightness shown as colours. Aim for mid grey (green) on a grey card and keep important highlights out of red."
            else -> ""
        }, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        st.videos.getOrNull(st.selected)?.let { v ->
            if (v.footage.gamma.isLog && st.lutId == null) Text(
                "This clip is ${v.footage.gamma.title.lowercase()}: readings are of the flat picture as shown, so highlights clip later than they look. In Look, turn a LUT on to judge the final image.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Reading(label: String, value: String, warn: Boolean = false) = Row(verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.bodyMedium.merge(Mono), color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
}

/** Double Desqueeze Protection: the clip already says it's anamorphic. */
@Composable
fun TagProtectionCard(st: AppState, v: VideoInfo, tag: Float) {
    val c = MaterialTheme.colorScheme
    val key = st.keyOf(v)
    val policy = st.tagPolicy[key]
    val picked = st.squeezeFor(v)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Warm.copy(alpha = 0.10f))
        .border(1.dp, Warm.copy(alpha = if (policy == null) 0.8f else 0.35f), RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(AppIcons.Warn, null, Modifier.size(18.dp), tint = Warm)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("This video already contains a ${fmtSqueeze(tag)} desqueeze tag.", style = MaterialTheme.typography.titleSmall)
                Text(if (policy == null) "Applying ${fmtSqueeze(picked)} on top would stretch it to ${fmtSqueeze(tag * picked)}. Choose what to do:"
                     else "Chosen: ${policy.label}. Exports as ${fmtSqueeze(st.effectiveSqueeze(v))}.",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
        TagPolicy.entries.forEach { p ->
            val sel = policy == p
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (sel) c.primary.copy(alpha = 0.12f) else c.surfaceContainer)
                .border(1.dp, if (sel) c.primary.copy(alpha = 0.6f) else c.outlineVariant, RoundedCornerShape(12.dp))
                .clickable(enabled = !st.busy) { st.tagPolicy[key] = p }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.label + when (p) {
                        TagPolicy.KEEP -> " (${fmtSqueeze(tag)})"; TagPolicy.REPLACE -> " (${fmtSqueeze(picked)})"
                        TagPolicy.FORCE -> " (${fmtSqueeze(tag * picked)})" }, style = MaterialTheme.typography.bodyMedium)
                    Text(p.detail, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                if (sel) Icon(AppIcons.Check, "Selected", Modifier.size(18.dp), tint = c.primary)
            }
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Segmented(options: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    val many = options.size > 3
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(selected = i == selected, onClick = { onSelect(i) }, enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = { if (!many) SegmentedButtonDefaults.Icon(i == selected) }) {
                Text(label, maxLines = 1, style = if (many) MaterialTheme.typography.labelMedium else LocalTextStyle.current)
            }
        }
    }
}

/* ------------------------------------------------------------------ 3. Look */

@Composable
fun LookStep(st: AppState, luts: LutManager, memory: PlayheadMemory, panes: Panes, previewMax: androidx.compose.ui.unit.Dp?) {
    val ctx = LocalContext.current
    val v = st.videos.getOrNull(st.selected) ?: return
    val g = geometry(v, st.effectiveSqueeze(v), st.orientation, st.direction)
    val cube by produceState<LutManager.CubeLut?>(null, st.lutId) {
        value = st.lutId?.let { id -> withContext(Dispatchers.IO) { try { luts.load(id) } catch (_: Exception) { null } } }
    }
    val uiScope = rememberCoroutineScope()
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) uiScope.launch {
            st.lutLoading = true
            try {
                val name = displayName(ctx, u)
                val entry = withContext(Dispatchers.IO) { luts.import(u, name) }   // big .cube files take a moment to parse
                st.lutId = entry.id; st.lutList = luts.list()
            } catch (e: Exception) { st.status = "This LUT couldn't be loaded: ${e.message}" }
            finally { st.lutLoading = false }
        }
    }
    // Reading the selected LUT for the live preview.
    val cubeLoading = st.lutId != null && cube == null

    val lutName = st.lutList.firstOrNull { it.id == st.lutId }?.name
    val tools = listOf(
        Tool("LUT", AppIcons.Gamut, lutName?.let { if (st.lutPreview) "${(st.strength * 100).toInt()}%" else "Off" } ?: "None", active = st.lutId != null) {
    Section("LUT") {
        PickerRow(null, st.lutList.firstOrNull { it.id == st.lutId }?.name ?: "No LUT",
            listOf("No LUT") + st.lutList.map { it.name } + "Import a .cube file…", enabled = !st.busy) { i ->
            when {
                i == 0 -> st.lutId = null
                i <= st.lutList.size -> st.lutId = st.lutList[i - 1].id
                else -> lutPicker.launch(arrayOf("*/*"))
            }
        }
        if (st.lutLoading) LoadingRow("Reading the LUT…", Modifier.padding(top = 10.dp))
        else if (cubeLoading) LoadingRow("Preparing the LUT for the preview…", Modifier.padding(top = 10.dp))
        AnimatedVisibility(st.lutId != null) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Segmented(listOf("LUT off", "LUT on"), if (st.lutPreview) 1 else 0) { st.lutPreview = it == 1 }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Strength", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${(st.strength * 100).toInt()}%", style = MaterialTheme.typography.titleMedium.merge(Mono), color = MaterialTheme.colorScheme.primary)
                }
                Slider(st.strength, { st.strength = it }, enabled = !st.busy)
                Row(Modifier.fillMaxWidth()) {
                    listOf(0f to "0%", 0.5f to "50%", 1f to "100%").forEachIndexed { i, (value, label) ->
                        if (i > 0) Spacer(Modifier.weight(1f))
                        Text(label, style = MaterialTheme.typography.labelMedium.merge(Mono),
                            color = if (kotlin.math.abs(st.strength - value) < 0.005f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !st.busy) { st.strength = value }
                                .padding(horizontal = 6.dp, vertical = 4.dp))
                    }
                }
                OutlinedButton(onClick = { st.compareRequest++ }, enabled = !st.busy && cube != null,
                    modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
                    Text("Before / After at full quality")
                }
                Text("What you see is what you export. The live preview plays at a lighter ~720p so it stays smooth; Before / After shows a full-quality frame with a slider. " +
                    "LUTs are only applied in Re-encode, so clips are now recommended for Re-encode.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (st.lutId == null) Text(when {
                v.footage.gamma.isConfirmedLog -> "This clip is ${v.footage.gamma.title} (confirmed by camera metadata). For a normal look, import your camera maker's official ${v.footage.gamma.title}-to-Rec.709 LUT."
                v.footage.gamma.isLog -> "Optional. ${v.footage.gamma.title}: the camera didn't confirm a log profile, so check the footage before using a log LUT. Creative 3D .cube LUTs work on anything."
                else -> "Optional. Any creative 3D .cube LUT."
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
        },
        Tool("Exposure", AppIcons.Histogram, st.scope.label, active = st.scope != Scope.OFF) { ExposureSection(st) },
    )
    PreviewWithTools(panes, tools, st.lookTool, { st.lookTool = it }) {
        ClipSwitcher(st)
        key(v.uri) {
            if (st.busy) ExportingPlaceholder(g.outRatio)
            else PreviewPlayer(v, g, st.desqueezed, memory, lut = cube, lutStrength = st.strength, lutOn = st.lutPreview,
                guides = st.guides, compareRequest = st.compareRequest, trim = st.trimFor(v), exposure = st.scope, scopeLarge = st.scopeLarge,
                onScopeLarge = { st.scopeLarge = it }, scopePos = st.scopePos, onScopePos = { st.scopePos = it }, onScopeClose = { st.scope = Scope.OFF }, onStats = { st.scopeStats = it }, maxHeight = previewMax)
        }
    }
}

/* ------------------------------------------------------------------ 4. Export */

@Composable
fun ExportStep(st: AppState, settings: Settings, exporter: Exporter, panes: Panes) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val v = st.videos.getOrNull(st.selected) ?: return
    val key = v.uri.toString()
    val deps = arrayOf<Any?>(st.clipFormat.toMap(), st.clipFill.toMap(), st.videos, st.clipSqueeze.toMap(), st.tagPolicy.toMap(), st.orientation, st.direction, st.lutId, st.codec, st.quality, st.followRecommendation, st.mode, st.clipModes.toMap(), st.formatFill, st.clipTrim.toMap())
    val recs by produceState<Map<String, Recommendation>>(emptyMap(), *deps) {
        value = withContext(Dispatchers.Default) { st.videos.associate { it.uri.toString() to recommendFor(st, exporter, it) } }
    }
    val modes = st.videos.associate { clip ->
        clip.uri.toString() to (st.clipModes[clip.uri.toString()] ?: if (st.followRecommendation) recs[clip.uri.toString()]?.mode ?: st.mode else st.mode)
    }
    val mode = modes[key] ?: st.mode
    val est by produceState<Map<String, Estimate>?>(null, *deps) {
        value = withContext(Dispatchers.Default) {
            st.videos.associate { clip ->
                clip.uri.toString() to estimate(ctx, exporter, settings, clip, geometry(clip, st.effectiveSqueeze(clip), st.orientation, st.direction),
                    modes[clip.uri.toString()] ?: st.mode, st.lengthMs(clip), st.formatOf(clip))
            }
        }
    }
    fun setMode(m: ExportMode) { st.clipModes[key] = m; st.formatSwitch = null }

    val compat by produceState<CompatReport?>(null, key, mode, *deps, st.keepHdrSetting) {
        value = withContext(Dispatchers.Default) { compatFor(st, exporter, v, mode) }
    }
    TwoPane(panes, left = {
    ClipSwitcher(st) { clip ->
        val f = st.formatOf(clip)
        if (modes[clip.uri.toString()] == ExportMode.LOSSLESS) "Lossless" + (if (f != OutFormat.ORIGINAL) " ✗ ${f.short}" else "")
        else "Re-encode" + if (f != OutFormat.ORIGINAL) " ${f.short}" else ""
    }

    recs[key]?.let { rec -> RecommendationCard(rec, mode, enabled = !st.busy) { m ->
        setMode(m)
        if (m == ExportMode.LOSSLESS && st.formatOf(v) != OutFormat.ORIGINAL) { st.clipFormat.remove(key); st.formatSwitch = null }
    } }
        ?: LinearProgressIndicator(Modifier.fillMaxWidth())


    Section(if (st.videos.size > 1) "Export method for this clip" else "Export method") {
        MethodToggle(mode, enabled = !st.busy) { setMode(it) }
        Spacer(Modifier.height(8.dp))
        Text(if (mode == ExportMode.LOSSLESS)
            "Copies your file untouched and tags its pixel aspect ratio, like setting it in DaVinci Resolve. Editors and players like Resolve, Premiere, Final Cut and VLC show it wide; a few apps and social sites ignore the tag." +
                if (st.lutId != null) " Your LUT won't be applied in Lossless." else ""
        else "Renders new pixels so every app shows it de-squeezed${if (st.lutId != null) ", with your LUT baked in" else ""}. Slower and re-compressed.",
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        if (st.videos.size > 1) Row(Modifier.padding(top = 4.dp)) {
            TextButton(onClick = { st.videos.forEach { st.clipModes[it.uri.toString()] = mode } }, enabled = !st.busy) {
                Text("Use ${if (mode == ExportMode.LOSSLESS) "Lossless" else "Re-encode"} for all")
            }
            TextButton(onClick = { st.clipModes.clear() }, enabled = !st.busy && st.clipModes.isNotEmpty()) { Text("Reset to recommended") }
        }
        AnimatedVisibility(mode == ExportMode.REENCODE) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PickerRow("Quality", st.quality.label, Quality.entries.map { it.label }, enabled = !st.busy) {
                    st.quality = Quality.entries[it]; settings.quality = st.quality }
                PickerRow("Codec", if (st.codec == Codec.HEVC) "HEVC" else "H.264", Codec.entries.map { it.label }, enabled = !st.busy) {
                    st.codec = Codec.entries[it]; settings.codec = st.codec }
            }
        }
    }

    FormatSection(st, settings, v, geometry(v, st.effectiveSqueeze(v), st.orientation, st.direction), mode, modes)
    }, right = {
    compat?.let { CompatibilityCard(it) }
    Section("Estimate") {
        val all = est
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer)
            .border(1.dp, c.outlineVariant, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val e = all?.get(key)
            if (all == null || e == null) LoadingRow("Calculating size and time…")
            else {
                if (st.videos.size > 1) Text("This clip", style = MaterialTheme.typography.labelMedium, color = c.primary)
                EstRow("Output", "${e.outW} × ${e.outH}")
                EstRow("Output size", "≈ " + fmtSize(e.bytes))
                EstRow("Processing time", fmtEta(e.seconds))
                if (mode == ExportMode.REENCODE) {
                    val full = e.scaledNote == null
                    Row(verticalAlignment = Alignment.Top) {
                        StatusIcon(if (full) Status.OK else Status.WARN)
                        Spacer(Modifier.width(8.dp))
                        Text(if (full) "This phone's encoder handles the full size." else (e.scaledNote + " Lossless keeps full resolution."),
                            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                }
                if (st.videos.size > 1) {
                    HorizontalDivider(color = c.outlineVariant)
                    val nL = modes.values.count { it == ExportMode.LOSSLESS }
                    Text("All ${st.videos.size} clips ($nL Lossless, ${st.videos.size - nL} Re-encode)", style = MaterialTheme.typography.labelMedium, color = c.primary)
                    EstRow("Total size", "≈ " + fmtSize(all.values.sumOf { it.bytes }))
                    EstRow("Total time", fmtEta(all.values.sumOf { it.seconds }))
                }
                Text("Estimates" + if (Speed.learned(ctx)) ", based on this phone's previous exports." else "; they get more accurate after your first export.",
                    style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            }
        }
    }
    AnimatedVisibility(st.results.isNotEmpty()) { Results(st.results, settings.folder) }
    })
}

/** Social-ready output frames (Re-encode): fit with black bars or crop to fill, with a live mini preview. */
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
fun FormatSection(st: AppState, settings: Settings, v: VideoInfo, g: Geometry, mode: ExportMode, modes: Map<String, ExportMode>) {
    val c = MaterialTheme.colorScheme
    val key = v.uri.toString()
    val format = st.formatOf(v)
    val fill = st.fillOf(v)

    /** Sets [f] on [clips]. A format needs new pixels, so any of them in Lossless are moved to Re-encode (with Undo). */
    fun choose(f: OutFormat, clips: List<VideoInfo>) {
        val switched = LinkedHashMap<String, Pair<ExportMode?, OutFormat>>()
        clips.forEach { clip ->
            val k = clip.uri.toString()
            val before = st.formatOf(clip)
            if (f == OutFormat.ORIGINAL) st.clipFormat.remove(k) else st.clipFormat[k] = f
            if (f != OutFormat.ORIGINAL && modes[k] == ExportMode.LOSSLESS) {
                switched[k] = st.clipModes[k] to before
                st.clipModes[k] = ExportMode.REENCODE
            }
        }
        st.formatSwitch = if (switched.isEmpty()) null else FormatSwitch(f, switched)
    }

    Section(if (st.videos.size > 1) "Format for this clip" else "Format") {
        // Icon tiles: each icon is drawn in that format's real shape.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutFormat.entries.forEach { f ->
                val (icon, sub) = when (f) {
                    OutFormat.ORIGINAL -> AppIcons.FormatWide to "Original"
                    OutFormat.YOUTUBE -> AppIcons.Format169 to "YouTube"
                    OutFormat.FEED -> AppIcons.Format45 to "Feed"
                    OutFormat.VERTICAL -> AppIcons.Format916 to "Reels"
                    OutFormat.SQUARE -> AppIcons.Format11 to "Square"
                }
                IconTile(icon, f.short, on = format == f, enabled = !st.busy, mono = f != OutFormat.ORIGINAL, sub = sub,
                    modifier = Modifier.weight(1f)) { choose(f, listOf(v)) }
            }
        }
        if (st.videos.size > 1) TextButton(onClick = { choose(format, st.videos); st.videos.forEach { st.clipFill[it.uri.toString()] = fill } },
            enabled = !st.busy, modifier = Modifier.padding(top = 2.dp)) { Text("Use ${format.short} for all clips") }

        // Amber notice: we changed the export method for them, and say why (with Undo).
        st.formatSwitch?.let { sw ->
            if (sw.previous.isNotEmpty()) Notice(
                color = Warm, icon = AppIcons.Warn,
                title = if (sw.previous.size == 1) "Switched to Re-encode" else "Switched ${sw.previous.size} clips to Re-encode",
                text = "Only Re-encode can reframe into ${sw.format.short}; Lossless always keeps the original wide frame. " +
                    "Re-encode makes new pixels, so 10-bit log becomes 8-bit.",
                action = "Undo",
            ) {
                sw.previous.forEach { (k, prev) ->
                    if (prev.first == null) st.clipModes.remove(k) else st.clipModes[k] = prev.first!!
                    if (prev.second == OutFormat.ORIGINAL) st.clipFormat.remove(k) else st.clipFormat[k] = prev.second
                }
                st.formatSwitch = null
            }
        }

        // Red conflict: Lossless is selected AND a format is set (e.g. after "Use Lossless for all").
        if (mode == ExportMode.LOSSLESS && format != OutFormat.ORIGINAL) Notice(
            color = c.error, icon = AppIcons.Cross,
            title = "${format.short} can't be made in Lossless",
            text = "Lossless copies the original pixels, so this clip would export in its original wide frame, not ${format.short}.",
            action = "Switch to Re-encode", secondary = "Use Wide",
            onSecondary = { st.clipFormat.remove(key); st.formatSwitch = null },
        ) { st.clipModes[key] = ExportMode.REENCODE; st.formatSwitch = null }

        Spacer(Modifier.height(8.dp))
        if (format == OutFormat.ORIGINAL) {
            Text("Keeps the full de-squeezed frame. Pick 16:9, 4:5, 9:16 or 1:1 to make a file ready for YouTube, Instagram, Reels, Shorts or TikTok (uses Re-encode).",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        } else {
            val size = formatSize(format, g)!!
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconTile(AppIcons.FitBars, "Fit", on = !fill, enabled = !st.busy, sub = "Whole picture, black bars",
                    modifier = Modifier.weight(1f)) { st.clipFill[key] = false; st.formatFill = false; settings.formatFill = false }
                IconTile(AppIcons.FillCrop, "Fill", on = fill, enabled = !st.busy, sub = "Fills the frame, crops sides",
                    modifier = Modifier.weight(1f)) { st.clipFill[key] = true; st.formatFill = true; settings.formatFill = true }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormatPreview(v, g, format, fill, Modifier.width(if (format.aspect >= 1f) 140.dp else 92.dp))
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${size.first} × ${size.second}", style = MaterialTheme.typography.titleMedium.merge(Mono))
                    Text("For ${format.where}.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    Text(if (fill) "Crops the sides to fill the frame: ${fillKeepsPercent(g, format)}% of the picture is kept."
                        else "The whole wide picture fits, with black bars.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }
        }
    }
}

/** Coloured notice with an icon, explanation and one or two actions. Amber = we changed something; red = conflict. */
@Composable
fun Notice(color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, action: String,
           secondary: String? = null, onSecondary: () -> Unit = {}, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.12f))
        .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, null, Modifier.size(18.dp).padding(top = 1.dp), tint = color)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = color)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (secondary != null) TextButton(onClick = onSecondary) { Text(secondary, color = color) }
            TextButton(onClick = onAction) { Text(action, color = color) }
        }
    }
}

/** Mini mock-up of the chosen frame with the clip's thumbnail fitted or cropped, at its de-squeezed shape. */
@Composable
fun FormatPreview(v: VideoInfo, g: Geometry, f: OutFormat, fill: Boolean, modifier: Modifier = Modifier) {
    val img = remember(v.thumb) { v.thumb?.asImageBitmap() }
    val c = MaterialTheme.colorScheme
    androidx.compose.foundation.Canvas(modifier.aspectRatio(f.aspect).clip(RoundedCornerShape(8.dp)).background(Color.Black)
        .border(1.dp, c.outlineVariant, RoundedCornerShape(8.dp))) {
        val W = size.width; val H = size.height; val pic = g.outRatio; val frame = W / H
        val (w, h) = if ((pic > frame) != fill) W to W / pic else H * pic to H
        val dst = androidx.compose.ui.unit.IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
        val off = androidx.compose.ui.unit.IntOffset(((W - w) / 2).toInt(), ((H - h) / 2).toInt())
        if (img != null) drawImage(img, dstOffset = off, dstSize = dst)
        else drawRect(c.primary.copy(alpha = 0.35f), topLeft = Offset(off.x.toFloat(), off.y.toFloat()), size = androidx.compose.ui.geometry.Size(w, h))
    }
}

@Composable
fun CompatibilityCard(r: CompatReport) {
    val c = MaterialTheme.colorScheme
    val tint = when (r.worst) { Status.OK -> Color(0xFF5BD68A); Status.WARN -> Warm; Status.NO -> c.error }
    Section("Compatibility", trailing = { StatusIcon(r.worst, 20) }) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer)
            .border(1.dp, if (r.worst == Status.OK) c.outlineVariant else tint.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            r.rows.filter { it.first != "Result" }.forEach { (k, value) ->
                Column {
                    Text(k, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyMedium.merge(Mono))
                }
            }
            HorizontalDivider(color = c.outlineVariant)
            Text("Result", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
            if (r.issues.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIcon(Status.OK); Spacer(Modifier.width(8.dp)); Text("Ready. No problems found.", style = MaterialTheme.typography.bodyMedium)
            }
            r.issues.sortedByDescending { it.first.ordinal }.forEach { (status, text) ->
                Row(verticalAlignment = Alignment.Top) {
                    StatusIcon(status); Spacer(Modifier.width(8.dp)); Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Shown when Export is pressed and some clips have warnings: nothing starts until you confirm. */
@Composable
fun PreflightDialog(st: AppState, onExport: () -> Unit) {
    val list = st.preflight ?: return
    AlertDialog(
        onDismissRequest = { st.preflight = null },
        icon = { Icon(AppIcons.Warn, null, tint = Warm) },
        title = { Text("Check before exporting") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                list.forEach { (name, r) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        r.issues.sortedByDescending { it.first.ordinal }.forEach { (status, text) ->
                            Row(verticalAlignment = Alignment.Top) { StatusIcon(status, 16); Spacer(Modifier.width(6.dp)); Text(text, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { st.preflight = null; onExport() }) { Text("Export anyway") } },
        dismissButton = { TextButton(onClick = { st.preflight = null; st.step = Step.Export }) { Text("Review") } },
    )
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

/** A small spinner with a short explanation, for anything that takes a moment. */
@Composable
fun LoadingRow(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Determinate progress while clips are being read (thumbnails, log/HDR analysis). */
@Composable
fun ImportProgress(st: AppState) {
    val imp = st.importing ?: return
    val (done, total) = imp
    val p by androidx.compose.animation.core.animateFloatAsState(if (total > 0) done / total.toFloat() else 0f, label = "import")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(if (total > 1) "Reading clip ${minOf(done + 1, total)} of $total…" else "Reading clip…", style = MaterialTheme.typography.bodyMedium)
        }
        LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)))
        Text("Checking codec, color and log/HDR, and sampling a few frames.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
