package com.desqueeze.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Photos screen's pages, like the video steps. */
enum class PhotoStep(val label: String) { Photos("Photos"), Edit("Edit"), Export("Save") }

/** Preview images of one photo: with the look, without it, and as shot. */
class PhotoPreview(val after: ImageBitmap, val plain: ImageBitmap, val original: ImageBitmap)

/** Adds photos to the batch, with a progress bar (reads only their headers, so it's quick). */
suspend fun addPhotos(ctx: android.content.Context, st: AppState, uris: List<android.net.Uri>) {
    if (uris.isEmpty()) return
    st.photoImporting = 0 to uris.size
    try {
        val items = mutableListOf<PhotoItem>()
        for ((i, u) in uris.withIndex()) {
            withContext(Dispatchers.IO) { PhotoExporter.probe(ctx, u) }?.let { items += it }
            st.photoImporting = (i + 1) to uris.size
        }
        val had = st.photos.isNotEmpty()
        st.photos = (st.photos + items).distinctBy { it.uri.toString() }
        if (items.any { it.isDng } && st.photos.none { it.isDng && it !in items }) st.photoFormat = PhotoFormat.DNG
        if (!had && st.photos.isNotEmpty()) { st.photoSel = 0; st.photoStep = PhotoStep.Edit }
    } finally { st.photoImporting = null }
}

/** Short description of a photo's settings, for lists. */
private fun summary(item: PhotoItem, l: PhotoLook, lutName: (String) -> String?) = listOfNotNull(
    fmtSqueeze(l.squeeze) + if (l.vertical) " ↕" else " ↔",
    if (l.crop > 0f) "crop %.1f%%".format(l.crop * 100) else null,
    l.lutId?.let { lutName(it) }?.let { "LUT ${(l.lutStrength * 100).toInt()}%" },
    if (!l.color.isNeutral) "colour" else null,
).joinToString(" · ")

/** Saves [list] one by one with per-photo status and an overall bar, like the video export. */
private fun startPhotoExport(ctx: android.content.Context, st: AppState, settings: Settings, luts: LutManager, list: List<PhotoItem>) {
    if (st.photoBusy || list.isEmpty()) return
    val fmt = st.photoFormat; val app = ctx.applicationContext
    st.photoBusy = true; st.photoCancel = false; st.photoProgress = 0f; st.photoRunId++; st.photoResults = emptyList()
    st.photoRuns.clear()
    list.forEach { p ->
        val out = if (p.isDng && fmt == PhotoFormat.DNG) "DNG" else if (fmt == PhotoFormat.PNG) "PNG" else "JPEG"
        st.photoRuns += ClipRun(p.name, ExportMode.REENCODE, waitLabel = out)
    }
    ExportController.scope.launch {
        val exporter = PhotoExporter(app, settings, luts)
        val notes = mutableListOf<String>()
        for ((i, p) in list.withIndex()) {
            if (st.photoCancel) { st.photoRuns[i] = st.photoRuns[i].copy(state = RunState.FAILED, note = "Cancelled"); continue }
            st.photoRuns[i] = st.photoRuns[i].copy(state = RunState.RUNNING, pct = 50)
            try {
                val r = withContext(Dispatchers.IO) { exporter.export(p, st.lookOf(p), fmt) }
                st.photoRuns[i] = st.photoRuns[i].copy(state = RunState.DONE, pct = 100, note = "${r.width} × ${r.height}")
                notes += "✓  ${r.name}" + (r.note?.let { "\n    $it" } ?: "")
            } catch (e: Throwable) {
                st.photoRuns[i] = st.photoRuns[i].copy(state = RunState.FAILED, note = "Failed")
                notes += "✗  ${p.name}: ${e.message ?: e.javaClass.simpleName}"
            }
            st.photoProgress = (i + 1f) / list.size
        }
        st.photoResults = notes; st.photoBusy = false
    }
}

/** De-squeeze anamorphic photos in three pages: pick, edit each photo, save. */
@Composable
fun PhotosScreen(st: AppState, settings: Settings, luts: LutManager, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch { addPhotos(ctx, st, uris) }
    }
    val pick = { picker.launch(arrayOf("image/*")) }
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    if (st.photos.isEmpty() && st.photoStep != PhotoStep.Photos) st.photoStep = PhotoStep.Photos

    Column(Modifier.fillMaxSize()) {
        TopBar("Anamorphic photos", onBack)
        PhotoStepHeader(st, Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(6.dp))
        FlareLine(Modifier.padding(horizontal = 20.dp), alpha = 0.6f)
        Box(Modifier.weight(1f)) {
            AnimatedContent(st.photoStep, label = "photoStep", transitionSpec = {
                smoothSlide(targetState.ordinal > initialState.ordinal, 12)
            }) { step ->
                when (step) {
                    PhotoStep.Photos -> PhotosPage(st, luts, pick)
                    PhotoStep.Edit -> PhotoEditPage(st, luts, Panes(wide))
                    PhotoStep.Export -> PhotoSavePage(st, settings, luts)
                }
            }
        }
        PhotoBottomBar(st, settings, luts)
    }
}

@Composable
private fun PhotoStepHeader(st: AppState, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PhotoStep.entries.forEachIndexed { i, s ->
            val current = s == st.photoStep; val done = s.ordinal < st.photoStep.ordinal
            val enabled = !st.photoBusy && (s == PhotoStep.Photos || st.photos.isNotEmpty())
            Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled) { st.photoStep = s }.padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(when { current -> c.primary; done -> c.primary.copy(alpha = 0.18f); else -> c.surfaceContainerHigh }),
                    contentAlignment = Alignment.Center) {
                    if (done) Icon(AppIcons.Check, null, Modifier.size(14.dp), tint = c.primary)
                    else Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = if (current) c.onPrimary else c.onSurfaceVariant)
                }
                Spacer(Modifier.width(6.dp))
                Text(s.label, style = MaterialTheme.typography.labelLarge, color = if (current) c.onSurface else c.onSurfaceVariant)
            }
            if (i < PhotoStep.entries.size - 1) Box(Modifier.weight(1f).height(1.dp).background(if (done) c.primary.copy(alpha = 0.5f) else c.outlineVariant))
        }
    }
}

/** Back / Next, or the save progress while saving (like the video export bar). */
@Composable
private fun PhotoBottomBar(st: AppState, settings: Settings, luts: LutManager) {
    val ctx = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
            if (st.photoBusy) PhotoProgressPanel(st)
            else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (st.photoStep != PhotoStep.Photos) OutlinedButton(onClick = { st.photoStep = PhotoStep.entries[st.photoStep.ordinal - 1] },
                    Modifier.height(56.dp), shape = RoundedCornerShape(16.dp)) { Text("Back") }
                val last = st.photoStep == PhotoStep.Export
                Button(onClick = {
                    if (!last) st.photoStep = PhotoStep.entries[st.photoStep.ordinal + 1]
                    else startPhotoExport(ctx, st, settings, luts, st.photos)
                }, enabled = st.photos.isNotEmpty() && st.photoImporting == null, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(16.dp)) {
                    Text(if (!last) "Next: ${PhotoStep.entries[st.photoStep.ordinal + 1].label}"
                         else "Save ${st.photos.size} photo${if (st.photos.size == 1) "" else "s"}", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun PhotoProgressPanel(st: AppState) {
    val c = MaterialTheme.colorScheme
    key(st.photoRunId) {
        val p by animateFloatAsState(st.photoProgress, tween(250), label = "photoProgress")
        val done = st.photoRuns.count { it.state == RunState.DONE }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(if (st.photoRuns.size > 1) "Saving ${st.photoRuns.size} photos · $done done" else "Saving photo",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.titleSmall.merge(Mono))
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
        Spacer(Modifier.height(10.dp))
        RunList(st.photoRuns, maxRows = 3)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { st.photoCancel = true }, enabled = !st.photoCancel,
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Text(if (st.photoCancel) "Stopping after this photo…" else "Cancel")
        }
        Text("Saved photos stay saved; cancelling skips the rest.", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp))
    }
}

/* ------------------------------------------------------------------ 1. Photos */

@Composable
private fun PhotosPage(st: AppState, luts: LutManager, pick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PhotoImportProgress(st)
        if (st.photos.isEmpty()) {
            Text("De-squeeze photos taken through an anamorphic adapter. Each photo can have its own squeeze, crop, LUT and colour. " +
                "DNG (RAW) files can stay RAW: copied untouched and tagged so Lightroom, Camera Raw and Photoshop open them de-squeezed. " +
                "Other photos are stretched to their real shape and saved as JPEG or lossless PNG. Your originals are untouched.",
                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            Button(onClick = pick, enabled = st.photoImporting == null, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                Text("Choose photos")
            }
            Text("DNG, JPEG, HEIC, PNG and WebP. Tip: in your gallery, select photos, tap Share and choose De-Squeeze.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        } else {
            Section("${st.photos.size} photo${if (st.photos.size == 1) "" else "s"}", trailing = {
                TextButton(onClick = pick, enabled = !st.photoBusy && st.photoImporting == null) { Text("Add photos") }
            }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    st.photos.forEachIndexed { i, p ->
                        PhotoRow(st, luts, p, selected = i == st.photoSel, onClick = { st.photoSel = i; st.photoStep = PhotoStep.Edit },
                            onRemove = if (st.photoBusy) null else ({
                                st.photoLooks.remove(p.uri.toString())
                                st.photos = st.photos.filterIndexed { j, _ -> j != i }
                                st.photoSel = st.photoSel.coerceAtMost((st.photos.size - 1).coerceAtLeast(0))
                            }))
                    }
                }
            }
            TextButton(onClick = { st.photos = emptyList(); st.photoLooks.clear(); st.photoResults = emptyList(); st.photoRuns.clear() },
                enabled = !st.photoBusy) { Text("Clear all") }
            Text("Tap a photo to edit it. Each photo keeps its own settings; tools have “Apply to all” when you want the same for every photo.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun PhotoImportProgress(st: AppState) {
    val imp = st.photoImporting ?: return
    val (done, total) = imp
    val p by animateFloatAsState(if (total > 0) done / total.toFloat() else 0f, label = "photoImport")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(if (total > 1) "Reading photo ${minOf(done + 1, total)} of $total…" else "Reading photo…", style = MaterialTheme.typography.bodyMedium)
        }
        LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun PhotoThumb(p: PhotoItem, modifier: Modifier) {
    val ctx = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, p.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(p.uri, android.util.Size(320, 320), null).asImageBitmap() }.getOrNull()
        }
    }
    Box(modifier.background(Color.Black)) { thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
}

@Composable
private fun PhotoRow(st: AppState, luts: LutManager, p: PhotoItem, selected: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)?) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer)
        .border(if (selected) 1.5.dp else 1.dp, if (selected) c.primary else c.outlineVariant, RoundedCornerShape(16.dp))
        .clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        PhotoThumb(p, Modifier.size(width = 96.dp, height = 64.dp).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${p.width} × ${p.height}${if (p.isDng) " · RAW" else ""}", style = MaterialTheme.typography.bodySmall.merge(Mono), color = c.onSurfaceVariant)
            Text(summary(p, st.lookOf(p)) { id -> st.lutList.firstOrNull { it.id == id }?.name }, style = MaterialTheme.typography.bodySmall,
                color = c.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRemove != null) IconButton(onClick = onRemove) { Icon(AppIcons.Cross, "Remove", Modifier.size(16.dp), tint = c.onSurfaceVariant) }
    }
}

/* ------------------------------------------------------------------ 2. Edit */

@Composable
private fun PhotoSwitcher(st: AppState) {
    if (st.photos.size < 2) return
    val c = MaterialTheme.colorScheme
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(st.photos, key = { _, p -> p.uri.toString() }) { i, p ->
            val sel = i == st.photoSel
            Box(Modifier.size(width = 64.dp, height = 44.dp).clip(RoundedCornerShape(10.dp))
                .border(if (sel) 2.dp else 1.dp, if (sel) c.primary else c.outlineVariant, RoundedCornerShape(10.dp))
                .clickable(enabled = !st.photoBusy) { st.photoSel = i }) {
                PhotoThumb(p, Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.TopStart).padding(3.dp).size(16.dp).clip(CircleShape)
                    .background(if (sel) c.primary else Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = if (sel) c.onPrimary else Color.White)
                }
            }
        }
    }
}

@Composable
private fun PhotoEditPage(st: AppState, luts: LutManager, panes: Panes) {
    val item = st.photos.getOrNull(st.photoSel.coerceIn(0, (st.photos.size - 1).coerceAtLeast(0))) ?: return
    val c = MaterialTheme.colorScheme
    val look = st.photoLook
    val multi = st.photos.size > 1
    val n = st.photoSel + 1
    @Composable fun applyAll(label: String, change: (PhotoLook) -> PhotoLook) {
        if (multi) TextButton(onClick = { st.applyPhotoToAll(change) }, enabled = !st.photoBusy) { Text(label) }
    }
    val lutName = look.lutId?.let { id -> st.lutList.firstOrNull { it.id == id }?.name }
    val tools = listOf(
        Tool("Squeeze", AppIcons.Aspect, fmtSqueeze(look.squeeze) + if (look.vertical) " ↕" else " ↔") {
            Section(if (multi) "Squeeze for photo $n" else "Squeeze", trailing = {
                Text(fmtSqueeze(look.squeeze), style = MaterialTheme.typography.titleLarge.merge(Mono), color = c.primary)
            }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(PRESETS) { _, p ->
                        FilterChip(selected = kotlin.math.abs(p - look.squeeze) < 0.001f, enabled = !st.photoBusy,
                            onClick = { st.photoLook = look.copy(squeeze = p) },
                            label = { Text(fmtSqueeze(p), style = LocalTextStyle.current.merge(Mono)) })
                    }
                }
                Slider(look.squeeze, { st.photoLook = look.copy(squeeze = Math.round(it * 100) / 100f) }, valueRange = 1f..3f, enabled = !st.photoBusy)
                Segmented(listOf("Stretch horizontally ↔", "Stretch vertically ↕"), if (look.vertical) 1 else 0, enabled = !st.photoBusy) {
                    st.photoLook = look.copy(vertical = it == 1) }
                Text("Horizontal for photos taken the way the adapter is mounted (usually). Vertical if the adapter was turned, " +
                    "e.g. portrait photos with the squeeze along the long side. The arrows on the preview show the direction.",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                applyAll("Apply ${fmtSqueeze(look.squeeze)} ${if (look.vertical) "↕" else "↔"} to all photos") { it.copy(squeeze = look.squeeze, vertical = look.vertical) }
            }
        },
        Tool("Crop", AppIcons.FillCrop, if (look.crop > 0f) "%.1f%%".format(look.crop * 100) else "None", active = look.crop > 0f) {
            Section(if (multi) "Edge crop for photo $n" else "Edge crop", trailing = {
                Text("%.1f%%".format(look.crop * 100), style = MaterialTheme.typography.titleLarge.merge(Mono), color = c.primary)
            }) {
                CropSlider(look.crop, !st.photoBusy) { st.photoLook = look.copy(crop = it) }
                Text("Cuts the same share from every edge to hide the adapter's dark or soft corners, keeping the shape. " +
                    "For DNG saved as RAW it becomes the DNG default crop (lossless).", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
                applyAll("Apply to all photos") { it.copy(crop = look.crop) }
            }
        },
        Tool("LUT", AppIcons.Gamut, lutName?.let { "${(look.lutStrength * 100).toInt()}%" } ?: "None", active = look.lutId != null) {
            Section(if (multi) "LUT for photo $n" else "LUT") {
                PickerRow(null, lutName ?: "No LUT", listOf("No LUT") + st.lutList.map { it.name }, enabled = !st.photoBusy) { i ->
                    st.photoLook = look.copy(lutId = if (i == 0) null else st.lutList[i - 1].id)
                }
                if (look.lutId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                        Text("Strength", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("${(look.lutStrength * 100).toInt()}%", style = MaterialTheme.typography.titleMedium.merge(Mono), color = c.primary)
                    }
                    Slider(look.lutStrength, { st.photoLook = look.copy(lutStrength = it) }, enabled = !st.photoBusy)
                }
                if (st.lutList.isEmpty()) Text("Import .cube LUTs in Settings → LUT library, or from the video Look step.",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                if (item.isDng && look.lutId != null) Text("Not applied when this DNG is saved as RAW (pick JPEG or PNG on the Save page).",
                    style = MaterialTheme.typography.bodySmall, color = Warm, modifier = Modifier.padding(top = 6.dp))
                applyAll("Apply to all photos") { it.copy(lutId = look.lutId, lutStrength = look.lutStrength) }
            }
        },
        Tool("Colour", AppIcons.Sliders, look.color.summary() ?: "None", active = !look.color.isNeutral) {
            Section(if (multi) "Colour for photo $n" else "Colour") {
                ColorSliders(look.color, !st.photoBusy) { st.photoLook = look.copy(color = it) }
                Row {
                    TextButton(onClick = { st.photoLook = look.copy(color = ColorAdjust()) }, enabled = !st.photoBusy && !look.color.isNeutral) { Text("Reset all") }
                    applyAll("Apply to all photos") { it.copy(color = look.color) }
                }
                if (item.isDng && !look.color.isNeutral) Text("Not applied when this DNG is saved as RAW (pick JPEG or PNG on the Save page).",
                    style = MaterialTheme.typography.bodySmall, color = Warm)
            }
        },
    )
    PreviewWithTools(st, panes, tools, st.photoTool, { st.photoTool = it }) { mod ->
        PhotoSwitcher(st)
        key(item.uri) { PhotoPreviewBox(st, luts, item, look, mod, bounded = !panes.wide) }
    }
}

/**
 * The photo with its edits, sized like the video preview: room for both shapes, so press-and-hold (as shot)
 * changes the picture only along the stretch axis. Dashed outline + arrows show the stretch.
 */
@Composable
private fun PhotoPreviewBox(st: AppState, luts: LutManager, item: PhotoItem, look: PhotoLook, modifier: Modifier, bounded: Boolean) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val lutCube by produceState<LutManager.CubeLut?>(null, look.lutId) {
        value = look.lutId?.let { id -> withContext(Dispatchers.IO) { runCatching { luts.load(id) }.getOrNull() } }
    }
    // The last preview stays on screen while a new one is made (a thin bar shows it's working).
    var preview by remember { mutableStateOf<PhotoPreview?>(null) }
    var rendering by remember { mutableStateOf(true) }
    LaunchedEffect(look, lutCube) {
        rendering = true
        if (preview != null) delay(150)   // let sliders settle
        val p = withContext(Dispatchers.Default) {
            runCatching {
                val src = PhotoExporter.decode(ctx, item.uri, look.copy(crop = 0f, squeeze = 1f), maxSide = 1600)
                val (w, h) = PhotoExporter.outputSize(src.width, src.height, look)
                val cube = LookBuilder.build(lutCube, look.lutStrength, look.color)
                val plain = PhotoExporter.render(src, look, null, w, h)
                val graded = if (cube != null) PhotoExporter.render(src, look, cube, w, h) else plain
                PhotoPreview(graded.asImageBitmap(), plain.asImageBitmap(), src.asImageBitmap())
            }.getOrNull()
        }
        if (p != null) preview = p
        rendering = false
    }
    val (outW, outH) = PhotoExporter.outputSize(item.width, item.height, look)
    var holding by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }
    val shown = preview
    val outRatio = shown?.after?.let { it.width.toFloat() / it.height } ?: (outW.toFloat() / outH)
    val origRatio = shown?.original?.let { it.width.toFloat() / it.height } ?: (item.width.toFloat() / item.height)
    val ratio by animateFloatAsState(if (holding) origRatio else outRatio, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), label = "photoRatio")
    val scope = rememberCoroutineScope()
    val canCompare = look.lutId != null || !look.color.isNeutral
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StretchFrame(origRatio, outRatio, ratio, look.vertical, (if (bounded) Modifier.weight(1f, fill = false) else Modifier).fillMaxWidth()) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        val j = scope.launch { delay(200); holding = true }
                        tryAwaitRelease(); j.cancel(); holding = false
                    })
                }) {
                Box(Modifier.matchParentSize()) {
                    val img = shown?.let { if (holding) it.original else it.after }
                    if (img != null) Image(img, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                }
                StretchOutline(look.vertical, look.squeeze, show = !holding, Modifier.matchParentSize())
                Row(Modifier.align(Alignment.TopStart).padding(10.dp)) {
                    Pill(if (holding) "As shot (holding)" else "De-squeezed ${fmtSqueeze(look.squeeze)} ${if (look.vertical) "↕" else "↔"}", accent = !holding)
                }
                Box(Modifier.align(Alignment.Center)) { if (shown == null) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp)) }
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    if (rendering && shown != null) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = c.primary, trackColor = Color.Transparent)
                }
                Box(Modifier.matchParentSize()) {
                    val s2 = shown
                    if (comparing && canCompare && s2 != null && !holding) CompareOverlay(s2.plain, s2.after, ratio) { comparing = false }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${item.width} × ${item.height}${if (item.isDng) " RAW" else ""} → $outW × $outH · hold to see it as shot",
                Modifier.weight(1f), style = MaterialTheme.typography.labelSmall.merge(Mono), color = c.onSurfaceVariant, maxLines = 2)
            TextButton(onClick = { comparing = !comparing }, enabled = canCompare && shown != null) { Text(if (comparing) "Done" else "Before / After") }
        }
    }
}

/**
 * Lays [content] out at [current] aspect inside room for both [squeezed] and [stretched]: a vertical stretch keeps
 * the width (the picture grows taller), a horizontal one keeps the height (it grows wider).
 */
@Composable
private fun StretchFrame(squeezed: Float, stretched: Float, current: Float, vertical: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { m, c ->
        val maxW = c.maxWidth.toFloat(); val maxH = if (c.hasBoundedHeight) c.maxHeight.toFloat() else maxW * 1.5f
        val tallest = minOf(squeezed, stretched, current); val widest = maxOf(squeezed, stretched, current)
        val (_, envH, w, h) = if (vertical) {
            val ew = minOf(maxW, maxH * tallest); listOf(ew, ew / tallest, ew, ew / current)
        } else {
            val eh = minOf(maxH, maxW / widest); listOf(eh * widest, eh, eh * current, eh)
        }
        val p = m.first().measure(Constraints.fixed(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)))
        val lw = c.maxWidth; val lh = c.constrainHeight(envH.toInt())
        layout(lw, lh) { p.place((lw - p.width) / 2, (lh - p.height) / 2) }
    }
}

/* ------------------------------------------------------------------ 3. Save */

@Composable
private fun PhotoSavePage(st: AppState, settings: Settings, luts: LutManager) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val dng = st.photos.filter { it.isDng }; val other = st.photos.size - dng.size
        Section("Save as") {
            val formats = if (dng.isNotEmpty()) PhotoFormat.entries else PhotoFormat.entries - PhotoFormat.DNG
            val fmt = st.photoFormat.takeIf { it in formats } ?: PhotoFormat.JPEG
            Segmented(formats.map { it.label }, formats.indexOf(fmt), enabled = !st.photoBusy) { st.photoFormat = formats[it] }
            Spacer(Modifier.height(8.dp))
            @Composable fun line(ok: Boolean, text: String) = Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 6.dp)) {
                Icon(if (ok) AppIcons.Check else AppIcons.Warn, null, Modifier.size(18.dp).padding(top = 2.dp), tint = if (ok) c.primary else Warm)
                Spacer(Modifier.width(8.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            fun n(k: Int, what: String) = "$k $what${if (k == 1) "" else "s"}"
            if (fmt == PhotoFormat.DNG) {
                line(true, "${n(dng.size, "DNG file")} stay RAW, untouched: the de-squeeze and edge crop are written as DNG tags, so Lightroom, " +
                    "Adobe Camera Raw and Photoshop open them at full resolution, de-squeezed. A de-squeezed preview is embedded for the gallery " +
                    "(as sharp as this phone can decode the RAW).")
                val graded = dng.count { val l = st.lookOf(it); l.lutId != null || !l.color.isNeutral }
                if (graded > 0) line(false, "LUT and colour aren't applied to RAW (${n(graded, "DNG")} have them). Apply them in your RAW editor, or pick JPEG or PNG.")
                if (other > 0) line(false, "${n(other, "photo")} that aren't RAW will be saved as JPEG.")
            } else {
                line(true, if (fmt == PhotoFormat.JPEG) "JPEG at quality 100: visually lossless and widely supported."
                    else "PNG keeps every pixel exactly, but files are several times larger and some apps don't keep camera details (EXIF).")
                if (dng.isNotEmpty()) line(false, "${n(dng.size, "DNG file")} will be rendered from this phone's RAW decode, and the RAW data isn't kept. " +
                    "Some phones can only decode a small preview. Pick DNG (RAW) to keep full resolution.")
            }
            line(true, "Saved to Pictures/${settings.folder}. " + if (settings.preserveMeta) "Date and location are kept from each photo."
                else "Date and location are left out (Settings → Keep recording date & location).")
        }
        Section("Photos") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                st.photos.forEachIndexed { i, p ->
                    val run = st.photoRuns.firstOrNull { it.name == p.name }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceContainer).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        PhotoThumb(p, Modifier.size(width = 64.dp, height = 44.dp).clip(RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(summary(p, st.lookOf(p)) { id -> st.lutList.firstOrNull { it.id == id }?.name } +
                                (run?.let { r -> when (r.state) { RunState.DONE -> " · saved ${r.note}"; RunState.FAILED -> " · ${r.note}"; RunState.RUNNING -> " · saving…"; else -> "" } } ?: ""),
                                style = MaterialTheme.typography.labelSmall, color = if (run?.state == RunState.FAILED) c.error else c.onSurfaceVariant, maxLines = 2)
                        }
                        TextButton(onClick = { st.photoSel = i; st.photoStep = PhotoStep.Edit }, enabled = !st.photoBusy) { Text("Edit") }
                        TextButton(onClick = { startPhotoExport(ctx, st, settings, luts, listOf(p)) }, enabled = !st.photoBusy) { Text("Save") }
                    }
                }
            }
        }
        if (st.photoResults.isNotEmpty() && !st.photoBusy) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                st.photoResults.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("✗")) c.error else c.onSurface) }
            }
        }
    }
}
