package com.desqueeze.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Adds photos to the batch (reads only their size, so it's quick). */
suspend fun addPhotos(ctx: android.content.Context, st: AppState, uris: List<android.net.Uri>) {
    if (uris.isEmpty()) return
    st.photoLoading = true
    try {
        val items = withContext(Dispatchers.IO) { uris.mapNotNull { PhotoExporter.probe(ctx, it) } }
        st.photos = (st.photos + items).distinctBy { it.uri.toString() }
        st.photoResults = emptyList()
    } finally { st.photoLoading = false }
}

/** De-squeeze anamorphic photos: pick, set squeeze / crop / LUT / colour once for the batch, save as JPEG. */
@Composable
fun PhotosScreen(st: AppState, settings: Settings, luts: LutManager, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch { addPhotos(ctx, st, uris) }
    }
    val pick = { picker.launch(arrayOf("image/*")) }
    val look = st.photoLook
    val enabled = !st.photoBusy

    Column(Modifier.fillMaxSize()) {
        TopBar("Anamorphic photos", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (st.photos.isEmpty()) {
                Text("De-squeeze photos taken through an anamorphic adapter. DNG (RAW) files stay RAW: they're copied untouched and " +
                    "tagged so Lightroom, Camera Raw and Photoshop open them de-squeezed. Other photos have no pixel-aspect tag that " +
                    "viewers respect, so they're stretched to their real shape (more pixels, nothing thrown away) and saved as " +
                    "JPEG at maximum quality or lossless PNG. Your originals are untouched.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                Button(onClick = pick, enabled = !st.photoLoading, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                    Text("Choose photos")
                }
                if (st.photoLoading) LoadingRow("Reading photos…")
                Text("DNG, JPEG, HEIC, PNG and WebP. Tip: in your gallery, select photos, tap Share and choose De-Squeeze.",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            } else PhotoEditor(st, settings, luts, pick, enabled)
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.PhotoEditor(st: AppState, settings: Settings, luts: LutManager, pick: () -> Unit, enabled: Boolean) {
            val ctx = LocalContext.current
            val c = MaterialTheme.colorScheme
            val look = st.photoLook
            val sel = st.photoSel.coerceIn(0, st.photos.size - 1)
            val item = st.photos[sel]

            // Preview of the selected photo with everything applied (a ~1600 px version, rebuilt as settings settle).
            val lutCube by produceState<LutManager.CubeLut?>(null, look.lutId) {
                value = look.lutId?.let { id -> withContext(Dispatchers.IO) { runCatching { luts.load(id) }.getOrNull() } }
            }
            val preview by produceState<ImageBitmap?>(null, item.uri, look, lutCube) {
                delay(150)
                value = withContext(Dispatchers.Default) {
                    runCatching {
                        val src = PhotoExporter.decode(ctx, item.uri, look, maxSide = 1600)
                        val (w, h) = PhotoExporter.outputSize(src.width, src.height, look)
                        val cube = LookBuilder.build(lutCube, look.lutStrength, look.color)
                        PhotoExporter.render(src, look, cube, w, h).asImageBitmap()
                    }.getOrNull()
                }
            }
            val (outW, outH) = PhotoExporter.outputSize(item.width, item.height, look)
            val ratio = preview?.let { it.width.toFloat() / it.height } ?: (outW.toFloat() / outH)
            Box(Modifier.fillMaxWidth().aspectRatio(maxOf(ratio, 0.8f)).clip(RoundedCornerShape(20.dp)).background(Color.Black),
                contentAlignment = Alignment.Center) {
                preview?.let { Image(it, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ?: CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
            Text("${item.name}\n${item.width} × ${item.height}  →  about $outW × $outH", style = MaterialTheme.typography.bodySmall.merge(Mono),
                color = c.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)

            // The batch: tap to preview, × to remove.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(st.photos, key = { _, p -> p.uri.toString() }) { i, p ->
                    val thumb by produceState<ImageBitmap?>(null, p.uri) {
                        value = withContext(Dispatchers.IO) {
                            runCatching { ctx.contentResolver.loadThumbnail(p.uri, android.util.Size(240, 240), null).asImageBitmap() }.getOrNull()
                        }
                    }
                    Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceContainerHigh)
                        .border(if (i == sel) 2.dp else 1.dp, if (i == sel) c.primary else c.outlineVariant, RoundedCornerShape(12.dp))
                        .clickable(enabled = enabled) { st.photoSel = i }) {
                        thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                            .clickable(enabled = enabled) {
                                st.photos = st.photos.filterIndexed { j, _ -> j != i }
                                st.photoSel = st.photoSel.coerceAtMost((st.photos.size - 1).coerceAtLeast(0))
                            }, contentAlignment = Alignment.Center) {
                            Icon(AppIcons.Cross, "Remove", Modifier.size(11.dp), tint = Color.White)
                        }
                    }
                }
            }
            Row {
                TextButton(onClick = pick, enabled = enabled && !st.photoLoading) { Text("Add photos") }
                TextButton(onClick = { st.photos = emptyList(); st.photoResults = emptyList() }, enabled = enabled) { Text("Clear") }
                if (st.photoLoading) LoadingRow("Reading…")
            }

            Section("Squeeze", trailing = { Text(fmtSqueeze(look.squeeze), style = MaterialTheme.typography.titleLarge.merge(Mono), color = c.primary) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(PRESETS) { _, p ->
                        FilterChip(selected = kotlin.math.abs(p - look.squeeze) < 0.001f, enabled = enabled, onClick = { st.photoSqueeze = p },
                            label = { Text(fmtSqueeze(p), style = LocalTextStyle.current.merge(Mono)) })
                    }
                }
                Slider(look.squeeze, { st.photoSqueeze = Math.round(it * 100) / 100f }, valueRange = 1f..3f, enabled = enabled)
                Segmented(listOf("Stretch horizontally", "Stretch vertically"), if (look.vertical) 1 else 0, enabled = enabled) { st.photoVertical = it == 1 }
                Text("Horizontal for photos taken the way the adapter is mounted (usually). Vertical if the adapter was turned, " +
                    "e.g. for portrait photos with the squeeze along the long side.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            Section("Edge crop", trailing = { Text("%.1f%%".format(look.crop * 100), style = MaterialTheme.typography.titleMedium.merge(Mono), color = c.primary) }) {
                CropSlider(look.crop, enabled) { st.photoCrop = it }
                Text("Cuts the same share from every edge to hide the adapter's dark or soft corners.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            Section("LUT") {
                val names = st.lutList.map { it.name }
                PickerRow(null, st.lutList.firstOrNull { it.id == look.lutId }?.name ?: "No LUT", listOf("No LUT") + names, enabled = enabled) { i ->
                    st.photoLutId = if (i == 0) null else st.lutList[i - 1].id
                }
                if (look.lutId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Strength", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("${(look.lutStrength * 100).toInt()}%", style = MaterialTheme.typography.titleMedium.merge(Mono), color = c.primary)
                    }
                    Slider(look.lutStrength, { st.photoStrength = it }, enabled = enabled)
                }
                if (st.lutList.isEmpty()) Text("Import .cube LUTs in Settings → LUT library, or from the Look step.",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            Section("Colour") {
                ColorSliders(look.color, enabled) { st.photoColor = it }
                TextButton(onClick = { st.photoColor = ColorAdjust() }, enabled = enabled && !look.color.isNeutral) { Text("Reset all") }
            }

            Section("Output") {
                val dng = st.photos.count { it.isDng }; val other = st.photos.size - dng
                val rendered = other > 0 || (dng > 0 && look.hasEdits)
                if (dng > 0) {
                    val raw = !look.hasEdits
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(if (raw) AppIcons.Check else AppIcons.Warn, null, Modifier.size(18.dp).padding(top = 2.dp), tint = if (raw) c.primary else Warm)
                        Spacer(Modifier.width(8.dp))
                        Text(if (raw) "$dng DNG file${if (dng == 1) "" else "s"}: saved as DNG, RAW data untouched, with a de-squeeze tag (DefaultScale). " +
                                "Lightroom, Adobe Camera Raw and Photoshop open them de-squeezed; the gallery and most phone apps show the built-in preview, still squeezed."
                            else "$dng DNG file${if (dng == 1) "" else "s"}: the LUT, colour or crop need new pixels, so they're rendered to " +
                                "${st.photoFormat.ext.uppercase()} from this phone's RAW decode. The RAW data isn't kept, and some phones can only decode a small " +
                                "preview. Remove the edits (LUT, colour, crop) to keep them as full-size RAW.",
                            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                }
                if (rendered) {
                    Segmented(PhotoFormat.entries.map { it.label }, st.photoFormat.ordinal, enabled = enabled) { st.photoFormat = PhotoFormat.entries[it] }
                    Text(if (st.photoFormat == PhotoFormat.JPEG) "JPEG at quality 100: visually lossless and widely supported."
                         else "PNG keeps every pixel exactly, but files are several times larger and some apps don't keep camera details (EXIF).",
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }

            Button(onClick = {
                val list = st.photos; val l = look; val app = ctx.applicationContext; val fmt = st.photoFormat
                st.photoBusy = true; st.photoDone = 0; st.photoResults = emptyList()
                ExportController.scope.launch {
                    val log = mutableListOf<String>()
                    val exporter = PhotoExporter(app, settings, luts)
                    for (p in list) {
                        log += try {
                            val r = withContext(Dispatchers.IO) { exporter.export(p, l, fmt) }
                            "✓  ${r.name}  (${r.width} × ${r.height})" + (r.note?.let { "\n    $it" } ?: "")
                        } catch (e: Throwable) { "✗  ${p.name}: ${e.message ?: e.javaClass.simpleName}" }
                        st.photoDone++
                    }
                    st.photoResults = log; st.photoBusy = false
                }
            }, enabled = enabled && st.photos.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                Text(if (st.photoBusy) "Saving ${st.photoDone + 1} of ${st.photos.size}…"
                     else "Save ${st.photos.size} de-squeezed photo${if (st.photos.size == 1) "" else "s"}")
            }
            if (st.photoBusy) LinearProgressIndicator(progress = { st.photoDone / st.photos.size.coerceAtLeast(1).toFloat() }, Modifier.fillMaxWidth())
            if (st.photoResults.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    st.photoResults.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("✗")) c.error else c.onSurface) }
                }
                Text("Saved to Pictures/${settings.folder}. " + if (settings.preserveMeta) "Date and location were kept from each photo."
                    else "Date and location were left out (Settings → Keep recording date & location).",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
}
